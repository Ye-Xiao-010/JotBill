package com.jotbill.app.overlay

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * 为 RN 提供付款确认弹窗的开关读写。
 *
 * 2026-10-12 精简：截图识别链路整链删除后，本模块只剩两个出口。随之一并删掉的还有：
 *   · `CheckoutCaptureOverlayManager` 的全部 6 个 companion 静态方法（悬浮按钮
 *     权限/显示方式/状态）—— 唯一调用方是被删掉的 `ManualCaptureOverlaySettings`；
 *   · `CheckoutCaptureOverlayStateHub` 状态事件链路（`stateListener` / `emitState` /
 *     `stateToMap` / `EVENT_STATE_CHANGED`）—— 事件只服务于那张已删的设置卡片；
 *   · `resultToMap` / `SnapshotCaptureResult` —— `requestManualCapture` 的返回 DTO；
 *   · `RecognitionCapabilities` 读写（识别链路开关本身，入口卡片已移除）；
 *   · `LifecycleEventListener` 实现。原先注册它只是为了在 Activity 销毁时摘掉状态
 *     hub 的监听（见旧 `onHostDestroy` 注释里那段 RN 0.81 CatalystInstance 复用分析），
 *     状态链路不存在后没有需要摘的监听，`onHostDestroy` 里那句
 *     `removeLifecycleEventListener` 也就没有存在的必要了。
 */
class OverlayModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    companion object {
        const val MODULE_NAME = "Overlay"
        private const val ERROR_CODE = "OVERLAY_ERROR"
    }

    override fun getName(): String = MODULE_NAME

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
}