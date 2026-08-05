package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.AppSettingEntity

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: AppSettingEntity): Long

    @Query("SELECT value FROM app_settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_settings ORDER BY key ASC")
    suspend fun getAll(): List<AppSettingEntity>
}
