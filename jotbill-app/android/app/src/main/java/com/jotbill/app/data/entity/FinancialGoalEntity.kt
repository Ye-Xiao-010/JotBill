package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "financial_goals")
data class FinancialGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "target_amount_minor") val targetAmountMinor: Long,
    @ColumnInfo(name = "current_amount_minor") val currentAmountMinor: Long,
    @ColumnInfo(name = "target_date") val targetDate: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
