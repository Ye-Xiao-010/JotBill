package com.jotbill.app.importing

import android.content.Context
import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.ImportBatchEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.FieldPriorityResolver
import org.json.JSONArray
import org.json.JSONObject
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
        val accountUnmatched: Boolean = false
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
    ): JSONObject {
        ensureNotRunning()
        var prepared: ImportFilePreparer.PreparedImport? = null
        try {
            val requestedCandidate = candidateFileName?.takeIf { it.isNotBlank() }
            prepared = preparer.prepare(filePath, password)
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
    ): JSONObject {
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

    private suspend fun candidateSelectionJson(
        prepared: ImportFilePreparer.PreparedImport
    ): JSONObject {
        val duplicateBatch = database.importBatchDao().findByFileHash(prepared.fileHash)
        return JSONObject().apply {
            put("needsCandidateSelection", true)
            put("source", JSONObject.NULL)
            put("format", prepared.format)
            put("fileHash", prepared.fileHash)
            put("encrypted", prepared.encrypted)
            put("duplicateFile", duplicateBatch != null)
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
            put("source", loaded.adapter.source)
            put("format", loaded.candidate.format)
            put("formatVersion", loaded.adapter.formatVersion)
            put("fileHash", prepared.fileHash)
            put("encrypted", prepared.encrypted)
            put("duplicateFile", duplicateBatch != null)
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
        database.importBatchDao().insert(batch)
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
                            mergeImported(row, loaded.adapter, accounts, batchId)
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
                        failedCount = counts.failed,
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
                    failedCount = 0,
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
        if (row.type != "refund" && !row.merchantOrderId.isNullOrBlank()) {
            val mergeTarget = database.billDao().findByOrderIdForImportMerge(row.merchantOrderId)
            if (mergeTarget != null) {
                return RowDecision(RowDecision.MERGED)
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
        return RowDecision(
            kind = RowDecision.IMPORTED,
            suspicious = similarHits.isNotEmpty(),
            accountUnmatched = accountsState.unmatched
        )
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
        val source = matchAccount(sourceTexts, accounts)
        val targetTexts = listOfNotNull(
            row.merchant?.trim()?.takeIf { it.isNotEmpty() },
            row.paymentMethod?.trim()?.takeIf { it.isNotEmpty() }
        )
        val target = matchAccount(
            texts = targetTexts,
            accounts = accounts,
            allowedKinds = if (row.type == "repayment") setOf("liability") else null
        )?.takeIf { it.id != source?.id }
        val bothMatched = source != null && target != null
        return ResolvedImportAccounts(
            sourceAccount = source,
            targetAccount = target,
            unmatched = !bothMatched
        )
    }

    private fun matchAccount(
        texts: List<String>,
        accounts: List<AccountEntity>,
        allowedKinds: Set<String>? = null
    ): AccountEntity? {
        val candidates = accounts.filter { allowedKinds == null || it.kind in allowedKinds }
        val normalizedTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedTexts.isEmpty()) return null
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
        batchId: String
    ) {
        val orderId = row.merchantOrderId
            ?: throw ImportException("IMPORT_FAILED", "合并路径缺少商户订单号")
        val existing = database.billDao().findByOrderIdForImportMerge(orderId)
            ?: throw ImportException("IMPORT_FAILED", "合并目标账单已不存在")
        val accountsState = resolveImportAccounts(row, accounts)
        val now = System.currentTimeMillis()
        val locks = FieldPriorityResolver.parseLocks(existing.fieldLocks)
        val current = FieldPriorityResolver.LOCKABLE_FIELDS.associateWith { field ->
            FieldPriorityResolver.Resolved(
                value = FieldPriorityResolver.billFieldValue(existing, field),
                source = null
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
        database.pendingTransactionDao().insertIfAbsent(pending)
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
            val direction = if (target.kind == "liability") -1L else 1L
            database.accountDao().updateBalance(
                id = target.id,
                newBalance = Math.addExact(
                    target.currentBalanceMinor,
                    Math.multiplyExact(bill.amountMinor * direction, multiplier)
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
        const val REFUND_PENDING_THRESHOLD_MINOR = 100_000_00L
    }
}
