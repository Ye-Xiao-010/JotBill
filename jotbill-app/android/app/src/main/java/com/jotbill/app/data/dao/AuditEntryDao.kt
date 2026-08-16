package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.AuditEntryEntity

@Dao
interface AuditEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditEntryEntity): Long

    /** 幂等插入：同一确定性 ID 的审计（如规则 ignore 决策）重复触发时静默跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries WHERE entity_type = :type AND entity_id = :id ORDER BY created_at DESC")
    suspend fun getByEntity(type: String, id: String): List<AuditEntryEntity>

    @Query("DELETE FROM audit_entries WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}
