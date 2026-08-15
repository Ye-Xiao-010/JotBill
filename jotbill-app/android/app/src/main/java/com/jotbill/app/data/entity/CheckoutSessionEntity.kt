package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkout_sessions",
    indices = [
        Index(value = ["status"]),
        Index(value = ["expires_at"]),
        Index(value = ["checkout_request_key"], unique = true)
    ]
)
/**
 * 结算会话实体。
 *
 * status 列只存 Native.d.ts 公开的 8 个 SessionStatus 值；
 * journey_state 列存内部 CheckoutJourneyState 值，用于状态机转换判定，
 * 不直接序列化给 RN。
 */
data class CheckoutSessionEntity(
    @PrimaryKey val id: String,
    val status: String,
    @ColumnInfo(name = "journey_state") val journeyState: String?,
    @ColumnInfo(name = "shopping_package") val shoppingPackage: String?,
    @ColumnInfo(name = "page_role") val pageRole: String?,
    @ColumnInfo(name = "page_fingerprint") val pageFingerprint: String?,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String?,
    @ColumnInfo(name = "checkout_request_key") val checkoutRequestKey: String?,
    @ColumnInfo(name = "ai_request_id") val aiRequestId: String?,
    @ColumnInfo(name = "candidate_id") val candidateId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long
)
