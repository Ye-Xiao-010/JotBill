package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_evidence",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["order_id"]),
        Index(value = ["source_event_key"], unique = true)
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
data class PaymentEvidenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long?,
    val merchant: String?,
    val platform: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "payment_method") val paymentMethod: String?,
    @ColumnInfo(name = "payment_status") val paymentStatus: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "raw_text_hash") val rawTextHash: String?,
    @ColumnInfo(name = "source_event_key") val sourceEventKey: String?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long
)
