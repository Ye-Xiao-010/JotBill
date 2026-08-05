package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.TransactionCandidateEntity

@Dao
interface TransactionCandidateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(candidate: TransactionCandidateEntity): Long

    @Update
    suspend fun update(candidate: TransactionCandidateEntity)

    @Delete
    suspend fun delete(candidate: TransactionCandidateEntity)

    @Query("SELECT * FROM transaction_candidates WHERE id = :id")
    suspend fun getById(id: String): TransactionCandidateEntity?

    @Query("SELECT * FROM transaction_candidates WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySession(sessionId: String): List<TransactionCandidateEntity>
}
