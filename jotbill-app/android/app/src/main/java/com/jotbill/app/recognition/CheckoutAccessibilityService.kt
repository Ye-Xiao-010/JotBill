package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.utils.PaymentTiming
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.bookkeeping.AccessibilityPageNode
import com.jotbill.app.bookkeeping.AutoBookkeeper
import com.jotbill.app.bookkeeping.AutoBookkeepingEngine
import com.jotbill.app.bookkeeping.BookkeepingChannels
import com.jotbill.app.bookkeeping.PageNode
import com.jotbill.app.bookkeeping.PageRuleRegistry
import com.jotbill.app.overlay.PendingConfirmOverlayManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        /** 支付结果页重扫兜底（P2-5）：重扫间隔与单次会话上限。 */
        private const val PAYMENT_RESCAN_INTERVAL_MILLIS = 1_000L
        private const val PAYMENT_RESCAN_MAX_COUNT = 8

        /**
         * 敏感支付页面的截图禁止锁存窗口。
         *
         * 支付密码页停留期间，无障碍会交替收到"敏感页"与"空节点树"两类帧；
         * 空树帧无法自行判定敏感，需靠锁存窗口保证整个密码输入过程都不截图。
         */
        private const val SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS = 60_000L

        /**
         * 支付结果页事件键记忆窗（去重第一道防线），**2026-10-24 由 90 秒收窄至 15 秒**。
         *
         * 依据见 [recentPaymentResultEventKeys] 的 KDoc。收窄到 15 秒是用户
         * 2026-10-24 的裁定：同额第二笔在 15 秒内接受合并，超过 15 秒一律视为新交易。
         * 数值统一由 [PaymentTiming.SAME_TXN_WINDOW_MILLIS] 提供。
         *
         * ⚠️ 须与 `CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS` 保持同值
         * （两者引用同一常量，不要再写死数字）。
         * ⚠️ "同一笔停留超过 15 秒"的重复观测同样会各自建卡 —— 这是裁定的取舍，
         *    不是遗漏（页面显示时间时 `occurredAt` 恒定，仍会命中）。
         */
        private const val PAYMENT_RESULT_EVENT_MEMORY_MILLIS = PaymentTiming.SAME_TXN_WINDOW_MILLIS

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
        private var activeService: CheckoutAccessibilityService? = null

        /**
         * 服务当前是否实际存活（onServiceConnected 后、onUnbind/onDestroy 前）。
         *
         * 部分 ROM（如 MIUI 部分版本）上 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
         * 读取结果与真实运行状态不一致（返回空或不含本服务），状态页会误报"未开启"。
         * 判定服务开启时应优先使用本方法，再回退系统设置读取。
         */
        @JvmStatic
        fun isServiceRunning(): Boolean = activeService != null

        /**
         * 通知证据的同进程回退入口 —— 精简版**恒拒绝**。
         *
         * ⚠️ 2026-10-12：这个行为不是本次改动引入的，精简包（`leanDefault=true`）
         * 在开关关闭时就已经如此，只是当时被一层运行时分支掩盖着。
         *
         * 原因链：原实现要求"复用当前真实稳定结算页的指纹"，而稳定结算页由
         * `CheckoutStabilityCoordinator.onStable` 回调喂入，协调器的唯一喂入点
         * `observe()` 位于 `if (RecognitionCapabilities.checkoutCaptureEnabled)` 分支内
         * —— 开关关闭时从不调用 ⇒ 稳定页恒为 null ⇒ 必然拒绝在 `NO_CHECKOUT_CONTEXT`。
         * 截图链路整链删除后这条链不再存在，故直接写死拒绝。
         *
         * **为什么保留这个入口而不是让 `PaymentNotificationService` 不调**：
         * 调用方 `PaymentNotificationService.tryFallbackSignal()` 按"是否被接走"
         * 决定要不要转孤儿落库。保留入口让它照常走到 `!acceptedByAccessibility`
         * 分支 —— 行为与删掉入口完全一致，但省掉跨模块签名改动，且 RN 侧
         * `RecognitionModule.mapRejectionCode` 的错误码契约不受影响。
         *
         * 真正的通知兜底由 `PaymentNotificationService.recordOrphanPaymentSignal`
         * 直接落库承担，与本入口无关（D-3 闸：v1 通知证据本就不参与匹配与入账）。
         */
        @JvmStatic
        fun requestPaymentNotificationFallbackSignal(
            signal: PaymentNotificationFallbackSignal,
            callback: PurchaseIntentResultCallback
        ) {
            if (activeService == null) {
                notifyPurchaseIntentUnavailable(
                    PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                    callback
                )
                return
            }
            notifyPurchaseIntentRejected(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
                callback
            )
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

    private var lastWindowPackage: String? = null
    private var lastWindowClass: String? = null
    private var lastObservedEventLogAt = 0L
    private var lastPaymentWindowScanAt = 0L

    // ── 支付结果页重扫兜底（P2-5，2026-10-24 场景审查）──────────────────────
    // 结果页在开场动画结束后可能完全静止：无障碍事件流停摆 ⇒ 证据提取与弹窗
    // 门禁全部饿死（"全被挡 + 无后续事件 = 零证据"）。任一帧无证据产出时排一个
    // 短周期重扫，仍走同一扫描管线；任一真实事件到达即重置预算。
    // **仅主线程访问**（无障碍回调与 Handler 都在主 looper）。
    private var paymentRescanCount = 0
    private var paymentRescanPackage: String? = null
    private var paymentRescanWindowClass: String? = null
    private val paymentRescanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val paymentRescanRunnable = Runnable { runPaymentResultRescan() }

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

    /**
     * 支付结果页事件键记忆窗（去重第一道防线）。
     *
     * ⚠️ **2026-09-30 由 60 秒调至 90 秒，2026-10-24 又收窄至 15 秒**（用户裁定）。
     * 原 60 秒与 `PaymentResultParser` 的 30 秒时间桶、[D-25] 的孤儿去重窗**阈值重叠**：
     * 三个 30~60 秒的判据挤在窄带里，只要用户在微信结果页停留超过 60 秒，
     * 三道防线在同一刻集体失效。
     *
     * 真机数据（小米 25128PNA1C，拼多多 build 0.0.98，2026-09-30 10:05~10:06）：
     * 同一笔 ¥2.68 的结果页被观测两次，`textLength` 均为 20（同一页面），
     * `occurredAt` 相差 **60393 ms** —— 比原 60 秒窗多 393 毫秒，
     * 事件层记忆过期 + 跨桶 + 越过 D-25 窗 ⇒ 落了两条待确认。
     *
     * ⚠️ **该场景现在不再保证合并**：2026-10-24 用户裁定"超过 15 秒即视为新交易"。
     * 页面不显示时间时，60.393 秒的同一笔会被再建一张卡（误报方向，用户可在
     * 待确认中心删除）—— 这是裁定明确接受的取舍，不是遗漏。
     * 页面显示时间时 `occurredAt` 恒定，D-25 窗口仍会命中，不受影响。
     *
     * 本常量须与 `CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS` **保持同值** ——
     * 两道防线阈值不一致时，先失效的那道形同虚设。
     */
    private val recentPaymentResultEventKeys = mutableMapOf<String, Long>()

    /**
     * 结算页门禁日志的最近打印时间，键为"包名|拒绝码"。
     *
     * 按组合采样而非全局采样：否则高频的某一种拒绝原因会把其他原因全部掩盖，
     * 无法看出页面究竟卡在哪个信号上。仅在主线程访问，无需同步。
     */
    private val lastCheckoutGateLogAt = mutableMapOf<String, Long>()

    /** "截图兜底已禁止"日志按原因的最近打印时间。 */
    private val lastScreenshotFallbackBlockLogAt = mutableMapOf<String, Long>()

    /** 采样间隔内被抑制的"截图兜底已禁止"次数，随下次输出一并汇报。 */
    private val suppressedScreenshotFallbackBlockCount = mutableMapOf<String, Int>()
    @Volatile
    private var serviceConnected = false
    private val aiLifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var paymentMatcher: CheckoutPaymentMatcher? = null
    private var matchingScope: CoroutineScope? = null
    private val evidenceFallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 规则式自动记账（mipay 移植管线，与上方截图 AI 管线并存互斥）────────
    // 引擎只做「页面定位 + 正则抽字段」，主线程同步执行（dump+正则，毫秒级）；
    // 规则资产在 IO 作用域异步加载，未就绪前引擎空转。认领注册表供
    // scanPaymentResultCore 查询让路（弹窗兜底 vs 自动入账互斥，拍板项①）。
    private val bookkeepingRules =
        java.util.concurrent.atomic.AtomicReference<PageRuleRegistry?>(null)

    @Volatile
    private var bookkeepingEngine: AutoBookkeepingEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        JotBillLog.i(
            TAG,
            "无障碍服务开始连接: previouslyConnected=$serviceConnected"
        )

        val configuredInfo = serviceInfo ?: AccessibilityServiceInfo()
        configuredInfo.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
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
        AccessibilityPageSummaryCollector.resetVolatileTracker()
        activeService = this
        initializePaymentMatcher()
        initializeAutoBookkeeping()
        serviceConnected = true
        JotBillLog.i(TAG, "结算页无障碍观察服务已连接")
    }

    /**
     * 在当前安全购物页重新截图，成功后才向桥接层报告新的 AI 请求。
     *
     * 命名为 perform* 以避免与 companion 的 @JvmStatic requestUserRetryForSession
     * 产生相同 JVM 签名（platform declaration clash）。
     *
     * ⚠️ 2026-10-12：精简后本方法**必然**在第一行就拒绝。截图协调器已整链删除，
     * 没有任何东西能再拿到当前购物页的稳定结算页上下文。保留外壳
     * （`mainHandler.post` + 回调去重 + `Rejected(SERVICE_NOT_CONNECTED)`）是为了让
     * `RecognitionModule.retryAiRecognition()` 的 RN 错误码路径一字不变 ——
     * 它会收到 `Rejected` 并经 `mapRejectionCode` 映射成 `SERVICE_UNAVAILABLE`。
     */
    private fun performUserRetryForSession(
        sessionId: String,
        expectedShoppingPackage: String,
        callback: PurchaseIntentResultCallback
    ) {
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
        mainHandler.post {
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
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!serviceConnected || event == null) {
            return
        }

        // 规则式自动记账管线先行（mipay 移植）：同步抽取成功即在引擎内登记
        // 认领指纹，下方弹窗管线据此让路。引擎内部吞异常，不影响本服务；
        // 快照构建在引擎 try 之外，这里再包一层保底。
        try {
            bookkeepingEngine?.onEvent(buildEventSnapshot(event), bookkeepingRootProvider())
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "自动记账引擎入口异常", error)
        }

        // 通知事件（typeNotificationStateChanged 为自动记账新增）只属于规则
        // 引擎（通知锚点 + 二次确认 + M6 短信通道）；此前事件流里没有这类事件，
        // 此处早退 = 既有管线零行为变化。
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
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
            val previousShoppingPackage = lastWindowPackage
            if (pageDetector.supportsPaymentPackage(shoppingPackage)) {
                // 阶段四 T3：在支付应用中观察支付结果页，提取结构化证据。
                // ★ 这是精简版**唯一**的数据来源 —— 弹窗的金额/商户/状态全部来自这里。
                observePaymentResultIfApplicable(
                    event = event,
                    paymentPackage = shoppingPackage
                )
            }
            // 2026-10-12：此处原有的
            // `PendingConfirmOverlayManager.onForegroundChanged(shoppingPackage)`
            // 已随该方法一并回退。它把状态栏/输入法/桌面事件当成"前台变更"喂入，
            // 真机实测每次点屏幕都会击穿弹窗的"前台是本应用时不弹"守卫
            // （证据见 docs/qa/device/2026-10-12/ 与 PendingConfirmOverlayManager
            // 同文件处的回退说明）。本分支不再向弹窗侧投递任何数据。
            lastWindowPackage = null
            lastWindowClass = null
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

        val eventSource = try {
            event.source
        } catch (_: RuntimeException) {
            null
        }
        val root = activeRoot ?: eventSource
        if (root == null) {
            // D-9 裁定 A：从购物页切到微信支付的瞬间常有一帧节点树为空，该帧走不到
            // 敏感判定，必须中断"连续合格帧"计数 —— 否则"合格页×2 → 空树帧 →
            // 合格页×1"会凑满阈值，而它们并非真连续。
            sensitivePageLatch.onUnobservableFrame()
            return
        }

        try {
            val rootPackage = root.packageName?.toString()?.trim().orEmpty()
            if (rootPackage.isNotBlank() && rootPackage != shoppingPackage) {
                if (pageDetector.supportsPaymentPackage(rootPackage)) {
                    // 购物应用窗口事件的根节点已经是微信/支付宝：这是提交订单后的
                    // 支付交接，不是节点树失效。继续观察支付结果页。
                    observePaymentResultIfApplicable(
                        event = event,
                        paymentPackage = rootPackage
                    )
                    return
                }
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
                // 结算页 / 非结算页分叉在精简版**已无意义**：两个分支原本分别喂
                // 稳定帧协调器与悬浮截图按钮，整链删除后两边都是空操作。
                // 保留 when 是为了让 `logCheckoutGateSampled` 的门禁日志照旧
                // 逐帧记录 ACCEPTED / REJECTED —— 那是排查"弹窗为何没出"的
                // 主要现场之一，不能随链路一起删。
                is CheckoutPageDetection.Accepted -> {
                    logCheckoutGateSampled(observation, rejection = null)
                }

                is CheckoutPageDetection.Rejected -> {
                    logCheckoutGateSampled(observation, rejection = detection.reason)
                }
            }
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "读取无障碍节点摘要失败", error)
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
     * 这里只清空页面级缓存，**不能**把 serviceConnected、匹配器和 static
     * activeService 一并拆除，否则 MIUI 等系统只发 onInterrupt 而不重新回调
     * onServiceConnected 时，服务会变成"已绑定但永久不再处理事件"的僵尸。
     */
    override fun onInterrupt() {
        lastWindowPackage = null
        lastWindowClass = null
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
        // 重扫兜底的 Handler 挂在主 looper 上，比服务实例活得长：解绑即摘，
        // 否则服务销毁后 runnable 仍会触碰本实例字段。
        paymentRescanHandler.removeCallbacks(paymentRescanRunnable)
        if (activeService === this) {
            activeService = null
        }
        JotBillLog.i(TAG, "结算页无障碍观察服务已解除绑定")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        JotBillLog.i(TAG, "结算页无障碍观察服务开始销毁: previouslyConnected=$serviceConnected")
        serviceConnected = false
        lastWindowPackage = null
        lastWindowClass = null
        paymentRescanHandler.removeCallbacks(paymentRescanRunnable)
        shutdownPaymentMatcher()
        evidenceFallbackScope.cancel()
        if (activeService === this) {
            activeService = null
        }
        JotBillLog.i(TAG, "结算页无障碍观察服务已销毁")
        super.onDestroy()
    }

    /**
     * 创建独立的匹配作用域。
     *
     * 命名与 KDoc 保留了历史的"AI 客户端"字样：精简版已无 AI 派发路径，本方法现在
     * 只负责支付证据落库 —— 但它**必须**在 `onServiceConnected` 里无条件执行，
     * 因为付款确认弹窗的证据落库完全依赖这里的 `paymentMatcher`。
     */
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
        }
        // 启动期证据兜底扫描（批 V-5）：
        //   ① 为历史"证据先到、AI 未返回"的会话补齐 EVIDENCE_ONLY 候选关联；
        //   ② 会话仍停在"等 AI 定论"而进程已被杀、定论永不返回 ⇒ 落待确认，
        //      避免"证据在、用户看不到"的静默窗口（`IMPL_PLAN` §2.4 D-3 兜底 2）。
        // 挂载点说明：这里（服务连接时）是唯一挂载点，且与正常流程不竞争 ——
        // 本服务已无 AI 派发路径，此刻没有在飞请求，因此"会话处于等待定论状态"
        // 必然是死状态。
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
        }
        scope?.cancel()
    }

    /**
     * 规则式自动记账引擎初始化（mipay 移植管线）。
     *
     * - 引擎在主线程无障碍回调里同步执行（dump + 正则，毫秒级），落账经
     *   [AutoBookkeeper] 走 [evidenceFallbackScope]（IO）；
     * - 规则资产首次从 assets 异步加载，进程内单例缓存；
     * - 通道开关（总开关 + 支付宝/微信/云闪付白名单）见 [BookkeepingChannels]。
     */
    private fun initializeAutoBookkeeping() {
        val existing = bookkeepingEngine
        if (existing != null) {
            // 服务解绑→重绑的复用场景：窗口跟踪是易变会话态，跨周期保留只会
            // 让新事件错配旧跟踪（MIUI 只发 onInterrupt 不重连的场景同理）。
            existing.resetTracking()
            return
        }
        val appContext = applicationContext
        val engine = AutoBookkeepingEngine(
            registryRef = bookkeepingRules,
            channels = { packageName ->
                BookkeepingChannels.shouldProcess(appContext, packageName)
            },
            sink = AutoBookkeeper(appContext, evidenceFallbackScope)
        )
        bookkeepingEngine = engine
        evidenceFallbackScope.launch {
            try {
                bookkeepingRules.set(PageRuleRegistry.get(appContext))
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "自动记账规则资产加载失败", error)
            }
        }
        JotBillLog.i(TAG, "自动记账引擎已挂载")
    }

    /** 把无障碍事件投影成引擎快照（引擎不触碰框架类，保证可 JVM 单测）。 */
    private fun buildEventSnapshot(event: AccessibilityEvent): AutoBookkeepingEngine.EventSnapshot {
        val texts = try {
            event.text
        } catch (_: RuntimeException) {
            null
        }
        val notificationText = when {
            texts == null || texts.isEmpty() -> null
            texts.size == 1 -> texts[0]?.toString()
            else -> texts.joinToString("\n") { it?.toString().orEmpty() }
        }
        return AutoBookkeepingEngine.EventSnapshot(
            eventType = event.eventType,
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            windowId = event.windowId,
            eventTime = event.eventTime,
            notificationText = notificationText,
            notificationContentDesc = try {
                event.contentDescription?.toString()
            } catch (_: RuntimeException) {
                null
            }
        )
    }

    /** 引擎取活动窗口根节点的通道；与既有管线各持引用，互不 recycle。 */
    private fun bookkeepingRootProvider(): () -> PageNode? = {
        try {
            rootInActiveWindow?.let(::AccessibilityPageNode)
        } catch (_: RuntimeException) {
            null
        }
    }

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
     * 真实无障碍事件入口：重置重扫兜底预算并撤销待执行的重扫，本帧立即全量扫描。
     * 重扫兜底（[runPaymentResultRescan]）复用同一扫描核心 [scanPaymentResultCore]。
     */
    private fun observePaymentResultIfApplicable(
        event: AccessibilityEvent,
        paymentPackage: String
    ) {
        paymentRescanCount = 0
        paymentRescanHandler.removeCallbacks(paymentRescanRunnable)
        scanPaymentResultCore(event, paymentPackage)
    }

    /**
     * 阶段四 T3：在支付应用中观察支付结果页，提取结构化证据。
     *
     * 由两条路径驱动：真实无障碍事件（[observePaymentResultIfApplicable]）与
     * 静止页重扫兜底（[runPaymentResultRescan]，合成事件）。两者走同一管线，
     * 读行重试 / 三道去重 / 敏感门禁 / 幂等键全部复用。
     *
     * 只读取无障碍节点文本，不截图、不读密码/付款码/银行卡号。
     * 支付成功的结果生成 PaymentResultEvidence，交给匹配器落库。
     * 支付失败的结果不生成成功证据，只记录诊断日志。
     */
    private fun scanPaymentResultCore(
        event: AccessibilityEvent,
        paymentPackage: String
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
            // 空树帧也可能是支付交接瞬间的暂态（D-9 记录过）：同样进入重扫兜底。
            schedulePaymentResultRescan(paymentPackage, event.className?.toString())
            return
        }

        var sawResultPage = false
        try {
            for ((rootIndex, root) in roots.withIndex()) {
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
                        // D-9：该分支在支付页每帧命中，真机
                        // .tmp-device-capture-20260824-142754 中一次支付即刷出数百行，
                        // 把"支付结果页解析到证据"等关键链路日志完全淹没。
                        // 该日志是隐私红线（敏感页禁止提取与截图）的证据，不可删除，只能限流。
                        sensitivePageLatch.onPaymentSensitiveFrame(System.currentTimeMillis())
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
                            // 2026-10-24 复审（子智能体报告后收敛）：失败分两性。
                            // PAYMENT_FAILED = 整窗文本含失败关键词（解析器先查失败
                            // 词后查成功词），是**明确的反向证据**——不得被同帧其他
                            // root 推翻：event.source 子树文本可能恰好不含位于兄弟
                            // 分支的失败词、却含成功词+金额，继续扫描会为一笔已失败
                            // 的支付落成功卡（摘樱桃误报）。保持 return（改动前行为）。
                            if (parseResult.reason == PaymentResultFailureReason.PAYMENT_FAILED) {
                                JotBillLog.i(TAG, "支付结果页含失败语义，终止本帧: ${parseResult.reason}")
                                return
                            }
                            // 证据不足类（金额/时间解析不出）与 NotPaymentResult 一样
                            // 只是"这个 root 读不出结果"——继续检查其余 root：
                            // roots 依次是 rootInActiveWindow / event.source / 活动窗口
                            // root，首个可能指向叠加在支付页上的系统对话框（权限弹窗/
                            // 输入法/MIUI 安全提示），它失败不代表支付页不可读。
                            // 只有证据已处理（Success/PersonalTransfer）才终止本帧。
                            JotBillLog.i(
                                TAG,
                                "支付结果页未生成成功证据: reason=${parseResult.reason}, " +
                                    "root=${rootIndex + 1}/${roots.size}"
                            )
                            continue
                        }
                        PaymentResultParseResult.NotPaymentResult -> {
                            JotBillLog.d(
                                TAG,
                                "支付结果页未命中可确认的结果语义: root=${rootIndex + 1}/${roots.size}"
                            )
                            continue
                        }
                    }

                    if (!markPaymentResultEvent(evidence.sourceEventKey, observation.observedAt)) {
                        JotBillLog.d(TAG, "支付结果页同一事件已处理，跳过重复解析: ${evidence.sourceEventKey.take(12)}")
                        return
                    }

                    // 自动记账互斥（拍板项①）：规则引擎已在认领窗内认领同包名同金额
                    // ⇒ 本帧让路，弹窗/待确认留给规则路径的兜底分支。金额感知避免
                    // 误伤并发第二笔不同金额的支付。
                    if (bookkeepingEngine?.shouldSuppressPopup(
                            paymentPackage,
                            evidence.amountMinor
                        ) == true
                    ) {
                        JotBillLog.i(
                            TAG,
                            "规则自动记账已认领同额支付，弹窗管线让路: " +
                                "pkg=$paymentPackage, amount=${evidence.amountMinor}分"
                        )
                        return
                    }
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
                            // ★ 精简版的核心落库路径：文本解析出的证据直接进匹配器，
                            //   产出待确认交易并触发 PendingConfirmOverlayManager 弹窗。
                            //   这里**不再有任何 AI 派发**（截图识别链路已整链删除），
                            //   因此不需要"等 AI 定论"的兜底落卡 —— recordPaymentResultEvidence
                            //   自身在无会话时走 recordOrphanPaymentSignal 直接落待确认。
                            recordPaymentResultEvidence(
                                matcher = matcher,
                                paymentPackage = paymentPackage,
                                evidence = evidence
                            )
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

        // 走到这里 = 本帧没有任何证据产出（Success / 转账 / 幂等命中都提前 return）。
        // 结果页静止后事件流停摆是"证据饿死"的最后一个成因，排短周期重扫兜底。
        schedulePaymentResultRescan(paymentPackage, event.className?.toString())
    }

    /**
     * 排一次重扫兜底；仅主线程调用。预算 [PAYMENT_RESCAN_MAX_COUNT] 次用尽后静默
     * 停止（最后一次重扫的日志行可见 count=N/N，不再新增行）。
     */
    private fun schedulePaymentResultRescan(paymentPackage: String, windowClass: String?) {
        if (paymentRescanCount >= PAYMENT_RESCAN_MAX_COUNT) {
            return
        }
        paymentRescanPackage = paymentPackage
        paymentRescanWindowClass = windowClass
        paymentRescanHandler.removeCallbacks(paymentRescanRunnable)
        paymentRescanHandler.postDelayed(paymentRescanRunnable, PAYMENT_RESCAN_INTERVAL_MILLIS)
    }

    /**
     * 重扫兜底执行体（P2-5）：用合成事件走 [scanPaymentResultCore] 同一管线。
     * 仅主线程调用。离开支付包（活动窗口根已不是白名单支付包，含本应用弹窗
     * 在前台 —— 那意味着证据已产出过）即停止；无障碍回调与 Handler 同在主
     * looper，字段访问无竞争。
     */
    private fun runPaymentResultRescan() {
        val paymentPackage = paymentRescanPackage ?: return
        val activeRoot = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }
        val rootPackage = try {
            activeRoot?.packageName?.toString()?.trim().orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
        if (rootPackage.isNotBlank() && !pageDetector.supportsPaymentPackage(rootPackage)) {
            JotBillLog.i(TAG, "支付结果页重扫兜底停止: 已离开支付包, rootPkg=$rootPackage")
            return
        }
        paymentRescanCount += 1
        JotBillLog.i(
            TAG,
            "支付结果页重扫兜底: pkg=$paymentPackage, " +
                "count=$paymentRescanCount/$PAYMENT_RESCAN_MAX_COUNT"
        )
        val rescanEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        try {
            rescanEvent.packageName = paymentPackage
            rescanEvent.className = paymentRescanWindowClass
            scanPaymentResultCore(rescanEvent, paymentPackage)
        } finally {
            rescanEvent.recycle()
        }
    }

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

    /**
     * 将支付结果页证据落库 —— 精简版的**核心落库路径**。
     *
     * 会话解析在精简版只剩一条路径：
     * 1. `resolveSessionForPayment` 按订单号 / 金额+时间窗口综合解析；
     * 2. 并发会话无法区分时输出 Ambiguous，不强行合并（T6 创建并发候选）；
     * 3. 无任何匹配会话时转纯通知待确认，证据不丢弃。
     *
     * ⚠️ 2026-10-12：原有的"路径一 —— 支付交接上下文"整段删除。它读
     * `handoffContext`，而该上下文的 `stableCheckout` 由稳定帧协调器的 `onStable`
     * 回调喂入，协调器已随截图链路整链删除 ⇒ `handoffContext` 恒为 null ⇒
     * 该分支在精简版**从未可达**，不是"可达但很少走"。
     *
     * 证据来源固定标记为 ACCESSIBILITY（无障碍结果页），与通知（NOTIFICATION）区分。
     *
     * @return 证据**成功落库**时的会话 id；证据被拒（已转孤儿待确认）或无会话可关联
     *   时返回 null。
     */
    private suspend fun recordPaymentResultEvidence(
        matcher: CheckoutPaymentMatcher,
        paymentPackage: String,
        evidence: PaymentResultEvidence,
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

        // 订单号/金额+时间窗口综合解析
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

    private fun logScreenshotFallbackBlockedSampled(
        reason: String,
        paymentPackage: String,
        extra: String? = null
    ) {
        val now = System.currentTimeMillis()
        // 采样键必须含支付包名（2026-10-24 场景审查）：微信/支付宝共用同一份
        // reason 文本会共享 3s 窗口，L1 连续 20 笔跨两平台时互相抑制，
        // 漏捕获的归因会少记。行内仍输出 pkg=。
        val sampleKey = "$reason|$paymentPackage"
        val lastAt = lastScreenshotFallbackBlockLogAt[sampleKey]
        if (lastAt != null && now - lastAt < SCREENSHOT_FALLBACK_BLOCK_LOG_INTERVAL_MILLIS) {
            suppressedScreenshotFallbackBlockCount[sampleKey] =
                (suppressedScreenshotFallbackBlockCount[sampleKey] ?: 0) + 1
            return
        }
        lastScreenshotFallbackBlockLogAt[sampleKey] = now
        val suppressed = suppressedScreenshotFallbackBlockCount.remove(sampleKey) ?: 0
        JotBillLog.i(
            TAG,
            "支付结果页截图兜底已禁止: $reason pkg=$paymentPackage" +
                (extra?.let { ", $it" } ?: "") +
                if (suppressed > 0) ", 期间已抑制${suppressed}次" else ""
        )
    }

}
