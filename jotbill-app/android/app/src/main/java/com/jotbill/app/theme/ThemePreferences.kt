package com.jotbill.app.theme

import android.content.Context

/**
 * 原生侧主题偏好存储（由 JS ThemeProvider 经 ThemeModule 同步写入）。
 *
 * 供脱离 RN 运行的原生组件（如付款确认悬浮窗 PendingConfirmOverlayManager）
 * 读取当前主题与明暗模式，确保全局视觉风格一致。
 */
internal object ThemePreferences {
    private const val PREFS_NAME = "jotbill_theme_prefs"
    private const val KEY_THEME_NAME = "theme_name"
    private const val KEY_IS_DARK = "is_dark"
    private const val DEFAULT_THEME_NAME = "blocks"
    const val THEME_SOFT = "soft"

    fun save(context: Context, themeName: String, isDark: Boolean) {
        val normalized = themeName.trim().ifEmpty { DEFAULT_THEME_NAME }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_NAME, normalized)
            .putBoolean(KEY_IS_DARK, isDark)
            .apply()
    }

    fun getThemeName(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_NAME, DEFAULT_THEME_NAME)
            ?: DEFAULT_THEME_NAME

    fun isDark(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_DARK, false)

    fun isSoft(context: Context): Boolean =
        isSoftThemeName(getThemeName(context))

    /** 纯函数判定，便于单测验证。 */
    internal fun isSoftThemeName(name: String?): Boolean =
        name?.trim()?.equals(THEME_SOFT, ignoreCase = true) == true
}
