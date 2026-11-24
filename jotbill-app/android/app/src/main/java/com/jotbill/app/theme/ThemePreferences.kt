package com.jotbill.app.theme

import android.content.Context

/**
 * 原生侧主题偏好存储（由 RN ThemeProvider 经 ThemeModule 同步写入）。
 * 供悬浮付款确认弹窗（PopupIconAssets）、SoundPool 音效模块与动态桌面图标切换器在不依赖 RN 实例时读取。
 */
internal object ThemePreferences {
    private const val PREFS_NAME = "jotbill_theme"
    private const val KEY_THEME_NAME = "theme_name"
    private const val KEY_HOGWARTS_HOUSE = "hogwarts_house"
    private const val KEY_SFX_ENABLED = "hogwarts_sfx_enabled"
    private const val KEY_DYNAMIC_ICON_ENABLED = "hogwarts_dynamic_icon_enabled"

    const val THEME_HOGWARTS = "hogwarts"

    fun save(
        context: Context,
        themeName: String,
        house: String,
        sfxEnabled: Boolean,
        dynamicIconEnabled: Boolean = true
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_THEME_NAME, themeName)
            .putString(KEY_HOGWARTS_HOUSE, house)
            .putBoolean(KEY_SFX_ENABLED, sfxEnabled)
            .putBoolean(KEY_DYNAMIC_ICON_ENABLED, dynamicIconEnabled)
            .apply()
    }

    fun getThemeName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_NAME, "blocks") ?: "blocks"
    }

    fun getHogwartsHouse(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOGWARTS_HOUSE, "classic") ?: "classic"
    }

    fun isSfxEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SFX_ENABLED, true)
    }

    fun isDynamicIconEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DYNAMIC_ICON_ENABLED, true)
    }

    fun isHogwarts(context: Context): Boolean = getThemeName(context) == THEME_HOGWARTS

    fun isHogwartsSfxActive(context: Context): Boolean = isHogwarts(context) && isSfxEnabled(context)
}
