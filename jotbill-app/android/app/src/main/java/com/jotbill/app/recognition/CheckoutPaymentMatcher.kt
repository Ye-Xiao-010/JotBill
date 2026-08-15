package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.ai.AiVisionResult
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
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
private const val AI_PENDING_STATUS = "AI_PENDING"
private const val WAITING_PAYMENT_STATUS = "WAITING_PAYMENT"
private const val WAITING_ENRICHMENT_STATUS = "WAITING_ENRICHMENT"
private const val NEEDS_CONFIRMATION_STATUS = "NEEDS_CONFIRMATION"
private const val AUTO_COMMITTED_STATUS = "AUTO_COMMITTED"
private const val COMPLETED_STATUS = "COMPLETED"
private const val AUTO_COMMIT_OPERATOR = "auto_commit"
private const val AI_RECOGNITION_FAILED_REASON = "AI_RECOGNITION_FAILED"
private val TERMINAL_SESSION_STATUSES = setOf(
    "COMPLETED",
    "CANCELLED",
    "FAILED",
    "EXPIRED"
)

/** 与 DefaultDataInitializer 种子账户 ID 保持一致的支付平台资金账户映射。 */
private const val WECHAT_PAY_ACCOUNT_ID = "default-account-wechat"
private const val ALIPAY_ACCOUNT_ID = "default-account-alipay"

internal sealed class CheckoutPaymentMatchResult {
    data class Accepted(
        val sessionId: String,
        val status: String,
        val candidateId: String? = null,
        val evidenceId: String? = null,
        val matched: Boolean = false,
        val billId: String? = null
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
 * 将支付证据和视觉候选限制在同一个 sessionId 内关联，并在匹配成功时自动入账。
 *
 * 阶段四 T5 起：matched 且 conflictReason == null 的候选通过 [LedgerRepository]
 * 在同一事务内写 Bill、联动更新账户余额、把候选标记为已入账，会话进入
 * AUTO_COMMITTED（公开 SessionStatus 为 COMPLETED）；冲突候选仍只写会话、
 * 证据和候选，由 T6 待确认中心处理，不静默入账。
 */
internal class CheckoutPaymentMatcher(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ledger = LedgerRepository(database)

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

    /**
     * 阶段四审查修复：记录无会话可关联的孤儿支付信号。
     *
     * 支付先到但没有任何结算会话可匹配时（无障碍服务未连接、无活跃结算页、
     * 会话解析 NoMatch），不丢弃证据：直接落一条纯通知待确认交易
     * （sessionId 为空、candidateId 为空），由用户在待确认中心处理。
     *
     * 幂等：待确认 ID 由 sourceEventKey 确定性生成，重复信号不产生第二条。
     */
    suspend fun recordOrphanPaymentSignal(
        signal: PaymentNotificationFallbackSignal,
        sourceType: String = "NOTIFICATION"
    ) {
        val now = nowMillis()
        database.withTransaction {
            if (!signal.isValid()) {
                return@withTransaction
            }
            val normalizedSourceType = normalizePaymentEvidenceSourceType(sourceType)
                ?: return@withTransaction
            val sourceEventKey = sourceEventKey(signal)
            val pendingId = stableId("pending-orphan", sourceEventKey)
            val dao = database.pendingTransactionDao()
            if (dao.getById(pendingId) != null) {
                return@withTransaction
            }
            val pending = PendingTransactionEntity(
                id = pendingId,
                candidateId = null,
                sessionId = null,
                amountMinor = signal.amountMinor,
                type = "expense",
                occurredAt = signal.occurredAt,
                platform = signal.paymentPackage,
                merchant = signal.merchant,
                productDescription = null,
                orderId = signal.orderId,
                accountId = defaultAccountIdForPayment(signal.paymentPackage),
                categoryId = null,
                conflictReason = "NO_ACTIVE_SESSION",
                sourceType = normalizedSourceType,
                createdAt = now,
                version = 1
            )
            dao.insertIfAbsent(pending)
        }
    }

    /**
     * 记录支付证据；重复来源事件只保留一条证据。
     *
     * @param sourceType 证据来源：NOTIFICATION（通知监听）或 ACCESSIBILITY（支付结果页），
     * 供前端区分展示，不再把结果页证据误记为通知。
     */
    suspend fun recordPaymentEvidence(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal,
        sourceType: String = "NOTIFICATION"
    ): CheckoutPaymentMatchResult {
        val validationError = validatePayment(reference, signal)
        if (validationError != null) {
            return CheckoutPaymentMatchResult.Rejected(reference.sessionId, validationError)
        }
        val normalizedSourceType = normalizePaymentEvidenceSourceType(sourceType)
            ?: return CheckoutPaymentMatchResult.Rejected(
                reference.sessionId,
                "PAYMENT_SOURCE_TYPE_INVALID"
            )

        return database.withTransaction {
            val now = nowMillis()
            val sourceEventKey = sourceEventKey(signal)
            val evidenceDao = database.paymentEvidenceDao()
            val existingEvidence = evidenceDao.getBySourceEventKey(sourceEventKey)
            if (existingEvidence != null) {
                if (existingEvidence.sessionId != reference.sessionId) {
                    return@withTransaction CheckoutPaymentMatchResult.Rejected(
                        reference.sessionId,
                        "PAYMENT_EVIDENCE_ALREADY_BOUND"
                    )
                }
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
                id = existingEvidence?.id ?: evidenceId,
                sessionId = reference.sessionId,
                sourceType = normalizedSourceType,
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
            val storedEvidence = if (existingEvidence == null) {
                evidenceDao.insertIfAbsent(evidence)
                evidenceDao.getById(evidence.id) ?: evidence
            } else {
                if (evidence != existingEvidence) {
                    // DAO 未提供更新方法，在同一事务内按原主键替换证据。
                    evidenceDao.delete(existingEvidence)
                    evidenceDao.insert(evidence)
                }
                evidence
            }

            val candidate = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            if (candidate == null) {
                session = advanceStatus(session, WAITING_ENRICHMENT_STATUS, now)
                // 阶段四 T6：纯通知回退（无 AI 候选）→ 转 PendingTransaction，sourceType=notification
                pendingFromEvidenceOnly(session, storedEvidence, now)?.let { upsertPendingForSession(it) }
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    evidenceId = storedEvidence.id
                )
            }

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
            val outcome = if (evaluation.conflictReason == null) {
                // 阶段四 T5：匹配成功且无冲突，自动入账（写 Bill + 余额更新 + 会话 AUTO_COMMITTED）
                autoCommitMatchedCandidate(session, updatedCandidate, storedEvidence, now)
            } else {
                // 阶段四 T6：冲突候选转待确认，conflictReason 必须填充供前端展示
                pendingFromConflict(
                    session = session,
                    candidate = updatedCandidate,
                    evidence = storedEvidence,
                    conflictReason = evaluation.conflictReason,
                    now = now
                )?.let { upsertPendingForSession(it) }
                AutoCommitOutcome(advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now), null)
            }
            CheckoutPaymentMatchResult.Accepted(
                sessionId = reference.sessionId,
                status = outcome.session.status,
                candidateId = updatedCandidate.id,
                evidenceId = storedEvidence.id,
                matched = updatedCandidate.matched,
                billId = outcome.billId
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
            val retryableAiFailure = isRetryableAiFailureCandidate(existingCandidate)
            val duplicateResult = existingSession != null && when (result) {
                is AiVisionResult.Succeeded ->
                    (requestId != null && requestId == existingSession.aiRequestId) ||
                        (sameRequestKey && existingCandidate != null && !retryableAiFailure)

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
            val retryingFailedAiSession =
                result is AiVisionResult.Succeeded &&
                    retryableAiFailure &&
                    existingSession?.let { it.status == "FAILED" && it.expiresAt > now } == true
            if (existingSession?.isClosedOrExpired(now) == true && !retryingFailedAiSession) {
                return@withTransaction CheckoutPaymentMatchResult.Rejected(
                    reference.sessionId,
                    "AI_SESSION_CLOSED"
                )
            }
            var session = upsertSession(
                reference = reference,
                initialStatus = AI_PENDING_STATUS,
                requestKey = metadata.requestKey,
                aiRequestId = requestId,
                now = now
            )
            when (result) {
                is AiVisionResult.Failed -> {
                    val successEvidences = database.paymentEvidenceDao()
                        .getBySession(reference.sessionId)
                        .filter { it.paymentStatus == "SUCCESS" }
                    // 审查修复：AI 失败也创建占位候选，保证 retryAiRecognition 链路可用；
                    // 否则用户无法重试识别，会话会卡死在 WAITING_PAYMENT
                    val placeholderCandidateId = candidateIdFor(reference.sessionId)
                    var placeholderCandidate = existingCandidate
                    if (placeholderCandidate == null) {
                        placeholderCandidate = TransactionCandidateEntity(
                            id = placeholderCandidateId,
                            sessionId = reference.sessionId,
                            amountMinor = successEvidences.firstOrNull()?.amountMinor,
                            platform = null,
                            merchant = successEvidences.firstOrNull()?.merchant,
                            productDescription = null,
                            evidenceAmountMinor = null,
                            evidenceMerchant = null,
                            evidenceOrderId = null,
                            aiOrderId = null,
                            aiOccurredAt = null,
                            matched = false,
                            conflictReason = if (successEvidences.isEmpty()) {
                                AI_RECOGNITION_FAILED_REASON
                            } else {
                                null
                            },
                            appliedRules = rulesJson(
                                "sessionId" to reference.sessionId,
                                "decision" to "AI_FAILED",
                                "aiRequestId" to result.requestId
                            ),
                            createdAt = now,
                            version = 1
                        )
                        database.transactionCandidateDao().insertIfAbsent(placeholderCandidate)
                        session = session.copy(
                            candidateId = placeholderCandidate.id,
                            updatedAt = now
                        )
                        database.checkoutSessionDao().update(session)
                    }
                    if (existingCandidate != null) {
                        database.transactionCandidateDao().update(
                            placeholderCandidate.copy(
                                conflictReason = if (successEvidences.isEmpty()) {
                                    AI_RECOGNITION_FAILED_REASON
                                } else {
                                    placeholderCandidate.conflictReason
                                },
                                appliedRules = rulesJson(
                                    "sessionId" to reference.sessionId,
                                    "decision" to "AI_FAILED",
                                    "aiRequestId" to result.requestId
                                ),
                                version = placeholderCandidate.version + 1
                            )
                        )
                        placeholderCandidate = database.transactionCandidateDao()
                            .getById(placeholderCandidate.id)
                            ?: placeholderCandidate
                    }
                    if (session.candidateId != placeholderCandidate.id) {
                        session = session.copy(
                            candidateId = placeholderCandidate.id,
                            updatedAt = now
                        )
                        database.checkoutSessionDao().update(session)
                    }
                    val failedSession = advanceStatus(session, "AI_FAILED", now)
                    session = if (successEvidences.isNotEmpty()) {
                        val paymentReady = advancePaymentConfirmed(failedSession, now)
                        advanceStatus(paymentReady, NEEDS_CONFIRMATION_STATUS, now)
                    } else {
                        // AI_FAILED 的公开映射已经是 FAILED，保留内部状态以允许同一候选重试。
                        failedSession
                    }
                    // 阶段四 T6：AI 失败但支付成功 → 证据转待确认（sourceType=notification）
                    if (successEvidences.isNotEmpty()) {
                        successEvidences.firstOrNull()?.let { evidence ->
                            pendingFromConflict(
                                session = session,
                                candidate = placeholderCandidate,
                                evidence = evidence,
                                conflictReason = AI_RECOGNITION_FAILED_REASON,
                                now = now
                            )?.let { upsertPendingForSession(it) }
                        }
                    }
                    // 审查修复：AI 失败且无支付证据 → 也生成待确认（金额取证据或 0 占位），
                    // 让用户能在待确认中心看到失败条目并触发重试，而不是无声丢失
                    if (successEvidences.isEmpty()) {
                        val pending = PendingTransactionEntity(
                            id = stableId("pending", reference.sessionId),
                            candidateId = placeholderCandidate.id,
                            sessionId = reference.sessionId,
                            amountMinor = 0L,
                            type = "expense",
                            occurredAt = now,
                            platform = null,
                            merchant = null,
                            productDescription = null,
                            orderId = null,
                            accountId = null,
                            categoryId = null,
                            conflictReason = AI_RECOGNITION_FAILED_REASON,
                            sourceType = "ai_vision",
                            createdAt = now,
                            version = 1
                        )
                        upsertPendingForSession(pending)
                    }
                    CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = session.status,
                        candidateId = placeholderCandidate.id
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
                    removeAiFailurePending(reference.sessionId)

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
                        // 阶段四 T6：证据歧义冲突转待确认（无单一证据，字段从候选补充）
                        pendingFromConflict(
                            session = session,
                            candidate = conflicted,
                            evidence = null,
                            conflictReason = selection.conflictReason,
                            now = now
                        )?.let { upsertPendingForSession(it) }
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
                    val outcome = if (evaluation.conflictReason == null) {
                        // 阶段四 T5：匹配成功且无冲突，自动入账（写 Bill + 余额更新 + 会话 AUTO_COMMITTED）
                        autoCommitMatchedCandidate(session, updatedCandidate, selection.evidence, now)
                    } else {
                        // 阶段四 T6：冲突候选转待确认，conflictReason 必须填充供前端展示
                        pendingFromConflict(
                            session = session,
                            candidate = updatedCandidate,
                            evidence = selection.evidence,
                            conflictReason = evaluation.conflictReason,
                            now = now
                        )?.let { upsertPendingForSession(it) }
                        AutoCommitOutcome(advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now), null)
                    }
                    CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = outcome.session.status,
                        candidateId = updatedCandidate.id,
                        evidenceId = selection.evidence.id,
                        matched = updatedCandidate.matched,
                        billId = outcome.billId
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

    /**
     * 阶段四 T5：把 matched 且无冲突的候选自动入账。
     *
     * 必须在外层 database.withTransaction 内调用（Room 嵌套事务合并到外层），
     * 同一事务内依次完成：幂等检查 → 写 Bill → 联动更新账户余额 → 候选标记已入账 →
     * 会话进入 AUTO_COMMITTED（公开 SessionStatus 为 COMPLETED）。
     * 任一步失败整体回滚，会话不进入 COMPLETED。
     *
     * 幂等保证：
     * 1. 会话旅程已是 AUTO_COMMITTED/COMPLETED 时直接返回，不重复入账；
     * 2. Bill 主键由 sessionId 确定性生成，预查存在时只推进会话状态；
     * 3. BillDao.insert 为 ABORT 策略，并发竞态下重复插入抛异常回滚整个事务。
     */
    private suspend fun autoCommitMatchedCandidate(
        session: CheckoutSessionEntity,
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        now: Long
    ): AutoCommitOutcome {
        val journeyValue = session.journeyState ?: session.status
        if (journeyValue == AUTO_COMMITTED_STATUS || journeyValue == COMPLETED_STATUS) {
            return AutoCommitOutcome(session, null)
        }
        val billId = stableId("bill", session.id)
        if (database.billDao().getById(billId) != null) {
            // 该会话历史上已产生过账单（重复通知/重复决策），只补齐会话状态，不写第二笔 Bill
            return AutoCommitOutcome(
                advanceStatus(session, AUTO_COMMITTED_STATUS, now),
                billId
            )
        }
        // 阶段四 T6：用户已手动确认过该会话的待确认交易（Bill.originalTransactionId = sessionId）
        // 视为该会话已入账，跳过自动写 Bill，只推进会话终态，避免同一支付重复入账
        val manuallyCommitted = database.billDao().findByOriginalTransactionId(session.id)
        if (manuallyCommitted != null) {
            return AutoCommitOutcome(advanceSessionCompleted(session, now), manuallyCommitted.id)
        }
        val amountMinor = requireNotNull(candidate.amountMinor) {
            "自动入账候选金额缺失：sessionId=${session.id}"
        }
        // 审查修复：支付账户缺失/停用时不自动入账——无账户账单虽不更新余额，
        // 但会话进入 COMPLETED 会让用户误以为已正确入账；转待确认由用户补选账户
        val accountId = defaultAccountIdForPayment(evidence.platform)
        if (accountId == null) {
            val pending = pendingFromConflict(
                session = session,
                candidate = candidate,
                evidence = evidence,
                conflictReason = "ACCOUNT_UNAVAILABLE",
                now = now
            )
            if (pending != null) {
                upsertPendingForSession(pending)
            }
            return AutoCommitOutcome(
                advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                null
            )
        }
        val bill = BillEntity(
            id = billId,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence.occurredAt ?: candidate.aiOccurredAt ?: now,
            description = candidate.productDescription,
            categoryId = null,
            accountId = accountId,
            targetAccountId = null,
            platform = candidate.platform,
            merchant = candidate.merchant,
            productDescription = candidate.productDescription,
            sourceType = "ai_vision",
            originalTransactionId = session.id,
            orderId = evidence.orderId ?: candidate.aiOrderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )
        // 写 Bill + 按类型联动余额 + 审计日志；嵌套 withTransaction 合并到外层事务
        ledger.createBillWithBalanceUpdate(bill, operator = AUTO_COMMIT_OPERATOR)

        // 候选标记为已入账，记录 billId 供审计与前端追溯
        database.transactionCandidateDao().update(
            candidate.copy(
                appliedRules = rulesJson(
                    "sessionId" to session.id,
                    "decision" to AUTO_COMMITTED_STATUS,
                    "billId" to bill.id,
                    "evidenceId" to evidence.id
                ),
                version = candidate.version + 1
            )
        )
        // 阶段四 T6：会话已自动入账，清理该会话仍在等待的待确认交易（忽略记录保留审计）
        database.pendingTransactionDao().findPendingBySessionId(session.id)?.let {
            database.pendingTransactionDao().delete(it)
        }
        return AutoCommitOutcome(advanceStatus(session, AUTO_COMMITTED_STATUS, now), bill.id)
    }

    /**
     * 阶段四 T6：把会话推进到旅程终态 COMPLETED（公开 SessionStatus 亦为 COMPLETED）。
     *
     * NEEDS_CONFIRMATION 只允许直达 COMPLETED（手动确认入账后的路径）；
     * 其余状态经 AUTO_COMMITTED 两步推进，任一步非法时 advanceStatus 自动跳过。
     */
    private suspend fun advanceSessionCompleted(
        session: CheckoutSessionEntity,
        now: Long
    ): CheckoutSessionEntity {
        val stepped = advanceStatus(session, AUTO_COMMITTED_STATUS, now)
        return advanceStatus(stepped, COMPLETED_STATUS, now)
    }

    /**
     * 支付包名到种子资金账户的固定映射：微信支付对应"微信零钱"，支付宝对应"支付宝"。
     * 账户被删除或停用时返回 null，账单不指定账户也不更新余额，
     * 由用户在后续编辑中补充（T6 待确认路径不受影响）。
     */
    private suspend fun defaultAccountIdForPayment(paymentPackage: String?): String? {
        val accountId = when (paymentPackage) {
            "com.tencent.mm" -> WECHAT_PAY_ACCOUNT_ID
            "com.eg.android.AlipayGphone" -> ALIPAY_ACCOUNT_ID
            else -> return null
        }
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) accountId else null
    }

    /**
     * 阶段四 T6：从支付证据构建纯通知待确认（无 AI 候选）。
     *
     * sourceType 固定为 notification，candidateId 为空；证据金额无效时返回 null（不生成待确认）。
     */
    private suspend fun pendingFromEvidenceOnly(
        session: CheckoutSessionEntity,
        evidence: PaymentEvidenceEntity,
        now: Long
    ): PendingTransactionEntity? {
        val amountMinor = evidence.amountMinor?.takeIf { it > 0L } ?: return null
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = null,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence.occurredAt ?: now,
            platform = evidence.platform,
            merchant = evidence.merchant,
            productDescription = null,
            orderId = evidence.orderId,
            accountId = defaultAccountIdForPayment(evidence.platform),
            categoryId = null,
            conflictReason = null,
            sourceType = "notification",
            createdAt = now,
            version = 1
        )
    }

    /**
     * 阶段四 T6：从冲突候选构建待确认交易。
     *
     * 金额/时间/订单号优先取支付证据，证据缺失时用候选字段补充；
     * conflictReason 必须填充供前端展示；sourceType 为 ai_vision（存在 AI 候选）。
     * 候选金额缺失且证据金额缺失时返回 null（无法构成合法待确认）。
     */
    private suspend fun pendingFromConflict(
        session: CheckoutSessionEntity,
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity?,
        conflictReason: String?,
        now: Long
    ): PendingTransactionEntity? {
        val amountMinor = evidence?.amountMinor?.takeIf { it > 0L }
            ?: candidate.amountMinor?.takeIf { it > 0L }
            ?: return null
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = candidate.id,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence?.occurredAt ?: candidate.aiOccurredAt ?: now,
            platform = candidate.platform ?: evidence?.platform,
            merchant = candidate.merchant ?: evidence?.merchant,
            productDescription = candidate.productDescription,
            orderId = evidence?.orderId ?: candidate.aiOrderId,
            accountId = defaultAccountIdForPayment(evidence?.platform),
            categoryId = null,
            conflictReason = conflictReason ?: candidate.conflictReason,
            sourceType = "ai_vision",
            createdAt = now,
            version = 1
        )
    }

    /**
     * 阶段四 T6：按会话幂等落待确认交易。
     *
     * - 首次出现：插入（ID 由 sessionId 确定性生成，IGNORE 兜底并发）；
     * - 已存在且仍为 pending：内容有实质变化时更新并递增版本（如纯通知待确认被 AI 冲突结果增强）；
     * - 已被忽略：保留用户决策，不做任何改动。
     */
    private suspend fun upsertPendingForSession(pending: PendingTransactionEntity) {
        val dao = database.pendingTransactionDao()
        val existing = dao.getById(pending.id) ?: run {
            dao.insertIfAbsent(pending)
            return
        }
        if (existing.status != PendingTransactionEntity.STATUS_PENDING) {
            return
        }
        val normalizedExisting = existing.copy(version = 1, createdAt = 0L)
        val normalizedPending = pending.copy(version = 1, createdAt = 0L)
        if (normalizedExisting != normalizedPending) {
            dao.update(pending.copy(version = existing.version + 1))
        }
    }

    /** 自动入账结果：推进后的会话与生成的账单 ID（幂等跳过时为 null 或已存在的 ID）。 */
    /** 成功重试后只删除 AI 失败占位，保留用户已经产生的其他待确认交易。 */
    private suspend fun removeAiFailurePending(sessionId: String) {
        val dao = database.pendingTransactionDao()
        val pending = dao.findPendingBySessionId(sessionId)
        if (pending?.conflictReason == AI_RECOGNITION_FAILED_REASON) {
            dao.delete(pending)
        }
    }

    private fun isRetryableAiFailureCandidate(candidate: TransactionCandidateEntity?): Boolean {
        if (candidate == null) {
            return false
        }
        if (candidate.conflictReason == AI_RECOGNITION_FAILED_REASON) {
            return true
        }
        return runCatching {
            JSONObject(candidate.appliedRules).optString("decision") == "AI_FAILED"
        }.getOrDefault(false)
    }

    private data class AutoCommitOutcome(
        val session: CheckoutSessionEntity,
        val billId: String?
    )

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
            // 审查修复：任一侧时间缺失时不能自动匹配——时间窗口是防误配的关键约束，
            // 缺时间走待确认由用户判断，不再输出 TIME_PARTIAL 放行
            evidence.occurredAt == null || aiOccurredAt == null -> {
                conflicts += "TIME_MISSING"
                "TIME_PARTIAL"
            }
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
