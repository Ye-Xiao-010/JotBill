package com.jotbill.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

/** Emits structured events without exposing Room entities to React Native. */
internal class NativeEventEmitter(
    private val reactContext: ReactApplicationContext
) {
    /**
     * 向 JS 推送事件。
     *
     * **推送是旁路，失败绝不能影响已完成的落库**。`getJSModule` 在 CatalystInstance
     * 未 initialize 或已 destroy 时抛 `IllegalStateException`（见
     * BridgeReactContext.java:106-111）。此前本方法没有任何存活守卫，异常会一路
     * 冒泡进调用方的 `launch(promise)`，被包成 `DB_ERROR` reject ——
     * 典型症状是"写库成功、JS 收到失败"，而 addBill 的 id 是随机 UUID、
     * 无幂等键，用户重试就真的产生重复账单。
     *
     * 同仓 OverlayModule.emitState 已有 `hasActiveCatalystInstance()` 守卫，
     * 此处补齐同一口径。
     */
    fun emit(eventName: String, payload: JSONObject? = null) {
        if (!reactContext.hasActiveCatalystInstance()) {
            return
        }
        try {
            reactContext.getJSModule(
                DeviceEventManagerModule.RCTDeviceEventEmitter::class.java
            ).emit(eventName, payload?.toWritableMap())
        } catch (error: RuntimeException) {
            // JS 侧未就绪或正在销毁：事件丢弃（数据已持久化，提醒是尽力而为的旁路）。
            android.util.Log.w("NativeEventEmitter", "推送事件失败，已丢弃: $eventName", error)
        }
    }
}

private fun JSONObject.toWritableMap(): WritableMap = Arguments.createMap().also { map ->
    keys().forEach { key ->
        putWritableValue(map, key, get(key))
    }
}

private fun JSONArray.toWritableArray(): WritableArray = Arguments.createArray().also { array ->
    for (index in 0 until length()) {
        putWritableValue(array, get(index))
    }
}

private fun putWritableValue(map: WritableMap, key: String, value: Any?) {
    when (value) {
        null, JSONObject.NULL -> map.putNull(key)
        is JSONObject -> map.putMap(key, value.toWritableMap())
        is JSONArray -> map.putArray(key, value.toWritableArray())
        is Boolean -> map.putBoolean(key, value)
        is Int -> map.putInt(key, value)
        is Long -> map.putDouble(key, value.toDouble())
        is Float -> map.putDouble(key, value.toDouble())
        is Double -> map.putDouble(key, value)
        is Number -> map.putDouble(key, value.toDouble())
        else -> map.putString(key, value.toString())
    }
}

private fun putWritableValue(array: WritableArray, value: Any?) {
    when (value) {
        null, JSONObject.NULL -> array.pushNull()
        is JSONObject -> array.pushMap(value.toWritableMap())
        is JSONArray -> array.pushArray(value.toWritableArray())
        is Boolean -> array.pushBoolean(value)
        is Int -> array.pushInt(value)
        is Long -> array.pushDouble(value.toDouble())
        is Float -> array.pushDouble(value.toDouble())
        is Double -> array.pushDouble(value)
        is Number -> array.pushDouble(value.toDouble())
        else -> array.pushString(value.toString())
    }
}
