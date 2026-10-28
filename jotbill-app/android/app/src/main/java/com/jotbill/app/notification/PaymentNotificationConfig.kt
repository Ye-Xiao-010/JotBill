package com.jotbill.app.notification

import android.content.Context
import org.json.JSONArray

/**
 * N-0c：通知监听的用户配置读取器。
 *
 * 对应 `PaymentNotificationModule.saveMonitoringConfig` 写入的
 * `payment_notification_config`（`monitored_apps` / `filter_keywords`）。
 * 此前这两个键**只写不读**，设置页的「监听应用 / 过滤关键词」保存后不生效，
 * 界面长得像功能而实际是写完就丢。本类把它们接到采集路径上。
 *
 * 安全边界（刻意收窄，不做扩张）：
 * - 配置只起**收窄**作用：不在 [PaymentResultNormalizer.SUPPORTED_PACKAGES] 内的包名
 *   无论如何都不会被监听 —— 解析器只认微信/支付宝，放行其他包只会产生噪声与误报。
 *   设置页「添加新的监听应用」对无解析规则的包**不生效**，调用方应提示用户。
 * - 任一配置项为**空列表**时回退到内置默认值，避免"清空配置"把匹配面放大成全量通知。
 *
 * 性能：`onNotificationPosted` 对**任意**应用的通知都会触发本类（需判断是否在监听名单），
 * 故解析结果按进程缓存，写入时由 [invalidate] 精确失效，不做每条通知一次的 JSON 解析。
 */
internal object PaymentNotificationConfig {

    private const val PREFS_NAME = "payment_notification_config"
    private const val KEY_MONITORED_APPS = "monitored_apps"
    private const val KEY_FILTER_KEYWORDS = "filter_keywords"

    /** 单个监听应用的开关。 */
    data class MonitoredApp(
        val packageName: String,
        val appName: String,
        val enabled: Boolean,
    )

    @Volatile
    private var cachedApps: List<MonitoredApp>? = null

    @Volatile
    private var cachedKeywords: List<String>? = null

    /** 配置写入后调用，使缓存立即失效。 */
    fun invalidate() {
        cachedApps = null
        cachedKeywords = null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已保存的监听应用列表；未保存或解析失败返回空列表（调用方据此回退内置白名单）。 */
    fun monitoredApps(context: Context): List<MonitoredApp> {
        cachedApps?.let { return it }
        val parsed = runCatching {
            val raw = prefs(context).getString(KEY_MONITORED_APPS, null)
                ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val packageName = obj.optString("packageName").trim()
                    if (packageName.isEmpty()) continue
                    add(
                        MonitoredApp(
                            packageName = packageName,
                            appName = obj.optString("appName").trim(),
                            enabled = obj.optBoolean("enabled", true),
                        )
                    )
                }
            }
        }.getOrElse {
            android.util.Log.w("PaymentNotificationConfig", "读取监听应用配置失败，回退内置白名单", it)
            emptyList()
        }
        cachedApps = parsed
        return parsed
    }

    /** 自定义支付关键词；空列表表示未配置或解析失败，调用方回退内置关键词表。 */
    fun filterKeywords(context: Context): List<String> {
        cachedKeywords?.let { return it }
        val parsed = runCatching {
            val raw = prefs(context).getString(KEY_FILTER_KEYWORDS, null)
                ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val keyword = array.optString(i).trim()
                    if (keyword.isNotEmpty()) add(keyword)
                }
            }
        }.getOrElse {
            android.util.Log.w("PaymentNotificationConfig", "读取过滤关键词配置失败，回退内置关键词", it)
            emptyList()
        }
        cachedKeywords = parsed
        return parsed
    }

    /**
     * 该包名是否应当被监听。
     *
     * 两级收窄：先过内置解析器白名单，再过用户配置（未配置时视为全开）。
     * 用户关掉某个应用 ⇒ 真的不再监听；用户添加无解析规则的应用 ⇒ 永远不放行。
     */
    fun isAppMonitored(context: Context, packageName: String): Boolean {
        if (packageName !in PaymentResultNormalizer.SUPPORTED_PACKAGES) return false
        val configured = monitoredApps(context)
        if (configured.isEmpty()) return true
        return configured.any { it.packageName == packageName && it.enabled }
    }

    /**
     * 该包名是否被用户添加、但因缺少解析规则而不会生效。
     * 供调用方给用户一个明确提示，而不是静默无事发生。
     */
    fun isUnsupportedButConfigured(context: Context, packageName: String): Boolean {
        if (packageName in PaymentResultNormalizer.SUPPORTED_PACKAGES) return false
        return monitoredApps(context).any { it.packageName == packageName && it.enabled }
    }
}
