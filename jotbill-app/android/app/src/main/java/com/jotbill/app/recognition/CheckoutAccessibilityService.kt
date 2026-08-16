package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jotbill.app.ai.CheckoutAiResultCallback
import com.jotbill.app.ai.CheckoutAiVisionClient
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.overlay.CheckoutCaptureOverlayManager
import com.jotbill.app.security.KeyStoreSecretStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 只读观察购物应用窗口的无障碍服务。
 *
 * 服务不点击、不输入、不处理支付确认，也不根据包名伪造平台或商户；稳定结算页只在门禁通过后
 * 交给本地截图和购买意图协调器。
 */
class CheckoutAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CheckoutAccessibilityService"

        /** 结果页文本提取时的敏感字段关键词：命中节点整体丢弃，不进入解析与哈希。 */
        private val SENSITIVE_TEXT_KEYWORDS = listOf(
            "密码", "支付密码", "付款码", "银行卡号", "银行卡", "卡号", "CVV", "cvv",
            "有效期", "身份证", "验证码", "安全码", "动态密码", "一次性密码", "CVV2", "CVC",
            "OTP"
        )
        private val SENSITIVE_ASCII_PATTERN = Regex("""(?i)\b(password|pin|otp)\b""")
        private const val STABLE_DELAY_MILLIS = 500L

        @Volatile
        private var observationListener: CheckoutObservationListener? = null

        @Volatile
        private var activeService: CheckoutAccessibilityService? = null

        /** 为后续截图/会话协调器设置稳定观察回调。传入 null 可解除引用。 */
        @JvmStatic
        fun setObservationListener(listener: CheckoutObservationListener?) {
            observationListener = listener
        }

        /** 供悬浮按钮或 RN bridge 请求当前稳定结算页的手动截图。 */
        @JvmStatic
        fun requestManualCapture(callback: SnapshotCaptureCallback) {
            val service = activeService
            if (service == null) {
                callback.onResult(
                    SnapshotCaptureResult(
                        status = SnapshotCaptureStatus.UNAVAILABLE,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reasonCode = SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
                    )
                )
                return
            }
            service.snapshotCaptureCoordinator?.requestManualCapture(callback)
                ?: callback.onResult(
                    SnapshotCaptureResult(
                        status = SnapshotCaptureStatus.UNAVAILABLE,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reasonCode = SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
                    )
                )
        }

        /** 设置后续协调器需要的自动/手动截图状态监听。 */
        @JvmStatic
        fun setSnapshotCaptureResultListener(listener: SnapshotCaptureResultListener?) {
            // 调用实例方法使用不同名称，避免 @JvmStatic 与实例方法产生相同 JVM 签名冲突。
            activeService?.setSnapshotCaptureResultListenerInternal(listener)
        }

        /** 设置购买意图结果消费者；Ready.snapshot 由消费者读取后负责 release。 */
        @JvmStatic
        fun setPurchaseIntentResultConsumer(consumer: PurchaseIntentResultCallback?) {
            // 调用实例方法使用不同名称，避免 @JvmStatic 与实例方法产生相同 JVM 签名冲突。
            activeService?.setPurchaseIntentResultConsumerInternal(consumer)
        }

        /** 设置结算页 AI 结果消费者；结果不包含图片，服务销毁时会断开旧回调。 */
        @JvmStatic
        fun setCheckoutAiResultCallback(callback: CheckoutAiResultCallback?) {
            activeService?.setAiResultCallback(callback)
        }

        /** 通用购买意图入口，供后续无障碍点击、通知服务和 RN bridge 调用。 */
        @JvmStatic
        fun requestPurchaseIntent(
            intent: PurchaseIntent,
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) {
            if (intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK) {
                // 通用入口没有结构化通知元数据，禁止绕过支付包名和金额门禁。
                notifyPurchaseIntentRejected(
                    intent,
                    PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
                    callback
                )
                return
            }
            val coordinator = activeService?.purchaseIntentCoordinator
            if (coordinator == null) {
                notifyPurchaseIntentUnavailable(intent, callback)
                return
            }
            coordinator.trigger(intent, pageFingerprint, callback)
        }

        @JvmStatic
        fun requestSubmitAction(
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) = requestPurchaseIntent(PurchaseIntent.SUBMIT_ACTION, pageFingerprint, callback)

        @JvmStatic
        fun requestPaymentAppHandoff(
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) = requestPurchaseIntent(PurchaseIntent.PAYMENT_APP_HANDOFF, pageFingerprint, callback)

        @JvmStatic
        fun requestPaymentPageVisible(
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) = requestPurchaseIntent(PurchaseIntent.PAYMENT_PAGE_VISIBLE, pageFingerprint, callback)

        @JvmStatic
        fun requestPaymentNotificationFallback(
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) {
            // 旧签名缺少支付包名和金额，保留 API 形状但禁止绕过结构化通知门禁。
            notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
                callback
            )
        }

        /**
         * 通知回退只允许复用当前真实稳定结算页的指纹；服务未连接或没有稳定页时直接拒绝。
         * 该入口是同进程的尽力接入，通知服务重建不会伪造页面上下文或恢复已释放的快照。
         */
        @JvmStatic
        fun requestPaymentNotificationFallbackSignal(
            signal: PaymentNotificationFallbackSignal,
            callback: PurchaseIntentResultCallback
        ) {
            val service = activeService
            if (service == null) {
                notifyPurchaseIntentUnavailable(
                    PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                    callback
                )
                return
            }
            service.requestPaymentNotificationFallbackForSignal(signal, callback)
        }

        @JvmStatic
        fun requestUserRetry(
            pageFingerprint: String?,
            callback: PurchaseIntentResultCallback
        ) = requestPurchaseIntent(PurchaseIntent.USER_RETRY, pageFingerprint, callback)

        /** 仅允许在当前安全购物页重新截图，并把结果回写到指定失败会话。 */
        @JvmStatic
        fun requestUserRetryForSession(
            sessionId: String,
            expectedShoppingPackage: String?,
            callback: PurchaseIntentResultCallback
        ) {
            val service = activeService
            if (service == null) {
                notifyPurchaseIntentUnavailable(PurchaseIntent.USER_RETRY, callback)
                return
            }
            if (sessionId.isBlank() || expectedShoppingPackage.isNullOrBlank()) {
                notifyPurchaseIntentRejected(
                    PurchaseIntent.USER_RETRY,
                    PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
                    callback
                )
                return
            }
            service.performUserRetryForSession(sessionId, expectedShoppingPackage, callback)
        }

        private fun notifyPurchaseIntentUnavailable(
            intent: PurchaseIntent,
            callback: PurchaseIntentResultCallback
        ) {
            Companion.notifyPurchaseIntentRejected(
                intent,
                PurchaseIntentGateRejection.SERVICE_NOT_CONNECTED,
                callback
            )
        }

        private fun notifyPurchaseIntentRejected(
            intent: PurchaseIntent,
            reason: PurchaseIntentGateRejection,
            callback: PurchaseIntentResultCallback
        ) {
            try {
                callback.onResult(
                    PurchaseIntentCoordinationResult.Rejected(
                        intent = intent,
                        gateReason = reason
                    )
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "购买意图不可用结果回调失败", error)
            }
        }
    }

    private val pageDetector = CheckoutPageDetector()
    private data class StableCheckoutKey(
        val shoppingPackage: String,
        val pageFingerprint: String
    )

    /** 支付结果异步任务使用的不可变交接上下文，不再回读可变的页面状态。 */
    private data class PaymentResultHandoffContext(
        val stableCheckout: StableCheckoutPage,
        val reference: CheckoutSnapshotReference?,
        val handoffAt: Long
    )

    private var lastWindowPackage: String? = null
    private var lastWindowClass: String? = null
    private var lastObservedEventLogAt = 0L
    private var lastPaymentWindowScanAt = 0L
    private var lastPaymentScreenshotFallbackAt = 0L
    @Volatile
    private var paymentScreenshotFallbackInFlight = false
    private val recentPaymentResultEventKeys = mutableMapOf<String, Long>()
    private val screenshotCallbackExecutor = Executor { runnable -> mainHandler.post(runnable) }
    @Volatile
    private var currentStableCheckout: StableCheckoutPage? = null
    @Volatile
    private var handoffStableCheckout: StableCheckoutPage? = null
    @Volatile
    private var handoffForegroundPackage: String? = null
    private var submitActionTriggeredKey: StableCheckoutKey? = null
    @Volatile
    private var serviceConnected = false
    private val stabilityCoordinator = CheckoutStabilityCoordinator(
        handler = Handler(Looper.getMainLooper()),
        stableDelayMillis = STABLE_DELAY_MILLIS,
        nowMillis = { System.currentTimeMillis() },
        onStable = { page ->
            currentStableCheckout = page
            handoffStableCheckout = null
            handoffForegroundPackage = null
            submitActionTriggeredKey = null
            dispatchEvent(CheckoutObservationEvent.Stable(page))
        },
        onInvalidated = { detail ->
            currentStableCheckout = null
            if (detail.reason != CheckoutInvalidationReason.PAYMENT_APP_HANDOFF) {
                handoffStableCheckout = null
                handoffForegroundPackage = null
            }
            submitActionTriggeredKey = null
            dispatchEvent(CheckoutObservationEvent.Invalidated(detail))
        }
    )
    private var snapshotCaptureCoordinator: CheckoutSnapshotCaptureCoordinator? = null
    @Volatile
    private var purchaseIntentCoordinator: CheckoutPurchaseIntentCoordinator? = null
    private var checkoutSnapshotBuffer: CheckoutSnapshotBuffer? = null
    private var checkoutCaptureOverlayManager: CheckoutCaptureOverlayManager? = null
    @Volatile
    private var userRetryCaptureContext: UserRetryCaptureContext? = null
    private var snapshotCaptureResultListener: SnapshotCaptureResultListener? = null
    @Volatile
    private var purchaseIntentResultConsumer: PurchaseIntentResultCallback? = null
    private val aiLifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var aiScope: CoroutineScope? = null
    private var aiVisionClient: CheckoutAiVisionClient? = null
    private val dispatchedAiRequestKeys = mutableSetOf<String>()
    private var aiResultCallback: CheckoutAiResultCallback? = null
    private var paymentMatcher: CheckoutPaymentMatcher? = null
    private var matchingScope: CoroutineScope? = null
    private val evidenceFallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeAiReferences = mutableMapOf<String, CheckoutSnapshotReference>()

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 重连时先结束旧观察旅程，避免旧指纹阻止新服务发出稳定事件。
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        userRetryCaptureContext = null
        val configuredInfo = serviceInfo ?: AccessibilityServiceInfo()
        configuredInfo.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        configuredInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN or
            AccessibilityServiceInfo.FEEDBACK_HAPTIC or
            AccessibilityServiceInfo.FEEDBACK_AUDIBLE or
            AccessibilityServiceInfo.FEEDBACK_VISUAL or
            AccessibilityServiceInfo.FEEDBACK_GENERIC or
            AccessibilityServiceInfo.FEEDBACK_BRAILLE
        configuredInfo.notificationTimeout = 100L
        configuredInfo.flags = configuredInfo.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        // 不在 XML 中限定包名，才能观察离开购物应用的事件并清理旧页面状态；代码仍会先做白名单资格判断。
        configuredInfo.packageNames = null
        serviceInfo = configuredInfo
        purchaseIntentCoordinator?.setReadyDispatcher(null)
        purchaseIntentCoordinator?.setResultConsumer(null)
        shutdownAiClient()
        purchaseIntentCoordinator?.close()
        snapshotCaptureCoordinator?.close()
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        checkoutCaptureOverlayManager?.close()
        val sharedBuffer = CheckoutSnapshotBuffer()
        checkoutSnapshotBuffer = sharedBuffer
        snapshotCaptureCoordinator = CheckoutSnapshotCaptureCoordinator(this, sharedBuffer)
        purchaseIntentCoordinator = CheckoutPurchaseIntentCoordinator(sharedBuffer)
        checkoutCaptureOverlayManager = CheckoutCaptureOverlayManager(this).also { manager ->
            manager.setManualCaptureRequestHandler { requestManualCaptureFromOverlay() }
        }
        activeService = this
        configureSnapshotCaptureResultListener()
        configurePurchaseIntentResultConsumer()
        initializePaymentMatcher()
        initializeAiClient()
        setObservationListener(snapshotCaptureCoordinator)
        serviceConnected = true
        Log.i(TAG, "结算页无障碍观察服务已连接")
    }

    /**
     * 在当前安全购物页重新截图，成功后才向桥接层报告新的 AI 请求。
     *
     * 命名为 perform* 以避免与 companion 的 @JvmStatic requestUserRetryForSession
     * 产生相同 JVM 签名（platform declaration clash）。
     */
    private fun performUserRetryForSession(
        sessionId: String,
        expectedShoppingPackage: String,
        callback: PurchaseIntentResultCallback
    ) {
        val normalizedShoppingPackage = expectedShoppingPackage.trim().takeIf { it.isNotEmpty() }
        val callbackDelivered = AtomicBoolean(false)
        val guardedCallback = PurchaseIntentResultCallback { result ->
            if (callbackDelivered.compareAndSet(false, true)) {
                try {
                    callback.onResult(result)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "失败会话重试结果回调失败", error)
                }
            }
        }
        val posted = mainHandler.post {
            val context = userRetryCaptureContext
            if (
                normalizedShoppingPackage == null ||
                context == null ||
                !context.isComplete() ||
                context.shoppingPackage != normalizedShoppingPackage ||
                (context.sessionId != null && context.sessionId != sessionId)
            ) {
                rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT)
                return@post
            }

            val boundContext = context.withSessionId(sessionId)
            userRetryCaptureContext = boundContext
            snapshotCaptureCoordinator?.setUserRetryContext(boundContext)
            purchaseIntentCoordinator?.setUserRetryContext(boundContext)

            val captureCoordinator = snapshotCaptureCoordinator
            if (captureCoordinator == null) {
                clearUserRetryCaptureContext()
                rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.SERVICE_NOT_CONNECTED)
                return@post
            }

            captureCoordinator.requestManualCapture { result ->
                val captureAccepted =
                    result.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE &&
                        result.status in setOf(
                            SnapshotCaptureStatus.STORED,
                            SnapshotCaptureStatus.DUPLICATE
                        ) &&
                        result.sessionId == sessionId &&
                        result.shoppingPackage == normalizedShoppingPackage &&
                        result.pageFingerprint == boundContext.pageFingerprint
                if (!captureAccepted) {
                    clearUserRetryCaptureContext()
                    rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.BUFFER_UNAVAILABLE)
                    return@requestManualCapture
                }

                val completedContext = boundContext.withSessionId(sessionId)
                userRetryCaptureContext = null
                purchaseIntentCoordinator?.setUserRetryContext(completedContext)
                val coordinator = purchaseIntentCoordinator
                if (coordinator == null) {
                    clearUserRetryCaptureContext()
                    rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.SERVICE_NOT_CONNECTED)
                    return@requestManualCapture
                }
                try {
                    coordinator.triggerUserRetry(completedContext, guardedCallback)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "失败会话重试触发失败", error)
                    rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.BUFFER_UNAVAILABLE)
                } finally {
                    snapshotCaptureCoordinator?.setUserRetryContext(null)
                    purchaseIntentCoordinator?.setUserRetryContext(null)
                }
            }
        }
        if (!posted) {
            rejectUserRetry(guardedCallback, PurchaseIntentGateRejection.SERVICE_NOT_CONNECTED)
        }
    }

    private fun rejectUserRetry(
        callback: PurchaseIntentResultCallback,
        reason: PurchaseIntentGateRejection
    ) {
        try {
            callback.onResult(
                PurchaseIntentCoordinationResult.Rejected(
                    intent = PurchaseIntent.USER_RETRY,
                    gateReason = reason
                )
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "失败会话重试结果回调失败", error)
        }
    }

    private fun logObservedEventSampled(
        shoppingPackage: String,
        event: AccessibilityEvent,
        eventPackage: String,
        activeRootPackage: String
    ) {
        val now = System.currentTimeMillis()
        if (now - lastObservedEventLogAt < 1_000L) {
            return
        }
        lastObservedEventLogAt = now
        Log.i(
            TAG,
            "无障碍事件采样: pkg=$shoppingPackage(eventPkg=${eventPackage.ifBlank { "null" }}, " +
                "rootPkg=${activeRootPackage.ifBlank { "null" }}), " +
                "type=${AccessibilityEvent.eventTypeToString(event.eventType)}, " +
                "class=${event.className}, contentChangeTypes=${event.contentChangeTypes}"
        )
    }

    /**
     * 用事件包名、活动窗口包名和最近已知前台包名推断当前应归属哪个购物/支付应用。
     *
     * 真机上的拼多多/微信页面会混入空包名或视频通话悬浮窗等第三方窗口事件；
     * 单看事件包名会把仍然停留在购物页的状态误判为离开，造成悬浮按钮高频闪烁。
     */
    private fun resolveEffectiveShoppingPackage(
        eventPackage: String,
        activeRootPackage: String
    ): String {
        if (eventPackage.isNotBlank()) {
            val eventIsKnown = pageDetector.supportsShoppingPackage(eventPackage) ||
                pageDetector.supportsPaymentPackage(eventPackage)
            val rootIsPayment = pageDetector.supportsPaymentPackage(activeRootPackage)
            if (eventIsKnown && !rootIsPayment) {
                // 离开支付/购物应用后，后台仍可能继续发出旧窗口事件（尤其微信
                // OrderHandlerUI/WxaLite 成功页）。若事件包是支付包、活动根窗口
                // 既不是支付包也不是购物包，说明支付页已经不在前台，不能把旧窗口
                // 当成当前支付结果页，否则会产生错误金额的待确认。
                val eventIsPayment = pageDetector.supportsPaymentPackage(eventPackage)
                val rootIsKnown = activeRootPackage.isBlank() ||
                    pageDetector.supportsShoppingPackage(activeRootPackage) ||
                    rootIsPayment
                if (eventIsPayment && !rootIsKnown) {
                    return activeRootPackage
                }
                return eventPackage
            }
            // 购物应用刚把前台交给微信/支付宝时，活动窗口已经是支付包，按支付包处理，
            // 保留结算页交接上下文，避免先被 NODE_TREE_UNAVAILABLE 清空。
            if (pageDetector.supportsShoppingPackage(eventPackage) && rootIsPayment) {
                return activeRootPackage
            }
            if (!eventIsKnown &&
                (pageDetector.supportsShoppingPackage(activeRootPackage) || rootIsPayment)
            ) {
                return activeRootPackage
            }
            return eventPackage
        }

        if (activeRootPackage.isNotBlank()) {
            return activeRootPackage
        }
        return lastWindowPackage.orEmpty()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceConnected || event == null) {
            return
        }

        // 部分购物页会发出 packageName 为空的内容变化事件（WebView/系统窗口）。
        // 空包名不代表已经离开购物应用；同时兼容视频通话悬浮窗等第三方窗口事件
        // 与购物应用窗口事件交错到达的真机场景，按活动窗口/事件包名推断归属。
        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        val activeRoot = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }
        val activeRootPackage = try {
            activeRoot?.packageName?.toString()?.trim().orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        val shoppingPackage = resolveEffectiveShoppingPackage(eventPackage, activeRootPackage)
        logObservedEventSampled(shoppingPackage, event, eventPackage, activeRootPackage)
        if (!pageDetector.supportsShoppingPackage(shoppingPackage)) {
            try {
                activeRoot?.recycle()
            } catch (_: RuntimeException) {
                // 系统节点已回收时无需再次处理。
            }
            clearUserRetryCaptureContext()
            val previousShoppingPackage = lastWindowPackage
            if (pageDetector.supportsPaymentPackage(shoppingPackage)) {
                if (currentStableCheckout != null) {
                    handoffStableCheckout = currentStableCheckout
                    handoffForegroundPackage = shoppingPackage
                } else if (handoffForegroundPackage != shoppingPackage) {
                    handoffStableCheckout = null
                    handoffForegroundPackage = null
                }
                // 阶段四 T3：在支付应用中观察支付结果页，提取结构化证据
                observePaymentResultIfApplicable(
                    event = event,
                    paymentPackage = shoppingPackage,
                    handoffContext = capturePaymentResultHandoffContext(shoppingPackage)
                )
            } else {
                handoffStableCheckout = null
                handoffForegroundPackage = null
            }
            lastWindowPackage = null
            lastWindowClass = null
            currentStableCheckout = null
            submitActionTriggeredKey = null
            val invalidationReason = if (pageDetector.supportsPaymentPackage(shoppingPackage)) {
                CheckoutInvalidationReason.PAYMENT_APP_HANDOFF
            } else {
                CheckoutInvalidationReason.LEFT_SUPPORTED_SHOPPING_APP
            }
            dispatchOverlayInvalidation(
                reason = invalidationReason,
                shoppingPackage = previousShoppingPackage,
                foregroundPackage = shoppingPackage.ifBlank { null }
            )
            stabilityCoordinator.invalidate(
                reason = invalidationReason,
                shoppingPackage = shoppingPackage.ifBlank { null },
                foregroundPackage = shoppingPackage.ifBlank { null }
            )
            return
        }

        // 回到购物应用即开始新的观察周期，不能让旧支付交接上下文跨旅程存活。
        handoffStableCheckout = null
        handoffForegroundPackage = null

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

        val eventSource = try {
            event.source
        } catch (_: RuntimeException) {
            null
        }
        val root = activeRoot ?: eventSource
        if (root == null) {
            clearUserRetryCaptureContext()
            dispatchOverlayInvalidation(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
            stabilityCoordinator.invalidate(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
            return
        }

        try {
            val rootPackage = root.packageName?.toString()?.trim().orEmpty()
            if (rootPackage.isNotBlank() && rootPackage != shoppingPackage) {
                if (pageDetector.supportsPaymentPackage(rootPackage)) {
                    // 购物应用窗口事件的根节点已经是微信/支付宝：这是提交订单后的
                    // 支付交接，不是节点树失效。保留稳定结算页引用并继续观察结果页。
                    if (currentStableCheckout != null) {
                        handoffStableCheckout = currentStableCheckout
                        handoffForegroundPackage = rootPackage
                    } else if (handoffForegroundPackage != rootPackage) {
                        handoffStableCheckout = null
                        handoffForegroundPackage = null
                    }
                    dispatchOverlayInvalidation(
                        reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                        shoppingPackage = shoppingPackage,
                        foregroundPackage = rootPackage
                    )
                    stabilityCoordinator.invalidate(
                        reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                        shoppingPackage = shoppingPackage,
                        foregroundPackage = rootPackage
                    )
                    observePaymentResultIfApplicable(
                        event = event,
                        paymentPackage = rootPackage,
                        handoffContext = capturePaymentResultHandoffContext(rootPackage)
                    )
                    return
                }

                clearUserRetryCaptureContext()
                dispatchOverlayInvalidation(
                    reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                    shoppingPackage = shoppingPackage
                )
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
                    clearUserRetryCaptureContext()
                    val stable = currentStableCheckout
                    val sameStableCheckout = stable?.let { stablePage ->
                        stablePage.observation.shoppingPackage == observation.shoppingPackage &&
                            stablePage.observation.pageFingerprint == observation.pageFingerprint &&
                            pageDetector.detect(stablePage.observation) is CheckoutPageDetection.Accepted
                    } == true

                    if (!sameStableCheckout && stable != null) {
                        currentStableCheckout = null
                        submitActionTriggeredKey = null
                    }
                    stabilityCoordinator.observe(detection.observation)
                    if (sameStableCheckout && shouldTriggerSubmitAction(observation, event, eventSource)) {
                        triggerSubmitAction(observation.pageFingerprint)
                    }
                }

                is CheckoutPageDetection.Rejected -> {
                    currentStableCheckout = null
                    handoffStableCheckout = null
                    handoffForegroundPackage = null
                    submitActionTriggeredKey = null
                    val reason = invalidationReason(observation, detection.reason)
                    val userRetryCaptureAvailable = if (reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
                        setUserRetryCaptureContext(observation)
                    } else {
                        clearUserRetryCaptureContext()
                        false
                    }
                    stabilityCoordinator.invalidate(
                        reason = reason,
                        shoppingPackage = shoppingPackage,
                        pageFingerprint = observation.pageFingerprint
                    )
                    if (reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
                        checkoutCaptureOverlayManager?.onSupportedShoppingNonCheckout(
                            shoppingPackage = observation.shoppingPackage,
                            pageFingerprint = observation.pageFingerprint,
                            userRetryCaptureAvailable = userRetryCaptureAvailable
                        )
                    } else if (reason == CheckoutInvalidationReason.SENSITIVE_PAGE) {
                        dispatchOverlayInvalidation(
                            reason = reason,
                            shoppingPackage = observation.shoppingPackage,
                            pageFingerprint = observation.pageFingerprint
                        )
                    }
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "读取无障碍节点摘要失败", error)
            dispatchOverlayInvalidation(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
            stabilityCoordinator.invalidate(
                reason = CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
                shoppingPackage = shoppingPackage
            )
            clearUserRetryCaptureContext()
        } finally {
            if (eventSource != null && eventSource !== root) {
                try {
                    eventSource.recycle()
                } catch (_: RuntimeException) {
                    // 系统节点已回收时无需再次处理。
                }
            }
            try {
                root.recycle()
            } catch (_: RuntimeException) {
                // 系统节点已经回收时无需再次处理。
            }
        }
    }

    /**
     * 系统要求中断无障碍反馈时，服务通常仍保持绑定，后续事件仍会继续到达。
     * 这里只结束当前观察旅程并收起悬浮按钮，不能把 serviceConnected、AI/匹配器、
     * RN 回调和 static activeService 一并拆除，否则 MIUI 等系统只发 onInterrupt
     * 而不重新回调 onServiceConnected 时，服务会变成“已绑定但永久不再处理事件”的僵尸。
     */
    override fun onInterrupt() {
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        clearUserRetryCaptureContext()
        snapshotCaptureCoordinator?.onServiceInterrupted()
        purchaseIntentCoordinator?.onServiceInterrupted()
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        checkoutCaptureOverlayManager?.onServiceInterrupted()
        Log.w(TAG, "结算页无障碍观察服务被中断")
        // AccessibilityService.onInterrupt() 是抽象方法，无法调用 super.onInterrupt()。
    }

    /** 真正解除绑定时才断开静态桥接；重新绑定由 onServiceConnected 完整重建。 */
    override fun onUnbind(intent: Intent?): Boolean {
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        clearUserRetryCaptureContext()
        if (activeService === this) {
            activeService = null
        }
        setObservationListener(null)
        Log.i(TAG, "结算页无障碍观察服务已解除绑定")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        clearUserRetryCaptureContext()
        purchaseIntentCoordinator?.setReadyDispatcher(null)
        purchaseIntentCoordinator?.setResultConsumer(null)
        setAiResultCallback(null)
        shutdownPaymentMatcher()
        shutdownAiClient()
        evidenceFallbackScope.cancel()
        stabilityCoordinator.dispose()
        purchaseIntentCoordinator?.close()
        snapshotCaptureCoordinator?.close()
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        checkoutCaptureOverlayManager?.close()
        purchaseIntentCoordinator = null
        snapshotCaptureCoordinator = null
        checkoutSnapshotBuffer = null
        checkoutCaptureOverlayManager = null
        snapshotCaptureResultListener = null
        purchaseIntentResultConsumer = null
        if (activeService === this) {
            activeService = null
        }
        setObservationListener(null)
        Log.i(TAG, "结算页无障碍观察服务已销毁")
        super.onDestroy()
    }

    /** 服务连接时创建与当前服务绑定的 AI 客户端，失败时保留无 AI 的安全路径。 */
    private fun initializeAiClient() {
        val client = try {
            CheckoutAiVisionClient(
                database = JotBillDatabaseProvider.get(applicationContext),
                secretStore = KeyStoreSecretStore(applicationContext)
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "结算页 AI 客户端初始化失败", error)
            null
        }

        if (client == null) {
            purchaseIntentCoordinator?.setReadyDispatcher(null)
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(aiLifecycleLock) {
            aiVisionClient = client
            aiScope = scope
            dispatchedAiRequestKeys.clear()
        }
        purchaseIntentCoordinator?.setReadyDispatcher(
            PurchaseIntentReadyDispatcher { ready -> dispatchReadyToAi(ready) }
        )
    }

    /** 创建独立的匹配作用域，即使 AI 配置不可用也能保存支付证据。 */
    private fun initializePaymentMatcher() {
        shutdownPaymentMatcher()
        val database = try {
            JotBillDatabaseProvider.get(applicationContext)
        } catch (error: RuntimeException) {
            Log.w(TAG, "支付匹配数据库初始化失败", error)
            null
        }
        if (database == null) {
            return
        }
        synchronized(aiLifecycleLock) {
            paymentMatcher = CheckoutPaymentMatcher(database)
            matchingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeAiReferences.clear()
        }
    }

    private fun shutdownPaymentMatcher() {
        val scope: CoroutineScope?
        synchronized(aiLifecycleLock) {
            scope = matchingScope
            matchingScope = null
            paymentMatcher = null
            activeAiReferences.clear()
        }
        scope?.cancel()
    }

    /** 中断或销毁时先断开资源引用，再取消网络和未启动的 Ready 任务。 */
    private fun shutdownAiClient() {
        var client: CheckoutAiVisionClient? = null
        var scope: CoroutineScope? = null
        synchronized(aiLifecycleLock) {
            client = aiVisionClient
            aiVisionClient = null
            scope = aiScope
            aiScope = null
            dispatchedAiRequestKeys.clear()
        }
        client?.close()
        scope?.cancel()
    }

    /** 将 Ready 异步交给 AI；返回 false 时所有权仍由协调器负责释放。 */
    private fun dispatchReadyToAi(ready: CheckoutAiTriggerResult.Ready): Boolean {
        val requestKey = ready.requestKey.value
        val client: CheckoutAiVisionClient
        val scope: CoroutineScope
        synchronized(aiLifecycleLock) {
            client = aiVisionClient ?: return false
            scope = aiScope ?: return false
            if (!scope.isActive || !dispatchedAiRequestKeys.add(requestKey)) {
                return false
            }
            activeAiReferences[ready.snapshot.sessionId] = ready.snapshot.reference()
        }

        val reference = ready.snapshot.reference()

        val clientStarted = AtomicBoolean(false)
        val handoffAccepted = AtomicBoolean(false)
        val releasedBeforeClient = AtomicBoolean(false)
        val releaseBeforeClient = {
            if (
                handoffAccepted.get() &&
                !clientStarted.get() &&
                releasedBeforeClient.compareAndSet(false, true)
            ) {
                ready.snapshot.release()
                synchronized(aiLifecycleLock) {
                    if (activeAiReferences[reference.sessionId] == reference) {
                        activeAiReferences.remove(reference.sessionId)
                    }
                }
            }
        }
        val job = try {
            scope.launch {
                clientStarted.set(true)
                var clientOwnsSnapshot = false
                try {
                    val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
                    if (matcher != null) {
                        try {
                            matcher.ensureSession(reference, ready.requestKey)
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "AI 会话预写入失败", error)
                        }
                    }
                    clientOwnsSnapshot = true
                    val result = client.recognize(ready)
                    if (matcher != null) {
                        try {
                            val matchResult = matcher.recordAiResult(result)
                            Log.i(TAG, "AI 结果匹配落库结果: ${formatMatchResult(matchResult)}")
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "AI 结果匹配写入失败", error)
                        }
                    }
                    notifyAiResult(result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "结算页 AI 任务异常", error)
                } finally {
                    if (!clientOwnsSnapshot) {
                        ready.snapshot.release()
                    }
                    synchronized(aiLifecycleLock) {
                        if (activeAiReferences[reference.sessionId] == reference) {
                            activeAiReferences.remove(reference.sessionId)
                        }
                    }
                }
            }
        } catch (error: RuntimeException) {
            synchronized(aiLifecycleLock) {
                dispatchedAiRequestKeys.remove(requestKey)
                if (activeAiReferences[reference.sessionId] == reference) {
                    activeAiReferences.remove(reference.sessionId)
                }
            }
            return false
        }
        job.invokeOnCompletion { _ ->
            // 协程在调用 recognize 前被取消，客户端尚未取得 Ready 所有权。
            releaseBeforeClient()
        }
        handoffAccepted.set(true)
        if (job.isCompleted) {
            // 完成回调可能早于 handoff 标记执行，补做一次无竞态的释放检查。
            releaseBeforeClient()
        }
        return true
    }

    private fun setAiResultCallback(callback: CheckoutAiResultCallback?) {
        synchronized(aiLifecycleLock) {
            aiResultCallback = callback
        }
    }

    /** 将截图结果同时交给悬浮按钮和现有监听者，避免外部注册覆盖按钮生命周期。 */
    private fun configureSnapshotCaptureResultListener() {
        snapshotCaptureCoordinator?.setResultListener { result ->
            checkoutCaptureOverlayManager?.onCaptureResult(result)
            val listener = snapshotCaptureResultListener ?: return@setResultListener
            try {
                listener.onResult(result)
            } catch (error: RuntimeException) {
                Log.w(TAG, "截图结果监听处理失败", error)
            }
        }
    }

    // 重命名为 Internal 后缀，避免与 Companion 中同名的 @JvmStatic 方法产生 JVM 签名冲突。
    private fun setSnapshotCaptureResultListenerInternal(listener: SnapshotCaptureResultListener?) {
        snapshotCaptureResultListener = listener
    }

    /** 保留原有购买意图回调，同时让悬浮按钮在快照交接后收起。 */
    private fun configurePurchaseIntentResultConsumer() {
        purchaseIntentCoordinator?.setResultConsumer { result ->
            when (result) {
                is PurchaseIntentCoordinationResult.AiDispatched,
                is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer ->
                    checkoutCaptureOverlayManager?.onAiDispatched()
                else -> Unit
            }
            val consumer = purchaseIntentResultConsumer ?: return@setResultConsumer
            try {
                consumer.onResult(result)
            } catch (error: RuntimeException) {
                Log.w(TAG, "购买意图结果监听处理失败", error)
            }
        }
    }

    // 重命名为 Internal 后缀，避免与 Companion 中同名的 @JvmStatic 方法产生 JVM 签名冲突。
    private fun setPurchaseIntentResultConsumerInternal(consumer: PurchaseIntentResultCallback?) {
        purchaseIntentResultConsumer = consumer
    }

    /**
     * 支付结果页节点树优先级：活动窗口 > 事件源节点 > 同包名应用窗口。
     * 微信支付成功页可能由透明 LiteApp/WebView 承载，活动窗口或事件源会短暂为空，
     * 此时从已检索窗口中按包名兜底，避免结果页因一次空树而永久漏记。
     */
    private fun findPaymentResultRoots(
        event: AccessibilityEvent,
        paymentPackage: String,
        includeWindows: Boolean
    ): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        fun add(candidate: AccessibilityNodeInfo?) {
            if (candidate == null || roots.any { it === candidate }) {
                return
            }
            roots += candidate
        }

        try {
            add(rootInActiveWindow)
        } catch (_: RuntimeException) {
            // 继续尝试事件源节点。
        }
        try {
            add(event.source)
        } catch (_: RuntimeException) {
            // 继续尝试窗口列表。
        }
        if (includeWindows && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                val inventory = mutableListOf<String>()
                windows.forEach { window ->
                    val rootPackage = try {
                        window.root?.packageName?.toString().orEmpty()
                    } catch (_: RuntimeException) {
                        ""
                    }
                    val rootClass = try {
                        window.root?.className?.toString().orEmpty()
                    } catch (_: RuntimeException) {
                        ""
                    }
                    inventory += "${window.type}:${rootPackage.ifBlank { "null" }}:${rootClass.substringAfterLast('.')}"
                    // 只取活动/聚焦窗口。此前扫描全部窗口会在离开微信后把后台旧成功页
                    // 再次解析成支付证据，产生错误金额的待确认。
                    val windowActive = try {
                        window.isActive || window.isFocused
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (windowActive) {
                        try {
                            add(window.root)
                        } catch (_: RuntimeException) {
                            // 单个窗口不可读时继续检查其它窗口。
                        }
                    }
                }
                Log.i(TAG, "支付窗口列表(${inventory.size}): ${inventory.joinToString(" | ")}")
            } catch (_: RuntimeException) {
                // 系统窗口列表不可读时保持原有空树语义。
            }
        }
        return roots
    }

    /**
     * 阶段四 T3：在支付应用中观察支付结果页，提取结构化证据。
     *
     * 只读取无障碍节点文本，不截图、不读密码/付款码/银行卡号。
     * 支付成功的结果生成 PaymentResultEvidence，交给匹配器落库。
     * 支付失败的结果不生成成功证据，只记录诊断日志。
     */
    private fun observePaymentResultIfApplicable(
        event: AccessibilityEvent,
        paymentPackage: String,
        handoffContext: PaymentResultHandoffContext?
    ) {
        val observedAt = System.currentTimeMillis()
        val includeWindows =
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                observedAt - lastPaymentWindowScanAt >= 1_500L
        if (includeWindows) {
            lastPaymentWindowScanAt = observedAt
        }
        val roots = findPaymentResultRoots(event, paymentPackage, includeWindows)
        if (roots.isEmpty()) {
            Log.i(
                TAG,
                "支付结果页无可用节点树: pkg=$paymentPackage, " +
                    "type=${AccessibilityEvent.eventTypeToString(event.eventType)}, class=${event.className}"
            )
            maybeCapturePaymentResultScreenshot(paymentPackage, handoffContext)
            return
        }

        var sawResultPage = false
        try {
            for (root in roots) {
                try {
                    val observation = AccessibilityPageSummaryCollector.collect(
                        shoppingPackage = paymentPackage,
                        windowClass = event.className?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        root = root,
                        observedAt = observedAt,
                        includeHiddenContentDescriptions = true
                    )

                    if (observation.pageRole == CheckoutPageRole.PAYMENT_RESULT) {
                        sawResultPage = true
                    }
                    if (observation.pageRole != CheckoutPageRole.PAYMENT_RESULT) {
                        val rootPackage = try {
                            root.packageName?.toString().orEmpty()
                        } catch (_: RuntimeException) {
                            ""
                        }
                        val rootClass = try {
                            root.className?.toString()?.substringAfterLast('.')
                        } catch (_: RuntimeException) {
                            ""
                        }
                        val pageTextLength = try {
                            collectPageText(root).length
                        } catch (_: RuntimeException) {
                            -1
                        }
                        Log.i(
                            TAG,
                            "支付包页面未分类为结果页: pkg=$paymentPackage, role=${observation.pageRole}, " +
                                "class=${observation.windowClass}, sensitive=${observation.isSensitivePage}, " +
                                "windows=${if (includeWindows) "all" else "active"}, " +
                                "rootPkg=${rootPackage.ifBlank { "null" }}, " +
                                "rootClass=${rootClass?.takeIf { it.isNotBlank() } ?: "null"}, " +
                                "children=${root.childCount}, pageTextLength=$pageTextLength"
                        )
                        continue
                    }

                    // 敏感页面（密码/付款码等）不提取任何证据
                    if (observation.isSensitivePage) {
                        Log.d(TAG, "支付结果页为敏感页面，跳过证据提取")
                        return
                    }

                    // 从节点文本和 contentDescription 提取结构化证据
                    val pageText = collectPageText(root)
                    val parseResult = PaymentResultParser.parseResult(
                        pageText = pageText,
                        sourcePackage = paymentPackage,
                        pageFingerprint = observation.pageFingerprint,
                        observedAt = observation.observedAt
                    )
                    val evidence = when (parseResult) {
                        is PaymentResultParseResult.Success -> parseResult.evidence
                        is PaymentResultParseResult.Failure -> {
                            Log.i(TAG, "支付结果页未生成成功证据: reason=${parseResult.reason}")
                            return
                        }
                        PaymentResultParseResult.NotPaymentResult -> {
                            Log.d(TAG, "支付结果页未命中可确认的结果语义")
                            continue
                        }
                    }

                    if (!markPaymentResultEvent(evidence.sourceEventKey, observation.observedAt)) {
                        Log.d(TAG, "支付结果页同一事件已处理，跳过重复解析: ${evidence.sourceEventKey.take(12)}")
                        return
                    }
                    Log.i(
                        TAG,
                        "支付结果页解析到证据: 金额=${evidence.amountMinor}分, 状态=${evidence.paymentStatus}, " +
                            "textLength=${pageText.length}"
                    )

                    // 匹配器尚未初始化时仍保留证据，避免结果页只记录日志后丢失。
                    val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
                        ?: run {
                            val database = try {
                                JotBillDatabaseProvider.get(applicationContext)
                            } catch (error: RuntimeException) {
                                Log.w(TAG, "支付结果页无法初始化证据数据库", error)
                                return
                            }
                            CheckoutPaymentMatcher(database)
                        }
                    val scope = synchronized(aiLifecycleLock) { matchingScope } ?: evidenceFallbackScope
                    scope.launch {
                        try {
                            recordPaymentResultEvidence(
                                matcher = matcher,
                                paymentPackage = paymentPackage,
                                evidence = evidence,
                                handoffContext = handoffContext
                            )
                        } catch (error: Exception) {
                            Log.w(TAG, "支付结果页证据落库失败", error)
                            try {
                                matcher.recordOrphanPaymentSignal(
                                    PaymentNotificationFallbackSignal(
                                        paymentPackage = paymentPackage,
                                        amountMinor = evidence.amountMinor,
                                        occurredAt = evidence.occurredAt ?: System.currentTimeMillis(),
                                        merchant = evidence.merchant,
                                        orderId = evidence.orderId,
                                        sourceEventKey = evidence.sourceEventKey,
                                        rawTextHash = evidence.rawTextHash
                                    ),
                                    sourceType = "ACCESSIBILITY",
                                    conflictReason = "EVIDENCE_PERSIST_FAILED"
                                )
                            } catch (fallbackError: Exception) {
                                Log.w(TAG, "支付结果页证据兜底落库失败", fallbackError)
                            }
                        }
                    }
                    return
                } catch (error: RuntimeException) {
                    Log.w(TAG, "支付结果页候选根节点解析失败，继续检查下一窗口", error)
                }
            }
        } finally {
            roots.forEach { root ->
                try {
                    root.recycle()
                } catch (_: RuntimeException) {
                    // 系统节点已经回收时无需再次处理。
                }
            }
        }
        if (!sawResultPage) {
            maybeCapturePaymentResultScreenshot(paymentPackage, handoffContext)
        }
    }
    /** 微信 LiteApp 成功页对无障碍服务暴露空树时，用系统截图 + AI 视觉兜底提取证据。 */
    private fun maybeCapturePaymentResultScreenshot(
        paymentPackage: String,
        handoffContext: PaymentResultHandoffContext?
    ) {
        val now = System.currentTimeMillis()
        if (
            paymentScreenshotFallbackInFlight ||
            now - lastPaymentScreenshotFallbackAt < 15_000L
        ) {
            return
        }
        val client = synchronized(aiLifecycleLock) { aiVisionClient }
        if (client == null) {
            Log.i(TAG, "支付结果页截图兜底未启用: AI 客户端不可用")
            return
        }
        lastPaymentScreenshotFallbackAt = now
        paymentScreenshotFallbackInFlight = true
        try {
            @Suppress("NewApi")
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotCallbackExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        paymentScreenshotFallbackInFlight = false
                        val bytes = encodePaymentScreenshot(screenshot)
                        if (bytes == null || bytes.isEmpty()) {
                            bytes?.fill(0)
                            Log.w(TAG, "支付结果页截图兜底编码失败")
                            return
                        }
                        Log.i(TAG, "支付结果页截图兜底已取得: ${bytes.size} bytes")
                        evidenceFallbackScope.launch {
                            consumePaymentResultScreenshot(client, bytes, paymentPackage, handoffContext)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        paymentScreenshotFallbackInFlight = false
                        Log.w(TAG, "支付结果页截图兜底失败: errorCode=$errorCode")
                    }
                }
            )
        } catch (error: RuntimeException) {
            paymentScreenshotFallbackInFlight = false
            Log.w(TAG, "支付结果页截图兜底调用失败", error)
        }
    }

    private suspend fun consumePaymentResultScreenshot(
        client: CheckoutAiVisionClient,
        imageBytes: ByteArray,
        paymentPackage: String,
        handoffContext: PaymentResultHandoffContext?
    ) {
        val requestSourceKey = sha256(
            "payment-result-screenshot|$paymentPackage|${System.currentTimeMillis() / 60_000L}"
        )
        val result = client.recognizePaymentResult(imageBytes, requestSourceKey)
        if (result !is CheckoutAiVisionClient.PaymentResultVisionResult.Succeeded) {
            val failed = result as CheckoutAiVisionClient.PaymentResultVisionResult.Failed
            Log.i(
                TAG,
                "支付结果页截图兜底未生成证据: code=${failed.code}, attempts=${failed.attempts}, " +
                    "httpStatus=${failed.httpStatus}"
            )
            return
        }
        val fields = result.fields
        val occurredAt = fields.occurredAt?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        } ?: System.currentTimeMillis()
        // 与无障碍结果页共用同一个去重键：同包名、同一分钟、同金额、同订单号只落一次。
        val sourceEventKey = sha256(
            "result-event|$paymentPackage|${occurredAt / 60_000L}|${fields.amountMinor}|" +
                "${fields.orderId.orEmpty()}"
        )
        if (!markPaymentResultEvent(sourceEventKey, System.currentTimeMillis())) {
            Log.d(TAG, "支付结果页截图兜底同一事件已处理，跳过重复落库")
            return
        }
        val evidence = PaymentResultEvidence(
            amountMinor = fields.amountMinor,
            merchant = fields.merchant,
            orderId = fields.orderId,
            paymentStatus = "SUCCESS",
            occurredAt = occurredAt,
            rawTextHash = sha256(
                "payment-result-screenshot|$paymentPackage|${fields.amountMinor}|" +
                    "${fields.merchant.orEmpty()}|${fields.orderId.orEmpty()}"
            ),
            sourceEventKey = sourceEventKey
        )
        Log.i(TAG, "支付结果页截图兜底解析到证据: 金额=${evidence.amountMinor}分")
        val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
            ?: run {
                val database = JotBillDatabaseProvider.get(applicationContext)
                CheckoutPaymentMatcher(database)
            }
        try {
            recordPaymentResultEvidence(
                matcher = matcher,
                paymentPackage = paymentPackage,
                evidence = evidence,
                handoffContext = handoffContext
            )
        } catch (error: Exception) {
            Log.w(TAG, "支付结果页截图兜底证据落库失败", error)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun markPaymentResultEvent(sourceEventKey: String, now: Long): Boolean {
        recentPaymentResultEventKeys.entries.removeAll { now - it.value > 60_000L }
        val previous = recentPaymentResultEventKeys[sourceEventKey]
        if (previous != null) {
            return false
        }
        recentPaymentResultEventKeys[sourceEventKey] = now
        return true
    }

    private fun encodePaymentScreenshot(screenshot: ScreenshotResult): ByteArray? {
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var transferred = false
        var compressed: ByteArray? = null
        try {
            val capturedBuffer = screenshot.hardwareBuffer ?: return null
            hardwareBuffer = capturedBuffer
            val capturedHardwareBitmap = Bitmap.wrapHardwareBuffer(capturedBuffer, screenshot.colorSpace)
                ?: return null
            hardwareBitmap = capturedHardwareBitmap
            val capturedSoftwareBitmap = capturedHardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return null
            softwareBitmap = capturedSoftwareBitmap
            val longestEdge = maxOf(capturedSoftwareBitmap.width, capturedSoftwareBitmap.height)
            val bitmapToEncode = if (longestEdge > 1080) {
                val scale = 1080f / longestEdge.toFloat()
                Bitmap.createScaledBitmap(
                    capturedSoftwareBitmap,
                    (capturedSoftwareBitmap.width * scale).toInt().coerceAtLeast(1),
                    (capturedSoftwareBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { scaledBitmap = it }
            } else {
                capturedSoftwareBitmap
            }
            val output = ByteArrayOutputStream()
            if (!bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                return null
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) {
                bytes.fill(0)
                return null
            }
            compressed = bytes
            transferred = true
            return bytes
        } catch (_: RuntimeException) {
            return null
        } finally {
            if (!transferred) {
                compressed?.fill(0)
            }
            try {
                scaledBitmap?.recycle()
            } finally {
                try {
                    softwareBitmap?.recycle()
                } finally {
                    try {
                        hardwareBitmap?.recycle()
                    } finally {
                        try {
                            hardwareBuffer?.close()
                        } catch (_: RuntimeException) {
                            // 系统缓冲区已关闭时无需再次处理。
                        }
                    }
                }
            }
        }
    }

    /** 收集节点树可见文本，用于支付结果页证据解析；数值在 parser 哈希前统一脱敏。 */
    private fun collectPageText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        collectNodeText(root, builder, depth = 0)
        return builder.toString()
    }

    /** 递归收集节点文本，深度限制避免过深遍历；含敏感关键词的文本节点直接丢弃。 */
    private fun collectNodeText(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        depth: Int,
        sensitiveAncestor: Boolean = false
    ) {
        if (depth > 20) return
        val nodeSensitive = sensitiveAncestor || isSensitiveNode(node)
        if (!nodeSensitive) {
            val candidates = listOfNotNull(
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
            )
            candidates.distinct().forEach { text ->
                if (
                    SENSITIVE_TEXT_KEYWORDS.none { text.contains(it, ignoreCase = true) } &&
                    !SENSITIVE_ASCII_PATTERN.containsMatchIn(text)
                ) {
                    builder.append(text).append(' ')
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: RuntimeException) {
                null
            } ?: continue
            try {
                collectNodeText(child, builder, depth + 1, nodeSensitive)
            } finally {
                try {
                    child.recycle()
                } catch (_: RuntimeException) {
                    // 系统节点已回收时无需再次处理。
                }
            }
        }
    }

    private fun isSensitiveNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        return listOf(className, viewId).any { value ->
            value.contains("password", ignoreCase = true) ||
                value.contains("verification", ignoreCase = true) ||
                value.contains("secure", ignoreCase = true) ||
                value.contains("pin", ignoreCase = true) ||
                value.contains("cvv", ignoreCase = true) ||
                value.contains("card", ignoreCase = true)
        }
    }

    /** 在支付结果事件所在主线程立即固定旧交接引用，避免后续新订单覆盖槽位后误关联。 */
    private fun capturePaymentResultHandoffContext(
        paymentPackage: String
    ): PaymentResultHandoffContext? {
        val stableCheckout = synchronized(this) {
            if (handoffForegroundPackage == paymentPackage) handoffStableCheckout else null
        } ?: return null
        val observation = stableCheckout.observation
        val slotId = checkoutSnapshotSlotId(observation.shoppingPackage)
        val reference = checkoutSnapshotBuffer?.findReference(
            slotId = slotId,
            pageFingerprint = observation.pageFingerprint
        ) ?: synchronized(aiLifecycleLock) {
            activeAiReferences.values
                .filter {
                    it.shoppingPackage == observation.shoppingPackage &&
                        it.pageFingerprint == observation.pageFingerprint
                }
                .singleOrNull()
        }
        return PaymentResultHandoffContext(
            stableCheckout = stableCheckout,
            reference = reference,
            handoffAt = System.currentTimeMillis()
        )
    }

    /**
     * 将支付结果页证据落库。
     *
     * 会话解析双路径（T4）：
     * 1. 优先用支付交接上下文（handoffStableCheckout）——提交订单后切到支付应用的场景；
     * 2. 否则用 resolveSessionForPayment 按订单号/金额+时间窗口综合解析；
     * 3. 并发会话无法区分时输出 Ambiguous，不强行合并（T6 创建并发候选）；
     * 4. 无任何匹配会话时转纯通知待确认，证据不丢弃。
     *
     * 证据来源固定标记为 ACCESSIBILITY（无障碍结果页），与通知（NOTIFICATION）区分。
     */
    private suspend fun recordPaymentResultEvidence(
        matcher: CheckoutPaymentMatcher,
        paymentPackage: String,
        evidence: PaymentResultEvidence,
        handoffContext: PaymentResultHandoffContext?
    ) {
        val signal = PaymentNotificationFallbackSignal(
            paymentPackage = paymentPackage,
            amountMinor = evidence.amountMinor,
            occurredAt = evidence.occurredAt ?: System.currentTimeMillis(),
            merchant = evidence.merchant,
            orderId = evidence.orderId,
            sourceEventKey = evidence.sourceEventKey,
            rawTextHash = evidence.rawTextHash
        )

        // 路径一：使用事件发生时固定的交接引用，禁止异步任务回读新订单槽位。
        if (handoffContext != null) {
            val handoffReference = handoffContext.reference ?: matcher.findReferenceForPayment(
                shoppingPackage = handoffContext.stableCheckout.observation.shoppingPackage,
                pageFingerprint = handoffContext.stableCheckout.observation.pageFingerprint,
                at = handoffContext.handoffAt
            )?.takeIf { reference -> reference.capturedAt <= handoffContext.handoffAt }
            if (handoffReference == null) {
                matcher.recordOrphanPaymentSignal(signal, sourceType = "ACCESSIBILITY")
                Log.i(TAG, "支付结果页交接引用已失效，证据已转待确认")
                return
            }
            val result = matcher.recordPaymentEvidence(
                handoffReference, signal, sourceType = "ACCESSIBILITY"
            )
            if (result is CheckoutPaymentMatchResult.Rejected) {
                matcher.recordOrphanPaymentSignal(
                    signal,
                    sourceType = "ACCESSIBILITY",
                    conflictReason = result.reason
                )
            }
            Log.i(TAG, "支付结果页证据落库结果（交接上下文）: ${formatMatchResult(result)}")
            return
        }

        // 路径二：订单号/金额+时间窗口综合解析
        when (val resolution = matcher.resolveSessionForPayment(
            paymentPackage = paymentPackage,
            amountMinor = evidence.amountMinor,
            occurredAt = evidence.occurredAt ?: System.currentTimeMillis(),
            orderId = evidence.orderId
        )) {
            is PaymentSessionResolution.Matched -> {
                val result = matcher.recordPaymentEvidence(
                    resolution.reference, signal, sourceType = "ACCESSIBILITY"
                )
                if (result is CheckoutPaymentMatchResult.Rejected) {
                    matcher.recordOrphanPaymentSignal(
                        signal,
                        sourceType = "ACCESSIBILITY",
                        conflictReason = result.reason
                    )
                }
                Log.i(
                    TAG,
                    "支付结果页证据落库结果（" +
                        (if (resolution.byOrderId) "订单号" else "金额+时间窗口") +
                        "）: ${formatMatchResult(result)}"
                )
            }
            is PaymentSessionResolution.Ambiguous -> {
                // 并发会话无法区分：不强行合并，保留证据交给已有待确认兜底。
                matcher.recordOrphanPaymentSignal(
                    signal,
                    sourceType = "ACCESSIBILITY",
                    conflictReason = "AMBIGUOUS_SESSION_IDS:${resolution.sessionIds.joinToString(",")}",
                    sessionIds = resolution.sessionIds
                )
                Log.i(TAG, "支付结果页命中并发会话(${resolution.sessionIds.size}个)，已转待确认")
            }
            PaymentSessionResolution.NoMatch -> {
                // 无关联会话：证据不丢弃，转纯通知待确认（sessionId 为空，sourceType=accessibility）
                matcher.recordOrphanPaymentSignal(signal, sourceType = "ACCESSIBILITY")
                Log.i(TAG, "支付结果页无关联活跃会话，已转纯通知待确认")
            }
        }
    }

    /** 格式化匹配决策结果用于日志，billId 非空表示阶段四 T5 已自动入账。 */
    private fun formatMatchResult(result: CheckoutPaymentMatchResult): String = when (result) {
        is CheckoutPaymentMatchResult.Accepted ->
            "Accepted(status=${result.status}, matched=${result.matched}" +
                ", candidateId=${result.candidateId}, evidenceId=${result.evidenceId}" +
                (result.billId?.let { ", billId=$it（已自动入账）" } ?: "") + ")"
        is CheckoutPaymentMatchResult.Rejected ->
            "Rejected(sessionId=${result.sessionId}, reason=${result.reason})"
    }

    /**
     * 通知服务只能提供结构化支付元数据，页面指纹必须从当前稳定观察中读取，不能由通知推测。
     */
    private fun requestPaymentNotificationFallbackForSignal(
        signal: PaymentNotificationFallbackSignal,
        callback: PurchaseIntentResultCallback
    ) {
        if (
            !serviceConnected ||
            !signal.isValid() ||
            !pageDetector.supportsPaymentPackage(signal.paymentPackage)
        ) {
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
                callback
            )
            return
        }

        if (
            handoffStableCheckout != null &&
            handoffForegroundPackage != signal.paymentPackage
        ) {
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
                callback
            )
            return
        }

        val observation = (currentStableCheckout ?: handoffStableCheckout)?.observation
        if (
            observation == null ||
            pageDetector.detect(observation) !is CheckoutPageDetection.Accepted ||
            observation.isSensitivePage
        ) {
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                if (observation?.isSensitivePage == true) {
                    PurchaseIntentGateRejection.SENSITIVE_PAGE
                } else {
                    PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT
                },
                callback
            )
            return
        }

        val coordinator = purchaseIntentCoordinator
        val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
            ?: run {
                val database = try {
                    JotBillDatabaseProvider.get(applicationContext)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "支付通知无法初始化证据数据库", error)
                    null
                }
                database?.let(::CheckoutPaymentMatcher)
            }
        if (matcher == null) {
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
                callback
            )
            return
        }
        val matchingScope = synchronized(aiLifecycleLock) { matchingScope }
            ?: evidenceFallbackScope
        val snapshotReference = checkoutSnapshotBuffer?.findReference(
            slotId = checkoutSnapshotSlotId(observation.shoppingPackage),
            pageFingerprint = observation.pageFingerprint
        )
        matchingScope.launch {
            try {
                val reference = snapshotReference
                    ?: synchronized(aiLifecycleLock) {
                        val now = System.currentTimeMillis()
                        activeAiReferences.values
                            .filter {
                                it.shoppingPackage == observation.shoppingPackage &&
                                    it.pageFingerprint == observation.pageFingerprint &&
                                    it.expiresAt > now
                            }
                            .singleOrNull()
                    }
                    ?: matcher.findReferenceForPayment(
                        shoppingPackage = observation.shoppingPackage,
                        pageFingerprint = observation.pageFingerprint
                    )
                if (reference == null) {
                    // 支付先到但无法关联任何结算会话：证据不丢弃，转纯通知待确认（sessionId 为空）
                    matcher.recordOrphanPaymentSignal(signal, sourceType = "NOTIFICATION")
                    Log.i(TAG, "支付通知无结算会话可匹配，已转纯通知待确认")
                    return@launch
                }
                val result = matcher.recordPaymentEvidence(reference, signal)
                if (result is CheckoutPaymentMatchResult.Rejected) {
                    matcher.recordOrphanPaymentSignal(
                        signal,
                        sourceType = "NOTIFICATION",
                        conflictReason = result.reason
                    )
                }
                Log.i(TAG, "支付通知证据落库结果: ${formatMatchResult(result)}")
            } catch (error: RuntimeException) {
                Log.w(TAG, "支付证据写入失败", error)
                try {
                    matcher.recordOrphanPaymentSignal(
                        signal,
                        sourceType = "NOTIFICATION",
                        conflictReason = "EVIDENCE_PERSIST_FAILED"
                    )
                } catch (fallbackError: RuntimeException) {
                    Log.w(TAG, "支付通知待确认兜底写入失败", fallbackError)
                }
            }
        }
        if (coordinator == null) {
            Companion.notifyPurchaseIntentUnavailable(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                callback
            )
            return
        }
        try {
            coordinator.trigger(
                intent = PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                pageFingerprint = observation.pageFingerprint,
                callback = callback,
                paymentNotification = signal
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "支付通知回退路由失败", error)
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
                callback
            )
        }
    }

    /** 悬浮按钮只调用现有手动截图入口，不直接接触截图资源或 AI。 */
    private fun requestManualCaptureFromOverlay() {
        val context = userRetryCaptureContext
        snapshotCaptureCoordinator?.requestManualCapture(SnapshotCaptureCallback { result ->
            if (context != null) {
                triggerUserRetryAfterOverlayCapture(context, result)
            }
        })
    }

    /** 普通页只能由悬浮按钮成功截图触发内部重试，并在触发后清理上下文防止重复 AI。 */
    private fun triggerUserRetryAfterOverlayCapture(
        context: UserRetryCaptureContext,
        result: SnapshotCaptureResult
    ) {
        if (
            result.sourceReason != SnapshotSourceReason.MANUAL_CAPTURE ||
            result.status !in setOf(SnapshotCaptureStatus.STORED, SnapshotCaptureStatus.DUPLICATE) ||
            userRetryCaptureContext != context ||
            result.sessionId.isNullOrBlank() ||
            result.shoppingPackage != context.shoppingPackage ||
            result.pageFingerprint != context.pageFingerprint
        ) {
            return
        }

        val completedContext = context.withSessionId(checkNotNull(result.sessionId))
        userRetryCaptureContext = null
        try {
            purchaseIntentCoordinator?.setUserRetryContext(completedContext)
            purchaseIntentCoordinator?.triggerUserRetry(completedContext)
        } catch (error: RuntimeException) {
            Log.w(TAG, "用户主动截图重试触发失败", error)
        } finally {
            snapshotCaptureCoordinator?.setUserRetryContext(null)
            purchaseIntentCoordinator?.setUserRetryContext(null)
        }
    }

    /** 可信上下文失效时，同时禁止截图协调器和意图协调器继续使用。 */
    private fun clearUserRetryCaptureContext() {
        userRetryCaptureContext = null
        snapshotCaptureCoordinator?.setUserRetryContext(null)
        purchaseIntentCoordinator?.setUserRetryContext(null)
    }

    /** 仅以本次成功读取节点树的白名单普通页建立用户主动截图上下文。 */
    private fun setUserRetryCaptureContext(observation: CheckoutPageObservation): Boolean {
        val context = UserRetryCaptureContext(observation = observation)
        if (!context.isComplete() || !pageDetector.supportsShoppingPackage(context.shoppingPackage)) {
            clearUserRetryCaptureContext()
            return false
        }
        val retainedContext = userRetryCaptureContext?.takeIf {
            it.shoppingPackage == context.shoppingPackage &&
                it.pageFingerprint == context.pageFingerprint &&
                it.pageRole == context.pageRole &&
                it.isSensitivePage == context.isSensitivePage
        } ?: context
        userRetryCaptureContext = retainedContext
        snapshotCaptureCoordinator?.setUserRetryContext(retainedContext)
        purchaseIntentCoordinator?.setUserRetryContext(retainedContext)
        return true
    }

    /** AI 回调只传递结构化结果，运行在 IO 协程中且不携带图片。 */
    private fun notifyAiResult(result: com.jotbill.app.ai.AiVisionResult) {
        val callback = synchronized(aiLifecycleLock) { aiResultCallback }
        if (callback == null) {
            return
        }
        try {
            callback.onResult(result)
        } catch (error: RuntimeException) {
            Log.w(TAG, "结算页 AI 结果消费者失败", error)
        }
    }

    /** 只有同一稳定结算指纹的第一次明确点击才会进入 SUBMIT_ACTION。 */
    private fun shouldTriggerSubmitAction(
        observation: CheckoutPageObservation,
        event: AccessibilityEvent,
        eventSource: AccessibilityNodeInfo?
    ): Boolean {
        if (observation.isSensitivePage || observation.pageRole != CheckoutPageRole.CHECKOUT) {
            return false
        }
        if (!CheckoutSubmitActionDetector.isSubmitActionEvent(
                event = event,
                source = eventSource,
                shoppingPackage = observation.shoppingPackage
            )
        ) {
            return false
        }

        val key = StableCheckoutKey(
            shoppingPackage = observation.shoppingPackage,
            pageFingerprint = observation.pageFingerprint
        )
        if (submitActionTriggeredKey == key) {
            return false
        }
        submitActionTriggeredKey = key
        return true
    }

    /** 提交动作只调用已有购买意图协调器，不直接触碰截图或 AI。 */
    private fun triggerSubmitAction(pageFingerprint: String) {
        val coordinator = purchaseIntentCoordinator ?: return
        try {
            coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, pageFingerprint)
        } catch (error: RuntimeException) {
            Log.w(TAG, "提交订单购买意图触发失败", error)
        }
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

    /** 仅把前台页面失效状态交给悬浮按钮，不改变购买意图和截图 buffer 路由。 */
    private fun dispatchOverlayInvalidation(
        reason: CheckoutInvalidationReason,
        shoppingPackage: String? = null,
        pageFingerprint: String? = null,
        foregroundPackage: String? = null
    ) {
        checkoutCaptureOverlayManager?.onObservationEvent(
            CheckoutObservationEvent.Invalidated(
                CheckoutObservationInvalidation(
                    reason = reason,
                    shoppingPackage = shoppingPackage,
                    pageFingerprint = pageFingerprint,
                    invalidatedAt = System.currentTimeMillis(),
                    foregroundPackage = foregroundPackage
                )
            )
        )
    }

    private fun dispatchEvent(event: CheckoutObservationEvent) {
        observationListener?.let { listener ->
            try {
                listener.onEvent(event)
            } catch (error: RuntimeException) {
                Log.w(TAG, "分发结算页观察事件失败", error)
            }
        }
        checkoutCaptureOverlayManager?.onObservationEvent(event)
        try {
            purchaseIntentCoordinator?.onEvent(event)
        } catch (error: RuntimeException) {
            Log.w(TAG, "分发购买意图事件失败", error)
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
        pageFingerprint: String? = null,
        foregroundPackage: String? = null
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
            invalidatedAt = nowMillis(),
            foregroundPackage = foregroundPackage
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
