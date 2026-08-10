package com.jotbill.app.hotupdate

import android.content.Context
import android.content.SharedPreferences
import com.facebook.react.bridge.*
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.ReactInstanceManager
import com.facebook.react.bridge.JSBundleLoader
import java.io.File

class HotUpdateModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "HotUpdate"
        private const val PREFS_NAME = "hot_update"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val HOT_UPDATE_DIR = "hot_update"
        private const val HOT_UPDATE_TEMP_DIR = "hot_update_temp"
        private const val HOT_UPDATE_PREVIOUS_DIR = "hot_update_previous"
        private const val BUNDLE_FILE = "index.android.bundle"
    }

    private val replaceLock = Any()

    override fun getName(): String = NAME

    private fun getPrefs(): SharedPreferences {
        return reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getHotUpdateDir(): File {
        return File(reactApplicationContext.filesDir, HOT_UPDATE_DIR)
    }

    /**
     * 标记 bundle 已成功加载，重置崩溃计数
     */
    @ReactMethod
    fun markBundleApplied() {
        getPrefs().edit().putInt(KEY_CRASH_COUNT, 0).apply()
    }

    /**
     * 获取热更新信息
     */
    @ReactMethod
    fun getUpdateInfo(promise: Promise) {
        try {
            val hotDir = getHotUpdateDir()
            val bundlePath = File(hotDir, BUNDLE_FILE)
            val prefs = getPrefs()

            val map = Arguments.createMap()
            map.putBoolean("hasHotBundle", bundlePath.exists() && bundlePath.length() > 0)
            map.putString("bundlePath", bundlePath.absolutePath)
            map.putString("hotUpdateDir", hotDir.absolutePath)
            map.putInt("crashCount", prefs.getInt(KEY_CRASH_COUNT, 0))
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("GET_INFO_ERROR", e.message)
        }
    }

    /** 将已校验的临时目录一次性替换为正式目录，失败时恢复旧目录。 */
    @ReactMethod
    fun applyPreparedBundle(promise: Promise) {
        try {
            synchronized(replaceLock) {
                val filesDir = reactApplicationContext.filesDir
                val preparedDir = File(filesDir, HOT_UPDATE_TEMP_DIR)
                val currentDir = File(filesDir, HOT_UPDATE_DIR)
                val previousDir = File(filesDir, HOT_UPDATE_PREVIOUS_DIR)
                val preparedBundle = File(preparedDir, BUNDLE_FILE)

                if (!preparedDir.isDirectory || !preparedBundle.isFile || preparedBundle.length() <= 0) {
                    throw IllegalStateException("临时热更新 bundle 不完整")
                }
                if (previousDir.exists() && !previousDir.deleteRecursively()) {
                    throw IllegalStateException("无法清理旧热更新备份")
                }

                val hadCurrent = currentDir.exists()
                if (hadCurrent && !currentDir.renameTo(previousDir)) {
                    throw IllegalStateException("无法保留当前热更新 bundle")
                }

                if (!preparedDir.renameTo(currentDir)) {
                    if (hadCurrent && !previousDir.renameTo(currentDir)) {
                        throw IllegalStateException("热更新失败且旧 bundle 回滚失败")
                    }
                    throw IllegalStateException("无法应用临时热更新 bundle")
                }

                if (previousDir.exists() && !previousDir.deleteRecursively()) {
                    android.util.Log.w("HotUpdate", "旧热更新备份清理失败，将在下次更新时重试")
                }
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("APPLY_ERROR", e.message, e)
        }
    }

    /**
     * 重新加载 JS Bundle
     * reload 前先把 BundleLoader 指向热更新文件，避免重新加载内置 bundle 导致死循环
     */
    @ReactMethod
    fun reload() {
        UiThreadUtil.runOnUiThread {
            val app = reactApplicationContext.applicationContext as? com.facebook.react.ReactApplication
                ?: return@runOnUiThread
            val manager = app.reactNativeHost.reactInstanceManager

            // 将 loader 指向热更新 bundle（sourceURL 保持 assets:// 以保留图片解析）
            val hotBundle = File(reactApplicationContext.filesDir, "$HOT_UPDATE_DIR/$BUNDLE_FILE")
            if (hotBundle.exists() && hotBundle.length() > 0) {
                try {
                    val field = ReactInstanceManager::class.java.getDeclaredField("mBundleLoader")
                    field.isAccessible = true
                    field.set(manager, JSBundleLoader.createFileLoader(
                        hotBundle.absolutePath, "assets://$BUNDLE_FILE", false
                    ))
                } catch (e: Exception) {
                    android.util.Log.w("HotUpdate", "reload: 替换 loader 失败: ${e.message}")
                }
            }

            manager.recreateReactContextInBackground()
        }
    }

    /**
     * 清除热更新 bundle，回退到内置版本
     */
    @ReactMethod
    fun clearHotUpdate(promise: Promise) {
        try {
            val filesDir = reactApplicationContext.filesDir
            listOf(
                File(filesDir, HOT_UPDATE_DIR),
                File(filesDir, HOT_UPDATE_TEMP_DIR),
                File(filesDir, HOT_UPDATE_PREVIOUS_DIR)
            ).forEach { directory ->
                if (directory.exists()) directory.deleteRecursively()
            }
            getPrefs().edit().clear().apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CLEAR_ERROR", e.message)
        }
    }
}
