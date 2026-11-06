package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.PendingTransactionEntity

/**
 * 待确认交易 DAO。
 *
 * 阶段四 T6 约定：
 * - 列表与计数只返回 status = pending 的记录，忽略记录仅供审计查询；
 * - 确认入账与忽略都必须基于 version 做乐观锁，条件更新/删除失败即版本冲突；
 * - 不提供自动过期删除，过期策略由后续阶段处理。
 */
@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pending: PendingTransactionEntity): Long

    /** 待确认 ID 按 sessionId 确定性生成，重复证据/重复决策不产生第二条待确认。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(pending: PendingTransactionEntity): Long

    @Update
    suspend fun update(pending: PendingTransactionEntity)

    @Delete
    suspend fun delete(pending: PendingTransactionEntity)

    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getPage(limit: Int, offset: Int): List<PendingTransactionEntity>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status = 'pending'")
    suspend fun count(): Int

    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: String): PendingTransactionEntity?

    /** 按会话查找待处理记录，供会话自动入账后清理对应待确认。 */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE session_id = :sessionId AND status = 'pending'
        LIMIT 1
        """
    )
    suspend fun findPendingBySessionId(sessionId: String): PendingTransactionEntity?

    /**
     * D-25：查"同支付包 + 同金额 + 落在时间窗内"的待确认记录（孤儿去重用）。
     *
     * 背景：支付结果页的事件键含 15 秒时间桶（`PaymentResultParser` 引用
     * `PaymentTiming.SAME_TXN_WINDOW_MILLIS`，2026-10-24 前为 30 秒），
     * 跨桶即产生第二个幂等键。
     * `CheckoutPaymentMatcher.mergeSessionEvidence` 的 2 分钟收敛只作用于**证据行**，
     * 收敛不到由 `recordOrphanPaymentSignal` 独立新建的**孤儿待确认卡**
     * ⇒ 真机上同一笔 ¥19.90 落了两条待确认（`FIX_TASK_2026-09-29` §三）。
     *
     * 本查询是那条兜底缺失的补口：按"包名 + 金额 + 时间窗"认出重复观测，
     * 合并 `conflictReason` 而不新建第二张卡。
     *
     * ⚠️ 只匹配 `status = 'pending'`：已被用户忽略的记录不该再吸收新的冲突码
     * （保留原审计语义）。`platform` 为 null 的行天然不匹配（SQL 三值逻辑），
     * 即"平台未知"不做合并 —— 保守侧。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND platform = :platform
          AND amount_minor = :amountMinor
          AND occurred_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentPendingByPlatformAndAmount(
        platform: String,
        amountMinor: Long,
        windowStart: Long,
        windowEnd: Long
    ): PendingTransactionEntity?

    /**
     * P3 跳包双卡收敛：**跨包**同额待确认查询 —— 跳包支付（购物 App 拉起微信/
     * 支付宝）会被站内腿与支付包腿各落一张卡，两卡金额相同、时间相邻、分属
     * 不同包名。本查询与 [findRecentPendingByPlatformAndAmount] 互补：只查
     * **其它包**的卡，供调用方归并标记而非双卡并存。
     *
     * 银行短信（com.android.mms）卡被显式排除：短信同额可能是另一笔真实交易
     * （拍板②：短信 orderId 恒空，不与支付信号归并），不得被跨包吸收。
     * 同样只匹配 `status='pending'` 且 `type='expense'`（键族隔离口径与
     * [findRecentPendingByOrderId] 一致）。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND type = 'expense'
          AND platform != :platform
          AND platform != :excludedPlatform
          AND amount_minor = :amountMinor
          AND occurred_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentPendingByAmountCrossPlatform(
        platform: String,
        excludedPlatform: String,
        amountMinor: Long,
        windowStart: Long,
        windowEnd: Long
    ): PendingTransactionEntity?

    /**
     * P1 多信号合并：按「支付身份」的强键 —— **订单号**（跨源跨包）查可归并的
     * 既有待确认卡。
     *
     * 与 [findRecentPendingByPlatformAndAmount] 的分工（拍板 2026-11-04）：
     * - orderId 非空且两侧相等 ⇒ 走本查询，**跨平台**归并，窗口 2 分钟
     *   （对齐 `PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 语义，订单号客观证明同笔，
     *   补偿通知/站内腿的跨源晚到）；
     * - orderId 缺失 ⇒ 维持同包同额 15s（[findRecentPendingByPlatformAndAmount]，
     *   行为不变）；
     * - orderId 冲突（两侧都非空且不同）⇒ **显式拒绝归并**（调用方不调本查询 /
     *   旧查询前先做冲突护栏），宁可两张可删的卡，不误并两笔真单。
     *
     * 只匹配 `status='pending'` 且 `type='expense'`：退款/手续费/转账卡键族独立
     * （`pending-refund`/`pending-fee`/`pending-transfer`），不得被普通支付信号吸收。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND type = 'expense'
          AND UPPER(TRIM(order_id)) = UPPER(TRIM(:orderId))
          AND occurred_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentPendingByOrderId(
        orderId: String,
        windowStart: Long,
        windowEnd: Long
    ): PendingTransactionEntity?

    /**
     * P4 合并留痕：归并第二条信号时同时更新冲突码与留痕键，与
     * [updateConflictReason] 同口径 —— 只改诊断列、**不递增 version**
     * （version 服务于确认/忽略乐观锁，归并不应使前端已持有的版本失效）。
     */
    @Query(
        """
        UPDATE pending_transactions
        SET conflict_reason = :conflictReason, applied_rules = :appliedRules
        WHERE id = :id AND status = 'pending'
        """
    )
    suspend fun updateMergeTrace(id: String, conflictReason: String, appliedRules: String): Int

    /**
     * 只更新冲突原因列（批 V-4）：用于同一笔支付被两条路径各落一次孤儿时的
     * **诊断信息保全**——幂等键保证不产生第二条待确认，但后到路径的
     * `conflictReason`（如 `PAYMENT_PROCESS_SNAPSHOT_MISSING`，量化"搬运漏检率"
     * 的日志锚点）不得被静默丢弃。
     *
     * 只改该列、**不递增 version**：version 服务于确认/忽略的乐观锁，
     * 诊断信息合并不应使前端已持有的版本失效。
     */
    @Query(
        """
        UPDATE pending_transactions
        SET conflict_reason = :conflictReason
        WHERE id = :id AND status = 'pending'
        """
    )
    suspend fun updateConflictReason(id: String, conflictReason: String): Int

    /**
     * 基于版本号的乐观锁忽略：版本一致时置为 ignored 并递增版本，返回受影响行数。
     * 返回 0 表示版本不一致或记录不存在，调用方应拒绝忽略。
     */
    @Query(
        """
        UPDATE pending_transactions
        SET status = 'ignored', version = version + 1
        WHERE id = :id AND version = :expectedVersion AND status = 'pending'
        """
    )
    suspend fun markIgnoredIfVersion(id: String, expectedVersion: Int): Int

    /**
     * 基于版本号的乐观锁删除：确认入账事务内使用。
     * 版本一致且仍为 pending 时物理删除（Bill 与审计日志承接追溯），返回受影响行数。
     * 已忽略（ignored）的记录即使版本一致也不允许再确认入账。
     */
    @Query(
        """
        DELETE FROM pending_transactions
        WHERE id = :id AND version = :expectedVersion AND status = 'pending'
        """
    )
    suspend fun deleteByIdIfVersion(id: String, expectedVersion: Int): Int
}
