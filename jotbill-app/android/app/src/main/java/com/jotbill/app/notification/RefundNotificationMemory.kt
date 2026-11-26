package com.jotbill.app.notification

import android.content.Context

/**
 * 退款通知去重记忆（2026-11-16）。
 *
 * 同一笔退款可能被 App 连发多条通知（美团实测：「退款通知」→ 2 分 22 秒后
 * 「退款状态提醒」，金额相同），各条 postTime 不同，单靠证据键与 RefundLinker
 * 的 2 分钟窗口挡不住。按「包名 + 金额」记忆 30 分钟，重复通知不再建卡。
 *
 * 取舍：同一 App 30 分钟内两笔**同金额**退款只出一张卡（低频，日志留痕）；
 * 不同渠道（银行短信等）仍各自走 RefundLinker 的跨来源判重。
 */
internal object RefundNotificationMemory {

    private const val PREFS_NAME = "refund_notification_memory"

    private const val TTL_MILLIS = 30L * 60 * 1000

    private const val PRUNE_THRESHOLD = 100

    private val lock = Any()

    fun isSeen(context: Context, key: String): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(key, 0L)
        last > 0L && System.currentTimeMillis() - last < TTL_MILLIS
    }

    fun mark(context: Context, key: String) = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(key, now).apply()
        if (prefs.all.size > PRUNE_THRESHOLD) {
            val stale = prefs.all.filterValues { value ->
                value is Long && now - value >= TTL_MILLIS
            }.keys
            if (stale.isNotEmpty()) {
                val editor = prefs.edit()
                stale.forEach { editor.remove(it) }
                editor.apply()
            }
        }
    }
}
