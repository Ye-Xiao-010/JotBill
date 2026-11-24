package com.jotbill.app.theme

import android.graphics.Color
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.core.view.WindowCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil

/**
 * 原生主题、触感、魔法音效与动态桌面图标桥接模块（JS 侧通过 NativeModules.ThemeModule 调用）。
 *
 * 1. syncTheme：持久化主题名/明暗到 SharedPreferences（供悬浮窗读取），并同步系统底部
 *    NavigationBar 背景色及图标明暗（消除深色模式下的底部白条）。
 * 2. performHaptic：通过 decorView.performHapticFeedback 触发系统线性马达触感，
 *    无需 VIBRATE 权限并自动遵循用户系统触感设置。
 * 3. setThemePreference：同步魔法羊皮纸主题的学院、音效与动态桌面图标开关，并预热 SoundPool。
 * 4. playSound / applyLauncherIconNow：触发魔法交互音效与动态桌面图标切换。
 */
class ThemeModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ThemeModule"

    @ReactMethod
    fun syncTheme(themeName: String, isDark: Boolean, navigationBarColor: String) {
        ThemePreferences.save(reactApplicationContext, themeName, isDark)
        val parsedColor = parseColorOrNull(navigationBarColor) ?: return
        UiThreadUtil.runOnUiThread {
            val activity = reactApplicationContext.currentActivity ?: return@runOnUiThread
            val window = activity.window ?: return@runOnUiThread
            runCatching {
                @Suppress("DEPRECATION")
                window.navigationBarColor = parsedColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @ReactMethod
    fun performHaptic(kind: String) {
        val constant = resolveHapticConstant(kind, Build.VERSION.SDK_INT)
        UiThreadUtil.runOnUiThread {
            val decorView = reactApplicationContext.currentActivity?.window?.decorView ?: return@runOnUiThread
            runCatching {
                decorView.performHapticFeedback(constant)
            }
        }
    }

    @ReactMethod
    fun setThemePreference(
        themeName: String,
        house: String,
        sfxEnabled: Boolean,
        dynamicIconEnabled: Boolean,
        promise: Promise,
    ) {
        try {
            ThemePreferences.save(
                reactApplicationContext,
                themeName,
                house,
                sfxEnabled,
                dynamicIconEnabled,
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

    internal companion object {
        fun parseColorOrNull(hex: String?): Int? {
            if (hex.isNullOrBlank()) return null
            return runCatching { Color.parseColor(hex.trim()) }.getOrNull()
        }

        fun resolveHapticConstant(kind: String, sdkInt: Int): Int = when (kind) {
            "success" -> if (sdkInt >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            "medium" -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
    }
}
