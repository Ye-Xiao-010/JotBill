package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.ai.AiVisionResult
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.RuleEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import org.json.JSONObject

private const val PAYMENT_MATCH_WINDOW_MILLIS = 5 * 60 * 1000L
internal const val CHECKOUT_PAYMENT_MATCH_WINDOW_MILLIS = PAYMENT_MATCH_WINDOW_MILLIS

/** 同会话跨来源证据收敛窗口：与服务层结果页 2 分钟去重桶保持一致。 */
private const val PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS = 2 * 60 * 1000L
private const val PAYMENT_CONFIRMED_STATUS = "PAYMENT_CONFIRMED"
private const val AI_PENDING_STATUS = "AI_PENDING"
private const val WAITING_PAYMENT_STATUS = "WAITING_PAYMENT"
private const val WAITING_ENRICHMENT_STATUS = "WAITING_ENRICHMENT"
private const val NEEDS_CONFIRMATION_STATUS = "NEEDS_CONFIRMATION"
private const val AUTO_COMMITTED_STATUS = "AUTO_COMMITTED"
private const val COMPLETED_STATUS = "COMPLETED"
private const val AUTO_COMMIT_OPERATOR = "auto_commit"
private const val AI_RECOGNITION_FAILED_REASON = "AI_RECOGNITION_FAILED"
private const val EVIDENCE_ONLY_DECISION = "EVIDENCE_ONLY"

/**
 * 仅由截图 AI 视觉推断出的支付结果状态。
 *
 * 与 "SUCCESS" 的区别：截图 AI 只看到一张图，无法证明支付真的完成
 *（历史账单详情、聊天转发凭证、未提交的收银台都可能被读出金额）。
 * 该状态的证据仍参与匹配与待确认展示，但一律不得触发自动入账。
 */
internal const val PAYMENT_STATUS_AI_UNVERIFIED = "SUCCESS_AI_UNVERIFIED"

/** 截图 AI 未经证实的支付结果转待确认时的冲突原因，供前端提示用户核对。 */
internal const val AI_UNVERIFIED_PAYMENT_REASON = "AI_UNVERIFIED_PAYMENT"

/**
 * 该证据是否代表"支付已发生"。
 *
 * 包含已证实的 SUCCESS 与截图 AI 推断的 [PAYMENT_STATUS_AI_UNVERIFIED]：
 * 两者都应参与候选关联与待确认展示，差异只体现在能否自动入账。
 */
internal fun PaymentEvidenceEntity.indicatesPaidOutcome(): Boolean =
    paymentStatus == "SUCCESS" || paymentStatus == PAYMENT_STATUS_AI_UNVERIFIED

/**
 * 在原有冲突判定之上叠加"截图 AI 未证实"守卫。
 *
 * 截图 AI 只凭一张图推断支付完成，可能把历史账单、聊天凭证或未提交的
 * 收银台读成本次支付。即使金额/订单号/时间三项校验全部通过，也不允许
 * 自动写入账本，必须转待确认由用户确认。
 *
 * @param conflictReason 原有评估得出的冲突原因，非空时直接沿用。
 * @param paymentStatus 参与本次匹配的支付证据状态。
 * @return 应生效的冲突原因；null 表示允许自动入账。
 */
internal fun resolveConflictWithAiUnverifiedGuard(
    conflictReason: String?,
    paymentStatus: String?
): String? = when {
    conflictReason != null -> conflictReason
    paymentStatus == PAYMENT_STATUS_AI_UNVERIFIED -> AI_UNVERIFIED_PAYMENT_REASON
    else -> null
}
/**
 * 购物应用包名到平台展示名的权威映射。
 *
 * 包名由系统无障碍事件提供，可靠性远高于 AI 视觉识别：真机上 AI 曾把
 * platform 读成"手机应用"这类无意义文字，导致账单来源无法体现购物平台。
 * 因此 platform 一律由该映射推导，不采用 AI 返回值。
 *
 * @param shoppingPackage 结算会话记录的购物应用包名。
 * @return 平台展示名；未收录的包名返回 null。
 */
internal fun shoppingPlatformName(shoppingPackage: String?): String? =
    when (shoppingPackage?.trim()) {
        "com.xunmeng.pinduoduo" -> "拼多多"
        "com.taobao.taobao" -> "淘宝"
        "com.tmall.wireless" -> "天猫"
        "com.jingdong.app.mall" -> "京东"
        "com.sankuai.meituan" -> "美团"
        "com.sdu.didi.psnger" -> "滴滴"
        "com.ss.android.ugc.aweme" -> "抖音"
        "com.xingin.xhs" -> "小红书"
        else -> null
    }

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
    private val ruleEngine = RuleEngine(database)

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
     * 页面指纹不可用时的最后兜底：在匹配时间窗内查找唯一可关联的活跃结算会话。
     *
     * 适用场景：AI 已派发、ensureSession 已落库（AI_PENDING），但内存交接引用与
     * buffer 快照都已失效（如收银台停留超过截图 TTL），支付证据晚到时仍应关联
     * 该会话生成 EVIDENCE_ONLY 候选，而不是落 NO_ACTIVE_SESSION 孤儿待确认。
     * 窗口内存在多个候选会话时无法区分，返回 null 交由调用方走原有保守路径。
     */
    suspend fun findRecentCheckoutReferenceForPayment(
        occurredAt: Long,
        at: Long = nowMillis()
    ): CheckoutSnapshotReference? {
        val activeSessions = try {
            database.checkoutSessionDao().getActiveSessions(at)
        } catch (_: RuntimeException) {
            return null
        }
        val candidates = activeSessions.filter { session ->
            val shoppingPackage = session.shoppingPackage.orEmpty()
            shoppingPackage in DEFAULT_SUPPORTED_SHOPPING_PACKAGES &&
                CheckoutPageRole.fromStorageValue(session.pageRole) == CheckoutPageRole.CHECKOUT &&
                abs(occurredAt - session.createdAt) <= PAYMENT_MATCH_WINDOW_MILLIS
        }
        val session = candidates.singleOrNull() ?: return null
        return referenceForSession(session)
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
            else -> {
                // 第三优先级：结算页截图 AI 失败后支付证据晚到。若时间窗口内只有
                // 一个尚未被证据补齐的 AI_FAILED 占位候选，则回填到原会话，
                // 而不是生成一条无法关联结算页的纯通知待确认。
                val failedCandidates = database.transactionCandidateDao()
                    .findAiFailedUnmatchedCandidates(
                        since = occurredAt - PAYMENT_MATCH_WINDOW_MILLIS,
                        until = occurredAt + PAYMENT_MATCH_WINDOW_MILLIS
                    )
                val failedSessionIds = failedCandidates.map { it.sessionId }.distinct()
                when {
                    failedSessionIds.size == 1 -> {
                        val session = database.checkoutSessionDao()
                            .getById(failedSessionIds.single())
                        if (session != null && session.expiresAt > at) {
                            // AI 失败占位来自普通页手动截图（pageRole 常为 UNKNOWN），
                            // 支付证据晚到时把引用角色提升为 CHECKOUT，允许通过支付证据校验。
                            PaymentSessionResolution.Matched(
                                reference = referenceForSession(session).copy(
                                    pageRole = CheckoutPageRole.CHECKOUT
                                ),
                                byOrderId = false
                            )
                        } else {
                            PaymentSessionResolution.NoMatch
                        }
                    }
                    failedSessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(failedSessionIds)
                    else -> PaymentSessionResolution.NoMatch
                }
            }
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
     * 记录个人转账（微信/支付宝向好友转账）为待确认转账。
     *
     * 收款人是个人而非商户，不作为消费证据参与会话匹配；文本解析与截图
     * AI 两条路径识别到同一笔转账时，由确定性 ID（包名+2 分钟桶+金额+收款人）
     * 幂等收敛为一条，避免"同一笔转账出现两个金额"。
     */
    suspend fun recordPersonalTransfer(
        amountMinor: Long,
        paymentPackage: String,
        recipient: String?,
        occurredAt: Long?
    ) {
        if (amountMinor <= 0L) return
        val now = nowMillis()
        val effectiveOccurredAt = occurredAt ?: now
        val pendingId = stableId(
            "personal-transfer",
            paymentPackage,
            amountMinor.toString(),
            (effectiveOccurredAt / 120_000L).toString(),
            recipient.orEmpty()
        )
        val dao = database.pendingTransactionDao()
        if (dao.getById(pendingId) != null) {
            return
        }
        database.withTransaction {
            dao.insertIfAbsent(
                PendingTransactionEntity(
                    id = pendingId,
                    candidateId = null,
                    sessionId = null,
                    amountMinor = amountMinor,
                    type = "transfer",
                    occurredAt = effectiveOccurredAt,
                    platform = paymentPackage,
                    merchant = recipient,
                    productDescription = null,
                    orderId = null,
                    accountId = null,
                    categoryId = null,
                    conflictReason = null,
                    sourceType = "accessibility_transfer",
                    createdAt = now,
                    version = 1
                )
            )
        }
        android.util.Log.i(
            "CheckoutPaymentMatcher",
            "个人转账已转待确认: amount=$amountMinor, recipient=${recipient ?: "未知"}, " +
                "package=$paymentPackage, pendingId=${pendingId.take(12)}"
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
        sourceType: String = "NOTIFICATION",
        conflictReason: String = "NO_ACTIVE_SESSION",
        sessionIds: List<String> = emptyList()
    ) {
        val now = nowMillis()
        database.withTransaction {
            if (!signal.isValid()) {
                android.util.Log.w(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据未落待确认(信号非法): pkg=${signal.paymentPackage}, " +
                        "amount=${signal.amountMinor}, reason=$conflictReason"
                )
                return@withTransaction
            }
            val normalizedSourceType = normalizePaymentEvidenceSourceType(sourceType)
                ?: run {
                    android.util.Log.w(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据未落待确认(来源类型非法): sourceType=$sourceType, " +
                            "amount=${signal.amountMinor}"
                    )
                    return@withTransaction
                }
            val sourceEventKey = sourceEventKey(signal)
            val pendingId = stableId("pending-orphan", sourceEventKey)
            val dao = database.pendingTransactionDao()
            if (dao.getById(pendingId) != null) {
                android.util.Log.i(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据已存在待确认(幂等跳过): pending=${pendingId.take(16)}, " +
                        "amount=${signal.amountMinor}, reason=$conflictReason"
                )
                return@withTransaction
            }
            // 阶段五 T1 时机一：纯通知待确认创建前执行规则；
            // set_platform 不应用于 pending.platform（该列承担支付包名语义），
            // set_merchant/set_account/set_category 可安全应用
            val ruleDecision = ruleEngine.evaluateAndRecord(
                RuleEngine.Subject(
                    amountMinor = signal.amountMinor,
                    platform = signal.paymentPackage,
                    merchant = signal.merchant,
                    description = null,
                    sourceType = normalizedSourceType.lowercase()
                )
            )
            if (ruleDecision is RuleEngine.Decision.Ignored) {
                recordRuleDecisionAudit(pendingId, "ignore", ruleDecision.hits)
                android.util.Log.i(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据被规则忽略: pending=${pendingId.take(16)}, " +
                        "amount=${signal.amountMinor}, hits=${ruleDecision.hits.size}"
                )
                return@withTransaction
            }
            val rulePatch = ruleEngine.sanitizePatch(
                (ruleDecision as? RuleEngine.Decision.Continue)?.patch
                    ?: RuleEngine.FieldPatch()
            )

            // ---- 阶段五 T6 去重第 4 级：纯通知待确认创建前疑似重复提示（只提示不合并）----
            // 查询异常保守处理：疑似重复信息缺失不阻塞待确认创建（证据不丢弃），
            // 保持原 conflictReason；命中时覆盖为 SUSPECTED_DUPLICATE 并在
            // appliedRules 记录重复账单与因素，原原因保留在 baseConflictReason
            val resolvedMerchant = rulePatch.merchant ?: signal.merchant
            val resolvedAccountId = rulePatch.accountId ?: defaultAccountIdForPayment(signal.paymentPackage)
            var effectiveConflictReason = conflictReason
            var dedupExtras: Pair<String, String>? = null
            try {
                val guard = DuplicateGuard(database)
                val similarHits = guard.findSimilar(
                    type = "expense",
                    amountMinor = signal.amountMinor,
                    occurredAt = signal.occurredAt,
                    accountId = resolvedAccountId,
                    merchant = resolvedMerchant
                )
                if (similarHits.isNotEmpty()) {
                    effectiveConflictReason = DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE
                    dedupExtras = "suspectedDuplicate" to guard.suspectedDuplicateHint(similarHits)
                }
            } catch (_: RuntimeException) {
                // 相似检查失败：不阻塞待确认创建，保守不标记
            }

            val pending = PendingTransactionEntity(
                id = pendingId,
                candidateId = null,
                sessionId = null,
                amountMinor = signal.amountMinor,
                type = "expense",
                occurredAt = signal.occurredAt,
                platform = signal.paymentPackage,
                merchant = resolvedMerchant,
                productDescription = null,
                orderId = signal.orderId,
                accountId = resolvedAccountId,
                categoryId = rulePatch.categoryId,
                conflictReason = effectiveConflictReason,
                sourceType = normalizedSourceType,
                appliedRules = dedupExtras?.let { extras ->
                    ruleEngine.mergeAppliedRules(
                        mergeRulesJson("{}", "baseConflictReason" to conflictReason, extras),
                        ruleDecision.hits
                    )
                } ?: ruleEngine.mergeAppliedRules("{}", ruleDecision.hits),
                createdAt = now,
                version = 1
            )

            if (sessionIds.isNotEmpty()) {
                val sessionDao = database.checkoutSessionDao()
                var insertedAmbiguousPending = false
                sessionIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach { sessionId ->
                        val session = sessionDao.getById(sessionId) ?: return@forEach
                        if (session.isClosedOrExpired(now)) return@forEach
                        val insertResult = dao.insertIfAbsent(
                            pending.copy(
                                id = stableId("pending-ambiguous", sessionId, sourceEventKey),
                                sessionId = sessionId
                            )
                        )
                        if (insertResult != -1L) {
                            insertedAmbiguousPending = true
                          }
                    }
                  if (!insertedAmbiguousPending) {
                      dao.insertIfAbsent(pending)
                  }
                android.util.Log.i(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据已落待确认(并发会话): pending=${pendingId.take(16)}, " +
                        "amount=${signal.amountMinor}, sessions=${sessionIds.size}, " +
                        "ambiguous=$insertedAmbiguousPending, reason=$effectiveConflictReason"
                )
                return@withTransaction
            }
            val inserted = dao.insertIfAbsent(pending)
            android.util.Log.i(
                "CheckoutPaymentMatcher",
                "孤立支付证据已落待确认: pending=${pendingId.take(16)}, " +
                    "amount=${signal.amountMinor}, merchant=${resolvedMerchant ?: "null"}, " +
                    "account=${resolvedAccountId ?: "null"}, reason=$effectiveConflictReason, " +
                    "inserted=${inserted != -1L}"
            )
        }
    }

    /**
     * 记录支付证据；重复来源事件只保留一条证据。
     *
     * @param sourceType 证据来源：NOTIFICATION（通知监听）或 ACCESSIBILITY（支付结果页），
     * 供前端区分展示，不再把结果页证据误记为通知。
     * @param aiUnverified true 表示该证据仅由截图 AI 视觉推断得出，未经页面文本
     * 语义或支付通知证实。此类证据落库为 [PAYMENT_STATUS_AI_UNVERIFIED]，
     * 不参与自动入账，只能转待确认由用户核对。
     */
    suspend fun recordPaymentEvidence(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal,
        sourceType: String = "NOTIFICATION",
        aiUnverified: Boolean = false
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
            val existingCandidateBeforeReopen = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            val failedAiPlaceholder = existingSession != null &&
                existingSession.status == "FAILED" &&
                existingCandidateBeforeReopen?.conflictReason == AI_RECOGNITION_FAILED_REASON &&
                existingCandidateBeforeReopen.amountMinor == null &&
                existingCandidateBeforeReopen.evidenceAmountMinor == null
            if (existingSession != null && existingSession.isClosedOrExpired(now) && !failedAiPlaceholder) {
                return@withTransaction CheckoutPaymentMatchResult.Rejected(
                    reference.sessionId,
                    "PAYMENT_SESSION_CLOSED"
                )
            }
            var reopenedFailedSession: CheckoutSessionEntity? = null
            if (failedAiPlaceholder && existingSession != null) {
                // AI 失败占位候选获得支付证据：用支付金额补齐候选，并把会话从
                // FAILED 重开为 PAYMENT_CONFIRMED，后续仍转待确认，不自动入账。
                val candidate = checkNotNull(existingCandidateBeforeReopen)
                database.transactionCandidateDao().update(
                    candidate.copy(
                        amountMinor = signal.amountMinor,
                        // merchant 只保留真实商户名（支付通知提供），不再用购物平台兜底：
                        // 平台已由 platform 字段独立承载。
                        merchant = signal.merchant ?: candidate.merchant,
                        evidenceAmountMinor = signal.amountMinor,
                        evidenceMerchant = signal.merchant,
                        evidenceOrderId = signal.orderId,
                        aiOccurredAt = signal.occurredAt,
                        version = candidate.version + 1
                    )
                )
                reopenedFailedSession = existingSession.copy(
                    status = CheckoutJourneyState.PAYMENT_CONFIRMED.toPublicSessionStatus().wireValue,
                    journeyState = PAYMENT_CONFIRMED_STATUS,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(reopenedFailedSession)
            }
            var session = reopenedFailedSession ?: upsertSession(
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
                paymentStatus = if (aiUnverified) PAYMENT_STATUS_AI_UNVERIFIED else "SUCCESS",
                occurredAt = signal.occurredAt,
                rawTextHash = signal.rawTextHash ?: stableId(
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
                mergeSessionEvidence(reference.sessionId, evidence, now)
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
                // AI 尚未返回时支付证据先到：先用证据建立 EVIDENCE_ONLY 临时候选，
                // 让待确认中心立刻显示关联到本会话的金额/商户/时间；AI 成功返回后仍可
                // 覆盖该占位候选并补全商品描述，AI 失败时也会回填失败原因和重试入口。
                val provisionalCandidate = TransactionCandidateEntity(
                    id = candidateIdFor(reference.sessionId),
                    sessionId = reference.sessionId,
                    amountMinor = storedEvidence.amountMinor,
                    // platform 固定为购物平台；证据里的 platform 是支付包名，不能混用。
                    platform = shoppingPlatformName(session.shoppingPackage),
                    merchant = storedEvidence.merchant,
                    productDescription = null,
                    evidenceAmountMinor = storedEvidence.amountMinor,
                    evidenceMerchant = storedEvidence.merchant,
                    evidenceOrderId = storedEvidence.orderId,
                    aiOrderId = null,
                    aiOccurredAt = null,
                    matched = false,
                    conflictReason = null,
                    appliedRules = rulesJson(
                        "sessionId" to reference.sessionId,
                        "decision" to EVIDENCE_ONLY_DECISION
                    ),
                    createdAt = now,
                    version = 1
                )
                database.transactionCandidateDao().insertIfAbsent(provisionalCandidate)
                val persistedProvisional = database.transactionCandidateDao()
                    .getById(provisionalCandidate.id)
                    ?: provisionalCandidate
                session = session.copy(
                    candidateId = persistedProvisional.id,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(session)
                session = advanceStatus(session, WAITING_ENRICHMENT_STATUS, now)
                // 阶段四 T6：AI 未就绪时用证据金额转待确认，候选和会话 ID 均已关联。
                pendingFromEvidenceOnly(
                    session = session,
                    evidence = storedEvidence,
                    now = now,
                    candidateId = persistedProvisional.id
                )?.let { upsertPendingForSession(it) }
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    candidateId = persistedProvisional.id,
                    evidenceId = storedEvidence.id,
                    matched = false
                )
            }

            if (isEvidenceOnlyProvisionalCandidate(candidate)) {
                // 候选仍是"证据先到"占位（AI 未返回，aiOccurredAt 必为空）：
                // 不能走 AI 候选评估，否则必然误报 TIME_MISSING 并把正常待确认
                // 覆盖成"缺少时间信息"冲突。这里只做字段补齐与金额一致性校验。
                val amountConsistent = storedEvidence.amountMinor == null ||
                    candidate.amountMinor == null ||
                    storedEvidence.amountMinor == candidate.amountMinor
                if (!amountConsistent) {
                    // 同会话两笔不同金额（真正的多证据冲突）：转待确认由用户裁决。
                    val conflicted = candidate.copy(
                        evidenceAmountMinor = storedEvidence.amountMinor,
                        evidenceMerchant = storedEvidence.merchant,
                        evidenceOrderId = storedEvidence.orderId,
                        conflictReason = "EVIDENCE_AMOUNT_CONFLICT",
                        matched = false,
                        version = candidate.version + 1
                    )
                    database.transactionCandidateDao().update(conflicted)
                    session = advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now)
                    pendingFromConflict(
                        session = session,
                        candidate = conflicted,
                        evidence = storedEvidence,
                        conflictReason = "EVIDENCE_AMOUNT_CONFLICT",
                        now = now
                    )?.let { upsertPendingForSession(it) }
                    return@withTransaction CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = session.status,
                        candidateId = conflicted.id,
                        evidenceId = storedEvidence.id,
                        matched = false
                    )
                }
                val enriched = candidate.copy(
                    amountMinor = storedEvidence.amountMinor ?: candidate.amountMinor,
                    // merchant 只保留真实商户名，不再用购物平台兜底。
                    merchant = candidate.merchant ?: storedEvidence.merchant,
                    evidenceAmountMinor = storedEvidence.amountMinor ?: candidate.evidenceAmountMinor,
                    evidenceMerchant = storedEvidence.merchant ?: candidate.evidenceMerchant,
                    evidenceOrderId = storedEvidence.orderId ?: candidate.evidenceOrderId,
                    version = candidate.version + 1
                )
                if (enriched != candidate) {
                    database.transactionCandidateDao().update(enriched)
                }
                session = session.copy(candidateId = enriched.id, updatedAt = now)
                database.checkoutSessionDao().update(session)
                pendingFromEvidenceOnly(
                    session = session,
                    evidence = storedEvidence,
                    now = now,
                    candidateId = enriched.id
                )?.let { upsertPendingForSession(it) }
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    candidateId = enriched.id,
                    evidenceId = storedEvidence.id,
                    matched = false
                )
            }

            // AI 先到但未识别到时间（结算页常无时间字段）：证据后到时用证据时间
            // 回填候选 aiOccurredAt，避免 evaluate 双侧时间校验误报 TIME_MISSING。
            val evalCandidate = if (candidate.aiOccurredAt == null && storedEvidence.occurredAt != null) {
                val backfilled = candidate.copy(
                    aiOccurredAt = storedEvidence.occurredAt,
                    version = candidate.version + 1
                )
                database.transactionCandidateDao().update(backfilled)
                android.util.Log.i(
                    "CheckoutPaymentMatcher",
                    "AI 未识别时间，证据后到回填: session=${reference.sessionId.take(8)}, " +
                        "occurredAt=${storedEvidence.occurredAt}"
                )
                backfilled
            } else {
                candidate
            }
            val evaluation = evaluate(evalCandidate, storedEvidence)
            val updatedCandidate = candidateAfterPayment(evalCandidate, storedEvidence, evaluation)
            if (updatedCandidate != candidate) {
                database.transactionCandidateDao().update(updatedCandidate)
            }
            session = session.copy(
                candidateId = updatedCandidate.id,
                updatedAt = now
            )
            database.checkoutSessionDao().update(session)
            val effectiveConflictReason = resolveConflictWithAiUnverifiedGuard(
                evaluation.conflictReason,
                storedEvidence.paymentStatus
            )
            val outcome = if (effectiveConflictReason == null) {
                // 阶段四 T5：匹配成功且无冲突，自动入账（写 Bill + 余额更新 + 会话 AUTO_COMMITTED）
                autoCommitMatchedCandidate(session, updatedCandidate, storedEvidence, now)
            } else {
                // 阶段四 T6：冲突候选转待确认，conflictReason 必须填充供前端展示
                pendingFromConflict(
                    session = session,
                    candidate = updatedCandidate,
                    evidence = storedEvidence,
                    conflictReason = effectiveConflictReason,
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

    /**
     * 升级到 EVIDENCE_ONLY 候选逻辑后，为历史“证据先到、AI 未返回”的会话补齐候选关联。
     *
     * 只处理尚无候选的活跃会话；每笔证据只补一次，已有待确认交易会被幂等升级为
     * candidateId/sessionId 完整关联，不改变用户已忽略或已确认的状态。
     */
    suspend fun backfillEvidenceOnlyCandidates(): Int {
        var backfilled = 0
        database.withTransaction {
            val now = nowMillis()
            // 兼容已过期的历史会话：证据已落库的会话即使过了 5 分钟窗口也应补齐候选关联。
            val sessions = database.checkoutSessionDao().getByStatuses(
                listOf("WAITING_ENRICHMENT", "PAYMENT_CONFIRMED", "NEEDS_CONFIRMATION")
            )
            for (session in sessions) {
                val existingCandidate = database.transactionCandidateDao()
                    .getBySession(session.id)
                    .singleOrNull()
                if (existingCandidate != null) {
                    continue
                }
                val evidence = database.paymentEvidenceDao()
                    .getBySession(session.id)
                    .firstOrNull { it.indicatesPaidOutcome() && (it.amountMinor ?: 0L) > 0L }
                    ?: continue
                val provisionalCandidate = TransactionCandidateEntity(
                    id = candidateIdFor(session.id),
                    sessionId = session.id,
                    amountMinor = evidence.amountMinor,
                    // platform 固定为购物平台，不写入支付包名。
                    platform = shoppingPlatformName(session.shoppingPackage),
                    merchant = evidence.merchant,
                    productDescription = null,
                    evidenceAmountMinor = evidence.amountMinor,
                    evidenceMerchant = evidence.merchant,
                    evidenceOrderId = evidence.orderId,
                    aiOrderId = null,
                    aiOccurredAt = null,
                    matched = false,
                    conflictReason = null,
                    appliedRules = rulesJson(
                        "sessionId" to session.id,
                        "decision" to EVIDENCE_ONLY_DECISION
                    ),
                    createdAt = now,
                    version = 1
                )
                database.transactionCandidateDao().insertIfAbsent(provisionalCandidate)
                val persisted = database.transactionCandidateDao()
                    .getById(provisionalCandidate.id)
                    ?: provisionalCandidate
                var updatedSession = session.copy(
                    candidateId = persisted.id,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(updatedSession)
                updatedSession = advanceStatus(updatedSession, WAITING_ENRICHMENT_STATUS, now)
                pendingFromEvidenceOnly(
                    session = updatedSession,
                    evidence = evidence,
                    now = now,
                    candidateId = persisted.id
                )?.let { upsertPendingForSession(it) }
                backfilled += 1
            }
        }
        return backfilled
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
            // 证据先到并临时建了 EVIDENCE_ONLY 候选时，后面的 AI 结果不能按重复请求跳过：
            // 成功要回填商户/商品并补全账单，失败也要把 AI 失败原因和重试入口落到候选。
            val evidenceOnlyPlaceholder = isEvidenceOnlyProvisionalCandidate(existingCandidate)
            val duplicateResult = existingSession != null && !evidenceOnlyPlaceholder && when (result) {
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
                        .filter { it.indicatesPaidOutcome() }
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
                    var occurredAt = fields.occurredAt?.let(::parseTimestamp)
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
                    // 阶段五 T1 时机一：AI 候选落库前执行规则（补全字段 / ignore / 强制待确认）
                    val ruleAdjustedCandidate = applyRulesAtCandidate(candidate)
                    var persistedCandidate = if (existing == null) {
                        database.transactionCandidateDao().insertIfAbsent(ruleAdjustedCandidate)
                        database.transactionCandidateDao().getById(ruleAdjustedCandidate.id) ?: ruleAdjustedCandidate
                    } else {
                        if (ruleAdjustedCandidate != existing) {
                            database.transactionCandidateDao().update(ruleAdjustedCandidate)
                        }
                        ruleAdjustedCandidate
                    }
                    removeAiFailurePending(reference.sessionId)

                    val evidences = database.paymentEvidenceDao()
                        .getBySession(reference.sessionId)
                        .filter { it.indicatesPaidOutcome() }
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

                    // 结算页截图通常不含时间字段，AI 识别不到时间是常态而非异常：
                    // 此时候选 aiOccurredAt 为空，evaluate 双侧时间校验必然误报
                    // TIME_MISSING。会话归属已由快照引用唯一确定，时间窗口仅是
                    // 防止证据错配的辅助约束，因此用同会话支付成功证据的时间回填。
                    if (occurredAt == null) {
                        occurredAt = evidences.mapNotNull { it.occurredAt }.minOrNull()
                        if (occurredAt != null && persistedCandidate.aiOccurredAt == null) {
                            database.transactionCandidateDao().update(
                                persistedCandidate.copy(
                                    aiOccurredAt = occurredAt,
                                    version = persistedCandidate.version + 1
                                )
                            )
                            persistedCandidate = database.transactionCandidateDao()
                                .getById(persistedCandidate.id)
                                ?: persistedCandidate
                            android.util.Log.i(
                                "CheckoutPaymentMatcher",
                                "AI 未识别时间，回填证据时间: session=${reference.sessionId.take(8)}, " +
                                    "occurredAt=$occurredAt"
                            )
                        }
                    }

                    val selection = selectEvidence(persistedCandidate.aiOrderId, evidences)
                    if (selection.evidence == null) {
                        val conflicted = persistedCandidate.copy(
                            matched = false,
                            conflictReason = selection.conflictReason,
                            appliedRules = mergeRulesJson(
                                persistedCandidate.appliedRules,
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
                    val effectiveConflictReason = resolveConflictWithAiUnverifiedGuard(
                        evaluation.conflictReason,
                        selection.evidence.paymentStatus
                    )
                    val outcome = if (effectiveConflictReason == null) {
                        // 阶段四 T5：匹配成功且无冲突，自动入账（写 Bill + 余额更新 + 会话 AUTO_COMMITTED）
                        autoCommitMatchedCandidate(session, updatedCandidate, selection.evidence, now)
                    } else {
                        // 阶段四 T6：冲突候选转待确认，conflictReason 必须填充供前端展示
                        pendingFromConflict(
                            session = session,
                            candidate = updatedCandidate,
                            evidence = selection.evidence,
                            conflictReason = effectiveConflictReason,
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
        // 阶段五 T1：候选生成时规则 ignore 已标记的交易不再入账（规则先标、证据后到的时序）
        if (ruleEngine.decisionOf(candidate.appliedRules) == RuleEngine.DECISION_IGNORED) {
            return AutoCommitOutcome(session, null)
        }
        // 阶段五 T1 时机二：自动入账前最终执行规则（规则可能在候选生成后被用户修改/删除）；
        // ignore 跳过记账，send_to_review 强制转待确认，set_* 按优先级补全账单字段
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = amountMinor,
                platform = candidate.platform,
                merchant = candidate.merchant,
                description = candidate.productDescription,
                sourceType = "ai_vision"
            )
        )
        when (ruleDecision) {
            is RuleEngine.Decision.Ignored -> {
                recordRuleDecisionAudit(candidate.id, "ignore", ruleDecision.hits)
                database.transactionCandidateDao().update(
                    candidate.copy(
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            ruleDecision.hits,
                            decisionOverride = RuleEngine.DECISION_IGNORED
                        ),
                        version = candidate.version + 1
                    )
                )
                // 不写 Bill、不进待确认；会话保持 WAITING_ENRICHMENT 等待过期回收
                return AutoCommitOutcome(session, null)
            }
            is RuleEngine.Decision.ToReview -> {
                database.transactionCandidateDao().update(
                    candidate.copy(
                        matched = false,
                        conflictReason = RuleEngine.CONFLICT_RULE_FORCED_REVIEW,
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            ruleDecision.hits,
                            decisionOverride = RuleEngine.DECISION_FORCED_REVIEW
                        ),
                        version = candidate.version + 1
                    )
                )
                val reviewPending = pendingFromConflict(
                    session = session,
                    candidate = candidate,
                    evidence = evidence,
                    conflictReason = RuleEngine.CONFLICT_RULE_FORCED_REVIEW,
                    now = now
                )
                if (reviewPending != null) {
                    upsertPendingForSession(reviewPending)
                }
                return AutoCommitOutcome(
                    advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                    null
                )
            }
            else -> Unit
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch
                ?: RuleEngine.FieldPatch()
        )
        // 审查修复：支付账户缺失/停用时不自动入账——无账户账单虽不更新余额，
        // 但会话进入 COMPLETED 会让用户误以为已正确入账；转待确认由用户补选账户；
        // 阶段五 T1：规则 set_account 指定的账户优先于包名默认映射
        val accountId = rulePatch.accountId ?: defaultAccountIdForPayment(evidence.platform)
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
            // 阶段五 T1：规则 set_category/set_account/set_platform/set_merchant 补全账单字段
            categoryId = rulePatch.categoryId,
            accountId = accountId,
            targetAccountId = null,
            // platform 语义固定为"购物平台"，且只信任系统提供的包名：
            // AI 视觉识别曾把它读成"手机应用"这类无意义文字。规则显式设置的
            // platform 优先级最高（用户意图），其余一律按包名映射推导。
            platform = rulePatch.platform ?: shoppingPlatformName(session.shoppingPackage),
            merchant = rulePatch.merchant
                ?: candidate.merchant
                ?: evidence.merchant,
            productDescription = candidate.productDescription,
            sourceType = "ai_vision",
            originalTransactionId = session.id,
            orderId = evidence.orderId ?: candidate.aiOrderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )

        // ---- 阶段五 T6：自动入账前去重检查（第 1/4 级）----
        // 第 4 级（相似度）命中或检查异常：不自动入账，转待确认由用户判断，
        // 宁可进待确认也不冒重复入账风险；第 1 级（orderId 精确命中）由
        // createBillWithDedup 内部处理——命中时合并进原账单不产生第二笔
        try {
            val guard = DuplicateGuard(database)
            val similarHits = guard.findSimilar(
                type = bill.type,
                amountMinor = bill.amountMinor,
                occurredAt = bill.occurredAt,
                accountId = bill.accountId,
                merchant = bill.merchant,
                excludeId = bill.id
            )
            if (similarHits.isNotEmpty()) {
                val reviewPending = pendingFromConflict(
                    session = session,
                    candidate = candidate,
                    evidence = evidence,
                    conflictReason = DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE,
                    now = now,
                    appliedRulesExtras = arrayOf(
                        "suspectedDuplicate" to guard.suspectedDuplicateHint(similarHits)
                    )
                )
                if (reviewPending != null) {
                    upsertPendingForSession(reviewPending)
                }
                return AutoCommitOutcome(
                    advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                    null
                )
            }
        } catch (error: RuntimeException) {
            // 去重检查异常：证据不丢弃，转待确认
            val fallbackPending = pendingFromConflict(
                session = session,
                candidate = candidate,
                evidence = evidence,
                conflictReason = DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED,
                now = now
            )
            if (fallbackPending != null) {
                upsertPendingForSession(fallbackPending)
            }
            return AutoCommitOutcome(
                advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                null
            )
        }

        // 写 Bill + 按类型联动余额 + 审计日志；嵌套 withTransaction 合并到外层事务；
        // 阶段五 T6：内部执行第 1 级查重——(sourceType, orderId) 命中时合并进
        // 既有账单（不产生第二笔），settledBillId 为实际落库账单
        val createOutcome = ledger.createBillWithDedup(bill, operator = AUTO_COMMIT_OPERATOR)
        val settledBillId = when (createOutcome) {
            is CreateBillOutcome.Committed -> createOutcome.bill.id
            is CreateBillOutcome.MergedInto -> createOutcome.bill.id
        }

        // 候选标记为已入账，记录 billId 供审计与前端追溯；保留规则命中键（rule:*）
        database.transactionCandidateDao().update(
            candidate.copy(
                appliedRules = mergeRulesJson(
                    candidate.appliedRules,
                    "sessionId" to session.id,
                    "decision" to AUTO_COMMITTED_STATUS,
                    "billId" to settledBillId,
                    "evidenceId" to evidence.id
                ),
                version = candidate.version + 1
            )
        )
        // 阶段四 T6：会话已自动入账，清理该会话仍在等待的待确认交易（忽略记录保留审计）
        database.pendingTransactionDao().findPendingBySessionId(session.id)?.let {
            database.pendingTransactionDao().delete(it)
        }
        return AutoCommitOutcome(advanceStatus(session, AUTO_COMMITTED_STATUS, now), settledBillId)
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
     * 阶段四 T6：从支付证据构建待确认交易。
     *
     * AI 尚未返回时仍传入 EVIDENCE_ONLY 候选 ID，保证候选、会话、待确认三者关联；
     * 商户缺失时按会话购物包名兜底。sourceType 继承证据来源；
     * 证据金额无效时返回 null（不生成待确认）。
     */
    private suspend fun pendingFromEvidenceOnly(
        session: CheckoutSessionEntity,
        evidence: PaymentEvidenceEntity,
        now: Long,
        candidateId: String? = null
    ): PendingTransactionEntity? {
        val amountMinor = evidence.amountMinor?.takeIf { it > 0L } ?: return null
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = candidateId ?: session.candidateId,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence.occurredAt ?: now,
            // platform 展示购物平台；账户判定仍用支付包名（evidence.platform）。
            platform = shoppingPlatformName(session.shoppingPackage),
            merchant = evidence.merchant,
            productDescription = null,
            orderId = evidence.orderId,
            accountId = defaultAccountIdForPayment(evidence.platform),
            categoryId = null,
            conflictReason = null,
            sourceType = normalizePaymentEvidenceSourceType(evidence.sourceType) ?: "notification",
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
     *
     * @param appliedRulesExtras 额外写入 appliedRules 的键值对
     *   （阶段五 T6 疑似重复提示等元数据），键值均为 String
     */
    private suspend fun pendingFromConflict(
        session: CheckoutSessionEntity,
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity?,
        conflictReason: String?,
        now: Long,
        vararg appliedRulesExtras: Pair<String, String>
    ): PendingTransactionEntity? {
        val amountMinor = evidence?.amountMinor?.takeIf { it > 0L }
            ?: candidate.amountMinor?.takeIf { it > 0L }
            ?: return null
        val appliedRules = if (appliedRulesExtras.isEmpty()) {
            // 未显式传附加元数据时维持既有行为（空对象），不继承候选规则记录
            "{}"
        } else {
            mergeRulesJson("{}", *appliedRulesExtras)
        }
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = candidate.id,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence?.occurredAt ?: candidate.aiOccurredAt ?: now,
            // platform 展示购物平台；账户判定仍用支付包名（evidence.platform）。
            platform = candidate.platform ?: shoppingPlatformName(session.shoppingPackage),
            merchant = candidate.merchant ?: evidence?.merchant,
            productDescription = candidate.productDescription,
            orderId = evidence?.orderId ?: candidate.aiOrderId,
            accountId = defaultAccountIdForPayment(evidence?.platform),
            categoryId = null,
            conflictReason = conflictReason ?: candidate.conflictReason,
            sourceType = "ai_vision",
            appliedRules = appliedRules,
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

    /** 支付证据先于 AI 返回时创建的临时占位候选；后续 AI 结果必须覆盖而非按重复跳过。 */
    private fun isEvidenceOnlyProvisionalCandidate(candidate: TransactionCandidateEntity?): Boolean {
        if (candidate == null) {
            return false
        }
        return runCatching {
            JSONObject(candidate.appliedRules).optString("decision") == EVIDENCE_ONLY_DECISION
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
        // 结算页 AI 不再提供 platform 与 merchant：
        // - platform 由系统包名权威推导（AI 曾读成"手机应用"这类无意义文字）；
        // - merchant 在结算页上实际读到的是商品品牌（如"金桂白牡丹"），
        //   把它当商户会污染账单与查重因子；真实商户名由支付通知链路提供。
        // 商品信息完整保留在 productDescription，由详情页描述栏展示。
        val aiPlatform = shoppingPlatformName(reference.shoppingPackage)
        val candidate = (existing ?: TransactionCandidateEntity(
            id = id,
            sessionId = reference.sessionId,
            amountMinor = fields.amountMinor,
            platform = aiPlatform,
            merchant = null,
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
            platform = aiPlatform ?: existing?.platform,
            merchant = existing?.merchant,
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
        appliedRules = mergeRulesJson(
            candidate.appliedRules,
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
            if (exact.isNotEmpty()) {
                return deduplicateEvidence(exact)
            }
            if (exact.size > 1) {
                // 不可达保留分支：同订单多证据的歧义判定已统一收敛到 deduplicateEvidence
            }
        }
        return deduplicateEvidence(evidences) ?: if (evidences.size == 1) {
            EvidenceSelection(evidences.single(), null)
        } else {
            EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        }
    }

    /**
     * 跨来源证据收敛：同一笔支付常同时产生"截图 AI 兜底"与"无障碍结果页"两条证据，
     * 两条路径的 sourceEventKey 生成规则不同，按主键查重挡不住，会在同一会话留下
     * 两条同额证据，被前端展示成"两个金额"。
     *
     * 规则：会话内已存在"同金额 +（订单号一致或均空）+ 时间窗内"的证据时，
     * 不再插入新行，而是把更全的字段（商户/订单号）合并回既有证据并提升
     * capturedAt；金额或订单号真正冲突时不收敛，照常插入，由候选评估或用户裁决。
     */
    private suspend fun mergeSessionEvidence(
        sessionId: String,
        incoming: PaymentEvidenceEntity,
        now: Long
    ): PaymentEvidenceEntity {
        val dao = database.paymentEvidenceDao()
        val similar = dao.getBySession(sessionId).firstOrNull { existing ->
            existing.amountMinor != null &&
                existing.amountMinor == incoming.amountMinor &&
                (existing.orderId.isNullOrBlank() && incoming.orderId.isNullOrBlank() ||
                    existing.orderId == incoming.orderId) &&
                existing.occurredAt != null &&
                incoming.occurredAt != null &&
                abs(incoming.occurredAt - existing.occurredAt) <=
                    PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS
        } ?: run {
            dao.insertIfAbsent(incoming)
            return dao.getById(incoming.id) ?: incoming
        }
        val merged = similar.copy(
            merchant = similar.merchant ?: incoming.merchant,
            orderId = similar.orderId ?: incoming.orderId,
            sourceType = incoming.sourceType,
            capturedAt = now
        )
        if (merged != similar) {
            dao.delete(similar)
            dao.insert(merged)
        }
        android.util.Log.i(
            "CheckoutPaymentMatcher",
            "支付证据跨来源收敛: session=${sessionId.take(8)}, amount=${merged.amountMinor}, " +
                "sources=${similar.sourceType}+${incoming.sourceType}, evidenceId=${merged.id.take(12)}"
        )
        return merged
    }

    /**
     * 收敛同一笔支付可能产生的多条证据（通知监听 + 支付结果页）。
     *
     * 一致证据只选 capturedAt 最新的一条参与匹配，避免把“同一次支付的两个来源”
     * 误判为 PAYMENT_EVIDENCE_AMBIGUOUS；只有金额/订单号/平台真正冲突时才保留歧义。
     * 无订单号时仅允许 60 秒内的同金额证据收敛，避免把连续两笔支付自动合并。
     */
    private fun deduplicateEvidence(
        evidences: List<PaymentEvidenceEntity>
    ): EvidenceSelection {
        if (evidences.isEmpty()) {
            return EvidenceSelection(null, "PAYMENT_EVIDENCE_MISSING")
        }
        if (evidences.size == 1) {
            return EvidenceSelection(evidences.single(), null)
        }
        val representative = evidences.maxByOrNull { it.capturedAt }
            ?: return EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        val representativeOrderId = representative.orderId
        val representativeAmount = representative.amountMinor
        val representativeOccurredAt = representative.occurredAt
        val compatible = evidences.all { evidence ->
            val samePlatform = evidence.platform == representative.platform
            val sameAmount = evidence.amountMinor != null &&
                evidence.amountMinor == representativeAmount
            val samePayment = if (!representativeOrderId.isNullOrBlank()) {
                evidence.orderId == representativeOrderId
            } else {
                evidence.orderId.isNullOrBlank() &&
                    representativeOccurredAt != null &&
                    evidence.occurredAt != null &&
                    abs((evidence.occurredAt ?: 0L) - (representativeOccurredAt ?: 0L)) <= 60_000L
            }
            samePlatform && sameAmount && samePayment
        }
        return if (compatible) {
            EvidenceSelection(representative, null)
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

    /**
     * 阶段五 T1 时机一：AI 候选生成时执行规则引擎。
     *
     * - Continue：set_platform/set_merchant 覆盖候选展示字段（用户确认的规则优先级
     *   高于 AI 视觉识别），set_category/set_account 记录命中，待入账/待确认时应用；
     * - Ignored：候选标记 decision=IGNORED，后续证据到达时不再入账或转待确认，审计留痕；
     * - ToReview：候选标记 decision=FORCED_REVIEW，证据匹配后由时机二强制转待确认。
     *
     * 命中摘要合并进 appliedRules（保留既有元数据键），命中计数由引擎在事务内累加。
     */
    private suspend fun applyRulesAtCandidate(
        candidate: TransactionCandidateEntity
    ): TransactionCandidateEntity {
        val decision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = candidate.amountMinor,
                platform = candidate.platform,
                merchant = candidate.merchant,
                description = candidate.productDescription,
                sourceType = "ai_vision"
            )
        )
        return when (decision) {
            is RuleEngine.Decision.Continue -> {
                if (decision.hits.isEmpty()) {
                    candidate
                } else {
                    candidate.copy(
                        platform = decision.patch.platform ?: candidate.platform,
                        merchant = decision.patch.merchant ?: candidate.merchant,
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            decision.hits
                        )
                    )
                }
            }
            is RuleEngine.Decision.Ignored -> {
                recordRuleDecisionAudit(candidate.id, "ignore", decision.hits)
                candidate.copy(
                    appliedRules = ruleEngine.mergeAppliedRules(
                        candidate.appliedRules,
                        decision.hits,
                        decisionOverride = RuleEngine.DECISION_IGNORED
                    )
                )
            }
            is RuleEngine.Decision.ToReview -> {
                candidate.copy(
                    appliedRules = ruleEngine.mergeAppliedRules(
                        candidate.appliedRules,
                        decision.hits,
                        decisionOverride = RuleEngine.DECISION_FORCED_REVIEW
                    )
                )
            }
        }
    }

    /**
     * 规则 ignore 决策审计：确定性 ID 幂等插入，保留命中规则清单供追溯，
     * 不写 Bill、不更新余额、不进待确认。
     */
    private suspend fun recordRuleDecisionAudit(
        entityId: String,
        action: String,
        hits: List<RuleEngine.RuleHit>
    ) {
        database.auditEntryDao().insertIfAbsent(
            AuditEntryEntity(
                id = "rule-$action:$entityId",
                entityType = "rule_decision",
                entityId = entityId,
                action = action,
                fieldName = null,
                oldValue = null,
                newValue = org.json.JSONArray().apply {
                    hits.forEach { hit ->
                        put("${hit.ruleId}:${hit.action}:${hit.actionValue ?: ""}")
                    }
                }.toString(),
                operator = "rule_engine",
                createdAt = nowMillis()
            )
        )
    }

    /** 在既有 appliedRules JSON 对象上合并决策元数据键，保留规则命中键（rule:*）。 */
    private fun mergeRulesJson(base: String, vararg values: Pair<String, Any?>): String {
        val merged = runCatching { JSONObject(base) }.getOrDefault(JSONObject())
        values.forEach { (key, value) -> merged.put(key, value ?: JSONObject.NULL) }
        return merged.toString()
    }

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
    expiresAt <= now ||
        (
            status in TERMINAL_SESSION_STATUSES &&
                journeyState != CheckoutJourneyState.AI_FAILED.wireValue
        )

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
