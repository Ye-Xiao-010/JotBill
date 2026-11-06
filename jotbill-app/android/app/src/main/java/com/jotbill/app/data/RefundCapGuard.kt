package com.jotbill.app.data

/**
 * 退款累计上限的共享判定（唯一出处）。
 *
 * 口径：同一原交易（original_transaction_id 指向的支出账单）名下的全部退款账单
 * 金额合计 + 本次拟入账金额，不得超过原交易实付金额。
 *
 * 此前该校验在 `RefundLinker`（自动入账）与 `LedgerRepository.confirmPendingTransaction`
 * （确认路径）各写了一份，编辑路径（updateBillWithBalanceUpdate）与导入路径
 * （ImportEngine）完全缺失 —— 余额审查 B2/B3 缺口。本类收拢为唯一实现：
 * - 确认路径 / 编辑路径：超额抛 `LedgerValidationException`（调用方决定）；
 * - 导入路径：超额转待确认（调用方决定）；
 * - 自动入账路径：超额进待确认（RefundLinker 既有行为）。
 *
 * 无原交易关联（originalTransactionId == null）或原交易已删除时不做校验（返回 null），
 * 与确认路径既有语义一致 —— 无锚点无法定义"原实付"。
 */
internal class RefundCapGuard(private val database: JotBillDatabase) {

    /**
     * 一次判定的完整快照。
     *
     * @param originalBillId 原交易账单 ID
     * @param originalAmountMinor 原交易实付金额
     * @param refundedBeforeMinor 本次之前已入账的退款合计（已排除 [evaluate] 的 excludeId）
     * @param exceeded 本次入账后是否超额（refundedBefore + 本次金额 > 原实付）
     */
    data class CapState(
        val originalBillId: String,
        val originalAmountMinor: Long,
        val refundedBeforeMinor: Long,
        val exceeded: Boolean
    )

    /**
     * 评估一笔拟入账/拟更新的退款是否突破累计上限。
     *
     * @param amountMinor 本次退款金额
     * @param originalTransactionId 原交易账单 ID；null 时直接返回 null（不校验）
     * @param excludeId 需要从"已退款合计"中排除的退款账单 ID —— 编辑既有退款时
     *   传自身 id，否则旧金额会被计入 refundedBefore 导致误报超额；新建场景传 null
     * @return 原交易存在时返回 [CapState]；无关联或原交易缺失时返回 null
     */
    suspend fun evaluate(
        amountMinor: Long,
        originalTransactionId: String?,
        excludeId: String? = null
    ): CapState? {
        val originalId = originalTransactionId?.takeIf { it.isNotBlank() } ?: return null
        val original = database.billDao().getById(originalId) ?: return null
        val refundedBefore = database.billDao().sumRefundsByOriginalId(original.id, excludeId)
        return CapState(
            originalBillId = original.id,
            originalAmountMinor = original.amountMinor,
            refundedBeforeMinor = refundedBefore,
            exceeded = refundedBefore + amountMinor > original.amountMinor
        )
    }

    companion object {
        /**
         * 超额拒绝的用户可见文案（唯一出处）：确认路径（`confirmPendingTransaction`）
         * 与编辑路径（`updateBillWithBalanceUpdate`）都抛这一句 —— 它经桥接层
         * `INVALID_ARGUMENT` 原样展示（CreateBillScreen showError / 确认面板错误提示），
         * native 抛中文、RN 不再套壳。文案须短且指导下一步。
         */
        const val REFUND_EXCEEDED_MESSAGE = "退款金额累计超过原交易实付，请核对原单或退款金额"
    }
}
