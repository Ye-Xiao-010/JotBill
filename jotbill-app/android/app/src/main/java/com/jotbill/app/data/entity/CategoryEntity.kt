package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["sort_order"]),
        Index(value = ["parent_id"])
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
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * 父分类 ID(批 4 S6/批 6:两级分类体系)。
     * null = 顶级分类;非 null = 该分类为其父分类下的子分类。
     * 完整性由应用层保证(LedgerRepository/DataModule):
     * - 父必须存在且同 type,且父本身不得再有父(深度恒为 2 级);
     * - 含子分类的分类不可删除;
     * - 统计与预算按父分类上卷(COALESCE(parent_id, id))。
     * 列为普通 TEXT 不带外键:迁移走轻量 ALTER 而非表重建,
     * 完整性风险在单人应用场景下由上述应用层校验覆盖。
     */
    @ColumnInfo(name = "parent_id") val parentId: String? = null
)
