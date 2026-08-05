package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_provider_configs",
    indices = [Index(value = ["is_default"])]
)
data class AiProviderConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "api_key_encrypted") val apiKeyEncrypted: String?,
    @ColumnInfo(name = "api_base_url") val apiBaseUrl: String?,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "supports_vision") val supportsVision: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
