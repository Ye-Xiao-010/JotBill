package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.model.NetWorthRow
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT * FROM accounts ORDER BY sort_order ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE is_active = 1 ORDER BY sort_order ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Query("UPDATE accounts SET current_balance_minor = :newBalance, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBalance(id: String, newBalance: Long, updatedAt: Long)

    @Query(
        """
        SELECT
          COALESCE(SUM(
            CASE
              WHEN kind = 'asset' THEN current_balance_minor
              WHEN kind = 'liability' AND current_balance_minor > 0 THEN current_balance_minor
              ELSE 0
            END
          ), 0) AS totalAssets,
          COALESCE(SUM(
            CASE
              WHEN kind = 'liability' AND current_balance_minor < 0 THEN -current_balance_minor
              ELSE 0
            END
          ), 0) AS totalLiabilities
        FROM accounts
        WHERE is_active = 1
        """
    )
    suspend fun getNetWorth(): NetWorthRow

    @Query("SELECT COUNT(*) FROM accounts WHERE is_active = 1")
    suspend fun activeCount(): Int
}
