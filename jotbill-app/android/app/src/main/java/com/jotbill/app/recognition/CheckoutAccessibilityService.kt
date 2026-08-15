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
    @Volatile
    private var userRetryCaptureContext: UserRetryCaptureContext? = null
    private var snapshotCaptureResultListener: SnapshotCaptureResultListener? = null
    @Volatile
    private var purchaseIntentResultConsumer: PurchaseIntentResultCallback? = null
    private val aiLifecycleLock = Any()
    private var aiScope: CoroutineScope? = null
    private var aiVisionClient: CheckoutAiVisionClient? = null
    private val dispatchedAiRequestKeys = mutableSetOf<String>()
    private var aiResultCallback: CheckoutAiResultCallback? = null
    private var paymentMatcher: CheckoutPaymentMatcher? = null
    private var matchingScope: CoroutineScope? = null
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
        initializePaymentMatcher()
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
                observePaymentResultIfApplicable(event, shoppingPackage)
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

    override fun onInterrupt() {
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
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        shutdownPaymentMatcher()
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
        // AccessibilityService.onInterrupt() 是抽象方法，无法调用 super.onInterrupt()。
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
        val isOrdinaryPageUserRetry =
            ready.intent == PurchaseIntent.USER_RETRY &&
                ready.snapshot.pageRole.canBufferUserRetrySnapshot()
        val client: CheckoutAiVisionClient
        val scope: CoroutineScope
        synchronized(aiLifecycleLock) {
            client = aiVisionClient ?: return false
            scope = aiScope ?: return false
            if (!scope.isActive || !dispatchedAiRequestKeys.add(requestKey)) {
                return false
            }
            if (!isOrdinaryPageUserRetry) {
                activeAiReferences[ready.snapshot.sessionId] = ready.snapshot.reference()
            }
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
                    val matcher = if (isOrdinaryPageUserRetry) {
                        null
                    } else {
                        synchronized(aiLifecycleLock) { paymentMatcher }
                    }
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
                            matcher.recordAiResult(result)
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
     * 阶段四 T3：在支付应用中观察支付结果页，提取结构化证据。
     *
     * 只读取无障碍节点文本，不截图、不读密码/付款码/银行卡号。
     * 支付成功的结果生成 PaymentResultEvidence，交给匹配器落库。
     * 支付失败的结果不生成成功证据，只记录诊断日志。
     */
    private fun observePaymentResultIfApplicable(
        event: AccessibilityEvent,
        paymentPackage: String
    ) {
        val root = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        } ?: return

        try {
            val observation = AccessibilityPageSummaryCollector.collect(
                shoppingPackage = paymentPackage,
                windowClass = event.className?.toString()?.trim()?.takeIf { it.isNotBlank() },
                root = root,
                observedAt = System.currentTimeMillis()
            )

            if (observation.pageRole != CheckoutPageRole.PAYMENT_RESULT) return

            // 从节点文本提取结构化证据（PaymentResultParser 内部已脱敏）
            val pageText = collectPageText(root)
            val evidence = PaymentResultParser.parse(
                pageText = pageText,
                sourcePackage = paymentPackage,
                pageFingerprint = observation.pageFingerprint,
                observedAt = observation.observedAt
            ) ?: run {
                Log.d(TAG, "支付结果页未解析到有效证据（可能为失败页或金额缺失）")
                return
            }

            Log.i(TAG, "支付结果页解析到证据: 金额=${evidence.amountMinor}分, 状态=${evidence.paymentStatus}")

            // 异步落库证据
            val scope = synchronized(aiLifecycleLock) { matchingScope } ?: return
            val matcher = synchronized(aiLifecycleLock) { paymentMatcher } ?: return
            scope.launch {
                try {
                    recordPaymentResultEvidence(matcher, paymentPackage, observation, evidence)
                } catch (error: Exception) {
                    Log.w(TAG, "支付结果页证据落库失败", error)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "支付结果页观察失败", error)
        }
    }

    /** 收集节点树可见文本，用于支付结果页证据解析。已排除密码/付款码等敏感字段。 */
    private fun collectPageText(root: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        collectNodeText(root, builder, depth = 0)
        return builder.toString()
    }

    /** 递归收集节点文本，深度限制避免过深遍历。 */
    private fun collectNodeText(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        depth: Int
    ) {
        if (depth > 20) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            builder.append(it).append(' ')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeText(child, builder, depth + 1)
        }
    }

    /**
     * 将支付结果页证据落库。
     *
     * 会话解析双路径（T4）：
     * 1. 优先用支付交接上下文（handoffStableCheckout）——提交订单后切到支付应用的场景；
     * 2. 否则用 resolveSessionForPayment 按订单号/金额+时间窗口综合解析；
     * 3. 并发会话无法区分时输出 Ambiguous，不强行合并（T6 创建并发候选）。
     */
    private suspend fun recordPaymentResultEvidence(
        matcher: CheckoutPaymentMatcher,
        paymentPackage: String,
        observation: CheckoutPageObservation,
        evidence: PaymentResultEvidence
    ) {
        val signal = PaymentNotificationFallbackSignal(
            paymentPackage = paymentPackage,
            amountMinor = evidence.amountMinor,
            occurredAt = evidence.occurredAt ?: System.currentTimeMillis(),
            merchant = evidence.merchant,
            orderId = evidence.orderId,
            sourceEventKey = evidence.sourceEventKey
        )

        // 路径一：支付交接上下文中的稳定结算页
        val handoffReference = synchronized(this) { handoffStableCheckout }?.let { stable ->
            val stableObservation = stable.observation
            matcher.findReferenceForPayment(
                shoppingPackage = stableObservation.shoppingPackage,
                pageFingerprint = stableObservation.pageFingerprint
            )
        }

        if (handoffReference != null) {
            val result = matcher.recordPaymentEvidence(handoffReference, signal)
            Log.i(TAG, "支付结果页证据落库结果（交接上下文）: ${result::class.java.simpleName}")
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
                val result = matcher.recordPaymentEvidence(resolution.reference, signal)
                Log.i(
                    TAG,
                    "支付结果页证据落库结果（" +
                        (if (resolution.byOrderId) "订单号" else "金额+时间窗口") +
                        "）: ${result::class.java.simpleName}"
                )
            }
            is PaymentSessionResolution.Ambiguous -> {
                // 并发会话无法区分：不强行合并，T6 创建并发候选由用户选择
                Log.i(TAG, "支付结果页命中并发会话(${resolution.sessionIds.size}个)，待确认处理")
            }
            PaymentSessionResolution.NoMatch -> {
                Log.d(TAG, "支付结果页无关联活跃会话，证据暂不落库")
            }
        }
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
        val matchingScope = synchronized(aiLifecycleLock) { matchingScope }
        val snapshotReference = checkoutSnapshotBuffer?.findReference(
            slotId = checkoutSnapshotSlotId(observation.shoppingPackage),
            pageFingerprint = observation.pageFingerprint
        )
        if (matcher != null && matchingScope != null) {
            matchingScope.launch {
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
                    Log.w(TAG, "支付通知没有唯一的结算会话可匹配")
                    return@launch
                }
                try {
                    matcher.recordPaymentEvidence(reference, signal)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "支付证据写入失败", error)
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
