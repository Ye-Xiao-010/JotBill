package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.CheckoutSessionEntity

@Dao
interface CheckoutSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: CheckoutSessionEntity): Long

    @Update
    suspend fun update(session: CheckoutSessionEntity)

    @Query("SELECT * FROM checkout_sessions WHERE id = :id")
    suspend fun getById(id: String): CheckoutSessionEntity?

    @Query("SELECT * FROM checkout_sessions WHERE status IN (:statuses) ORDER BY created_at DESC")
    suspend fun getByStatuses(statuses: List<String>): List<CheckoutSessionEntity>

    @Query("SELECT * FROM checkout_sessions WHERE checkout_request_key = :key LIMIT 1")
    suspend fun findByRequestKey(key: String): CheckoutSessionEntity?

    @Query("UPDATE checkout_sessions SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}
