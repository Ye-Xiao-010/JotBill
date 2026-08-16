package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阶段六 T1：账单导入批次实体（docs/tdd/10_phase6_tasks.md 第 3.6 节）。
 *
 * 批次只保存文件内容哈希、来源/格式/导入器版本、状态与汇总计数，
 * 不保存账单文件内容、解压产物或密码。文件级重复检测依赖 [fileHash] 唯一索引。
 *
 * 状态机：
 * ```
 * RUNNING -> COMPLETED  正常完成
 * RUNNING -> CANCELLED  用户取消（已写入部分已清理）
 * RUNNING -> FAILED     解析或写入失败（已写入部分已清理）
 * COMPLETED -> UNDONE   整批撤销成功
 * COMPLETED -> UNDO_FAILED 撤销存在受保护记录（部分撤销）
 * ```
 */
@Entity(
    tableName = "import_batches",
    indices = [
        Index(value = ["file_hash"], unique = true),
        Index(value = ["status"]),
        Index(value = ["created_at"])
    ]
)
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "file_hash") val fileHash: String,
    val source: String,
    val format: String,
    @ColumnInfo(name = "format_version") val formatVersion: String,
    @ColumnInfo(name = "importer_version") val importerVersion: String,
    val status: String,
    @ColumnInfo(name = "total_records") val totalRecords: Int = 0,
    @ColumnInfo(name = "valid_records") val validRecords: Int = 0,
    @ColumnInfo(name = "imported_count") val importedCount: Int = 0,
    @ColumnInfo(name = "merged_count") val mergedCount: Int = 0,
    @ColumnInfo(name = "skipped_count") val skippedCount: Int = 0,
    @ColumnInfo(name = "pending_count") val pendingCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "suspicious_count") val suspiciousCount: Int = 0,
    @ColumnInfo(name = "account_unmatched_count") val accountUnmatchedCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_UNDONE = "UNDONE"
        const val STATUS_UNDO_FAILED = "UNDO_FAILED"

        /** 导入器版本：用于批次可追溯，解析/写入规则变化时递增。 */
        const val IMPORTER_VERSION = "1"
    }
}
