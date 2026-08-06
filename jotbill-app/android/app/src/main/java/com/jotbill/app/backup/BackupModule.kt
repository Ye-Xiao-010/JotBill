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
import java.io.File
import java.time.Instant

/** 通过 Android SAF 导出账单 CSV，并提供显式清除本地数据入口。 */
class BackupModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database: JotBillDatabase by lazy { JotBillDatabaseProvider.get(reactContext) }
    private var pendingPromise: Promise? = null
    private var pendingCsv: String? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String = "Backup"

    @ReactMethod
    fun exportData(format: String, promise: Promise) {
        if (format != "csv") {
            promise.reject("UNSUPPORTED_FORMAT", "阶段二仅支持 CSV 导出")
            return
        }
        if (pendingPromise != null) {
            promise.reject("EXPORT_IN_PROGRESS", "已有导出任务正在等待文件位置")
            return
        }

        scope.launch {
            try {
                val csv = buildCsv()
                withContext(Dispatchers.Main) {
                    val activity = reactContext.currentActivity
                    if (activity == null) {
                        val file = File(reactContext.filesDir, "exports/jotbill-${System.currentTimeMillis()}.csv")
                        file.parentFile?.mkdirs()
                        file.writeText(csv, Charsets.UTF_8)
                        promise.resolve(JSONObject().put("filePath", file.absolutePath).toString())
                    } else {
                        pendingPromise = promise
                        pendingCsv = csv
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TITLE, "jotbill-${System.currentTimeMillis()}.csv")
                        }
                        activity.startActivityForResult(intent, REQUEST_CODE)
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("EXPORT_ERROR", error.message, error)
                }
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
        if (requestCode != REQUEST_CODE) return
        val promise = pendingPromise ?: return
        val csv = pendingCsv
        pendingPromise = null
        pendingCsv = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null || csv == null) {
            promise.reject("EXPORT_CANCELLED", "用户取消了文件导出")
            return
        }
        try {
            reactContext.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(csv.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法打开导出文件")
            promise.resolve(JSONObject().put("filePath", uri.toString()).toString())
        } catch (error: Exception) {
            promise.reject("EXPORT_ERROR", error.message, error)
        }
    }

    override fun onNewIntent(intent: Intent) = Unit

    override fun invalidate() {
        reactContext.removeActivityEventListener(this)
        pendingPromise?.reject("EXPORT_CANCELLED", "导出任务已结束")
        pendingPromise = null
        pendingCsv = null
        scope.coroutineContext.cancel()
        super.invalidate()
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
                bill.originalTransactionId
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
        const val REQUEST_CODE = 7101
    }
}
