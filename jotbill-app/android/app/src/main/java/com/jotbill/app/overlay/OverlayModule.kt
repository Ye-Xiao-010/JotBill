package com.jotbill.app.overlay

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.recognition.RecognitionCapabilities
import com.jotbill.app.recognition.SnapshotCaptureResult

/** 为 RN 提供悬浮按钮权限、显示配置、截图触发和无图片状态事件。 */
class OverlayModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {
    companion object {
        const val MODULE_NAME = "Overlay"
        const val EVENT_STATE_CHANGED = "onCheckoutCaptureOverlayStateChanged"
        private const val PERMISSION_AUTHORIZED = "authorized"
        private const val PERMISSION_DENIED = "denied"
        private const val ERROR_CODE = "OVERLAY_ERROR"
    }

    private val stateListener: (CheckoutOverlayState) -> Unit = { state ->
        emitState(state)
    }

    init {
        reactContext.addLifecycleEventListener(this)
        CheckoutCaptureOverlayStateHub.setListener(stateListener)
    }

    override fun getName(): String = MODULE_NAME

    @ReactMethod
    fun getOverlayPermissionStatus(promise: Promise) {
        try {
            promise.resolve(
                if (CheckoutCaptureOverlayManager.hasOverlayPermission(reactContext)) {
                    PERMISSION_AUTHORIZED
                } else {
                    PERMISSION_DENIED
                }
            )
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "读取悬浮窗权限失败", error)
        }
    }

    @ReactMethod
    fun requestOverlayPermission(promise: Promise) {
        try {
            CheckoutCaptureOverlayManager.requestOverlayPermission(reactContext)
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "打开悬浮窗权限设置失败", error)
        }
    }

    @ReactMethod
    fun setManualCaptureButtonEnabled(enabled: Boolean, promise: Promise) {
        try {
            CheckoutCaptureOverlayManager.setEnabled(reactContext, enabled)
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "更新手动截图悬浮按钮状态失败", error)
        }
    }

    @ReactMethod
    fun setManualCaptureButtonMode(mode: String, promise: Promise) {
        try {
            val parsed = CheckoutOverlayMode.parse(mode)
            CheckoutCaptureOverlayManager.setMode(reactContext, parsed)
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "更新手动截图悬浮按钮模式失败", error)
        }
    }

    @ReactMethod
    fun getManualCaptureButtonMode(promise: Promise) {
        try {
            promise.resolve(CheckoutCaptureOverlayManager.getMode(reactContext).wireValue)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "读取手动截图悬浮按钮模式失败", error)
        }
    }

    @ReactMethod
    fun getManualCaptureButtonState(promise: Promise) {
        try {
            promise.resolve(stateToMap(CheckoutCaptureOverlayManager.currentState(reactContext)))
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "读取手动截图悬浮按钮状态失败", error)
        }
    }

    @ReactMethod
    fun setPendingConfirmPopupEnabled(enabled: Boolean, promise: Promise) {
        try {
            PendingConfirmOverlayManager.setEnabled(reactContext, enabled)
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "更新付款确认弹窗开关失败", error)
        }
    }

    @ReactMethod
    fun getPendingConfirmPopupEnabled(promise: Promise) {
        try {
            promise.resolve(PendingConfirmOverlayManager.isEnabled(reactContext))
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "读取付款确认弹窗开关失败", error)
        }
    }

    /**
     * 结算页截图识别链路开关（精简版）。
     *
     * 与 [setPendingConfirmPopupEnabled] 刻意分开：后者只控制"弹窗出不出现"，
     * 前者控制"要不要截结算页的图送 AI"。两者都是 SharedPreferences 落盘，
     * 但语义正交——关掉识别链路不影响弹窗，关掉弹窗也不影响识别。
     */
    @ReactMethod
    fun setCheckoutCaptureEnabled(enabled: Boolean, promise: Promise) {
        try {
            RecognitionCapabilities.setCheckoutCaptureEnabled(reactContext, enabled)
            promise.resolve(null)
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "更新结算页截图识别链路开关失败", error)
        }
    }

    @ReactMethod
    fun getCheckoutCaptureEnabled(promise: Promise) {
        try {
            promise.resolve(RecognitionCapabilities.isCheckoutCaptureEnabled(reactContext))
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "读取结算页截图识别链路开关失败", error)
        }
    }

    @ReactMethod
    fun requestManualCapture(promise: Promise) {
        try {
            CheckoutAccessibilityService.requestManualCapture { result ->
                promise.resolve(resultToMap(result))
            }
        } catch (error: RuntimeException) {
            promise.reject(ERROR_CODE, "请求手动截图失败", error)
        }
    }

    /** React Native 新版事件发射器要求模块提供这两个订阅计数方法。 */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    override fun onHostResume() = Unit

    override fun onHostPause() = Unit

    /**
     * 宿主 Activity 销毁**不是** CatalystInstance 销毁。
     *
     * RN 0.81 路径：onHostDestroy → ReactInstanceManager 只把生命周期状态前移并
     * 发事件，不销毁 CatalystInstance；Activity 因内存紧张被回收后重建时，
     * NativeModule 实例被复用、`init` 不再执行。
     *
     * 因此清监听必须挂在 [invalidate]（= onCatalystInstanceDestroy）上：
     * 挂在 onHostDestroy 会让 CheckoutCaptureOverlayStateHub.listener 变 null
     * 且永不重建，`onCheckoutCaptureOverlayStateChanged` 在本进程内永久失效，
     * ManualCaptureOverlaySettings.tsx:242 的订阅静默失效。
     */
    override fun onHostDestroy() {
        // 只解除 RN 生命周期订阅；状态监听保留到 invalidate。
        reactContext.removeLifecycleEventListener(this)
    }

    override fun invalidate() {
        CheckoutCaptureOverlayStateHub.clearListener(stateListener)
        reactContext.removeLifecycleEventListener(this)
        super.invalidate()
    }

    private fun emitState(state: CheckoutOverlayState) {
        if (!reactContext.hasActiveCatalystInstance()) {
            return
        }
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT_STATE_CHANGED, stateToMap(state))
        } catch (_: RuntimeException) {
            // RN 正在销毁时丢弃状态事件，不保留原生页面或图片数据。
        }
    }

    private fun stateToMap(state: CheckoutOverlayState): WritableMap =
        Arguments.createMap().apply {
            putString("status", state.status.wireValue)
            putBoolean("visible", state.visible)
            putBoolean("enabled", state.enabled)
            putString("mode", state.mode.wireValue)
            putNullableString("reasonCode", state.reasonCode)
            putNullableString("shoppingPackage", state.shoppingPackage)
            putNullableString("pageFingerprint", state.pageFingerprint)
            putNullableString("sessionId", state.sessionId)
            putNullableString("snapshotId", state.snapshotId)
            if (state.candidateRevision == null) {
                putNull("candidateRevision")
            } else {
                putInt("candidateRevision", state.candidateRevision)
            }
            putBoolean("requiresDisambiguation", state.requiresDisambiguation)
        }

    private fun resultToMap(result: SnapshotCaptureResult): WritableMap =
        Arguments.createMap().apply {
            putString("status", result.status.wireValue)
            putString("sourceReason", result.sourceReason.wireValue)
            putString("reasonCode", result.reasonCode)
            putNullableString("sessionId", result.sessionId)
            putNullableString("slotId", result.slotId)
            putNullableString("shoppingPackage", result.shoppingPackage)
            putNullableString("pageFingerprint", result.pageFingerprint)
            putNullableString("snapshotId", result.snapshotId)
            if (result.candidateRevision == null) {
                putNull("candidateRevision")
            } else {
                putInt("candidateRevision", result.candidateRevision)
            }
            putBoolean("requiresDisambiguation", result.requiresDisambiguation)
        }

    private fun WritableMap.putNullableString(key: String, value: String?) {
        if (value == null) {
            putNull(key)
        } else {
            putString(key, value)
        }
    }
}
