package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["category_id"]),
        Index(value = ["is_active"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val period: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "alert_at") val alertAt: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
