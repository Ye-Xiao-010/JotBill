package com.jotbill.app.theme

import android.content.Context

/**
 * 原生侧主题偏好存储（由 RN ThemeProvider 经 ThemeModule 同步写入）。
 *
 * 供脱离 RN 运行的原生组件（付款确认悬浮窗 SoftOverlayTheme / HogwartsOverlayTheme /
 * PopupIconAssets、SoundPool 魔法音效播放器、动态桌面图标切换器 LauncherIconManager）
 * 在不依赖 RN 实例时读取当前主题、明暗模式与学院/音效/图标偏好。
 */
internal object ThemePreferences {
    private const val PREFS_NAME = "jotbill_theme_prefs"
    private const val KEY_THEME_NAME = "theme_name"
    private const val KEY_IS_DARK = "is_dark"
    private const val KEY_HOGWARTS_HOUSE = "hogwarts_house"
    private const val KEY_SFX_ENABLED = "hogwarts_sfx_enabled"
    private const val KEY_DYNAMIC_ICON_ENABLED = "hogwarts_dynamic_icon_enabled"

    private const val DEFAULT_THEME_NAME = "blocks"
    const val THEME_SOFT = "soft"
    const val THEME_HOGWARTS = "hogwarts"

    fun save(context: Context, themeName: String, isDark: Boolean) {
        val normalized = themeName.trim().ifEmpty { DEFAULT_THEME_NAME }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_NAME, normalized)
            .putBoolean(KEY_IS_DARK, isDark)
            .apply()
    }

    fun save(
        context: Context,
        themeName: String,
        house: String,
        sfxEnabled: Boolean,
        dynamicIconEnabled: Boolean = true
    ) {
        val normalized = themeName.trim().ifEmpty { DEFAULT_THEME_NAME }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_NAME, normalized)
            .putString(KEY_HOGWARTS_HOUSE, house)
            .putBoolean(KEY_SFX_ENABLED, sfxEnabled)
            .putBoolean(KEY_DYNAMIC_ICON_ENABLED, dynamicIconEnabled)
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

    fun getHogwartsHouse(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOGWARTS_HOUSE, "classic")
            ?: "classic"

    fun isSfxEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SFX_ENABLED, true)

    fun isDynamicIconEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DYNAMIC_ICON_ENABLED, true)

    fun isHogwarts(context: Context): Boolean =
        getThemeName(context).equals(THEME_HOGWARTS, ignoreCase = true)

    fun isHogwartsSfxActive(context: Context): Boolean =
        isHogwarts(context) && isSfxEnabled(context)
}
