package com.jotbill.app.keepalive

import android.content.Context

/** 后台守护的独立开关；与自动记账总开关是 AND 关系。 */
object KeepAliveSettings {
    private const val PREFS_NAME = "recognition_keep_alive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROMPTED = "prompted"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }
}
