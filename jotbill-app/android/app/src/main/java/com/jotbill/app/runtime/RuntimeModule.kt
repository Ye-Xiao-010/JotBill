package com.jotbill.app.runtime

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.DatabaseMigrationGuard
import com.jotbill.app.data.JOT_BILL_DATABASE_VERSION
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.notification.PaymentNotificationService
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.utils.DiagLogExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** 提供阶段二所需的本地运行状态摘要。 */
class RuntimeModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database: JotBillDatabase by lazy { JotBillDatabaseProvider.get(reactContext) }
    private val preferences = reactContext.getSharedPreferences(PREFERENCES_NAME, 0)

    init {
        preferences.edit()
            .putString(LAST_LAUNCH_KEY, Instant.now().toString())
            .apply()
    }

    override fun getName(): String = "Runtime"

    /**
     * 打开系统"通知访问"设置页（通知监听权限所在页面）。
     *
     * 审查修复：此前通知权限引导复用 openAccessibilitySettings，跳转到的是
     * 无障碍设置页而非通知监听权限页，用户按引导无法完成授权。
     * 该方法是通知权限功能所需的最小契约变更，已同步登记到
     * docs/types/Native.d.ts；不扩展 RuntimeHealth 字段。
     */
    @ReactMethod
    fun openNotificationListenerSettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("OPEN_SETTINGS_ERROR", error.message, error)
        }
    }

    /**
     * 打包诊断日志并拉起系统分享面板（应用内一键导出，2026-10-27）。
     *
     * 背景：`JotBillLog` 落盘目录 Android 11+ 文件管理器进不去，远程用户
     * （无法 adb）遇到"弹窗不出现"类问题时无证据可交。打包内容与隐私边界见
     * [DiagLogExporter] KDoc；无日志时 reject `DIAG_EXPORT_EMPTY`，
     * 无前台 Activity（分享面板必须由 Activity 发起）时 reject
     * `DIAG_EXPORT_NO_ACTIVITY`。
     *
     * @return zip 文件名（RN 侧 toast 用）。
     */
    @ReactMethod
    fun exportDiagLog(promise: Promise) {
        scope.launch {
            try {
                val zip = DiagLogExporter.buildExportZip(reactContext)
                    ?: run {
                        withContext(Dispatchers.Main) {
                            promise.reject("DIAG_EXPORT_EMPTY", "没有可导出的诊断日志")
                        }
                        return@launch
                    }
                withContext(Dispatchers.Main) {
                    val activity = reactContext.currentActivity
                    if (activity == null) {
                        promise.reject("DIAG_EXPORT_NO_ACTIVITY", "分享面板需要前台界面")
                        return@withContext
                    }
                    try {
                        DiagLogExporter.shareZip(reactContext, zip)
                        promise.resolve(zip.name)
                    } catch (error: Exception) {
                        promise.reject("DIAG_EXPORT_SHARE_ERROR", error.message, error)
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("DIAG_EXPORT_ERROR", error.message, error)
                }
            }
        }
    }

    @ReactMethod
    fun getRuntimeHealth(promise: Promise) {
        scope.launch {
            try {
                val result = JSONObject().apply {
                    put("databaseVersion", JOT_BILL_DATABASE_VERSION)
                    put("billCount", database.billDao().count())
                    put("categoryCount", database.categoryDao().getAll().size)
                    put("accountCount", database.accountDao().getAll().size)
                    put("lastLaunchAt", preferences.getString(LAST_LAUNCH_KEY, Instant.now().toString()))
                    val lastMigrationRecoveredAt = DatabaseMigrationGuard(reactContext).lastRecoveredAt()
                    put(
                        "lastMigrationRecoveredAt",
                        if (lastMigrationRecoveredAt > 0L) Instant.ofEpochMilli(lastMigrationRecoveredAt).toString() else JSONObject.NULL
                    )
                    put("checkedAt", Instant.now().toString())
                    put("aiProviderConfigured", database.aiProviderConfigDao().getDefault() != null)
                    put("accessibilityEnabled", isAccessibilityServiceEnabled())
                    put("notificationEnabled", isNotificationListenerEnabled())
                    put("overlayPermissionGranted", isOverlayPermissionGranted())
                    put("batteryOptimizationIgnored", isIgnoringBatteryOptimizations())
                    put("orphanImportBatches", database.importBatchDao().getRunning().size)
                }.toString()
                withContext(Dispatchers.Main) { promise.resolve(result) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("RUNTIME_STATUS_ERROR", error.message, error)
                }
            }
        }
    }

    /** 检查结算页无障碍服务是否已在系统设置中启用。 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        // 优先看服务是否实际存活：部分 ROM 系统设置读取与真实状态不一致会误报未开启。
        if (CheckoutAccessibilityService.isServiceRunning()) {
            return true
        }
        val enabledServices = Settings.Secure.getString(
            reactContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = ComponentName(
            reactContext.packageName,
            CheckoutAccessibilityService::class.java.name
        ).flattenToString()
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    /** 检查支付通知监听服务是否已在系统设置中启用。 */
    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            reactContext.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val componentName = ComponentName(
            reactContext.packageName,
            PaymentNotificationService::class.java.name
        ).flattenToString()
        return enabledListeners.split(':').any { it.equals(componentName, ignoreCase = true) }
    }

    /** 检查悬浮窗权限是否已授予。 */
    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(reactContext)
        } else {
            true
        }
    }

    /** 阶段七 T6：电池优化白名单状态（未加入时提示用户前往系统设置）。 */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = reactContext.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(reactContext.packageName)
    }

    override fun invalidate() {
        scope.coroutineContext.cancel()
        super.invalidate()
    }

    private companion object {
        const val PREFERENCES_NAME = "jotbill_runtime"
        const val LAST_LAUNCH_KEY = "last_launch_at"
    }
}
