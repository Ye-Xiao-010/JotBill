package com.jotbill.app.recognition

import com.jotbill.app.bookkeeping.AccountInference
import com.jotbill.app.bookkeeping.AutoBookkeeper
import com.jotbill.app.bookkeeping.SignalBillWriter
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.data.PENDING_CONFIRM_AUDIT_TYPE
import com.jotbill.app.data.RefundCapGuard
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.rules.RuleEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 跨来源同额退款去重窗（2026-11-16 真机）：同一笔退款可能由拼多多通知
 * （「退款成功通知」）、微信/支付宝退款通知、银行到账短信等多条信号描述，
 * 各来源 sourceEventKey 不同。2 分钟内已有同额退款卡视为同一笔，不建第二张。
 */
private const val REFUND_CROSS_SOURCE_DEDUP_WINDOW_MILLIS = 2 * 60 * 1000L

// P14：支付平台种子账户 ID 唯一出处为 [AccountInference]，本文件不再私有定义。
private val WECHAT_PAY_ACCOUNT_ID = AccountInference.WECHAT_PAY_ACCOUNT_ID
private val ALIPAY_ACCOUNT_ID = AccountInference.ALIPAY_ACCOUNT_ID

/** 与 DefaultDataInitializer 保持一致的退款种子分类。 */
private const val DEFAULT_REFUND_CATEGORY_ID = "default-category-income-refund"

/**
 * 退款识别与原交易关联决策（首笔信号模型，2026-11-16 调整 / 2026-11-17 审查修复）。
 *
 * 职责：
 * - 接收 Normalizer 解析出的退款证据（paymentStatus = REFUND）；
 * - 原交易号唯一命中时绑定本地支出 Bill；
 * - **首笔退款信号直接成账**（交给 [SignalBillWriter.recordRefund]），60 秒内同额
 *   后续信号只做信息补全；账户按「规则 > 退款去向提示 > 原单账户 > 包名映射 > 现金」解析；
 * - 只有规则 send_to_review、订单号撞号、入账失败等场景才落待确认（可重试、不静默丢）；
 * - 累计退款超过原实付仅审计提示（首笔信号必须落账，由用户核对）；
 * - 幂等：待确认 ID 确定性生成；账单幂等由 [SignalBillWriter] 的事务内事件键承担。
 *
 * 退款余额口径（沿用 BalanceCalculator）：refund 计入收入侧统计、金额加回退款接收账户。
 */
internal class RefundLinker(
    private val database: JotBillDatabase,
    private val appContext: android.content.Context? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ruleEngine = RuleEngine(database)

    /** 退款处理结果，供调用方记录日志与面包屑。 */
    internal sealed class RefundOutcome {
        /** 自动入账成功：退款 Bill 已写入并联动余额。 */
        data class AutoCommitted(val billId: String, val originalBillId: String?) : RefundOutcome()

        /** 后续同笔信号补全既有退款账单（未新建）。 */
        data class Enriched(val billId: String) : RefundOutcome()

        /** 进入待确认中心：conflictReason 说明原因。 */
        data class PendingCreated(val pendingId: String, val conflictReason: String) : RefundOutcome()

        /** 重复通知：该 sourceEventKey 已处理过（账单/待确认/已确认墓碑存在）。 */
        object Duplicate : RefundOutcome()

        /** 规则 ignore 命中：不挂账、不进待确认，审计留痕。 */
        object Ignored : RefundOutcome()
    }

    /**
     * 处理一条退款证据：幂等校验 → 规则执行 → 原交易关联 → 入账或待确认。
     * 账单写入与幂等键在 [SignalBillWriter] 的同一事务内完成；待确认卡按确定性 ID 幂等插入。
     */
    suspend fun processRefund(input: PaymentEvidenceInput): RefundOutcome {
        // 首笔信号模型（2026-11-16）：退款不再受自动入账闸限制，直接成账；
        // 是否落待确认只由规则 send_to_review / 撞号 / 入账失败决定。
        val now = nowMillis()
        if (input.amountMinor !in 1L..MAX_SAFE_MINOR_UNIT) {
            return RefundOutcome.Duplicate
        }

        // ---- 幂等：确定性 ID + 用户确认墓碑，同一退款事件只处理一次 ----
        // billId 用于兼容旧模型已按 sourceEventKey 落库的退款账单。
        val billId = stableId("refund-bill", input.sourceEventKey)
        val pendingId = stableId("pending-refund", input.sourceEventKey)
        if (database.billDao().getById(billId) != null ||
            database.pendingTransactionDao().getById(pendingId) != null ||
            database.auditEntryDao()
                .getByEntity(PENDING_CONFIRM_AUDIT_TYPE, pendingId).isNotEmpty()
        ) {
            return RefundOutcome.Duplicate
        }

        // ---- 跨来源同额退款去重（2026-11-16 真机）----
        // 拼多多「退款成功通知」、微信/支付宝退款通知、银行到账短信会描述同一笔
        // 退款，键族各不相同；2 分钟内同额退款卡已存在即判为同一笔，不建第二张。
        val recentRefundCard = database.pendingTransactionDao().findRecentPendingRefundByAmount(
            amountMinor = input.amountMinor,
            windowStart = now - REFUND_CROSS_SOURCE_DEDUP_WINDOW_MILLIS,
            // 只回溯不前瞻：未来时间戳的卡是时钟异常，不应挡住当前这笔真实退款。
            windowEnd = now
        )
        if (recentRefundCard != null) {
            JotBillLog.i(
                "RefundLinker",
                "跨来源同额退款卡已存在，判为同一笔: pending=${recentRefundCard.id.take(16)}, " +
                    "amount=${input.amountMinor}, source=${input.sourcePackage}"
            )
            return RefundOutcome.Duplicate
        }

        // ---- 规则引擎（阶段五 T1：纯通知路径同样执行规则）----
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = input.amountMinor,
                platform = input.sourcePackage,
                platformAliases = listOfNotNull(AutoBookkeeper.channelLabel(input.sourcePackage)),
                merchant = input.merchant,
                description = null,
                sourceType = "notification"
            )
        )
        if (ruleDecision is RuleEngine.Decision.Ignored) {
            database.auditEntryDao().insertIfAbsent(
                AuditEntryEntity(
                    id = "rule-ignore:$pendingId",
                    entityType = "rule_decision",
                    entityId = pendingId,
                    action = "ignore",
                    fieldName = null,
                    oldValue = null,
                    newValue = JSONArray().apply {
                        ruleDecision.hits.forEach { hit ->
                            put("${hit.ruleId}:${hit.action}:${hit.actionValue ?: ""}")
                        }
                    }.toString(),
                    operator = "rule_engine",
                    createdAt = now
                )
            )
            return RefundOutcome.Ignored
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
        )
        val forcedReview = ruleDecision is RuleEngine.Decision.ToReview

        // ---- 原交易关联：订单号唯一命中才绑定（否则退化为无原单退款账单）----
        val orderIdMatches = input.orderId
            ?.takeIf { it.isNotBlank() }
            ?.let { database.billDao().findExpensesByOrderId(it) }
            .orEmpty()
        val original: BillEntity? = orderIdMatches.singleOrNull()
        if (orderIdMatches.size > 1) {
            JotBillLog.i(
                "RefundLinker",
                "退款订单号命中多笔原单，不绑定: orderId=${input.orderId?.take(16)}, " +
                    "count=${orderIdMatches.size}"
            )
        }

        // ---- 累计退款上限（仅审计提示；首笔信号必须落账，不再转待确认）----
        if (original != null) {
            val capState = try {
                RefundCapGuard(database).evaluate(input.amountMinor, original.id)
            } catch (error: RuntimeException) {
                null
            }
            if (capState?.exceeded == true) {
                JotBillLog.w(
                    "RefundLinker",
                    "退款累计超过原实付，仍按信号入账(请用户核对): " +
                        "original=${original.id.take(8)}, amount=${input.amountMinor}, " +
                        "refundedBefore=${capState.refundedBeforeMinor}"
                )
            }
        }

        // ---- 规则强制复核（send_to_review）：落待确认，不静默入账 ----
        if (forcedReview) {
            val reason = (ruleDecision as RuleEngine.Decision.ToReview).reason
            return createRefundPending(
                input = input,
                pendingId = pendingId,
                rulePatch = rulePatch,
                ruleHits = ruleDecision.hits,
                original = original,
                orderIdMatches = orderIdMatches,
                conflictReason = reason,
                now = now
            )
        }

        // ---- 首笔信号直接入账（2026-11-16 模型调整）----
        val outcome = try {
            SignalBillWriter(appContext, database).recordRefund(
                SignalBillWriter.RefundSignal(
                    amountMinor = input.amountMinor,
                    occurredAt = input.occurredAt,
                    merchant = rulePatch.merchant ?: input.merchant,
                    orderId = input.orderId,
                    platform = original?.platform ?: input.sourcePackage,
                    sourcePackage = input.sourcePackage,
                    sourceType = if (input.sourcePackage == "com.android.mms") "sms" else "notification",
                    receivingHints = listOf(input.receivingAccountHint),
                    bankCard = input.bankCard,
                    originalBillId = original?.id,
                    ruleAccountId = rulePatch.accountId,
                    categoryId = rulePatch.categoryId,
                    sourceEventKey = input.sourceEventKey
                )
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w("RefundLinker", "退款信号入账失败", error)
            return createRefundPending(
                input, pendingId, rulePatch, ruleDecision.hits, original,
                orderIdMatches, "REFUND_BOOK_FAILED", now
            )
        }
        return when (outcome) {
            is SignalBillWriter.Outcome.Created ->
                RefundOutcome.AutoCommitted(outcome.billId, original?.id)
            is SignalBillWriter.Outcome.Enriched ->
                RefundOutcome.Enriched(outcome.billId)
            is SignalBillWriter.Outcome.Duplicate -> RefundOutcome.Duplicate
            is SignalBillWriter.Outcome.Ignored -> {
                JotBillLog.i("RefundLinker", "退款信号被丢弃: ${outcome.reason}")
                RefundOutcome.Ignored
            }
            is SignalBillWriter.Outcome.NeedsReview -> createRefundPending(
                input, pendingId, rulePatch, ruleDecision.hits, original,
                orderIdMatches, outcome.reason, now
            )
            is SignalBillWriter.Outcome.Failed -> createRefundPending(
                input, pendingId, rulePatch, ruleDecision.hits, original,
                orderIdMatches, outcome.reason, now
            )
        }
    }

    /**
     * 退款待确认卡（规则强制复核 / 撞号 / 入账失败）：确定性 ID 幂等，
     * 证据与候选原单不丢；emit 变更供待确认中心与提醒使用。
     */
    private suspend fun createRefundPending(
        input: PaymentEvidenceInput,
        pendingId: String,
        rulePatch: RuleEngine.FieldPatch,
        ruleHits: List<RuleEngine.RuleHit>,
        original: BillEntity?,
        orderIdMatches: List<BillEntity>,
        conflictReason: String,
        now: Long
    ): RefundOutcome.PendingCreated {
        val pending = PendingTransactionEntity(
            id = pendingId,
            candidateId = null,
            sessionId = null,
            amountMinor = input.amountMinor,
            type = "refund",
            occurredAt = input.occurredAt.takeIf { it > 0L } ?: now,
            platform = input.sourcePackage,
            merchant = input.merchant,
            productDescription = null,
            orderId = input.orderId,
            accountId = rulePatch.accountId
                ?: original?.accountId?.let { activeAccountId(it) }
                ?: packageDefaultAccountId(input.sourcePackage),
            categoryId = rulePatch.categoryId ?: refundCategoryId(),
            conflictReason = conflictReason,
            sourceType = "notification",
            appliedRules = ruleEngine.mergeAppliedRules(
                JSONObject().apply {
                    put("decision", "REFUND")
                    original?.let { put("originalBillId", it.id) }
                    if (orderIdMatches.size > 1) {
                        put(
                            "candidates",
                            JSONArray().apply { orderIdMatches.forEach { put(it.id) } }
                        )
                    }
                    input.receivingAccountHint?.let { put("receivingAccountHint", it) }
                }.toString(),
                ruleHits
            ),
            originalBillId = original?.id,
            createdAt = now,
            version = 1
        )
        val inserted = database.pendingTransactionDao().insertIfAbsent(pending)
        if (inserted != -1L) {
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = pending.id,
                    type = pending.type,
                    amountMinor = pending.amountMinor,
                    sourceType = pending.sourceType,
                    conflictReason = pending.conflictReason,
                    occurredAt = pending.occurredAt,
                    isNew = true
                )
            )
            JotBillLog.i(
                "RefundLinker",
                "退款证据转待确认: pending=${pending.id.take(16)}, reason=$conflictReason"
            )
        }
        return RefundOutcome.PendingCreated(pending.id, conflictReason)
    }

    /** 账户存在且启用时返回 ID，否则 null。 */
    private suspend fun activeAccountId(accountId: String): String? {
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) accountId else null
    }

    /** 支付包名到种子资金账户的固定映射（与 CheckoutPaymentMatcher 保持一致）。 */
    private suspend fun packageDefaultAccountId(paymentPackage: String?): String? =
        when (paymentPackage) {
            "com.tencent.mm" -> activeAccountId(WECHAT_PAY_ACCOUNT_ID)
            "com.eg.android.AlipayGphone" -> activeAccountId(ALIPAY_ACCOUNT_ID)
            else -> null
        }

    /** 退款种子分类存在时返回其 ID，被删除时返回 null（账单分类留空）。 */
    private suspend fun refundCategoryId(): String? =
        if (database.categoryDao().getById(DEFAULT_REFUND_CATEGORY_ID) != null) {
            DEFAULT_REFUND_CATEGORY_ID
        } else {
            null
        }

    /** 确定性 ID：SHA-256(prefix|payload)，同一退款事件重复到达生成相同 ID。 */
    private fun stableId(prefix: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }
}
