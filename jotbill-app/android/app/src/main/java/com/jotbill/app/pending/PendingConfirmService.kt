package com.jotbill.app.pending

import com.jotbill.app.bridge.BridgeDtoMapper
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.recognition.MAX_SAFE_MINOR_UNIT
import com.jotbill.app.rules.RuleEngine
import java.util.UUID

/**
 * 待确认确认入账的统一入口：桥接层(ReviewScreen)与付款确认弹窗
 * ([com.jotbill.app.overlay.PendingConfirmOverlayManager])共用。
 *
 * 抽取的唯一目的：两条入口必须走同一条"读 pending → 覆写字段 → 规则引擎
 * 最终执行 → 组装 Bill → 事务内乐观锁确认"路径。任何一条入口单独实现，
 * 规则执行/字段优先级/去重合并语义都会逐渐漂移（09-06 审计中还款方向
 * P0 即三处同源复制中毒的教训）。
 *
 * 字段优先级与 DataModule.confirmPendingTransaction 保持一致：
 * 用户显式覆写 > 规则补全 > 待确认既有字段（证据/AI）。
 */
internal class PendingConfirmService(private val database: JotBillDatabase) {

    /** 用户在确认入口可覆写的字段；null 表示沿用待确认既有值。 */
    data class Overrides(
        val type: String? = null,
        val sourceType: String? = null,
        val amountMinor: Long? = null,
        val merchant: String? = null,
        val categoryId: String? = null,
        val accountId: String? = null,
        val targetAccountId: String? = null,
        val platform: String? = null,
        val productDescription: String? = null,
        val orderId: String? = null,
        val description: String? = null,
        val occurredAt: Long? = null,
        /** 退款/手续费手动关联的原交易 ID；不传时绑定待确认来源会话。 */
        val originalTransactionId: String? = null
    )

    sealed class Outcome {
        /** 确认成功。merged=true 表示去重第 1 级命中，合并进了既有账单。 */
        data class Confirmed(val bill: BillEntity, val merged: Boolean) : Outcome()

        /** 待确认不存在，或确认事务内版本已变化（另一入口已处理）。 */
        object AlreadyHandled : Outcome()

        /** 桥接层传入参数非法（金额越界/类型不支持）。 */
        data class Invalid(val reason: String) : Outcome()
    }

    /**
     * 确认入账。调用方无需预读 pending；版本冲突与已处理统一收敛为
     * [Outcome.AlreadyHandled]，由各入口自行决定提示方式。
     */
    suspend fun confirm(pendingId: String, overrides: Overrides): Outcome {
        val pending = database.pendingTransactionDao().getById(pendingId)
            ?: return Outcome.AlreadyHandled
        val now = System.currentTimeMillis()
        val type = overrides.type ?: pending.type
        val sourceType = overrides.sourceType ?: pending.sourceType
        try {
            BridgeDtoMapper.validateBillType(type)
            BridgeDtoMapper.validateSourceType(sourceType)
        } catch (error: RuntimeException) {
            return Outcome.Invalid(error.message ?: "INVALID_ARGUMENT")
        }
        val amountMinor = overrides.amountMinor ?: pending.amountMinor
        if (amountMinor !in 1L..MAX_SAFE_MINOR_UNIT) {
            return Outcome.Invalid("amountMinor must be a positive safe integer minor unit")
        }
        // 阶段五 T1 时机二：确认入账前最终执行规则。ignore/send_to_review
        // 不阻断用户确认（用户明确操作优先级最高），仅记录命中。
        val ruleEngine = RuleEngine(database)
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = amountMinor,
                platform = pending.platform,
                merchant = pending.merchant,
                description = pending.productDescription,
                sourceType = sourceType
            )
        )
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
        )
        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amountMinor,
            type = type,
            occurredAt = overrides.occurredAt ?: pending.occurredAt,
            description = overrides.description,
            categoryId = overrides.categoryId
                ?: rulePatch.categoryId
                ?: pending.categoryId,
            accountId = overrides.accountId
                ?: rulePatch.accountId
                ?: pending.accountId,
            targetAccountId = overrides.targetAccountId,
            platform = overrides.platform
                ?: rulePatch.platform
                ?: pending.platform,
            merchant = overrides.merchant ?: rulePatch.merchant ?: pending.merchant,
            productDescription = overrides.productDescription ?: pending.productDescription,
            sourceType = sourceType,
            // 来源绑定不可改写：退款允许用户从编辑面板手动指定原交易，
            // 否则绑定待确认的来源会话 ID，避免请求改写幂等边界。
            originalTransactionId = overrides.originalTransactionId ?: pending.sessionId,
            orderId = overrides.orderId ?: pending.orderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )
        // 事务内乐观锁确认：版本不一致返回 false，Bill 不写入、余额不更新。
        // 阶段五 T6：确认前执行去重（第 1 级命中合并进既有账单，不产生第二笔）。
        val confirmResult = LedgerRepository(database).confirmPendingTransaction(pending, bill)
        if (!confirmResult.success) {
            return Outcome.AlreadyHandled
        }
        val settledBill = confirmResult.committed ?: confirmResult.mergedInto ?: bill
        return Outcome.Confirmed(
            bill = settledBill,
            merged = confirmResult.mergedInto != null
        )
    }
}
