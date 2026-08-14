package com.jotbill.app.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/** 通过 Android SAF 提供 CSV 导出和本地备份恢复，并保留显式清除入口。 */
class BackupModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database: JotBillDatabase by lazy { JotBillDatabaseProvider.get(reactContext) }
    private val backupManager: BackupManager by lazy { BackupManager(database) }
    private val pendingLock = Any()
    private var pendingOperation: PendingFileOperation? = null

    private enum class PendingKind {
        EXPORT_CSV,
        CREATE_BACKUP,
        RESTORE_PREVIEW,
    }

    private data class PendingFileOperation(
        val kind: PendingKind,
        val promise: Promise,
        val csv: String? = null,
        val backup: BackupManager.BackupFile? = null,
    )

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String = "Backup"

    @ReactMethod
    fun createBackup(optionsJson: String, promise: Promise) {
        if (isPending()) {
            promise.reject("BACKUP_FAILED", "已有文件操作正在等待完成")
            return
        }
        val encrypt = try {
            parseEncryptOption(optionsJson)
        } catch (error: BackupManager.BackupException) {
            promise.reject(error.code, error.message)
            return
        }

        scope.launch {
            try {
                val backup = backupManager.createBackup(encrypt)
                withContext(Dispatchers.Main) {
                    val activity = reactContext.currentActivity
                    if (activity == null) {
                        try {
                            val file = File(
                                reactContext.filesDir,
                                "backups/jotbill-${System.currentTimeMillis()}.jotbill.json",
                            )
                            file.parentFile?.mkdirs()
                            file.writeBytes(backup.bytes)
                            promise.resolve(backupManager.summaryForPath(file.absolutePath, backup).toString())
                        } catch (error: Exception) {
                            reject(promise, error, "BACKUP_IO_ERROR", "无法保存备份文件")
                        }
                    } else {
                        val operation = PendingFileOperation(
                            kind = PendingKind.CREATE_BACKUP,
                            promise = promise,
                            backup = backup,
                        )
                        if (!reservePending(operation)) {
                            promise.reject("BACKUP_FAILED", "已有文件操作正在等待完成")
                            return@withContext
                        }
                        try {
                            activity.startActivityForResult(createBackupIntent(), REQUEST_CREATE_BACKUP)
                        } catch (error: Exception) {
                            clearPending(operation)
                            reject(promise, error, "BACKUP_IO_ERROR", "无法打开备份保存位置")
                        }
                    }
                }
            } catch (error: Exception) {
                rejectFromWorker(promise, error, "BACKUP_FAILED")
            }
        }
    }

    @ReactMethod
    fun restoreBackup(filePath: String, promise: Promise) {
        if (isPending()) {
            promise.reject("RESTORE_PREVIEW_FAILED", "已有文件操作正在等待完成")
            return
        }
        if (filePath.isBlank()) {
            val activity = reactContext.currentActivity
            if (activity == null) {
                promise.reject("NOT_IMPLEMENTED", "当前没有可用的文件选择器")
                return
            }
            val operation = PendingFileOperation(
                kind = PendingKind.RESTORE_PREVIEW,
                promise = promise,
            )
            if (!reservePending(operation)) {
                promise.reject("RESTORE_PREVIEW_FAILED", "已有文件操作正在等待完成")
                return
            }
            try {
                activity.startActivityForResult(openBackupIntent(), REQUEST_OPEN_BACKUP)
            } catch (error: Exception) {
                clearPending(operation)
                reject(promise, error, "BACKUP_IO_ERROR", "无法打开备份文件选择器")
            }
            return
        }

        scope.launch {
            processRestorePreview(filePath, promise)
        }
    }

    @ReactMethod
    fun executeRestore(filePath: String, strategy: String, promise: Promise) {
        if (strategy == STRATEGY_MERGE) {
            promise.reject("NOT_IMPLEMENTED", "MVP 暂不支持合并恢复")
            return
        }
        if (strategy != STRATEGY_OVERWRITE) {
            promise.reject("INVALID_ARGUMENT", "不支持的恢复策略")
            return
        }
        if (filePath.isBlank()) {
            promise.reject("INVALID_ARGUMENT", "恢复文件路径不能为空")
            return
        }

        scope.launch {
            try {
                val parsed = backupManager.parse(readBackupBytes(filePath), filePath)
                val result = backupManager.executeRestore(parsed, strategy)
                withContext(Dispatchers.Main) {
                    promise.resolve(result.toString())
                }
            } catch (error: Exception) {
                rejectFromWorker(promise, error, "RESTORE_FAILED")
            }
        }
    }

    @ReactMethod
    fun importData(filePath: String, promise: Promise) {
        promise.reject("NOT_IMPLEMENTED", "MVP 暂不支持 CSV 导入")
    }

    @ReactMethod
    fun exportData(format: String, promise: Promise) {
        if (format != "csv") {
            promise.reject("UNSUPPORTED_FORMAT", "阶段二仅支持 CSV 导出")
            return
        }
        if (isPending()) {
            promise.reject("EXPORT_IN_PROGRESS", "已有导出或备份任务正在等待文件位置")
            return
        }

        scope.launch {
            try {
                val csv = buildCsv()
                withContext(Dispatchers.Main) {
                    val activity = reactContext.currentActivity
                    if (activity == null) {
                        try {
                            val file = File(reactContext.filesDir, "exports/jotbill-${System.currentTimeMillis()}.csv")
                            file.parentFile?.mkdirs()
                            file.writeText(csv, Charsets.UTF_8)
                            promise.resolve(JSONObject().put("filePath", file.absolutePath).toString())
                        } catch (error: Exception) {
                            reject(promise, error, "EXPORT_ERROR", "无法保存导出文件")
                        }
                    } else {
                        val operation = PendingFileOperation(
                            kind = PendingKind.EXPORT_CSV,
                            promise = promise,
                            csv = csv,
                        )
                        if (!reservePending(operation)) {
                            promise.reject("EXPORT_IN_PROGRESS", "已有导出或备份任务正在等待文件位置")
                            return@withContext
                        }
                        try {
                            activity.startActivityForResult(createCsvIntent(), REQUEST_CREATE_CSV)
                        } catch (error: Exception) {
                            clearPending(operation)
                            reject(promise, error, "EXPORT_ERROR", "无法打开导出文件位置")
                        }
                    }
                }
            } catch (error: Exception) {
                rejectFromWorker(promise, error, "EXPORT_ERROR")
            }
        }
    }

    @ReactMethod
    fun clearData(promise: Promise) {
        scope.launch {
            try {
                database.clearAllTables()
                withContext(Dispatchers.Main) { promise.resolve(null) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("CLEAR_DATA_ERROR", error.message, error)
                }
            }
        }
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        val kind = when (requestCode) {
            REQUEST_CREATE_CSV -> PendingKind.EXPORT_CSV
            REQUEST_CREATE_BACKUP -> PendingKind.CREATE_BACKUP
            REQUEST_OPEN_BACKUP -> PendingKind.RESTORE_PREVIEW
            else -> return
        }
        val operation = takePending(kind) ?: return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            operation.promise.reject(cancelCode(kind), "用户取消了文件操作")
            return
        }

        when (kind) {
            PendingKind.EXPORT_CSV -> finishCsvExport(operation, uri)
            PendingKind.CREATE_BACKUP -> finishBackupCreation(operation, uri)
            PendingKind.RESTORE_PREVIEW -> finishRestoreSelection(operation, uri, data)
        }
    }

    override fun onNewIntent(intent: Intent) = Unit

    override fun invalidate() {
        reactContext.removeActivityEventListener(this)
        takePending()?.let { operation ->
            operation.promise.reject(cancelCode(operation.kind), "文件操作已结束")
        }
        scope.coroutineContext.cancel()
        super.invalidate()
    }

    private fun finishCsvExport(operation: PendingFileOperation, uri: Uri) {
        val csv = operation.csv ?: run {
            operation.promise.reject("EXPORT_ERROR", "导出内容不存在")
            return
        }
        scope.launch {
            try {
                writeUri(uri, csv.toByteArray(Charsets.UTF_8))
                withContext(Dispatchers.Main) {
                    operation.promise.resolve(JSONObject().put("filePath", uri.toString()).toString())
                }
            } catch (error: Exception) {
                rejectFromWorker(operation.promise, error, "EXPORT_ERROR")
            }
        }
    }

    private fun finishBackupCreation(operation: PendingFileOperation, uri: Uri) {
        val backup = operation.backup ?: run {
            operation.promise.reject("BACKUP_FAILED", "备份内容不存在")
            return
        }
        scope.launch {
            try {
                writeUri(uri, backup.bytes)
                val summary = backupManager.summaryForPath(uri.toString(), backup)
                withContext(Dispatchers.Main) {
                    operation.promise.resolve(summary.toString())
                }
            } catch (error: Exception) {
                rejectFromWorker(operation.promise, error, "BACKUP_IO_ERROR")
            }
        }
    }

    private fun finishRestoreSelection(operation: PendingFileOperation, uri: Uri, data: Intent?) {
        takePersistableReadPermission(uri, data)
        scope.launch {
            processRestorePreview(uri.toString(), operation.promise)
        }
    }

    private suspend fun processRestorePreview(filePath: String, promise: Promise) {
        try {
            val parsed = backupManager.parse(readBackupBytes(filePath), filePath)
            val preview = backupManager.preview(parsed)
            withContext(Dispatchers.Main) {
                promise.resolve(preview.toString())
            }
        } catch (error: Exception) {
            rejectFromWorker(promise, error, "RESTORE_PREVIEW_FAILED")
        }
    }

    private fun parseEncryptOption(optionsJson: String): Boolean {
        val options = try {
            JSONObject(optionsJson)
        } catch (error: Exception) {
            throw BackupManager.BackupException("INVALID_ARGUMENT", "备份选项格式无效")
        }
        if (!options.has("encrypt") || options.isNull("encrypt")) return false
        val value = options.opt("encrypt")
        if (value !is Boolean) {
            throw BackupManager.BackupException("INVALID_ARGUMENT", "备份选项 encrypt 必须是布尔值")
        }
        return value
    }

    private fun readBackupBytes(filePath: String): ByteArray {
        if (filePath.isBlank()) {
            throw BackupManager.BackupException("INVALID_ARGUMENT", "备份文件路径不能为空")
        }
        try {
            val input = openInput(filePath)
                ?: throw BackupManager.BackupException("NOT_FOUND", "找不到备份文件")
            return input.use { readLimited(it) }
        } catch (error: BackupManager.BackupException) {
            throw error
        } catch (error: FileNotFoundException) {
            throw BackupManager.BackupException("NOT_FOUND", "找不到备份文件")
        } catch (error: SecurityException) {
            throw BackupManager.BackupException("PERMISSION_DENIED", "没有读取备份文件的权限")
        } catch (error: IOException) {
            throw BackupManager.BackupException("BACKUP_IO_ERROR", "读取备份文件失败")
        }
    }

    private fun openInput(filePath: String): InputStream? {
        return if (filePath.startsWith("content:", ignoreCase = true) ||
            filePath.startsWith("file:", ignoreCase = true)
        ) {
            reactContext.contentResolver.openInputStream(Uri.parse(filePath))
        } else {
            File(filePath).inputStream()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_BACKUP_BYTES) {
                throw BackupManager.BackupException("BACKUP_IO_ERROR", "备份文件超过大小限制")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun writeUri(uri: Uri, bytes: ByteArray) {
        reactContext.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
        } ?: throw BackupManager.BackupException("BACKUP_IO_ERROR", "无法打开目标文件")
    }

    private fun createCsvIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/csv"
        putExtra(Intent.EXTRA_TITLE, "jotbill-${System.currentTimeMillis()}.csv")
    }

    private fun createBackupIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, "jotbill-backup-${System.currentTimeMillis()}.jotbill.json")
    }

    private fun openBackupIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    private fun takePersistableReadPermission(uri: Uri, data: Intent?) {
        val flags = (data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (flags == 0) return
        try {
            reactContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // 部分文件选择器不提供可持久化授权，当前请求仍可在本次页面生命周期内完成。
        }
    }

    private fun isPending(): Boolean = synchronized(pendingLock) {
        pendingOperation != null
    }

    private fun reservePending(operation: PendingFileOperation): Boolean = synchronized(pendingLock) {
        if (pendingOperation != null) return@synchronized false
        pendingOperation = operation
        true
    }

    private fun clearPending(operation: PendingFileOperation) {
        synchronized(pendingLock) {
            if (pendingOperation === operation) pendingOperation = null
        }
    }

    private fun takePending(kind: PendingKind): PendingFileOperation? = synchronized(pendingLock) {
        if (pendingOperation?.kind != kind) return@synchronized null
        pendingOperation.also { pendingOperation = null }
    }

    private fun takePending(): PendingFileOperation? = synchronized(pendingLock) {
        pendingOperation.also { pendingOperation = null }
    }

    private fun cancelCode(kind: PendingKind): String = when (kind) {
        PendingKind.EXPORT_CSV -> "EXPORT_CANCELLED"
        PendingKind.CREATE_BACKUP, PendingKind.RESTORE_PREVIEW -> "BACKUP_CANCELLED"
    }

    private fun reject(promise: Promise, error: Exception, fallbackCode: String, fallbackMessage: String) {
        val backupError = error as? BackupManager.BackupException
        promise.reject(
            backupError?.code ?: fallbackCode,
            backupError?.message ?: (error.message ?: fallbackMessage),
            error,
        )
    }

    private suspend fun rejectFromWorker(promise: Promise, error: Exception, fallbackCode: String) {
        withContext(Dispatchers.Main) {
            reject(promise, error, fallbackCode, "备份操作失败")
        }
    }

    private suspend fun buildCsv(): String {
        val bills = database.billDao().getAllForExport()
        val header = "id,amountMinor,type,occurredAt,description,categoryId,accountId,platform,merchant,productDescription,sourceType,orderId,originalTransactionId"
        val rows = bills.map { bill ->
            listOf(
                bill.id,
                bill.amountMinor.toString(),
                bill.type,
                Instant.ofEpochMilli(bill.occurredAt).toString(),
                bill.description,
                bill.categoryId,
                bill.accountId,
                bill.platform,
                bill.merchant,
                bill.productDescription,
                bill.sourceType,
                bill.orderId,
                bill.originalTransactionId,
            ).joinToString(",", transform = ::escapeCsv)
        }
        return "\uFEFF" + listOf(header).plus(rows).joinToString("\n") + "\n"
    }

    private fun escapeCsv(value: String?): String {
        val text = value ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }
    }

    private companion object {
        const val REQUEST_CREATE_CSV = 7101
        const val REQUEST_CREATE_BACKUP = 7102
        const val REQUEST_OPEN_BACKUP = 7103
        const val STRATEGY_OVERWRITE = "overwrite"
        const val STRATEGY_MERGE = "merge"
        const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
        const val BUFFER_SIZE = 16 * 1024
    }
}
