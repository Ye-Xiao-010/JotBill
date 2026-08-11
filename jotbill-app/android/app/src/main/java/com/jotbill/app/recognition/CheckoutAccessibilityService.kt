package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 只读观察购物应用窗口的无障碍服务。
 *
 * 服务不点击、不输入、不截图，也不根据包名伪造平台或商户；稳定结算页只通过观察回调交给后续层。
 */
class CheckoutAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CheckoutAccessibilityService"
        private const val STABLE_DELAY_MILLIS = 500L

        @Volatile
        private var observationListener: CheckoutObservationListener? = null

        /** 为后续截图/会话协调器设置稳定观察回调。传入 null 可解除引用。 */
        @JvmStatic
        fun setObservationListener(listener: CheckoutObservationListener?) {
            observationListener = listener
        }
    }

    private val pageDetector = CheckoutPageDetector()
    private var lastWindowPackage: String? = null
    private var lastWindowClass: String? = null
    private val stabilityCoordinator = CheckoutStabilityCoordinator(
        handler = Handler(Looper.getMainLooper()),
        stableDelayMillis = STABLE_DELAY_MILLIS,
        nowMillis = { System.currentTimeMillis() },
        onStable = { page -> dispatchEvent(CheckoutObservationEvent.Stable(page)) },
        onInvalidated = { detail -> dispatchEvent(CheckoutObservationEvent.Invalidated(detail)) }
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        val configuredInfo = serviceInfo ?: AccessibilityServiceInfo()
        configuredInfo.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        configuredInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        configuredInfo.notificationTimeout = 100L
        configuredInfo.flags = configuredInfo.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // 不在 XML 中限定包名，才能观察离开购物应用的事件并清理旧页面状态；代码仍会先做白名单资格判断。
        configuredInfo.packageNames = null
        serviceInfo = configuredInfo
        Log.i(TAG, "结算页无障碍观察服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }

        val shoppingPackage = event.packageName?.toString()?.trim().orEmpty()
        if (!pageDetector.supportsShoppingPackage(shoppingPackage)) {
            lastWindowPackage = null
            lastWindowClass = null
            stabilityCoordinator.invalidate(
                reason = CheckoutInvalidationReason.LEFT_SUPPORTED_SHOPPING_APP,
                shoppingPackage = shoppingPackage.ifBlank { null }
            )
            return
        }

        val eventWindowClass = event.className?.toString()?.trim().orEmpty()
        if (lastWindowPackage != shoppingPackage) {
            lastWindowPackage = shoppingPackage
            lastWindowClass = eventWindowClass.ifBlank { null }
        } else if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventWindowClass.isNotBlank()
        ) {
            lastWindowClass = eventWindowClass
        }

        val root = rootInActiveWindow ?: event.source
        if (root == null) {
            stabilityCoordinator.invalidate(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
            return
        }

        try {
            val rootPackage = root.packageName?.toString()?.trim().orEmpty()
            if (rootPackage.isNotBlank() && rootPackage != shoppingPackage) {
                stabilityCoordinator.invalidate(
                    reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                    shoppingPackage = shoppingPackage
                )
                return
            }

            val observation = AccessibilityPageSummaryCollector.collect(
                shoppingPackage = shoppingPackage,
                windowClass = lastWindowClass
                    ?: root.className?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: eventWindowClass.ifBlank { null },
                root = root,
                observedAt = System.currentTimeMillis()
            )
            when (val detection = pageDetector.detect(observation)) {
                is CheckoutPageDetection.Accepted -> {
                    stabilityCoordinator.observe(detection.observation)
                }

                is CheckoutPageDetection.Rejected -> {
                    stabilityCoordinator.invalidate(
                        reason = invalidationReason(observation, detection.reason),
                        shoppingPackage = shoppingPackage,
                        pageFingerprint = observation.pageFingerprint
                    )
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "读取无障碍节点摘要失败", error)
            stabilityCoordinator.invalidate(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
        } finally {
            try {
                root.recycle()
            } catch (_: RuntimeException) {
                // 系统节点已经回收时无需再次处理。
            }
        }
    }

    override fun onInterrupt() {
        lastWindowPackage = null
        lastWindowClass = null
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        Log.w(TAG, "结算页无障碍观察服务被中断")
        super.onInterrupt()
    }

    override fun onDestroy() {
        lastWindowPackage = null
        lastWindowClass = null
        stabilityCoordinator.dispose()
        setObservationListener(null)
        Log.i(TAG, "结算页无障碍观察服务已销毁")
        super.onDestroy()
    }

    private fun invalidationReason(
        observation: CheckoutPageObservation,
        rejection: CheckoutDetectionRejection
    ): CheckoutInvalidationReason = when {
        observation.isSensitivePage || rejection == CheckoutDetectionRejection.SENSITIVE_PAGE ->
            CheckoutInvalidationReason.SENSITIVE_PAGE
        rejection == CheckoutDetectionRejection.UNSUPPORTED_SHOPPING_PACKAGE ->
            CheckoutInvalidationReason.LEFT_SUPPORTED_SHOPPING_APP
        else -> CheckoutInvalidationReason.NON_CHECKOUT_PAGE
    }

    private fun dispatchEvent(event: CheckoutObservationEvent) {
        val listener = observationListener ?: return
        try {
            listener.onEvent(event)
        } catch (error: RuntimeException) {
            Log.w(TAG, "分发结算页观察事件失败", error)
        }
    }
}

/**
 * 在主线程上等待页面无新事件后再确认稳定状态。
 *
 * 该协调器只以页面指纹和时间作为去抖依据，不使用无障碍事件次数。
 */
internal class CheckoutStabilityCoordinator(
    private val handler: Handler,
    private val stableDelayMillis: Long,
    private val nowMillis: () -> Long,
    private val onStable: (StableCheckoutPage) -> Unit,
    private val onInvalidated: (CheckoutObservationInvalidation) -> Unit
) {
    private var pendingObservation: CheckoutPageObservation? = null
    private var lastStableFingerprint: String? = null
    private var activePackage: String? = null
    private var activeFingerprint: String? = null
    private var hasActiveCheckout: Boolean = false
    private var disposed: Boolean = false

    private val stableRunnable = Runnable {
        val observation = pendingObservation ?: return@Runnable
        pendingObservation = null
        if (!hasActiveCheckout || observation.pageFingerprint == lastStableFingerprint) {
            return@Runnable
        }

        lastStableFingerprint = observation.pageFingerprint
        onStable(
            StableCheckoutPage(
                observation = observation,
                stableAt = nowMillis()
            )
        )
    }

    init {
        require(stableDelayMillis in 300L..800L) { "稳定窗口必须在 300~800ms 内" }
    }

    fun observe(observation: CheckoutPageObservation) {
        if (disposed) {
            return
        }

        hasActiveCheckout = true
        activePackage = observation.shoppingPackage
        activeFingerprint = observation.pageFingerprint
        if (observation.pageFingerprint == lastStableFingerprint) {
            pendingObservation = null
            handler.removeCallbacks(stableRunnable)
            return
        }

        pendingObservation = observation
        handler.removeCallbacks(stableRunnable)
        handler.postDelayed(stableRunnable, stableDelayMillis)
    }

    fun invalidate(
        reason: CheckoutInvalidationReason,
        shoppingPackage: String? = null,
        pageFingerprint: String? = null
    ) {
        if (disposed || !hasActiveCheckout) {
            return
        }

        handler.removeCallbacks(stableRunnable)
        pendingObservation = null
        hasActiveCheckout = false
        val detail = CheckoutObservationInvalidation(
            reason = reason,
            shoppingPackage = activePackage ?: shoppingPackage,
            pageFingerprint = activeFingerprint ?: pageFingerprint,
            invalidatedAt = nowMillis()
        )
        activePackage = null
        activeFingerprint = null
        lastStableFingerprint = null
        onInvalidated(detail)
    }

    fun dispose() {
        if (disposed) {
            return
        }
        invalidate(CheckoutInvalidationReason.SERVICE_DESTROYED)
        handler.removeCallbacks(stableRunnable)
        pendingObservation = null
        disposed = true
    }
}
