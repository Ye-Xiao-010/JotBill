package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.PaymentEvidenceEntity

@Dao
interface PaymentEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(evidence: PaymentEvidenceEntity): Long

    /** 证据 ID 由来源事件字段确定，重复通知只返回忽略结果。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(evidence: PaymentEvidenceEntity): Long

    @Delete
    suspend fun delete(evidence: PaymentEvidenceEntity)

    @Query("SELECT * FROM payment_evidence WHERE session_id = :sessionId ORDER BY captured_at ASC")
    suspend fun getBySession(sessionId: String): List<PaymentEvidenceEntity>

    @Query("SELECT * FROM payment_evidence WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PaymentEvidenceEntity?

    @Query("SELECT * FROM payment_evidence WHERE source_event_key = :sourceEventKey LIMIT 1")
    suspend fun getBySourceEventKey(sourceEventKey: String): PaymentEvidenceEntity?

    @Query("SELECT * FROM payment_evidence WHERE order_id = :orderId ORDER BY captured_at DESC")
    suspend fun getByOrderId(orderId: String): List<PaymentEvidenceEntity>
}
