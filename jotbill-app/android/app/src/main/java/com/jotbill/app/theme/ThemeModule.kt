package com.jotbill.app.theme

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * 主题、魔法音效与动态桌面图标原生桥接模块（暴露为 NativeModules.ThemeModule）。
 */
class ThemeModule(
    reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ThemeModule"

    @ReactMethod
    fun setThemePreference(
        themeName: String,
        house: String,
        sfxEnabled: Boolean,
        dynamicIconEnabled: Boolean,
        promise: Promise
    ) {
        try {
            ThemePreferences.save(
                reactApplicationContext,
                themeName,
                house,
                sfxEnabled,
                dynamicIconEnabled
            )
            if (themeName == ThemePreferences.THEME_HOGWARTS && sfxEnabled) {
                MagicSoundPlayer.prewarm(reactApplicationContext)
            }
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("THEME_PREF_ERROR", error.message, error)
        }
    }

    @ReactMethod
    fun playSound(soundKey: String, promise: Promise) {
        try {
            val played = MagicSoundPlayer.play(reactApplicationContext, soundKey)
            promise.resolve(played)
        } catch (error: Exception) {
            promise.resolve(false)
        }
    }

    /** 手动立即触发桌面图标同步（常规路径在 MainActivity.onStop 退后台时自动无感切换）。 */
    @ReactMethod
    fun applyLauncherIconNow(promise: Promise) {
        try {
            val changed = LauncherIconManager.applyPendingFromPreferences(reactApplicationContext)
            promise.resolve(changed)
        } catch (error: Exception) {
            promise.resolve(false)
        }
    }
}
