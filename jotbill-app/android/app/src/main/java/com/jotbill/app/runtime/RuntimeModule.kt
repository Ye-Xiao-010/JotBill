package com.jotbill.app.runtime

import android.content.ComponentName
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JOT_BILL_DATABASE_VERSION
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.notification.PaymentNotificationService
import com.jotbill.app.recognition.CheckoutAccessibilityService
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
                    put("checkedAt", Instant.now().toString())
                    put("aiProviderConfigured", database.aiProviderConfigDao().getDefault() != null)
                    put("accessibilityEnabled", isAccessibilityServiceEnabled())
                    put("accessibilityServiceRunning", isAccessibilityServiceEnabled())
                    put("notificationEnabled", isNotificationListenerEnabled())
                    put("overlayPermissionGranted", isOverlayPermissionGranted())
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

    override fun invalidate() {
        scope.coroutineContext.cancel()
        super.invalidate()
    }

    private companion object {
        const val PREFERENCES_NAME = "jotbill_runtime"
        const val LAST_LAUNCH_KEY = "last_launch_at"
    }
}
