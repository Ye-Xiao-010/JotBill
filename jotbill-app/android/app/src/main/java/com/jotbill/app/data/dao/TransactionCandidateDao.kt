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

    /** 候选 ID 按 sessionId 确定，避免重复回调创建第二个候选。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(candidate: TransactionCandidateEntity): Long

    @Update
    suspend fun update(candidate: TransactionCandidateEntity)

    /** 基于候选版本和会话绑定收口用户确认/忽略结果，防止并发覆盖。 */
    @Query(
        """
        UPDATE transaction_candidates
        SET matched = :matched,
            conflict_reason = :conflictReason,
            applied_rules = :appliedRules,
            version = version + 1
        WHERE id = :id
          AND session_id = :sessionId
          AND version = :expectedVersion
        """
    )
    suspend fun resolveIfVersion(
        id: String,
        sessionId: String,
        expectedVersion: Int,
        matched: Boolean,
        conflictReason: String?,
        appliedRules: String
    ): Int

    @Delete
    suspend fun delete(candidate: TransactionCandidateEntity)

    @Query("SELECT * FROM transaction_candidates WHERE id = :id")
    suspend fun getById(id: String): TransactionCandidateEntity?

    @Query("SELECT * FROM transaction_candidates WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySession(sessionId: String): List<TransactionCandidateEntity>

    /**
     * 按订单号查找活跃会话的候选（订单号一致优先级最高，直接匹配）。
     * 只返回未进入终态且未过期会话的候选。
     */
    @Query(
        """
        SELECT candidates.* FROM transaction_candidates candidates
        INNER JOIN checkout_sessions sessions ON sessions.id = candidates.session_id
        WHERE candidates.ai_order_id = :orderId
          AND sessions.status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
          AND sessions.expires_at > :at
        ORDER BY candidates.created_at DESC
        """
    )
    suspend fun findActiveByOrderId(orderId: String, at: Long): List<TransactionCandidateEntity>

    /**
     * 按金额查找活跃会话中未被支付证据占用的候选。
     * 条件：候选金额 >= 支付金额（优惠后下降视为正常）、未匹配、未绑定证据。
     * 时间窗口过滤由调用方在内存中执行（需结合候选时间和支付时间）。
     */
    @Query(
        """
        SELECT candidates.* FROM transaction_candidates candidates
        INNER JOIN checkout_sessions sessions ON sessions.id = candidates.session_id
        WHERE candidates.amount_minor >= :amountMinor
          AND candidates.matched = 0
          AND candidates.evidence_amount_minor IS NULL
          AND sessions.status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
          AND sessions.expires_at > :at
        ORDER BY candidates.created_at DESC
        """
    )
    suspend fun findUnoccupiedByAmountCovering(amountMinor: Long, at: Long): List<TransactionCandidateEntity>
}
