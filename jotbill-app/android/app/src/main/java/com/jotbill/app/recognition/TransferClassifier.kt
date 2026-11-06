package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.bookkeeping.AccountInference
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
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.RuleEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

// P14：支付平台种子账户 ID 唯一出处为 [AccountInference]，本文件不再私有定义。
private val WECHAT_PAY_ACCOUNT_ID = AccountInference.WECHAT_PAY_ACCOUNT_ID
private val ALIPAY_ACCOUNT_ID = AccountInference.ALIPAY_ACCOUNT_ID

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
 * - 收款人命中（转账给他人）按 transfer 语义进待确认（P13：方向与账户都由用户
 *   显式决策），不自动判定为自有账户转账，也不再预填为消费支出；
 * - 推断失败、源=目标、目标非负债（还款）一律 ACCOUNT_UNRESOLVED 进待确认；
 * - 转账/还款不计收支，余额为双账户调整（复用修复后的 applyBalanceDelta）；
 * - 幂等：确定性账单 ID + 待确认 ID + 用户确认审计墓碑，重复通知只处理一次。
 */
internal class TransferClassifier(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /**
     * 自动入账闸的测试缝：生产默认走 [isNotificationAutoCommitAllowed]（当前恒
     * false，自动入账分支不可达）；单测注入 true 以驱动撞号回落路径（复核发现的
     * 潜伏缺口：该分支未接 OrderConflictException，闸一旦放开异常会逃出事务）。
     */
    private val autoCommitAllowed: () -> Boolean = { isNotificationAutoCommitAllowed() }
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
        // N-1：通知来源放行（D-3 整体停用闸已删）。本分类器直写 Bill、自动入账、
        // 联动双账户余额，是三条绕过链路里后果最重的一条；故自动入账仍由
        // [isNotificationAutoCommitAllowed] 扣住，证据先落待确认。
        // P13：收款人命中的待确认卡已改为 transfer 语义预填（不再定型 expense），
        // 不会命中付款确认弹窗（PendingPromptPolicy 只放行 expense）。
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

        // ---- M2：跨腿查重 —— 结果页个人转账卡已存在（同收款人）⇒ 归并，不再双卡 ----
        // 结果页腿（recordPersonalTransfer）与通知腿是两条独立键族，同一笔微信转账
        // 会各落一张卡。命中即归并留痕并返回 Duplicate；收款人缺失/不等绝不合并。
        if (!isRepayment && input.recipientName != null) {
            val matcher = CheckoutPaymentMatcher(database)
            val crossDup = matcher.findSameTransferCard(
                platform = input.sourcePackage,
                amountMinor = input.amountMinor,
                recipient = input.recipientName,
                occurredAt = input.occurredAt
            )
            if (crossDup != null) {
                matcher.mergeTransferSignalInto(
                    existing = crossDup,
                    incomingSourceEventKey = input.sourceEventKey,
                    incomingReason = ""
                )
                JotBillLog.i(
                    "TransferClassifier",
                    "通知转账与结果页转账归并为单卡: pending=${crossDup.id.take(16)}, " +
                        "amount=${input.amountMinor}, recipient=${input.recipientName}"
                )
                return@withTransaction TransferOutcome.Duplicate
            }
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
            // N-1：放在各分类器自己的冲突码**之后** —— 有更具体原因时优先展示具体原因。
            !autoCommitAllowed() -> CONFLICT_AUTO_COMMIT_DEFERRED
            else -> null
        }

        // 撞号回落标记（复核修复）：自动入账撞号时改为待确认原因，证据转建卡段。
        var effectiveConflictReason = conflictReason

        // ---- 自动入账：双账户精确匹配且约束满足 ----
        if (effectiveConflictReason == null) {
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
            //
            // 复核修复（M1 潜伏缺口，2026-11-06 探针实证，留存
            // [com.jotbill.app.data.RoomNestedTransactionProbeTest]）：撞号处理**不得**靠
            // catch OrderConflictException —— LedgerRepository.createBillWithDedup
            // 自身在 Room withTransaction 内抛错，嵌套事务内层异常会让**外层事务
            // 整体回滚**（回落建卡行随之消失）。改为**预检**：与 L1 同口径先查
            // checkLevel1Consistent，撞号（Conflict）直接转建卡段，不产生异常；
            // Consistent 仍由 createBillWithDedup 走原合并语义（MergedInto）。
            val level1 = DuplicateGuard(database).checkLevel1Consistent(
                orderId = input.orderId,
                type = billType,
                amountMinor = input.amountMinor,
                excludeId = billId
            )
            if (level1 is DuplicateGuard.Level1Check.Conflict) {
                JotBillLog.w(
                    "TransferClassifier",
                    "转账/还款撞号(单号同但金额/类型不同)，回落建卡: " +
                        "orderId=${input.orderId?.take(12)}, existing=${level1.bill.id.take(8)}"
                )
                effectiveConflictReason = DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED
            } else {
            val outcome = try {
                ledger.createBillWithDedup(bill, operator = TRANSFER_AUTO_COMMIT_OPERATOR)
            } catch (error: com.jotbill.app.data.OrderConflictException) {
                // 预检后理论上不再到达（同事务内无并发写）；保留作兜底防御：
                // 真到此处说明预检与落库之间有并发变更，转建卡段保证据。
                effectiveConflictReason = DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED
                null
            }
            if (outcome != null) {
                val settledBillId = when (outcome) {
                    is CreateBillOutcome.Committed -> outcome.bill.id
                    is CreateBillOutcome.MergedInto -> outcome.bill.id
                }
                return@withTransaction TransferOutcome.AutoCommitted(settledBillId, billType)
            }
            }
        }

        // ---- 待确认：类型按语义预填 ----
        // P13：收款人命中（转账给他人）**不再**预填 type="expense" —— 旧形态下
        // PendingPromptPolicy 只按 expense 放行弹窗，用户点一次确认就把一笔
        // 「转账给他人」写成消费支出（方向错账）。改为按业务语义预填 transfer/
        // repayment，方向（给他人支出 vs 自有账户互转）连同账户一起在待确认
        // 中心由用户显式决策；收款人仍记录在 appliedRules.recipient 供核对。
        val pendingType = billType
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
            conflictReason = effectiveConflictReason,
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
        // effectiveConflictReason 此处必非 null：要么判定阶段就非空（各类未解析），
        // 要么撞号回落已写入 DEDUP_CHECK_FAILED；为 null 的唯一路径是自动入账分支，
        // 其成功时已 return。故可安全断言非空（不变式，非覆盖式强转）。
        TransferOutcome.PendingCreated(pending.id, effectiveConflictReason!!, pendingType)
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
     * 匹配语义与 [com.jotbill.app.bookkeeping.AccountInference.matchAccountsByName] 共用（唯一命中才返回）。
     */
    private suspend fun matchAccountByName(hint: String): AccountEntity? {
        val normalizedHint = hint.trim()
        if (normalizedHint.isEmpty()) return null
        val activeAccounts = database.accountDao().getAll().filter { it.isActive }
        if (activeAccounts.isEmpty()) return null
        return com.jotbill.app.bookkeeping.AccountInference
            .matchAccountsByName(activeAccounts, normalizedHint)
            .singleOrNull()
    }

    /** 确定性 ID：SHA-256(prefix|payload)，同一事件重复到达生成相同 ID。 */
    private fun stableId(prefix: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }
}
