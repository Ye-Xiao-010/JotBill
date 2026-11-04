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
    // TODO(2026-11-04 死代码标注)：aiOrderId / aiOccurredAt 由 AI 候选链路填充，
    //  该链路已随截图识别链路 4c62779 删除 ⇒ 全仓写入恒为 null、消费点恒走空值分支。
    //  保留待「AI 候选恢复」功能决策；删除需用户拍板。
    @ColumnInfo(name = "ai_order_id") val aiOrderId: String?,
    @ColumnInfo(name = "ai_occurred_at") val aiOccurredAt: Long?,
    val matched: Boolean,
    @ColumnInfo(name = "conflict_reason") val conflictReason: String?,
    @ColumnInfo(name = "applied_rules") val appliedRules: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val version: Int
)
