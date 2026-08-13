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
import java.util.concurrent.Executor
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
    }

    private data class CaptureKey(
        val sessionId: String,
        val pageFingerprint: String
    )

    private data class ActiveCheckout(
        val sessionId: String,
        val slotId: String,
        val page: StableCheckoutPage
    ) {
        val key: CaptureKey
            get() = CaptureKey(sessionId, page.observation.pageFingerprint)
    }

    private data class PendingCapture(
        val requestId: String,
        val activeCheckout: ActiveCheckout,
        val sourceReason: SnapshotSourceReason,
        val callback: SnapshotCaptureCallback?,
        var cancelled: Boolean = false
    )

    private data class HandoffCheckout(
        val activeCheckout: ActiveCheckout,
        val attemptedAutomaticCaptures: Set<CaptureKey>
    )

    private data class QueuedManualCapture(
        val key: CaptureKey,
        val callback: SnapshotCaptureCallback
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotThread = HandlerThread("CheckoutScreenshot").apply { start() }
    private val screenshotHandler = Handler(screenshotThread.looper)
    private val callbackExecutor = Executor { runnable -> screenshotHandler.post(runnable) }
    private val pageDetector = CheckoutPageDetector()
    private val attemptedAutomaticCaptures = mutableSetOf<CaptureKey>()

    private var activeCheckout: ActiveCheckout? = null
    private var pendingCapture: PendingCapture? = null
    private var queuedAutomatic: ActiveCheckout? = null
    private var queuedManual: QueuedManualCapture? = null
    private var handoffCheckout: HandoffCheckout? = null
    private var resultListener: SnapshotCaptureResultListener? = null
    private var closed = false

    override fun onEvent(event: CheckoutObservationEvent) {
        // 服务回调和桥接调用都归并到主线程，避免页面状态与截图回调交错写入。
        when (event) {
            is CheckoutObservationEvent.Invalidated -> {
                if (event.detail.reason == CheckoutInvalidationReason.PAYMENT_APP_HANDOFF) {
                    // 购买意图协调器随后会同步处理同一事件，必须先建立旧会话的消费栅栏。
                    preparePaymentHandoff()
                }
            }

            is CheckoutObservationEvent.Stable -> prepareStableTransition(event.page)
        }
        mainHandler.post {
            if (closed) {
                return@post
            }
            when (event) {
                is CheckoutObservationEvent.Stable -> handleStable(event.page)
                is CheckoutObservationEvent.Invalidated -> handleInvalidated(event.detail)
            }
        }
    }

    /** 设置自动截图结果监听器；监听器不拥有图片引用。 */
    fun setResultListener(listener: SnapshotCaptureResultListener?) {
        mainHandler.post {
            resultListener = listener
        }
    }

    /** 用户点击入口；手动截图不等待新的稳定窗口，但仍必须经过结算页门禁。 */
    fun requestManualCapture(callback: SnapshotCaptureCallback) {
        mainHandler.post {
            if (closed) {
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
                    queuedManual = QueuedManualCapture(current.key, callback)
                }
                return@post
            }

            beginCapture(current, SnapshotSourceReason.MANUAL_CAPTURE, callback)
        }
    }

    /** 服务中断时立即取消回调并清理当前购物包槽位。 */
    fun onServiceInterrupted() {
        mainHandler.post {
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
        }
    }

    /** 服务销毁时关闭协调器并释放所有正在等待的图片。 */
    fun close() {
        mainHandler.post {
            if (closed) {
                return@post
            }
            handleInvalidated(
                CheckoutObservationInvalidation(
                    reason = CheckoutInvalidationReason.SERVICE_DESTROYED,
                    shoppingPackage = activeCheckout?.page?.observation?.shoppingPackage,
                    pageFingerprint = activeCheckout?.page?.observation?.pageFingerprint,
                    invalidatedAt = nowMillis()
            )
            )
            // 销毁时即使此前发生过支付交接，也必须停止过期回调并释放保留快照。
            buffer.close()
            closed = true
            resultListener = null
            screenshotThread.quitSafely()
        }
    }

    private fun handleStable(page: StableCheckoutPage) {
        val observation = page.observation
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
            val retained = retainedHandoff.activeCheckout
            val isSameCheckout =
                retained.page.observation.shoppingPackage == observation.shoppingPackage &&
                    retained.page.observation.pageFingerprint == observation.pageFingerprint
            handoffCheckout = null
            if (isSameCheckout) {
                // 支付应用返回原结算页时恢复原会话和尝试状态，不重新截图或覆盖原候选。
                buffer.restoreFromPaymentHandoff(
                    slotId = retained.slotId,
                    sessionId = retained.sessionId,
                    pageFingerprint = observation.pageFingerprint
                )
                activeCheckout = retained.copy(page = page)
                attemptedAutomaticCaptures.clear()
                attemptedAutomaticCaptures += retainedHandoff.attemptedAutomaticCaptures
                return
            }

            // 返回到不同页面时，旧交接会话和其槽位不能被新订单复用。
            buffer.discard(retained.slotId)
            attemptedAutomaticCaptures.clear()
        }

        val previous = activeCheckout
        val startsNewSession = previous == null ||
            previous.page.observation.shoppingPackage != observation.shoppingPackage ||
            previous.page.observation.pageFingerprint != observation.pageFingerprint
        val current = if (startsNewSession) {
            cancelPendingCapture(SnapshotCaptureRejection.PAGE_CHANGED.wireValue)
            queuedManual?.let { queued ->
                queuedManual = null
                publish(
                    cancelledResult(
                        current = previous,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                        key = queued.key
                    ),
                    queued.callback
                )
            }
            queuedAutomatic = null
            previous?.let { oldCheckout ->
                // 新指纹代表新的订单边界；先释放旧槽位，避免旧图片或消费墓碑阻塞新会话。
                buffer.discard(oldCheckout.slotId)
                attemptedAutomaticCaptures.removeAll { it.sessionId == oldCheckout.sessionId }
            }
            ActiveCheckout(
                sessionId = sessionIdFactory(),
                slotId = slotIdFor(observation.shoppingPackage),
                page = page
            )
        } else {
            previous.copy(page = page)
        }
        activeCheckout = current

        val key = current.key
        if (key in attemptedAutomaticCaptures) {
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
        val handoffSource = current ?: retained?.activeCheckout
        val retainForHandoff =
            detail.reason == CheckoutInvalidationReason.PAYMENT_APP_HANDOFF && handoffSource != null
        handoffCheckout = if (!retainForHandoff) {
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

        cancelPendingCapture(detail.reason.wireValue)

        queuedManual?.let { queued ->
            queuedManual = null
            publish(
                cancelledResult(
                    current = current,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = detail.reason.wireValue,
                    key = queued.key
                ),
                queued.callback
            )
        }
        queuedAutomatic = null
        // 只有明确的支付交接保留快照；普通离开和其他失效原因都清理槽位，避免旧图误用。
        if (!retainForHandoff) {
            buffer.clear()
        } else {
            handoffSource?.let { retainedCheckout ->
                buffer.retainForPaymentHandoff(
                    slotId = retainedCheckout.slotId,
                    sessionId = retainedCheckout.sessionId,
                    pageFingerprint = retainedCheckout.page.observation.pageFingerprint
                )
            }
        }
        activeCheckout = null
        attemptedAutomaticCaptures.clear()
    }

    private fun cancelPendingCapture(reason: String) {
        val pending = detachPendingCapture() ?: return
        // 先解除当前请求，再通知回调；晚到的系统回调只能命中释放分支。
        pendingCapture = null
        pending.cancelled = true
        publish(
            cancelledResult(
                current = pending.activeCheckout,
                sourceReason = pending.sourceReason,
                reason = reason
            ),
            pending.callback
        )
    }

    private fun detachPendingCapture(): PendingCapture? {
        val pending = pendingCapture ?: return null
        // 先解除当前请求，晚到的系统回调只能走释放路径。
        pendingCapture = null
        pending.cancelled = true
        return pending
    }

    private fun beginCapture(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        callback: SnapshotCaptureCallback?
    ) {
        val unavailableReason = screenshotUnavailableReason()
        if (unavailableReason != null) {
            publish(
                unavailableResult(
                    current = current,
                    sourceReason = sourceReason,
                    reasonCode = unavailableReason.wireValue
                ),
                callback
            )
            drainQueuedCapture()
            return
        }

        val pending = PendingCapture(
            requestId = UUID.randomUUID().toString(),
            activeCheckout = current,
            sourceReason = sourceReason,
            callback = callback
        )
        pendingCapture = pending

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
                        mainHandler.post {
                            finishCapture(
                                pending = pending,
                                imageBytes = null,
                                failureReason = screenshotFailureReason(errorCode)
                            )
                        }
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishCapture(pending, imageBytes, failureReason) }
            return
        }

        if (pendingCapture?.requestId != pending.requestId) {
            imageBytes?.fill(0)
            return
        }
        pendingCapture = null

        if (pending.cancelled || closed || activeCheckout?.key != pending.activeCheckout.key) {
            imageBytes?.fill(0)
            publish(
                cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                ),
                pending.callback
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
            publish(result, pending.callback)
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
            isSensitivePage = pending.activeCheckout.page.observation.isSensitivePage
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
        publish(result, pending.callback)
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
                publish(
                    cancelledResult(
                        current = current,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                        key = queued.key
                    ),
                    queued.callback
                )
            } else {
                beginCapture(current, SnapshotSourceReason.MANUAL_CAPTURE, queued.callback)
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

    private fun preparePaymentHandoff() {
        if (closed) {
            return
        }
        val retainedCheckout = activeCheckout ?: handoffCheckout?.activeCheckout ?: return
        buffer.retainForPaymentHandoff(
            slotId = retainedCheckout.slotId,
            sessionId = retainedCheckout.sessionId,
            pageFingerprint = retainedCheckout.page.observation.pageFingerprint
        )
    }

    private fun prepareStableTransition(page: StableCheckoutPage) {
        if (closed) {
            return
        }

        val retainedCheckout = handoffCheckout?.activeCheckout
        val current = retainedCheckout ?: activeCheckout ?: return
        val observation = page.observation
        val sameCheckout =
            pageDetector.detect(observation) is CheckoutPageDetection.Accepted &&
                current.page.observation.shoppingPackage == observation.shoppingPackage &&
                current.page.observation.pageFingerprint == observation.pageFingerprint
        if (sameCheckout) {
            if (retainedCheckout != null) {
                buffer.restoreFromPaymentHandoff(
                    slotId = current.slotId,
                    sessionId = current.sessionId,
                    pageFingerprint = observation.pageFingerprint
                )
            }
            return
        }

        // Stable 事件交给主线程处理前，先阻断购买意图协调器的无指纹消费窗口。
        val detachedPending = detachPendingCapture()
        val detachedManual = queuedManual
        queuedManual = null
        queuedAutomatic = null
        buffer.discard(current.slotId)
        if (detachedPending != null || detachedManual != null) {
            mainHandler.post {
                detachedPending?.let { pending ->
                    publish(
                        cancelledResult(
                            current = pending.activeCheckout,
                            sourceReason = pending.sourceReason,
                            reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                        ),
                        pending.callback
                    )
                }
                detachedManual?.let { queued ->
                    publish(
                        cancelledResult(
                            current = current,
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                            key = queued.key
                        ),
                        queued.callback
                    )
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
