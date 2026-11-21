package com.jotbill.app.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetAlertPolicyTest {
    @Test
    fun `alerts once per level and re-arms after progress falls`() {
        val first = BudgetAlertPolicy.evaluate(0.85, 80, 0, Int.MIN_VALUE)
        assertEquals(80, first.levelToNotify)

        val duplicate = BudgetAlertPolicy.evaluate(0.86, 80, first.notifiedMask, 80)
        assertEquals(0, duplicate.levelToNotify)

        val dropped = BudgetAlertPolicy.evaluate(0.79, 80, duplicate.notifiedMask, 80)
        assertEquals(0, dropped.levelToNotify)
        assertEquals(0, dropped.notifiedMask)

        val crossedAgain = BudgetAlertPolicy.evaluate(0.85, 80, dropped.notifiedMask, 80)
        assertEquals(80, crossedAgain.levelToNotify)
    }

    @Test
    fun `over level re-arms after refund drops below 100`() {
        val over = BudgetAlertPolicy.evaluate(1.05, 80, 0, Int.MIN_VALUE)
        assertEquals(100, over.levelToNotify)

        val belowOver = BudgetAlertPolicy.evaluate(0.90, 80, over.notifiedMask, 80)
        assertEquals(0, belowOver.levelToNotify)

        val overAgain = BudgetAlertPolicy.evaluate(1.0, 80, belowOver.notifiedMask, 80)
        assertEquals(100, overAgain.levelToNotify)
    }

    @Test
    fun `zero threshold never alerts`() {
        val result = BudgetAlertPolicy.evaluate(0.5, 0, 0, Int.MIN_VALUE)
        assertEquals(0, result.levelToNotify)
    }

    @Test
    fun `changed threshold re-alerts at new level`() {
        val raised = BudgetAlertPolicy.evaluate(0.75, 70, 1, 80)
        assertEquals(70, raised.levelToNotify)
    }

    @Test
    fun `muted evaluation clears stale levels without marking new ones`() {
        val muted = BudgetAlertPolicy.evaluate(
            progressRatio = 0.85,
            alertAt = 80,
            notifiedMask = 3,
            storedAlertAt = 80,
            markAsNotified = false
        )
        assertEquals(0, muted.levelToNotify)
        assertEquals(1, muted.notifiedMask)

        val dropped = BudgetAlertPolicy.evaluate(
            progressRatio = 0.6,
            alertAt = 80,
            notifiedMask = 3,
            storedAlertAt = 80,
            markAsNotified = false
        )
        assertEquals(0, dropped.levelToNotify)
        assertEquals(0, dropped.notifiedMask)
    }
}
