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
        private const val KEY_SWAP_STATE = "swap_state"
        private const val SWAP_IDLE = "idle"
        private const val SWAP_PREPARED = "prepared"
        private const val SWAP_CURRENT_MOVED = "current_moved"
        private const val SWAP_CURRENT_INSTALLED = "current_installed"

        private val fileSwapLock = Any()
    }

    override fun getName(): String = NAME

    private fun getPrefs(): SharedPreferences {
        return reactApplicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getHotUpdateDir(): File {
        return File(reactApplicationContext.filesDir, HOT_UPDATE_DIR)
    }

    private fun isUsableBundle(directory: File): Boolean {
        val bundle = File(directory, BUNDLE_FILE)
        return directory.isDirectory && bundle.isFile && bundle.length() > 0
    }

    private fun deleteDirectoryIfPresent(directory: File): Boolean {
        if (!directory.exists()) return true
        return directory.deleteRecursively() && !directory.exists()
    }

    private fun writeSwapState(prefs: SharedPreferences, state: String): Boolean {
        return prefs.edit().putString(KEY_SWAP_STATE, state).commit()
    }

    /** 根据 marker 和目录状态恢复被进程中断的原子交换。 */
    private fun recoverInterruptedSwapLocked(filesDir: File, prefs: SharedPreferences) {
        val currentDir = File(filesDir, HOT_UPDATE_DIR)
        val previousDir = File(filesDir, HOT_UPDATE_PREVIOUS_DIR)
        val marker = prefs.getString(KEY_SWAP_STATE, SWAP_IDLE) ?: SWAP_IDLE

        if (!previousDir.exists() && marker == SWAP_IDLE) return

        if (isUsableBundle(currentDir)) {
            if (!deleteDirectoryIfPresent(previousDir)) {
                android.util.Log.w("HotUpdate", "新 bundle 可用，但旧备份清理失败，将保留现场重试")
                return
            }
            if (!writeSwapState(prefs, SWAP_IDLE)) {
                android.util.Log.w("HotUpdate", "热更新恢复 marker 清理失败，将在下次启动重试")
            }
            return
        }

        if (isUsableBundle(previousDir)) {
            if (!deleteDirectoryIfPresent(currentDir)) {
                android.util.Log.w("HotUpdate", "旧 bundle 可恢复，但不完整的新目录清理失败")
                return
            }
            if (!previousDir.renameTo(currentDir) || !isUsableBundle(currentDir)) {
                android.util.Log.w("HotUpdate", "热更新中断恢复失败，保留目录以避免丢失旧 bundle")
                return
            }
            if (!writeSwapState(prefs, SWAP_IDLE)) {
                android.util.Log.w("HotUpdate", "热更新恢复 marker 写入失败，将在下次启动重试")
            }
            return
        }

        if (marker != SWAP_IDLE || currentDir.exists() || previousDir.exists()) {
            android.util.Log.w("HotUpdate", "热更新目录状态不完整，未删除现场")
        }
    }

    /** 在 React Native 初始化前恢复上次未完成的目录交换。 */
    @JvmStatic
    fun recoverInterruptedSwap(context: Context) {
        synchronized(fileSwapLock) {
            recoverInterruptedSwapLocked(
                context.filesDir,
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
        }
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
            recoverInterruptedSwap(reactApplicationContext)
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
            synchronized(fileSwapLock) {
                val filesDir = reactApplicationContext.filesDir
                val preparedDir = File(filesDir, HOT_UPDATE_TEMP_DIR)
                val currentDir = File(filesDir, HOT_UPDATE_DIR)
                val previousDir = File(filesDir, HOT_UPDATE_PREVIOUS_DIR)
                val preparedBundle = File(preparedDir, BUNDLE_FILE)

                if (!preparedDir.isDirectory || !preparedBundle.isFile || preparedBundle.length() <= 0) {
                    throw IllegalStateException("临时热更新 bundle 不完整")
                }

                recoverInterruptedSwapLocked(filesDir, getPrefs())
                if (previousDir.exists()) {
                    throw IllegalStateException("上次热更新交换尚未完成")
                }

                val hadCurrent = currentDir.exists()
                if (!writeSwapState(getPrefs(), SWAP_PREPARED)) {
                    throw IllegalStateException("无法记录热更新交换状态")
                }
                if (hadCurrent && !currentDir.renameTo(previousDir)) {
                    throw IllegalStateException("无法保留当前热更新 bundle")
                }
                if (!writeSwapState(getPrefs(), SWAP_CURRENT_MOVED)) {
                    throw IllegalStateException("无法记录热更新交换进度")
                }

                if (!preparedDir.renameTo(currentDir)) {
                    val restored = !hadCurrent ||
                        (previousDir.renameTo(currentDir) && isUsableBundle(currentDir))
                    if (restored) {
                        if (!writeSwapState(getPrefs(), SWAP_IDLE)) {
                            throw IllegalStateException("热更新失败且无法记录回滚状态")
                        }
                    } else {
                        throw IllegalStateException("热更新失败且旧 bundle 回滚失败")
                    }
                    throw IllegalStateException("无法应用临时热更新 bundle")
                }

                if (!isUsableBundle(currentDir)) {
                    val removed = deleteDirectoryIfPresent(currentDir)
                    val restored = !hadCurrent ||
                        (previousDir.renameTo(currentDir) && isUsableBundle(currentDir))
                    if (!removed || !restored) {
                        throw IllegalStateException("热更新目录校验失败且旧 bundle 回滚失败")
                    }
                    if (!writeSwapState(getPrefs(), SWAP_IDLE)) {
                        throw IllegalStateException("热更新目录校验失败且无法记录回滚状态")
                    }
                    throw IllegalStateException("热更新 bundle 校验失败")
                }

                if (!writeSwapState(getPrefs(), SWAP_CURRENT_INSTALLED)) {
                    throw IllegalStateException("无法记录热更新安装状态")
                }
                if (!deleteDirectoryIfPresent(previousDir)) {
                    throw IllegalStateException("新 bundle 已安装，但旧 bundle 备份清理失败")
                }
                if (!writeSwapState(getPrefs(), SWAP_IDLE)) {
                    throw IllegalStateException("热更新已安装，但无法清理交换状态")
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
            synchronized(fileSwapLock) {
                val filesDir = reactApplicationContext.filesDir
                val deleted = listOf(
                    File(filesDir, HOT_UPDATE_DIR),
                    File(filesDir, HOT_UPDATE_TEMP_DIR),
                    File(filesDir, HOT_UPDATE_PREVIOUS_DIR),
                ).all { directory -> deleteDirectoryIfPresent(directory) }
                if (!deleted) {
                    promise.resolve(false)
                } else if (!getPrefs().edit().clear().commit()) {
                    promise.resolve(false)
                } else {
                    promise.resolve(true)
                }
            }
        } catch (e: Exception) {
            promise.reject("CLEAR_ERROR", e.message)
        }
    }
}
