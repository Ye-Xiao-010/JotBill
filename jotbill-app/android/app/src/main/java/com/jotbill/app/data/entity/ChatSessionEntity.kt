package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本地 AI 对话会话。 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["is_pinned"]),
        Index(value = ["ai_provider_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AiProviderConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["ai_provider_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String?,
    @ColumnInfo(name = "ai_provider_id") val aiProviderId: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
