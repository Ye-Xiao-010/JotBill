package com.jotbill.app.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jotbill.app.data.JotBillDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** 负责备份文件的快照、校验和事务性覆盖恢复。 */
internal class BackupManager(
    private val database: JotBillDatabase,
) {
    internal class BackupException(
        val code: String,
        override val message: String,
    ) : Exception(message)

    internal data class BackupFile(
        val bytes: ByteArray,
        val checksum: String,
        val createdAt: String,
        val recordSummary: JSONObject,
    )

    internal data class ParsedBackup(
        val filePath: String,
        val formatVersion: Int,
        val checksum: String,
        val createdAt: String,
        val data: JSONObject,
        val recordSummary: JSONObject,
        val counts: RecordCounts,
        val aiProviderConfigsIncluded: Boolean,
        val aiCostRecordsIncluded: Boolean,
        val aiProviderApiBaseUrlSkippedCount: Int,
    )

    internal data class RecordCounts(
        val billCount: Int,
        val accountCount: Int,
        val categoryCount: Int,
        val budgetCount: Int,
        val financialGoalCount: Int,
        val ruleCount: Int,
        val balanceCalibrationCount: Int,
        val auditLogCount: Int,
        val appSettingCount: Int,
        val aiProviderConfigCount: Int,
        val aiCostRecordCount: Int,
    ) {
        val totalCount: Int
            get() = checkedSum(
                billCount,
                accountCount,
                categoryCount,
                budgetCount,
                financialGoalCount,
                ruleCount,
                balanceCalibrationCount,
                auditLogCount,
                appSettingCount,
                aiProviderConfigCount,
                aiCostRecordCount,
            )

        /** AI 记录只恢复非敏感字段，API Key 等凭据始终不进入备份。 */
        val restorableCount: Int
            get() = checkedSum(
                billCount,
                accountCount,
                categoryCount,
                budgetCount,
                financialGoalCount,
                ruleCount,
                balanceCalibrationCount,
                auditLogCount,
                appSettingCount,
                aiProviderConfigCount,
                aiCostRecordCount,
            )

        fun toJson(): JSONObject = JSONObject().apply {
            put("billCount", billCount)
            put("accountCount", accountCount)
            put("categoryCount", categoryCount)
            put("budgetCount", budgetCount)
            put("financialGoalCount", financialGoalCount)
            put("ruleCount", ruleCount)
            put("balanceCalibrationCount", balanceCalibrationCount)
            put("auditLogCount", auditLogCount)
            put("appSettingCount", appSettingCount)
            put("aiProviderConfigCount", aiProviderConfigCount)
            put("aiCostRecordCount", aiCostRecordCount)
            put("totalCount", totalCount)
        }

        private companion object {
            fun checkedSum(vararg values: Int): Int {
                val total = values.fold(0L) { current, value -> current + value.toLong() }
                if (total > Int.MAX_VALUE) {
                    throw IllegalArgumentException("备份记录数量超出支持范围")
                }
                return total.toInt()
            }
        }
    }

    private data class Snapshot(
        val data: JSONObject,
        val counts: RecordCounts,
        val aiProviderApiBaseUrlSkippedCount: Int,
    )

    private data class RestoreMutationResult(
        val duplicateCount: Int,
        val retainedProviderCount: Int,
    )

    /** 在数据库事务中读取一致的快照，避免摘要和记录来自不同时间点。 */
    internal suspend fun createBackup(encrypt: Boolean): BackupFile {
        if (encrypt) {
            throw BackupException("NOT_IMPLEMENTED", "MVP 暂不支持加密备份")
        }

        val snapshot = database.withTransaction { readSnapshot() }
        val createdAt = Instant.now().toString()
        val root = JSONObject().apply {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("source", SOURCE_LOCAL_DATABASE)
            put("createdAt", createdAt)
            put("encrypted", false)
            put("recordSummary", snapshot.counts.toJson())
            put("data", snapshot.data)
            put(
                "ai",
                JSONObject()
                    .put("providerConfigCount", snapshot.counts.aiProviderConfigCount)
                    .put("costRecordCount", snapshot.counts.aiCostRecordCount)
                    .put("apiBaseUrlSkippedCount", snapshot.aiProviderApiBaseUrlSkippedCount),
            )
        }
        val checksum = sha256(canonicalJson(root).toByteArray(StandardCharsets.UTF_8))
        root.put("checksum", checksum)
        val bytes = (root.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw BackupException("BACKUP_IO_ERROR", "生成的备份文件超过 32 MB 大小限制")
        }
        return BackupFile(
            bytes = bytes,
            checksum = checksum,
            createdAt = createdAt,
            recordSummary = snapshot.counts.toJson(),
        )
    }

    internal fun summaryForPath(filePath: String, backup: BackupFile): JSONObject {
        val counts = backup.recordSummary
        return JSONObject().apply {
            put("filePath", filePath)
            put("fileSize", backup.bytes.size)
            put("source", SOURCE_LOCAL_DATABASE)
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("checksum", backup.checksum)
            put("recordSummary", counts)
            put("billCount", counts.getInt("billCount"))
            put("accountCount", counts.getInt("accountCount"))
            put("categoryCount", counts.getInt("categoryCount"))
            put("ruleCount", counts.getInt("ruleCount"))
            put("createdAt", backup.createdAt)
            put("encrypted", false)
        }
    }

    internal fun parse(bytes: ByteArray, filePath: String): ParsedBackup {
        if (filePath.isBlank()) {
            throw BackupException("INVALID_ARGUMENT", "备份文件路径不能为空")
        }
        if (bytes.isEmpty()) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份文件为空")
        }
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw BackupException("BACKUP_IO_ERROR", "备份文件超过大小限制")
        }

        return try {
            parseJson(bytes, filePath)
        } catch (error: BackupException) {
            throw error
        } catch (_: Exception) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份文件格式无效")
        }
    }

    internal suspend fun preview(parsed: ParsedBackup): JSONObject =
        database.withTransaction {
            val existing = readCurrentCounts()
            val duplicateCount = countDuplicates(database.openHelper.readableDatabase, parsed)
            JSONObject().apply {
                put("filePath", parsed.filePath)
                put("source", SOURCE_BACKUP_FILE)
                put("format", FORMAT)
                put("formatVersion", parsed.formatVersion)
                put("checksum", parsed.checksum)
                put("billCount", parsed.counts.billCount)
                put("accountCount", parsed.counts.accountCount)
                put("categoryCount", parsed.counts.categoryCount)
                put("ruleCount", parsed.counts.ruleCount)
                put("backupCreatedAt", parsed.createdAt)
                put("compatible", true)
                put("recordSummary", parsed.recordSummary)
                put("existingRecordSummary", existing.toJson())
                put("duplicateCount", duplicateCount)
                put("duplicateRecordPolicy", DUPLICATE_REPLACE)
            }
        }

    internal suspend fun executeRestore(parsed: ParsedBackup, strategy: String): JSONObject {
        if (strategy == "merge") {
            throw BackupException("NOT_IMPLEMENTED", "MVP 暂不支持合并恢复")
        }
        if (strategy != STRATEGY_OVERWRITE) {
            throw BackupException("INVALID_ARGUMENT", "不支持的恢复策略")
        }

        val mutation = try {
            database.withTransaction {
                val sqlite = database.openHelper.writableDatabase
                val duplicates = countDuplicates(sqlite, parsed)
                deleteRestorableData(sqlite)
                val retainedProviderCount = insertRestorableData(sqlite, parsed)
                RestoreMutationResult(duplicates, retainedProviderCount)
            }
        } catch (error: BackupException) {
            throw error
        } catch (_: Exception) {
            throw BackupException("BACKUP_TRANSACTION_FAILED", "恢复事务失败，原有数据未改变")
        }

        val skipped = (if (parsed.aiProviderConfigsIncluded) 0 else parsed.counts.aiProviderConfigCount) +
            (if (parsed.aiCostRecordsIncluded) 0 else parsed.counts.aiCostRecordCount)
        val warnings = JSONArray()
        if (parsed.aiProviderConfigsIncluded && parsed.counts.aiProviderConfigCount > 0) {
            warnings.put("AI Provider 的非敏感配置字段已恢复；API Key、密钥和凭据未写入备份，也不会由恢复生成")
        }
        if (!parsed.aiProviderConfigsIncluded) {
            warnings.put("备份未包含 AI Provider 配置数组，本类按跳过处理并保留当前配置")
        }
        if (!parsed.aiCostRecordsIncluded) {
            warnings.put("备份未包含 AI 成本记录数组，本类按跳过处理并保留当前记录")
        }
        if (parsed.aiProviderApiBaseUrlSkippedCount > 0) {
            warnings.put("${parsed.aiProviderApiBaseUrlSkippedCount} 个不安全的 AI Provider 地址字段已省略")
        }
        if (mutation.retainedProviderCount > 0) {
            warnings.put("${mutation.retainedProviderCount} 个仍被聊天会话引用的旧 AI Provider 配置已保留")
        }
        return JSONObject().apply {
            put("status", "import_succeeded")
            put("source", SOURCE_BACKUP_FILE)
            put("format", FORMAT)
            put("formatVersion", parsed.formatVersion)
            put("imported", parsed.counts.restorableCount - skipped)
            put("skipped", skipped)
            put("duplicates", mutation.duplicateCount)
            put("replaced", mutation.duplicateCount)
            put("failed", 0)
            put("duplicateRecordPolicy", DUPLICATE_REPLACE)
            put("errors", warnings)
            put("recordSummary", parsed.recordSummary)
            put("strategy", STRATEGY_OVERWRITE)
        }
    }

    private fun parseJson(bytes: ByteArray, filePath: String): ParsedBackup {
        val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF").trim()
        val root = JSONObject(text)
        rejectSensitiveKeys(root)

        val format = requiredString(root, "format")
        if (format != FORMAT) {
            throw BackupException("BACKUP_UNSUPPORTED_FORMAT", "不支持的备份格式")
        }
        val formatVersion = requiredInt(root, "formatVersion")
        if (formatVersion != FORMAT_VERSION) {
            throw BackupException("BACKUP_UNSUPPORTED_FORMAT", "不支持的备份版本")
        }
        if (requiredString(root, "source") != SOURCE_LOCAL_DATABASE) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份来源标识无效")
        }
        if (requiredBoolean(root, "encrypted")) {
            throw BackupException("NOT_IMPLEMENTED", "MVP 暂不支持加密备份恢复")
        }
        val createdAt = requiredString(root, "createdAt")
        try {
            Instant.parse(createdAt)
        } catch (_: Exception) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份生成时间无效")
        }
        val checksum = requiredString(root, "checksum")
        if (!CHECKSUM_PATTERN.matches(checksum)) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份摘要格式无效")
        }
        root.remove("checksum")
        val actualChecksum = sha256(canonicalJson(root).toByteArray(StandardCharsets.UTF_8))
        if (!MessageDigest.isEqual(actualChecksum.toByteArray(), checksum.lowercase(Locale.US).toByteArray())) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份摘要校验失败")
        }

        val ai = requiredObject(root, "ai")
        val aiProviderConfigCount = requiredNonNegativeInt(ai, "providerConfigCount")
        val aiCostRecordCount = requiredNonNegativeInt(ai, "costRecordCount")
        val aiProviderApiBaseUrlSkippedCount = if (ai.has("apiBaseUrlSkippedCount")) {
            requiredNonNegativeInt(ai, "apiBaseUrlSkippedCount")
        } else {
            0
        }
        if (aiProviderApiBaseUrlSkippedCount > aiProviderConfigCount) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "AI Provider 地址跳过数量不合法")
        }
        val data = requiredObject(root, "data")
        val recordSummary = requiredObject(root, "recordSummary")
        val arrays = requiredArrays(data, aiProviderConfigCount, aiCostRecordCount)
        val counts = RecordCounts(
            billCount = arrays.bills.length(),
            accountCount = arrays.accounts.length(),
            categoryCount = arrays.categories.length(),
            budgetCount = arrays.budgets.length(),
            financialGoalCount = arrays.financialGoals.length(),
            ruleCount = arrays.rules.length(),
            balanceCalibrationCount = arrays.balanceCalibrations.length(),
            auditLogCount = arrays.auditEntries.length(),
            appSettingCount = arrays.appSettings.length(),
            aiProviderConfigCount = aiProviderConfigCount,
            aiCostRecordCount = aiCostRecordCount,
        )
        validateSummary(recordSummary, counts)
        validateData(arrays, counts)
        return ParsedBackup(
            filePath = filePath,
            formatVersion = formatVersion,
            checksum = checksum.lowercase(Locale.US),
            createdAt = createdAt,
            data = data,
            recordSummary = recordSummary,
            counts = counts,
            aiProviderConfigsIncluded = arrays.aiProviderConfigsIncluded,
            aiCostRecordsIncluded = arrays.aiCostRecordsIncluded,
            aiProviderApiBaseUrlSkippedCount = aiProviderApiBaseUrlSkippedCount,
        )
    }

    private data class BackupArrays(
        val bills: JSONArray,
        val accounts: JSONArray,
        val categories: JSONArray,
        val budgets: JSONArray,
        val financialGoals: JSONArray,
        val rules: JSONArray,
        val balanceCalibrations: JSONArray,
        val auditEntries: JSONArray,
        val appSettings: JSONArray,
        val aiProviderConfigs: JSONArray,
        val aiCostRecords: JSONArray,
        val aiProviderConfigsIncluded: Boolean,
        val aiCostRecordsIncluded: Boolean,
    )

    private data class OptionalBackupArray(
        val values: JSONArray,
        val included: Boolean,
    )

    private fun requiredArrays(
        data: JSONObject,
        aiProviderConfigCount: Int,
        aiCostRecordCount: Int,
    ): BackupArrays {
        val aiProviderConfigs = optionalArray(data, "aiProviderConfigs", aiProviderConfigCount)
        val aiCostRecords = optionalArray(data, "aiCostRecords", aiCostRecordCount)
        return BackupArrays(
            bills = requiredArray(data, "bills"),
            accounts = requiredArray(data, "accounts"),
            categories = requiredArray(data, "categories"),
            budgets = requiredArray(data, "budgets"),
            financialGoals = requiredArray(data, "financialGoals"),
            rules = requiredArray(data, "rules"),
            balanceCalibrations = requiredArray(data, "balanceCalibrations"),
            auditEntries = requiredArray(data, "auditEntries"),
            appSettings = requiredArray(data, "appSettings"),
            aiProviderConfigs = aiProviderConfigs.values,
            aiCostRecords = aiCostRecords.values,
            aiProviderConfigsIncluded = aiProviderConfigs.included,
            aiCostRecordsIncluded = aiCostRecords.included,
        )
    }

    private fun optionalArray(parent: JSONObject, key: String, expectedCount: Int): OptionalBackupArray {
        if (!parent.has(key)) {
            // 缺失字段表示旧版备份未覆盖该类数据，不能按空数组删除当前记录。
            return OptionalBackupArray(JSONArray(), included = false)
        }
        val value = parent.opt(key)
        if (value !is JSONArray || value.length() != expectedCount) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份数组 $key 与记录摘要不一致")
        }
        return OptionalBackupArray(value, included = true)
    }

    private fun validateSummary(summary: JSONObject, counts: RecordCounts) {
        val expected = counts.toJson()
        val fields = listOf(
            "billCount",
            "accountCount",
            "categoryCount",
            "budgetCount",
            "financialGoalCount",
            "ruleCount",
            "balanceCalibrationCount",
            "auditLogCount",
            "appSettingCount",
            "aiProviderConfigCount",
            "aiCostRecordCount",
            "totalCount",
        )
        fields.forEach { field ->
            if (requiredInt(summary, field) != expected.getInt(field)) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "备份记录摘要与内容不一致")
            }
        }
    }

    private fun validateData(arrays: BackupArrays, counts: RecordCounts) {
        val accountIds = uniqueIds(arrays.accounts, "id")
        val categoryIds = uniqueIds(arrays.categories, "id")
        uniqueIds(arrays.bills, "id")
        uniqueIds(arrays.budgets, "id")
        uniqueIds(arrays.financialGoals, "id")
        uniqueIds(arrays.rules, "id")
        uniqueIds(arrays.balanceCalibrations, "id")
        uniqueIds(arrays.auditEntries, "id")
        uniqueIds(arrays.appSettings, "key")
        if (arrays.aiProviderConfigsIncluded) uniqueIds(arrays.aiProviderConfigs, "id")
        if (arrays.aiCostRecordsIncluded) uniqueIds(arrays.aiCostRecords, "id")

        forEachObject(arrays.accounts) { row ->
            validateText(row, "id")
            validateText(row, "name")
            val subtype = requiredString(row, "subtype")
            val kind = requiredString(row, "kind")
            val expectedKind = if (subtype == "credit_card" || subtype == "consumer_loan") {
                "liability"
            } else {
                "asset"
            }
            if (subtype !in ACCOUNT_SUBTYPES || kind != expectedKind) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账户字段不合法")
            }
            validateText(row, "currency")
            validateOptionalText(row, "icon")
            validateOptionalText(row, "color")
            validateMinor(requiredLong(row, "initialBalanceMinor"), allowNegative = kind == "liability")
            validateMinor(requiredLong(row, "currentBalanceMinor"), allowNegative = true)
            if (kind == "asset" && requiredLong(row, "initialBalanceMinor") < 0L) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "资产账户初始余额不能为负")
            }
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
            requiredInt(row, "sortOrder")
            requiredBoolean(row, "isActive")
        }

        forEachObject(arrays.categories) { row ->
            validateText(row, "id")
            validateText(row, "name")
            if (requiredString(row, "type") !in CATEGORY_TYPES) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "分类类型不合法")
            }
            validateOptionalText(row, "icon")
            validateOptionalText(row, "color")
            requiredBoolean(row, "isDefault")
            requiredInt(row, "sortOrder")
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
        }

        forEachObject(arrays.bills) { row ->
            validateText(row, "id")
            validateMinor(requiredLong(row, "amountMinor"), allowNegative = false)
            if (requiredString(row, "type") !in BILL_TYPES) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单类型不合法")
            }
            if (requiredString(row, "sourceType") !in SOURCE_TYPES) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单来源类型不合法")
            }
            validateTimestamp(row, "occurredAt")
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
            validateOptionalText(row, "description")
            validateOptionalText(row, "platform")
            validateOptionalText(row, "merchant")
            validateOptionalText(row, "productDescription")
            validateOptionalText(row, "originalTransactionId")
            validateOptionalText(row, "orderId")
            val categoryId = optionalString(row, "categoryId")
            val accountId = optionalString(row, "accountId")
            val targetAccountId = optionalString(row, "targetAccountId")
            if (categoryId != null && categoryId !in categoryIds) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单引用了不存在的分类")
            }
            if (accountId != null && accountId !in accountIds) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单引用了不存在的账户")
            }
            if (targetAccountId != null && targetAccountId !in accountIds) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单目标账户不存在")
            }
            if (requiredInt(row, "version") < 1) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "账单版本号不合法")
            }
        }

        forEachObject(arrays.budgets) { row ->
            validateText(row, "id")
            validateText(row, "name")
            validateMinor(requiredLong(row, "amountMinor"), allowNegative = false)
            if (requiredString(row, "period") !in BUDGET_PERIODS) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "预算周期不合法")
            }
            val categoryId = optionalString(row, "categoryId")
            if (categoryId != null && categoryId !in categoryIds) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "预算引用了不存在的分类")
            }
            val alertAt = requiredInt(row, "alertAt")
            if (alertAt !in 0..100) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "预算提醒比例不合法")
            }
            requiredBoolean(row, "isActive")
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
        }

        forEachObject(arrays.financialGoals) { row ->
            validateText(row, "id")
            validateText(row, "name")
            val target = requiredLong(row, "targetAmountMinor")
            val current = requiredLong(row, "currentAmountMinor")
            validateMinor(target, allowNegative = false)
            validateMinor(current, allowNegative = false)
            if (current > target) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "财务目标当前金额超过目标金额")
            }
            val targetDate = optionalLong(row, "targetDate")
            if (targetDate != null && targetDate < 0L) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "财务目标日期不合法")
            }
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
        }

        forEachObject(arrays.rules) { row ->
            validateText(row, "id")
            validateText(row, "name")
            validateText(row, "condition")
            validateText(row, "conditionValue")
            validateText(row, "action")
            validateOptionalText(row, "actionValue")
            requiredInt(row, "priority")
            requiredBoolean(row, "isActive")
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
        }

        forEachObject(arrays.balanceCalibrations) { row ->
            validateText(row, "id")
            val accountId = requiredString(row, "accountId")
            if (accountId !in accountIds) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "余额校准引用了不存在的账户")
            }
            val beforeBalance = requiredLong(row, "beforeBalanceMinor")
            val afterBalance = requiredLong(row, "afterBalanceMinor")
            val diff = requiredLong(row, "diffMinor")
            validateMinor(beforeBalance, allowNegative = true)
            validateMinor(afterBalance, allowNegative = true)
            validateMinor(diff, allowNegative = true)
            if (try {
                    Math.subtractExact(afterBalance, beforeBalance) != diff
                } catch (_: ArithmeticException) {
                    true
                }
            ) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "余额校准差额与前后余额不一致")
            }
            validateOptionalText(row, "note")
            validateTimestamp(row, "createdAt")
        }

        forEachObject(arrays.auditEntries) { row ->
            validateText(row, "id")
            validateText(row, "entityType")
            validateText(row, "entityId")
            validateText(row, "action")
            validateOptionalText(row, "fieldName")
            validateOptionalText(row, "oldValue")
            validateOptionalText(row, "newValue")
            validateText(row, "operator")
            validateTimestamp(row, "createdAt")
        }

        forEachObject(arrays.appSettings) { row ->
            val key = requiredString(row, "key")
            if (key !in SAFE_SETTING_KEYS) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "备份包含不允许恢复的应用设置")
            }
            validateText(row, "value")
            validateTimestamp(row, "updatedAt")
        }

        if (arrays.aiProviderConfigsIncluded) {
            validateAiProviderConfigs(arrays.aiProviderConfigs, counts.aiProviderConfigCount)
        }
        if (arrays.aiCostRecordsIncluded) {
            validateAiCostRecords(arrays.aiCostRecords, counts.aiCostRecordCount)
        }

        if (counts.totalCount < 0) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "备份记录数量不合法")
        }
    }

    private fun validateAiProviderConfigs(rows: JSONArray, expectedCount: Int) {
        if (rows.length() != expectedCount) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "AI Provider 配置摘要与内容不一致")
        }
        forEachObject(rows) { row ->
            if (row.has("apiKeyEncrypted") || row.has("api_key_encrypted")) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "备份包含禁止导入的 API Key 字段")
            }
            validateText(row, "id")
            validateText(row, "name")
            val provider = requiredString(row, "provider")
            if (provider !in AI_PROVIDERS) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "AI Provider 类型不受支持")
            }
            validateText(row, "model")
            val apiBaseUrl = optionalString(row, "apiBaseUrl")
            if (apiBaseUrl != null) {
                validateTextValue(apiBaseUrl, "apiBaseUrl")
                if (safeApiBaseUrl(apiBaseUrl) != apiBaseUrl) {
                    throw BackupException("BACKUP_VALIDATION_FAILED", "AI Provider 地址包含疑似凭据")
                }
            }
            requiredBoolean(row, "isDefault")
            requiredBoolean(row, "supportsVision")
            validateTimestamp(row, "createdAt")
            validateTimestamp(row, "updatedAt")
        }
    }

    private fun validateAiCostRecords(rows: JSONArray, expectedCount: Int) {
        if (rows.length() != expectedCount) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "AI 成本记录摘要与内容不一致")
        }
        forEachObject(rows) { row ->
            validateText(row, "id")
            val provider = requiredString(row, "provider")
            if (provider !in AI_PROVIDERS) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "AI 成本记录的 Provider 类型不受支持")
            }
            validateText(row, "model")
            val requestType = requiredString(row, "requestType")
            if (requestType !in AI_REQUEST_TYPES) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "AI 请求类型不受支持")
            }
            optionalInt(row, "tokensIn")?.let { tokens ->
                if (tokens < 0) throw BackupException("BACKUP_VALIDATION_FAILED", "AI 输入 Token 数不能为负")
            }
            optionalInt(row, "tokensOut")?.let { tokens ->
                if (tokens < 0) throw BackupException("BACKUP_VALIDATION_FAILED", "AI 输出 Token 数不能为负")
            }
            validateMinor(requiredLong(row, "costMinor"), allowNegative = false)
            requiredBoolean(row, "success")
            validateTimestamp(row, "createdAt")
            val month = requiredString(row, "month")
            if (!MONTH_PATTERN.matches(month)) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "AI 成本记录月份格式无效")
            }
        }
    }

    private fun safeApiBaseUrl(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(normalized)
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme !in HTTP_SCHEMES || uri.host.isNullOrBlank()) return null
            if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null

            val authority = decodeUriPart(uri.rawAuthority ?: return null) ?: return null
            if (authority.contains('@')) return null

            val path = decodeUriPart(uri.rawPath ?: "") ?: return null
            if (path.contains('@') || path.contains('?') || path.contains('#') || hasCredentialPath(path)) {
                return null
            }
            normalized
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUriPart(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        null
    }

    private fun hasCredentialPath(path: String): Boolean {
        return path.split('/').any { segment ->
            CREDENTIAL_PATH_PATTERN.matches(segment.trim())
        }
    }

    private fun readSnapshot(): Snapshot {
        val data = JSONObject()
        val bills = readRows("bills", "occurred_at ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("amountMinor", cursor.long("amount_minor"))
                put("type", cursor.string("type"))
                put("occurredAt", cursor.long("occurred_at"))
                putNullable("description", cursor.string("description"))
                putNullable("categoryId", cursor.string("category_id"))
                putNullable("accountId", cursor.string("account_id"))
                putNullable("targetAccountId", cursor.string("target_account_id"))
                putNullable("platform", cursor.string("platform"))
                putNullable("merchant", cursor.string("merchant"))
                putNullable("productDescription", cursor.string("product_description"))
                put("sourceType", cursor.string("source_type"))
                putNullable("originalTransactionId", cursor.string("original_transaction_id"))
                putNullable("orderId", cursor.string("order_id"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
                put("version", cursor.int("version"))
                // 阶段五 T5 / 阶段六 T1：用户字段锁与导入记录标识必须随备份导出，
                // 否则恢复后字段锁丢失（低优先级来源可覆盖用户保护）、导入去重键丢失（可能重复导入）。
                put("fieldLocks", cursor.string("field_locks") ?: "[]")
                putNullable("importRecordId", cursor.string("import_record_id"))
            }
        }
        val accounts = readRows("accounts", "sort_order ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("subtype", cursor.string("subtype"))
                put("kind", cursor.string("kind"))
                put("initialBalanceMinor", cursor.long("initial_balance_minor"))
                put("currentBalanceMinor", cursor.long("current_balance_minor"))
                put("currency", cursor.string("currency"))
                putNullable("icon", cursor.string("icon"))
                putNullable("color", cursor.string("color"))
                put("sortOrder", cursor.int("sort_order"))
                put("isActive", cursor.bool("is_active"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val categories = readRows("categories", "sort_order ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("type", cursor.string("type"))
                putNullable("icon", cursor.string("icon"))
                putNullable("color", cursor.string("color"))
                put("isDefault", cursor.bool("is_default"))
                put("sortOrder", cursor.int("sort_order"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val budgets = readRows("budgets", "created_at ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("amountMinor", cursor.long("amount_minor"))
                put("period", cursor.string("period"))
                putNullable("categoryId", cursor.string("category_id"))
                put("alertAt", cursor.int("alert_at"))
                put("isActive", cursor.bool("is_active"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val financialGoals = readRows("financial_goals", "target_date ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("targetAmountMinor", cursor.long("target_amount_minor"))
                put("currentAmountMinor", cursor.long("current_amount_minor"))
                putNullableLong("targetDate", cursor.longOrNull("target_date"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val rules = readRows("rules", "priority DESC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("condition", cursor.string("condition"))
                put("conditionValue", cursor.string("condition_value"))
                put("action", cursor.string("action"))
                putNullable("actionValue", cursor.string("action_value"))
                put("priority", cursor.int("priority"))
                put("isActive", cursor.bool("is_active"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val balanceCalibrations = readRows("balance_calibrations", "created_at ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("accountId", cursor.string("account_id"))
                put("beforeBalanceMinor", cursor.long("before_balance_minor"))
                put("afterBalanceMinor", cursor.long("after_balance_minor"))
                put("diffMinor", cursor.long("diff_minor"))
                putNullable("note", cursor.string("note"))
                put("createdAt", cursor.long("created_at"))
            }
        }
        val auditEntries = readRows("audit_entries", "created_at ASC, id ASC") { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("entityType", cursor.string("entity_type"))
                put("entityId", cursor.string("entity_id"))
                put("action", cursor.string("action"))
                putNullable("fieldName", cursor.string("field_name"))
                putNullable("oldValue", cursor.string("old_value"))
                putNullable("newValue", cursor.string("new_value"))
                put("operator", cursor.string("operator"))
                put("createdAt", cursor.long("created_at"))
            }
        }
        val appSettings = readRows("app_settings", "key ASC") { cursor ->
            JSONObject().apply {
                put("key", cursor.string("key"))
                put("value", cursor.string("value"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }.filterObjects { requiredString(it, "key") in SAFE_SETTING_KEYS }
        var aiProviderApiBaseUrlSkippedCount = 0
        val aiProviderConfigs = readRowsWithColumns(
            table = "ai_provider_configs",
            columns = "id, name, provider, model, api_base_url, is_default, supports_vision, created_at, updated_at",
            orderBy = "is_default DESC, id ASC",
        ) { cursor ->
            val rawApiBaseUrl = cursor.string("api_base_url")
            val sanitizedApiBaseUrl = safeApiBaseUrl(rawApiBaseUrl)
            if (!rawApiBaseUrl.isNullOrBlank() && sanitizedApiBaseUrl == null) {
                aiProviderApiBaseUrlSkippedCount++
            }
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("name", cursor.string("name"))
                put("provider", cursor.string("provider"))
                put("model", cursor.string("model"))
                when {
                    rawApiBaseUrl.isNullOrBlank() -> put("apiBaseUrl", JSONObject.NULL)
                    sanitizedApiBaseUrl != null -> put("apiBaseUrl", sanitizedApiBaseUrl)
                }
                put("isDefault", cursor.bool("is_default"))
                put("supportsVision", cursor.bool("supports_vision"))
                put("createdAt", cursor.long("created_at"))
                put("updatedAt", cursor.long("updated_at"))
            }
        }
        val aiCostRecords = readRowsWithColumns(
            table = "ai_cost_records",
            columns = "id, provider, model, request_type, tokens_in, tokens_out, cost_minor, success, created_at, month",
            orderBy = "created_at ASC, id ASC",
        ) { cursor ->
            JSONObject().apply {
                put("id", cursor.string("id"))
                put("provider", cursor.string("provider"))
                put("model", cursor.string("model"))
                put("requestType", cursor.string("request_type"))
                putNullableInt("tokensIn", cursor.intOrNull("tokens_in"))
                putNullableInt("tokensOut", cursor.intOrNull("tokens_out"))
                put("costMinor", cursor.long("cost_minor"))
                put("success", cursor.bool("success"))
                put("createdAt", cursor.long("created_at"))
                put("month", cursor.string("month"))
            }
        }

        data.put("bills", bills)
        data.put("accounts", accounts)
        data.put("categories", categories)
        data.put("budgets", budgets)
        data.put("financialGoals", financialGoals)
        data.put("rules", rules)
        data.put("balanceCalibrations", balanceCalibrations)
        data.put("auditEntries", auditEntries)
        data.put("appSettings", appSettings)
        data.put("aiProviderConfigs", aiProviderConfigs)
        data.put("aiCostRecords", aiCostRecords)
        return Snapshot(
            data = data,
            counts = RecordCounts(
                billCount = bills.length(),
                accountCount = accounts.length(),
                categoryCount = categories.length(),
                budgetCount = budgets.length(),
                financialGoalCount = financialGoals.length(),
                ruleCount = rules.length(),
                balanceCalibrationCount = balanceCalibrations.length(),
                auditLogCount = auditEntries.length(),
                appSettingCount = appSettings.length(),
                aiProviderConfigCount = aiProviderConfigs.length(),
                aiCostRecordCount = aiCostRecords.length(),
            ),
            aiProviderApiBaseUrlSkippedCount = aiProviderApiBaseUrlSkippedCount,
        )
    }

    private fun readCurrentCounts(): RecordCounts {
        val db = database.openHelper.readableDatabase
        return RecordCounts(
            billCount = countRows(db, "bills"),
            accountCount = countRows(db, "accounts"),
            categoryCount = countRows(db, "categories"),
            budgetCount = countRows(db, "budgets"),
            financialGoalCount = countRows(db, "financial_goals"),
            ruleCount = countRows(db, "rules"),
            balanceCalibrationCount = countRows(db, "balance_calibrations"),
            auditLogCount = countRows(db, "audit_entries"),
            appSettingCount = countSafeSettings(db),
            aiProviderConfigCount = countRows(db, "ai_provider_configs"),
            aiCostRecordCount = countRows(db, "ai_cost_records"),
        )
    }

    private fun countDuplicates(db: SupportSQLiteDatabase, parsed: ParsedBackup): Int {
        val mappings = listOf(
            Triple("bills", "id", "bills"),
            Triple("accounts", "id", "accounts"),
            Triple("categories", "id", "categories"),
            Triple("budgets", "id", "budgets"),
            Triple("financial_goals", "id", "financialGoals"),
            Triple("rules", "id", "rules"),
            Triple("balance_calibrations", "id", "balanceCalibrations"),
            Triple("audit_entries", "id", "auditEntries"),
            Triple("app_settings", "key", "appSettings"),
        )
        var duplicateCount = 0
        mappings.forEach { (table, column, jsonKey) ->
            val existingIds = readIds(db, table, column)
            val rows = parsed.data.getJSONArray(jsonKey)
            forEachObject(rows) { row ->
                val id = requiredString(row, if (column == "key") "key" else "id")
                if (id in existingIds) duplicateCount++
            }
        }
        if (parsed.aiProviderConfigsIncluded) {
            val existingIds = readIds(db, "ai_provider_configs", "id")
            forEachObject(parsed.data.getJSONArray("aiProviderConfigs")) { row ->
                if (requiredString(row, "id") in existingIds) duplicateCount++
            }
        }
        if (parsed.aiCostRecordsIncluded) {
            val existingIds = readIds(db, "ai_cost_records", "id")
            forEachObject(parsed.data.getJSONArray("aiCostRecords")) { row ->
                if (requiredString(row, "id") in existingIds) duplicateCount++
            }
        }
        return duplicateCount
    }

    private fun deleteRestorableData(db: SupportSQLiteDatabase) {
        // 先删除引用子表，再删除账户和分类，保留未纳入 MVP 的会话与待确认数据。
        db.execSQL("DELETE FROM bills")
        db.execSQL("DELETE FROM balance_calibrations")
        db.execSQL("DELETE FROM budgets")
        db.execSQL("DELETE FROM audit_entries")
        db.execSQL("DELETE FROM financial_goals")
        db.execSQL("DELETE FROM rules")
        // 只替换备份允许恢复的设置，保留未纳入 MVP 的本地设置。
        SAFE_SETTING_KEYS.forEach { key ->
            db.delete("app_settings", "key = ?", arrayOf(key))
        }
        db.execSQL("DELETE FROM categories")
        db.execSQL("DELETE FROM accounts")
    }

    private fun insertRestorableData(db: SupportSQLiteDatabase, parsed: ParsedBackup): Int {
        val arrays = requiredArrays(
            data = parsed.data,
            aiProviderConfigCount = parsed.counts.aiProviderConfigCount,
            aiCostRecordCount = parsed.counts.aiCostRecordCount,
        )
        insertAccounts(db, arrays.accounts)
        insertCategories(db, arrays.categories)
        insertBudgets(db, arrays.budgets)
        insertFinancialGoals(db, arrays.financialGoals)
        insertRules(db, arrays.rules)
        insertBills(db, arrays.bills)
        insertBalanceCalibrations(db, arrays.balanceCalibrations)
        insertAuditEntries(db, arrays.auditEntries)
        insertAppSettings(db, arrays.appSettings)
        val retainedProviderCount = if (parsed.aiProviderConfigsIncluded) {
            syncAiProviderConfigs(db, arrays.aiProviderConfigs)
        } else {
            0
        }
        if (parsed.aiCostRecordsIncluded) syncAiCostRecords(db, arrays.aiCostRecords)
        return retainedProviderCount
    }

    private fun insertAccounts(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "accounts", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("name", requiredString(row, "name"))
                put("subtype", requiredString(row, "subtype"))
                put("kind", requiredString(row, "kind"))
                put("initial_balance_minor", requiredLong(row, "initialBalanceMinor"))
                put("current_balance_minor", requiredLong(row, "currentBalanceMinor"))
                put("currency", requiredString(row, "currency"))
                putNullableString("icon", optionalString(row, "icon"))
                putNullableString("color", optionalString(row, "color"))
                put("sort_order", requiredInt(row, "sortOrder"))
                put("is_active", requiredBooleanInt(row, "isActive"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun insertCategories(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "categories", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("name", requiredString(row, "name"))
                put("type", requiredString(row, "type"))
                putNullableString("icon", optionalString(row, "icon"))
                putNullableString("color", optionalString(row, "color"))
                put("is_default", requiredBooleanInt(row, "isDefault"))
                put("sort_order", requiredInt(row, "sortOrder"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun insertBudgets(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "budgets", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("name", requiredString(row, "name"))
                put("amount_minor", requiredLong(row, "amountMinor"))
                put("period", requiredString(row, "period"))
                putNullableString("category_id", optionalString(row, "categoryId"))
                put("alert_at", requiredInt(row, "alertAt"))
                put("is_active", requiredBooleanInt(row, "isActive"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun insertFinancialGoals(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "financial_goals", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("name", requiredString(row, "name"))
                put("target_amount_minor", requiredLong(row, "targetAmountMinor"))
                put("current_amount_minor", requiredLong(row, "currentAmountMinor"))
                putNullableLong("target_date", optionalLong(row, "targetDate"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun insertRules(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "rules", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("name", requiredString(row, "name"))
                put("condition", requiredString(row, "condition"))
                put("condition_value", requiredString(row, "conditionValue"))
                put("action", requiredString(row, "action"))
                putNullableString("action_value", optionalString(row, "actionValue"))
                put("priority", requiredInt(row, "priority"))
                put("is_active", requiredBooleanInt(row, "isActive"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun insertBills(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "bills", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("amount_minor", requiredLong(row, "amountMinor"))
                put("type", requiredString(row, "type"))
                put("occurred_at", requiredLong(row, "occurredAt"))
                putNullableString("description", optionalString(row, "description"))
                putNullableString("category_id", optionalString(row, "categoryId"))
                putNullableString("account_id", optionalString(row, "accountId"))
                putNullableString("target_account_id", optionalString(row, "targetAccountId"))
                putNullableString("platform", optionalString(row, "platform"))
                putNullableString("merchant", optionalString(row, "merchant"))
                putNullableString("product_description", optionalString(row, "productDescription"))
                put("source_type", requiredString(row, "sourceType"))
                putNullableString("original_transaction_id", optionalString(row, "originalTransactionId"))
                putNullableString("order_id", optionalString(row, "orderId"))
                put("created_at", requiredLong(row, "createdAt"))
                put("updated_at", requiredLong(row, "updatedAt"))
                put("version", requiredInt(row, "version"))
                // 阶段五 T5 / 阶段六 T1：恢复用户字段锁与导入记录标识；
                // 老备份缺失键时回退默认值（field_locks="[]"，import_record_id=null），保持向后兼容。
                put("field_locks", optionalString(row, "fieldLocks") ?: "[]")
                putNullableString("import_record_id", optionalString(row, "importRecordId"))
            })
        }
    }

    private fun insertBalanceCalibrations(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "balance_calibrations", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("account_id", requiredString(row, "accountId"))
                put("before_balance_minor", requiredLong(row, "beforeBalanceMinor"))
                put("after_balance_minor", requiredLong(row, "afterBalanceMinor"))
                put("diff_minor", requiredLong(row, "diffMinor"))
                putNullableString("note", optionalString(row, "note"))
                put("created_at", requiredLong(row, "createdAt"))
            })
        }
    }

    private fun insertAuditEntries(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "audit_entries", ContentValues().apply {
                put("id", requiredString(row, "id"))
                put("entity_type", requiredString(row, "entityType"))
                put("entity_id", requiredString(row, "entityId"))
                put("action", requiredString(row, "action"))
                putNullableString("field_name", optionalString(row, "fieldName"))
                putNullableString("old_value", optionalString(row, "oldValue"))
                putNullableString("new_value", optionalString(row, "newValue"))
                put("operator", requiredString(row, "operator"))
                put("created_at", requiredLong(row, "createdAt"))
            })
        }
    }

    private fun insertAppSettings(db: SupportSQLiteDatabase, rows: JSONArray) {
        forEachObject(rows) { row ->
            insertRow(db, "app_settings", ContentValues().apply {
                put("key", requiredString(row, "key"))
                put("value", requiredString(row, "value"))
                put("updated_at", requiredLong(row, "updatedAt"))
            })
        }
    }

    private fun syncAiProviderConfigs(db: SupportSQLiteDatabase, rows: JSONArray): Int {
        val existingIds = readIds(db, "ai_provider_configs", "id")
        val backupIds = HashSet<String>()
        forEachObject(rows) { row ->
            val id = requiredString(row, "id")
            backupIds += id
            val values = aiProviderValues(row)
            if (id in existingIds) {
                if (db.update("ai_provider_configs", SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) != 1) {
                    throw IllegalStateException("更新 AI Provider 配置失败")
                }
            } else {
                // 故意不提供 api_key_encrypted，新增配置的凭据保持为空。
                insertRow(db, "ai_provider_configs", values.withId(id))
            }
        }

        var retainedProviderCount = 0
        existingIds.filterNot { it in backupIds }.forEach { id ->
            if (hasChatSessionReference(db, id)) {
                retainedProviderCount++
            } else {
                db.delete("ai_provider_configs", "id = ?", arrayOf(id))
            }
        }
        return retainedProviderCount
    }

    private fun syncAiCostRecords(db: SupportSQLiteDatabase, rows: JSONArray) {
        val existingIds = readIds(db, "ai_cost_records", "id")
        val backupIds = HashSet<String>()
        forEachObject(rows) { row ->
            val id = requiredString(row, "id")
            backupIds += id
            val values = aiCostValues(row)
            if (id in existingIds) {
                if (db.update("ai_cost_records", SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) != 1) {
                    throw IllegalStateException("更新 AI 成本记录失败")
                }
            } else {
                insertRow(db, "ai_cost_records", values.withId(id))
            }
        }
        existingIds.filterNot { it in backupIds }.forEach { id ->
            db.delete("ai_cost_records", "id = ?", arrayOf(id))
        }
    }

    private fun aiProviderValues(row: JSONObject): ContentValues = ContentValues().apply {
        put("name", requiredString(row, "name"))
        put("provider", requiredString(row, "provider"))
        put("model", requiredString(row, "model"))
        if (row.has("apiBaseUrl")) {
            // 缺失地址字段表示它被安全过滤，更新时保留当前值；显式 NULL 才清空地址。
            optionalString(row, "apiBaseUrl")?.let { put("api_base_url", it) } ?: putNull("api_base_url")
        }
        put("is_default", requiredBooleanInt(row, "isDefault"))
        put("supports_vision", requiredBooleanInt(row, "supportsVision"))
        put("created_at", requiredLong(row, "createdAt"))
        put("updated_at", requiredLong(row, "updatedAt"))
    }

    private fun aiCostValues(row: JSONObject): ContentValues = ContentValues().apply {
        put("provider", requiredString(row, "provider"))
        put("model", requiredString(row, "model"))
        put("request_type", requiredString(row, "requestType"))
        putNullableInt("tokens_in", optionalInt(row, "tokensIn"))
        putNullableInt("tokens_out", optionalInt(row, "tokensOut"))
        put("cost_minor", requiredLong(row, "costMinor"))
        put("success", requiredBooleanInt(row, "success"))
        put("created_at", requiredLong(row, "createdAt"))
        put("month", requiredString(row, "month"))
    }

    private fun ContentValues.withId(id: String): ContentValues = apply {
        put("id", id)
    }

    private fun hasChatSessionReference(db: SupportSQLiteDatabase, providerId: String): Boolean {
        db.query(
            "SELECT 1 FROM chat_sessions WHERE ai_provider_id = ? LIMIT 1",
            arrayOf(providerId),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun insertRow(db: SupportSQLiteDatabase, table: String, values: ContentValues) {
        if (db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) == -1L) {
            throw IllegalStateException("写入备份表失败")
        }
    }

    private fun readRows(
        table: String,
        orderBy: String,
        mapper: (Cursor) -> JSONObject,
    ): JSONArray = readRowsWithColumns(table, "*", orderBy, mapper)

    private fun readRowsWithColumns(
        table: String,
        columns: String,
        orderBy: String,
        mapper: (Cursor) -> JSONObject,
    ): JSONArray {
        val result = JSONArray()
        database.openHelper.readableDatabase.query("SELECT $columns FROM $table ORDER BY $orderBy").use { cursor ->
            while (cursor.moveToNext()) {
                result.put(mapper(cursor))
            }
        }
        return result
    }

    private fun countRows(table: String): Int = countRows(database.openHelper.readableDatabase, table)

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    private fun countSafeSettings(db: SupportSQLiteDatabase): Int {
        var count = 0
        db.query("SELECT key FROM app_settings").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string("key") in SAFE_SETTING_KEYS) count++
            }
        }
        return count
    }

    private fun readIds(db: SupportSQLiteDatabase, table: String, column: String): Set<String> {
        val result = HashSet<String>()
        db.query("SELECT $column FROM $table").use { cursor ->
            while (cursor.moveToNext()) {
                cursor.string(column)?.let(result::add)
            }
        }
        return result
    }

    private fun uniqueIds(rows: JSONArray, key: String): Set<String> {
        val ids = HashSet<String>()
        forEachObject(rows) { row ->
            val value = requiredString(row, key)
            validateTextValue(value, key)
            if (!ids.add(value)) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "备份包含重复记录标识")
            }
        }
        return ids
    }

    private fun forEachObject(rows: JSONArray, block: (JSONObject) -> Unit) {
        for (index in 0 until rows.length()) {
            val value = rows.opt(index)
            if (value !is JSONObject) {
                throw BackupException("BACKUP_VALIDATION_FAILED", "备份记录不是对象")
            }
            block(value)
        }
    }

    private fun JSONArray.filterObjects(predicate: (JSONObject) -> Boolean): JSONArray {
        val result = JSONArray()
        forEachObject(this) { row -> if (predicate(row)) result.put(row) }
        return result
    }

    private fun validateText(row: JSONObject, key: String) {
        validateTextValue(requiredString(row, key), key)
    }

    private fun validateOptionalText(row: JSONObject, key: String) {
        optionalString(row, key)?.let { validateTextValue(it, key) }
    }

    private fun validateTextValue(value: String, field: String) {
        if (value.isBlank() || value.length > MAX_TEXT_LENGTH) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $field 不合法")
        }
    }

    private fun validateTimestamp(row: JSONObject, key: String) {
        if (requiredLong(row, key) < 0L) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "时间字段 $key 不合法")
        }
    }

    private fun validateMinor(value: Long, allowNegative: Boolean) {
        if ((!allowNegative && value < 0L) || value > MAX_SAFE_MINOR || value < -MAX_SAFE_MINOR) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "金额字段不合法")
        }
    }

    private fun requiredObject(parent: JSONObject, key: String): JSONObject {
        val value = parent.opt(key)
        if (value !is JSONObject) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "缺少对象字段 $key")
        }
        return value
    }

    private fun requiredArray(parent: JSONObject, key: String): JSONArray {
        val value = parent.opt(key)
        if (value !is JSONArray) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "缺少数组字段 $key")
        }
        return value
    }

    private fun requiredString(parent: JSONObject, key: String): String {
        val value = parent.opt(key)
        if (value !is String || value.isBlank()) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "缺少字段 $key")
        }
        return value
    }

    private fun optionalString(parent: JSONObject, key: String): String? {
        if (!parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        if (value !is String) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 类型不合法")
        }
        return value
    }

    private fun requiredLong(parent: JSONObject, key: String): Long {
        val value = parent.opt(key)
        if (value !is Number) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 必须是整数")
        }
        return value.toString().toLongOrNull()
            ?: throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 必须是整数")
    }

    private fun optionalLong(parent: JSONObject, key: String): Long? {
        if (!parent.has(key) || parent.isNull(key)) return null
        return requiredLong(parent, key)
    }

    private fun requiredInt(parent: JSONObject, key: String): Int {
        val value = requiredLong(parent, key)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 超出范围")
        }
        return value.toInt()
    }

    private fun optionalInt(parent: JSONObject, key: String): Int? {
        val value = optionalLong(parent, key) ?: return null
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 超出范围")
        }
        return value.toInt()
    }

    private fun requiredNonNegativeInt(parent: JSONObject, key: String): Int {
        val value = requiredInt(parent, key)
        if (value < 0) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 不能为负")
        }
        return value
    }

    private fun requiredBoolean(parent: JSONObject, key: String): Boolean {
        val value = parent.opt(key)
        if (value !is Boolean) {
            throw BackupException("BACKUP_VALIDATION_FAILED", "字段 $key 必须是布尔值")
        }
        return value
    }

    private fun requiredBooleanInt(parent: JSONObject, key: String): Int = if (requiredBoolean(parent, key)) 1 else 0

    private fun rejectSensitiveKeys(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.forEach { key ->
                    if (isSensitiveKey(key)) {
                        throw BackupException("BACKUP_VALIDATION_FAILED", "备份包含禁止导入的敏感字段")
                    }
                    rejectSensitiveKeys(value.opt(key))
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) rejectSensitiveKeys(value.opt(index))
            }
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US).replace("_", "").replace("-", "")
        // AI 成本表中的 tokensIn/tokensOut 是数量统计，不是 Token 凭据。
        val isTokenCredential = normalized.contains("token") &&
            normalized != "tokensin" &&
            normalized != "tokensout"
        return normalized.contains("apikey") ||
            normalized.contains("keystore") ||
            normalized.contains("rawnotification") ||
            normalized.contains("screenshot") ||
            normalized.contains("imagedata") ||
            normalized.contains("password") ||
            normalized.contains("secret") ||
            isTokenCredential ||
            normalized == "database"
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) keys += iterator.next()
            keys.sort()
            keys.joinToString(prefix = "{", postfix = "}") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
            }
        }
        is JSONArray -> {
            (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                canonicalJson(value.opt(index))
            }
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableLong(key: String, value: Long?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableInt(key: String, value: Int?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun ContentValues.putNullableString(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    // 合并后 aiCostValues 在 ContentValues 上调用 putNullableInt；原先仅有 JSONObject 扩展，
    // 这里补充 ContentValues 版本以保证接收者类型匹配。
    private fun ContentValues.putNullableInt(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.bool(column: String): Boolean = getInt(getColumnIndexOrThrow(column)) != 0

    private companion object {
        const val FORMAT = "jotbill"
        const val FORMAT_VERSION = 1
        const val SOURCE_LOCAL_DATABASE = "local_database"
        const val SOURCE_BACKUP_FILE = "backup_file"
        const val STRATEGY_OVERWRITE = "overwrite"
        const val DUPLICATE_REPLACE = "replace"
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        const val MAX_TEXT_LENGTH = 100_000
        const val MAX_SAFE_MINOR = 9_007_199_254_740_991L
        val CHECKSUM_PATTERN = Regex("[0-9a-fA-F]{64}")
        val MONTH_PATTERN = Regex("\\d{4}-(0[1-9]|1[0-2])")
        val HTTP_SCHEMES = setOf("http", "https")
        val AI_PROVIDERS = setOf("qwen", "glm", "siliconflow", "custom")
        val AI_REQUEST_TYPES = setOf("vision", "chat", "asr")
        val CREDENTIAL_PATH_PATTERN = Regex(
            "^(api[-_]?key|access[-_]?token|token|password|secret|credentials?|auth|key)([-_=].*)?$",
            RegexOption.IGNORE_CASE,
        )
        val ACCOUNT_SUBTYPES = setOf("cash", "e_wallet", "bank_debit", "credit_card", "consumer_loan", "other")
        val CATEGORY_TYPES = setOf("income", "expense")
        val BILL_TYPES = setOf("income", "expense", "transfer", "refund", "repayment", "fee", "adjustment")
        val SOURCE_TYPES = setOf("ai_vision", "notification", "accessibility", "sms", "import", "manual", "ai_chat")
        val BUDGET_PERIODS = setOf("monthly", "yearly")
        val SAFE_SETTING_KEYS = setOf("initialized", "ai_monthly_budget_minor")
    }
}
