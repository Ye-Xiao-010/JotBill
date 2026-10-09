package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jotbill.app.utils.JotBillLog
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jotbill.app.ai.CheckoutAiResultCallback
import com.jotbill.app.ai.CheckoutAiVisionClient
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.overlay.CheckoutCaptureOverlayManager
import com.jotbill.app.overlay.PendingConfirmOverlayManager
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
import kotlinx.coroutines.delay
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

        /** 结算页门禁日志按"包名+拒绝码"的采样间隔。 */
        private const val CHECKOUT_GATE_LOG_INTERVAL_MILLIS = 3_000L

        /** 截图协调器重建的最小间隔，避免重建失败时形成每事件重试风暴。 */
        private const val COORDINATOR_RECOVERY_THROTTLE_MILLIS = 10_000L

        /** "截图兜底已禁止"日志按原因的采样间隔。 */
        private const val SCREENSHOT_FALLBACK_BLOCK_LOG_INTERVAL_MILLIS = 3_000L

        /** 支付交接补发节流窗口：同一支付包在该窗口内只补发一次交接事件。 */
        private const val PAYMENT_HANDOFF_REDISPATCH_THROTTLE_MILLIS = 5_000L

        /**
         * 敏感支付页面的截图禁止锁存窗口。
         *
         * 支付密码页停留期间，无障碍会交替收到"敏感页"与"空节点树"两类帧；
         * 空树帧无法自行判定敏感，需靠锁存窗口保证整个密码输入过程都不截图。
         */
        private const val SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS = 60_000L

        /**
         * 支付结果页事件键记忆窗（去重第一道防线），**2026-09-30 由 60 秒调至 90 秒**。
         *
         * 依据见 [recentPaymentResultEventKeys] 的 KDoc（真机实测同一结果页被观测两次、
         * `occurredAt` 相差 60393 ms，比原 60 秒窗多 393 毫秒而漏过去）。
         * ⚠️ 须与 `CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS` 保持同值。
         */
        private const val PAYMENT_RESULT_EVENT_MEMORY_MILLIS = 90_000L

        /** 支付结果页截图兜底的系统回调看门狗：MIUI 等可能静默丢弃回调，超时强制复位。 */
        private const val PAYMENT_SCREENSHOT_FALLBACK_WATCHDOG_MILLIS = 8_000L

        /** 用户断言截图的最小点击间隔，防止连点在同一页面制造多条待确认。 */
        private const val USER_ASSERTED_CAPTURE_MIN_INTERVAL_MILLIS = 3_000L

        /** 用户断言截图的系统回调看门狗：MIUI 等可能静默丢弃回调，超时强制复位。 */
        private const val USER_ASSERTED_CAPTURE_WATCHDOG_MILLIS = 8_000L

        /**
         * 用户断言截图的专用内存槽位前缀。
         *
         * 断言截图绝不写入 shopping-package: 结算槽位：store 对新会话会整体替换
         * 槽位状态，会把在途自动候选连同交接栅栏一起清掉。专用槽位让断言通道
         * 与 journey 状态机完全隔离，复用 buffer 的字节卫生与原子消费语义。
         */
        private const val USER_ASSERTED_SLOT_PREFIX = "user-asserted:"

        /**
         * 系统 UI 包名：这些窗口（状态栏、通知、输入法）的内容变化不代表购物页变化。
         *
         * 它们仍参与悬浮按钮的归属推断，但不应驱动结算页整树重采集。
         */
        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.android.inputmethod.latin"
        )

        @Volatile
        private var observationListener: CheckoutObservationListener? = null

        @Volatile
        private var activeService: CheckoutAccessibilityService? = null

        /** 为后续截图/会话协调器设置稳定观察回调。传入 null 可解除引用。 */
        @JvmStatic
        fun setObservationListener(listener: CheckoutObservationListener?) {
            observationListener = listener
        }

        /**
         * 服务当前是否实际存活（onServiceConnected 后、onUnbind/onDestroy 前）。
         *
         * 部分 ROM（如 MIUI 部分版本）上 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
         * 读取结果与真实运行状态不一致（返回空或不含本服务），状态页会误报"未开启"。
         * 判定服务开启时应优先使用本方法，再回退系统设置读取。
         */
        @JvmStatic
        fun isServiceRunning(): Boolean = activeService != null

        /** 供悬浮按钮或 RN bridge 请求当前稳定结算页的手动截图。 */
        @JvmStatic
        fun requestManualCapture(callback: SnapshotCaptureCallback) {
            // 精简模式：截图识别链路整体停用时手动补截同样无意义（协调器本就没有
            // 稳定结算页槽位，返回的会是 NO_ACTIVE_CHECKOUT，对用户不可解释）。
            // 在入口显式拒绝，日志锚点比"无上下文"更接近真实原因。
            if (!RecognitionCapabilities.checkoutCaptureEnabled) {
                callback.onResult(
                    SnapshotCaptureResult(
                        status = SnapshotCaptureStatus.UNAVAILABLE,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reasonCode = SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
                    )
                )
                return
            }
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
                JotBillLog.w(TAG, "购买意图不可用结果回调失败", error)
            }
        }
    }

    private val pageDetector = CheckoutPageDetector()

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
    @Volatile
    private var lastSuccessfulPaymentEvidenceAt = 0L

    /**
     * 敏感页锁存（D-9：增加"读到明确非敏感购物页 ×N 帧 ⇒ 提前解除"）。
     *
     * 原先这里持有两个 `@Volatile var` 时间戳（支付侧/购物侧），现由
     * [SensitivePageLatch] 统一持有，原因有二：
     * ① 解除判据抽成纯对象才能被 JVM 单测驱动 —— 触碰不变式 1 的改动
     *    必须由负向用例钉死，否则"解除"一旦写宽无人能及时发现；
     * ② "解除水位"只作用于断言（手动）通道，支付结果页兜底（自动）通道
     *    的判定语义原样保留（G1-8 风控①）。
     */
    private val sensitivePageLatch = SensitivePageLatch(
        blockMillis = SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS
    )

    /** 用户断言截图的在飞标志；配合看门狗在系统回调丢失时强制复位。 */
    @Volatile
    private var userAssertedCaptureInFlight = false

    /** 用户断言截图最近一次受理时间，用于点击节流。 */
    @Volatile
    private var lastUserAssertedCaptureAt = 0L

    /**
     * 已派发 AI 的用户断言会话 ID。
     *
     * AI 结果落库后据此收口：识别成功且无支付证据时立即转待确认
     * （promoteAssertedSessionToPending），避免识别结果随会话过期无声消失。
     * 自动截图的会话不进该集合，保持"等待支付证据"的原有语义。
     */
    private val assertedSessionIds = mutableSetOf<String>()

    /**
     * 支付结果页事件键记忆窗（去重第一道防线）。
     *
     * ⚠️ **2026-09-30 由 60 秒调至 90 秒**（真机实证）。原 60 秒与
     * `PaymentResultParser` 的 30 秒时间桶、[D-25] 的孤儿去重窗**阈值重叠**：
     * 三个 30~60 秒的判据挤在窄带里，只要用户在微信结果页停留超过 60 秒，
     * 三道防线在同一刻集体失效。
     *
     * 真机数据（小米 25128PNA1C，拼多多 build 0.0.98，2026-09-30 10:05~10:06）：
     * 同一笔 ¥2.68 的结果页被观测两次，`textLength` 均为 20（同一页面），
     * `occurredAt` 相差 **60393 ms** —— 比原 60 秒窗多 393 毫秒，
     * 事件层记忆过期 + 跨桶 + 越过 D-25 窗 ⇒ 落了两条待确认。
     *
     * 90 秒的依据：覆盖实测的 60.393 秒；**仍远小于 D-6 关心的 2 分钟**
     * （"同包同金额、2 分钟内两单不得撞键"），不把那个风险重新引进来。
     *
     * ⚠️ **这不是根治**：纯时间判据必然随用户停留时长失效。停留超 90 秒仍会重复。
     * 根治方向见 `FIX_TASK_2026-09-29` §三.② 的待办（把判据从时间锚到结算上下文）。
     * 本常量须与 `CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS` **保持同值** ——
     * 两道防线阈值不一致时，先失效的那道形同虚设。
     */
    private val recentPaymentResultEventKeys = mutableMapOf<String, Long>()
    private val screenshotCallbackExecutor = Executor { runnable -> mainHandler.post(runnable) }
    @Volatile
    private var currentStableCheckout: StableCheckoutPage? = null
    @Volatile
    private var handoffStableCheckout: StableCheckoutPage? = null
    @Volatile
    private var handoffForegroundPackage: String? = null
    /** 支付交接补发的节流状态：同一支付包短时间内只补发一次 PAYMENT_APP_HANDOFF。 */
    @Volatile
    private var lastPaymentHandoffRedispatchFor: String? = null
    @Volatile
    private var lastPaymentHandoffRedispatchAt = 0L
    /** 支付交接诊断日志的最近打印时间，按秒采样防止日志风暴。 */
    @Volatile
    private var lastHandoffDiagnosticsAt = 0L
    /** 支付交接上下文日志的最近打印时间，按秒采样防止日志风暴。 */
    @Volatile
    private var lastHandoffContextLogAt = 0L

    /**
     * 结算页门禁日志的最近打印时间，键为"包名|拒绝码"。
     *
     * 按组合采样而非全局采样：否则高频的某一种拒绝原因会把其他原因全部掩盖，
     * 无法看出页面究竟卡在哪个信号上。仅在主线程访问，无需同步。
     */
    private val lastCheckoutGateLogAt = mutableMapOf<String, Long>()

    /** 最近一次尝试重建截图协调器的时间，用于抑制重建风暴。 */
    @Volatile
    private var lastCoordinatorRecoveryAt = 0L

    /**
     * 已应用到本服务状态的开关取值；null 表示本次服务生命周期内尚未应用过。
     * 用途是**运行中翻转**：用户在设置页关掉识别链路时，已经建立的稳定结算页、
     * 交接上下文与手动补截上下文必须立刻失效，否则"关掉之后仍能截图"会一直成立到
     * 下一次服务重连。用可空初始化强制首个事件就建立基线，避免默认 true 的取值
     * 让"启动即关闭"的用户错过一次清理。
     */
    @Volatile
    private var appliedCheckoutCaptureEnabled: Boolean? = null

    /** "截图兜底已禁止"日志按原因的最近打印时间。 */
    private val lastScreenshotFallbackBlockLogAt = mutableMapOf<String, Long>()

    /** 采样间隔内被抑制的"截图兜底已禁止"次数，随下次输出一并汇报。 */
    private val suppressedScreenshotFallbackBlockCount = mutableMapOf<String, Int>()
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
            dispatchEvent(CheckoutObservationEvent.Stable(page))
        },
        onInvalidated = { detail ->
            if (!shouldRetainCheckoutAcrossPaymentInterstitial(detail)) {
                currentStableCheckout = null
                if (detail.reason != CheckoutInvalidationReason.PAYMENT_APP_HANDOFF) {
                    handoffStableCheckout = null
                    handoffForegroundPackage = null
                }
            }
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

        // 每次系统重连都会 close 旧协调器并新建一个：若真机上出现"截图突然全部
        // 不可用"，需要据此区分是协调器熔断还是服务被反复重连。
        JotBillLog.i(
            TAG,
            "无障碍服务开始连接: 旧协调器=${if (snapshotCaptureCoordinator != null) "存在(将关闭)" else "无"}, " +
                "previouslyConnected=$serviceConnected"
        )

        // 重连时先结束旧观察旅程，避免旧指纹阻止新服务发出稳定事件。
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
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
        AccessibilityPageSummaryCollector.resetVolatileTracker()
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        checkoutCaptureOverlayManager?.close()
        // 精简模式：整条截图识别链路既不创建也不接线。
        //
        // 为什么是"不创建"而不只是"闸住入口"：
        // ① CheckoutSnapshotCaptureCoordinator 构造即
        //    `HandlerThread("CheckoutScreenshot").apply { start() }` —— 一条常驻
        //    OS 线程，只在 onDestroy 才 quitSafely。开关关闭时它永远等不到消息。
        // ② CheckoutAiVisionClient 构造即建 OkHttpClient（连接池 + 线程池），
        //    而 recognize/recognizePaymentResult 在精简版永不被调。
        // ③ 更关键的是**接线**：`setObservationListener(snapshotCaptureCoordinator)`
        //    注册后，每个 Invalidated 事件（购物页每秒数十条）仍会跑完
        //    handleInvalidated 的 ~140 行（算 handoffSource / isPaymentInterstitial /
        //    replaceRetainedHandoff + 日志 + buffer.clear），而缓冲区里永远什么都没有；
        //    每次 clearUserRetryCaptureContext() 还要 mainHandler.post{} 走一遍
        //    140 行交接守卫（纯 null 赋值却付了完整 Handler 往返）。
        //    省掉的是**每事件**开销，不只是启动那一次建对象。
        //
        // 五个字段本来就可空、95 处调用点全用安全调用 `?.`，因此"保持 null"
        // 等价于"不存在"，不需要为它新增分支。
        //
        // ⚠️ purchaseIntentCoordinator 为 null 时，dispatchPaymentResultIntent 恒返
        // NOT_DISPATCHED → promotePendingWhenAiUnavailable(matcher, sessionId) 接管落卡。
        // 精简版主路径（无会话 → recordOrphanPaymentSignal 本就落卡）不受影响；
        // "命中遗留会话"路径则由 promoteEvidenceOnlySessionToPending 补上 ——
        // 即协调器的孤儿出口在这里被服务层的兜底取代，pending 仍会落地。
        RecognitionCapabilities.load(applicationContext)
        if (RecognitionCapabilities.checkoutCaptureEnabled) {
            createCaptureChain()
        } else {
            JotBillLog.i(TAG, "结算页截图识别链路已停用: 跳过协调器/悬浮层创建与事件接线")
        }
        activeService = this
        initializePaymentMatcher()
        initializeAiClient()
        serviceConnected = true
        applyCheckoutCaptureCapability()
        JotBillLog.i(TAG, "结算页无障碍观察服务已连接")
    }

    /**
     * 把开关取值应用到本服务的运行状态；取值变化时清理结算页侧的全部可截图上下文。
     *
     * 关闭时必须清理的四项，缺一都会让"关掉之后仍能截图/关联"继续成立：
     * 1. `stabilityCoordinator` —— 它的在途稳定窗口会在随后触发 `Stable` 事件，
     *    而 `Stable` 是自动截图与 buffer 落图的唯一触发源；
     * 2. `currentStableCheckout` / `handoffStableCheckout` / `handoffForegroundPackage` ——
     *    交接上下文会让支付结果页拿到旧会话引用，从而消费 `payment:<sessionId>` 槽位；
     * 3. `userRetryCaptureContext` —— 手动补截与用户断言通道的入口；
     * 4. 悬浮截图按钮 —— 它存在的唯一目的就是发起截图，停用后继续显示会让用户
     *    点到一个必然失败的按钮。
     *
     * **不清理** `sensitivePageLatch`：它是密码页零截图的不变式依据，与开关无关，
     * 停用识别链路只会让截图更少，不构成放宽风险。
     *
     * 仅主线程（无障碍事件回调）访问，无需同步。
     */
    private fun applyCheckoutCaptureCapability() {
        val enabled = RecognitionCapabilities.checkoutCaptureEnabled
        val previous = appliedCheckoutCaptureEnabled
        if (previous == enabled) {
            return
        }
        appliedCheckoutCaptureEnabled = enabled
        JotBillLog.i(TAG, "结算页截图识别链路: ${if (enabled) "启用" else "停用"}（上次=${previous ?: "无基线"}）")
        if (enabled) {
            // 运行中从关闭翻到开启：整条链在关闭时**根本没有被创建**（见 onServiceConnected），
            // 这里按需补建并接线，否则用户改完设置后要等服务重连才生效。
            if (snapshotCaptureCoordinator == null) {
                createCaptureChain()
            }
            initializeAiClient()
            return
        }
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
        clearUserRetryCaptureContext()
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        // 复用"服务中断"状态：它是既有语义里唯一表达"按钮收起且不再可截图"的迁移，
        // 另造一套状态会污染 CheckoutOverlayStatus 契约与 RN 侧状态映射。
        checkoutCaptureOverlayManager?.onServiceInterrupted()
        // 拆链而不是只清状态：协调器内部的截图线程与事件接线必须真正停掉，
        // 否则每事件仍要跑一遍 handleInvalidated 的空转逻辑。
        disposeCaptureChain()
    }

    /**
     * 创建并接线整条结算截图链（buffer → 截图协调器 → 意图协调器 → 悬浮层）。
     *
     * 幂等：已存在时不重复创建（重复创建会泄漏前一个 HandlerThread）。
     */
    private fun createCaptureChain() {
        if (snapshotCaptureCoordinator != null) {
            return
        }
        val sharedBuffer = CheckoutSnapshotBuffer()
        checkoutSnapshotBuffer = sharedBuffer
        snapshotCaptureCoordinator = CheckoutSnapshotCaptureCoordinator(this, sharedBuffer)
        purchaseIntentCoordinator = CheckoutPurchaseIntentCoordinator(sharedBuffer)
        checkoutCaptureOverlayManager = CheckoutCaptureOverlayManager(this).also { manager ->
            manager.setManualCaptureRequestHandler { requestManualCaptureFromOverlay() }
        }
        configureSnapshotCaptureResultListener()
        configurePurchaseIntentResultConsumer()
        configurePaymentSignalOrphanSink()
        setObservationListener(snapshotCaptureCoordinator)
    }

    /**
     * 关闭并断开整条结算截图链。
     *
     * 必须 close 而非置空：截图协调器持有 `HandlerThread("CheckoutScreenshot")`，
     * 只置空引用会让那条线程一直空转到进程结束（quitSafely 只在 close 里调用）。
     * 关闭顺序与 [onServiceConnected] 的建立顺序相反，先摘监听再关组件。
     */
    private fun disposeCaptureChain() {
        setObservationListener(null)
        purchaseIntentCoordinator?.setReadyDispatcher(null)
        purchaseIntentCoordinator?.setResultConsumer(null)
        purchaseIntentCoordinator?.close()
        snapshotCaptureCoordinator?.close()
        checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
        checkoutCaptureOverlayManager?.close()
        purchaseIntentCoordinator = null
        snapshotCaptureCoordinator = null
        checkoutSnapshotBuffer = null
        checkoutCaptureOverlayManager = null
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
                    JotBillLog.w(TAG, "失败会话重试结果回调失败", error)
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
                    JotBillLog.w(TAG, "失败会话重试触发失败", error)
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
            JotBillLog.w(TAG, "失败会话重试结果回调失败", error)
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
        JotBillLog.i(
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
                val rootIsShopping = pageDetector.supportsShoppingPackage(activeRootPackage)
                // 活动窗口已经回到购物 App 时，微信旧窗口事件是后台残留，直接按购物包处理。
                if (eventIsPayment && rootIsShopping) {
                    return activeRootPackage
                }
                val rootIsKnown = activeRootPackage.isBlank() ||
                    rootIsShopping ||
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

    /**
     * 判断是否应跳过本次事件的整树重采集。
     *
     * 自身悬浮层（`com.jotbill.app`）与系统 UI 的内容变化事件会被
     * [resolveEffectiveShoppingPackage] 按活动根窗口归属为购物包，用于维持悬浮
     * 按钮状态。但这类事件并不代表购物页真的发生变化，一旦驱动重采集，结算页
     * 轮播文案等动态内容就会生成新指纹，被下游误判为新订单并反复截图。
     *
     * 仅在已有稳定结算页时跳过：尚未识别出结算页时仍需正常采集，避免漏检。
     *
     * @param eventPackage 事件原始包名。
     * @param shoppingPackage 归属推断后的购物包名。
     * @return true 表示本次事件不应触发采集。
     */
    private fun shouldSkipForeignEventCollection(
        eventPackage: String,
        shoppingPackage: String
    ): Boolean {
        if (eventPackage.isBlank() || eventPackage == shoppingPackage) {
            return false
        }
        if (eventPackage != packageName && eventPackage !in SYSTEM_UI_PACKAGES) {
            return false
        }
        return currentStableCheckout != null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceConnected || event == null) {
            return
        }

        // 协调器熔断后截图能力不可逆失效，且系统不会因此回调 onServiceConnected，
        // 此前只能靠用户手动重启无障碍服务恢复。在事件入口自愈重建，
        // 使截图能力最迟在下一个无障碍事件到来时恢复。
        recoverSnapshotCoordinatorIfClosed()

        // 精简模式开关在运行中翻转：结算页侧的可截图上下文必须立即失效。
        // 放在 recoverSnapshotCoordinatorIfClosed 之后 —— 熔断重建会换掉整组协调器，
        // 清理必须针对重建后的那一组。
        applyCheckoutCaptureCapability()

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
                logHandoffDiagnosticsSampled(shoppingPackage, previousShoppingPackage)
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
            ).also { dispatched ->
                dispatchPaymentHandoffIfNeeded(
                    dispatched = dispatched,
                    reason = invalidationReason,
                    shoppingPackage = previousShoppingPackage,
                    foregroundPackage = shoppingPackage.ifBlank { null }
                )
            }
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
            // 从购物页切到微信支付的瞬间常有一帧节点树为空；这里只暂时隐藏悬浮按钮，
            // 不把稳定结算页和截图缓冲清掉，否则支付结果到达时永远无会话可关联。
            // D-9 裁定 A：该帧同样走不到敏感判定，必须中断"连续合格帧"计数 ——
            // 否则"合格页×2 → 空树帧 → 合格页×1"会凑满阈值，而它们并非真连续。
            sensitivePageLatch.onUnobservableFrame()
            clearUserRetryCaptureContext()
            dispatchOverlayInvalidation(
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
                    ).also { dispatched ->
                        dispatchPaymentHandoffIfNeeded(
                            dispatched = dispatched,
                            reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                            shoppingPackage = shoppingPackage,
                            foregroundPackage = rootPackage
                        )
                    }
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

            // P1 事件隔离：自身悬浮层与系统 UI 的内容变化事件只是"顺带唤醒"，
            // 页面本身没有变化。这类事件被归属为购物包后会驱动整树重采集，
            // 动态文案（轮播/倒计时）一旦落入指纹就会被误判为新订单并重复截图。
            // 已有稳定结算页时直接跳过采集，保留悬浮按钮与会话状态不受影响。
            if (shouldSkipForeignEventCollection(eventPackage, shoppingPackage)) {
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
            // D-9：每帧都交给锁存对象 —— 敏感帧刷新锁存；读到明确非敏感购物页
            // 连续 N 帧则提前解除（解除只作用于断言通道，理由见 SensitivePageLatch）。
            // 判据内的"明确" = 白名单购物包 ∧ 具体购物角色，空树帧（UNKNOWN）与
            // 首页（SHOPPING_HOME）均不合格 ⇒ 天然排除"空树帧无法判定敏感"的陷阱。
            val shoppingObservedAt = System.currentTimeMillis()
            val latchReleased = sensitivePageLatch.onShoppingObservation(
                shoppingPackage = shoppingPackage,
                pageRole = observation.pageRole,
                isSensitivePage = observation.isSensitivePage,
                now = shoppingObservedAt
            )
            if (latchReleased) {
                JotBillLog.i(
                    TAG,
                    "敏感页锁存提前解除: 连续 ${SensitivePageLatch.RELEASE_FRAME_THRESHOLD} 帧明确非敏感购物页, " +
                        "pkg=$shoppingPackage, role=${observation.pageRole}"
                )
            }
            // 付款确认弹窗的帧级敏感门禁依据：只在确认当前帧非敏感时才允许弹出。
            PendingConfirmOverlayManager.onObservationState(
                sensitive = observation.isSensitivePage,
                foregroundPackage = shoppingPackage
            )
            when (val detection = pageDetector.detect(observation)) {
                is CheckoutPageDetection.Accepted -> {
                    logCheckoutGateSampled(observation, rejection = null)
                    if (RecognitionCapabilities.checkoutCaptureEnabled) {
                        // 结算页也必须具备手动截图上下文。
                        //
                        // 真机数据：结算态（activeCheckout）从建立到转入交接挂起仅存活
                        // 469ms（京东 a23f589b）~5.5s（京东 e3b01a29），拼多多同样只有
                        // 2067ms。挂起后 activeCheckout 被置空，用户在结算页上的每一次
                        // 点击都返回 NO_ACTIVE_CHECKOUT——本轮日志中连续失败 14 次。
                        // 此前这里调用 clearUserRetryCaptureContext()，等于在唯一真正
                        // 需要手动补截的页面上主动移除了兜底能力。
                        // 改为建立上下文：用户随时可手动补截，不再受那不足一秒的窗口限制。
                        // 该上下文只承载脱敏元数据，且 CHECKOUT 角色本就在
                        // canBufferUserRetrySnapshot() 允许集合内；敏感页已在更前面拦截。
                        setUserRetryCaptureContext(observation)
                        // 门禁通过即通知悬浮层进入"处理中"：候选期（稳定窗口确认前，
                        // 指纹漂移时可达数十秒）此前零反馈，按钮停留在 HIDDEN 会让用户
                        // 以为识别未工作（截图链路实际照常运行）。后续 Stable 事件、
                        // Invalidated 或截图结果会自然覆盖该状态。
                        checkoutCaptureOverlayManager?.onCheckoutCandidate(observation)
                        // 新指纹只是“候选观察”，要等稳定窗口确认后才能替换当前结算页；
                        // 提前清空会在 PDD 内部支付中间页切微信时丢掉手头正在提交的会话。
                        stabilityCoordinator.observe(detection.observation)
                    } else {
                        // 精简模式：结算页只保留"读文本 + 敏感判定"，不进入稳定帧监测。
                        // 上面三行必须整组跳过——任一行单独漏掉都会留下截图或 AI 入口：
                        //   setUserRetryCaptureContext → 截图协调器的手动补截上下文
                        //   onCheckoutCandidate         → 悬浮按钮进入"处理中"（诱导点击截图）
                        //   stabilityCoordinator.observe → Stable 事件 → 自动截图 + buffer 落图
                        // 刻意留在门外的部分（不受开关影响）：
                        //   AccessibilityPageSummaryCollector.collect / sensitivePageLatch
                        //     / PendingConfirmOverlayManager.onObservationState
                        //   它们只读节点文本、不截图，且是付款确认弹窗的敏感帧门禁依据；
                        //   跟着开关一起关掉会让弹窗永远等不到"最近一帧确认非敏感"，
                        //   退化到只剩系统通知兜底。
                        clearUserRetryCaptureContext()
                    }
                }

                is CheckoutPageDetection.Rejected -> {
                    logCheckoutGateSampled(observation, rejection = detection.reason)
                    val reason = invalidationReason(observation, detection.reason)
                    // 同购物包内的非结算中间页（拼多多提交订单后的收银台）是支付旅程
                    // 的一部分：保留稳定结算引用等待切支付应用，否则快照与会话上下文
                    // 会被提前销毁，支付证据只能落 NO_ACTIVE_SESSION 孤儿待确认。
                    val retainForPaymentInterstitial = shouldRetainCheckoutAcrossPaymentInterstitial(
                        CheckoutObservationInvalidation(
                            reason = reason,
                            shoppingPackage = observation.shoppingPackage,
                            pageFingerprint = observation.pageFingerprint,
                            invalidatedAt = System.currentTimeMillis()
                        )
                    )
                    if (!retainForPaymentInterstitial) {
                        currentStableCheckout = null
                        handoffStableCheckout = null
                        handoffForegroundPackage = null
                    }
                    val userRetryCaptureAvailable = if (
                        RecognitionCapabilities.checkoutCaptureEnabled &&
                            reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE
                    ) {
                        // 手动重试上下文就绪，或用户断言通道可用（服务存活且 AI 已配置），
                        // 都让按钮进入 READY：识别失败/普通购物页不再表现为"按钮不可用"，
                        // 此时的点击由断言通道接管并直接产出待确认。
                        setUserRetryCaptureContext(observation) || isUserAssertedCaptureAvailable()
                    } else {
                        // 精简模式同样落到这里：不得留下手动补截上下文，否则
                        // snapshotCaptureCoordinator 仍持有一个可截图的 USER_RETRY 上下文。
                        clearUserRetryCaptureContext()
                        false
                    }
                    stabilityCoordinator.invalidate(
                        reason = reason,
                        shoppingPackage = shoppingPackage,
                        pageFingerprint = observation.pageFingerprint
                    )
                    if (reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
                        // 精简模式：不向悬浮按钮投递状态。按钮的可见性实际由
                        // Invalidated 事件驱动（见 dispatchEvent），这里跳过只是
                        // 少一次冗余投递 —— 真正的隐藏在 dispatchEvent 的门禁里。
                        if (RecognitionCapabilities.checkoutCaptureEnabled) {
                            checkoutCaptureOverlayManager?.onSupportedShoppingNonCheckout(
                                shoppingPackage = observation.shoppingPackage,
                                pageFingerprint = observation.pageFingerprint,
                                userRetryCaptureAvailable = userRetryCaptureAvailable
                            )
                        }
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
            JotBillLog.w(TAG, "读取无障碍节点摘要失败", error)
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
        clearUserRetryCaptureContext()
        snapshotCaptureCoordinator?.onServiceInterrupted()
        purchaseIntentCoordinator?.onServiceInterrupted()
        stabilityCoordinator.invalidate(CheckoutInvalidationReason.SERVICE_INTERRUPTED)
        checkoutCaptureOverlayManager?.onServiceInterrupted()
        // 服务中断后页面观察不再连续，L3 跨帧易变观察窗口失去意义，
        // 清空防止陈旧观察污染重新连接后的判定。
        AccessibilityPageSummaryCollector.resetVolatileTracker()
        JotBillLog.w(TAG, "结算页无障碍观察服务被中断")
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
        clearUserRetryCaptureContext()
        if (activeService === this) {
            activeService = null
        }
        setObservationListener(null)
        JotBillLog.i(TAG, "结算页无障碍观察服务已解除绑定")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // 与协调器熔断相区分：本路径会把 snapshotCaptureCoordinator 置空，
        // 悬浮按钮回调走空引用；熔断则保留引用并返回 SERVICE_NOT_CONNECTED。
        JotBillLog.i(TAG, "结算页无障碍观察服务开始销毁: previouslyConnected=$serviceConnected")
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        currentStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
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
        JotBillLog.i(TAG, "结算页无障碍观察服务已销毁")
        super.onDestroy()
    }

    /** 服务连接时创建与当前服务绑定的 AI 客户端，失败时保留无 AI 的安全路径。 */
    private fun initializeAiClient() {
        // 精简模式：结算页 AI 派发整条不可达（无协调器 ⇒ 无 Ready ⇒ 无 dispatch）。
        // 提前返回即省掉 CheckoutAiVisionClient 构造 —— 它会建一个 OkHttpClient
        // （Dispatcher 线程池 + ConnectionPool + CleanupThread），
        // 在精简版下这个连接池永远不会有任何请求。开关打开时行为完全不变。
        if (!RecognitionCapabilities.checkoutCaptureEnabled) {
            JotBillLog.i(TAG, "结算页 AI 客户端跳过初始化: 截图识别链路已停用")
            return
        }
        val client = try {
            CheckoutAiVisionClient(
                database = JotBillDatabaseProvider.get(applicationContext),
                secretStore = KeyStoreSecretStore(applicationContext)
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "结算页 AI 客户端初始化失败", error)
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
            JotBillLog.w(TAG, "支付匹配数据库初始化失败", error)
            null
        }
        if (database == null) {
            return
        }
        val matcher = CheckoutPaymentMatcher(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(aiLifecycleLock) {
            paymentMatcher = matcher
            matchingScope = scope
            activeAiReferences.clear()
        }
        // 启动期证据兜底扫描（批 V-5 扩展为两件事）：
        //   ① 为历史“证据先到、AI 未返回”的会话补齐 EVIDENCE_ONLY 候选关联；
        //   ② 会话仍停在“等 AI 定论”而进程已被杀、定论永不返回 ⇒ 落待确认，
        //      避免“证据在、用户看不到”的静默窗口（`IMPL_PLAN` §2.4 D-3 兜底 2）。
        // 挂载点说明：这里（服务连接时）是唯一挂载点，且与正常流程不竞争 ——
        // 上面刚执行过 `shutdownAiClient()` 取消旧 AI 作用域，此刻没有在飞请求，
        // 因此“会话处于等待定论状态”必然是死状态。
        scope.launch {
            try {
                val outcome = matcher.backfillEvidenceOnlyCandidates()
                if (outcome.candidatesBackfilled > 0 || outcome.pendingsPromoted > 0) {
                    JotBillLog.i(
                        TAG,
                        "支付证据启动期兜底扫描: candidates=${outcome.candidatesBackfilled}, " +
                            "pendings=${outcome.pendingsPromoted}"
                    )
                }
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "支付证据历史会话补齐失败", error)
            }
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
        val dispatchStartedAt = System.currentTimeMillis()
        JotBillLog.i(
            TAG,
            "结算页 AI 派发: session=${reference.sessionId.take(8)}, " +
                "intent=${ready.intent.wireValue}, requestKey=${requestKey.take(24)}"
        )

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
                            JotBillLog.w(TAG, "AI 会话预写入失败", error)
                        }
                    }
                    clientOwnsSnapshot = true
                    val result = client.recognize(ready)
                    if (result is com.jotbill.app.ai.AiVisionResult.Failed) {
                        JotBillLog.i(
                            TAG,
                            "AI 视觉识别失败: code=${result.code}, attempts=${result.attempts}, " +
                                "httpStatus=${result.httpStatus}, retryable=${result.retryable}, " +
                                "requestId=${result.requestId}"
                        )
                    }
                    if (matcher != null) {
                        // 断言标记先摘除再落库：无论后续成败都不残留集合项。
                        val isAssertedSession = synchronized(aiLifecycleLock) {
                            assertedSessionIds.remove(reference.sessionId)
                        }
                        try {
                            val matchResult = matcher.recordAiResult(result)
                            JotBillLog.i(TAG, "AI 结果匹配落库结果: ${formatMatchResult(matchResult)}")
                            if (isAssertedSession &&
                                result is com.jotbill.app.ai.AiVisionResult.Succeeded
                            ) {
                                // 断言通道的收口：识别成功且无支付证据时立即转待确认；
                                // 失败结果由 recordAiResult 的占位分支处理，无需在此收口。
                                val promoted = matcher.promoteAssertedSessionToPending(
                                    reference.sessionId
                                )
                                JotBillLog.i(
                                    TAG,
                                    "断言截图会话收口: session=${reference.sessionId.take(8)}, " +
                                        "promoted=$promoted"
                                )
                            }
                        } catch (error: RuntimeException) {
                            JotBillLog.w(TAG, "AI 结果匹配写入失败", error)
                        }
                    }
                    notifyAiResult(result)
                } catch (error: CancellationException) {
                    JotBillLog.i(
                        TAG,
                        "结算页 AI 任务被取消: session=${reference.sessionId.take(8)}, " +
                            "elapsed=${System.currentTimeMillis() - dispatchStartedAt}ms"
                    )
                    throw error
                } catch (error: Throwable) {
                    JotBillLog.w(TAG, "结算页 AI 任务异常", error)
                } finally {
                    if (!clientOwnsSnapshot) {
                        ready.snapshot.release()
                    }
                    synchronized(aiLifecycleLock) {
                        if (activeAiReferences[reference.sessionId] == reference) {
                            activeAiReferences.remove(reference.sessionId)
                        }
                    }
                    JotBillLog.i(
                        TAG,
                        "结算页 AI 任务结束: session=${reference.sessionId.take(8)}, " +
                            "elapsed=${System.currentTimeMillis() - dispatchStartedAt}ms"
                    )
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
                JotBillLog.w(TAG, "截图结果监听处理失败", error)
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
                JotBillLog.w(TAG, "购买意图结果监听处理失败", error)
            }
        }
    }

    // 重命名为 Internal 后缀，避免与 Companion 中同名的 @JvmStatic 方法产生 JVM 签名冲突。
    private fun setPurchaseIntentResultConsumerInternal(consumer: PurchaseIntentResultCallback?) {
        purchaseIntentResultConsumer = consumer
    }

    /**
     * 接入改造 B-3 / E-2 的孤儿落库出口：支付结果无法自动归属（0 命中 / Ambiguous）
     * 或支付过程槽位缺图（PAYMENT_PROCESS_SNAPSHOT_MISSING）时，统一落待确认，
     * 不静默丢弃。协调器是纯内存调度器，落库必须由本层完成。
     */
    private fun configurePaymentSignalOrphanSink() {
        purchaseIntentCoordinator?.setPaymentSignalOrphanSink { signal, conflictReason, _, sessionIds ->
            val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
            if (matcher == null) {
                JotBillLog.w(
                    TAG,
                    "支付信号无法落待确认(匹配器不可用): reason=$conflictReason, " +
                        "amount=${signal.amountMinor}"
                )
                return@setPaymentSignalOrphanSink
            }
            val scope = synchronized(aiLifecycleLock) { matchingScope } ?: evidenceFallbackScope
            scope.launch {
                try {
                    matcher.recordOrphanPaymentSignal(
                        signal.toPaymentNotificationFallbackSignal(),
                        sourceType = "ACCESSIBILITY",
                        conflictReason = conflictReason,
                        sessionIds = sessionIds
                    )
                    JotBillLog.i(
                        TAG,
                        "支付信号已转待确认: reason=$conflictReason, amount=${signal.amountMinor}, " +
                            "sessions=${sessionIds.size}"
                    )
                } catch (error: RuntimeException) {
                    JotBillLog.w(TAG, "支付信号落待确认失败", error)
                }
            }
        }
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
                JotBillLog.i(TAG, "支付窗口列表(${inventory.size}): ${inventory.joinToString(" | ")}")
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
            JotBillLog.i(
                TAG,
                "支付结果页无可用节点树: pkg=$paymentPackage, " +
                    "type=${AccessibilityEvent.eventTypeToString(event.eventType)}, class=${event.className}"
            )
            maybeCapturePaymentResultScreenshot(paymentPackage, handoffContext, sensitivePageSeen = false)
            return
        }

        var sawResultPage = false
        // 本轮扫描中只要有任一窗口被判定为敏感（支付密码/付款码/人脸识别等），
        // 整轮都不得走截图 AI 兜底：截图会把密码键盘、付款码一并发往外部模型。
        var sawSensitivePage = false
        try {
            for (root in roots) {
                try {
                    val observation = AccessibilityPageSummaryCollector.collect(
                        shoppingPackage = paymentPackage,
                        windowClass = event.className?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        root = root,
                        observedAt = observedAt,
                        includeHiddenText = true
                    )
                    // 付款确认弹窗的帧级敏感门禁依据（支付密码页帧为敏感时不得弹出）。
                    PendingConfirmOverlayManager.onObservationState(
                        sensitive = observation.isSensitivePage,
                        foregroundPackage = paymentPackage
                    )

                    // 敏感判定必须先于页面角色分支：微信支付密码页常被分类为
                    // UNKNOWN，若沿用“先判角色再判敏感”的顺序，敏感页会从
                    // role != PAYMENT_RESULT 的 continue 分支溜走，最终触发截图兜底。
                    if (observation.isSensitivePage) {
                        sawSensitivePage = true
                        sensitivePageLatch.onPaymentSensitiveFrame(System.currentTimeMillis())
                        // 该分支在支付页每帧命中，真机 .tmp-device-capture-20260824-142754
                        // 中一次支付即刷出数百行，把"支付结果页解析到证据"
                        // "AI 结果匹配落库"等关键链路日志完全淹没。
                        // 该日志是隐私红线（敏感页禁止提取与截图）的证据，不可删除，只能限流。
                        logScreenshotFallbackBlockedSampled(
                            reason = "支付包页面为敏感页面，跳过证据提取与截图兜底",
                            paymentPackage = paymentPackage,
                            extra = "role=${observation.pageRole}, class=${observation.windowClass}"
                        )
                        continue
                    }

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
                        JotBillLog.i(
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

                    // 从节点文本和 contentDescription 提取结构化证据
                    // （敏感页已在角色判断前统一拦截并 continue，此处无需重复判定）
                    val pageText = collectPageText(root)
                    val parseResult = PaymentResultParser.parseResult(
                        pageText = pageText,
                        sourcePackage = paymentPackage,
                        pageFingerprint = observation.pageFingerprint,
                        observedAt = observation.observedAt
                    )
                    val evidence = when (parseResult) {
                        is PaymentResultParseResult.Success -> parseResult.evidence
                        is PaymentResultParseResult.PersonalTransfer -> {
                            // 个人转账（好友收款）不落消费证据：直接转待确认转账，
                            // 避免 AI/文本两条路径各落一条消费金额。
                            JotBillLog.i(
                                TAG,
                                "支付结果页识别为个人转账: 金额=${parseResult.transfer.amountMinor}分, " +
                                    "收款人=${parseResult.transfer.recipient ?: "未知"}"
                            )
                            val transfer = parseResult.transfer
                            evidenceFallbackScope.launch {
                                recordPersonalTransferFromResultPage(transfer, paymentPackage)
                            }
                            return
                        }
                        is PaymentResultParseResult.Failure -> {
                            JotBillLog.i(TAG, "支付结果页未生成成功证据: reason=${parseResult.reason}")
                            maybeCapturePaymentResultScreenshot(
                                paymentPackage,
                                handoffContext,
                                sensitivePageSeen = sawSensitivePage
                            )
                            return
                        }
                        PaymentResultParseResult.NotPaymentResult -> {
                            JotBillLog.d(TAG, "支付结果页未命中可确认的结果语义")
                            maybeCapturePaymentResultScreenshot(
                                paymentPackage,
                                handoffContext,
                                sensitivePageSeen = sawSensitivePage
                            )
                            return
                        }
                    }

                    if (!markPaymentResultEvent(evidence.sourceEventKey, observation.observedAt)) {
                        JotBillLog.d(TAG, "支付结果页同一事件已处理，跳过重复解析: ${evidence.sourceEventKey.take(12)}")
                        return
                    }
                    lastSuccessfulPaymentEvidenceAt = System.currentTimeMillis()
                    JotBillLog.i(
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
                                JotBillLog.w(TAG, "支付结果页无法初始化证据数据库", error)
                                return
                            }
                            CheckoutPaymentMatcher(database)
                        }
                    val scope = synchronized(aiLifecycleLock) { matchingScope } ?: evidenceFallbackScope
                    scope.launch {
                        try {
                            val persistedSessionId = recordPaymentResultEvidence(
                                matcher = matcher,
                                paymentPackage = paymentPackage,
                                evidence = evidence,
                                handoffContext = handoffContext
                            )
                            // 改造 A-3（批 V-4）：证据落库返回后**在同一协程内**派发 AI。
                            // 顺序保证：suspend 函数返回即证据已落库（含"落待确认"分支），
                            // 故 trigger 必然晚于落库；写在本 launch 之外则 trigger 会先执行。
                            // ⚠️ 只接主路径：截图兜底路径自己已送 AI，不得二次派发。
                            val outcome = dispatchPaymentResultIntent(paymentPackage, evidence)
                            // 批 V-5（改造 D-2 ②）：派发**不可得**时立即落待确认 —— 改造 D 已把
                            // 落卡推迟到 AI 定论之后，此处是"定论永不返回"的第一道安全网。
                            // COORDINATOR_ALREADY_RECORDED ⇒ 协调器已落孤儿卡，不得重复落。
                            if (outcome == PaymentResultDispatchOutcome.NOT_DISPATCHED) {
                                promotePendingWhenAiUnavailable(matcher, persistedSessionId)
                            }
                        } catch (error: Exception) {
                            JotBillLog.w(TAG, "支付结果页证据落库失败", error)
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
                                JotBillLog.w(TAG, "支付结果页证据兜底落库失败", fallbackError)
                            }
                        }
                    }
                    return
                } catch (error: RuntimeException) {
                    JotBillLog.w(TAG, "支付结果页候选根节点解析失败，继续检查下一窗口", error)
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
            maybeCapturePaymentResultScreenshot(
                paymentPackage,
                handoffContext,
                sensitivePageSeen = sawSensitivePage
            )
        }
    }

    /**
     * 微信 LiteApp 成功页对无障碍服务暴露空树时，用系统截图 + AI 视觉兜底提取证据。
     *
     * @param sensitivePageSeen 本轮观察是否见过敏感页面；为 true 时禁止截图，
     * 避免支付密码键盘、付款码、人脸识别画面被编码后发往外部 AI 服务。
     */
    private fun maybeCapturePaymentResultScreenshot(
        paymentPackage: String,
        handoffContext: PaymentResultHandoffContext?,
        sensitivePageSeen: Boolean
    ) {
        // 精简模式开关：这条兜底虽是"证据链路"的位置，做的却是 takeScreenshot +
        // 外部视觉模型，与结算页自动截图同属一类动作，必须一起停掉，否则
        // "关闭后日志中不再有截图"这条验收判据不成立。
        // 结果页**文本**解析主路径（PaymentResultParser）不经过本方法，不受影响。
        if (!RecognitionCapabilities.checkoutCaptureEnabled) {
            logScreenshotFallbackBlockedSampled(
                reason = "结算页截图识别链路已停用",
                paymentPackage = paymentPackage
            )
            return
        }
        // P0 隐私红线：敏感支付页面一律禁止截图 AI 兜底，且不受节流状态影响。
        if (sensitivePageSeen) {
            logScreenshotFallbackBlockedSampled("本轮观察到敏感页面", paymentPackage)
            return
        }
        // 支付旅程门槛：无任何支付交接上下文（既非购物应用→支付应用的结算交接，
        // 也无截图协调器的最近支付交接引用）说明用户只是在日常使用微信/支付宝，
        // 此时截图会把聊天记录、朋友圈等无关内容编码后发往外部 AI——一律禁止。
        // 结果页文本解析主路径不受影响（无截图、无外发）。
        if (handoffContext == null) {
            JotBillLog.i(TAG, "支付结果页截图兜底跳过: 无支付交接上下文 pkg=$paymentPackage")
            return
        }
        val now = System.currentTimeMillis()
        // 空节点树帧无法自行判定敏感，用锁存窗口覆盖密码页与空树帧交替的场景。
        // D-9 风控①：本通道是**自动**兜底，不读解除水位 —— 支付包内的锁存
        // 有效期逻辑与 D-9 之前完全一致（解除只作用于断言/手动通道）。
        if (sensitivePageLatch.isPaymentFallbackBlocked(now)) {
            logScreenshotFallbackBlockedSampled(
                "敏感页面锁存窗口内",
                paymentPackage,
                extra = "elapsed=${sensitivePageLatch.paymentLatchAgeMillis(now)}ms"
            )
            return
        }
        if (
            paymentScreenshotFallbackInFlight ||
            now - lastPaymentScreenshotFallbackAt < 15_000L ||
            now - lastSuccessfulPaymentEvidenceAt < 90_000L
        ) {
            return
        }
        // AccessibilityService.takeScreenshot 为 API 30+；低版本系统直接禁用该兜底
        //（NoSuchMethodError 是 Error，不能靠外层 RuntimeException 捕获）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            JotBillLog.i(TAG, "支付结果页截图兜底不可用: 需要 Android 11+，当前 API ${Build.VERSION.SDK_INT}")
            return
        }
        val activeRootPackage = try {
            rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        if (
            activeRootPackage.isNotBlank() &&
            !pageDetector.supportsPaymentPackage(activeRootPackage)
        ) {
            JotBillLog.i(TAG, "支付结果页截图兜底跳过: 当前前台不是支付应用 pkg=$activeRootPackage")
            return
        }
        val client = synchronized(aiLifecycleLock) { aiVisionClient }
        if (client == null) {
            JotBillLog.i(TAG, "支付结果页截图兜底未启用: AI 客户端不可用")
            return
        }
        lastPaymentScreenshotFallbackAt = now
        paymentScreenshotFallbackInFlight = true
        // MIUI 等系统可能静默丢弃 takeScreenshot 回调，8 秒后强制复位 in-flight 标志，
        // 避免"失败后立刻在原页重试"永远被 CAPTURE_IN_FLIGHT/兜底永久停用。
        val watchdogJob = evidenceFallbackScope.launch {
            delay(PAYMENT_SCREENSHOT_FALLBACK_WATCHDOG_MILLIS)
            if (paymentScreenshotFallbackInFlight) {
                paymentScreenshotFallbackInFlight = false
                JotBillLog.w(TAG, "支付结果页截图兜底看门狗复位（系统回调未达）")
            }
        }
        try {
            @Suppress("NewApi")
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotCallbackExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        watchdogJob.cancel()
                        paymentScreenshotFallbackInFlight = false
                        val bytes = encodePaymentScreenshot(screenshot)
                        if (bytes == null || bytes.isEmpty()) {
                            bytes?.fill(0)
                            JotBillLog.w(TAG, "支付结果页截图兜底编码失败")
                            return
                        }
                        JotBillLog.i(TAG, "支付结果页截图兜底已取得: ${bytes.size} bytes")
                        evidenceFallbackScope.launch {
                            consumePaymentResultScreenshot(client, bytes, paymentPackage, handoffContext)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        watchdogJob.cancel()
                        paymentScreenshotFallbackInFlight = false
                        JotBillLog.w(TAG, "支付结果页截图兜底失败: errorCode=$errorCode")
                    }
                }
            )
        } catch (error: RuntimeException) {
            watchdogJob.cancel()
            paymentScreenshotFallbackInFlight = false
            JotBillLog.w(TAG, "支付结果页截图兜底调用失败", error)
        }
    }

    /**
     * 个人转账转待确认（文本解析与截图 AI 两条路径共用）。
     *
     * 由 Matcher 按包名+2 分钟桶+金额+收款人幂等收敛，重复识别不产生第二条。
     */
    private suspend fun recordPersonalTransferFromResultPage(
        transfer: PaymentPersonalTransfer,
        paymentPackage: String
    ) {
        val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
            ?: run {
                val database = JotBillDatabaseProvider.get(applicationContext)
                CheckoutPaymentMatcher(database)
            }
        try {
            matcher.recordPersonalTransfer(
                amountMinor = transfer.amountMinor,
                paymentPackage = paymentPackage,
                recipient = transfer.recipient,
                occurredAt = transfer.occurredAt
            )
        } catch (error: Exception) {
            JotBillLog.w(TAG, "个人转账待确认落库失败", error)
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
            JotBillLog.i(
                TAG,
                "支付结果页截图兜底未生成证据: code=${failed.code}, attempts=${failed.attempts}, " +
                    "httpStatus=${failed.httpStatus}"
            )
            return
        }
        val fields = result.fields
        // 个人转账（好友收款）不落消费证据：直接转待确认转账，收款人即 merchant。
        // 与文本解析路径共用 Matcher 确定性 ID，两条路径识别同一笔只落一条。
        if (fields.transferType == CheckoutAiVisionClient.TRANSFER_TYPE_PERSONAL) {
            JotBillLog.i(
                TAG,
                "支付结果页截图识别为个人转账: 金额=${fields.amountMinor}分, " +
                    "收款人=${fields.merchant ?: "未知"}"
            )
            recordPersonalTransferFromResultPage(
                PaymentPersonalTransfer(
                    recipient = fields.merchant,
                    amountMinor = fields.amountMinor,
                    occurredAt = null
                ),
                paymentPackage
            )
            return
        }
        val occurredAt = fields.occurredAt?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        } ?: System.currentTimeMillis()
        // 截图 AI 时效闸门：AI 读到的显示时间距当前超过 30 分钟，说明截图是
        // 历史账单详情/聊天凭证而非当次支付结果（AI 偶发误判时兜底），拒绝落证据。
        if (kotlin.math.abs(occurredAt - System.currentTimeMillis()) > 30 * 60 * 1000L) {
            JotBillLog.i(
                TAG,
                "支付结果页截图证据时间超出时效窗口，判定为历史信息回放: occurredAt=$occurredAt"
            )
            return
        }
        // 与无障碍结果页共用同一个去重键：同包名、同一 30 秒桶、同金额、同订单号只落一次。
        // 桶宽须与主路径（PaymentResultParser）一致 —— 只改一处则另一条路径仍按旧桶撞键。
        val sourceEventKey = sha256(
            "result-event|$paymentPackage|${occurredAt / 30_000L}|${fields.amountMinor}|" +
                "${fields.orderId.orEmpty()}"
        )
        val now = System.currentTimeMillis()
        if (!markPaymentResultEvent(sourceEventKey, now)) {
            JotBillLog.d(TAG, "支付结果页截图兜底同一事件已处理，跳过重复落库")
            return
        }
        lastSuccessfulPaymentEvidenceAt = now
        val evidence = PaymentResultEvidence(
            amountMinor = fields.amountMinor,
            merchant = fields.merchant,
            orderId = fields.orderId,
            // 截图 AI 只看到一张图，无法证明支付真的完成：标记为未证实状态，
            // 由 Matcher 强制转待确认，不允许自动写入账本。
            paymentStatus = PAYMENT_STATUS_AI_UNVERIFIED,
            occurredAt = occurredAt,
            rawTextHash = sha256(
                "payment-result-screenshot|$paymentPackage|${fields.amountMinor}|" +
                    "${fields.merchant.orEmpty()}|${fields.orderId.orEmpty()}"
            ),
            sourceEventKey = sourceEventKey
        )
        JotBillLog.i(TAG, "支付结果页截图兜底解析到证据（未证实，需用户确认）: 金额=${evidence.amountMinor}分")
        val matcher = synchronized(aiLifecycleLock) { paymentMatcher }
            ?: run {
                val database = JotBillDatabaseProvider.get(applicationContext)
                CheckoutPaymentMatcher(database)
            }
        try {
            val persistedSessionId = recordPaymentResultEvidence(
                matcher = matcher,
                paymentPackage = paymentPackage,
                evidence = evidence,
                handoffContext = handoffContext,
                aiUnverified = true
            )
            // 批 V-5（改造 D-2 ②）：本路径**不会有 AI 定论** —— 上面的
            // `client.recognizePaymentResult`（:1803）是视觉客户端**直调**，不经 matcher 的
            // AI 定论链路（`recordAiResult`）⇒ 没有任何后续结论会来推进该会话。
            // 改造 D 之前，该形态靠"证据先到即落卡"侥幸可见；推迟落卡后必须在此显式补上，
            // 否则截图兜底证据会静默悬置到下次启动扫描。
            promotePendingWhenAiUnavailable(matcher, persistedSessionId)
        } catch (error: Exception) {
            JotBillLog.w(TAG, "支付结果页截图兜底证据落库失败", error)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun markPaymentResultEvent(sourceEventKey: String, now: Long): Boolean {
        recentPaymentResultEventKeys.entries.removeAll {
            now - it.value > PAYMENT_RESULT_EVENT_MEMORY_MILLIS
        }
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
        val synchronizedCheckout = synchronized(this) {
            if (handoffForegroundPackage == paymentPackage) handoffStableCheckout else null
        }
        val fallbackReference = snapshotCaptureCoordinator?.currentPaymentHandoffReference()
        val stableCheckout = synchronizedCheckout ?: fallbackReference?.let { reference ->
            StableCheckoutPage(
                observation = CheckoutPageObservation(
                    shoppingPackage = reference.shoppingPackage,
                    pageRole = reference.pageRole,
                    pageFingerprint = reference.pageFingerprint,
                    hasFinalAmountSignal = false,
                    hasCheckoutActionSignal = false,
                    isSensitivePage = false,
                    windowClass = "",
                    normalizedTextHash = "",
                    observedAt = reference.capturedAt
                ),
                stableAt = reference.capturedAt
            )
        } ?: return null
        val observation = stableCheckout.observation
        val slotId = checkoutSnapshotSlotId(observation.shoppingPackage)
        val bufferedReference = checkoutSnapshotBuffer?.findReference(
            slotId = slotId,
            pageFingerprint = observation.pageFingerprint
        )
        val reference = bufferedReference ?: synchronized(aiLifecycleLock) {
            activeAiReferences.values
                .filter {
                    it.shoppingPackage == observation.shoppingPackage &&
                        it.pageFingerprint == observation.pageFingerprint
                }
                .singleOrNull()
        } ?: fallbackReference
        // 按秒采样：支付应用事件密集，避免交接上下文日志冲掉缓冲区关键日志。
        val loggedAt = System.currentTimeMillis()
        if (loggedAt - lastHandoffContextLogAt >= 1_000L) {
            lastHandoffContextLogAt = loggedAt
            JotBillLog.i(
                TAG,
                "支付交接上下文: stable=${stableCheckout.observation.pageFingerprint.take(12)}, " +
                    "buffered=${bufferedReference != null}, " +
                    "aiRef=${reference != null && reference !== fallbackReference}, " +
                    "fallback=${fallbackReference?.sessionId ?: "null"}, " +
                    "finalRef=${reference?.sessionId ?: "null"}"
            )
        }
        return PaymentResultHandoffContext(
            stableCheckout = stableCheckout,
            reference = reference,
            handoffAt = System.currentTimeMillis()
        )
    }

    /**
     * 改造 A-3（批 V-4）：结果页主路径的 AI 派发接线。
     *
     * **位置约束（三条，均有实测依据）**：
     * 1. 必须在 `recordPaymentResultEvidence` 返回之后，且与它**同一协程**顺序执行
     *    —— 写在该 `scope.launch { }` 之外时，`launch` 只是提交任务，
     *    `trigger` 会先于证据落库执行（即"证据未落库就派 AI"）；
     * 2. **只接结果页主路径**（文本解析成功），**不接截图兜底路径** ——
     *    后者在 `consumePaymentResultScreenshot` 里已自行调用
     *    `client.recognizePaymentResult` 送 AI，再接一次会让同一支付事件
     *    产生 2 次 AI 请求（违反"完成支付 ⇒ 恰好 1 次 AI 请求"）；
     * 3. **不触碰断言通道** —— 断言通道是 `performUserAssertedCapture` 内
     *    `buffer.consumeForAi(USER_RETRY) → dispatchReadyToAi` 的独立旁路，
     *    与本次接线无交集。
     *
     * 派发语义 = 消费支付过程槽位 `payment:<sessionId>` 的图并送 AI；无图/过期/
     * 归属歧义时经协调器孤儿出口落待确认（不静默丢弃）。
     *
     * @return 批 V-5（改造 D-2 ②）新增：派发档位。`NOT_DISPATCHED` 表示该会话**拿不到
     *   AI 定论**（AI 未配置 / 派发失败 / 预算耗尽 / 门禁拒绝）⇒ 调用方必须立即落待确认
     *   （改造 D 把落卡推迟到定论之后，此处是"定论永不返回"的第一道安全网）。
     *   档位口径见 [paymentResultDispatchOutcome]。
     */
    private fun dispatchPaymentResultIntent(
        paymentPackage: String,
        evidence: PaymentResultEvidence
    ): PaymentResultDispatchOutcome {
        val coordinator = purchaseIntentCoordinator
        if (coordinator == null) {
            JotBillLog.w(TAG, "支付结果页派发跳过: 购买意图协调器不可用")
            return PaymentResultDispatchOutcome.NOT_DISPATCHED
        }
        return try {
            coordinator.trigger(
                intent = PurchaseIntent.PAYMENT_RESULT,
                paymentResult = buildPaymentResultAttributionSignal(paymentPackage, evidence)
            ).paymentResultDispatchOutcome()
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "支付结果页 AI 派发失败", error)
            PaymentResultDispatchOutcome.NOT_DISPATCHED
        }
    }

    /**
     * 批 V-5（改造 D-2 ② / D-3）：**AI 定论不可得**时立即落待确认，并把
     * [PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE] 作为日志锚点（"派发不可用率"可量化）。
     *
     * 两个调用面共用本方法：
     * 1. 结果页主路径 —— 派发档位为 `NOT_DISPATCHED`（AI 未配置 / 派发失败 / 预算耗尽 /
     *    门禁拒绝）；
     * 2. 结果页截图兜底路径 —— 该路径本就直调视觉客户端、不派发结算页 AI。
     *
     * ⚠️ **不得**在 `COORDINATOR_ALREADY_RECORDED` 档位调用：那三条兜底（槽位缺图 /
     * 归属 0 命中 / Ambiguous）已由协调器孤儿出口落卡，再补一条会让同一笔支付出现两张卡。
     * 落卡本身的幂等与防重由 [CheckoutPaymentMatcher.promoteEvidenceOnlySessionToPending]
     * 内部的四道守卫保证（已有卡 / 已入账 / 已有孤儿卡 / AI 已定论）。
     *
     * @param sessionId 证据成功落库的会话 id；为空说明无会话可关联，此时该笔已由
     *   孤儿待确认（`NO_ACTIVE_SESSION` 等）承载，只需留日志。
     */
    private suspend fun promotePendingWhenAiUnavailable(
        matcher: CheckoutPaymentMatcher,
        sessionId: String?
    ) {
        if (sessionId.isNullOrBlank()) {
            JotBillLog.w(
                TAG,
                "$PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE: 无会话可关联，已由孤儿待确认承载"
            )
            return
        }
        try {
            val promoted = matcher.promoteEvidenceOnlySessionToPending(
                sessionId = sessionId,
                reason = PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE
            )
            JotBillLog.w(
                TAG,
                "$PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE: session=${sessionId.take(8)}, " +
                    "promoted=$promoted"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "AI 定论不可用时落待确认失败", error)
        }
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
     *
     * @return 批 V-5（改造 D-2 ②）新增：证据**成功落库**时的会话 id（`Accepted.sessionId`）；
     *   证据被拒（已转孤儿待确认）或无会话可关联时返回 null。调用方据此在"AI 定论不可得"
     *   时调 [CheckoutPaymentMatcher.promoteEvidenceOnlySessionToPending] 立即补落待确认。
     */
    private suspend fun recordPaymentResultEvidence(
        matcher: CheckoutPaymentMatcher,
        paymentPackage: String,
        evidence: PaymentResultEvidence,
        handoffContext: PaymentResultHandoffContext?,
        aiUnverified: Boolean = false
    ): String? {
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
                // D-24 / R4：0 命中 ⇒ 落孤儿待确认，**不猜**。
                // 此前此处有「最近活跃结算会话」兜底（findRecentCheckoutReferenceForPayment），
                // 与裁定「不回落猜测」冲突，已摘除（该方法已无调用点）。
                matcher.recordOrphanPaymentSignal(signal, sourceType = "ACCESSIBILITY")
                JotBillLog.i(TAG, "支付结果页交接引用已失效，证据已转待确认（不回落猜测）")
                return null
            }
            val result = matcher.recordPaymentEvidence(
                handoffReference,
                signal,
                sourceType = "ACCESSIBILITY",
                aiUnverified = aiUnverified
            )
            if (result is CheckoutPaymentMatchResult.Rejected) {
                matcher.recordOrphanPaymentSignal(
                    signal,
                    sourceType = "ACCESSIBILITY",
                    conflictReason = result.reason
                )
            }
            JotBillLog.i(TAG, "支付结果页证据落库结果（交接上下文）: ${formatMatchResult(result)}")
            return acceptedSessionIdOf(result)
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
                    resolution.reference,
                    signal,
                    sourceType = "ACCESSIBILITY",
                    aiUnverified = aiUnverified
                )
                if (result is CheckoutPaymentMatchResult.Rejected) {
                    matcher.recordOrphanPaymentSignal(
                        signal,
                        sourceType = "ACCESSIBILITY",
                        conflictReason = result.reason
                    )
                }
                JotBillLog.i(
                    TAG,
                    "支付结果页证据落库结果（" +
                        (if (resolution.byOrderId) "订单号" else "金额+时间窗口") +
                        "）: ${formatMatchResult(result)}"
                )
                return acceptedSessionIdOf(result)
            }
            is PaymentSessionResolution.Ambiguous -> {
                // 并发会话无法区分：不强行合并，保留证据交给已有待确认兜底。
                matcher.recordOrphanPaymentSignal(
                    signal,
                    sourceType = "ACCESSIBILITY",
                    conflictReason = "AMBIGUOUS_SESSION_IDS:${resolution.sessionIds.joinToString(",")}",
                    sessionIds = resolution.sessionIds
                )
                JotBillLog.i(TAG, "支付结果页命中并发会话(${resolution.sessionIds.size}个)，已转待确认")
                return null
            }
            PaymentSessionResolution.NoMatch -> {
                // D-24 / R4：0 命中 ⇒ 落孤儿待确认，不猜（已摘除最近活跃会话兜底）。
                matcher.recordOrphanPaymentSignal(signal, sourceType = "ACCESSIBILITY")
                JotBillLog.i(TAG, "支付结果页无关联活跃会话，已转待确认（不回落猜测）")
                return null
            }
        }
    }

    /**
     * 批 V-5：从证据落库结果取会话 id —— 仅 `Accepted`（证据确实落库）才可用于
     * "AI 定论不可得时补落待确认"；`Rejected` 已转孤儿待确认，不需要（也不应该）二次落卡。
     */
    private fun acceptedSessionIdOf(result: CheckoutPaymentMatchResult): String? =
        (result as? CheckoutPaymentMatchResult.Accepted)?.sessionId

    /** 格式化匹配决策结果用于日志，billId 非空表示阶段四 T5 已自动入账。 */
    private fun formatMatchResult(result: CheckoutPaymentMatchResult): String = when (result) {
        is CheckoutPaymentMatchResult.Accepted ->
            "Accepted(sessionId=${result.sessionId.take(8)}, status=${result.status}, matched=${result.matched}" +
                ", candidateId=${result.candidateId?.take(8)}, evidenceId=${result.evidenceId?.take(12)}" +
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
                    JotBillLog.w(TAG, "支付通知无法初始化证据数据库", error)
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
                    // D-24 / R4：0 命中 ⇒ 落孤儿待确认，不猜（已摘除最近活跃会话兜底）。
                    // 注：本路径 sourceType = NOTIFICATION，D-3 生效后此处整体短路不落。
                    matcher.recordOrphanPaymentSignal(signal, sourceType = "NOTIFICATION")
                    JotBillLog.i(TAG, "支付通知无结算会话可匹配，已转待确认（不回落猜测）")
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
                JotBillLog.i(TAG, "支付通知证据落库结果: ${formatMatchResult(result)}")
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "支付证据写入失败", error)
                try {
                    matcher.recordOrphanPaymentSignal(
                        signal,
                        sourceType = "NOTIFICATION",
                        conflictReason = "EVIDENCE_PERSIST_FAILED"
                    )
                } catch (fallbackError: RuntimeException) {
                    JotBillLog.w(TAG, "支付通知待确认兜底写入失败", fallbackError)
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
            JotBillLog.w(TAG, "支付通知回退路由失败", error)
            Companion.notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
                callback
            )
        }
    }

    /** 悬浮按钮只调用现有手动截图入口，不直接接触截图资源或 AI。 */
    /**
     * 悬浮按钮点击路由。
     *
     * 结算槽位仍有未消费的自动候选时（识别成功的正常链路），保持原手动路径语义：
     * 截图补入既有会话，支付交接时消费，多为 DUPLICATE 反馈；
     * 否则走用户断言通道——识别失败、交接挂起、普通购物页这些原路径必然
     * NO_ACTIVE_CHECKOUT/NOT_CHECKOUT_PAGE 的场景，现在都能直接产出待确认。
     */
    private fun requestManualCaptureFromOverlay() {
        // 精简模式：悬浮按钮的两条分支（原会话绑定手动补截 / 用户断言）都以
        // takeScreenshot + buffer 落图 + AI 派发收尾，属于同一条截图识别链路，
        // 在这个唯一路由点一刀切掉，避免下游两处各写一遍开关判断。
        if (!RecognitionCapabilities.checkoutCaptureEnabled) {
            JotBillLog.i(TAG, "手动截图已停用: 结算页截图识别链路关闭")
            return
        }
        val foregroundPackage = currentForegroundShoppingPackage()
        val hasLiveAutoCandidate = foregroundPackage != null && try {
            checkoutSnapshotBuffer?.findReference(checkoutSnapshotSlotId(foregroundPackage)) != null
        } catch (_: RuntimeException) {
            false
        }
        if (hasLiveAutoCandidate) {
            requestSessionBoundManualCapture()
        } else {
            performUserAssertedCapture(foregroundPackage)
        }
    }

    /** 原会话绑定的手动截图路径：仅在结算槽位有在途自动候选时由路由进入。 */
    private fun requestSessionBoundManualCapture() {
        val context = userRetryCaptureContext
        if (context != null) {
            JotBillLog.i(
                TAG,
                "手动截图上下文: role=${context.pageRole}, amountSignal=${context.observation.hasFinalAmountSignal}, " +
                    "actionSignal=${context.observation.hasCheckoutActionSignal}, sensitive=${context.isSensitivePage}, " +
                    "class=${context.observation.windowClass}"
            )
        } else {
            JotBillLog.i(TAG, "手动截图上下文缺失")
        }
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
            JotBillLog.w(TAG, "用户主动截图重试触发失败", error)
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

    /**
     * 当前前台是否为白名单购物应用。
     *
     * rootInActiveWindow 瞬时不可读（系统窗过渡、多窗口切换）时回退最近一次
     * 确认的购物页上下文——与支付兜底"空前台不拦截、非白名单前台才拦截"的
     * 既有口径一致；用户真实切到非白名单应用时仍然拒绝。
     */
    private fun currentForegroundShoppingPackage(): String? {
        val activePackage = try {
            rootInActiveWindow?.packageName?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
        if (activePackage != null) {
            return activePackage.takeIf { pageDetector.supportsShoppingPackage(it) }
        }
        return userRetryCaptureContext?.shoppingPackage
            ?.takeIf { pageDetector.supportsShoppingPackage(it) }
    }

    /** 用户断言通道是否可用：链路开关打开、服务存活且 AI 视觉客户端已配置。 */
    private fun isUserAssertedCaptureAvailable(): Boolean =
        RecognitionCapabilities.checkoutCaptureEnabled &&
            serviceConnected && synchronized(aiLifecycleLock) { aiVisionClient != null }

    /**
     * 用户断言截图通道：绕过结算页识别与会话门禁，把"用户点了截图"直接变成
     * 一条待确认交易。
     *
     * 不可绕过的门禁只剩三道：敏感页锁存（不变式第 1 条——密码页/付款码页
     * 永不截图）、前台购物包名白名单、系统截图 API。截图落在专用内存槽位
     * （user-asserted:&lt;pkg&gt;），与结算会话槽位零交互；随后立即经既有 AI 管道
     * （ensureSession → recognize → recordAiResult）生成候选，无支付证据时由
     * [CheckoutPaymentMatcher.promoteAssertedSessionToPending] 收口为待确认，
     * 支付证据晚到时仍走既有匹配链路自动核销或收敛。
     */
    private fun performUserAssertedCapture(foregroundPackage: String?) {
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            JotBillLog.i(TAG, "断言截图不可用: 需要 Android 11+，当前 API ${Build.VERSION.SDK_INT}")
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.UNAVAILABLE,
                SnapshotCaptureUnavailableReason.API_UNSUPPORTED.wireValue
            )
            return
        }
        if (!serviceConnected) {
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.UNAVAILABLE,
                SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
            )
            return
        }
        if (foregroundPackage == null) {
            JotBillLog.i(TAG, "断言截图拒绝: 前台不是白名单购物应用")
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.REJECTED,
                SnapshotCaptureRejection.NOT_CHECKOUT_PAGE.wireValue
            )
            return
        }
        // 敏感页锁存：支付应用与购物应用两把锁存任一命中即拒绝。这是本通道
        // 唯一继承自自动链路的页面判定门禁，也是不变式第 1 条的入口。
        // D-9：本通道（手动）是"提前解除"的唯一受益者 —— 读到明确非敏感购物页
        // 连续 N 帧后不再被锁存拦截；水位不作用于自动兜底（见 SensitivePageLatch）。
        if (sensitivePageLatch.isAssertionCaptureBlocked(now)) {
            JotBillLog.w(
                TAG,
                "断言截图拒绝: 敏感页锁存窗口内 " +
                    "elapsed=${sensitivePageLatch.assertionLatchAgeMillis(now)}ms, pkg=$foregroundPackage"
            )
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.REJECTED,
                SnapshotCaptureRejection.SENSITIVE_PAGE.wireValue
            )
            return
        }
        if (userAssertedCaptureInFlight) {
            JotBillLog.i(TAG, "断言截图拒绝: 截图在飞")
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.REJECTED,
                SnapshotCaptureRejection.CAPTURE_IN_FLIGHT.wireValue
            )
            return
        }
        if (now - lastUserAssertedCaptureAt < USER_ASSERTED_CAPTURE_MIN_INTERVAL_MILLIS) {
            JotBillLog.i(TAG, "断言截图拒绝: 点击过快")
            // 必须发布结果：按钮此时已进入 CAPTURING，静默返回会让它在静态页面上
            // 一直卡住（没有后续观察事件来复位状态）。
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.REJECTED,
                "USER_ASSERTED_THROTTLED"
            )
            return
        }
        val client = synchronized(aiLifecycleLock) { aiVisionClient }
        if (client == null) {
            JotBillLog.i(TAG, "断言截图拒绝: AI 视觉客户端未配置")
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.UNAVAILABLE,
                "AI_NOT_CONFIGURED"
            )
            return
        }

        lastUserAssertedCaptureAt = now
        userAssertedCaptureInFlight = true
        val watchdogJob = evidenceFallbackScope.launch {
            delay(USER_ASSERTED_CAPTURE_WATCHDOG_MILLIS)
            if (userAssertedCaptureInFlight) {
                userAssertedCaptureInFlight = false
                JotBillLog.w(TAG, "断言截图看门狗复位（系统回调未达）")
            }
        }
        JotBillLog.i(TAG, "断言截图受理: pkg=$foregroundPackage")
        try {
            @Suppress("NewApi")
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotCallbackExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        watchdogJob.cancel()
                        userAssertedCaptureInFlight = false
                        val bytes = encodePaymentScreenshot(screenshot)
                        if (bytes == null || bytes.isEmpty()) {
                            bytes?.fill(0)
                            JotBillLog.w(TAG, "断言截图编码失败")
                            publishAssertedOverlayResult(
                                SnapshotCaptureStatus.FAILED,
                                SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue
                            )
                            return
                        }
                        JotBillLog.i(TAG, "断言截图已取得: ${bytes.size} bytes")
                        evidenceFallbackScope.launch {
                            dispatchUserAssertedScreenshot(bytes, foregroundPackage)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        watchdogJob.cancel()
                        userAssertedCaptureInFlight = false
                        JotBillLog.w(TAG, "断言截图失败: errorCode=$errorCode")
                        publishAssertedOverlayResult(
                            SnapshotCaptureStatus.UNAVAILABLE,
                            "SCREENSHOT_ERROR_$errorCode"
                        )
                    }
                }
            )
        } catch (error: RuntimeException) {
            watchdogJob.cancel()
            userAssertedCaptureInFlight = false
            JotBillLog.w(TAG, "断言截图调用失败", error)
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.UNAVAILABLE,
                SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
            )
        }
    }

    /**
     * 断言截图的字节处理：写入专用槽位 → 立即消费 → 派发既有 AI 管道。
     *
     * 字节卫生全程遵循"单一所有者"：buffer.store 内部复制后，入口数组在本函数
     * finally 中抹零；Stored 返回的元数据副本读取后立即 release；Ready 的所有权
     * 按 dispatchReadyToAi 的既有契约移交（返回 false 时由本调用方释放）。
     */
    private suspend fun dispatchUserAssertedScreenshot(
        imageBytes: ByteArray,
        shoppingPackage: String
    ) {
        val sessionId = CheckoutRequestKey.newSessionId()
        // 合成指纹只承载"一次断言"的幂等键：SHA-256 十六进制格式与页面指纹一致，
        // 使请求键/会话记录无需任何特判；两次断言指纹必然不同，互不合并。
        val pageFingerprint = sha256("user-asserted|$shoppingPackage|$sessionId")
        val slotId = USER_ASSERTED_SLOT_PREFIX + shoppingPackage
        val buffer = checkoutSnapshotBuffer
        if (buffer == null) {
            JotBillLog.w(TAG, "断言截图落库失败: 缓冲区不可用")
            imageBytes.fill(0)
            publishAssertedOverlayResult(
                SnapshotCaptureStatus.UNAVAILABLE,
                SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
            )
            return
        }
        try {
            val storeResult = try {
                buffer.store(
                    SnapshotCaptureRequest(
                        slotId = slotId,
                        sessionId = sessionId,
                        shoppingPackage = shoppingPackage,
                        pageRole = CheckoutPageRole.CHECKOUT,
                        pageFingerprint = pageFingerprint,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        imageBytes = imageBytes,
                        // 敏感页已在入口门禁拒绝；此处如实传 false。
                        isSensitivePage = false,
                        captureTarget = SnapshotCaptureTarget.USER_RETRY
                    )
                )
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "断言截图写入缓冲失败", error)
                null
            }
            val stored = storeResult as? SnapshotStoreResult.Stored
            if (stored == null) {
                JotBillLog.w(TAG, "断言截图落库被拒: ${storeResult}")
                publishAssertedOverlayResult(
                    SnapshotCaptureStatus.REJECTED,
                    (storeResult as? SnapshotStoreResult.Rejected)?.reason?.wireValue
                        ?: "BUFFER_STORE_FAILED"
                )
                return
            }
            val snapshotMetadata = stored.snapshot
            checkoutCaptureOverlayManager?.onCaptureResult(
                SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.STORED,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reasonCode = SnapshotCaptureStatus.STORED.wireValue,
                    sessionId = sessionId,
                    slotId = slotId,
                    shoppingPackage = shoppingPackage,
                    pageFingerprint = pageFingerprint,
                    snapshotId = snapshotMetadata.snapshotId,
                    candidateRevision = snapshotMetadata.candidateRevision
                )
            )
            snapshotMetadata.release()

            val trigger = buffer.consumeForAi(
                slotId = slotId,
                intent = PurchaseIntent.USER_RETRY,
                pageFingerprint = pageFingerprint
            )
            if (trigger !is CheckoutAiTriggerResult.Ready) {
                JotBillLog.w(TAG, "断言截图消费失败: ${trigger}")
                publishAssertedOverlayResult(
                    SnapshotCaptureStatus.REJECTED,
                    (trigger as? CheckoutAiTriggerResult.Rejected)?.reason?.wireValue
                        ?: "CONSUME_FAILED"
                )
                return
            }
            synchronized(aiLifecycleLock) { assertedSessionIds += sessionId }
            JotBillLog.i(TAG, "断言截图派发 AI: session=${sessionId.take(8)}, pkg=$shoppingPackage")
            val dispatched = dispatchReadyToAi(trigger)
            if (!dispatched) {
                // dispatchReadyToAi 返回 false 表示未进入 AI 任务，所有权归还本调用方。
                synchronized(aiLifecycleLock) { assertedSessionIds -= sessionId }
                trigger.snapshot.release()
                publishAssertedOverlayResult(
                    SnapshotCaptureStatus.UNAVAILABLE,
                    "AI_DISPATCH_FAILED"
                )
                return
            }
            // AI 已接管快照：收起按钮防止同页重复断言（pending 到达后有系统通知提示）。
            checkoutCaptureOverlayManager?.onAiDispatched()
        } finally {
            imageBytes.fill(0)
        }
    }

    /**
     * 断言截图的悬浮按钮状态反馈。
     *
     * 失败结果携带当前真实页面指纹（而非合成指纹），使 overlay 的结果门禁
     * 能采纳并把 CAPTURING 状态复位；成功结果按包名匹配放行。
     */
    private fun publishAssertedOverlayResult(
        status: SnapshotCaptureStatus,
        reasonCode: String
    ) {
        val observation = userRetryCaptureContext?.observation
        checkoutCaptureOverlayManager?.onCaptureResult(
            SnapshotCaptureResult(
                status = status,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                reasonCode = reasonCode,
                sessionId = null,
                slotId = null,
                shoppingPackage = observation?.shoppingPackage,
                pageFingerprint = observation?.pageFingerprint,
                snapshotId = null,
                candidateRevision = null
            )
        )
    }

    /**
     * 按"包名+拒绝码"采样输出结算页门禁判定结果。
     *
     * 自动截图（CHECKOUT_STABLE）必须先通过该门禁、再熬过稳定窗口才会触发。
     * 此前只有 Rejected 分支间接打印"手动截图上下文"，Accepted 分支完全静默，
     * 导致"自动截图难触发"无法归因：既分不清是门禁没过还是稳定窗口没熬够，
     * 也分不清究竟卡在哪个信号上。
     *
     * 每个组合首次出现时立即打印，之后按间隔采样，既保证每种失败原因都留痕，
     * 又不会被高频事件刷屏。
     *
     * @param observation 本次页面观察摘要，只输出信号位与截断指纹，不含页面文本。
     * @param rejection 门禁拒绝码；null 表示本次判定通过。
     */
    private fun logCheckoutGateSampled(
        observation: CheckoutPageObservation,
        rejection: CheckoutDetectionRejection?
    ) {
        val key = observation.shoppingPackage + "|" + (rejection?.wireValue ?: "ACCEPTED")
        val now = System.currentTimeMillis()
        val lastAt = lastCheckoutGateLogAt[key]
        if (lastAt != null && now - lastAt < CHECKOUT_GATE_LOG_INTERVAL_MILLIS) {
            return
        }
        lastCheckoutGateLogAt[key] = now
        JotBillLog.i(
            TAG,
            "结算页门禁: ${rejection?.wireValue ?: "ACCEPTED"}, " +
                "pkg=${observation.shoppingPackage}, role=${observation.pageRole}, " +
                "amountSignal=${observation.hasFinalAmountSignal}, " +
                "actionSignal=${observation.hasCheckoutActionSignal}, " +
                "classSemantic=${observation.hasCheckoutClassSemantic}, " +
                "sensitive=${observation.isSensitivePage}, " +
                "fp=${observation.pageFingerprint.take(12)}, " +
                "class=${observation.windowClass}"
        )
    }

    /**
     * 协调器熔断后重建截图链路，恢复自动与手动截图能力。
     *
     * 熔断（fail-closed）会 quitSafely() 退出截图线程并置 closed=true，
     * 此后手动截图恒返回 SERVICE_NOT_CONNECTED、自动链路在事件入口静默 return。
     * 该状态不可逆，此前只能靠用户手动重启无障碍服务恢复——真机日志
     * .tmp-device-capture-20260823-123024 中协调器在采集开始前就已熔断，
     * 整个 100 秒窗口内 12 次手动截图全失败、自动截图 0 次，
     * 期间门禁其实已在 12:30:45 正确识别到结算页（ACCEPTED）。
     *
     * 只重建截图相关组件（buffer / 截图协调器 / 购买意图协调器 / 悬浮层），
     * 不触碰 AI 客户端与支付匹配器：它们与熔断无关，重建会打断进行中的
     * 支付匹配与待确认收敛。
     *
     * @return true 表示已重建；false 表示服务不可用或协调器仍健康，无需重建。
     */
    private fun recoverSnapshotCoordinatorIfClosed(): Boolean {
        if (!serviceConnected) {
            return false
        }
        // 精简模式：整条链根本没被创建，snapshotCaptureCoordinator 恒为 null。
        // 若不在此拦一道，:3148 的 `coordinator == null` 会一路走到重建分支，
        // 每 10 秒凭空造出一条已关闭的"全新"协调器 —— 既白起 HandlerThread，
        // 又把 disposeCaptureChain 的清理成果全部推翻。
        if (!RecognitionCapabilities.checkoutCaptureEnabled) {
            return false
        }
        val coordinator = snapshotCaptureCoordinator
        if (coordinator != null && !coordinator.isPermanentlyClosed()) {
            return false
        }
        val now = System.currentTimeMillis()
        // 重建自身需要新建截图线程与缓冲，失败时不应变成每事件一次的重试风暴。
        if (now - lastCoordinatorRecoveryAt < COORDINATOR_RECOVERY_THROTTLE_MILLIS) {
            return false
        }
        lastCoordinatorRecoveryAt = now
        JotBillLog.w(
            TAG,
            "检测到截图协调器已熔断，开始重建截图链路: " +
                "coordinator=${if (coordinator == null) "null" else "closed"}"
        )
        return try {
            setObservationListener(null)
            purchaseIntentCoordinator?.setReadyDispatcher(null)
            purchaseIntentCoordinator?.setResultConsumer(null)
            purchaseIntentCoordinator?.close()
            coordinator?.close()
            checkoutCaptureOverlayManager?.setManualCaptureRequestHandler(null)
            checkoutCaptureOverlayManager?.close()

            currentStableCheckout = null
            handoffStableCheckout = null
            handoffForegroundPackage = null
            userRetryCaptureContext = null

            val sharedBuffer = CheckoutSnapshotBuffer()
            checkoutSnapshotBuffer = sharedBuffer
            snapshotCaptureCoordinator = CheckoutSnapshotCaptureCoordinator(this, sharedBuffer)
            purchaseIntentCoordinator = CheckoutPurchaseIntentCoordinator(sharedBuffer)
            checkoutCaptureOverlayManager = CheckoutCaptureOverlayManager(this).also { manager ->
                manager.setManualCaptureRequestHandler { requestManualCaptureFromOverlay() }
            }
            configureSnapshotCaptureResultListener()
            configurePurchaseIntentResultConsumer()
            configurePaymentSignalOrphanSink()
            setObservationListener(snapshotCaptureCoordinator)
            JotBillLog.i(TAG, "截图协调器重建完成，截图能力已恢复")
            true
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "重建截图协调器失败，截图能力仍不可用", error)
            false
        }
    }

    /**
     * 按原因采样输出"支付结果页截图兜底已禁止"，并汇报期间抑制次数。
     *
     * 敏感页停留期间该分支每 10ms 即可命中，真机
     * .tmp-device-capture-20260823-154942 中两条原因各刷出数百行，
     * 把"支付结果页解析到证据""AI 结果匹配落库"等关键链路日志完全淹没。
     * 该日志是隐私红线（敏感页禁止截图）的证据，不能删除，只能限流。
     *
     * @param reason 禁止原因，用作采样分组键。
     * @param paymentPackage 支付应用包名。
     * @param extra 附加诊断信息，可为空。
     */
    private fun logScreenshotFallbackBlockedSampled(
        reason: String,
        paymentPackage: String,
        extra: String? = null
    ) {
        val now = System.currentTimeMillis()
        val lastAt = lastScreenshotFallbackBlockLogAt[reason]
        if (lastAt != null && now - lastAt < SCREENSHOT_FALLBACK_BLOCK_LOG_INTERVAL_MILLIS) {
            suppressedScreenshotFallbackBlockCount[reason] =
                (suppressedScreenshotFallbackBlockCount[reason] ?: 0) + 1
            return
        }
        lastScreenshotFallbackBlockLogAt[reason] = now
        val suppressed = suppressedScreenshotFallbackBlockCount.remove(reason) ?: 0
        JotBillLog.i(
            TAG,
            "支付结果页截图兜底已禁止: $reason pkg=$paymentPackage" +
                (extra?.let { ", $it" } ?: "") +
                if (suppressed > 0) ", 期间已抑制${suppressed}次" else ""
        )
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
            JotBillLog.w(TAG, "结算页 AI 结果消费者失败", error)
        }
    }

    /**
     * 稳定协调器被支付中间页复位后不再转发失效事件；支付交接必须手动补发，
     * 否则购买意图协调器收不到 PAYMENT_APP_HANDOFF，挂起的结算快照无法消费、
     * AI 会话无法预写，支付证据最终只能落 NO_ACTIVE_SESSION 孤儿待确认。
     */
    private fun dispatchPaymentHandoffIfNeeded(
        dispatched: Boolean,
        reason: CheckoutInvalidationReason,
        shoppingPackage: String?,
        foregroundPackage: String?
    ) {
        if (dispatched || reason != CheckoutInvalidationReason.PAYMENT_APP_HANDOFF) {
            return
        }
        // 支付应用会以每秒数十条内容变化事件涌入；同一支付包短时间内只补发一次，
        // 防止重复触发购买意图与诊断日志风暴。
        val now = System.currentTimeMillis()
        val lastForeground = lastPaymentHandoffRedispatchFor
        if (
            lastForeground == foregroundPackage &&
            now - lastPaymentHandoffRedispatchAt < PAYMENT_HANDOFF_REDISPATCH_THROTTLE_MILLIS
        ) {
            return
        }
        lastPaymentHandoffRedispatchFor = foregroundPackage
        lastPaymentHandoffRedispatchAt = now
        val retainedFingerprint = handoffStableCheckout?.observation?.pageFingerprint
            ?: currentStableCheckout?.observation?.pageFingerprint
        JotBillLog.i(
            TAG,
            "支付交接事件补发: stable=${handoffStableCheckout != null || currentStableCheckout != null}, " +
                "fingerprint=${retainedFingerprint?.take(12) ?: "null"}, foreground=$foregroundPackage"
        )
        dispatchEvent(
            CheckoutObservationEvent.Invalidated(
                CheckoutObservationInvalidation(
                    reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                    shoppingPackage = shoppingPackage,
                    pageFingerprint = retainedFingerprint,
                    invalidatedAt = System.currentTimeMillis(),
                    foregroundPackage = foregroundPackage
                )
            )
        )
        ensureSessionForHandoffRedispatch(shoppingPackage)
    }

    /**
     * 快照缺失（结算页指纹高频变化导致最后一张截图未完成）而 AI 无法派发时，
     * 至少用交接引用预写结算会话，保证支付证据与通知始终有会话可关联，
     * 而不是等证据到达时落入 NO_ACTIVE_SESSION 孤儿。
     */
    private fun ensureSessionForHandoffRedispatch(shoppingPackage: String?) {
        if (shoppingPackage.isNullOrBlank()) {
            return
        }
        val coordinator = snapshotCaptureCoordinator ?: return
        val reference = coordinator.currentPaymentHandoffReference() ?: return
        if (reference.shoppingPackage != shoppingPackage) {
            return
        }
        val matcher = synchronized(aiLifecycleLock) { paymentMatcher } ?: return
        val scope = synchronized(aiLifecycleLock) { matchingScope } ?: evidenceFallbackScope
        scope.launch {
            try {
                val requestKey = CheckoutRequestKey.from(
                    sessionId = reference.sessionId,
                    shoppingPackage = reference.shoppingPackage,
                    pageFingerprint = reference.pageFingerprint
                )
                matcher.ensureSession(reference, requestKey)
                JotBillLog.i(TAG, "支付交接会话预写入: session=${reference.sessionId.take(8)}")
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "支付交接会话预写入失败", error)
            }
        }
    }

    /**
     * 按秒采样的支付交接诊断：支付应用每秒涌入数十条内容变化事件，
     * 全量打印会冲掉缓冲区中更关键的截图与派发日志。
     */
    private fun logHandoffDiagnosticsSampled(paymentPackage: String, previousShoppingPackage: String?) {
        val now = System.currentTimeMillis()
        if (now - lastHandoffDiagnosticsAt < 1_000L) {
            return
        }
        lastHandoffDiagnosticsAt = now
        JotBillLog.i(
            TAG,
            "支付交接诊断: payment=$paymentPackage, currentStable=${currentStableCheckout != null}, " +
                "handoffStable=${handoffStableCheckout != null}, previousShopping=$previousShoppingPackage, " +
                "coordRef=${snapshotCaptureCoordinator?.currentPaymentHandoffReference()?.sessionId ?: "null"}"
        )
    }

    /**
     * 判断一次页面失效是否属于“同购物包内的支付中间页”（如拼多多提交订单后的收银台）。
     *
     * 此类页面虽不是结算页，却是结算 → 支付旅程的必经环节；稳定结算引用应挂起保留，
     * 等待切支付应用交接或回到结算页，而不是立即销毁。
     */
    private fun shouldRetainCheckoutAcrossPaymentInterstitial(
        detail: CheckoutObservationInvalidation
    ): Boolean {
        if (detail.reason != CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
            return false
        }
        val stable = currentStableCheckout ?: return false
        val invalidatedPackage = detail.shoppingPackage?.trim().orEmpty()
        return invalidatedPackage.isNotBlank() &&
            stable.observation.shoppingPackage == invalidatedPackage
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
                JotBillLog.w(TAG, "分发结算页观察事件失败", error)
            }
        }
        // 精简模式：悬浮截图按钮整体不出现。
        //
        // 这里必须是门禁点，**不能只靠"不调用 onSupportedShoppingNonCheckout"**：
        // 按钮的可见性主要由 Invalidated 事件驱动，而
        // `CheckoutCaptureOverlayManager.handleInvalidated` 对 NON_CHECKOUT_PAGE 是
        // **硬编码 READY + visible=canShowOnCurrentPage()**，完全不看可用性判据。
        // 漏掉这一行 ⇒ 开关关闭后按钮照样显示"可截图"，点下去却被
        // requestManualCaptureFromOverlay 静默拒绝 —— 正是该文件 KDoc 里
        // "按钮显示截图但点击必然被拒绝，用户看到的是无响应"那个已修过的失败模式。
        //
        // observationListener / purchaseIntentCoordinator 仍照常收事件：
        // 前者是截图协调器的清理通道，后者消费 Invalidated 做状态复位，
        // 两者都无副作用，切断反而会让残留状态无法回收。
        if (RecognitionCapabilities.checkoutCaptureEnabled) {
            checkoutCaptureOverlayManager?.onObservationEvent(event)
        }
        try {
            // §8.3 sessionId 传递链路第③环：会话标识是截图协调器处理本帧时的决策
            // （新建 / 漂移沿用 / 支付返回恢复），因此只能在它之后解析并补进事件，
            // 购买意图协调器才拿得到会话标识（改造 B/E 的键空间来源）。
            purchaseIntentCoordinator?.onEvent(resolveSessionIdOf(event))
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "分发购买意图事件失败", error)
        }
    }

    /**
     * 给稳定帧补上本帧对应会话的 sessionId（§8.3）。
     *
     * 解析不到时**原样返回**（sessionId 保持 null）：该帧由截图协调器判定为"不建立/不沿用
     * 会话"（交接过渡窗内被有意忽略、协调器已关闭），此时既不得凭空造会话，也不得
     * 回退上一帧的会话——那正是改造 E 要消除的错误归属。
     */
    private fun resolveSessionIdOf(event: CheckoutObservationEvent): CheckoutObservationEvent {
        if (event !is CheckoutObservationEvent.Stable) {
            return event
        }
        val observation = event.page.observation
        val sessionId = snapshotCaptureCoordinator?.currentCheckoutSessionId(
            shoppingPackage = observation.shoppingPackage,
            pageFingerprint = observation.pageFingerprint
        )
        if (sessionId.isNullOrBlank()) {
            return event
        }
        return CheckoutObservationEvent.Stable(event.page.copy(sessionId = sessionId))
    }
}

/**
 * 判定一次失效是否应保留结算候选与累计等待时间。
 *
 * 京东弹窗结算页在 ACCEPTED 与 NOT_CHECKOUT_PAGE 之间高频抖动，
 * 真机 .tmp-device-capture-20260823-144208 中相邻两次判定最短仅隔 16ms：
 *   14:42:42.432 ACCEPTED  → 14:42:43.099 NOT_CHECKOUT_PAGE (667ms)
 *   14:43:11.881 ACCEPTED  → 14:43:11.897 NOT_CHECKOUT_PAGE (16ms)
 *   14:43:23.194 ACCEPTED  → 14:43:23.222 NOT_CHECKOUT_PAGE (28ms)
 * invalidate() 此前会把 pendingObservation 与 firstPendingAt 一并清零，
 * 导致最长等待上限永远攒不满：京东 6 次 ACCEPTED 只换来 1 次自动截图，
 * 而拼多多因 ACCEPTED 能连续出现（3.4 秒内两次）故 2/2 成功。
 *
 * 只有同购物包内的 NON_CHECKOUT_PAGE 才视为抖动：离开购物应用、敏感页、
 * 支付交接、节点树不可用、服务中断都是明确的旅程终止，必须清空候选，
 * 否则会把过期候选带入下一段旅程。
 *
 * @param reason 本次失效原因。
 * @param invalidatedPackage 触发失效的页面所属购物包名。
 * @param pendingPackage 当前待确认候选所属购物包名；无候选时为 null。
 * @return true 表示保留候选与累计等待，让强制确认仍有机会达成。
 */
internal fun shouldKeepPendingAcrossInvalidation(
    reason: CheckoutInvalidationReason,
    invalidatedPackage: String?,
    pendingPackage: String?
): Boolean {
    if (reason != CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
        return false
    }
    val invalidated = invalidatedPackage?.trim().orEmpty()
    val pending = pendingPackage?.trim().orEmpty()
    return pending.isNotBlank() && invalidated == pending
}

/**
 * 判定结算候选是否已超过最长等待上限，必须强制确认稳定。
 *
 * 提取为纯函数以便单测覆盖：CheckoutStabilityCoordinator 依赖 Android Handler，
 * 无法在 JVM 单测中构造，但该判据决定了自动截图能否在指纹漂移页面上触发。
 *
 * 去抖策略是"每来一个新指纹就把去抖计时器归零"。京东弹窗结算页的推荐位与
 * 优惠券文案会持续改写指纹，只要漂移间隔小于去抖窗口，稳定确认就被无限推迟，
 * 自动截图（CHECKOUT_STABLE）因此极难触发。达到上限后必须放行一次，
 * 宁可确认一帧略旧的结算页，也不能一张都截不到。
 *
 * @param firstPendingAt 当前候选序列首次出现的时间戳；0 表示尚无候选。
 * @param now 当前时间戳。
 * @param maxStableWaitMillis 最长等待上限。
 * @return true 表示应立即强制确认，不再重置去抖计时器。
 */
internal fun shouldForceStableConfirm(
    firstPendingAt: Long,
    now: Long,
    maxStableWaitMillis: Long
): Boolean = firstPendingAt > 0L && now - firstPendingAt >= maxStableWaitMillis

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
    private val onInvalidated: (CheckoutObservationInvalidation) -> Unit,
    /**
     * 单个结算候选从首次出现到必须确认稳定的最长等待时间。
     *
     * 去抖策略是"每来一个新指纹就把 stableDelayMillis 计时器归零"，
     * 这在指纹持续漂移的页面上会导致稳定窗口永远无法完成——京东弹窗结算页
     * 的推荐位、优惠券文案会不断改写指纹，自动截图因此极难触发。
     * 超过该上限即用最近一次候选强制确认一次，宁可截到一帧略旧的结算页，
     * 也不能一张都截不到。
     */
    private val maxStableWaitMillis: Long = 2_000L
) {
    private var pendingObservation: CheckoutPageObservation? = null
    private var lastStableFingerprint: String? = null
    private var activePackage: String? = null
    private var activeFingerprint: String? = null
    private var hasActiveCheckout: Boolean = false
    private var disposed: Boolean = false

    /** 当前结算候选序列首次出现的时间；用于判断是否已超过最长等待上限。 */
    private var firstPendingAt: Long = 0L

    /** 是否已有稳定确认在 Handler 队列中排队等待执行。 */
    private var stableConfirmScheduled: Boolean = false

    /**
     * 当前候选是否为"抖动期间保留"的候选。
     *
     * 同包非结算页失效会把 hasActiveCheckout 置 false，但候选被有意保留以便
     * 凑足最长等待上限。该标记让确认逻辑区分"候选仍有效但页面在抖动"与
     * "旅程已真正终止"，避免保留的候选被 hasActiveCheckout 检查一律拦掉。
     */
    private var pendingKeptAcrossInvalidation: Boolean = false

    private val stableRunnable = Runnable {
        stableConfirmScheduled = false
        val observation = pendingObservation ?: return@Runnable
        val keptAcrossInvalidation = pendingKeptAcrossInvalidation
        pendingObservation = null
        firstPendingAt = 0L
        pendingKeptAcrossInvalidation = false
        // 抖动期间保留的候选不受 hasActiveCheckout 限制：该标志已被 invalidate
        // 置为 false，若一律拦下则保留候选毫无意义，最长等待上限仍然形同虚设。
        if ((!hasActiveCheckout && !keptAcrossInvalidation) ||
            observation.pageFingerprint == lastStableFingerprint
        ) {
            return@Runnable
        }

        // Y4（2026-09-10 15:41 真机实证，0.0.97 B/D 场景）：抖动保留路径确认稳定后
        // 必须恢复 hasActiveCheckout。旧实现只恢复候选不恢复标记，导致：
        // 规格层抖动帧（observe 后紧跟 invalidate）锚定的会话，此后所有"真正离开
        // 结算页"的失效全部被入口 !hasActiveCheckout 吞掉——协调器零"观察失效"，
        // activeCheckout 无限期存续，商品规格层会话与截图跨 4 次页面切换
        // （规格层→主页→商品页→结算页）活到支付交接栅栏，真结算页自动截图
        // 被"指纹漂移沿用原会话"拒绝。恢复标记后，下一次离开正常上报，协调器
        // 把会话转入挂起（快照保留），由交接过渡窗（handoffStableEventAction）
        // 约束时效。正常路径 observe() 已置位，此处恢复是幂等 no-op。
        hasActiveCheckout = true
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
        // 重新收到结算观察说明抖动已结束，候选回归正常生命周期。
        pendingKeptAcrossInvalidation = false
        if (observation.pageFingerprint == lastStableFingerprint) {
            pendingObservation = null
            firstPendingAt = 0L
            handler.removeCallbacks(stableRunnable)
            stableConfirmScheduled = false
            return
        }

        val now = nowMillis()
        val hadPending = pendingObservation != null
        // 始终保留最新观察：强制确认时也应该用最近一帧，而不是最早那一帧。
        pendingObservation = observation
        if (!hadPending || firstPendingAt == 0L) {
            firstPendingAt = now
            scheduleStableConfirm(stableDelayMillis)
            return
        }

        // 已超过最长等待上限：不再重置计时器，让已排定的确认按时执行。
        // 否则指纹持续漂移的页面（京东弹窗结算页的推荐位/优惠券文案）
        // 会把 500ms 去抖窗口无限推迟，自动截图永远等不到稳定确认。
        if (shouldForceStableConfirm(firstPendingAt, now, maxStableWaitMillis)) {
            if (!stableConfirmScheduled) {
                scheduleStableConfirm(0L)
            }
            return
        }

        scheduleStableConfirm(stableDelayMillis)
    }

    /**
     * 排定一次稳定确认，覆盖此前尚未执行的排定。
     *
     * 用自维护标志替代 Handler.hasCallbacks（该 API 需要 API 29+），
     * 以便在最低支持版本上也能判断"是否已有确认在排队"。
     *
     * @param delayMillis 延迟毫秒；0 表示尽快执行。
     */
    private fun scheduleStableConfirm(delayMillis: Long) {
        handler.removeCallbacks(stableRunnable)
        stableConfirmScheduled = true
        handler.postDelayed(stableRunnable, delayMillis)
    }

    /**
     * 失效即结束当前稳定观察周期。
     *
     * @return true 表示本次失效已分发给观察监听器；false 表示协调器已被复位
     *（例如同包支付中间页先触发了 NON_CHECKOUT_PAGE），调用方需要自行补发事件，
     * 否则下游截图/购买意图协调器会漏掉 PAYMENT_APP_HANDOFF 等关键交接信号。
     */
    fun invalidate(
        reason: CheckoutInvalidationReason,
        shoppingPackage: String? = null,
        pageFingerprint: String? = null,
        foregroundPackage: String? = null
    ): Boolean {
        if (disposed || !hasActiveCheckout) {
            return false
        }

        // 同包内的非结算页判定多为页面渲染抖动而非真正离开：京东弹窗结算页
        // 相邻判定最短仅隔 16ms。此处若把候选与累计等待一起清零，
        // 最长等待上限永远攒不满，自动截图只能靠 ACCEPTED 恰好连续出现的运气。
        // 保留候选让强制确认仍有机会达成；真正的旅程终止（离开应用、敏感页、
        // 支付交接、服务中断）仍按原逻辑清空。
        val keepPending = shouldKeepPendingAcrossInvalidation(
            reason = reason,
            invalidatedPackage = activePackage ?: shoppingPackage,
            pendingPackage = pendingObservation?.shoppingPackage
        )
        handler.removeCallbacks(stableRunnable)
        stableConfirmScheduled = false
        if (!keepPending) {
            pendingObservation = null
            firstPendingAt = 0L
            pendingKeptAcrossInvalidation = false
        } else {
            // 抖动期间仍需继续推进确认，否则候选会一直悬着无人处理。
            pendingKeptAcrossInvalidation = true
            scheduleStableConfirm(stableDelayMillis)
        }
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
        return true
    }

    fun dispose() {
        if (disposed) {
            return
        }
        invalidate(CheckoutInvalidationReason.SERVICE_DESTROYED)
        handler.removeCallbacks(stableRunnable)
        stableConfirmScheduled = false
        pendingObservation = null
        firstPendingAt = 0L
        pendingKeptAcrossInvalidation = false
        disposed = true
    }
}
