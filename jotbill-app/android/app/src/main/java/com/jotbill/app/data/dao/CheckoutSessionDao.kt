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

    /** 会话由稳定 sessionId 幂等创建，避免支付通知与 AI 结果同时到达时插入冲突。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(session: CheckoutSessionEntity): Long

    @Update
    suspend fun update(session: CheckoutSessionEntity)

    @Query("SELECT * FROM checkout_sessions WHERE id = :id")
    suspend fun getById(id: String): CheckoutSessionEntity?

    @Query("SELECT * FROM checkout_sessions WHERE status IN (:statuses) ORDER BY created_at DESC")
    suspend fun getByStatuses(statuses: List<String>): List<CheckoutSessionEntity>

    @Query("SELECT * FROM checkout_sessions WHERE checkout_request_key = :key LIMIT 1")
    suspend fun findByRequestKey(key: String): CheckoutSessionEntity?

    @Query(
        """
        SELECT * FROM checkout_sessions
        WHERE shopping_package = :shoppingPackage
          AND page_fingerprint = :pageFingerprint
          AND expires_at > :at
          AND status NOT IN ('COMPLETED', 'DISMISSED', 'CANCELLED', 'FAILED', 'EXPIRED')
        ORDER BY updated_at DESC
        """
    )
    suspend fun findActiveByPage(
        shoppingPackage: String,
        pageFingerprint: String,
        at: Long
    ): List<CheckoutSessionEntity>

    @Query("UPDATE checkout_sessions SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}
