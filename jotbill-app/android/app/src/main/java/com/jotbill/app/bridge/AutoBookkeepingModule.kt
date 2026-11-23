package com.jotbill.app.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.jotbill.app.bookkeeping.BookkeepingChannels
import com.jotbill.app.keepalive.KeepAliveSettings
import com.jotbill.app.keepalive.RecognitionKeepAliveService
import com.jotbill.app.overlay.PendingConfirmOverlayManager
import com.jotbill.app.utils.JotBillLog
import org.json.JSONObject

/**
 * 自动记账设置桥接：总开关 + 渠道开关 + 内置规则规模。
 *
 * 开关本体落在 SharedPreferences（[BookkeepingChannels]），无障碍服务每事件
 * 读取 —— RN 侧改完即生效，无需通知服务重启。
 */
class AutoBookkeepingModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "AutoBookkeeping"

    @ReactMethod
    fun getSettings(promise: Promise) {
        try {
            val channelsJson = JSONObject().apply {
                for (info in BookkeepingChannels.CHANNELS) {
                    put(info.key, BookkeepingChannels.isChannelEnabled(reactContext, info.key))
                }
            }
            val metaJson = org.json.JSONArray().apply {
                for (info in BookkeepingChannels.CHANNELS) {
                    put(
                        JSONObject()
                            .put("key", info.key)
                            .put("label", info.label)
                            .put("description", info.description)
                            .put("defaultEnabled", info.defaultEnabled)
                    )
                }
            }
            val result = JSONObject()
                .put("masterEnabled", BookkeepingChannels.isMasterEnabled(reactContext))
                .put("silentBookkeepingEnabled", PendingConfirmOverlayManager.isSilentBookkeepingEnabled(reactContext))
                .put("autoBookedNotificationEnabled", BookkeepingChannels.isAutoBookedNotificationEnabled(reactContext))
                .put("keepAliveEnabled", KeepAliveSettings.isEnabled(reactContext))
                .put("keepAlivePrompted", KeepAliveSettings.isPrompted(reactContext))
                .put("keepAliveRunning", RecognitionKeepAliveService.isRunning)
                .put("channels", channelsJson)
                .put("channelMeta", metaJson)
            promise.resolve(result.toString())
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SETTINGS_FAILED", error)
        }
    }

    @ReactMethod
    fun setMasterEnabled(enabled: Boolean, promise: Promise) {
        try {
            BookkeepingChannels.setMasterEnabled(reactContext, enabled)
            RecognitionKeepAliveService.sync(reactContext)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun setSilentBookkeepingEnabled(enabled: Boolean, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                PendingConfirmOverlayManager.setSilentBookkeepingEnabled(reactContext, enabled)
                promise.resolve(null)
            } catch (error: RuntimeException) {
                promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
            }
        }
    }

    @ReactMethod
    fun setAutoBookedNotificationEnabled(enabled: Boolean, promise: Promise) {
        try {
            BookkeepingChannels.setAutoBookedNotificationEnabled(reactContext, enabled)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun setKeepAliveEnabled(enabled: Boolean, promise: Promise) {
        try {
            KeepAliveSettings.setEnabled(reactContext, enabled)
            RecognitionKeepAliveService.sync(reactContext)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun markKeepAlivePrompted(promise: Promise) {
        try {
            KeepAliveSettings.markPrompted(reactContext)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun setChannelEnabled(channel: String, enabled: Boolean, promise: Promise) {
        if (!BookkeepingChannels.isValidChannelKey(channel)) {
            promise.reject("AUTO_BOOKKEEPING_UNKNOWN_CHANNEL", "Unknown channel: $channel")
            return
        }
        try {
            BookkeepingChannels.setChannelEnabled(reactContext, channel, enabled)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun getRuleStats(promise: Promise) {
        try {
            val registry = com.jotbill.app.bookkeeping.PageRuleRegistry.get(reactContext)
            val stats = JSONObject()
                .put("keys", registry.keyCount)
                .put("rules", registry.parsedRuleCount)
                .putOpt("error", registry.loadError)
            promise.resolve(stats.toString())
        } catch (error: Exception) {
            JotBillLog.w("AutoBookkeepingModule", "规则统计读取失败", error)
            promise.reject("AUTO_BOOKKEEPING_STATS_FAILED", error)
        }
    }

    @ReactMethod
    fun getServiceRunning(promise: Promise) {
        try {
            promise.resolve(
                com.jotbill.app.recognition.CheckoutAccessibilityService.isServiceRunning()
            )
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_STATUS_FAILED", error)
        }
    }

    companion object
}
