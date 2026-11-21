package com.jotbill.app.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetProgressPercentTest {
    @Test
    fun `integer percentages do not lose one point to floating point`() {
        assertEquals(29, BudgetProgressCalculator.progressPercent(29_00L, 100_00L))
        assertEquals(57, BudgetProgressCalculator.progressPercent(57_00L, 100_00L))
        assertEquals(47, BudgetProgressCalculator.progressPercent(141_50L, 300_00L))
    }

    @Test
    fun `boundaries keep floor semantics`() {
        assertEquals(0, BudgetProgressCalculator.progressPercent(0L, 100_00L))
        assertEquals(99, BudgetProgressCalculator.progressPercent(99_99L, 100_00L))
        assertEquals(100, BudgetProgressCalculator.progressPercent(100_00L, 100_00L))
        assertEquals(101, BudgetProgressCalculator.progressPercent(101_00L, 100_00L))
    }

    @Test
    fun `zero amount keeps legacy zero-or-hundred rule`() {
        assertEquals(0, BudgetProgressCalculator.progressPercent(0L, 0L))
        assertEquals(100, BudgetProgressCalculator.progressPercent(1L, 0L))
    }
}
