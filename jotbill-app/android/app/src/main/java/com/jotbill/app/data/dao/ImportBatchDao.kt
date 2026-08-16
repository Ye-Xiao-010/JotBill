package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.ImportBatchEntity

/**
 * 阶段六 T1：导入批次 DAO。
 *
 * 文件哈希唯一索引承担文件级查重；批次查询为预览（重复文件）与撤销入口提供数据。
 */
@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(batch: ImportBatchEntity): Long

    @Query("SELECT * FROM import_batches WHERE file_hash = :fileHash LIMIT 1")
    suspend fun findByFileHash(fileHash: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getById(id: String): ImportBatchEntity?

    /** 最近完成的批次是 T6 撤销入口唯一允许撤销的对象。 */
    @Query(
        """
        SELECT * FROM import_batches
        WHERE status = 'COMPLETED'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestCompleted(): ImportBatchEntity?

    /** 前端撤销入口所需的批次列表；只暴露非 RUNNING 批次，避免展示进行中的中间态。 */
    @Query(
        """
        SELECT * FROM import_batches
        WHERE status != 'RUNNING'
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getPage(limit: Int, offset: Int): List<ImportBatchEntity>

    /** 中断恢复：进程重启后发现 RUNNING 且无对应进行中任务的批次，按失败处理并清理。 */
    @Query("SELECT * FROM import_batches WHERE status = 'RUNNING' ORDER BY created_at ASC")
    suspend fun getRunning(): List<ImportBatchEntity>

    @Query(
        """
        UPDATE import_batches SET
          status = :status,
          imported_count = :importedCount,
          merged_count = :mergedCount,
          skipped_count = :skippedCount,
          pending_count = :pendingCount,
          failed_count = :failedCount,
          suspicious_count = :suspiciousCount,
          account_unmatched_count = :accountUnmatchedCount,
          updated_at = :updatedAt,
          completed_at = :completedAt
        WHERE id = :id AND status = 'RUNNING'
        """
    )
    suspend fun finish(
        id: String,
        status: String,
        importedCount: Int,
        mergedCount: Int,
        skippedCount: Int,
        pendingCount: Int,
        failedCount: Int,
        suspiciousCount: Int,
        accountUnmatchedCount: Int,
        updatedAt: Long,
        completedAt: Long?
    ): Int

    @Query(
        """
        UPDATE import_batches SET
          status = :status,
          updated_at = :updatedAt,
          completed_at = :completedAt
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        updatedAt: Long,
        completedAt: Long?
    ): Int
}
