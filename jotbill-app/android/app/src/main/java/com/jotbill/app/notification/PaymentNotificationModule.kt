package com.jotbill.app.notification

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray

/**
 * 支付通知监听 React Native 模块
 * 
 * 提供给 JavaScript 层的接口，用于：
 * 1. 检查通知监听权限状态
 * 2. 请求通知监听权限
 * 3. 接收支付通知事件
 * 
 * 使用方式（JavaScript）：
 * ```javascript
 * import { NativeModules, NativeEventEmitter } from 'react-native';
 * 
 * const { PaymentNotificationModule } = NativeModules;
 * const eventEmitter = new NativeEventEmitter(PaymentNotificationModule);
 * 
 * // 检查权限
 * const status = await PaymentNotificationModule.getPermissionStatus();
 * 
 * // 请求权限
 * PaymentNotificationModule.requestPermission();
 * 
 * // 监听支付事件
 * eventEmitter.addListener('onPaymentDetected', (event) => {
 *   console.log('Payment detected:', event);
 * });
 * ```
 * 
 * @author JotBill
 * @since 1.0.0
 */
class PaymentNotificationModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    companion object {
        private const val TAG = "PaymentNotificationModule"

        /** 模块名称，JavaScript 中通过此名称访问 */
        const val MODULE_NAME = "PaymentNotificationModule"

        /** 权限状态：已授权 */
        const val PERMISSION_AUTHORIZED = "authorized"
        
        /** 权限状态：未授权 */
        const val PERMISSION_DENIED = "denied"
        
        /** 权限状态：未知 */
        const val PERMISSION_UNKNOWN = "unknown"
    }

    init {
        reactContext.addLifecycleEventListener(this)
    }

    override fun getName(): String = MODULE_NAME

    /**
     * 获取通知监听权限状态
     * 
     * @param promise Promise 对象，返回权限状态字符串
     */
    @ReactMethod
    fun getPermissionStatus(promise: Promise) {
        try {
            val status = checkNotificationListenerPermission()
            promise.resolve(status)
        } catch (e: Exception) {
            Log.e(TAG, "检查权限状态失败", e)
            promise.reject("ERROR", "检查权限状态失败", e)
        }
    }

    /**
     * 请求通知监听权限
     * 
     * 打开系统设置页面，让用户手动授权通知访问权限
     */
    @ReactMethod
    fun requestPermission() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
            Log.i(TAG, "已打开通知监听权限设置页面")
        } catch (e: Exception) {
            Log.e(TAG, "打开通知监听权限设置页面失败", e)
        }
    }

    /**
     * 检查服务是否正在运行
     * 
     * @param promise Promise 对象，返回布尔值
     */
    @ReactMethod
    fun isServiceRunning(promise: Promise) {
        try {
            val isRunning = checkNotificationListenerPermission() == PERMISSION_AUTHORIZED
            promise.resolve(isRunning)
        } catch (e: Exception) {
            Log.e(TAG, "检查服务状态失败", e)
            promise.reject("ERROR", "检查服务状态失败", e)
        }
    }

    /**
     * 检查悬浮窗权限状态
     * 
     * @param promise Promise 对象，返回权限状态字符串
     */
    @ReactMethod
    fun getOverlayPermissionStatus(promise: Promise) {
        try {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(reactContext)
            } else {
                true
            }
            promise.resolve(if (hasPermission) PERMISSION_AUTHORIZED else PERMISSION_DENIED)
        } catch (e: Exception) {
            Log.e(TAG, "检查悬浮窗权限失败", e)
            promise.reject("ERROR", "检查悬浮窗权限失败", e)
        }
    }

    /**
     * 请求悬浮窗权限
     * 
     * 打开系统设置页面，让用户手动授权悬浮窗权限
     */
    @ReactMethod
    fun requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${reactContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(intent)
                Log.i(TAG, "已打开悬浮窗权限设置页面")
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开悬浮窗权限设置页面失败", e)
        }
    }

    /**
     * ⚠️ 2026-10-12 删除三个空壳 `@ReactMethod`：JS 侧零调用方。
     *   · `getDeviceManufacturer` —— 无人问设备厂商；
     *   · `startListening` / `stopListening` —— 函数体各只有一行日志，
     *     自述"D-20 后不再注册任何接收器"。它们保留至今只是"接口形状"，
     *     而没有 JS 调用方时，形状本身不构成兼容性：删掉后 JS 侧本来也没人调。
     */

    /**
     * React Native 新版事件发射器要求模块提供这两个订阅计数方法。
     *
     * `App.tsx` 用 `new NativeEventEmitter(PaymentNotificationModule)` 订阅
     * `onBillCreated`（弹窗/通知落单后刷新账单缓存），缺少这两个方法时 RN
     * 每次冷启动都会打印：
     *   `new NativeEventEmitter() was called with a non-null argument
     *    without the required addListener method.`
     *
     * Android 上事件经 RCTDeviceEventEmitter 投递，功能不受影响（AiModule /
     * DataModule / RecognitionModule / OverlayModule 早已按此约定实现），
     * 但这是真实的契约缺口，新架构下要求更严，故对齐。
     */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    /**
     * 获取已安装应用列表
     *
     * @param promise Promise 对象，返回应用列表
     */
    @ReactMethod
    fun getInstalledApps(promise: Promise) {
        try {
            val pm = reactContext.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList: WritableArray = Arguments.createArray()

            for (app in packages) {
                val appInfo = Arguments.createMap().apply {
                    putString("packageName", app.packageName)
                    putString("appName", app.loadLabel(pm).toString())
                }
                appList.pushMap(appInfo)
            }

            promise.resolve(appList)
            Log.i(TAG, "已获取 ${appList.size()} 个已安装应用")
        } catch (e: Exception) {
            Log.e(TAG, "获取已安装应用失败", e)
            promise.reject("ERROR", "获取已安装应用失败", e)
        }
    }

    /**
     * 检查通知监听权限
     *
     * @return 权限状态字符串
     */
    private fun checkNotificationListenerPermission(): String {
        return try {
            val packageName = reactContext.packageName
            val enabledListeners = Settings.Secure.getString(
                reactContext.contentResolver,
                "enabled_notification_listeners"
            )
            
            when {
                enabledListeners == null -> PERMISSION_UNKNOWN
                enabledListeners.contains(packageName) -> PERMISSION_AUTHORIZED
                else -> PERMISSION_DENIED
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查通知监听权限失败", e)
            PERMISSION_UNKNOWN
        }
    }

    // LifecycleEventListener 实现
    //
    // D-20：账单创建广播链路已删除（旧 PaymentOverlayManager 是 ACTION_BILL_CREATED
    // 的唯一发送方，其类内发送点随整文件删除）。生命周期回调保留为接口实现，不承载行为；
    // 「确认入账 → 前端缓存刷新」现由 sharedJsEventEmitter 直接推送 onBillCreated 承担。

    override fun onHostResume() = Unit

    override fun onHostPause() = Unit

    override fun onHostDestroy() = Unit
}
