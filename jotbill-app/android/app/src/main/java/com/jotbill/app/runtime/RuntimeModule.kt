package com.jotbill.app.runtime

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
                    put("databaseVersion", DATABASE_VERSION)
                    put("billCount", database.billDao().count())
                    put("categoryCount", database.categoryDao().getAll().size)
                    put("accountCount", database.accountDao().getAll().size)
                    put("lastLaunchAt", preferences.getString(LAST_LAUNCH_KEY, Instant.now().toString()))
                    put("checkedAt", Instant.now().toString())
                    put("aiProviderConfigured", database.aiProviderConfigDao().getDefault() != null)
                    put("accessibilityEnabled", false)
                    put("notificationEnabled", false)
                    put("overlayPermissionGranted", false)
                }.toString()
                withContext(Dispatchers.Main) { promise.resolve(result) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("RUNTIME_STATUS_ERROR", error.message, error)
                }
            }
        }
    }

    override fun invalidate() {
        scope.coroutineContext.cancel()
        super.invalidate()
    }

    private companion object {
        const val DATABASE_VERSION = 1
        const val PREFERENCES_NAME = "jotbill_runtime"
        const val LAST_LAUNCH_KEY = "last_launch_at"
    }
}
