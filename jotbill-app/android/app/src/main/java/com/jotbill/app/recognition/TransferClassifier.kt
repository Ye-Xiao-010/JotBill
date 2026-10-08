package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.PENDING_CONFIRM_AUDIT_TYPE
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.rules.RuleEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 与 DefaultDataInitializer 种子账户 ID 保持一致的支付平台资金账户映射。 */
private const val WECHAT_PAY_ACCOUNT_ID = "default-account-wechat"
private const val ALIPAY_ACCOUNT_ID = "default-account-alipay"

/** 转账/还款自动入账的审计操作者标记。 */
private const val TRANSFER_AUTO_COMMIT_OPERATOR = "transfer_auto_commit"

/** 账户 kind 常量（与 AccountEntity 种子数据一致）。 */
private const val ACCOUNT_KIND_LIABILITY = "liability"

/**
 * 阶段五 T3：转账与还款的方向与账户归属决策。
 *
 * 职责（见 docs/tdd/09_phase5_tasks.md 第 3.5 节与任务卡 T3）：
 * - 接收 Normalizer 解析出的转账/还款证据（paymentStatus = TRANSFER/REPAYMENT）；
 * - 源账户取支付应用默认账户（沿用阶段四映射）；
 * - 目标账户只按"文本关键词 + 本地启用账户名精确/包含匹配"推断，不做模糊猜测；
 * - 自动入账条件（严格执行）：
 *   转账 = 源与目标均精确匹配启用账户 且 源 ≠ 目标；
 *   还款 = 源与目标均精确匹配启用账户 且 目标为负债类型（欠款减少）；
 * - 收款人命中（转账给他人）按支出语义进待确认，由用户改类型，不自动判定为自有账户转账；
 * - 推断失败、源=目标、目标非负债（还款）一律 ACCOUNT_UNRESOLVED 进待确认；
 * - 转账/还款不计收支，余额为双账户调整（复用修复后的 applyBalanceDelta）；
 * - 幂等：确定性账单 ID + 待确认 ID + 用户确认审计墓碑，重复通知只处理一次。
 */
internal class TransferClassifier(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ledger = LedgerRepository(database)
    private val ruleEngine = RuleEngine(database)

    /** 转账/还款处理结果，供调用方记录日志与面包屑。 */
    internal sealed class TransferOutcome {
        /** 自动入账成功：双账户余额已联动调整。 */
        data class AutoCommitted(val billId: String, val type: String) : TransferOutcome()

        /** 进入待确认中心：conflictReason 说明原因。 */
        data class PendingCreated(
            val pendingId: String,
            val conflictReason: String,
            val pendingType: String
        ) : TransferOutcome()

        /** 重复通知：该 sourceEventKey 已处理过（账单/待确认/确认墓碑存在）。 */
        object Duplicate : TransferOutcome()

        /** 规则 ignore 命中：不挂账、不进待确认，审计留痕。 */
        object Ignored : TransferOutcome()
    }

    /**
     * 处理一条转账/还款证据：幂等校验 → 规则执行 → 账户归属判定 → 自动入账或待确认。
     * 全程在单个 Room 事务内，任一写入失败整体回滚。
     */
    suspend fun processTransfer(input: PaymentEvidenceInput): TransferOutcome = database.withTransaction {
        // D-3：通知派生链路在 v1 整体停用。
        //
        // 本分类器是三条绕过链路里后果最重的一条：它会直写 Bill、自动入账、
        // 联动双账户余额；且收款人命中时把 pending 定型为 `type="expense"`，
        // 而 PendingPromptPolicy 只按 `type == "expense"` 放行 ⇒ 一条纯通知来源的
        // "转账给他人"会命中付款确认弹窗，用户点"确认入账"即写成**支出**。
        // 精简版下弹窗是唯一的自动记账入口，这条路径必须彻底关掉。
        if (isNotificationPipelineDisabledInV1()) {
            JotBillLog.i(
                "TransferClassifier",
                "转账来源在 v1 已停用(D-3)，未落账: amount=${input.amountMinor}"
            )
            return@withTransaction TransferOutcome.Duplicate
        }
        val now = nowMillis()
        if (input.amountMinor !in 1L..MAX_SAFE_MINOR_UNIT || input.occurredAt <= 0L) {
            return@withTransaction TransferOutcome.Duplicate
        }
        val isRepayment = input.paymentStatus == "REPAYMENT"
        val billType = if (isRepayment) "repayment" else "transfer"

        // ---- 幂等：确定性 ID + 用户确认墓碑，同一事件只处理一次 ----
        val billId = stableId("$billType-bill", input.sourceEventKey)
        val pendingId = stableId("pending-$billType", input.sourceEventKey)
        if (database.billDao().getById(billId) != null ||
            database.pendingTransactionDao().getById(pendingId) != null ||
            database.auditEntryDao()
                .getByEntity(PENDING_CONFIRM_AUDIT_TYPE, pendingId).isNotEmpty()
        ) {
            return@withTransaction TransferOutcome.Duplicate
        }

        // ---- 规则引擎（阶段五 T1：纯通知路径同样执行规则）----
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = input.amountMinor,
                platform = input.sourcePackage,
                merchant = null,
                description = input.targetAccountHint ?: input.recipientName,
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
            return@withTransaction TransferOutcome.Ignored
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
        )
        val forcedReview = ruleDecision is RuleEngine.Decision.ToReview

        // ---- 源账户：规则指定 > 支付应用默认账户 ----
        val sourceAccount = rulePatch.accountId?.let { activeAccount(it) }
            ?: packageDefaultAccount(input.sourcePackage)

        // ---- 目标账户：文本提示 + 本地启用账户名精确/包含匹配 ----
        val targetAccount = input.targetAccountHint
            ?.takeIf { it.isNotBlank() }
            ?.let { matchAccountByName(it) }

        // ---- 决策：他人转账 / 账户未解析 / 约束不满足 → 待确认 ----
        val conflictReason: String? = when {
            forcedReview -> RuleEngine.CONFLICT_RULE_FORCED_REVIEW
            // 收款人命中：无法确认对方账户属于用户，按支出语义待确认由用户改类型
            input.recipientName != null -> "ACCOUNT_UNRESOLVED"
            targetAccount == null -> "ACCOUNT_UNRESOLVED"
            sourceAccount == null -> "ACCOUNT_UNRESOLVED"
            sourceAccount.id == targetAccount.id -> "ACCOUNT_UNRESOLVED"
            // 还款目标必须是负债账户（欠款减少），否则语义不成立
            isRepayment && targetAccount.kind != ACCOUNT_KIND_LIABILITY -> "ACCOUNT_UNRESOLVED"
            else -> null
        }

        // ---- 自动入账：双账户精确匹配且约束满足 ----
        if (conflictReason == null) {
            val source = sourceAccount!!
            val target = targetAccount!!
            val bill = BillEntity(
                id = billId,
                amountMinor = input.amountMinor,
                type = billType,
                occurredAt = input.occurredAt,
                description = input.targetAccountHint
                    ?: if (isRepayment) "还款" else "转账",
                categoryId = rulePatch.categoryId,
                accountId = source.id,
                targetAccountId = target.id,
                // 转账/还款不是消费行为，不填消费平台
                platform = null,
                merchant = null,
                productDescription = null,
                sourceType = "notification",
                originalTransactionId = null,
                orderId = input.orderId,
                createdAt = now,
                updatedAt = now,
                version = 1
            )
            // 写 Bill + 双账户余额联动（转账源减目标加；还款源减、负债目标减欠款）+ 审计日志。
            // 必须按 outcome 分支：命中去重第 1 级时 createBillWithDedup **不 insert**、
            // 不动余额，只把新证据字段合并进既有账单。此前这里忽略返回值并一律上报
            // AutoCommitted(bill.id)，而 bill.id 是一个从未落库的 UUID ——
            // 上层会据此认为"已入账"并把该 ID 当作退款/转账的关联目标。
            val outcome = ledger.createBillWithDedup(bill, operator = TRANSFER_AUTO_COMMIT_OPERATOR)
            val settledBillId = when (outcome) {
                is CreateBillOutcome.Committed -> outcome.bill.id
                is CreateBillOutcome.MergedInto -> outcome.bill.id
            }
            return@withTransaction TransferOutcome.AutoCommitted(settledBillId, billType)
        }

        // ---- 待确认：类型按语义预填，他人转账按支出语义由用户改类型 ----
        val pendingType = if (input.recipientName != null) "expense" else billType
        val pending = PendingTransactionEntity(
            id = pendingId,
            candidateId = null,
            sessionId = null,
            amountMinor = input.amountMinor,
            type = pendingType,
            occurredAt = input.occurredAt,
            platform = input.sourcePackage,
            merchant = null,
            productDescription = null,
            orderId = input.orderId,
            // 源账户可用时预填，用户确认时可直接选用
            accountId = sourceAccount?.id ?: rulePatch.accountId,
            categoryId = null,
            conflictReason = conflictReason,
            sourceType = "notification",
            appliedRules = ruleEngine.mergeAppliedRules(
                JSONObject().apply {
                    put("decision", if (isRepayment) "REPAYMENT" else "TRANSFER")
                    input.targetAccountHint?.let { put("targetHint", it) }
                    input.recipientName?.let { put("recipient", it) }
                    targetAccount?.let { put("suggestedTargetAccountId", it.id) }
                    sourceAccount?.let { put("sourceAccountId", it.id) }
                }.toString(),
                ruleDecision.hits
            ),
            originalBillId = null,
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
        TransferOutcome.PendingCreated(pending.id, conflictReason, pendingType)
    }

    /** 账户存在且启用时返回实体，否则 null。 */
    private suspend fun activeAccount(accountId: String): AccountEntity? {
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) account else null
    }

    /** 支付包名到种子资金账户的固定映射（与 CheckoutPaymentMatcher 保持一致）。 */
    private suspend fun packageDefaultAccount(paymentPackage: String?): AccountEntity? =
        when (paymentPackage) {
            "com.tencent.mm" -> activeAccount(WECHAT_PAY_ACCOUNT_ID)
            "com.eg.android.AlipayGphone" -> activeAccount(ALIPAY_ACCOUNT_ID)
            else -> null
        }

    /**
     * 目标账户名匹配：先精确匹配（忽略大小写），再双向包含匹配。
     * 只匹配启用账户；匹配到多个同名账户时视为歧义返回 null（转待确认）。
     * 例：提示"转到银行卡"可命中本地"银行卡"账户；"还款至花呗"命中"花呗"。
     */
    private suspend fun matchAccountByName(hint: String): AccountEntity? {
        val normalizedHint = hint.trim()
        if (normalizedHint.isEmpty()) return null
        val activeAccounts = database.accountDao().getAll().filter { it.isActive }
        if (activeAccounts.isEmpty()) return null

        // 精确匹配（忽略大小写）
        val exactMatches = activeAccounts.filter {
            it.name.equals(normalizedHint, ignoreCase = true)
        }
        if (exactMatches.size == 1) return exactMatches.first()
        if (exactMatches.size > 1) return null

        // 包含匹配：账户名包含提示，或提示包含账户名（账户名至少 2 字符防误匹配）
        val containsMatches = activeAccounts.filter { account ->
            account.name.length >= 2 &&
                (account.name.contains(normalizedHint, ignoreCase = true) ||
                    normalizedHint.contains(account.name, ignoreCase = true))
        }
        return containsMatches.singleOrNull()
    }

    /** 确定性 ID：SHA-256(prefix|payload)，同一事件重复到达生成相同 ID。 */
    private fun stableId(prefix: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }
}
