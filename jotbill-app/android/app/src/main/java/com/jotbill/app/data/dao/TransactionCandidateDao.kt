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
    // TODO(2026-11-04 死代码标注)：ai_order_id 恒 null ⇒ 本查询恒空（"订单号优先匹配"
    //  语义当前不可达）。保留待 AI 候选恢复决策；删除需用户拍板。
    suspend fun findActiveByOrderId(orderId: String, at: Long): List<TransactionCandidateEntity>

    /**
     * 按金额查找活跃会话中未被支付证据占用的候选。
     *
     * 条件：**候选金额与支付金额之差的绝对值落在容差带内**（D-19 双向有界容差
     * `max(¥1.00, 支付金额 × 5%)`，由调用方按同一公式计算后以 `toleranceMinor` 传入）、
     * 未匹配、未绑定证据。
     *
     * ⚠️ 此前为单边条件 `amount_minor >= :amountMinor`（自述"优惠后下降视为正常"）：
     * AI 金额**更低**的候选会在会话解析阶段就被滤掉 ⇒ 证据落孤儿待确认，根本走不到
     * `CheckoutPaymentMatcher.evaluate()` ⇒ 该方向的容差完全失效（D-19 跨层影响）。
     *
     * 容差只在 Kotlin 侧定义一次（`paymentAmountToleranceMinor`），**不得在本 SQL 内联公式**。
     * `ABS(NULL - x)` 结果为 NULL ⇒ 金额缺失的候选被排除（与旧条件同样排除，此处 `IS NOT NULL`
     * 仅为把该隐含语义显式化）。
     * 时间窗口过滤由调用方在内存中执行（需结合候选时间和支付时间）。
     */
    @Query(
        """
        SELECT candidates.* FROM transaction_candidates candidates
        INNER JOIN checkout_sessions sessions ON sessions.id = candidates.session_id
        WHERE candidates.amount_minor IS NOT NULL
          AND ABS(candidates.amount_minor - :amountMinor) <= :toleranceMinor
          AND candidates.matched = 0
          AND candidates.evidence_amount_minor IS NULL
          AND sessions.status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
          AND sessions.expires_at > :at
        ORDER BY candidates.created_at DESC
        """
    )
    suspend fun findUnoccupiedByAmountCovering(
        amountMinor: Long,
        toleranceMinor: Long,
        at: Long
    ): List<TransactionCandidateEntity>

    /**
     * 查找最近一次 AI 失败且尚未被支付证据补齐的占位候选。
     * 供支付结果晚到时按时间窗口把孤儿证据回填到原结算会话，避免“结算页截图了、
     * AI 失败、支付又成功”的场景被拆成两条互不关联的待确认。
     */
    @Query(
        """
        SELECT candidates.* FROM transaction_candidates candidates
        INNER JOIN checkout_sessions sessions ON sessions.id = candidates.session_id
        WHERE candidates.conflict_reason = 'AI_RECOGNITION_FAILED'
          AND candidates.amount_minor IS NULL
          AND candidates.evidence_amount_minor IS NULL
          AND candidates.matched = 0
          AND sessions.status = 'FAILED'
          AND sessions.created_at BETWEEN :since AND :until
        ORDER BY candidates.created_at DESC
        """
    )
    suspend fun findAiFailedUnmatchedCandidates(since: Long, until: Long): List<TransactionCandidateEntity>
}
