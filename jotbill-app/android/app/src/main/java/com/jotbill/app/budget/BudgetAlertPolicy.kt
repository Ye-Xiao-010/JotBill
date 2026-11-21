package com.jotbill.app.budget

/**
 * 预算提醒档位判定（纯函数，便于单测）。
 *
 * 档位用位掩码记录"本周期已提醒过哪些档位"：退款或改账导致进度回落后，
 * 对应档位自动解除，再次跨过时会重新提醒；进度未回落时不会重复提醒。
 */
internal object BudgetAlertPolicy {
    private const val BIT_ALERT = 1
    private const val BIT_OVER = 2

    data class Decision(val notifiedMask: Int, val levelToNotify: Int)

    fun evaluate(
        progressRatio: Double,
        alertAt: Int,
        notifiedMask: Int,
        storedAlertAt: Int,
        markAsNotified: Boolean = true
    ): Decision {
        val alertSatisfied = alertAt in 1..100 && progressRatio >= alertAt / 100.0
        val overSatisfied = progressRatio >= 1.0
        var mask = notifiedMask
        if (!alertSatisfied) mask = mask and BIT_ALERT.inv()
        if (!overSatisfied) mask = mask and BIT_OVER.inv()
        // 用户调整阈值后，新阈值档位允许再提醒一次。
        if (storedAlertAt != Int.MIN_VALUE && storedAlertAt != alertAt) {
            mask = mask and BIT_ALERT.inv()
        }

        val level = when {
            overSatisfied && (mask and BIT_OVER) == 0 -> 100
            alertSatisfied && (mask and BIT_ALERT) == 0 -> alertAt
            else -> 0
        }
        if (!markAsNotified) {
            return Decision(mask, 0)
        }
        if (level == 100) {
            mask = mask or BIT_OVER
            if (alertSatisfied) mask = mask or BIT_ALERT
        } else if (level > 0) {
            mask = mask or BIT_ALERT
        }
        return Decision(mask, level)
    }
}
