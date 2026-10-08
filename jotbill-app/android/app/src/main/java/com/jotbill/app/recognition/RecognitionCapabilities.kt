package com.jotbill.app.recognition

import android.content.Context
import com.jotbill.app.utils.JotBillLog

/**
 * 识别能力开关（精简版改造 · 阶段一：只加开关，不删代码）。
 *
 * **存在意义**：把"要不要跑结算页截图识别链路"从代码结构里抽成一个可运行时翻转的
 * 布尔量，使后续阶段可以先验证行为、再决定删哪些代码。默认 true —— 完整版的既有
 * 行为原样保留，未改动过偏好的安装不会被静默关掉识别链路。
 *
 * **为什么持久化在 SharedPreferences 而不是只放内存**：无障碍服务可以在 RN 进程
 * 未启动时被系统拉起，能力开关必须跨进程重启存活，否则每次服务重连都会回到默认
 * 值，用户在设置页关掉的链路会自己复活。
 *
 * **热路径不读盘**：无障碍事件每帧都会经过门禁判定，同步读 SharedPreferences 会把
 * 每一次节点树采集都变成一次磁盘访问。热路径只读 [checkoutCaptureEnabled] 这个
 * `@Volatile` 字段，持久值仅在 [load] 与 [setCheckoutCaptureEnabled] 写入内存镜像。
 */
internal object RecognitionCapabilities {
    private const val TAG = "RecognitionCapabilities"
    private const val PREFS_NAME = "recognition_capabilities"
    private const val KEY_CHECKOUT_CAPTURE_ENABLED = "checkout_capture_enabled"

    /**
     * 默认开启。
     *
     * 与 [PendingConfirmOverlayManager.isEnabled] 取同一个默认值方向（均为 true），
     * 避免出现"弹窗默认开、识别链路默认关"这种用户看不懂的半开状态。
     */
    private const val DEFAULT_CHECKOUT_CAPTURE_ENABLED = true

    /**
     * 结算页截图识别链路开关。false = 不做稳定帧监测/截图/AI 派发。
     *
     * 关闭后仍然保留（**不得**一并关掉）：
     * - 支付结果页文本解析（`PaymentResultParser`）—— 付款确认弹窗的唯一数据来源；
     * - `PendingConfirmOverlayManager.onObservationState` 喂入的敏感帧判定 —— 弹窗门禁依据；
     * - `FeeExtractor` / `RefundLinker` / `TransferClassifier` 等直发 `PendingChangeHub` 的分类器。
     */
    @Volatile
    var checkoutCaptureEnabled: Boolean = DEFAULT_CHECKOUT_CAPTURE_ENABLED

    /** 从持久化偏好刷新内存镜像；进程启动与无障碍服务连接时各调一次。 */
    @Synchronized
    fun load(context: Context) {
        checkoutCaptureEnabled = readPersisted(context)
    }

    /**
     * 写入开关并立即刷新内存镜像。
     *
     * 偏好写失败（磁盘满/存储不可用）不抛异常：内存镜像已经更新，本次进程内行为
     * 仍然正确，只是重启后会回到偏好里的旧值 —— 记日志即可，不阻断设置页操作。
     */
    @Synchronized
    fun setCheckoutCaptureEnabled(context: Context, enabled: Boolean) {
        checkoutCaptureEnabled = enabled
        try {
            preferences(context).edit().putBoolean(KEY_CHECKOUT_CAPTURE_ENABLED, enabled).apply()
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "写入结算页截图识别链路开关失败(本次仅进程内生效)", error)
        }
        JotBillLog.i(TAG, "结算页截图识别链路开关: $enabled")
    }

    /** 设置页回读用；读偏好而非内存镜像，避免写盘失败时页面显示与实际行为不符。 */
    fun isCheckoutCaptureEnabled(context: Context): Boolean = readPersisted(context)

    private fun readPersisted(context: Context): Boolean = try {
        preferences(context).getBoolean(KEY_CHECKOUT_CAPTURE_ENABLED, DEFAULT_CHECKOUT_CAPTURE_ENABLED)
    } catch (error: RuntimeException) {
        JotBillLog.w(TAG, "读取结算页截图识别链路开关失败，按默认值处理", error)
        DEFAULT_CHECKOUT_CAPTURE_ENABLED
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}