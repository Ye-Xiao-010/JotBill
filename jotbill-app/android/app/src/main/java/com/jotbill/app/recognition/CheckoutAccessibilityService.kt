package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jotbill.app.ai.CheckoutAiResultCallback
import com.jotbill.app.ai.CheckoutAiVisionClient
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.overlay.CheckoutCaptureOverlayManager
import com.jotbill.app.security.KeyStoreSecretStore
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
            activeService?.setSnapshotCaptureResultListener(listener)
        }

        /** 设置购买意图结果消费者；Ready.snapshot 由消费者读取后负责 release。 */
        @JvmStatic
        fun setPurchaseIntentResultConsumer(consumer: PurchaseIntentResultCallback?) {
            activeService?.setPurchaseIntentResultConsumer(consumer)
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
        ) = requestPurchaseIntent(
            PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
            pageFingerprint,
            callback
        )

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

    private var lastWindowPackage: String? = null
    private var lastWindowClass: String? = null
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
    private var snapshotCaptureResultListener: SnapshotCaptureResultListener? = null
    @Volatile
    private var purchaseIntentResultConsumer: PurchaseIntentResultCallback? = null
    private val aiLifecycleLock = Any()
    private var aiScope: CoroutineScope? = null
    private var aiVisionClient: CheckoutAiVisionClient? = null
    private val dispatchedAiRequestKeys = mutableSetOf<String>()
    private var aiResultCallback: CheckoutAiResultCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 重连时先结束旧观察旅程，避免旧指纹阻止新服务发出稳定事件。
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        val configuredInfo = serviceInfo ?: AccessibilityServiceInfo()
        configuredInfo.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        configuredInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        configuredInfo.notificationTimeout = 100L
        configuredInfo.flags = configuredInfo.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
        initializeAiClient()
        setObservationListener(snapshotCaptureCoordinator)
        serviceConnected = true
        Log.i(TAG, "结算页无障碍观察服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceConnected || event == null) {
            return
        }

        val shoppingPackage = event.packageName?.toString()?.trim().orEmpty()
        if (!pageDetector.supportsShoppingPackage(shoppingPackage)) {
            val previousShoppingPackage = lastWindowPackage
            if (pageDetector.supportsPaymentPackage(shoppingPackage)) {
                if (currentStableCheckout != null) {
                    handoffStableCheckout = currentStableCheckout
                    handoffForegroundPackage = shoppingPackage
                } else if (handoffForegroundPackage != shoppingPackage) {
                    handoffStableCheckout = null
                    handoffForegroundPackage = null
                }
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
        val root = rootInActiveWindow ?: eventSource
        if (root == null) {
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
                    stabilityCoordinator.invalidate(
                        reason = reason,
                        shoppingPackage = shoppingPackage,
                        pageFingerprint = observation.pageFingerprint
                    )
                    if (reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
                        checkoutCaptureOverlayManager?.onSupportedShoppingNonCheckout(
                            shoppingPackage = observation.shoppingPackage,
                            pageFingerprint = observation.pageFingerprint
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

    override fun onInterrupt() {
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        purchaseIntentCoordinator?.setReadyDispatcher(null)
        purchaseIntentCoordinator?.setResultConsumer(null)
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        shutdownAiClient()
        snapshotCaptureCoordinator?.onServiceInterrupted()
        purchaseIntentCoordinator?.onServiceInterrupted()
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        checkoutCaptureOverlayManager?.onServiceInterrupted()
        snapshotCaptureResultListener = null
        purchaseIntentResultConsumer = null
        if (activeService === this) {
            activeService = null
        }
        setObservationListener(null)
        Log.w(TAG, "结算页无障碍观察服务被中断")
        super.onInterrupt()
    }

    override fun onDestroy() {
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        submitActionTriggeredKey = null
        purchaseIntentCoordinator?.setReadyDispatcher(null)
        purchaseIntentCoordinator?.setResultConsumer(null)
        setAiResultCallback(null)
        shutdownAiClient()
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
        }

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
            }
        }
        val job = try {
            scope.launch {
                clientStarted.set(true)
                try {
                    val result = client.recognize(ready)
                    notifyAiResult(result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // 客户端的正常异常路径由自身 finally 释放，这里兜底处理未进入完整路径的异常。
                    ready.snapshot.release()
                    Log.w(TAG, "结算页 AI 任务异常", error)
                }
            }
        } catch (error: RuntimeException) {
            synchronized(aiLifecycleLock) {
                dispatchedAiRequestKeys.remove(requestKey)
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

    private fun setSnapshotCaptureResultListener(listener: SnapshotCaptureResultListener?) {
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

    private fun setPurchaseIntentResultConsumer(consumer: PurchaseIntentResultCallback?) {
        purchaseIntentResultConsumer = consumer
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
        snapshotCaptureCoordinator?.requestManualCapture(SnapshotCaptureCallback { })
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
