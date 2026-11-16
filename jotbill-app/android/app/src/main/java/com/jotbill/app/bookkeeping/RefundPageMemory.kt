package com.jotbill.app.bookkeeping

import android.content.Context

/**
 * 页面退款证据的访问记忆（2026-11-16）。
 *
 * 拼多多退款详情页没有订单号/唯一 id，同一页面会被反复扫描（窗口事件/重扫），
 * 用户也可能多次回访历史退款页。用「包名 + 规则 + 金额 + 退款渠道」作为页面身份，
 * 7 天内只处理一次，避免同一笔退款反复生成待确认卡。
 *
 * 取舍：同一渠道、同一金额的第二笔真实退款在 7 天内不会再由页面链路建卡；
 * 若该笔有银行短信/支付平台退款通知，仍会被对应链路记录（日志会留痕）。
 */
internal object RefundPageMemory {

    private const val PREFS_NAME = "refund_page_memory"

    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    private const val PRUNE_THRESHOLD = 200

    fun isSeen(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(key, 0L)
        return last > 0L && System.currentTimeMillis() - last < TTL_MILLIS
    }

    fun mark(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putLong(key, now)
        editor.apply()
        if (prefs.all.size > PRUNE_THRESHOLD) {
            val stale = prefs.all.filterValues { value ->
                value is Long && now - value >= TTL_MILLIS
            }.keys
            if (stale.isNotEmpty()) {
                val pruneEditor = prefs.edit()
                stale.forEach { pruneEditor.remove(it) }
                pruneEditor.apply()
            }
        }
    }
}
