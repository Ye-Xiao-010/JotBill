package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rules",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["priority"])
    ]
)
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val condition: String,
    @ColumnInfo(name = "condition_value") val conditionValue: String,
    val action: String,
    @ColumnInfo(name = "action_value") val actionValue: String?,
    val priority: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
