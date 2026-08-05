package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_cost_records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["provider"]),
        Index(value = ["month"])
    ]
)
data class AiCostRecordEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "request_type") val requestType: String,
    @ColumnInfo(name = "tokens_in") val tokensIn: Int?,
    @ColumnInfo(name = "tokens_out") val tokensOut: Int?,
    @ColumnInfo(name = "cost_minor") val costMinor: Long,
    val success: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val month: String
)
