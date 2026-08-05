package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.PendingTransactionEntity

@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pending: PendingTransactionEntity): Long

    @Update
    suspend fun update(pending: PendingTransactionEntity)

    @Delete
    suspend fun delete(pending: PendingTransactionEntity)

    @Query("SELECT * FROM pending_transactions ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<PendingTransactionEntity>

    @Query("SELECT COUNT(*) FROM pending_transactions")
    suspend fun count(): Int

    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: String): PendingTransactionEntity?
}
