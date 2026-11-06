package com.jotbill.app.data.model

data class BillSummary(
    val totalIncome: Long,
    val totalExpense: Long,
    val incomeCount: Long,
    val expenseCount: Long
)

data class CategoryStatRow(
    val categoryId: String?,
    val categoryName: String?,
    val categoryIcon: String?,
    val amountMinor: Long,
    val count: Long
)

data class PlatformStatRow(
    val platform: String?,
    val amountMinor: Long,
    val count: Long
)

data class TrendRow(
    val period: String,
    val type: String,
    val amountMinor: Long
)

data class NetWorthRow(
    val totalAssets: Long,
    val totalLiabilities: Long
)

/** B4：逐账户聚合行（账单净增减 / 转账目标侧入账 / 校准差额共用）。 */
data class AccountDeltaRow(
    val accountId: String,
    val delta: Long
)
