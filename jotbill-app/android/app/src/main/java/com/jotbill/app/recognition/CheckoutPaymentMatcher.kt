package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.ai.AiVisionResult
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import org.json.JSONObject

private const val PAYMENT_MATCH_WINDOW_MILLIS = 5 * 60 * 1000L
internal const val CHECKOUT_PAYMENT_MATCH_WINDOW_MILLIS = PAYMENT_MATCH_WINDOW_MILLIS
private const val PAYMENT_CONFIRMED_STATUS = "PAYMENT_CONFIRMED"
private const val WAITING_PAYMENT_STATUS = "WAITING_PAYMENT"
private const val WAITING_ENRICHMENT_STATUS = "WAITING_ENRICHMENT"
private const val NEEDS_CONFIRMATION_STATUS = "NEEDS_CONFIRMATION"
private val TERMINAL_SESSION_STATUSES = setOf(
    "COMPLETED",
    "CANCELLED",
    "FAILED",
    "EXPIRED"
)

internal sealed class CheckoutPaymentMatchResult {
    data class Accepted(
        val sessionId: String,
        val status: String,
        val candidateId: String? = null,
        val evidenceId: String? = null,
        val matched: Boolean = false
    ) : CheckoutPaymentMatchResult()

    data class Rejected(
        val sessionId: String?,
        val reason: String
    ) : CheckoutPaymentMatchResult()
}

/**
 * 阶段四 T4：支付证据归属会话的解析决策。
 *
 * 并发会话无法区分时输出 Ambiguous，不强行合并；
 * 由待确认中心（T6）创建并发候选，由用户选择归属。
 */
internal sealed class PaymentSessionResolution {
    /** 订单号或金额+时间窗口唯一匹配的会话。byOrderId 表示是否通过订单号直接命中。 */
    data class Matched(
        val reference: CheckoutSnapshotReference,
        val byOrderId: Boolean
    ) : PaymentSessionResolution()

    /** 多个并发会话无法区分，进入待确认由用户选择。 */
    data class Ambiguous(val sessionIds: List<String>) : PaymentSessionResolution()

    /** 无匹配会话。 */
    object NoMatch : PaymentSessionResolution()
}

/**
 * 将支付证据和视觉候选限制在同一个 sessionId 内关联。
 * 本类只写会话、证据和候选，不创建正式账单，也不更新账户余额。
 */
internal class CheckoutPaymentMatcher(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    /** AI 请求开始前先建立会话，支付通知可以在 AI 返回前安全落到同一 sessionId。 */
    suspend fun ensureSession(
        reference: CheckoutSnapshotReference,
        requestKey: CheckoutRequestKey,
        aiRequestId: String? = null
    ): CheckoutSessionEntity = database.withTransaction {
        upsertSession(
            reference = reference,
            initialStatus = "AI_PENDING",
            requestKey = requestKey,
            aiRequestId = aiRequestId,
            now = nowMillis()
        )
    }

    /** AI 已消费内存快照后，从未过期的唯一会话恢复原始 sessionId。 */
    suspend fun findReferenceForPayment(
        shoppingPackage: String,
        pageFingerprint: String,
        at: Long = nowMillis()
    ): CheckoutSnapshotReference? {
        val sessions = database.checkoutSessionDao().findActiveByPage(
            shoppingPackage = shoppingPackage,
            pageFingerprint = pageFingerprint,
            at = at
        )
        val session = sessions.singleOrNull() ?: return null
        val pageRole = CheckoutPageRole.fromStorageValue(session.pageRole)
        if (pageRole != CheckoutPageRole.CHECKOUT) {
            return null
        }
        return CheckoutSnapshotReference(
            snapshotId = session.snapshotId.orEmpty(),
            sessionId = session.id,
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = pageFingerprint,
            capturedAt = session.createdAt,
            expiresAt = session.expiresAt,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    /**
     * 阶段四 T4：按订单号优先、金额+时间窗口+会话未占用综合解析支付证据应归属的会话。
     *
     * 匹配规则（文档 3.5 节）：
     * - 订单号一致时优先级最高，直接匹配；
     * - 没有订单号时按金额+时间窗口+会话未被占用综合匹配；
     * - 仅凭"同金额 + 五分钟内"不能自动合并两笔连续支付；
     * - 并发会话无法区分时输出 Ambiguous，不强行合并，由待确认中心让用户选择。
     */
    suspend fun resolveSessionForPayment(
        paymentPackage: String,
        amountMinor: Long,
        occurredAt: Long,
        orderId: String? = null,
        at: Long = nowMillis()
    ): PaymentSessionResolution {
        if (paymentPackage !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES || amountMinor <= 0L) {
            return PaymentSessionResolution.NoMatch
        }

        // 第一优先级：订单号一致直接匹配
        if (!orderId.isNullOrBlank()) {
            val byOrderId = database.transactionCandidateDao()
                .findActiveByOrderId(orderId, at)
            val sessionIds = byOrderId.map { it.sessionId }.distinct()
            return when {
                sessionIds.size == 1 -> {
                    val session = database.checkoutSessionDao().getById(sessionIds.single())
                    if (session != null && !session.isClosedOrExpired(at)) {
                        PaymentSessionResolution.Matched(
                            reference = referenceForSession(session),
                            byOrderId = true
                        )
                    } else {
                        PaymentSessionResolution.NoMatch
                    }
                }
                sessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(sessionIds)
                else -> PaymentSessionResolution.NoMatch
            }
        }

        // 第二优先级：金额覆盖 + 时间窗口 + 会话未被占用
        val candidates = database.transactionCandidateDao()
            .findUnoccupiedByAmountCovering(amountMinor, at)
        val withinWindow = candidates.filter { candidate ->
            val candidateTime = candidate.aiOccurredAt
                ?: database.checkoutSessionDao().getById(candidate.sessionId)?.createdAt
            candidateTime != null &&
                abs(occurredAt - candidateTime) <= PAYMENT_MATCH_WINDOW_MILLIS
        }
        val sessionIds = withinWindow.map { it.sessionId }.distinct()
        return when {
            sessionIds.size == 1 -> {
                val session = database.checkoutSessionDao().getById(sessionIds.single())
                if (session != null && !session.isClosedOrExpired(at)) {
                    PaymentSessionResolution.Matched(
                        reference = referenceForSession(session),
                        byOrderId = false
                    )
                } else {
                    PaymentSessionResolution.NoMatch
                }
            }
            sessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(sessionIds)
            else -> PaymentSessionResolution.NoMatch
        }
    }

    /** 从会话实体构建快照引用，供证据落库复用。 */
    private fun referenceForSession(session: CheckoutSessionEntity): CheckoutSnapshotReference {
        val shoppingPackage = session.shoppingPackage.orEmpty()
        val pageRole = CheckoutPageRole.fromStorageValue(session.pageRole)
        return CheckoutSnapshotReference(
            snapshotId = session.snapshotId.orEmpty(),
            sessionId = session.id,
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = session.pageFingerprint.orEmpty(),
            capturedAt = session.createdAt,
            expiresAt = session.expiresAt,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    /** 记录支付通知；重复来源事件只保留一条证据。 */
    suspend fun recordPaymentEvidence(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal
    ): CheckoutPaymentMatchResult {
        val validationError = validatePayment(reference, signal)
        if (validationError != null) {
            return CheckoutPaymentMatchResult.Rejected(reference.sessionId, validationError)
        }

        return database.withTransaction {
            val now = nowMillis()
            val sourceEventKey = sourceEventKey(signal)
            val existingEvidence = database.paymentEvidenceDao()
                .getBySourceEventKey(sourceEventKey)
            if (existingEvidence != null) {
                if (existingEvidence.sessionId != reference.sessionId) {
                    return@withTransaction CheckoutPaymentMatchResult.Rejected(
                        reference.sessionId,
                        "PAYMENT_EVIDENCE_ALREADY_BOUND"
                    )
                }
                val existingSession = database.checkoutSessionDao().getById(reference.sessionId)
                    ?: return@withTransaction CheckoutPaymentMatchResult.Rejected(
                        reference.sessionId,
                        "PAYMENT_SESSION_MISSING"
                    )
                val existingCandidate = database.transactionCandidateDao()
                    .getBySession(reference.sessionId)
                    .singleOrNull()
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = existingSession.status,
                    candidateId = existingSession.candidateId ?: existingCandidate?.id,
                    evidenceId = existingEvidence.id,
                    matched = existingCandidate?.matched == true
                )
            }
            val existingSession = database.checkoutSessionDao().getById(reference.sessionId)
            if (existingSession != null && existingSession.isClosedOrExpired(now)) {
                return@withTransaction CheckoutPaymentMatchResult.Rejected(
                    reference.sessionId,
                    "PAYMENT_SESSION_CLOSED"
                )
            }
            var session = upsertSession(
                reference = reference,
                initialStatus = PAYMENT_CONFIRMED_STATUS,
                requestKey = null,
                aiRequestId = null,
                now = now
            )
            session = advancePaymentConfirmed(session, now)
            val evidenceId = stableId(
                "payment",
                sourceEventKey
            )
            val evidence = PaymentEvidenceEntity(
                id = evidenceId,
                sessionId = reference.sessionId,
                sourceType = "NOTIFICATION",
                amountMinor = signal.amountMinor,
                merchant = signal.merchant,
                platform = signal.paymentPackage,
                orderId = signal.orderId,
                paymentMethod = null,
                paymentStatus = "SUCCESS",
                occurredAt = signal.occurredAt,
                rawTextHash = stableId(
                    "payment-source",
                    signal.paymentPackage,
                    signal.amountMinor.toString(),
                    signal.occurredAt.toString(),
                    signal.merchant,
                    signal.orderId,
                    sourceEventKey
                ),
                sourceEventKey = sourceEventKey,
                capturedAt = now
            )
            database.paymentEvidenceDao().insertIfAbsent(evidence)

            val candidate = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            if (candidate == null) {
                session = advanceStatus(session, WAITING_ENRICHMENT_STATUS, now)
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    evidenceId = evidenceId
                )
            }

            val storedEvidence = existingEvidence
                ?: database.paymentEvidenceDao().getById(evidenceId)
                ?: evidence
            val evaluation = evaluate(candidate, storedEvidence)
            val updatedCandidate = candidateAfterPayment(candidate, storedEvidence, evaluation)
            if (updatedCandidate != candidate) {
                database.transactionCandidateDao().update(updatedCandidate)
            }
            session = session.copy(
                candidateId = updatedCandidate.id,
                updatedAt = now
            )
            database.checkoutSessionDao().update(session)
            session = if (evaluation.conflictReason == null) {
                advanceStatus(session, WAITING_ENRICHMENT_STATUS, now)
            } else {
                advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now)
            }
            CheckoutPaymentMatchResult.Accepted(
                sessionId = reference.sessionId,
                status = session.status,
                candidateId = updatedCandidate.id,
                evidenceId = storedEvidence.id,
                matched = updatedCandidate.matched
            )
        }
    }

    /** 保存 AI 结果；AI 先到时只建立 WAITING_PAYMENT 候选。 */
    suspend fun recordAiResult(result: AiVisionResult): CheckoutPaymentMatchResult {
        val metadata = result.metadata
        val reference = metadata.reference()
        if (reference.sessionId.isBlank()) {
            return CheckoutPaymentMatchResult.Rejected(null, "SESSION_ID_MISSING")
        }

        return database.withTransaction {
            val now = nowMillis()
            val requestId = result.requestIdOrNull()
            val existingSession = database.checkoutSessionDao().getById(reference.sessionId)
            val existingCandidate = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            val sameRequestKey = existingSession?.checkoutRequestKey == metadata.requestKey.value
            val duplicateResult = existingSession != null && when (result) {
                is AiVisionResult.Succeeded ->
                    (requestId != null && requestId == existingSession.aiRequestId) ||
                        (sameRequestKey && existingCandidate != null)

                is AiVisionResult.Failed ->
                    (requestId != null && requestId == existingSession.aiRequestId) ||
                        (sameRequestKey && existingCandidate != null) ||
                        (sameRequestKey && (existingSession.journeyState ?: existingSession.status) in setOf(
                            WAITING_PAYMENT_STATUS,
                            NEEDS_CONFIRMATION_STATUS
                        ))
            }
            if (duplicateResult) {
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = existingSession.status,
                    candidateId = existingSession.candidateId ?: existingCandidate?.id,
                    matched = existingCandidate?.matched == true
                )
            }
            if (existingSession?.isClosedOrExpired(now) == true) {
                return@withTransaction CheckoutPaymentMatchResult.Rejected(
                    reference.sessionId,
                    "AI_SESSION_CLOSED"
                )
            }
            var session = upsertSession(
                reference = reference,
                initialStatus = WAITING_PAYMENT_STATUS,
                requestKey = metadata.requestKey,
                aiRequestId = requestId,
                now = now
            )
            when (result) {
                is AiVisionResult.Failed -> {
                    val evidenceExists = database.paymentEvidenceDao()
                        .getBySession(reference.sessionId)
                        .any { it.paymentStatus == "SUCCESS" }
                    session = advanceStatus(
                        session,
                        if (evidenceExists) NEEDS_CONFIRMATION_STATUS else WAITING_PAYMENT_STATUS,
                        now
                    )
                    CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = session.status
                    )
                }

                is AiVisionResult.Succeeded -> {
                    val fields = result.fields
                    val occurredAt = fields.occurredAt?.let(::parseTimestamp)
                    val candidateId = candidateIdFor(reference.sessionId)
                    val existing = existingCandidate
                    val candidate = candidateFromAi(
                        id = existing?.id ?: candidateId,
                        reference = reference,
                        result = result,
                        occurredAt = occurredAt,
                        existing = existing,
                        now = now
                    )
                    val persistedCandidate = if (existing == null) {
                        database.transactionCandidateDao().insertIfAbsent(candidate)
                        database.transactionCandidateDao().getById(candidate.id) ?: candidate
                    } else {
                        if (candidate != existing) {
                            database.transactionCandidateDao().update(candidate)
                        }
                        candidate
                    }

                    val evidences = database.paymentEvidenceDao()
                        .getBySession(reference.sessionId)
                        .filter { it.paymentStatus == "SUCCESS" }
                    if (evidences.isEmpty()) {
                        session = session.copy(
                            candidateId = persistedCandidate.id,
                            updatedAt = now
                        )
                        database.checkoutSessionDao().update(session)
                        session = advanceStatus(session, WAITING_PAYMENT_STATUS, now)
                        return@withTransaction CheckoutPaymentMatchResult.Accepted(
                            sessionId = reference.sessionId,
                            status = session.status,
                            candidateId = persistedCandidate.id
                        )
                    }

                    val selection = selectEvidence(persistedCandidate.aiOrderId, evidences)
                    if (selection.evidence == null) {
                        val conflicted = persistedCandidate.copy(
                            matched = false,
                            conflictReason = selection.conflictReason,
                            appliedRules = rulesJson(
                                "sessionId" to reference.sessionId,
                                "decision" to "NEEDS_CONFIRMATION",
                                "reason" to selection.conflictReason
                            ),
                            version = persistedCandidate.version + 1
                        )
                        database.transactionCandidateDao().update(conflicted)
                        session = session.copy(
                            candidateId = conflicted.id,
                            updatedAt = now
                        )
                        database.checkoutSessionDao().update(session)
                        session = advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now)
                        return@withTransaction CheckoutPaymentMatchResult.Accepted(
                            sessionId = reference.sessionId,
                            status = session.status,
                            candidateId = conflicted.id,
                            matched = false
                        )
                    }

                    val evaluation = evaluate(
                        candidate = persistedCandidate,
                        evidence = selection.evidence,
                        aiOccurredAt = occurredAt
                    )
                    val updatedCandidate = candidateAfterPayment(
                        candidate = persistedCandidate,
                        evidence = selection.evidence,
                        evaluation = evaluation
                    )
                    if (updatedCandidate != persistedCandidate) {
                        database.transactionCandidateDao().update(updatedCandidate)
                    }
                    session = session.copy(
                        candidateId = updatedCandidate.id,
                        updatedAt = now
                    )
                    database.checkoutSessionDao().update(session)
                    session = advanceStatus(
                        session,
                        if (evaluation.conflictReason == null) {
                            WAITING_ENRICHMENT_STATUS
                        } else {
                            NEEDS_CONFIRMATION_STATUS
                        },
                        now
                    )
                    CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = session.status,
                        candidateId = updatedCandidate.id,
                        evidenceId = selection.evidence.id,
                        matched = updatedCandidate.matched
                    )
                }
            }
        }
    }

    private suspend fun upsertSession(
        reference: CheckoutSnapshotReference,
        initialStatus: String,
        requestKey: CheckoutRequestKey?,
        aiRequestId: String?,
        now: Long
    ): CheckoutSessionEntity {
        val dao = database.checkoutSessionDao()
        val existing = dao.getById(reference.sessionId)
        if (existing == null) {
            val journeyState = CheckoutJourneyState.fromStorageValue(initialStatus)
            val created = CheckoutSessionEntity(
                id = reference.sessionId,
                status = journeyState.toPublicSessionStatus().wireValue,
                journeyState = journeyState.wireValue,
                shoppingPackage = reference.shoppingPackage,
                pageRole = reference.pageRole.storageValue,
                pageFingerprint = reference.pageFingerprint,
                snapshotId = reference.snapshotId,
                checkoutRequestKey = requestKey?.value,
                aiRequestId = aiRequestId,
                candidateId = null,
                createdAt = reference.capturedAt,
                updatedAt = now,
                expiresAt = sessionExpiresAt(reference)
            )
            if (dao.insertIfAbsent(created) != -1L) {
                return created
            }
            val raced = dao.getById(reference.sessionId)
                ?: throw IllegalStateException("结算会话插入后无法读取")
            return mergeSession(raced, reference, requestKey, aiRequestId, now)
        }

        return mergeSession(existing, reference, requestKey, aiRequestId, now)
    }

    private suspend fun mergeSession(
        existing: CheckoutSessionEntity,
        reference: CheckoutSnapshotReference,
        requestKey: CheckoutRequestKey?,
        aiRequestId: String?,
        now: Long
    ): CheckoutSessionEntity {
        val updated = existing.copy(
            shoppingPackage = existing.shoppingPackage ?: reference.shoppingPackage,
            pageRole = existing.pageRole ?: reference.pageRole.storageValue,
            pageFingerprint = existing.pageFingerprint ?: reference.pageFingerprint,
            snapshotId = existing.snapshotId ?: reference.snapshotId,
            checkoutRequestKey = existing.checkoutRequestKey ?: requestKey?.value,
            aiRequestId = aiRequestId ?: existing.aiRequestId,
            updatedAt = now,
            expiresAt = maxOf(existing.expiresAt, sessionExpiresAt(reference))
        )
        if (updated != existing) {
            database.checkoutSessionDao().update(updated)
        }
        return updated
    }

    private suspend fun advanceStatus(
        session: CheckoutSessionEntity,
        target: String,
        now: Long
    ): CheckoutSessionEntity {
        val journeyValue = session.journeyState ?: session.status
        if (journeyValue == target) {
            return session
        }
        val currentState = CheckoutJourneyState.fromStorageValue(journeyValue)
        val targetState = CheckoutJourneyState.fromStorageValue(target)
        if (!currentState.canTransitionTo(targetState)) {
            return session
        }
        val updated = session.copy(
            status = targetState.toPublicSessionStatus().wireValue,
            journeyState = targetState.wireValue,
            updatedAt = now
        )
        database.checkoutSessionDao().update(updated)
        return updated
    }

    private suspend fun advancePaymentConfirmed(
        session: CheckoutSessionEntity,
        now: Long
    ): CheckoutSessionEntity {
        val journeyValue = session.journeyState ?: session.status
        val state = CheckoutJourneyState.fromStorageValue(journeyValue)
        return if (
            state == CheckoutJourneyState.PAYMENT_CONFIRMED ||
            state == CheckoutJourneyState.WAITING_ENRICHMENT ||
            state == CheckoutJourneyState.NEEDS_CONFIRMATION ||
            state == CheckoutJourneyState.AUTO_COMMITTED
        ) {
            session
        } else {
            advanceStatus(session, PAYMENT_CONFIRMED_STATUS, now)
        }
    }

    private fun candidateFromAi(
        id: String,
        reference: CheckoutSnapshotReference,
        result: AiVisionResult.Succeeded,
        occurredAt: Long?,
        existing: TransactionCandidateEntity?,
        now: Long
    ): TransactionCandidateEntity {
        val fields = result.fields
        val candidate = (existing ?: TransactionCandidateEntity(
            id = id,
            sessionId = reference.sessionId,
            amountMinor = fields.amountMinor,
            platform = fields.platform,
            merchant = fields.merchant,
            productDescription = fields.productDescription,
            evidenceAmountMinor = null,
            evidenceMerchant = null,
            evidenceOrderId = null,
            aiOrderId = fields.orderId,
            aiOccurredAt = occurredAt,
            matched = false,
            conflictReason = null,
            appliedRules = "{}",
            createdAt = now,
            version = 1
        )).copy(
            amountMinor = if (existing?.matched == true) existing.amountMinor else fields.amountMinor,
            platform = fields.platform ?: existing?.platform,
            merchant = fields.merchant ?: existing?.merchant,
            productDescription = fields.productDescription ?: existing?.productDescription,
            aiOrderId = fields.orderId ?: existing?.aiOrderId,
            aiOccurredAt = occurredAt ?: existing?.aiOccurredAt,
            conflictReason = if (existing?.matched == true) existing.conflictReason else null,
            appliedRules = rulesJson(
                "sessionId" to reference.sessionId,
                "decision" to "WAITING_PAYMENT",
                "aiRequestId" to result.requestId,
                "aiConfidence" to fields.confidence
            ),
            version = if (existing == null) 1 else existing.version + 1
        )
        return candidate
    }

    private fun candidateAfterPayment(
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        evaluation: PaymentEvaluation
    ): TransactionCandidateEntity = candidate.copy(
        amountMinor = if (evaluation.conflictReason == null) {
            evidence.amountMinor
        } else {
            candidate.amountMinor
        },
        evidenceAmountMinor = evidence.amountMinor,
        evidenceMerchant = evidence.merchant,
        evidenceOrderId = evidence.orderId,
        matched = evaluation.conflictReason == null,
        conflictReason = evaluation.conflictReason,
        appliedRules = rulesJson(
            "decision" to if (evaluation.conflictReason == null) "MATCHED" else "NEEDS_CONFIRMATION",
            "amountRule" to evaluation.amountRule,
            "orderRule" to evaluation.orderRule,
            "timeRule" to evaluation.timeRule,
            "paymentPackage" to evidence.platform
        ),
        version = candidate.version + 1
    )

    private fun evaluate(
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        aiOccurredAt: Long? = candidate.aiOccurredAt
    ): PaymentEvaluation {
        val conflicts = mutableListOf<String>()
        val amountRule = when {
            evidence.amountMinor == null || evidence.amountMinor <= 0L -> {
                conflicts += "PAYMENT_AMOUNT_MISSING"
                "INVALID"
            }
            candidate.amountMinor == null || candidate.amountMinor <= 0L -> {
                conflicts += "AI_AMOUNT_MISSING"
                "INVALID"
            }
            candidate.amountMinor < evidence.amountMinor -> {
                conflicts += "AI_AMOUNT_LOWER_THAN_PAYMENT"
                "PAYMENT_EXCEEDS_AI"
            }
            else -> "AI_AMOUNT_COVERS_PAYMENT"
        }
        val orderRule = when {
            candidate.aiOrderId.isNullOrBlank() || evidence.orderId.isNullOrBlank() -> "ORDER_ID_PARTIAL"
            candidate.aiOrderId == evidence.orderId -> "ORDER_ID_EXACT"
            else -> {
                conflicts += "ORDER_ID_MISMATCH"
                "ORDER_ID_CONFLICT"
            }
        }
        val timeRule = when {
            evidence.occurredAt == null || aiOccurredAt == null -> "TIME_PARTIAL"
            abs(evidence.occurredAt - aiOccurredAt) <= PAYMENT_MATCH_WINDOW_MILLIS -> "TIME_WITHIN_WINDOW"
            else -> {
                conflicts += "TIME_WINDOW_MISMATCH"
                "TIME_OUTSIDE_WINDOW"
            }
        }
        if (evidence.platform !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES) {
            conflicts += "PAYMENT_PACKAGE_UNSUPPORTED"
        }
        return PaymentEvaluation(
            conflictReason = conflicts.takeIf { it.isNotEmpty() }?.joinToString(","),
            amountRule = amountRule,
            orderRule = orderRule,
            timeRule = timeRule
        )
    }

    private fun selectEvidence(
        aiOrderId: String?,
        evidences: List<PaymentEvidenceEntity>
    ): EvidenceSelection {
        if (evidences.isEmpty()) {
            return EvidenceSelection(null, "PAYMENT_EVIDENCE_MISSING")
        }
        if (!aiOrderId.isNullOrBlank()) {
            val exact = evidences.filter { it.orderId == aiOrderId }
            if (exact.size == 1) {
                return EvidenceSelection(exact.single(), null)
            }
            if (exact.size > 1) {
                return EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
            }
        }
        return if (evidences.size == 1) {
            EvidenceSelection(evidences.single(), null)
        } else {
            EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        }
    }

    private fun validatePayment(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal
    ): String? {
        if (
            reference.sessionId.isBlank() ||
            reference.shoppingPackage.isBlank() ||
            reference.pageFingerprint.isBlank() ||
            reference.pageRole != CheckoutPageRole.CHECKOUT
        ) {
            return "CHECKOUT_REFERENCE_INVALID"
        }
        if (!signal.isValid() || signal.paymentPackage !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES) {
            return "PAYMENT_SIGNAL_INVALID"
        }
        if (reference.capturedAt <= 0L ||
            abs(signal.occurredAt - reference.capturedAt) > PAYMENT_MATCH_WINDOW_MILLIS
        ) {
            return "PAYMENT_TIME_OUTSIDE_WINDOW"
        }
        return null
    }

    private fun sourceEventKey(signal: PaymentNotificationFallbackSignal): String =
        signal.sourceEventKey?.takeIf { it.isNotBlank() }
            ?: stableId(
                "payment-source-event",
                signal.paymentPackage,
                signal.amountMinor.toString(),
                signal.occurredAt.toString(),
                signal.merchant,
                signal.orderId
            )

    private fun sessionExpiresAt(reference: CheckoutSnapshotReference): Long = maxOf(
        reference.expiresAt,
        reference.capturedAt + PAYMENT_MATCH_WINDOW_MILLIS
    )

    private fun candidateIdFor(sessionId: String): String = stableId("candidate", sessionId)

    private fun parseTimestamp(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()

    private fun stableId(prefix: String, vararg values: String?): String {
        val payload = values.joinToString("|") { it.orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun rulesJson(vararg values: Pair<String, Any?>): String = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }.toString()

    private data class PaymentEvaluation(
        val conflictReason: String?,
        val amountRule: String,
        val orderRule: String,
        val timeRule: String
    )

    private data class EvidenceSelection(
        val evidence: PaymentEvidenceEntity?,
        val conflictReason: String?
    )
}

private fun CheckoutSessionEntity.isClosedOrExpired(now: Long): Boolean =
    status in TERMINAL_SESSION_STATUSES || expiresAt <= now

private fun AiVisionResult.requestIdOrNull(): String? = when (this) {
    is AiVisionResult.Succeeded -> requestId
    is AiVisionResult.Failed -> requestId
}

private fun AiVisionResult.metadataReference(): CheckoutSnapshotReference = metadata.reference()

private fun com.jotbill.app.ai.AiVisionRequestMetadata.reference(): CheckoutSnapshotReference =
    CheckoutSnapshotReference(
        snapshotId = snapshotId,
        sessionId = sessionId,
        slotId = slotId,
        shoppingPackage = shoppingPackage,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = pageFingerprint,
        capturedAt = capturedAt,
        expiresAt = snapshotExpiresAt,
        candidateRevision = candidateRevision,
        sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
    )

// 合并后 CheckoutAccessibilityService 也需要通过 reference() 获取快照引用，
// 因此从 private 提升为 internal，供同模块内的调用方使用。
internal fun CheckoutSnapshot.reference(): CheckoutSnapshotReference =
    CheckoutSnapshotReference(
        snapshotId = snapshotId,
        sessionId = sessionId,
        slotId = slotId,
        shoppingPackage = shoppingPackage,
        pageRole = pageRole,
        pageFingerprint = pageFingerprint,
        capturedAt = capturedAt,
        expiresAt = expiresAt,
        candidateRevision = candidateRevision,
        sourceReason = sourceReason
    )
