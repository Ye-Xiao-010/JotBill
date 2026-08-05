package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_entries",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["entity_type"]),
        Index(value = ["entity_id"])
    ]
)
data class AuditEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val action: String,
    @ColumnInfo(name = "field_name") val fieldName: String?,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    val operator: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
