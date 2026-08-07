package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.AiProviderConfigEntity

@Dao
interface AiProviderConfigDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(config: AiProviderConfigEntity): Long

    @Update
    suspend fun update(config: AiProviderConfigEntity)

    @Delete
    suspend fun delete(config: AiProviderConfigEntity)

    @Query("SELECT * FROM ai_provider_configs ORDER BY is_default DESC")
    suspend fun getAll(): List<AiProviderConfigEntity>

    @Query("SELECT * FROM ai_provider_configs WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): AiProviderConfigEntity?

    @Query("SELECT * FROM ai_provider_configs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AiProviderConfigEntity?
}
