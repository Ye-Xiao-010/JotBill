package com.jotbill.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠️ 2026-10-12：原用例断言 `BalanceCalculator.calculate(currentBalance, amount, type)`
 * （= `addExact(currentBalance, delta(amount, type))`），该方法生产零调用点已删除。
 *
 * 覆盖不变 —— 那 6 条断言验的本来就是 `delta` 的真值表，`currentBalance` 只是
 * 为了让 `calculate` 产出 `900`/`1_100` 这类可读数字而传的哑元。
 * 现直接断言 `delta` 本身：`expense` −100、`income`/`refund` +100、
 * `transfer`/`repayment` −100、未知类型抛异常。
 */
class LedgerRepositoryTest {
    @Test
    fun expenseReducesBalance() {
        assertEquals(-100L, BalanceCalculator.delta(100L, "expense"))
    }

    @Test
    fun incomeAndRefundIncreaseBalance() {
        assertEquals(100L, BalanceCalculator.delta(100L, "income"))
        assertEquals(100L, BalanceCalculator.delta(100L, "refund"))
    }

    @Test
    fun transferReducesSourceBalanceUntilTargetIsApplied() {
        assertEquals(-100L, BalanceCalculator.delta(100L, "transfer"))
    }

    @Test
    fun repaymentReducesSourceBalanceUntilLiabilityTargetIsApplied() {
        assertEquals(-100L, BalanceCalculator.delta(100L, "repayment"))
    }

    @Test
    fun feeReducesBalance() {
        assertEquals(-100L, BalanceCalculator.delta(100L, "fee"))
    }

    @Test
    fun adjustmentLeavesBalanceUnchanged() {
        assertEquals(0L, BalanceCalculator.delta(100L, "adjustment"))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownTransactionTypeIsRejected() {
        BalanceCalculator.delta(100L, "unknown")
    }
}