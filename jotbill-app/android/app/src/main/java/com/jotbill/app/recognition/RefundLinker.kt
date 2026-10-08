package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.PENDING_CONFIRM_AUDIT_TYPE
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.rules.RuleEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 无订单号时的退款组合候选回溯窗口：退款不会早于原消费，向前回溯 90 天。 */
private const val REFUND_CANDIDATE_WINDOW_MILLIS = 90L * 24 * 60 * 60 * 1000

/** 组合候选上限：候选只作待确认提示，超出后由用户在待确认中心自行搜索原交易。 */
private const val REFUND_CANDIDATE_LIMIT = 5

/** 退款自动入账的审计操作者标记。 */
private const val REFUND_AUTO_COMMIT_OPERATOR = "refund_auto_commit"

/** 与 DefaultDataInitializer 种子账户 ID 保持一致的支付平台资金账户映射。 */
private const val WECHAT_PAY_ACCOUNT_ID = "default-account-wechat"
private const val ALIPAY_ACCOUNT_ID = "default-account-alipay"

/** 与 DefaultDataInitializer 保持一致的退款种子分类。 */
private const val DEFAULT_REFUND_CATEGORY_ID = "default-category-income-refund"

/** 待确认冲突原因：原交易号命中多笔支出，无法唯一关联。 */
internal const val REFUND_CONFLICT_AMBIGUOUS = "ORIGINAL_AMBIGUOUS"

/**
 * 阶段五 T2：退款识别与原交易关联决策。
 *
 * 职责（见 docs/tdd/09_phase5_tasks.md 第 3.5 节与任务卡 T2）：
 * - 接收 Normalizer 解析出的退款证据（paymentStatus = REFUND）；
 * - 原交易号优先关联本地支出 Bill；无订单号时按金额+商户+平台+时间窗口生成候选（仅提示）；
 * - 多次部分退款按 originalTransactionId 累计，累计退款超过原实付金额时标记 REFUND_EXCEEDED；
 * - 原交易缺失时创建独立退款待确认（ORIGINAL_MISSING），不阻塞其他流程；
 * - 自动入账条件（严格执行，缺一不可）：原交易号唯一命中 + 累计退款 ≤ 原实付 + 可用资金账户；
 *   其余场景一律进入待确认，不静默入账；
 * - 幂等：账单 ID 与待确认 ID 均由 sourceEventKey 确定性生成，重复通知只处理一次；
 *   用户确认过的退款通过审计墓碑（refund_confirm）防止重复通知再次挂账。
 *
 * 退款余额口径（沿用 BalanceCalculator）：refund 计入收入侧统计、金额加回退款接收账户。
 * 接收账户以通知为准，但 MVP 无法把"退回至XX"文本可靠映射到本地账户，
 * 按 原交易账户（原路退回）> 支付包默认账户 回退。
 */
internal class RefundLinker(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ledger = LedgerRepository(database)
    private val ruleEngine = RuleEngine(database)

    /** 退款处理结果，供调用方记录日志与面包屑。 */
    internal sealed class RefundOutcome {
        /** 自动入账成功：退款 Bill 已写入并联动余额。 */
        data class AutoCommitted(val billId: String, val originalBillId: String) : RefundOutcome()

        /** 进入待确认中心：conflictReason 说明原因。 */
        data class PendingCreated(val pendingId: String, val conflictReason: String) : RefundOutcome()

        /** 重复通知：该 sourceEventKey 已处理过（账单/待确认/已确认墓碑存在）。 */
        object Duplicate : RefundOutcome()

        /** 规则 ignore 命中：不挂账、不进待确认，审计留痕。 */
        object Ignored : RefundOutcome()
    }

    /**
     * 处理一条退款证据：幂等校验 → 规则执行 → 原交易关联 → 自动入账或待确认。
     * 全程在单个 Room 事务内，任一写入失败整体回滚。
     */
    suspend fun processRefund(input: PaymentEvidenceInput): RefundOutcome = database.withTransaction {
        val now = nowMillis()
        if (input.amountMinor !in 1L..MAX_SAFE_MINOR_UNIT || input.occurredAt <= 0L) {
            return@withTransaction RefundOutcome.Duplicate
        }

        // ---- 幂等：确定性 ID + 用户确认墓碑，同一退款事件只处理一次 ----
        val billId = stableId("refund-bill", input.sourceEventKey)
        val pendingId = stableId("pending-refund", input.sourceEventKey)
        if (database.billDao().getById(billId) != null ||
            database.pendingTransactionDao().getById(pendingId) != null ||
            database.auditEntryDao()
                .getByEntity(PENDING_CONFIRM_AUDIT_TYPE, pendingId).isNotEmpty()
        ) {
            return@withTransaction RefundOutcome.Duplicate
        }

        // ---- 规则引擎（阶段五 T1：纯通知路径同样执行规则）----
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = input.amountMinor,
                platform = input.sourcePackage,
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
            return@withTransaction RefundOutcome.Ignored
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
        )
        val forcedReview = ruleDecision is RuleEngine.Decision.ToReview

        // ---- 原交易关联：订单号优先，感知多笔歧义 ----
        val orderIdMatches = input.orderId
            ?.takeIf { it.isNotBlank() }
            ?.let { database.billDao().findExpensesByOrderId(it) }
            .orEmpty()
        val original: BillEntity? = orderIdMatches.singleOrNull()

        // 无订单号命中时的组合候选（仅待确认提示，不作为自动入账依据）
        val candidates: List<BillEntity> = if (orderIdMatches.isEmpty()) {
            database.billDao().findRefundCandidates(
                merchant = input.merchant?.takeIf { it.isNotBlank() },
                platform = input.sourcePackage,
                minAmountMinor = input.amountMinor,
                startDate = input.occurredAt - REFUND_CANDIDATE_WINDOW_MILLIS,
                endDate = input.occurredAt,
                limit = REFUND_CANDIDATE_LIMIT
            )
        } else {
            emptyList()
        }

        // ---- 累计退款校验（多次部分退款口径）----
        val refundedBefore = original
            ?.let { database.billDao().sumRefundsByOriginalId(it.id) }
            ?: 0L
        val exceeded = original != null &&
            refundedBefore + input.amountMinor > original.amountMinor

        // ---- 资金账户解析：规则指定 > 原交易账户（原路退回）> 支付包默认 ----
        val accountId = rulePatch.accountId
            ?: original?.accountId?.let { activeAccountId(it) }
            ?: packageDefaultAccountId(input.sourcePackage)

        // ---- 决策：自动入账条件严格判定，其余一律待确认 ----
        val conflictReason: String? = when {
            forcedReview -> RuleEngine.CONFLICT_RULE_FORCED_REVIEW
            orderIdMatches.size > 1 -> REFUND_CONFLICT_AMBIGUOUS
            original == null -> "ORIGINAL_MISSING"
            exceeded -> "REFUND_EXCEEDED"
            accountId == null -> "ACCOUNT_UNAVAILABLE"
            else -> null
        }

        if (conflictReason == null) {
            val bill = BillEntity(
                id = billId,
                amountMinor = input.amountMinor,
                type = "refund",
                occurredAt = input.occurredAt,
                description = input.merchant ?: "退款",
                categoryId = rulePatch.categoryId ?: refundCategoryId(),
                accountId = accountId,
                targetAccountId = null,
                platform = original?.platform ?: input.sourcePackage,
                merchant = input.merchant ?: original?.merchant,
                productDescription = null,
                sourceType = "notification",
                originalTransactionId = original!!.id,
                orderId = input.orderId,
                createdAt = now,
                updatedAt = now,
                version = 1
            )
            // 写 Bill + refund 口径联动余额（收入侧，金额加回账户）+ 审计日志。
            // 按 outcome 分支：命中去重时未 insert、未动余额，返回的必须是
            // 既有账单的 id，而非那个从未落库的 UUID。
            val outcome = ledger.createBillWithDedup(bill, operator = REFUND_AUTO_COMMIT_OPERATOR)
            val settledBillId = when (outcome) {
                is CreateBillOutcome.Committed -> outcome.bill.id
                is CreateBillOutcome.MergedInto -> outcome.bill.id
            }
            return@withTransaction RefundOutcome.AutoCommitted(settledBillId, original.id)
        }

        val pending = PendingTransactionEntity(
            id = pendingId,
            candidateId = null,
            sessionId = null,
            amountMinor = input.amountMinor,
            type = "refund",
            occurredAt = input.occurredAt,
            platform = input.sourcePackage,
            merchant = input.merchant,
            productDescription = null,
            orderId = input.orderId,
            accountId = accountId ?: original?.accountId?.let { activeAccountId(it) },
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
                    if (candidates.isNotEmpty()) {
                        put(
                            "candidates",
                            JSONArray().apply { candidates.forEach { put(it.id) } }
                        )
                    }
                    if (original != null) {
                        put("refundedMinor", refundedBefore)
                        put("originalAmountMinor", original.amountMinor)
                    }
                    input.receivingAccountHint?.let { put("receivingAccountHint", it) }
                }.toString(),
                ruleDecision.hits
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
        }
        RefundOutcome.PendingCreated(pending.id, conflictReason)
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
