package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.AiCostRecordEntity

@Dao
interface AiCostRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AiCostRecordEntity): Long

    @Query("SELECT * FROM ai_cost_records WHERE month = :month ORDER BY created_at DESC")
    suspend fun getByMonth(month: String): List<AiCostRecordEntity>

    @Query("SELECT COALESCE(SUM(cost_minor), 0) FROM ai_cost_records WHERE month = :month")
    suspend fun getMonthlyCost(month: String): Long

    @Query("SELECT COUNT(*) FROM ai_cost_records WHERE month = :month AND success = 1")
    suspend fun getMonthlySuccessCount(month: String): Int

    @Query("DELETE FROM ai_cost_records WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}
