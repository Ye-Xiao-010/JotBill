package com.jotbill.app.importing

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.ImportBatchEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.FieldPriorityResolver
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阶段六 T5：导入预览与执行引擎（docs/tdd/10_phase6_tasks.md 任务卡 T5）。
 *
 * - [inspect] 只读：解析、分类统计、重复文件标记，不产生账单/待确认/批次。
 * - [execute] 先落 RUNNING 批次，再分批事务写入；批内原子，批次状态与计数同步推进。
 * - 跨来源合并只经 FieldPriorityResolver(Source.IMPORT) 更新可变字段；
 * - 账户匹配按 3.3 节口径（支付方式/交易对方与启用账户名精确或双向包含）；
 * - 取消/失败后按审计 operator=batchId 清理半批数据。
 */
internal class ImportEngine(
    private val context: Context,
    private val database: JotBillDatabase
) {
    class ImportException(
        val code: String,
        override val message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    private val preparer = ImportFilePreparer(context)
    private val adapters = listOf(WechatBillAdapter, AlipayBillAdapter)
    private val running = AtomicBoolean(false)

    private class RunState {
        @Volatile
        var cancelled: Boolean = false
    }

    @Volatile
    private var activeRun: RunState? = null

    /**
     * inspect 与 execute 串行化：execute 的 running CAS 只挡"先 inspect 后 execute"，
     * 反向（execute 进行中 inspect 已越过 ensureNotRunning）仍会让预览读到分批写入
     * 的中间态，预览计数与最终结果不一致。两入口统一持锁。
     */
    private val lifecycleMutex = Mutex()

    /**
     * 导入生命周期的**唯一互斥闸**：撤销与中断恢复也必须走它。
     *
     * 此前只有 inspect / execute 持锁，而 [ImportUndo] 完全在锁外。后果是
     * `undoLatestCompleted` 进入事务后第一步 `recoverInterruptedBatchesInTransaction()`
     * 会把**正在执行**的 RUNNING 批次当成崩溃孤儿处理：删掉已写入的账单、
     * 反向恢复余额、置 FAILED；而 execute 的分块事务继续写后续 chunk，最终
     * `finish()` 带 `WHERE status='RUNNING'` 匹配 0 行且**返回值被丢弃**，
     * execute 照样返回 import_succeeded。调用方看到成功，数据残缺、余额错乱。
     *
     * 走同一把锁后，"撤销"与"导入"不可能交错。
     */
    suspend fun <T> withLifecycleLock(block: suspend () -> T): T = lifecycleMutex.withLock {
        block()
    }

    /** 用户取消当前导入；进程/模块异常路径由 BackupModule.invalidate 调用。 */
    fun requestCancel() {
        activeRun?.cancelled = true
    }

    private data class LoadedImport(
        val prepared: ImportFilePreparer.PreparedImport,
        val candidate: ImportFilePreparer.Candidate,
        val adapter: BillImportAdapter,
        val parseResult: ImportParseResult
    )

    private data class PlannedCounts(
        var imported: Int = 0,
        var merged: Int = 0,
        var skipped: Int = 0,
        var pending: Int = 0,
        var failed: Int = 0
    )

    private data class InspectionPlan(
        val counts: PlannedCounts,
        val typeDistribution: MutableMap<String, Int>,
        val startAt: Long?,
        val endAt: Long?,
        val errors: MutableList<String>,
        val suspiciousCount: Int,
        val accountUnmatchedCount: Int
    )

    private data class RowDecision(
        val kind: String,
        val error: String? = null,
        val suspicious: Boolean = false,
        val accountUnmatched: Boolean = false,
        /**
         * P3 拍板③：MERGED 决策携带的合并目标账单 id —— 无商户订单号的强一致
         * 合并不能靠 orderId 重查目标，由 decide 选定后传给 [mergeImported]。
         * null 时（有 merchantOrderId 的路径）维持原按订单号重查语义。
         */
        val mergeTargetId: String? = null
    ) {
        companion object {
            const val IMPORTED = "imported"
            const val MERGED = "merged"
            const val SKIPPED = "skipped"
            const val PENDING = "pending"
            const val FAILED = "failed"
        }
    }

    suspend fun inspect(
        filePath: String,
        password: String?,
        candidateFileName: String?
    ): JSONObject = lifecycleMutex.withLock {
        ensureNotRunning()
        var prepared: ImportFilePreparer.PreparedImport? = null
        try {
            val requestedCandidate = candidateFileName?.takeIf { it.isNotBlank() }
            prepared = preparer.prepare(filePath, password)
            if (prepared.encrypted && password.isNullOrBlank()) {
                return passwordRequiredJson(prepared)
            }
            if (prepared.candidates.size > 1 && requestedCandidate == null) {
                return candidateSelectionJson(prepared)
            }
            val loaded = loadPrepared(prepared, requestedCandidate)
            return buildInspectionJson(loaded)
        } finally {
            prepared?.let { preparer.cleanup(it) }
        }
    }

    suspend fun execute(
        filePath: String,
        password: String?,
        candidateFileName: String?
    ): JSONObject = lifecycleMutex.withLock {
        if (!running.compareAndSet(false, true)) {
            throw ImportException("IMPORT_IN_PROGRESS", "已有导入任务正在执行")
        }
        val runState = RunState()
        activeRun = runState
        var prepared: ImportFilePreparer.PreparedImport? = null
        var batchId: String? = null
        try {
            val loaded = load(filePath, password, candidateFileName)
            prepared = loaded.prepared
            val plan = buildPlan(loaded)
            batchId = createRunningBatch(loaded, plan)

            val result = executeLoaded(loaded, batchId, runState)
            preparer.cleanup(loaded.prepared)
            prepared = null
            return result
        } catch (error: ImportException) {
            val cancelled = error.code == "IMPORT_CANCELLED"
            batchId?.let { id ->
                runCatching { cleanupPartialBatch(id, respectUserProtection = false) }
                database.importBatchDao().updateStatus(
                    id = id,
                    status = if (cancelled) {
                        ImportBatchEntity.STATUS_CANCELLED
                    } else {
                        ImportBatchEntity.STATUS_FAILED
                    },
                    updatedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis()
                )
            }
            throw error
        } catch (error: Exception) {
            batchId?.let { id ->
                runCatching { cleanupPartialBatch(id, respectUserProtection = false) }
                database.importBatchDao().updateStatus(
                    id = id,
                    status = ImportBatchEntity.STATUS_FAILED,
                    updatedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis()
                )
            }
            throw ImportException("IMPORT_FAILED", error.message ?: "导入执行失败", error)
        } finally {
            prepared?.let { preparer.cleanup(it) }
            activeRun = null
            running.set(false)
        }
    }

    private fun load(
        filePath: String,
        password: String?,
        candidateFileName: String?
    ): LoadedImport {
        val prepared = preparer.prepare(filePath, password)
        return loadPrepared(prepared, candidateFileName)
    }

    private fun loadPrepared(
        prepared: ImportFilePreparer.PreparedImport,
        candidateFileName: String?
    ): LoadedImport {
        val candidate = selectCandidate(prepared, candidateFileName)
        val adapter = adapters.firstOrNull {
            candidate.format in it.supportedFormats && it.canParse(candidate.file, candidate.encoding)
        } ?: throw ImportException(
            "UNSUPPORTED_FORMAT",
            "无法识别该账单文件，请确认是微信/支付宝官方 CSV、XLSX 或加密压缩包"
        )
        val parseResult = adapter.parse(candidate.file, candidate.encoding)
        return LoadedImport(
            prepared = prepared,
            candidate = candidate,
            adapter = adapter,
            parseResult = parseResult
        )
    }

    private fun selectCandidate(
        prepared: ImportFilePreparer.PreparedImport,
        requestedFileName: String?
    ): ImportFilePreparer.Candidate {
        if (prepared.candidates.isEmpty()) {
            throw ImportException(
                "IMPORT_PASSWORD_REQUIRED",
                "该账单压缩包已加密，请输入解压密码"
            )
        }
        val requested = requestedFileName?.takeIf { it.isNotBlank() }
        return when {
            requested != null -> prepared.candidates.firstOrNull {
                it.fileName.equals(requested, ignoreCase = true)
            } ?: throw ImportException(
                "INVALID_ARGUMENT",
                "压缩包内找不到指定候选文件：$requested"
            )
            prepared.candidates.size == 1 -> prepared.primaryCandidate
            else -> throw ImportException(
                "IMPORT_CANDIDATE_REQUIRED",
                "压缩包内存在多个候选账单文件，请选择要导入的文件"
            )
        }
    }

    /**
     * file_hash 唯一索引仍会阻断导入的批次状态。
     * FAILED/CANCELLED/UNDONE 批次在 createRunningBatch 时被释放，允许同文件重试；
     * RUNNING/COMPLETED/UNDO_FAILED 仍阻断（进行中/已入账/撤销部分失败）。
     */
    private fun isHashBlockingBatch(batch: ImportBatchEntity?): Boolean =
        batch != null && batch.status in setOf(
            ImportBatchEntity.STATUS_RUNNING,
            ImportBatchEntity.STATUS_COMPLETED,
            ImportBatchEntity.STATUS_UNDO_FAILED
        )

    private suspend fun passwordRequiredJson(
        prepared: ImportFilePreparer.PreparedImport
    ): JSONObject {
        val duplicateBatch = database.importBatchDao().findByFileHash(prepared.fileHash)
        return JSONObject().apply {
            put("filePath", prepared.originalPath)
            put("passwordRequired", true)
            put("needsCandidateSelection", false)
            put("source", JSONObject.NULL)
            put("format", prepared.format)
            put("fileHash", prepared.fileHash)
            put("encrypted", true)
            put("duplicateFile", isHashBlockingBatch(duplicateBatch))
            put("candidates", JSONArray())
            put("sampleErrors", JSONArray())
        }
    }

    private suspend fun candidateSelectionJson(
        prepared: ImportFilePreparer.PreparedImport
    ): JSONObject {
        val duplicateBatch = database.importBatchDao().findByFileHash(prepared.fileHash)
        return JSONObject().apply {
            put("filePath", prepared.originalPath)
            put("needsCandidateSelection", true)
            put("source", JSONObject.NULL)
            put("format", prepared.format)
            put("fileHash", prepared.fileHash)
            put("encrypted", prepared.encrypted)
            put("duplicateFile", isHashBlockingBatch(duplicateBatch))
            put("sampleErrors", JSONArray())
            put(
                "candidates",
                JSONArray().apply {
                    prepared.candidates.forEach { candidate ->
                        put(
                            JSONObject()
                                .put("fileName", candidate.fileName)
                                .put("format", candidate.format)
                                .put("encoding", candidate.encoding ?: JSONObject.NULL)
                        )
                    }
                }
            )
        }
    }

    private suspend fun buildInspectionJson(loaded: LoadedImport): JSONObject {
        val plan = buildPlan(loaded)
        val prepared = loaded.prepared
        val duplicateBatch = database.importBatchDao().findByFileHash(prepared.fileHash)
        val errors = JSONArray()
        plan.errors.take(MAX_SAMPLE_ERRORS).forEach { errors.put(it) }
        val typeDistribution = JSONObject()
        plan.typeDistribution.toSortedMap().forEach { (type, count) -> typeDistribution.put(type, count) }
        return JSONObject().apply {
            put("filePath", prepared.originalPath)
            put("source", loaded.adapter.source)
            put("format", loaded.candidate.format)
            put("formatVersion", loaded.adapter.formatVersion)
            put("fileHash", prepared.fileHash)
            put("encrypted", prepared.encrypted)
            put("duplicateFile", isHashBlockingBatch(duplicateBatch))
            put("needsCandidateSelection", prepared.candidates.size > 1)
            put(
                "candidates",
                JSONArray().apply {
                    prepared.candidates.forEach { candidate ->
                        put(
                            JSONObject()
                                .put("fileName", candidate.fileName)
                                .put("format", candidate.format)
                                .put("encoding", candidate.encoding ?: JSONObject.NULL)
                        )
                    }
                }
            )
            put("totalRecords", loaded.parseResult.totalRecords)
            put("validRecords", loaded.parseResult.validRecords)
            put("filteredRecords", loaded.parseResult.filteredRecords)
            put("skippedBySource", loaded.parseResult.skippedRecords)
            plan.startAt?.let { put("timeRangeStart", it) } ?: put("timeRangeStart", JSONObject.NULL)
            plan.endAt?.let { put("timeRangeEnd", it) } ?: put("timeRangeEnd", JSONObject.NULL)
            put(
                "planned",
                JSONObject()
                    .put("imported", plan.counts.imported)
                    .put("merged", plan.counts.merged)
                    .put("skipped", plan.counts.skipped)
                    .put("pending", plan.counts.pending)
                    .put("failed", plan.counts.failed)
            )
            put("suspiciousCount", plan.suspiciousCount)
            put("accountUnmatchedCount", plan.accountUnmatchedCount)
            put("typeDistribution", typeDistribution)
            put("sampleErrors", errors)
        }
    }

    private suspend fun buildPlan(loaded: LoadedImport): InspectionPlan {
        val accounts = database.accountDao().getAll().filter { it.isActive }
        val counts = PlannedCounts()
        val typeDistribution = mutableMapOf<String, Int>()
        val errors = loaded.parseResult.errors
            .map { "第${it.lineNumber}行：${it.message}｜${it.sample}" }
            .toMutableList()
        var startAt: Long? = null
        var endAt: Long? = null
        var suspiciousCount = 0
        var accountUnmatchedCount = 0

        for (row in loaded.parseResult.rows) {
            typeDistribution[row.type] = (typeDistribution[row.type] ?: 0) + 1
            if (startAt == null || row.occurredAt < startAt) startAt = row.occurredAt
            if (endAt == null || row.occurredAt > endAt) endAt = row.occurredAt

            val decision = decide(row, loaded.adapter, accounts)
            when (decision.kind) {
                RowDecision.IMPORTED -> counts.imported += 1
                RowDecision.MERGED -> counts.merged += 1
                RowDecision.SKIPPED -> counts.skipped += 1
                RowDecision.PENDING -> counts.pending += 1
                RowDecision.FAILED -> {
                    counts.failed += 1
                    decision.error?.let { errors += it }
                }
            }
            if (decision.suspicious) suspiciousCount += 1
            if (decision.accountUnmatched) accountUnmatchedCount += 1
        }
        return InspectionPlan(
            counts = counts,
            typeDistribution = typeDistribution,
            startAt = startAt,
            endAt = endAt,
            errors = errors,
            suspiciousCount = suspiciousCount,
            accountUnmatchedCount = accountUnmatchedCount
        )
    }

    private suspend fun createRunningBatch(
        loaded: LoadedImport,
        plan: InspectionPlan
    ): String {
        val now = System.currentTimeMillis()
        val batch = ImportBatchEntity(
            id = "import-batch-${UUID.randomUUID()}",
            fileHash = loaded.prepared.fileHash,
            source = loaded.adapter.source,
            format = loaded.prepared.format,
            formatVersion = loaded.adapter.formatVersion,
            importerVersion = ImportBatchEntity.IMPORTER_VERSION,
            status = ImportBatchEntity.STATUS_RUNNING,
            totalRecords = loaded.parseResult.totalRecords,
            validRecords = loaded.parseResult.validRecords,
            createdAt = now,
            updatedAt = now
        )
        try {
            database.importBatchDao().insert(batch)
        } catch (_: SQLiteConstraintException) {
            // file_hash 唯一索引命中：FAILED/CANCELLED/UNDONE 批次不阻断重试——
            // 释放（删除）旧批次行后重建，否则一次瞬时失败（合并版本冲突、进程被杀）
            // 会让该文件被永久阻断且只报笼统的 IMPORT_FAILED。
            // RUNNING/COMPLETED/UNDO_FAILED 仍拒绝，给出可理解的错误。
            val existing = database.importBatchDao().findByFileHash(batch.fileHash)
            when (existing?.status) {
                null -> throw ImportException("IMPORT_FAILED", "导入批次写入冲突，请重试")
                ImportBatchEntity.STATUS_RUNNING ->
                    throw ImportException("IMPORT_IN_PROGRESS", "该文件已有导入任务进行中")
                ImportBatchEntity.STATUS_FAILED,
                ImportBatchEntity.STATUS_CANCELLED,
                ImportBatchEntity.STATUS_UNDONE -> {
                    database.importBatchDao().deleteById(existing.id)
                    database.importBatchDao().insert(batch)
                }
                else -> throw ImportException(
                    "DUPLICATE_FILE",
                    "该账单文件已导入过，不能重复导入；如需重导请先撤销原批次"
                )
            }
        }
        return batch.id
    }

    private suspend fun executeLoaded(
        loaded: LoadedImport,
        batchId: String,
        runState: RunState
    ): JSONObject {
        val accounts = database.accountDao().getAll().filter { it.isActive }
        val counts = PlannedCounts()
        val errors = loaded.parseResult.errors
            .map { "第${it.lineNumber}行：${it.message}｜${it.sample}" }
            .toMutableList()
        var suspiciousCount = 0
        var accountUnmatchedCount = 0
        val typeDistribution = mutableMapOf<String, Int>()

        loaded.parseResult.rows.chunked(BATCH_SIZE).forEachIndexed { batchIndex, rows ->
            if (runState.cancelled) {
                throw ImportException("IMPORT_CANCELLED", "导入已取消，已写入部分已清理")
            }
            database.withTransaction {
                rows.forEach { row ->
                    if (runState.cancelled) {
                        throw ImportException("IMPORT_CANCELLED", "导入已取消，已写入部分已清理")
                    }
                    typeDistribution[row.type] = (typeDistribution[row.type] ?: 0) + 1
                    val decision = decide(row, loaded.adapter, accounts)
                    when (decision.kind) {
                        RowDecision.IMPORTED -> {
                            writeImported(row, loaded.adapter, accounts, batchId)
                            counts.imported += 1
                        }

                        RowDecision.MERGED -> {
                            mergeImported(
                                row, loaded.adapter, accounts, batchId,
                                mergeTargetId = decision.mergeTargetId
                            )
                            counts.merged += 1
                        }
                        RowDecision.SKIPPED -> counts.skipped += 1
                        RowDecision.PENDING -> {
                            writePending(row, batchId)
                            counts.pending += 1
                        }
                        RowDecision.FAILED -> {
                            counts.failed += 1
                            decision.error?.let { errors += it }
                        }
                    }
                    if (decision.suspicious) suspiciousCount += 1
                    if (decision.accountUnmatched) accountUnmatchedCount += 1
                }
                val lastBatchIndex = (loaded.parseResult.rows.size + BATCH_SIZE - 1) / BATCH_SIZE - 1
                if (batchIndex == lastBatchIndex) {
                    val now = System.currentTimeMillis()
                    database.importBatchDao().finish(
                        id = batchId,
                        status = ImportBatchEntity.STATUS_COMPLETED,
                        importedCount = counts.imported,
                        mergedCount = counts.merged,
                        skippedCount = counts.skipped,
                        pendingCount = counts.pending,
                        // 解析层失败(金额/时间无法解析等)此前只进 errors 样本、不计入
                        // failed_count,导致"总-有效"口径对不上
                        failedCount = counts.failed + loaded.parseResult.errors.size,
                        suspiciousCount = suspiciousCount,
                        accountUnmatchedCount = accountUnmatchedCount,
                        updatedAt = now,
                        completedAt = now
                    )
                }
            }
        }
        if (loaded.parseResult.rows.isEmpty()) {
            database.withTransaction {
                val now = System.currentTimeMillis()
                database.importBatchDao().finish(
                    id = batchId,
                    status = ImportBatchEntity.STATUS_COMPLETED,
                    importedCount = 0,
                    mergedCount = 0,
                    skippedCount = 0,
                    pendingCount = 0,
                    failedCount = loaded.parseResult.errors.size,
                    suspiciousCount = 0,
                    accountUnmatchedCount = 0,
                    updatedAt = now,
                    completedAt = now
                )
            }
        }
        return JSONObject().apply {
            put("status", "import_succeeded")
            put("batchId", batchId)
            put("source", loaded.adapter.source)
            put("format", loaded.candidate.format)
            put("formatVersion", loaded.adapter.formatVersion)
            put("fileHash", loaded.prepared.fileHash)
            put("imported", counts.imported)
            put("merged", counts.merged)
            put("skipped", counts.skipped)
            put("pending", counts.pending)
            put("failed", counts.failed)
            put("duplicates", counts.skipped)
            put("replaced", counts.merged)
            put("duplicateRecordPolicy", "skip")
            put("suspiciousCount", suspiciousCount)
            put("accountUnmatchedCount", accountUnmatchedCount)
            put("totalRecords", loaded.parseResult.totalRecords)
            put("validRecords", loaded.parseResult.validRecords)
            put("processed", loaded.parseResult.rows.size)
            put("typeDistribution", JSONObject().apply {
                typeDistribution.toSortedMap().forEach { (type, count) -> put(type, count) }
            })
            put(
                "errors",
                JSONArray().apply { errors.take(MAX_SAMPLE_ERRORS).forEach { put(it) } }
            )
        }
    }

    private fun ensureNotRunning() {
        if (running.get()) {
            throw ImportException("IMPORT_IN_PROGRESS", "已有导入任务正在执行")
        }
    }

    private suspend fun decide(
        row: ParsedImportRow,
        adapter: BillImportAdapter,
        accounts: List<AccountEntity>
    ): RowDecision {
        if (row.amountMinor <= 0L || row.amountMinor > MAX_SAFE_MINOR_UNIT) {
            return RowDecision(RowDecision.FAILED, "第${row.sourceLineNumber}行：金额超出可入账范围")
        }
        if (row.transactionId.isNullOrBlank()) {
            return RowDecision(RowDecision.FAILED, "第${row.sourceLineNumber}行：缺少交易单号")
        }
        val guard = DuplicateGuard(database)
        val level3Hit = guard.checkLevel3(row.transactionId)
        if (level3Hit != null) {
            return RowDecision(RowDecision.SKIPPED)
        }
        if (row.type != "refund") {
            if (!row.merchantOrderId.isNullOrBlank()) {
                val mergeTarget = database.billDao().findByOrderIdForImportMerge(row.merchantOrderId)
                if (mergeTarget != null) {
                    return RowDecision(RowDecision.MERGED, mergeTargetId = mergeTarget.id)
                }
            } else {
                // P3 拍板③：无商户订单号时的强一致合并 —— 金额精确 + 同日 +
                // 商户模糊匹配三者全中且**唯一命中**才 MERGED；缺任一、多候选
                // 一律退回 IMPORTED + 疑似提示（只提示不合并，保守侧）。
                val strongTarget = findStrongIdentityMergeTarget(row)
                if (strongTarget != null) {
                    return RowDecision(RowDecision.MERGED, mergeTargetId = strongTarget.id)
                }
            }
        }
        val accountsState = resolveImportAccounts(row, accounts)
        val similarHits = guard.findSimilar(
            type = row.type,
            amountMinor = row.amountMinor,
            occurredAt = row.occurredAt,
            accountId = accountsState.sourceAccount?.id,
            merchant = row.merchant,
            excludeId = null
        )
        if (row.type == "refund" && findRefundOriginal(row) == null &&
            row.amountMinor >= REFUND_PENDING_THRESHOLD_MINOR
        ) {
            return RowDecision(RowDecision.PENDING)
        }
        // ---- P9：账户未匹配 ⇒ 显式决策（转待确认），不再落「无账户账单」----
        // 旧行为：账户未匹配的行仍以 accountId=null 直接入账，余额静默不动
        // （applyImportedBalance 对空账户直接 return）—— 账单落库了却没有任何
        // 账户归属，统计与余额口径里这笔完全隐形。改为转待确认卡，由用户在
        // 待确认中心显式选择账户后入账；accountUnmatched 计数口径保持不变。
        if (accountsState.unmatched) {
            return RowDecision(RowDecision.PENDING, accountUnmatched = true)
        }
        return RowDecision(
            kind = RowDecision.IMPORTED,
            suspicious = similarHits.isNotEmpty(),
            accountUnmatched = false
        )
    }

    /**
     * P3 拍板③：无商户订单号导入行的「强一致」合并目标判定。
     *
     * 三条件**全部命中**才算同一笔（缺任一即退回只提示）：
     * 1. 金额精确相等（同 type）；
     * 2. 发生时间同一天（本地日 00:00–24:00 窗口）；
     * 3. 商户模糊匹配（[merchantFuzzyMatch]）。
     *
     * 目标护栏与 `findByOrderIdForImportMerge` 同源：排除退款/带原交易关联的账单
     * （导入行不得并进退款账单）。**多候选命中不合并** —— 无法客观证明是同一笔，
     * 保守退回 IMPORTED + 疑似提示，由用户判断。
     *
     * internal 供 `ImportMergeTargetTest` 直驱（三条件判别性验证）。
     */
    internal suspend fun findStrongIdentityMergeTarget(row: ParsedImportRow): BillEntity? {
        if (row.amountMinor <= 0L || row.occurredAt <= 0L) return null
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.ofInstant(Instant.ofEpochMilli(row.occurredAt), zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L - 1
        val candidates = database.billDao().findSimilarAmountWindow(
            type = row.type,
            amountMinor = row.amountMinor,
            fromAt = dayStart,
            toAt = dayEnd,
            excludeId = null
        ).filter { bill ->
            bill.originalTransactionId.isNullOrBlank() &&
                merchantFuzzyMatch(bill.merchant, row.merchant)
        }
        return candidates.singleOrNull()
    }

    /**
     * 商户模糊匹配（P3 条件 3）：两侧都非空，且相等或互相包含（忽略大小写）。
     * 任一侧为空视为**不命中** —— 无商户信息不足以证明同一笔，保守退回只提示。
     */
    private fun merchantFuzzyMatch(existing: String?, incoming: String?): Boolean {
        val left = existing?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val right = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return left.equals(right, ignoreCase = true) ||
            left.contains(right, ignoreCase = true) ||
            right.contains(left, ignoreCase = true)
    }

    private data class ResolvedImportAccounts(
        val sourceAccount: AccountEntity?,
        val targetAccount: AccountEntity?,
        val unmatched: Boolean
    )

    private fun resolveImportAccounts(
        row: ParsedImportRow,
        accounts: List<AccountEntity>
    ): ResolvedImportAccounts {
        val sourceTexts = listOfNotNull(
            row.paymentMethod?.trim()?.takeIf { it.isNotEmpty() },
            row.merchant?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (row.type != "transfer" && row.type != "repayment") {
            val source = matchAccount(sourceTexts, accounts)
            return ResolvedImportAccounts(
                sourceAccount = source,
                targetAccount = null,
                unmatched = source == null
            )
        }
        // transfer / repayment 需要**两个不同**账户。
        //
        // ⚠️ 此前这里把同一组文本仅换顺序传给两次匹配（source=[paymentMethod, merchant]、
        // target=[merchant, paymentMethod]），而 matchAccount 对入参顺序完全不敏感
        // （exact 用 distinctBy 后判 size==1，contained 用 any）⇒ source 与 target
        // 必然解析出同一个账户，随即被 `takeIf { it.id != source?.id }` 抹成 null。
        // 后果：targetAccountId 恒为 null → applyImportedBalance 直接 return →
        // 导入的转账/还款**从不改动任何账户余额**，且 accountUnmatchedCount 被虚高。
        //
        // 正确语义：付款方来自 paymentMethod（账单里的付款渠道/账户），
        // 对方账户来自 merchant（交易对手）。因此两侧各自主字段优先，
        // 主字段匹配不到时才回退到"两个字段一起试"，以兼容只填了一列的账单。
        val paymentMethod = row.paymentMethod?.trim()?.takeIf { it.isNotEmpty() }
        val merchantName = row.merchant?.trim()?.takeIf { it.isNotEmpty() }
        val source = matchAccount(
            texts = listOfNotNull(paymentMethod, merchantName),
            accounts = accounts,
            preferred = paymentMethod
        )
        val target = matchAccount(
            texts = listOfNotNull(merchantName, paymentMethod),
            accounts = accounts,
            preferred = merchantName,
            allowedKinds = if (row.type == "repayment") setOf("liability") else null
        )?.takeIf { it.id != source?.id }
        val bothMatched = source != null && target != null
        return ResolvedImportAccounts(
            sourceAccount = source,
            targetAccount = target,
            unmatched = !bothMatched
        )
    }

    /**
     * 按文本匹配账户。
     *
     * @param preferred 该侧的**主字段**文本（转账源=付款渠道、目标=交易对手）。
     *   主字段能单独唯一命中时直接采用；否则回退到用 [texts] 全组匹配。
     *   这是修复"源与目标用同一组文本换顺序 ⇒ 必然解析出同一账户 ⇒ 目标恒为 null"
     *   的关键：两侧必须能各自锚定到不同的主字段。
     */
    private fun matchAccount(
        texts: List<String>,
        accounts: List<AccountEntity>,
        allowedKinds: Set<String>? = null,
        preferred: String? = null
    ): AccountEntity? {
        val candidates = accounts.filter { allowedKinds == null || it.kind in allowedKinds }
        val normalizedTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedTexts.isEmpty()) return null
        // 主字段单独唯一命中 ⇒ 直接采用，不再看其它文本（否则两侧会撞同一个账户）
        preferred?.trim()?.takeIf { it.isNotEmpty() }?.let { primary ->
            val primaryExact = candidates.firstOrNull { it.name == primary }
            if (primaryExact != null) return primaryExact
            val primaryContained = candidates.filter { account ->
                val name = account.name
                name.contains(primary) || primary.contains(name)
            }
            if (primaryContained.size == 1) return primaryContained.single()
        }
        val exact = normalizedTexts
            .mapNotNull { text -> candidates.firstOrNull { it.name == text } }
            .distinctBy { it.id }
        if (exact.size == 1) return exact.first()
        val contained = candidates.filter { account ->
            val name = account.name
            normalizedTexts.any { text -> name.contains(text) || text.contains(name) }
        }
        return contained.singleOrNull()
    }

    private suspend fun writeImported(
        row: ParsedImportRow,
        adapter: BillImportAdapter,
        accounts: List<AccountEntity>,
        batchId: String
    ) {
        val accountsState = resolveImportAccounts(row, accounts)
        val now = System.currentTimeMillis()
        val refundOriginalId = if (row.type == "refund") {
            findRefundOriginal(row)?.id
        } else {
            null
        }
        val bill = BillEntity(
            id = "import:${row.transactionId}",
            amountMinor = row.amountMinor,
            type = row.type,
            occurredAt = row.occurredAt,
            description = buildImportedDescription(row, accountsState),
            categoryId = null,
            accountId = accountsState.sourceAccount?.id,
            targetAccountId = accountsState.targetAccount?.id,
            platform = platformLabel(adapter.source),
            merchant = row.merchant,
            productDescription = row.productDescription,
            sourceType = DuplicateGuard.IMPORT_SOURCE_TYPE,
            originalTransactionId = refundOriginalId,
            orderId = row.merchantOrderId,
            createdAt = now,
            updatedAt = now,
            version = 1,
            fieldLocks = "[]",
            importRecordId = row.transactionId
        )
        database.billDao().insert(bill)
        applyImportedBalance(bill)
        database.auditEntryDao().insert(
            AuditEntryEntity(
                id = "${bill.id}:import-create:${batchId}",
                entityType = "bill",
                entityId = bill.id,
                action = "import_create",
                fieldName = null,
                oldValue = null,
                newValue = billToJson(bill).toString(),
                operator = batchId,
                createdAt = now
            )
        )
    }

    private suspend fun mergeImported(
        row: ParsedImportRow,
        adapter: BillImportAdapter,
        accounts: List<AccountEntity>,
        batchId: String,
        mergeTargetId: String? = null
    ) {
        // P3 拍板③：无商户订单号的强一致合并由 decide 选定目标（mergeTargetId），
        // 不能靠 orderId 重查；有 merchantOrderId 的原路径维持按订单号重查语义。
        val existing = if (mergeTargetId != null) {
            database.billDao().getById(mergeTargetId)
                ?: throw ImportException("IMPORT_FAILED", "合并目标账单已不存在")
        } else {
            val orderId = row.merchantOrderId
                ?: throw ImportException("IMPORT_FAILED", "合并路径缺少商户订单号")
            database.billDao().findByOrderIdForImportMerge(orderId)
                ?: throw ImportException("IMPORT_FAILED", "合并目标账单已不存在")
        }
        val accountsState = resolveImportAccounts(row, accounts)
        val now = System.currentTimeMillis()
        val locks = FieldPriorityResolver.parseLocks(existing.fieldLocks)
        val current = FieldPriorityResolver.LOCKABLE_FIELDS.associateWith { field ->
            FieldPriorityResolver.Resolved(
                value = FieldPriorityResolver.billFieldValue(existing, field),
                // 手写账单的既有字段按**用户级**保护：导入可以补空缺，但不得改写用户已填的值。
                // 否则「先手写一笔、再导入同订单号账单」会被 IMPORT(3) 整段覆盖 ——
                // resolve 对 source=null 按 EVIDENCE(4) 处理，低于 IMPORT 必被覆盖。
                // 与 L1 的「手写账不被自动吸收」同源原则：**手写数据完整性优先于去重**。
                source = if (existing.sourceType == "manual") {
                    FieldPriorityResolver.Source.USER_LOCK
                } else {
                    null
                }
            )
        }
        val assignments = mutableListOf<FieldPriorityResolver.Assignment>()
        fun assign(field: String, value: Any?) {
            if (value != null) {
                assignments += FieldPriorityResolver.Assignment(field, value, FieldPriorityResolver.Source.IMPORT)
            }
        }
        assign("amountMinor", row.amountMinor)
        assign("type", row.type)
        assign("occurredAt", row.occurredAt)
        assign("description", buildImportedDescription(row, accountsState))
        assign("accountId", accountsState.sourceAccount?.id)
        assign("targetAccountId", accountsState.targetAccount?.id)
        assign("platform", platformLabel(adapter.source))
        assign("merchant", row.merchant)
        assign("productDescription", row.productDescription)
        val resolved = FieldPriorityResolver.resolve(current, assignments, locks)
        var proposed = existing.copy(
            updatedAt = now,
            version = existing.version + 1
        )
        FieldPriorityResolver.LOCKABLE_FIELDS.forEach { field ->
            if (field != "orderId") {
                proposed = FieldPriorityResolver.withBillFieldValue(proposed, field, resolved.values[field])
            }
        }
        val (protected, lockConflicts) = FieldPriorityResolver.protectLockedUpdate(
            existing,
            proposed,
            FieldPriorityResolver.Source.IMPORT
        )
        applyImportedBalance(existing, multiplier = -1L)
        val updatedRows = database.billDao().updateIfVersion(
            id = protected.id,
            expectedVersion = existing.version,
            amountMinor = protected.amountMinor,
            type = protected.type,
            occurredAt = protected.occurredAt,
            description = protected.description,
            categoryId = protected.categoryId,
            accountId = protected.accountId,
            targetAccountId = protected.targetAccountId,
            platform = protected.platform,
            merchant = protected.merchant,
            productDescription = protected.productDescription,
            sourceType = protected.sourceType,
            originalTransactionId = protected.originalTransactionId,
            orderId = protected.orderId,
            fieldLocks = protected.fieldLocks,
            updatedAt = protected.updatedAt,
            newVersion = protected.version
        )
        if (updatedRows != 1) {
            throw ImportException("IMPORT_FAILED", "合并目标账单版本已变化，本次导入已回滚")
        }
        applyImportedBalance(protected)
        database.auditEntryDao().insert(
            AuditEntryEntity(
                id = "${existing.id}:import-merge:${batchId}:${protected.version}",
                entityType = "bill",
                entityId = existing.id,
                action = "import_merge",
                fieldName = null,
                oldValue = billToJson(existing).toString(),
                newValue = billToJson(protected).toString(),
                operator = batchId,
                createdAt = now
            )
        )
        (resolved.conflicts + lockConflicts).forEach { conflict ->
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${existing.id}:import-lock-conflict:${batchId}:${conflict.field}",
                    entityType = "field_lock_conflict",
                    entityId = existing.id,
                    action = "reject",
                    fieldName = conflict.field,
                    oldValue = conflict.rejectedValue?.toString(),
                    newValue = conflict.keptValue?.toString(),
                    operator = batchId,
                    createdAt = now
                )
            )
        }
    }

    private suspend fun writePending(row: ParsedImportRow, batchId: String) {
        val now = System.currentTimeMillis()
        val pending = PendingTransactionEntity(
            id = "import-pending:${row.transactionId}",
            candidateId = null,
            sessionId = null,
            amountMinor = row.amountMinor,
            type = row.type,
            occurredAt = row.occurredAt,
            platform = platformLabel("import"),
            merchant = row.merchant,
            productDescription = row.productDescription,
            orderId = row.merchantOrderId,
            accountId = null,
            categoryId = null,
            conflictReason = "IMPORT_SEMANTIC_UNSAFE",
            sourceType = DuplicateGuard.IMPORT_SOURCE_TYPE,
            createdAt = now,
            version = 1,
            status = PendingTransactionEntity.STATUS_PENDING
        )
        val insertedPending = database.pendingTransactionDao().insertIfAbsent(pending)
        if (insertedPending != -1L) {
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = pending.id,
                    type = pending.type,
                    amountMinor = pending.amountMinor,
                    sourceType = pending.sourceType,
                    conflictReason = pending.conflictReason,
                    occurredAt = pending.occurredAt,
                    isNew = true
                )
            )
        }
        database.auditEntryDao().insertIfAbsent(
            AuditEntryEntity(
                id = "${pending.id}:import-pending:${batchId}",
                entityType = "pending_transaction",
                entityId = pending.id,
                action = "import_pending",
                fieldName = null,
                oldValue = null,
                newValue = JSONObject()
                    .put("amountMinor", row.amountMinor)
                    .put("type", row.type)
                    .put("occurredAt", row.occurredAt)
                    .put("merchant", row.merchant ?: JSONObject.NULL)
                    .toString(),
                operator = batchId,
                createdAt = now
            )
        )
    }

    private suspend fun findRefundOriginal(row: ParsedImportRow): BillEntity? {
        val orderId = row.merchantOrderId ?: return null
        val expenses = database.billDao().findExpensesByOrderId(orderId)
        return expenses.firstOrNull { it.amountMinor >= row.amountMinor } ?: expenses.firstOrNull()
    }

    private fun buildImportedDescription(
        row: ParsedImportRow,
        accountsState: ResolvedImportAccounts
    ): String? {
        val parts = mutableListOf<String>()
        row.remark?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (accountsState.unmatched && !row.paymentMethod.isNullOrBlank()) {
            parts += "支付方式:${row.paymentMethod}"
        }
        return parts.joinToString("；").takeIf { it.isNotBlank() }
    }

    private fun platformLabel(source: String): String = when (source) {
        "wechat" -> "微信支付"
        "alipay" -> "支付宝"
        else -> "账单导入"
    }

    /**
     * 导入路径余额更新：账户未匹配时只记账不动余额；
     * transfer/repayment 仅源与目标账户都匹配时才更新双账户余额。
     */
    private suspend fun applyImportedBalance(bill: BillEntity, multiplier: Long = 1L) {
        if (bill.type == "transfer" || bill.type == "repayment") {
            val sourceId = bill.accountId?.takeIf { it.isNotBlank() } ?: return
            val targetId = bill.targetAccountId?.takeIf { it.isNotBlank() } ?: return
            if (sourceId == targetId) return
            val source = database.accountDao().getById(sourceId) ?: return
            val target = database.accountDao().getById(targetId) ?: return
            val now = System.currentTimeMillis()
            database.accountDao().updateBalance(
                id = source.id,
                newBalance = Math.addExact(source.currentBalanceMinor, Math.multiplyExact(-bill.amountMinor, multiplier)),
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

    /**
     * 按审计 operator=batchId 清理半批数据：
     * 删除本批新建账单并反向恢复余额，合并更新按审计 old_value 回滚。
     * 失败路径不静默吞错：清理异常会向上抛出，批次状态由调用方置 FAILED。
     */
    suspend fun cleanupPartialBatch(
        batchId: String,
        respectUserProtection: Boolean
    ) {
        val entries = database.auditEntryDao().getByEntityTypeAndOperator("bill", batchId)
            .sortedByDescending { it.createdAt }
        for (entry in entries) {
            when (entry.action) {
                "import_create" -> {
                    val bill = database.billDao().getById(entry.entityId) ?: continue
                    if (respectUserProtection && userModifiedAfter(bill.id, entry.createdAt)) continue
                    applyImportedBalance(bill, multiplier = -1L)
                    database.billDao().deleteById(bill.id)
                }
                "import_merge" -> {
                    val old = billFromJson(entry.oldValue) ?: continue
                    val current = database.billDao().getById(entry.entityId) ?: continue
                    if (respectUserProtection && userModifiedAfter(current.id, entry.createdAt)) continue
                    applyImportedBalance(current, multiplier = -1L)
                    database.billDao().update(old)
                    applyImportedBalance(old)
                }
            }
        }
        val pendingEntries = database.auditEntryDao()
            .getByEntityTypeAndOperator("pending_transaction", batchId)
        for (entry in pendingEntries) {
            val pending = database.pendingTransactionDao().getById(entry.entityId) ?: continue
            database.pendingTransactionDao().delete(pending)
        }
    }

    /** 撤销保护判定：存在该账单在导入审计时间之后的无批次前缀更新审计，或 field_locks 非空。 */
    suspend fun userModifiedAfter(billId: String, importAuditAt: Long): Boolean {
        val bill = database.billDao().getById(billId) ?: return false
        if (FieldPriorityResolver.parseLocks(bill.fieldLocks).isNotEmpty()) return true
        val updates = database.auditEntryDao().getByEntity("bill", billId)
        return updates.any { entry ->
            entry.createdAt > importAuditAt &&
                entry.action == "update" &&
                !entry.operator.startsWith("import-")
        }
    }

    private fun billToJson(bill: BillEntity): JSONObject = JSONObject().apply {
        put("id", bill.id)
        put("amountMinor", bill.amountMinor)
        put("type", bill.type)
        put("occurredAt", bill.occurredAt)
        put("description", bill.description ?: JSONObject.NULL)
        put("categoryId", bill.categoryId ?: JSONObject.NULL)
        put("accountId", bill.accountId ?: JSONObject.NULL)
        put("targetAccountId", bill.targetAccountId ?: JSONObject.NULL)
        put("platform", bill.platform ?: JSONObject.NULL)
        put("merchant", bill.merchant ?: JSONObject.NULL)
        put("productDescription", bill.productDescription ?: JSONObject.NULL)
        put("sourceType", bill.sourceType)
        put("originalTransactionId", bill.originalTransactionId ?: JSONObject.NULL)
        put("orderId", bill.orderId ?: JSONObject.NULL)
        put("createdAt", bill.createdAt)
        put("updatedAt", bill.updatedAt)
        put("version", bill.version)
        put("fieldLocks", bill.fieldLocks)
        put("importRecordId", bill.importRecordId ?: JSONObject.NULL)
    }

    private fun billFromJson(value: String?): BillEntity? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(value)
            BillEntity(
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

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_SAFE_MINOR_UNIT = 9_007_199_254_740_991L
        const val BATCH_SIZE = 200
        const val MAX_SAMPLE_ERRORS = 20
        // 无关联退款达到该金额即转待确认(分)。原值 100_000_00L=¥100,000 系多写一个
        // 数量级,2026-08-29 经用户确认原意 ¥10,000。
        const val REFUND_PENDING_THRESHOLD_MINOR = 10_000_00L
    }
}
