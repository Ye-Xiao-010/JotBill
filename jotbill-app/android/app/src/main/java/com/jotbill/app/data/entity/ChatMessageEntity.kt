package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本地 AI 对话消息，图片数据随 SQLCipher 数据库加密保存。 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["session_id", "seq_num"], unique = true),
        Index(value = ["created_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq_num") val seqNum: Int,
    val role: String,
    val content: String?,
    @ColumnInfo(name = "image_data_json") val imageDataJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
