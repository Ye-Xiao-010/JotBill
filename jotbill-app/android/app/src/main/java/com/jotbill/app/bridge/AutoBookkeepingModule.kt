package com.jotbill.app.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.bookkeeping.BookkeepingChannels
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
            val result = JSONObject()
                .put("masterEnabled", BookkeepingChannels.isMasterEnabled(reactContext))
                .put(
                    "channels",
                    JSONObject().apply {
                        for (channel in CHANNELS) {
                            put(channel, BookkeepingChannels.isChannelEnabled(reactContext, channel))
                        }
                    }
                )
            promise.resolve(result.toString())
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SETTINGS_FAILED", error)
        }
    }

    @ReactMethod
    fun setMasterEnabled(enabled: Boolean, promise: Promise) {
        try {
            BookkeepingChannels.setMasterEnabled(reactContext, enabled)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTO_BOOKKEEPING_SET_FAILED", error)
        }
    }

    @ReactMethod
    fun setChannelEnabled(channel: String, enabled: Boolean, promise: Promise) {
        if (channel !in CHANNELS) {
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
            promise.resolve(stats.toString())
        } catch (error: Exception) {
            JotBillLog.w("AutoBookkeepingModule", "规则统计读取失败", error)
            promise.reject("AUTO_BOOKKEEPING_STATS_FAILED", error)
        }
    }

    companion object {
        /** 与 BookkeepingChannels.DEFAULTS 的键保持一致（RN 只见这四个）。 */
        private val CHANNELS = listOf("alipay", "wechat", "unionpay", "mms")
    }
}
