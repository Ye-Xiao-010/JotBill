package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["sort_order"])
    ]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val icon: String?,
    val color: String?,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
