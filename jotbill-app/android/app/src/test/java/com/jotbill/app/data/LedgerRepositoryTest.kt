package com.jotbill.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerRepositoryTest {
    @Test
    fun expenseReducesBalance() {
        assertEquals(900L, BalanceCalculator.calculate(1_000L, 100L, "expense"))
    }

    @Test
    fun incomeAndRefundIncreaseBalance() {
        assertEquals(1_100L, BalanceCalculator.calculate(1_000L, 100L, "income"))
        assertEquals(1_100L, BalanceCalculator.calculate(1_000L, 100L, "refund"))
    }

    @Test
    fun transferDoesNotChangeTheSourceBalance() {
        assertEquals(1_000L, BalanceCalculator.calculate(1_000L, 100L, "transfer"))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownTransactionTypeIsRejected() {
        BalanceCalculator.calculate(1_000L, 100L, "unknown")
    }
}
