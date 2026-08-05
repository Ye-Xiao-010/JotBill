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
