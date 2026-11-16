package com.jotbill.app.pending

import com.jotbill.app.bridge.BridgeDtoMapper
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.BillEntity
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

        /**
         * 卡片已被其它通道自动入账（跨通道吸收，2026-11-16 真机）：
         * 用户在弹窗点确认的同时银行短信入账、卡被吸收 —— 单据已记，按成功
         * 反馈关闭，不显示"入账失败"。
         */
        data class AutoBooked(val billId: String, val amountMinor: Long) : Outcome()

        /** 桥接层传入参数非法（金额越界/类型不支持）。 */
        data class Invalid(val reason: String) : Outcome()

        /**
         * M4：卡带 SUSPECTED_DUPLICATE 冲突码且调用方未显式二次确认 ——
         * 不落账、不删卡，由 RN 弹二次确认后带 confirmedDuplicate=true 重试。
         * 不是硬阻断：用户明确知道自己在做什么时必须能过（第二次带标记即放行）。
         */
        object DuplicateConfirmRequired : Outcome()
    }

    /**
     * 确认入账。调用方无需预读 pending；版本冲突与已处理统一收敛为
     * [Outcome.AlreadyHandled]，由各入口自行决定提示方式。
     *
     * @param confirmedDuplicate M4 二次确认标记：卡带 SUSPECTED_DUPLICATE 时
     *   必须为 true 才放行（RN 弹过「疑似重复」确认后重传）。
     */
    suspend fun confirm(pendingId: String, overrides: Overrides, confirmedDuplicate: Boolean = false): Outcome {
        val pending = database.pendingTransactionDao().getById(pendingId)
            ?: return detectRecentAutoBookedBill(overrides) ?: Outcome.AlreadyHandled
        // M4：疑似重复卡不许"点一下就过" —— 首次确认返回 DuplicateConfirmRequired，
        // 由 RN 弹窗明示后带 confirmedDuplicate=true 重试；不做硬阻断。
        if (
            !confirmedDuplicate &&
            pending.conflictReason?.split(',')
                ?.any { it.trim() == com.jotbill.app.rules.DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE } == true
        ) {
            return Outcome.DuplicateConfirmRequired
        }
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
        // 金额口径的单一出处：PendingConfirmReadiness.isValidAmount（UI 就绪判据共用）。
        if (!PendingConfirmReadiness.isValidAmount(amountMinor)) {
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
        val resolvedCategoryId = overrides.categoryId ?: rulePatch.categoryId ?: pending.categoryId
        val resolvedAccountId = overrides.accountId ?: rulePatch.accountId ?: pending.accountId
        // 分类/账户引用校验（2026-10-24）：`bills.category_id` / `account_id` 是
        // ForeignKey(RESTRICT) —— pending 上的引用可能指向已被删除的行
        // （`DataModule.deleteCategory` 只检查 bills，不检查 pending）。
        // 悬空引用会让插入抛 SQL 异常，错误信息以英文透传给用户；而 RN 待确认
        // 中心的快捷确认（`handleConfirm` 传 `input={}`）不传分类，编辑面板也没有
        // 分类选择器 ⇒ 那条待确认将永久无法入账。在服务层提前拒绝并给出中文提示。
        if (resolvedCategoryId != null && database.categoryDao().getById(resolvedCategoryId) == null) {
            return Outcome.Invalid("分类已被删除，请编辑后重新选择分类")
        }
        if (resolvedAccountId != null && database.accountDao().getById(resolvedAccountId) == null) {
            return Outcome.Invalid("账户已被删除，请编辑后重新选择账户")
        }
        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amountMinor,
            type = type,
            occurredAt = overrides.occurredAt ?: pending.occurredAt,
            description = overrides.description,
            categoryId = resolvedCategoryId,
            accountId = resolvedAccountId,
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
        // M1：同单号撞号（金额/类型不一致）→ 事务回滚、卡保留，明确告知用户冲突。
        val confirmResult = try {
            LedgerRepository(database).confirmPendingTransaction(pending, bill)
        } catch (error: com.jotbill.app.data.OrderConflictException) {
            return Outcome.Invalid(
                "订单号与既有账单（金额或类型）冲突，请到账单列表核对后再处理"
            )
        }
        if (!confirmResult.success) {
            return Outcome.AlreadyHandled
        }
        val settledBill = confirmResult.committed ?: confirmResult.mergedInto ?: bill
        return Outcome.Confirmed(
            bill = settledBill,
            merged = confirmResult.mergedInto != null
        )
    }

    /**
     * 卡已不在时判断是否刚被其它通道自动入账（真机 2026-11-16）：同类型 + 同额 +
     * 2 分钟内新账单存在 ⇒ 返回 [Outcome.AutoBooked]，弹窗按成功反馈关闭，
     * 不再显示"入账失败 / 已在其他入口处理"。
     */
    private suspend fun detectRecentAutoBookedBill(overrides: Overrides): Outcome.AutoBooked? {
        val amount = overrides.amountMinor?.takeIf { it > 0L } ?: return null
        val type = overrides.type ?: "expense"
        val now = System.currentTimeMillis()
        return try {
            database.billDao().findRecentByAmountAndType(
                type = type,
                amountMinor = amount,
                windowStart = now - AUTO_BOOKED_RECENT_WINDOW_MILLIS,
                windowEnd = now
            )?.let { Outcome.AutoBooked(it.id, it.amountMinor) }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** 自动入账识别窗：跨通道吸收与用户点确认的真实时差（秒级）。 */
private const val AUTO_BOOKED_RECENT_WINDOW_MILLIS = 2 * 60 * 1000L
