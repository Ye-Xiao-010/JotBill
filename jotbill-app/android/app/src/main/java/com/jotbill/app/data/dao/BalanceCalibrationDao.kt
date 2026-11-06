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

    /**
     * B4：逐账户的校准差额合计。余额不变式须把校准差额纳入口径
     * （current = initial + Σ账单delta + Σ校准diff），否则校准过的账户永远误报。
     */
    @Query("SELECT account_id AS accountId, SUM(diff_minor) AS delta FROM balance_calibrations GROUP BY account_id")
    suspend fun sumDiffByAccount(): List<com.jotbill.app.data.model.AccountDeltaRow>
}
