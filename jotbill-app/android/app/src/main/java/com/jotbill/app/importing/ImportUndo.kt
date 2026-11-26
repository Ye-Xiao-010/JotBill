package com.jotbill.app.importing

import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.ImportBatchEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段六 T6：导入撤销与中断恢复（docs/tdd/10_phase6_tasks.md 第 3.6/3.7 节）。
 *
 * - 只允许撤销最近一批 COMPLETED 批次；
 * - 批次新建账单删除、合并更新按审计 old_value 回滚，余额经同一导入口径反向恢复；
 * - 导入后被用户编辑过（field_locks 非空或存在导入之后的用户更新审计）的记录不自动处理，
 *   逐笔列入 protected 清单，批次状态置 UNDO_FAILED；
 * - 孤儿 RUNNING 批次按失败处理并清理半批数据。
 */
internal class ImportUndo(
    private val database: JotBillDatabase,
    private val engine: ImportEngine
) {
    class UndoException(
        val code: String,
        override val message: String
    ) : Exception(message)

    data class ProtectedRecord(
        val billId: String,
        val reason: String
    )

    data class UndoResult(
        val status: String,
        val deletedCount: Int,
        val rollbackCount: Int,
        val pendingDeletedCount: Int,
        val protectedRecords: List<ProtectedRecord>
    )

    /**
     * 撤销最近一批导入。
     *
     * 整个流程经 [ImportEngine.withLifecycleLock] 串行化：撤销会先调用
     * `recoverInterruptedBatchesInTransaction()`，若此时有导入正在执行，
     * 那个 RUNNING 批次会被当成崩溃孤儿清理（删账单 + 反向余额 + 置 FAILED），
     * 而 execute 的分块事务仍会继续写入后续 chunk 并最终返回"导入成功"。
     * 锁保证"撤销"与"导入"不可能交错。
     */
    suspend fun undoLatestCompleted(batchId: String): UndoResult {
        val result = engine.withLifecycleLock { undoLatestCompletedLocked(batchId) }
        // 撤销可能移除退款/支出：重算预算提醒并重新武装回落档位。
        runCatching {
            com.jotbill.app.budget.BudgetReminderNotifier.checkAndNotify(database)
        }
        return result
    }

    private suspend fun undoLatestCompletedLocked(batchId: String): UndoResult {
        val batch = database.importBatchDao().getById(batchId)
            ?: throw UndoException("NOT_FOUND", "找不到导入批次 $batchId")
        if (batch.status != ImportBatchEntity.STATUS_COMPLETED) {
            throw UndoException("INVALID_STATE", "只有已完成的导入批次可以撤销")
        }
        val latest = database.importBatchDao().getLatestCompleted()
            ?: throw UndoException("INVALID_STATE", "当前没有可撤销的导入批次")
        if (latest.id != batchId) {
            throw UndoException("INVALID_STATE", "只允许撤销最近一批导入")
        }

        val removedPendings = mutableListOf<PendingTransactionEntity>()
        val result = database.withTransaction {
            recoverInterruptedBatchesInTransaction()
            val protectedRecords = mutableListOf<ProtectedRecord>()
            var deletedCount = 0
            var rollbackCount = 0

            // 同毫秒多次合并同一账单时，按 version 严格倒序回滚，防止低版本覆盖高版本回滚结果
            val billAudits = database.auditEntryDao()
                .getByEntityTypeAndOperator("bill", batchId)
                .sortedWith(
                    compareByDescending<AuditEntryEntity> { it.createdAt }
                        .thenByDescending { engine.extractAuditBillVersion(it) }
                        .thenByDescending { it.id }
                )
            for (entry in billAudits) {
                when (entry.action) {
                    "import_create" -> {
                        val bill = database.billDao().getById(entry.entityId)
                        if (bill == null) continue
                        val protection = protectionReason(bill, entry.createdAt)
                        if (protection != null) {
                            protectedRecords += ProtectedRecord(bill.id, protection)
                            continue
                        }
                        rollbackCreatedBill(bill)
                        deletedCount += 1
                    }
                    "import_merge" -> {
                        val current = database.billDao().getById(entry.entityId)
                        if (current == null) continue
                        val protection = protectionReason(current, entry.createdAt)
                        if (protection != null) {
                            protectedRecords += ProtectedRecord(current.id, protection)
                            continue
                        }
                        val old = restoreMergeTarget(entry.oldValue)
                            ?: continue
                        applyBalance(current, -1L)
                        database.billDao().update(old)
                        applyBalance(old, 1L)
                        rollbackCount += 1
                    }
                }
            }

            var pendingDeletedCount = 0
            val pendingAudits = database.auditEntryDao()
                .getByEntityTypeAndOperator("pending_transaction", batchId)
            for (entry in pendingAudits) {
                val pending = database.pendingTransactionDao().getById(entry.entityId)
                if (pending == null) {
                    // 若待确认卡片已由用户确认转正，必须记入受保护清单，避免置 UNDONE 后允许重复导入
                    val confirmAudits = database.auditEntryDao().getByEntity("pending_transaction", entry.entityId)
                    val confirmedAudit = confirmAudits.firstOrNull { it.action == "confirm" }
                    if (confirmedAudit != null) {
                        protectedRecords += ProtectedRecord(
                            billId = confirmedAudit.newValue ?: entry.entityId,
                            reason = "该批次产生的待确认记录已由用户确认入账"
                        )
                    }
                    continue
                }
                database.pendingTransactionDao().delete(pending)
                removedPendings += pending
                pendingDeletedCount += 1
            }

            val status = if (protectedRecords.isEmpty()) {
                ImportBatchEntity.STATUS_UNDONE
            } else {
                ImportBatchEntity.STATUS_UNDO_FAILED
            }
            val now = System.currentTimeMillis()
            database.importBatchDao().updateStatus(
                id = batchId,
                status = status,
                updatedAt = now,
                completedAt = now
            )
            UndoResult(
                status = status,
                deletedCount = deletedCount,
                rollbackCount = rollbackCount,
                pendingDeletedCount = pendingDeletedCount,
                protectedRecords = protectedRecords.toList()
            )
        }
        removedPendings.forEach { pending ->
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = pending.id,
                    type = pending.type,
                    amountMinor = pending.amountMinor,
                    sourceType = pending.sourceType,
                    conflictReason = pending.conflictReason,
                    occurredAt = pending.occurredAt,
                    isNew = false,
                    removed = true
                )
            )
        }
        return result
    }

    fun toJson(result: UndoResult): JSONObject = JSONObject().apply {
        put("status", result.status)
        put("deletedCount", result.deletedCount)
        put("rollbackCount", result.rollbackCount)
        put("pendingDeletedCount", result.pendingDeletedCount)
        put(
            "protectedRecords",
            JSONArray().apply {
                result.protectedRecords.forEach { protected ->
                    put(
                        JSONObject()
                            .put("billId", protected.billId)
                            .put("reason", protected.reason)
                    )
                }
            }
        )
    }

    /**
     * 中断恢复：RUNNING 且无对应进行中任务（进程重启后）按失败清理。
     *
     * 同样经 [ImportEngine.withLifecycleLock] 串行化 —— 它会把 RUNNING 批次当
     * 失败清理，而正在执行的导入也会留下 RUNNING 批次，二者交错会摧毁在途数据。
     */
    suspend fun recoverInterruptedBatches() {
        engine.withLifecycleLock {
            database.withTransaction {
                recoverInterruptedBatchesInTransaction()
            }
        }
    }

    private suspend fun recoverInterruptedBatchesInTransaction() {
        val running = database.importBatchDao().getRunning()
        if (running.isEmpty()) return
        val now = System.currentTimeMillis()
        for (batch in running) {
            engine.cleanupPartialBatchInTransaction(batch.id, respectUserProtection = false)
            database.importBatchDao().updateStatus(
                id = batch.id,
                status = ImportBatchEntity.STATUS_FAILED,
                updatedAt = now,
                completedAt = now
            )
        }
    }

    private suspend fun protectionReason(
        bill: com.jotbill.app.data.entity.BillEntity,
        importAuditAt: Long
    ): String? {
        if (com.jotbill.app.rules.FieldPriorityResolver.parseLocks(bill.fieldLocks).isNotEmpty()) {
            return "账单包含用户锁定字段"
        }
        val hasUserUpdate = database.auditEntryDao().getByEntity("bill", bill.id).any { entry ->
            entry.createdAt > importAuditAt &&
                entry.action == "update" &&
                !entry.operator.startsWith("import-")
        }
        return if (hasUserUpdate) "账单在导入后已被用户编辑" else null
    }

    private suspend fun rollbackCreatedBill(bill: com.jotbill.app.data.entity.BillEntity) {
        applyBalance(bill, -1L)
        database.billDao().deleteById(bill.id)
    }

    private suspend fun restoreMergeTarget(oldValue: String?): com.jotbill.app.data.entity.BillEntity? {
        if (oldValue.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(oldValue)
            com.jotbill.app.data.entity.BillEntity(
                id = json.getString("id"),
                amountMinor = json.getLong("amountMinor"),
                type = json.getString("type"),
                occurredAt = json.getLong("occurredAt"),
                description = json.nullableString("description"),
                categoryId = json.nullableString("categoryId"),
                accountId = json.nullableString("accountId"),
                targetAccountId = json.nullableString("targetAccountId"),
                platform = json.nullableString("platform"),
                merchant = json.nullableString("merchant"),
                productDescription = json.nullableString("productDescription"),
                sourceType = json.getString("sourceType"),
                originalTransactionId = json.nullableString("originalTransactionId"),
                orderId = json.nullableString("orderId"),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
                version = json.getInt("version"),
                fieldLocks = json.optString("fieldLocks", "[]"),
                importRecordId = json.nullableString("importRecordId")
            )
        }.getOrNull()
    }

    private suspend fun applyBalance(
        bill: com.jotbill.app.data.entity.BillEntity,
        multiplier: Long
    ) {
        if (bill.type == "transfer" || bill.type == "repayment") {
            val sourceId = bill.accountId?.takeIf { it.isNotBlank() } ?: return
            val targetId = bill.targetAccountId?.takeIf { it.isNotBlank() } ?: return
            if (sourceId == targetId) return
            val source = database.accountDao().getById(sourceId) ?: return
            val target = database.accountDao().getById(targetId) ?: return
            val now = System.currentTimeMillis()
            database.accountDao().updateBalance(
                id = source.id,
                newBalance = Math.addExact(
                    source.currentBalanceMinor,
                    Math.multiplyExact(-bill.amountMinor, multiplier)
                ),
                updatedAt = now
            )
            // 存储口径：负债账户"负数=欠款"，转入即还款，余额向 0 靠近（与 LedgerRepository 对齐）
            database.accountDao().updateBalance(
                id = target.id,
                newBalance = Math.addExact(
                    target.currentBalanceMinor,
                    Math.multiplyExact(bill.amountMinor, multiplier)
                ),
                updatedAt = now
            )
            return
        }
        val accountId = bill.accountId?.takeIf { it.isNotBlank() } ?: return
        val account = database.accountDao().getById(accountId) ?: return
        val delta = when (bill.type) {
            "income", "refund" -> bill.amountMinor
            "expense", "fee" -> -bill.amountMinor
            else -> return
        }
        database.accountDao().updateBalance(
            id = account.id,
            newBalance = Math.addExact(account.currentBalanceMinor, Math.multiplyExact(delta, multiplier)),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
