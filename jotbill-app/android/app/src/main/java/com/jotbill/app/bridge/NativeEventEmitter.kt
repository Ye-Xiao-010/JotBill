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
    fun emit(eventName: String, payload: JSONObject? = null) {
        val event = reactContext.getJSModule(
            DeviceEventManagerModule.RCTDeviceEventEmitter::class.java
        )
        event.emit(eventName, payload?.toWritableMap())
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
