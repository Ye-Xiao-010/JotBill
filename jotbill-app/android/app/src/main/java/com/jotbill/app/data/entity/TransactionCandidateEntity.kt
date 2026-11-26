package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transaction_candidates",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["matched"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CheckoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TransactionCandidateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long?,
    val platform: String?,
    val merchant: String?,
    @ColumnInfo(name = "product_description") val productDescription: String?,
    @ColumnInfo(name = "evidence_amount_minor") val evidenceAmountMinor: Long?,
    @ColumnInfo(name = "evidence_merchant") val evidenceMerchant: String?,
    @ColumnInfo(name = "evidence_order_id") val evidenceOrderId: String?,
    // 注（2026-11-26 核实）：aiOrderId 仅在历史 AI 候选链路填充，当前写入恒为 null；
    // aiOccurredAt 仍在 CheckoutPaymentMatcher 中由支付信号写入与时间窗口比对消费，不可误删。
    @ColumnInfo(name = "ai_order_id") val aiOrderId: String?,
    @ColumnInfo(name = "ai_occurred_at") val aiOccurredAt: Long?,
    val matched: Boolean,
    @ColumnInfo(name = "conflict_reason") val conflictReason: String?,
    @ColumnInfo(name = "applied_rules") val appliedRules: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val version: Int
)
