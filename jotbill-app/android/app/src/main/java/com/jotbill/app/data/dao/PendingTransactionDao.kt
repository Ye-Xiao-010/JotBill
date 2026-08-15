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
     * 基于版本号的乐观锁删除：确认入账事务内使用，
     * 版本一致时物理删除（Bill 与审计日志承接追溯），返回受影响行数。
     */
    @Query(
        "DELETE FROM pending_transactions WHERE id = :id AND version = :expectedVersion"
    )
    suspend fun deleteByIdIfVersion(id: String, expectedVersion: Int): Int
}
