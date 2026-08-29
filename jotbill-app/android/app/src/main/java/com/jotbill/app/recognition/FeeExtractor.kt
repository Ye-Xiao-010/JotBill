package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.data.PENDING_CONFIRM_AUDIT_TYPE
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AuditEntryEntity
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

/** 手续费种子分类 ID（DefaultDataInitializer 阶段五 T4 新增）。 */
private const val FEE_CATEGORY_ID = "default-category-expense-fee"

/**
 * 阶段五 T4：手续费拆分与主交易关联决策。
 *
 * 职责（见 docs/tdd/09_phase5_tasks.md 第 3.5 节与任务卡 T4）：
 * - 接收 Normalizer 拆分出的手续费证据（paymentStatus = FEE，amountMinor=0 表示解析失败）；
 * - 手续费金额小、特征弱，一律进入待确认，不自动入账；
 * - 伴随主交易（提现/转账/还款）时：主账单已存在则把账单 ID 写入待确认 originalBillId，
 *   主账单未入账时在 appliedRules 记录关联提示，均不阻塞确认；
 * - 手续费计入支出（type=fee），确认入账后按 BalanceCalculator 现有口径扣减源账户；
 * - 幂等：确定性待确认 ID + 用户确认审计墓碑，重复通知只处理一次。
 */
internal class FeeExtractor(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ruleEngine = RuleEngine(database)

    /** 手续费处理结果，供调用方记录日志与面包屑。 */
    internal sealed class FeeOutcome {
        /** 进入待确认中心：linkedBillId 非空表示已关联到主账单。 */
        data class PendingCreated(
            val pendingId: String,
            val conflictReason: String?,
            val linkedBillId: String?
        ) : FeeOutcome()

        /** 重复通知：该 sourceEventKey 已处理过（待确认/确认墓碑存在）。 */
        object Duplicate : FeeOutcome()

        /** 规则 ignore 命中：不挂账、不进待确认，审计留痕。 */
        object Ignored : FeeOutcome()
    }

    /**
     * 处理一条手续费证据：幂等校验 → 规则执行 → 主交易关联 → 创建待确认。
     * 全程在单个 Room 事务内，任一写入失败整体回滚。
     *
     * @param input 手续费证据（amountMinor=0 表示金额解析失败）
     * @param mainPart 同通知拆分出的主交易证据；非空时尝试关联其确定性账单 ID
     */
    suspend fun processFee(
        input: PaymentEvidenceInput,
        mainPart: PaymentEvidenceInput?
    ): FeeOutcome = database.withTransaction {
        val now = nowMillis()
        if (input.occurredAt <= 0L || input.amountMinor < 0L ||
            input.amountMinor > MAX_SAFE_MINOR_UNIT
        ) {
            return@withTransaction FeeOutcome.Duplicate
        }

        // ---- 幂等：确定性待确认 ID + 用户确认墓碑，同一事件只处理一次 ----
        val pendingId = stableId("pending-fee", input.sourceEventKey)
        if (database.pendingTransactionDao().getById(pendingId) != null ||
            database.auditEntryDao()
                .getByEntity(PENDING_CONFIRM_AUDIT_TYPE, pendingId).isNotEmpty()
        ) {
            return@withTransaction FeeOutcome.Duplicate
        }

        // ---- 规则引擎（阶段五 T1：纯通知路径同样执行规则）----
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = input.amountMinor.takeIf { it > 0L },
                platform = input.sourcePackage,
                merchant = null,
                description = mainPart?.let { "伴随主交易:${it.paymentStatus}" },
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
            return@withTransaction FeeOutcome.Ignored
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
        )

        // ---- 金额解析失败：整条进待确认并标记，不强行入账 ----
        val amountUnparsed = input.amountMinor == 0L
        val conflictReason = when {
            amountUnparsed -> CONFLICT_FEE_AMOUNT_UNPARSED
            ruleDecision is RuleEngine.Decision.ToReview -> RuleEngine.CONFLICT_RULE_FORCED_REVIEW
            else -> null
        }

        // ---- 源账户：规则指定 > 支付应用默认账户（手续费从主交易扣款账户支出）----
        val sourceAccount = rulePatch.accountId?.let { activeAccount(it) }
            ?: packageDefaultAccount(input.sourcePackage)

        // ---- 主交易关联：主账单已存在时写 originalBillId，未入账时记提示，不阻塞确认 ----
        val mainBillId = mainPart?.let { resolveMainBillId(it) }
        val linkedBillId = mainBillId
            ?.takeIf { database.billDao().getById(it) != null }

        // ---- 手续费分类：种子分类存在时预填，不存在（旧库未初始化）留给用户选择 ----
        val categoryId = rulePatch.categoryId
            ?: FEE_CATEGORY_ID.takeIf { database.categoryDao().getById(it) != null }

        // ---- 待确认：手续费一律待确认（金额小、特征弱），不自动入账 ----
        val pending = PendingTransactionEntity(
            id = pendingId,
            candidateId = null,
            sessionId = null,
            amountMinor = input.amountMinor,
            type = "fee",
            occurredAt = input.occurredAt,
            platform = input.sourcePackage,
            merchant = null,
            productDescription = null,
            orderId = input.orderId,
            // 源账户可用时预填（确认后按支出口径扣减该账户余额）
            accountId = sourceAccount?.id ?: rulePatch.accountId,
            categoryId = categoryId,
            conflictReason = conflictReason,
            sourceType = "notification",
            appliedRules = ruleEngine.mergeAppliedRules(
                JSONObject().apply {
                    put("decision", "FEE")
                    if (amountUnparsed) put("amountUnparsed", true)
                    if (mainPart != null) {
                        put("mainStatus", mainPart.paymentStatus)
                        put("mainSourceEventKey", mainPart.sourceEventKey)
                    }
                    linkedBillId?.let { put("linkedBillId", it) }
                    sourceAccount?.let { put("sourceAccountId", it.id) }
                }.toString(),
                ruleDecision.hits
            ),
            // 主账单已存在时关联（确认入账时供参考）；未入账时 null，不阻塞确认
            originalBillId = linkedBillId,
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
        FeeOutcome.PendingCreated(pending.id, conflictReason, linkedBillId)
    }

    /** 账户存在且启用时返回实体，否则 null。 */
    private suspend fun activeAccount(accountId: String): AccountEntity? {
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) account else null
    }

    /** 支付包名到种子资金账户的固定映射（与 TransferClassifier 保持一致）。 */
    private suspend fun packageDefaultAccount(paymentPackage: String?): AccountEntity? =
        when (paymentPackage) {
            "com.tencent.mm" -> activeAccount(WECHAT_PAY_ACCOUNT_ID)
            "com.eg.android.AlipayGphone" -> activeAccount(ALIPAY_ACCOUNT_ID)
            else -> null
        }

    /**
     * 按 TransferClassifier 同款确定性算法推算主交易账单 ID：
     * billId = "<billType>-bill-" + SHA-256("<billType>-bill|<mainSourceEventKey>")。
     * 主交易尚未入账（自动入账未发生或进待确认）时该 ID 查不到账单，返回提示路径。
     */
    private fun resolveMainBillId(mainPart: PaymentEvidenceInput): String {
        val billType = if (mainPart.paymentStatus == "REPAYMENT") "repayment" else "transfer"
        return stableId("$billType-bill", mainPart.sourceEventKey)
    }

    /** 确定性 ID：SHA-256(prefix|payload)，同一事件重复到达生成相同 ID。 */
    private fun stableId(prefix: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 手续费金额解析失败标记：整条进待确认，由用户补金额后确认。 */
        const val CONFLICT_FEE_AMOUNT_UNPARSED = "FEE_AMOUNT_UNPARSED"
    }
}
