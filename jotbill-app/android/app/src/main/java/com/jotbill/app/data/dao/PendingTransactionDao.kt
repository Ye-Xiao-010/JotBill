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
     * 跨通道已入账吸收：按「类型 + 金额 + **创建时间窗**」查仍在 pending 的卡（**不限平台**）。
     *
     * 场景（真机 2026-11-16）：支付宝结果页证据先落卡（平台=支付宝包名），
     * 几秒后银行短信自动入账（平台=银行短信）—— 卡与账单并存，用户确认卡即
     * 双记。自动入账成功后调用本查询吸收同额卡，不再依赖包名维度。
     *
     * 窗口落在 `created_at` 而非 `occurred_at`：短信业务时间只到分钟（"10:36"），
     * 与证据观测时刻可能差 60 秒以上，用真实创建时刻才稳定。
     *
     * 只匹配 `status='pending'`；退款/转账/手续费卡由调用方按 type 隔离。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND type = :type
          AND amount_minor = :amountMinor
          AND created_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        """
    )
    suspend fun findPendingByAmountWindow(
        type: String,
        amountMinor: Long,
        windowStart: Long,
        windowEnd: Long
    ): List<PendingTransactionEntity>

    /**
     * 启动期跨通道收口：找出「与同额账单在 [windowMillis] 内并存」的支出待确认卡。
     *
     * 真机 2026-11-16 遗留形态：修复前支付宝结果页与银行短信会各留一张卡、一笔账单；
     * 启动时把这类卡标记忽略，避免用户在待确认中心确认后双记。单号冲突（都非空
     * 且不同）不吸收，真实第二笔仍保留。
     */
    @Query(
        """
        SELECT p.* FROM pending_transactions p
        WHERE p.status = 'pending'
          AND p.type = 'expense'
          AND EXISTS (
            SELECT 1 FROM bills b
            WHERE b.type = 'expense'
              AND b.amount_minor = p.amount_minor
              AND ABS(b.created_at - p.created_at) <= :windowMillis
              AND (
                p.order_id IS NULL OR TRIM(p.order_id) = ''
                OR b.order_id IS NULL OR TRIM(b.order_id) = ''
                OR UPPER(TRIM(b.order_id)) = UPPER(TRIM(p.order_id))
              )
          )
        """
    )
    suspend fun findPendingExpensesWithRecentSameAmountBill(
        windowMillis: Long
    ): List<PendingTransactionEntity>

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
     * M2 转账跨腿查重：同包同额的**转账**待确认卡列表（窗口内）。
     *
     * 结果页个人转账（`recordPersonalTransfer`，收款人存 merchant 列）与通知
     * 转账/还款（`TransferClassifier`，收款人存 appliedRules.recipient）是
     * 两条独立键族，同一笔转账会各落一张卡。本查询供两侧在建卡前互查；
     * 收款人一致性由调用方在 Kotlin 侧比对（两处存储位置不同），**收款人
     * 不同绝不许合并**（拍板：不得把两笔真实转账并成一笔）。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND type = 'transfer'
          AND platform = :platform
          AND amount_minor = :amountMinor
          AND occurred_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        """
    )
    suspend fun findRecentTransferPendings(
        platform: String,
        amountMinor: Long,
        windowStart: Long,
        windowEnd: Long
    ): List<PendingTransactionEntity>

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

    /**
     * B1：同原单 + 同金额的退款待确认卡条数 —— 导入退款行的跨源查重判据之一。
     * 通知链路的退款待确认（`pending-refund` 键族）经 original_bill_id 锚定原交易；
     * 只匹配 status='pending'（已忽略/已删除的卡不构成重复）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM pending_transactions
        WHERE status = 'pending'
          AND type = 'refund'
          AND original_bill_id = :originalBillId
          AND amount_minor = :amountMinor
        """
    )
    suspend fun countPendingRefundsByOriginalAndAmount(originalBillId: String, amountMinor: Long): Int

    /**
     * 跨来源退款去重（2026-11-16）：同额退款卡在时间窗内已存在 ⇒ 判为同一笔退款。
     *
     * 同一笔退款可能同时被拼多多通知（「退款成功通知」）、微信/支付宝退款通知、
     * 银行到账短信等多条信号描述 —— 各来源 sourceEventKey 不同，单靠键幂等挡不住，
     * 需按「同额 + 创建时间窗」在 RefundLinker 入口收口，避免同笔退款多张卡。
     */
    @Query(
        """
        SELECT * FROM pending_transactions
        WHERE status = 'pending'
          AND type = 'refund'
          AND amount_minor = :amountMinor
          AND created_at BETWEEN :windowStart AND :windowEnd
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentPendingRefundByAmount(
        amountMinor: Long,
        windowStart: Long,
        windowEnd: Long
    ): PendingTransactionEntity?
}
