package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.CategoryEntity

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    /** 播种专用：预设分类 ID 冲突时静默跳过，不让首启种子失败拖垮建库。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY is_default DESC, sort_order ASC")
    suspend fun getByType(type: String): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY is_default DESC, sort_order ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    /** 批 6 子分类:某父分类下的直接子分类(不含孙级——体系恒为两级)。 */
    @Query("SELECT * FROM categories WHERE parent_id = :parentId ORDER BY is_default DESC, sort_order ASC")
    suspend fun getChildren(parentId: String): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories WHERE parent_id = :parentId")
    suspend fun countChildren(parentId: String): Int
}
