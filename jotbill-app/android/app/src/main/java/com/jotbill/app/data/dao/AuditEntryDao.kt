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

    @Query("SELECT * FROM audit_entries WHERE entity_type = :type AND entity_id = :id ORDER BY created_at DESC")
    suspend fun getByEntity(type: String, id: String): List<AuditEntryEntity>

    @Query("DELETE FROM audit_entries WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}
