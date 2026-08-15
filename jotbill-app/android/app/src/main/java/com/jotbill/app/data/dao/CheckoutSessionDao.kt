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

    /** 查询当前未进入终态且未过期的会话，供桥接层统计活跃会话使用。 */
    @Query(
        """
        SELECT * FROM checkout_sessions
        WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
          AND expires_at > :at
        ORDER BY updated_at DESC
        """
    )
    suspend fun getActiveSessions(at: Long): List<CheckoutSessionEntity>

    /** 查询当前处于 AI 请求中状态的会话，供桥接层报告 AI 是否在识别中。 */
    @Query(
        """
        SELECT COUNT(*) FROM checkout_sessions
        WHERE status = 'AI_REQUESTING'
          AND expires_at > :at
        """
    )
    suspend fun countAiRequesting(at: Long): Int

    @Query("SELECT * FROM checkout_sessions WHERE checkout_request_key = :key LIMIT 1")
    suspend fun findByRequestKey(key: String): CheckoutSessionEntity?

    @Query(
        """
        SELECT * FROM checkout_sessions
        WHERE shopping_package = :shoppingPackage
          AND page_fingerprint = :pageFingerprint
          AND expires_at > :at
          AND status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
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
