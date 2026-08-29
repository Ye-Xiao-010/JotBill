package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CheckoutSnapshotCaptureCoordinator 事件流特征测试(批 4 S5 前置安全网)。
 *
 * handleStable/handleInvalidated 是批 4 journey 重写的直接对象,但此前只有
 * 纯谓词被测试过。本测试用真实 buffer(注入时钟/调度器/日志 sink)+ Robolectric
 * 影子主线程驱动事件流,钉死会话与槽位机制:
 * - 同指纹二次稳定不重复截图;
 * - 指纹漂移且无候选时新会话 + 丢弃旧槽位(NEW_CHECKOUT_SESSION);
 * - 支付交接后返回原页恢复原会话(不丢弃、不重截)。
 *
 * 截图在影子环境下走 beginCapture 的优雅失败路径(发布 UNAVAILABLE/FAILED),
 * 会话与槽位机制不受影响——这正是被测对象。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckoutSnapshotCaptureCoordinatorFlowTest {

    private companion object {
        const val SLOT = "shopping-package:com.xunmeng.pinduoduo"
        const val PKG = "com.xunmeng.pinduoduo"
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
        const val TTL = 120_000L
        const val START_NOW = 1_000_000L
        /** 与协调器 HANDOFF_DRIFT_TAIL_WINDOW_MILLIS 一致(private,测试自带副本)。 */
        const val HANDOFF_DRIFT_TAIL_WINDOW_MILLIS = 2_500L
    }

    private class MutableClock(var now: Long = START_NOW)

    private class RecordingScheduler : SnapshotExpirationScheduler {
        override fun postDelayed(runnable: Runnable, delayMillis: Long) = Unit
        override fun cancel(runnable: Runnable) = Unit
    }

    private class RecordingLogSink : SnapshotBufferLogSink {
        val lines = mutableListOf<String>()
        override fun i(tag: String, message: String) {
            lines.add("I: $message")
        }
        override fun w(tag: String, message: String) {
            lines.add("W: $message")
        }
    }

    private lateinit var clock: MutableClock
    private lateinit var logSink: RecordingLogSink
    private lateinit var buffer: CheckoutSnapshotBuffer
    private lateinit var coordinator: CheckoutSnapshotCaptureCoordinator
    private val results = mutableListOf<SnapshotCaptureResult>()
    private val driverExecutor = Executors.newSingleThreadExecutor()

    private var snapshotCounter = 0
    private var sessionCounter = 0

    @Before
    fun setUp() {
        clock = MutableClock()
        logSink = RecordingLogSink()
        buffer = CheckoutSnapshotBuffer(
            snapshotTtlMillis = TTL,
            nowMillis = { clock.now },
            snapshotIdFactory = { "snap-${++snapshotCounter}" },
            expirationScheduler = RecordingScheduler(),
            logSink = logSink
        )
        coordinator = CheckoutSnapshotCaptureCoordinator(
            accessibilityService = object : AccessibilityService() {
                override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
                override fun onInterrupt() = Unit
            },
            buffer = buffer,
            nowMillis = { clock.now },
            sessionIdFactory = { "session-${++sessionCounter}" }
        )
        coordinator.setResultListener { results.add(it) }
    }

    private fun observation(fingerprint: String) = CheckoutPageObservation(
        shoppingPackage = PKG,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        hasFinalAmountSignal = true,
        hasCheckoutActionSignal = true,
        isSensitivePage = false,
        observedAt = clock.now
    )

    private fun drive(event: CheckoutObservationEvent) {
        val done = CountDownLatch(1)
        driverExecutor.execute {
            try {
                coordinator.onEvent(event)
            } catch (_: RuntimeException) {
                // 事件处理失败(影子环境下的派发失败)不阻断断言:状态机制另行观测
            } finally {
                done.countDown()
            }
        }
        // 等待事件被投递到影子主线程,循环 idle 执行;同线程不能直接 await 死等
        var waited = 0L
        while (!done.await(50, TimeUnit.MILLISECONDS) && waited < 5_000L) {
            shadowOf(Looper.getMainLooper()).idle()
            waited += 50
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("事件未在 5 秒内处理完成", done.await(1, TimeUnit.SECONDS))
    }

    private fun driveStable(fingerprint: String) {
        clock.now += 100L
        drive(
            CheckoutObservationEvent.Stable(
                StableCheckoutPage(observation(fingerprint), stableAt = clock.now)
            )
        )
    }

    private fun driveHandoffInvalidation(fingerprint: String) {
        clock.now += 100L
        drive(
            CheckoutObservationEvent.Invalidated(
                CheckoutObservationInvalidation(
                    reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                    shoppingPackage = PKG,
                    pageFingerprint = fingerprint,
                    invalidatedAt = clock.now,
                    foregroundPackage = "com.tencent.mm"
                )
            )
        )
    }

    private fun discardLogs(reason: String) =
        // 命中真实槽位与空槽位走不同日志变体,统一按 reason 匹配
        logSink.lines.filter { it.contains("槽位丢弃") && it.contains("reason=$reason") }

    @Test
    fun stableEventAttemptsCaptureWithSessionAndSlot() {
        driveStable(FP_A)
        assertEquals(1, results.size)
        val result = results.single()
        assertTrue("应携带会话", !result.sessionId.isNullOrBlank())
        assertEquals(SLOT, result.slotId)
        assertEquals(FP_A, result.pageFingerprint)
    }

    @Test
    fun sameFingerprintRestableDoesNotReattemptCapture() {
        driveStable(FP_A)
        driveStable(FP_A)
        assertEquals("同指纹已尝试过,不重复截图", 1, results.size)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
    }

    @Test
    fun fingerprintDriftWithoutCandidateStartsNewSessionAndDiscardsOldSlot() {
        driveStable(FP_A)
        driveStable(FP_B)
        assertEquals(2, results.size)
        val first = results[0]
        val second = results[1]
        assertNotEquals("漂移且无候选 = 新会话", first.sessionId, second.sessionId)
        assertEquals(FP_B, second.pageFingerprint)
        assertEquals(1, discardLogs("NEW_CHECKOUT_SESSION").size)
    }

    @Test
    fun handoffThenReturnRestoresSessionWithoutDiscardOrRecapture() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        driveStable(FP_A)
        // 返回原页:恢复原会话,不丢槽位、不重截
        assertEquals("返回原页不应触发新截图", 1, results.size)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
    }

    @Test
    fun handoffThenDifferentCheckoutDiscardsRetainedSlot() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        // 跳过交接漂移尾窗(2.5 秒),否则迟到稳定事件被视为旧订单尾波直接忽略
        clock.now += HANDOFF_DRIFT_TAIL_WINDOW_MILLIS + 1
        driveStable(FP_B)
        // 交接挂起的会话被另一页面终结:走 HANDOFF_RETURNED_TO_OTHER_CHECKOUT 丢弃,
        // activeCheckout 已在交接时让位,不会再有 NEW_CHECKOUT_SESSION 丢弃
        assertEquals(
            1,
            discardLogs("HANDOFF_RETURNED_TO_OTHER_CHECKOUT").size
        )
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
        assertEquals(2, results.size)
    }
}
