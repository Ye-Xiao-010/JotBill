package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.BalanceCalibrationEntity

@Dao
interface BalanceCalibrationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(calibration: BalanceCalibrationEntity): Long

    @Query("SELECT * FROM balance_calibrations WHERE account_id = :accountId ORDER BY created_at DESC")
    suspend fun getByAccount(accountId: String): List<BalanceCalibrationEntity>
}
