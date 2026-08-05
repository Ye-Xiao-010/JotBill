package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.RuleEntity

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    @Query("SELECT * FROM rules WHERE is_active = 1 ORDER BY priority DESC")
    suspend fun getActive(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY priority DESC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: String): RuleEntity?
}
