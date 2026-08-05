package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.FinancialGoalEntity

@Dao
interface FinancialGoalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goal: FinancialGoalEntity): Long

    @Update
    suspend fun update(goal: FinancialGoalEntity)

    @Delete
    suspend fun delete(goal: FinancialGoalEntity)

    @Query("SELECT * FROM financial_goals ORDER BY target_date ASC")
    suspend fun getAll(): List<FinancialGoalEntity>

    @Query("SELECT * FROM financial_goals WHERE id = :id")
    suspend fun getById(id: String): FinancialGoalEntity?
}
