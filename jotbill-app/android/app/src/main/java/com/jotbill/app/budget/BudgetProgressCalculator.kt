package com.jotbill.app.budget

import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.BudgetEntity

/**
 * 预算进度唯一计算入口。
 *
 * 口径：
 * - 支出只算 expense + fee；
 * - 退款不追溯原单所属期，只要发生在预算期内即按金额冲减已用；
 *   总预算扣减本期全部退款，单分类预算排除跨分类退款（仅扣减自身或关联原单归属该分类/子分类的退款）；
 * - 收入（income）、转账、还款不参与预算；
 * - 净额下限为 0，避免退款多于支出时出现负进度。
 */
internal object BudgetProgressCalculator {
    data class Snapshot(
        val range: BudgetPeriods.Range,
        val spentMinor: Long,
        val grossSpentMinor: Long,
        val refundMinor: Long,
        val remainingMinor: Long,
        val progress: Double,
        val progressPct: Int,
        val isOverBudget: Boolean,
        val isActivePeriod: Boolean
    )

    suspend fun compute(
        database: JotBillDatabase,
        budget: BudgetEntity,
        now: Long
    ): Snapshot {
        val range = BudgetPeriods.rangeFor(budget, now)
        val grossSpent = database.billDao().sumExpenses(
            budget.categoryId,
            range.startAt,
            range.endAt
        )
        val refunds = database.billDao().sumRefunds(
            budget.categoryId,
            range.startAt,
            range.endAt
        )
        val spent = (grossSpent - refunds).coerceAtLeast(0L)
        val progress = if (budget.amountMinor == 0L) {
            if (spent > 0L) 1.0 else 0.0
        } else {
            spent.toDouble() / budget.amountMinor.toDouble()
        }
        return Snapshot(
            range = range,
            spentMinor = spent,
            grossSpentMinor = grossSpent,
            refundMinor = refunds,
            remainingMinor = budget.amountMinor - spent,
            progress = progress,
            progressPct = progressPercent(spent, budget.amountMinor),
            isOverBudget = spent > budget.amountMinor,
            isActivePeriod = range.contains(now)
        )
    }

    /**
     * 展示百分比用整数分演算，避免 29/100 因浮点 floor 显示成 28%。
     * 语义仍是向下取整；金额为 0 时沿用 0/100 的既有约定。
     */
    internal fun progressPercent(spentMinor: Long, amountMinor: Long): Int {
        if (amountMinor == 0L) {
            return if (spentMinor > 0L) 100 else 0
        }
        return ((spentMinor * 100L) / amountMinor)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }
}
