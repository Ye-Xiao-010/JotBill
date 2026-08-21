package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** 本地截图入口的最终状态；不会代表已经发起 AI 网络请求。 */
enum class SnapshotCaptureStatus(val wireValue: String) {
    STORED("STORED"),
    PROTECTED("PROTECTED"),
    EXPIRED("EXPIRED"),
    REJECTED("REJECTED"),
    UNAVAILABLE("UNAVAILABLE"),
    DUPLICATE("DUPLICATE"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED")
}

/** 截图入口自身的安全和页面门禁拒绝原因。 */
enum class SnapshotCaptureRejection(val wireValue: String) {
    NO_ACTIVE_CHECKOUT("NO_ACTIVE_CHECKOUT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    CAPTURE_IN_FLIGHT("CAPTURE_IN_FLIGHT"),
    PAGE_CHANGED("PAGE_CHANGED")
}

/** Android 截图能力不可用或系统拒绝截图的原因。 */
enum class SnapshotCaptureUnavailableReason(val wireValue: String) {
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED"),
    API_UNSUPPORTED("API_UNSUPPORTED"),
    CAPABILITY_UNAVAILABLE("CAPABILITY_UNAVAILABLE"),
    SCREENSHOT_SECURE("SCREENSHOT_SECURE"),
    SCREENSHOT_BUSY("SCREENSHOT_BUSY"),
    INVALID_DISPLAY("INVALID_DISPLAY"),
    SYSTEM_REJECTED("SYSTEM_REJECTED"),
    ENCODE_FAILED("ENCODE_FAILED")
}

/** 不携带图片数据的截图结果，供购买意图协调器和 RN 显示状态使用。 */
data class SnapshotCaptureResult(
    val status: SnapshotCaptureStatus,
    val sourceReason: SnapshotSourceReason,
    val reasonCode: String,
    val sessionId: String? = null,
    val slotId: String? = null,
    val shoppingPackage: String? = null,
    val pageFingerprint: String? = null,
    val snapshotId: String? = null,
    val candidateRevision: Int? = null,
    val requiresDisambiguation: Boolean = false
)

/** 手动截图异步回调；回调只收到状态和元数据，不收到图片字节。 */
fun interface SnapshotCaptureCallback {
    fun onResult(result: SnapshotCaptureResult)
}

/**
 * 生成观察事件的诊断名称，用于熔断与分发失败日志。
 *
 * 只输出事件类型、购物包名与截断指纹：页面文本、金额、订单号一律不进日志，
 * 与"页面指纹不得保留敏感原值"的既有约束保持一致。
 *
 * @return 形如 `Stable(pkg=com.jingdong.app.mall,fp=4dc69b730966)` 的短标识。
 */
private fun CheckoutObservationEvent.diagnosticName(): String = when (this) {
    is CheckoutObservationEvent.Stable ->
        "Stable(pkg=${page.observation.shoppingPackage}," +
            "fp=${page.observation.pageFingerprint.take(12)})"
    is CheckoutObservationEvent.Invalidated ->
        "Invalidated(reason=${detail.reason.wireValue}," +
            "pkg=${detail.shoppingPackage ?: "null"}," +
            "fp=${detail.pageFingerprint?.take(12) ?: "null"})"
}

/**
 * 判断一次页面失效是否属于“同购物包内的支付中间页”。
 *
 * 中间页（拼多多提交订单后的收银台、京东提交后的加载页）不是结算页，却是
 * 结算 → 支付旅程的必经环节：应按预交接挂起会话与快照，而非清空槽位。
 *
 * 提取为纯函数以便单测覆盖——该分支判错会静默销毁唯一的结算截图，
 * 只能靠真机日志事后追查。
 *
 * 判据取“当前会话或已挂起会话”的包名：预交接已把 activeCheckout 置空，
 * 挂起期间同包内的每个非结算页事件都会让当前会话为空。若只看当前会话，
 * 这些后续事件会被误判为普通离开并清空槽位。
 *
 * @param reason 本次失效原因；只有 NON_CHECKOUT_PAGE 才可能是中间页。
 * @param invalidatedPackage 触发失效的页面所属购物包名。
 * @param handoffSourcePackage 当前会话或已挂起会话的购物包名；两者皆无时为 null。
 * @return true 表示应按预交接挂起处理，保留槽位与快照。
 */
internal fun isSamePackagePaymentInterstitial(
    reason: CheckoutInvalidationReason,
    invalidatedPackage: String?,
    handoffSourcePackage: String?
): Boolean {
    if (reason != CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
        return false
    }
    val invalidated = invalidatedPackage?.trim().orEmpty()
    val source = handoffSourcePackage?.trim().orEmpty()
    return invalidated.isNotBlank() && invalidated == source
}

/**
 * 判定普通页手动重试上下文能否接管截图槽位。
 *
 * 提取为纯函数以便单测覆盖。两类回归都由该判据统一收口：
 * 1. 交接挂起期间 `activeCheckout` 被置空，若只看"无人占用"就接管，会 discard
 *    掉正在交接的结算截图（拼多多回归）；
 * 2. 交接会话本身没有快照可保护时（结算截图在飞行中被中间页取消），若仍一律
 *    拒绝接管，空壳交接会长期霸占槽位，用户手动截图全部被 NO_ACTIVE_CHECKOUT
 *    拒绝（京东回归）。
 *
 * 因此判据是"交接会话是否真的有快照需要保护"，而非"是否存在交接会话"。
 *
 * @param handoffHasSnapshot 挂起的交接会话在缓冲中是否仍有可消费候选；
 * 无挂起会话时传 false。
 * @param activeCaptureTarget 当前活跃会话的截图目标；null 表示无活跃会话。
 * @return true 表示允许手动重试上下文接管槽位。
 */
internal fun canUserRetryTakeOverSlot(
    handoffHasSnapshot: Boolean,
    activeCaptureTarget: SnapshotCaptureTarget?
): Boolean = when {
    handoffHasSnapshot -> false
    activeCaptureTarget == SnapshotCaptureTarget.CHECKOUT -> false
    else -> true
}

/** 供后续协调器观察自动和手动截图状态的回调。 */
fun interface SnapshotCaptureResultListener {
    fun onResult(result: SnapshotCaptureResult)
}

/**
 * 把稳定结算页观察连接到内存截图缓冲区。
 *
 * 该类不执行点击、支付确认、AI 请求或记账；它只负责截图能力门禁、截图生命周期和缓冲写入。
 */
internal class CheckoutSnapshotCaptureCoordinator(
    private val accessibilityService: AccessibilityService,
    private val buffer: CheckoutSnapshotBuffer = CheckoutSnapshotBuffer(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() }
) : CheckoutObservationListener {
    companion object {
        private const val TAG = "CheckoutSnapshotCapture"
        private const val SCREENSHOT_ERROR_PREFIX = "SCREENSHOT_ERROR_"
        private const val MAX_SCREENSHOT_LONG_EDGE = 1080
        private const val JPEG_QUALITY = 85
        private const val MAX_ENCODED_SCREENSHOT_BYTES = 5 * 1024 * 1024
        private const val EVENT_DISPATCH_TIMEOUT_MILLIS = 5_000L
        /** 支付中间页挂起后，同包指纹漂移尾波稳定事件被视为旧订单的时间窗口。 */
        private const val HANDOFF_DRIFT_TAIL_WINDOW_MILLIS = 2_500L
        /** 熔断后"跳过观察事件"日志的采样间隔，兼顾可观测与防刷屏。 */
        private const val CLOSED_EVENT_SKIP_LOG_INTERVAL_MILLIS = 5_000L
        private const val MAIN_HANDLER_UNAVAILABLE = "MAIN_HANDLER_UNAVAILABLE"
        private const val EVENT_PROCESSING_FAILED = "EVENT_PROCESSING_FAILED"
        private const val SCREENSHOT_HANDLER_UNAVAILABLE = "SCREENSHOT_HANDLER_UNAVAILABLE"
    }

    private data class CaptureKey(
        val sessionId: String,
        val pageFingerprint: String
    )

    private data class ActiveCheckout(
        val sessionId: String,
        val slotId: String,
        val page: StableCheckoutPage,
        val captureTarget: SnapshotCaptureTarget
    ) {
        val key: CaptureKey
            get() = CaptureKey(sessionId, page.observation.pageFingerprint)
    }

    private data class PendingCapture(
        val requestId: String,
        val activeCheckout: ActiveCheckout,
        val sourceReason: SnapshotSourceReason,
        val callback: SnapshotCaptureCallback?,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val completionPublished: AtomicBoolean = AtomicBoolean(false),
        val finishStarted: AtomicBoolean = AtomicBoolean(false),
        val imageBytes: AtomicReference<ByteArray?> = AtomicReference(null)
    ) {
        fun offerImage(bytes: ByteArray?): Boolean {
            if (cancelled.get()) {
                bytes?.fill(0)
                return false
            }
            imageBytes.set(bytes)
            if (cancelled.get()) {
                imageBytes.getAndSet(null)?.fill(0)
                return false
            }
            return true
        }

        fun takeImage(): ByteArray? = imageBytes.getAndSet(null)

        fun releaseForEmergency() {
            cancelled.set(true)
            imageBytes.getAndSet(null)?.fill(0)
        }
    }

    private data class HandoffCheckout(
        val activeCheckout: ActiveCheckout,
        val attemptedAutomaticCaptures: Set<CaptureKey>
    )

    private data class QueuedManualCapture(
        val key: CaptureKey,
        val callback: SnapshotCaptureCallback,
        val completionPublished: AtomicBoolean = AtomicBoolean(false)
    )

    private class MainThreadDispatch {
        val completed = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotThread = HandlerThread("CheckoutScreenshot").apply { start() }
    private val screenshotHandler = Handler(screenshotThread.looper)
    private val callbackExecutor = Executor { runnable ->
        val posted = try {
            screenshotHandler.post(runnable)
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = SCREENSHOT_HANDLER_UNAVAILABLE)
            throw RejectedExecutionException(SCREENSHOT_HANDLER_UNAVAILABLE)
        }
    }
    private val pageDetector = CheckoutPageDetector()
    private val attemptedAutomaticCaptures = mutableSetOf<CaptureKey>()
    /** 只作为主线程不可用时的消费阻断标记，不承载协调器状态转换。 */
    private val failClosedRequested = AtomicBoolean(false)
    private val emergencyClosed = AtomicBoolean(false)
    private var activeCheckout: ActiveCheckout? = null
    private var pendingCapture: PendingCapture? = null
    private var queuedAutomatic: ActiveCheckout? = null
    private var queuedManual: QueuedManualCapture? = null
    private var handoffCheckout: HandoffCheckout? = null
    private var userRetryContext: UserRetryCaptureContext? = null
    /** "交接挂起期间跳过手动重试接管"日志的最近打印时间，按秒采样防止刷屏。 */
    private var lastHandoffRetrySkipLogAt = 0L

    /**
     * "协调器已关闭跳过事件"日志的最近打印时间。
     *
     * 熔断后每个无障碍事件都会命中该分支（真机每秒可达数十次），
     * 必须采样；但完全不打印会让"自动截图 0 次"变成无据可查的黑盒。
     */
    private var lastClosedEventSkipLogAt = 0L
    private var resultListener: SnapshotCaptureResultListener? = null
    /** fallback 可能在截图线程设置，主线程路径需要可靠观察关闭状态。 */
    @Volatile
    private var closed = false
    private var eventGeneration = 0L

    override fun onEvent(event: CheckoutObservationEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runEventOnMainThread(event)?.let { error ->
                throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
            }
            return
        }

        val dispatch = MainThreadDispatch()
        val postedAt = nowMillis()
        val posted = try {
            mainHandler.post {
                try {
                    dispatch.failure = runEventOnMainThread(event)
                } catch (error: Throwable) {
                    // 即使异常来自事件包装层，也必须把失败写入共享结果后再释放等待者。
                    dispatch.failure = error
                } finally {
                    dispatch.completed.countDown()
                }
            }
        } catch (error: RuntimeException) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE, error)
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE)
        }

        val completed = try {
            dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Log.w(
                TAG,
                "结算观察事件分发被中断: event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms, thread=${Thread.currentThread().name}"
            )
            requestFailClosedFromAnyThread(reason = EVENT_PROCESSING_FAILED)
            Thread.currentThread().interrupt()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        if (!completed) {
            // 主线程在超时窗口内未能处理该事件：这是熔断最常见的真实起因
            // （真机上伴随 Choreographer Skipped frames / Davey! 卡顿）。
            // 记录等待耗时可区分"主线程卡顿"与"处理逻辑本身异常"。
            Log.w(
                TAG,
                "结算观察事件分发超时，即将熔断协调器: event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms, " +
                    "timeout=${EVENT_DISPATCH_TIMEOUT_MILLIS}ms, " +
                    "thread=${Thread.currentThread().name}"
            )
            requestFailClosedFromAnyThread(reason = EVENT_PROCESSING_FAILED)
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }

        dispatch.failure?.let { error ->
            Log.w(
                TAG,
                "结算观察事件在主线程处理失败: event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms",
                error
            )
            if (!emergencyClosed.get()) {
                requestFailClosedFromAnyThread(reason = EVENT_PROCESSING_FAILED)
            }
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
        }
    }

    private fun runEventOnMainThread(event: CheckoutObservationEvent): Throwable? = try {
        handleEventOnMainThread(event)
        null
    } catch (error: Throwable) {
        Log.w(TAG, "结算观察事件处理失败，已关闭截图协调器", error)
        failClosedOnMainThread(reason = EVENT_PROCESSING_FAILED)
        error
    }

    private fun eventDispatchFailure(reason: String, cause: Throwable? = null): IllegalStateException =
        IllegalStateException(reason, cause)

    private fun handleEventOnMainThread(event: CheckoutObservationEvent) {
        if (isClosedOnMainThread()) {
            // 协调器熔断后自动链路在此静默 return，真机表现为"自动截图 0 次"。
            // 无障碍事件每秒可达数十次，因此按时间采样打印，避免刷屏。
            val now = nowMillis()
            if (now - lastClosedEventSkipLogAt >= CLOSED_EVENT_SKIP_LOG_INTERVAL_MILLIS) {
                lastClosedEventSkipLogAt = now
                Log.w(
                    TAG,
                    "协调器已关闭，跳过结算观察事件: event=${event.diagnosticName()}, " +
                        "closed=$closed, failClosedRequested=${failClosedRequested.get()}, " +
                        "emergencyClosed=${emergencyClosed.get()}"
                )
            }
            return
        }
        eventGeneration += 1
        when (event) {
            is CheckoutObservationEvent.Stable -> handleStable(event.page)
            is CheckoutObservationEvent.Invalidated -> handleInvalidated(event.detail)
        }
    }

    private fun isClosedOnMainThread(): Boolean =
        closed || failClosedRequested.get() || emergencyClosed.get()

    /** 仅在主线程摘除状态、关闭缓冲并发布取消结果。 */
    private fun failClosedOnMainThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null,
        reason: String
    ) {
        imageBytes?.fill(0)
        pending?.releaseForEmergency()
        if (closed) {
            return
        }

        failClosedRequested.set(true)
        // 与主线程不可用 fallback 竞争关闭所有权，避免双方交叉清理协调器状态。
        if (!emergencyClosed.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "协调器熔断请求被忽略(关闭所有权已被占用): reason=$reason, " +
                    "thread=${Thread.currentThread().name}"
            )
            return
        }
        closed = true
        // 熔断不可逆：下面会 quitSafely() 退出截图线程，此后所有手动截图返回
        // SERVICE_NOT_CONNECTED、自动链路在 handleEventOnMainThread 入口静默 return，
        // 只有服务重建才能恢复。必须留下原因与现场，否则真机上只能看到
        // "按钮点了没反应"而查不到任何关闭记录。
        Log.w(
            TAG,
            "协调器已熔断(不可逆，截图功能将持续不可用): reason=$reason, " +
                "thread=${Thread.currentThread().name}, " +
                "pendingCapture=${pendingCapture?.sourceReason?.wireValue ?: "none"}, " +
                "activeSession=${activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "handoffSession=${handoffCheckout?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "eventGeneration=$eventGeneration",
            Throwable("熔断调用栈")
        )
        try {
            buffer.close()
        } catch (error: RuntimeException) {
            Log.w(TAG, "关闭截图缓冲失败，继续释放协调器状态", error)
        }

        // 先完成协调器状态清理，再通知旧回调；回调重入只能看到已关闭的状态。
        try {
            screenshotThread.quitSafely()
        } catch (error: RuntimeException) {
            Log.w(TAG, "退出截图线程失败", error)
        }

        val pendingToNotify = mutableListOf<PendingCapture>()
        val queuedToNotify = mutableListOf<QueuedManualCapture>()
        pendingCapture?.let { current ->
            pendingToNotify += current
        }
        if (pending != null && pendingToNotify.none { it.requestId == pending.requestId }) {
            pendingToNotify += pending
        }
        pendingCapture = null
        pendingToNotify.forEach { it.releaseForEmergency() }

        queuedManual?.let { queuedToNotify += it }
        queuedManual = null
        queuedAutomatic = null
        activeCheckout = null
        handoffCheckout = null
        userRetryContext = null
        attemptedAutomaticCaptures.clear()
        resultListener = null

        pendingToNotify.forEach { cancelled ->
            publishPendingResult(
                pending = cancelled,
                result = cancelledResult(
                    current = cancelled.activeCheckout,
                    sourceReason = cancelled.sourceReason,
                    reason = reason
                )
            )
        }
        queuedToNotify.forEach { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = null,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = reason,
                    key = queued.key
                )
            )
        }
    }

    /**
     * 主线程不可用时建立关闭语义并摘除全部协调器状态。
     * fallback 不发布回调；无法安全通知的回调会被记录，迟到路径只能释放资源后返回。
     */
    private fun emergencyCloseWithoutMainThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null
    ) {
        imageBytes?.fill(0)
        pending?.releaseForEmergency()
        failClosedRequested.set(true)
        // 只有取得关闭所有权的一方可以清理普通协调器字段，避免覆盖主线程正常收口。
        if (!emergencyClosed.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "主线程不可用兜底关闭被忽略(关闭所有权已被占用): " +
                    "thread=${Thread.currentThread().name}"
            )
            return
        }
        closed = true
        // 该路径不发布任何回调，调用方拿不到结果只会一直等待或静默失败，
        // 因此这里是唯一的现场记录点。
        Log.w(
            TAG,
            "协调器已紧急关闭(主线程不可用，不发布回调): " +
                "thread=${Thread.currentThread().name}, " +
                "pendingCapture=${pendingCapture?.sourceReason?.wireValue ?: "none"}, " +
                "queuedManual=${queuedManual != null}, " +
                "eventGeneration=$eventGeneration",
            Throwable("紧急关闭调用栈")
        )

        val pendingToRelease = pendingCapture
        val queuedToDrop = queuedManual
        pendingCapture = null
        queuedManual = null
        queuedAutomatic = null
        activeCheckout = null
        handoffCheckout = null
        userRetryContext = null
        attemptedAutomaticCaptures.clear()
        resultListener = null
        pendingToRelease?.let {
            it.releaseForEmergency()
            it.completionPublished.compareAndSet(false, true)
        }
        pending?.completionPublished?.compareAndSet(false, true)
        queuedToDrop?.completionPublished?.compareAndSet(false, true)
        if (pendingToRelease != null || pending != null || queuedToDrop != null) {
            Log.w(
                TAG,
                "主线程不可用，已丢弃未通知的截图回调: " +
                    "pending=" + (pendingToRelease?.requestId ?: pending?.requestId) +
                    ", queued=" + (queuedToDrop != null)
            )
        }
        try {
            buffer.close()
        } catch (error: RuntimeException) {
            Log.w(TAG, "主线程不可用时关闭截图缓冲失败", error)
        }
        try {
            screenshotThread.quitSafely()
        } catch (error: RuntimeException) {
            Log.w(TAG, "主线程不可用时退出截图线程失败", error)
        }
    }

    /** 请求主线程执行完整 fail-closed；只有投递或等待失败才走最小原子 fallback。 */
    private fun requestFailClosedFromAnyThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null,
        reason: String
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            failClosedOnMainThread(pending, imageBytes, reason)
            return true
        }

        failClosedRequested.set(true)
        // 该标记先于主线程投递生效：isClosedOnMainThread() 立即返回 true，
        // 因此从此刻起手动截图就会返回 SERVICE_NOT_CONNECTED，
        // 而真正的 failClosedOnMainThread 可能还排在阻塞的主线程队列里。
        // 真机故障（主线程卡顿 4.8 秒后事件分发超时）正是走这条路径，
        // 此处不记录就无法解释"点击受理后数秒才批量失败"。
        Log.w(
            TAG,
            "已请求协调器熔断(跨线程，判定即刻生效): reason=$reason, " +
                "thread=${Thread.currentThread().name}, " +
                "pending=${pending?.sourceReason?.wireValue ?: "none"}",
            Throwable("熔断请求调用栈")
        )
        val dispatch = MainThreadDispatch()
        val posted = try {
            mainHandler.post {
                try {
                    failClosedOnMainThread(pending, imageBytes, reason)
                } catch (error: Throwable) {
                    dispatch.failure = error
                } finally {
                    dispatch.completed.countDown()
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            return false
        }

        val completed = try {
            dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            Thread.currentThread().interrupt()
            return false
        }
        if (!completed || dispatch.failure != null) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            return false
        }
        return emergencyClosed.get()
    }

    /** 设置自动截图结果监听器；监听器不拥有图片引用。 */
    fun setResultListener(listener: SnapshotCaptureResultListener?) {
        val posted = try {
            mainHandler.post {
                resultListener = listener
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /**
     * 挂起的交接会话在缓冲中是否仍有可消费候选。
     *
     * 用于区分两种交接状态：
     * - 有快照：槽位承载着等待支付交接消费的结算截图，必须保护；
     * - 无快照：结算截图在飞行中被中间页取消，或已过期被清理，此时交接只剩
     *   元数据外壳，继续独占槽位会让手动截图一直被 NO_ACTIVE_CHECKOUT 拒绝。
     *
     * @return true 表示存在挂起会话且其槽位内仍有该会话的候选。
     */
    private fun handoffCheckoutHasSnapshot(): Boolean {
        val retained = handoffCheckout?.activeCheckout ?: return false
        return try {
            buffer.currentCandidates(retained.slotId).any { candidate ->
                candidate.sessionId == retained.sessionId
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 按秒采样输出"交接挂起期间跳过手动重试接管"，避免中间页高频事件刷屏。
     *
     * @param contextPackage 被跳过的手动重试上下文所属购物包，仅用于诊断。
     */
    private fun logHandoffRetrySkipSampled(contextPackage: String?) {
        val now = nowMillis()
        if (now - lastHandoffRetrySkipLogAt < 1_000L) {
            return
        }
        lastHandoffRetrySkipLogAt = now
        Log.i(
            TAG,
            "交接挂起期间跳过手动重试接管: " +
                "handoffSession=${handoffCheckout?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "contextPkg=${contextPackage ?: "null"}"
        )
    }

    /** 设置服务最近一次确认的手动截图上下文；节点树不可读时只保留脱敏元数据并禁止捕获。 */
    fun setUserRetryContext(context: UserRetryCaptureContext?) {
        val posted = try {
            mainHandler.post {
                if (closed) {
                    return@post
                }
                val current = activeCheckout
                val activeUserRetry = current?.takeIf {
                    it.captureTarget == SnapshotCaptureTarget.USER_RETRY
                }
                val resolvedContext = context?.let { next ->
                    if (
                        activeUserRetry != null &&
                        next.isComplete() &&
                        next.sessionId.isNullOrBlank() &&
                        next.shoppingPackage == activeUserRetry.page.observation.shoppingPackage &&
                        next.pageFingerprint == activeUserRetry.page.observation.pageFingerprint &&
                        next.pageRole == activeUserRetry.page.observation.pageRole &&
                        next.isSensitivePage == activeUserRetry.page.observation.isSensitivePage
                    ) {
                        next.withSessionId(activeUserRetry.sessionId)
                    } else {
                        next
                    }
                }
                val previous = userRetryContext
                userRetryContext = resolvedContext
                // 支付交接挂起期间槽位属于挂起会话，其快照正等待切支付应用后被消费，
                // 此时 activeCheckout 已被置空，不能让手动重试上下文接管并 discard。
                // 但若挂起会话本身已无快照（结算截图在飞行中被中间页取消），继续拒绝
                // 只会让空壳交接长期霸占槽位、手动截图一直失败，此时应允许接管。
                val handoffHasSnapshot = handoffCheckoutHasSnapshot()
                if (!canUserRetryTakeOverSlot(handoffHasSnapshot, current?.captureTarget)) {
                    if (handoffHasSnapshot) {
                        logHandoffRetrySkipSampled(resolvedContext?.shoppingPackage)
                    }
                    return@post
                }
                if (current?.captureTarget == SnapshotCaptureTarget.USER_RETRY) {
                    val sameContext = resolvedContext?.let {
                        it.sessionId == current.sessionId &&
                            it.shoppingPackage == current.page.observation.shoppingPackage &&
                            it.pageFingerprint == current.page.observation.pageFingerprint &&
                            it.pageRole == current.page.observation.pageRole &&
                            it.isSensitivePage == current.page.observation.isSensitivePage
                    } == true
                    if (!sameContext || resolvedContext?.isComplete() != true) {
                        activeCheckout = null
                        buffer.discard(current.slotId, reason = "USER_RETRY_CONTEXT_CHANGED")
                    } else if (resolvedContext != null) {
                        activeCheckout = current.copy(
                            page = StableCheckoutPage(
                                observation = resolvedContext.observation,
                                stableAt = nowMillis()
                            )
                        )
                    }
                }

                if (resolvedContext?.hasSafeDisplayContext() == true &&
                    resolvedContext.isComplete() &&
                    (activeCheckout == null || activeCheckout?.captureTarget == SnapshotCaptureTarget.USER_RETRY)
                ) {
                    val sessionId = resolvedContext.sessionId ?: sessionIdFactory()
                    val previousKey = previous?.let {
                        it.shoppingPackage to it.pageFingerprint
                    }
                    val nextKey = resolvedContext.shoppingPackage to resolvedContext.pageFingerprint
                    if (previousKey != nextKey) {
                        buffer.discard(
                            checkoutSnapshotSlotId(resolvedContext.shoppingPackage),
                            reason = "USER_RETRY_NEW_PAGE"
                        )
                    }
                    activeCheckout = ActiveCheckout(
                        sessionId = sessionId,
                        slotId = checkoutSnapshotSlotId(resolvedContext.shoppingPackage),
                        page = StableCheckoutPage(
                            observation = resolvedContext.observation,
                            stableAt = nowMillis()
                        ),
                        captureTarget = SnapshotCaptureTarget.USER_RETRY
                    )
                    userRetryContext = resolvedContext.copy(sessionId = sessionId)
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /** 用户点击入口；手动截图不等待新的稳定窗口，但仍必须经过结算页门禁。 */
    fun requestManualCapture(callback: SnapshotCaptureCallback) {
        val posted = try {
            mainHandler.post {
                if (closed) {
                    // 悬浮按钮此时仍显示 READY 并受理点击，用户只会看到"点了没反应"。
                    // 打印三个关闭标志位以区分：正常 close(SERVICE_DESTROYED)、
                    // 跨线程熔断请求、以及主线程不可用的紧急关闭。
                    Log.w(
                        TAG,
                        "手动截图被拒(协调器已关闭): closed=$closed, " +
                            "failClosedRequested=${failClosedRequested.get()}, " +
                            "emergencyClosed=${emergencyClosed.get()}"
                    )
                    publish(
                        unavailableResult(
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reasonCode = SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
                        ),
                        callback
                    )
                    return@post
                }

                val current = activeCheckout
                if (current == null) {
                    publish(
                        rejectedResult(
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.NO_ACTIVE_CHECKOUT
                        ),
                        callback
                    )
                    return@post
                }

                if (current.captureTarget == SnapshotCaptureTarget.CHECKOUT) {
                    when (pageDetector.detect(current.page.observation)) {
                        is CheckoutPageDetection.Accepted -> Unit
                        is CheckoutPageDetection.Rejected -> {
                            publish(
                                rejectedResult(
                                    current = current,
                                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                                    reason = if (current.page.observation.isSensitivePage) {
                                        SnapshotCaptureRejection.SENSITIVE_PAGE
                                    } else {
                                        SnapshotCaptureRejection.NOT_CHECKOUT_PAGE
                                    }
                                ),
                                callback
                            )
                            return@post
                        }
                    }
                } else if (userRetryContext?.isComplete() != true ||
                    userRetryContext?.sessionId != current.sessionId ||
                    userRetryContext?.pageFingerprint != current.page.observation.pageFingerprint
                ) {
                    publish(
                        rejectedResult(
                            current = current,
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.NOT_CHECKOUT_PAGE
                        ),
                        callback
                    )
                    return@post
                }

                val observation = current.page.observation
                if (observation.isSensitivePage) {
                    publish(
                        rejectedResult(
                            current = current,
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.SENSITIVE_PAGE
                        ),
                        callback
                    )
                    return@post
                }

                if (buffer.hasCandidate(
                        slotId = current.slotId,
                        sessionId = current.sessionId,
                        pageFingerprint = observation.pageFingerprint,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE
                    )
                ) {
                    publish(
                        duplicateResult(current, SnapshotSourceReason.MANUAL_CAPTURE),
                        callback
                    )
                    return@post
                }

                val inFlight = pendingCapture
                if (inFlight != null) {
                    if (inFlight.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE || queuedManual != null) {
                        publish(
                            rejectedResult(
                                current = current,
                                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                                reason = SnapshotCaptureRejection.CAPTURE_IN_FLIGHT
                            ),
                            callback
                        )
                    } else {
                        if (!closed) {
                            queuedManual = QueuedManualCapture(current.key, callback)
                        } else {
                            publish(
                                unavailableResult(
                                    current = current,
                                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                                    reasonCode = MAIN_HANDLER_UNAVAILABLE
                                ),
                                callback
                            )
                        }
                    }
                    return@post
                }

                beginCapture(current, SnapshotSourceReason.MANUAL_CAPTURE, callback)
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            Log.w(TAG, "主线程不可用时手动截图未能发布结果")
        }
    }

    /**
     * 给支付结果页交接兜底：即使服务层 currentStableCheckout 因瞬时事件被清空，
     * 截图协调器仍持有 activeCheckout/handoffCheckout 时，优先返回 buffer 中
     * 的真实引用；buffer 已被清空时用页面元数据构造引用，供支付证据建立会话。
     */
    fun currentPaymentHandoffReference(): CheckoutSnapshotReference? {
        if (closed) return null
        val source = handoffCheckout?.activeCheckout ?: activeCheckout ?: return null
        val observation = source.page.observation
        val buffered = buffer.findReference(source.slotId, observation.pageFingerprint)
        return buffered ?: CheckoutSnapshotReference(
            snapshotId = "",
            sessionId = source.sessionId,
            slotId = source.slotId,
            shoppingPackage = observation.shoppingPackage,
            pageRole = observation.pageRole,
            pageFingerprint = observation.pageFingerprint,
            capturedAt = observation.observedAt,
            expiresAt = observation.observedAt + 30_000L,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    /** 服务中断时立即取消回调并清理当前购物包槽位。 */
    fun onServiceInterrupted() {
        val posted = try {
            mainHandler.post {
                try {
                    if (!closed) {
                        handleInvalidated(
                            CheckoutObservationInvalidation(
                                reason = CheckoutInvalidationReason.SERVICE_INTERRUPTED,
                                shoppingPackage = activeCheckout?.page?.observation?.shoppingPackage,
                                pageFingerprint = activeCheckout?.page?.observation?.pageFingerprint,
                                invalidatedAt = nowMillis()
                            )
                        )
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "服务中断清理失败，已关闭截图协调器", error)
                    failClosedOnMainThread(reason = EVENT_PROCESSING_FAILED)
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /** 服务销毁时关闭协调器并释放所有正在等待的图片。 */
    fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeOnMainThread()
            return
        }

        val closeDispatch = MainThreadDispatch()
        val posted = try {
            mainHandler.post {
                try {
                    closeOnMainThread()
                } catch (error: Throwable) {
                    closeDispatch.failure = error
                } finally {
                    closeDispatch.completed.countDown()
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE)
        }

        val completed = try {
            closeDispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            emergencyCloseWithoutMainThread()
            Thread.currentThread().interrupt()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        if (!completed) {
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        closeDispatch.failure?.let { error ->
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
        }
    }

    private fun closeOnMainThread() {
        if (closed) {
            return
        }
        // 关闭先建立 buffer 消费栅栏，再释放旧回调，避免回调重入消费旧快照。
        failClosedOnMainThread(reason = CheckoutInvalidationReason.SERVICE_DESTROYED.wireValue)
    }

    private fun publishPendingResult(
        pending: PendingCapture,
        result: SnapshotCaptureResult
    ) {
        if (pending.completionPublished.compareAndSet(false, true)) {
            publish(result, pending.callback)
        }
    }

    private fun publishQueuedResult(
        queued: QueuedManualCapture,
        result: SnapshotCaptureResult
    ) {
        if (queued.completionPublished.compareAndSet(false, true)) {
            publish(result, queued.callback)
        }
    }

    /**
     * 判断旧结算会话的截图是否值得跨指纹漂移保留。
     *
     * 只有同一购物包内的指纹变化才可能是轮播/倒计时造成的漂移；切换购物应用
     * 一定是新订单，旧图必须释放，否则会把上一单的截图关联到本单支付证据。
     *
     * 另外要求旧槽位确实存在未过期、未消费的候选，避免为空槽位保留消费墓碑
     * 而阻塞新会话截图。
     *
     * @param previous 漂移前的结算会话。
     * @param observation 新的稳定页面观察结果。
     * @return true 表示旧槽位应保留，不执行 discard。
     */
    private fun hasRetainableCandidate(
        previous: ActiveCheckout,
        observation: CheckoutPageObservation
    ): Boolean {
        if (previous.page.observation.shoppingPackage != observation.shoppingPackage) {
            return false
        }
        if (previous.captureTarget != SnapshotCaptureTarget.CHECKOUT) {
            return false
        }
        return try {
            buffer.currentCandidates(previous.slotId).any { candidate ->
                candidate.sessionId == previous.sessionId
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun handleStable(page: StableCheckoutPage) {
        val transitionGeneration = eventGeneration
        val observation = page.observation
        // 真实结算事件优先于普通页手动重试上下文，避免两个目标共享一次点击。
        userRetryContext = null
        if (pageDetector.detect(observation) !is CheckoutPageDetection.Accepted) {
            handleInvalidated(
                CheckoutObservationInvalidation(
                    reason = if (observation.isSensitivePage) {
                        CheckoutInvalidationReason.SENSITIVE_PAGE
                    } else {
                        CheckoutInvalidationReason.NON_CHECKOUT_PAGE
                    },
                    shoppingPackage = observation.shoppingPackage,
                    pageFingerprint = observation.pageFingerprint,
                    invalidatedAt = nowMillis()
                )
            )
            return
        }

        val retainedHandoff = handoffCheckout
        if (retainedHandoff != null) {
            if (closed) {
                return
            }
            val retained = retainedHandoff.activeCheckout
            val isSameCheckout =
                retained.page.observation.shoppingPackage == observation.shoppingPackage &&
                    retained.page.observation.pageFingerprint == observation.pageFingerprint
            // 支付中间页挂起初期，结算页指纹漂移（倒计时/控件刷新）的迟到稳定事件
            // 不代表新订单：保留挂起会话与快照，等待切支付应用消费。
            val isDriftTail =
                !isSameCheckout &&
                    observation.shoppingPackage == retained.page.observation.shoppingPackage &&
                    observation.observedAt - retained.page.stableAt < HANDOFF_DRIFT_TAIL_WINDOW_MILLIS
            if (isDriftTail) {
                return
            }
            handoffCheckout = null
            if (isSameCheckout) {
                if (closed) {
                    return
                }
                // 支付应用返回原结算页时恢复原会话和尝试状态，不重新截图或覆盖原候选。
                buffer.restoreFromPaymentHandoff(
                    slotId = retained.slotId,
                    sessionId = retained.sessionId,
                    pageFingerprint = observation.pageFingerprint
                )
                activeCheckout = retained.copy(page = page)
                attemptedAutomaticCaptures.clear()
                attemptedAutomaticCaptures += retainedHandoff.attemptedAutomaticCaptures
                if (closed) {
                    // 关闭可能与恢复并发发生；不得让旧交接状态在失败收口后复活。
                    activeCheckout = null
                    handoffCheckout = null
                    attemptedAutomaticCaptures.clear()
                }
                return
            }

            // 返回到不同页面时，旧交接会话和其槽位不能被新订单复用。
            buffer.discard(retained.slotId, reason = "HANDOFF_RETURNED_TO_OTHER_CHECKOUT")
            attemptedAutomaticCaptures.clear()
        }

        val previous = activeCheckout
        // 同一购物包内的指纹变化可能只是轮播/倒计时造成的漂移，而非新订单。
        // 若旧会话已有未消费且未过期的截图，沿用旧会话与槽位：既避免把"已截到的图"
        // 在提交订单前清零（真机上曾导致支付交接只剩 NO_BUFFERED_SNAPSHOT），
        // 也让后续截图落在同一会话内由 buffer 按指纹去重。
        val driftWithinSameCheckout = previous != null &&
            previous.page.observation.shoppingPackage == observation.shoppingPackage &&
            previous.page.observation.pageFingerprint != observation.pageFingerprint &&
            hasRetainableCandidate(previous, observation)
        val startsNewSession = previous == null ||
            previous.page.observation.shoppingPackage != observation.shoppingPackage ||
            (previous.page.observation.pageFingerprint != observation.pageFingerprint &&
                !driftWithinSameCheckout)
        var pendingToNotify: PendingCapture? = null
        var queuedManualToNotify: QueuedManualCapture? = null
        val current = if (startsNewSession) {
            // 先摘除旧请求和排队项；回调必须等新会话及旧槽位清理完成后才能重入。
            pendingToNotify = detachPendingCapture()
            queuedManualToNotify = queuedManual
            queuedManual = null
            queuedAutomatic = null
            previous?.let { oldCheckout ->
                // 新指纹代表新的订单边界；先释放旧槽位，避免旧图片或消费墓碑阻塞新会话。
                buffer.discard(oldCheckout.slotId, reason = "NEW_CHECKOUT_SESSION")
                attemptedAutomaticCaptures.removeAll { it.sessionId == oldCheckout.sessionId }
            }
            ActiveCheckout(
                sessionId = sessionIdFactory(),
                slotId = slotIdFor(observation.shoppingPackage),
                page = page,
                captureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        } else {
            if (driftWithinSameCheckout) {
                Log.i(
                    TAG,
                    "结算页指纹漂移沿用原会话: slot=${previous!!.slotId}, " +
                        "session=${previous.sessionId.take(8)}"
                )
            }
            previous!!.copy(page = page)
        }
        activeCheckout = current

        pendingToNotify?.let { pending ->
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                )
            )
        }
        queuedManualToNotify?.let { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = previous,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                    key = queued.key
                )
            )
        }

        // 取消回调允许同步重入；若重入已经推进事件序列，旧事件不得继续写入或排队。
        if (closed || eventGeneration != transitionGeneration || activeCheckout?.key != current.key) {
            if (closed) {
                activeCheckout = null
                handoffCheckout = null
                queuedAutomatic = null
            }
            return
        }

        val key = current.key
        if (key in attemptedAutomaticCaptures) {
            return
        }

        // 指纹漂移沿用原会话时，槽内已有本会话的可用截图：结算页内容并未真正改变，
        // 不必为每一次漂移重复截图（真机上曾在 8 秒内连截 6 张并互相覆盖）。
        // 后续支付交接会按槽内真实指纹消费这张已有截图。
        if (driftWithinSameCheckout) {
            attemptedAutomaticCaptures += key
            publish(duplicateResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }

        if (buffer.hasCandidate(
                slotId = current.slotId,
                sessionId = current.sessionId,
                pageFingerprint = observation.pageFingerprint,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE
            )
        ) {
            attemptedAutomaticCaptures += key
            publish(protectedResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }

        if (buffer.hasCandidate(
                slotId = current.slotId,
                sessionId = current.sessionId,
                pageFingerprint = observation.pageFingerprint,
                sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
            )
        ) {
            attemptedAutomaticCaptures += key
            publish(duplicateResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }

        if (pendingCapture != null) {
            queuedAutomatic = current
            return
        }

        attemptedAutomaticCaptures += key
        beginCapture(current, SnapshotSourceReason.CHECKOUT_STABLE, callback = null)
    }

    private fun handleInvalidated(detail: CheckoutObservationInvalidation) {
        val current = activeCheckout
        val retained = handoffCheckout
        // 同购物包内的非结算中间页（拼多多提交订单后的收银台）是支付旅程的一部分：
        // 视作“预交接”挂起当前结算会话与快照，等待切支付应用或回到结算页恢复，
        // 而不是立即清空槽位导致已 STORED 的截图再也关联不上支付证据。
        //
        // 判定必须同时接受已挂起会话（retained）：预交接已把 activeCheckout 置空，
        // 此后同包内的每一个非结算页事件都会让 current==null。若只看 current，
        // 挂起期间的后续事件就会被当成"普通离开"而清空槽位——真机上京东在
        // 10:54:27 正确设置交接栅栏后，10:54:57 的同包事件即以
        // current=null,retained=3c91b93b,consumed=false 把唯一快照清空，
        // 随后支付证据只能落 NO_BUFFERED_SNAPSHOT / CHECKOUT_REFERENCE_INVALID。
        val handoffSource = current ?: retained?.activeCheckout
        val isPaymentInterstitial = isSamePackagePaymentInterstitial(
            reason = detail.reason,
            invalidatedPackage = detail.shoppingPackage,
            handoffSourcePackage = handoffSource?.page?.observation?.shoppingPackage
        )
        val retainForHandoff =
            (detail.reason == CheckoutInvalidationReason.PAYMENT_APP_HANDOFF || isPaymentInterstitial) &&
                handoffSource != null
        val nextHandoff = if (!retainForHandoff) {
            null
        } else if (retained != null) {
            retained
        } else {
            val activeForHandoff = checkNotNull(handoffSource)
            HandoffCheckout(
                activeCheckout = activeForHandoff,
                attemptedAutomaticCaptures = attemptedAutomaticCaptures
                    .filter { it.sessionId == activeForHandoff.sessionId }
                    .toSet()
            )
        }

        // 失效决策链路诊断：retainForHandoff=false 即代表本次会清空全部图片候选。
        // current/retained 的组合可区分"首次进入中间页"与"中间页后续事件"。
        Log.i(
            TAG,
            "观察失效: reason=${detail.reason.wireValue}, " +
                "current=${current?.sessionId?.take(8) ?: "null"}, " +
                "retained=${retained?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "interstitial=$isPaymentInterstitial, retainForHandoff=$retainForHandoff, " +
                "detailPkg=${detail.shoppingPackage ?: "null"}, " +
                "detailFingerprint=${detail.pageFingerprint?.take(12) ?: "null"}"
        )

        // 先摘除请求和排队项，再建立交接栅栏并清空旧状态，最后才允许旧回调重入。
        // 例外：支付交接（含同包中间页预交接）且截图仍在飞行中时不摘除请求。
        // 京东这类"结算页稳定后数百毫秒即跳中间页"的时序下，摘除等于把唯一一次
        // 结算截图取消，槽位永远为空，支付证据只能落 CHECKOUT_REFERENCE_INVALID。
        // 保留请求可让它落盘到挂起会话的槽位，交接时正常被消费。
        val keepPendingForHandoff = retainForHandoff &&
            pendingCapture != null &&
            pendingCapture?.sourceReason == SnapshotSourceReason.CHECKOUT_STABLE &&
            pendingCapture?.activeCheckout?.sessionId == nextHandoff?.activeCheckout?.sessionId
        val pendingToNotify = if (keepPendingForHandoff) {
            Log.i(
                TAG,
                "交接期间保留飞行中的结算截图: " +
                    "session=${pendingCapture?.activeCheckout?.sessionId?.take(8)}, " +
                    "reason=${detail.reason.wireValue}"
            )
            null
        } else {
            detachPendingCapture()
        }
        val queuedManualToNotify = queuedManual
        queuedManual = null
        queuedAutomatic = null
        userRetryContext = null
        // 只有明确的支付交接保留快照；普通离开和其他失效原因都清理槽位，避免旧图误用。
        if (!retainForHandoff) {
            buffer.clear(
                reason = "INVALIDATED_${detail.reason.wireValue}" +
                    "(current=${current != null},retained=${retained != null}," +
                    "interstitial=$isPaymentInterstitial," +
                    "pkg=${detail.shoppingPackage ?: "null"})"
            )
        } else {
            // 栅栏必须落在真正被挂起的那个会话上（nextHandoff），而不是 handoffSource。
            // 二次交接时 handoffSource 取的是当前会话，若它并非挂起会话，会用错误的
            // sessionId/指纹设置栅栏，真机上表现为"交接栅栏设置失败(槽位不存在)"。
            nextHandoff?.activeCheckout?.let { retainedCheckout ->
                buffer.retainForPaymentHandoff(
                    slotId = retainedCheckout.slotId,
                    sessionId = retainedCheckout.sessionId,
                    pageFingerprint = retainedCheckout.page.observation.pageFingerprint
                )
            }
        }
        handoffCheckout = nextHandoff
        activeCheckout = null
        attemptedAutomaticCaptures.clear()
        if (closed) {
            handoffCheckout = null
            activeCheckout = null
            queuedAutomatic = null
            return
        }

        pendingToNotify?.let { pending ->
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = detail.reason.wireValue
                )
            )
        }
        queuedManualToNotify?.let { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = current,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = detail.reason.wireValue,
                    key = queued.key
                )
            )
        }
    }

    private fun detachPendingCapture(): PendingCapture? {
        val pending = pendingCapture ?: return null
        // 先解除当前请求，晚到的系统回调只能走释放路径。
        pendingCapture = null
        pending.releaseForEmergency()
        return pending
    }

    private fun beginCapture(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        callback: SnapshotCaptureCallback?,
        completionPublished: AtomicBoolean = AtomicBoolean(false),
        queued: QueuedManualCapture? = null
    ) {
        if (closed) {
            val result = cancelledResult(
                current = current,
                sourceReason = sourceReason,
                reason = MAIN_HANDLER_UNAVAILABLE,
                key = queued?.key
            )
            if (queued != null) {
                publishQueuedResult(queued, result)
            } else {
                publish(result, callback)
            }
            return
        }
        val unavailableReason = screenshotUnavailableReason()
        if (unavailableReason != null) {
            val result = unavailableResult(
                current = current,
                sourceReason = sourceReason,
                reasonCode = unavailableReason.wireValue
            )
            if (queued != null) {
                publishQueuedResult(queued, result)
            } else {
                publish(result, callback)
            }
            drainQueuedCapture()
            return
        }

        val pending = PendingCapture(
            requestId = UUID.randomUUID().toString(),
            activeCheckout = current,
            sourceReason = sourceReason,
            callback = callback,
            completionPublished = completionPublished
        )
        pendingCapture = pending

        if (closed) {
            pendingCapture = null
            pending.releaseForEmergency()
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = current,
                    sourceReason = sourceReason,
                    reason = MAIN_HANDLER_UNAVAILABLE,
                    key = queued?.key
                )
            )
            return
        }

        try {
            @Suppress("NewApi")
            accessibilityService.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                callbackExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val encoded = encodeScreenshot(screenshot)
                        finishCapture(pending, encoded.bytes, encoded.reasonCode)
                    }

                    override fun onFailure(errorCode: Int) {
                        finishCapture(
                            pending = pending,
                            imageBytes = null,
                            failureReason = screenshotFailureReason(errorCode)
                        )
                    }
                }
            )
        } catch (_: SecurityException) {
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE.wireValue
            )
        } catch (_: IllegalStateException) {
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "调用系统截图失败", error)
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
            )
        }
    }

    private fun finishCapture(
        pending: PendingCapture,
        imageBytes: ByteArray?,
        failureReason: String?
    ) {
        if (!pending.finishStarted.compareAndSet(false, true)) {
            imageBytes?.fill(0)
            return
        }
        if (!pending.offerImage(imageBytes)) {
            return
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            val dispatch = MainThreadDispatch()
            val posted = try {
                mainHandler.post {
                    try {
                        finishCaptureOnMainThread(pending, failureReason)
                    } catch (error: Throwable) {
                        dispatch.failure = error
                        pending.takeImage()?.fill(0)
                        failClosedOnMainThread(
                            pending = pending,
                            reason = EVENT_PROCESSING_FAILED
                        )
                    } finally {
                        dispatch.completed.countDown()
                    }
                }
            } catch (_: RuntimeException) {
                false
            }
            if (!posted) {
                requestFailClosedFromAnyThread(
                    pending = pending,
                    reason = MAIN_HANDLER_UNAVAILABLE
                )
                return
            }

            val completed = try {
                dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                requestFailClosedFromAnyThread(
                    pending = pending,
                    reason = EVENT_PROCESSING_FAILED
                )
                Thread.currentThread().interrupt()
                return
            }
            if (!completed || dispatch.failure != null) {
                requestFailClosedFromAnyThread(
                    pending = pending,
                    reason = EVENT_PROCESSING_FAILED
                )
            }
            return
        }

        finishCaptureOnMainThread(pending, failureReason)
    }

    private fun finishCaptureOnMainThread(
        pending: PendingCapture,
        failureReason: String?
    ) {
        if (closed || emergencyClosed.get()) {
            pending.takeImage()?.fill(0)
            return
        }
        val imageBytes = pending.takeImage()
        if (pendingCapture?.requestId != pending.requestId) {
            imageBytes?.fill(0)
            return
        }
        pendingCapture = null

        // 交接挂起期间 activeCheckout 已被置空，但该请求属于挂起会话时仍应落盘：
        // 京东"结算页稳定后即跳中间页"的时序下，这是唯一一次结算截图。
        val belongsToHandoff = handoffCheckout?.activeCheckout?.key == pending.activeCheckout.key
        val keyMatches = activeCheckout?.key == pending.activeCheckout.key || belongsToHandoff
        if (pending.cancelled.get() || closed || !keyMatches) {
            imageBytes?.fill(0)
            pending.cancelled.set(true)
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                )
            )
            drainQueuedCapture()
            return
        }

        val capturedBytes = imageBytes
        if (failureReason != null || capturedBytes == null || capturedBytes.isEmpty()) {
            capturedBytes?.fill(0)
            val result = if (failureReason == SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue) {
                failedResult(
                    pending.activeCheckout,
                    pending.sourceReason,
                    failureReason ?: SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue
                )
            } else {
                unavailableResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reasonCode = failureReason ?: SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue
                )
            }
            publishPendingResult(pending, result)
            drainQueuedCapture()
            return
        }

        val request = SnapshotCaptureRequest(
            slotId = pending.activeCheckout.slotId,
            sessionId = pending.activeCheckout.sessionId,
            shoppingPackage = pending.activeCheckout.page.observation.shoppingPackage,
            pageRole = pending.activeCheckout.page.observation.pageRole,
            pageFingerprint = pending.activeCheckout.page.observation.pageFingerprint,
            sourceReason = pending.sourceReason,
            imageBytes = capturedBytes,
            isSensitivePage = pending.activeCheckout.page.observation.isSensitivePage,
            captureTarget = pending.activeCheckout.captureTarget
        )

        val result = try {
            try {
                storeResult(pending.activeCheckout, pending.sourceReason, buffer.store(request))
            } catch (error: RuntimeException) {
                Log.w(TAG, "写入截图缓冲失败", error)
                failedResult(pending.activeCheckout, pending.sourceReason, "BUFFER_STORE_FAILED")
            }
        } finally {
            // 缓冲区在成功时已复制字节；失败、拒绝和取消都在这里清零入口数组。
            capturedBytes.fill(0)
        }
        // 交接挂起期间落盘的结算截图，其栅栏在 handleInvalidated 时因槽位仍为空而
        // 设置失败，必须在此补设，否则支付交接消费时仍会 NO_BUFFERED_SNAPSHOT。
        if (belongsToHandoff && result.status == SnapshotCaptureStatus.STORED) {
            try {
                buffer.retainForPaymentHandoff(
                    slotId = pending.activeCheckout.slotId,
                    sessionId = pending.activeCheckout.sessionId,
                    pageFingerprint = pending.activeCheckout.page.observation.pageFingerprint
                )
                Log.i(
                    TAG,
                    "交接后落盘已补设栅栏: " +
                        "session=${pending.activeCheckout.sessionId.take(8)}, " +
                        "fingerprint=${pending.activeCheckout.page.observation.pageFingerprint.take(12)}"
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "交接后补设栅栏失败", error)
            }
        }
        publishPendingResult(pending, result)
        drainQueuedCapture()
    }

    private fun storeResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        storeResult: SnapshotStoreResult
    ): SnapshotCaptureResult {
        return when (storeResult) {
            is SnapshotStoreResult.Stored -> {
                val snapshot = storeResult.snapshot
                val result = SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.STORED,
                    sourceReason = sourceReason,
                    reasonCode = SnapshotCaptureStatus.STORED.wireValue,
                    sessionId = snapshot.sessionId,
                    slotId = snapshot.slotId,
                    shoppingPackage = snapshot.shoppingPackage,
                    pageFingerprint = snapshot.pageFingerprint,
                    snapshotId = snapshot.snapshotId,
                    candidateRevision = storeResult.candidateRevision,
                    requiresDisambiguation = storeResult.requiresDisambiguation
                )
                snapshot.release()
                result
            }

            is SnapshotStoreResult.Unchanged -> {
                val snapshot = storeResult.snapshot
                val result = SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.DUPLICATE,
                    sourceReason = sourceReason,
                    reasonCode = SnapshotCaptureStatus.DUPLICATE.wireValue,
                    sessionId = snapshot.sessionId,
                    slotId = snapshot.slotId,
                    shoppingPackage = snapshot.shoppingPackage,
                    pageFingerprint = snapshot.pageFingerprint,
                    snapshotId = snapshot.snapshotId,
                    candidateRevision = snapshot.candidateRevision
                )
                snapshot.release()
                result
            }

            is SnapshotStoreResult.Rejected -> when (storeResult.reason) {
                SnapshotStoreRejection.MANUAL_CANDIDATE_PROTECTED ->
                    protectedResult(current, sourceReason)
                else -> SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.REJECTED,
                    sourceReason = sourceReason,
                    reasonCode = storeResult.reason.wireValue,
                    sessionId = current.sessionId,
                    slotId = current.slotId,
                    shoppingPackage = current.page.observation.shoppingPackage,
                    pageFingerprint = current.page.observation.pageFingerprint
                )
            }
        }
    }

    private fun drainQueuedCapture() {
        if (closed || pendingCapture != null) {
            return
        }

        queuedManual?.let { queued ->
            queuedManual = null
            val current = activeCheckout
            if (current == null || current.key != queued.key) {
                publishQueuedResult(
                    queued = queued,
                    result = cancelledResult(
                        current = current,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                        key = queued.key
                    )
                )
            } else {
                beginCapture(
                    current = current,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    callback = queued.callback,
                    completionPublished = queued.completionPublished,
                    queued = queued
                )
            }
            return
        }

        val queued = queuedAutomatic ?: return
        queuedAutomatic = null
        val current = activeCheckout
        if (current == null || current.key != queued.key || queued.key in attemptedAutomaticCaptures) {
            return
        }
        if (buffer.hasCandidate(
                slotId = current.slotId,
                sessionId = current.sessionId,
                pageFingerprint = current.page.observation.pageFingerprint,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE
            )
        ) {
            attemptedAutomaticCaptures += current.key
            publish(protectedResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }
        attemptedAutomaticCaptures += current.key
        beginCapture(current, SnapshotSourceReason.CHECKOUT_STABLE, callback = null)
    }

    private fun screenshotUnavailableReason(): SnapshotCaptureUnavailableReason? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SnapshotCaptureUnavailableReason.API_UNSUPPORTED
        }
        val capabilities = try {
            accessibilityService.serviceInfo?.capabilities ?: 0
        } catch (_: RuntimeException) {
            0
        }
        return if (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
            SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE
        } else {
            null
        }
    }

    private data class EncodedScreenshot(
        val bytes: ByteArray?,
        val reasonCode: String?
    )

    /** 把硬件截图缩放并压缩到受限 JPEG 后，立即释放系统资源。 */
    private fun encodeScreenshot(screenshot: ScreenshotResult): EncodedScreenshot {
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var compressedBytes: ByteArray? = null
        var transferred = false
        val output = BoundedByteArrayOutputStream(MAX_ENCODED_SCREENSHOT_BYTES)

        try {
            val capturedBuffer = screenshot.hardwareBuffer
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            hardwareBuffer = capturedBuffer
            val capturedHardwareBitmap = Bitmap.wrapHardwareBuffer(capturedBuffer, screenshot.colorSpace)
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            hardwareBitmap = capturedHardwareBitmap
            val capturedSoftwareBitmap = capturedHardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            softwareBitmap = capturedSoftwareBitmap
            val longestEdge = maxOf(capturedSoftwareBitmap.width, capturedSoftwareBitmap.height)
            val bitmapToEncode = if (longestEdge > MAX_SCREENSHOT_LONG_EDGE) {
                val scale = MAX_SCREENSHOT_LONG_EDGE.toFloat() / longestEdge.toFloat()
                val targetWidth = (capturedSoftwareBitmap.width * scale)
                    .roundToInt()
                    .coerceAtLeast(1)
                val targetHeight = (capturedSoftwareBitmap.height * scale)
                    .roundToInt()
                    .coerceAtLeast(1)
                Bitmap.createScaledBitmap(
                    capturedSoftwareBitmap,
                    targetWidth,
                    targetHeight,
                    true
                ).also { scaledBitmap = it }
            } else {
                capturedSoftwareBitmap
            }
            if (!bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            }
            val encodedBytes = output.toByteArray()
            compressedBytes = encodedBytes
            if (encodedBytes.isEmpty() || encodedBytes.size > MAX_ENCODED_SCREENSHOT_BYTES) {
                return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            }
            transferred = true
            return EncodedScreenshot(encodedBytes, null)
        } catch (_: RuntimeException) {
            return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
        } finally {
            if (!transferred) {
                compressedBytes?.fill(0)
            }
            output.clear()
            try {
                scaledBitmap?.recycle()
            } finally {
                try {
                    softwareBitmap?.recycle()
                } finally {
                    try {
                        hardwareBitmap?.recycle()
                    } finally {
                        hardwareBuffer?.close()
                    }
                }
            }
        }
    }

    /** 限制压缩输出不超过 5MB，避免超限图片在内存中完整暂存。 */
    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int
    ) : ByteArrayOutputStream(32 * 1024) {
        fun clear() {
            buf.fill(0)
            reset()
        }

        override fun write(b: Int) {
            if (size() >= maxBytes) {
                throw ScreenshotTooLargeException()
            }
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > maxBytes - size()) {
                throw ScreenshotTooLargeException()
            }
            super.write(b, off, len)
        }
    }

    private class ScreenshotTooLargeException : RuntimeException()

    private fun screenshotFailureReason(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            SnapshotCaptureUnavailableReason.SCREENSHOT_SECURE.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            SnapshotCaptureUnavailableReason.SCREENSHOT_BUSY.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            SnapshotCaptureUnavailableReason.INVALID_DISPLAY.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
        else -> SCREENSHOT_ERROR_PREFIX + errorCode
    }

    private fun slotIdFor(shoppingPackage: String): String = checkoutSnapshotSlotId(shoppingPackage)

    private fun protectedResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.PROTECTED,
        sourceReason = sourceReason,
        reasonCode = SnapshotStoreRejection.MANUAL_CANDIDATE_PROTECTED.wireValue,
        sessionId = current.sessionId,
        slotId = current.slotId,
        shoppingPackage = current.page.observation.shoppingPackage,
        pageFingerprint = current.page.observation.pageFingerprint
    )

    private fun duplicateResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.DUPLICATE,
        sourceReason = sourceReason,
        reasonCode = SnapshotCaptureStatus.DUPLICATE.wireValue,
        sessionId = current.sessionId,
        slotId = current.slotId,
        shoppingPackage = current.page.observation.shoppingPackage,
        pageFingerprint = current.page.observation.pageFingerprint
    )

    private fun rejectedResult(
        current: ActiveCheckout? = null,
        sourceReason: SnapshotSourceReason,
        reason: SnapshotCaptureRejection
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.REJECTED,
        sourceReason = sourceReason,
        reasonCode = reason.wireValue,
        sessionId = current?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint
    )

    private fun unavailableResult(
        current: ActiveCheckout? = null,
        sourceReason: SnapshotSourceReason,
        reasonCode: String
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.UNAVAILABLE,
        sourceReason = sourceReason,
        reasonCode = reasonCode,
        sessionId = current?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint
    )

    private fun failedResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        reason: String
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.FAILED,
        sourceReason = sourceReason,
        reasonCode = reason,
        sessionId = current.sessionId,
        slotId = current.slotId,
        shoppingPackage = current.page.observation.shoppingPackage,
        pageFingerprint = current.page.observation.pageFingerprint
    )

    private fun cancelledResult(
        current: ActiveCheckout?,
        sourceReason: SnapshotSourceReason,
        reason: String,
        key: CaptureKey? = null
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.CANCELLED,
        sourceReason = sourceReason,
        reasonCode = reason,
        sessionId = current?.sessionId ?: key?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint ?: key?.pageFingerprint
    )

    private fun publish(
        result: SnapshotCaptureResult,
        callback: SnapshotCaptureCallback? = null
    ) {
        Log.i(TAG, "截图状态: ${result.status.wireValue}, 来源=${result.sourceReason.wireValue}, 原因=${result.reasonCode}")
        try {
            resultListener?.onResult(result)
        } catch (error: RuntimeException) {
            Log.w(TAG, "截图结果监听器处理失败", error)
        }
        if (callback != null) {
            try {
                callback.onResult(result)
            } catch (error: RuntimeException) {
                Log.w(TAG, "截图回调处理失败", error)
            }
        }
    }
}
