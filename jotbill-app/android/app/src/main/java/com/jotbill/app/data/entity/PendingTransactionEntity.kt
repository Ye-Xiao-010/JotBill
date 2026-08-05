package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_transactions",
    indices = [
        Index(value = ["candidate_id"]),
        Index(value = ["session_id"]),
        Index(value = ["created_at"])
    ]
)
data class PendingTransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String?,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val platform: String?,
    val merchant: String?,
    @ColumnInfo(name = "product_description") val productDescription: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "conflict_reason") val conflictReason: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val version: Int
)
