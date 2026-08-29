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
 * CheckoutSnapshotCaptureCoordinator 事件流特征测试(批 4 S5b 旅程语义)。
 *
 * 用真实 buffer(注入时钟/调度器/日志 sink)+ Robolectric 影子主线程驱动事件流,
 * 钉死旅程语义下的会话与槽位机制(S5b 有意行为变化见规格 §2a/§8):
 * - 指纹漂移不再拆会话:同旅程内无候选则补截、有候选则去重;
 * - 交接挂起期漂移噪声(尾窗内)忽略;尾窗外同旅程返回 = 恢复会话;
 * - 跨包返回丢弃挂起槽位(HANDOFF_RETURNED_TO_OTHER_CHECKOUT);
 * - D2 停滞 10 分钟 / D1 交接 8 分钟惰性死亡,JOURNEY_TIMEOUT 丢弃。
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
        const val PKG_2 = "com.taobao.taobao"
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
        const val TTL = 120_000L
        const val START_NOW = 1_000_000L
        /** 与协调器 HANDOFF_DRIFT_TAIL_WINDOW_MILLIS 一致(private,测试自带副本)。 */
        const val HANDOFF_DRIFT_TAIL_WINDOW_MILLIS = 2_500L
        /** 与 JourneyLifecyclePolicy DEFAULT_CHECKOUT_STALL_TIMEOUT_MILLIS 一致。 */
        const val CHECKOUT_STALL_TIMEOUT_MILLIS = 10 * 60_000L
        /** 与 JourneyLifecyclePolicy DEFAULT_WAITING_PAYMENT_TIMEOUT_MILLIS 一致。 */
        const val WAITING_PAYMENT_TIMEOUT_MILLIS = 8 * 60_000L
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

    private fun observation(
        fingerprint: String,
        pkg: String = PKG
    ) = CheckoutPageObservation(
        shoppingPackage = pkg,
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

    private fun driveStable(fingerprint: String, pkg: String = PKG) {
        clock.now += 100L
        drive(
            CheckoutObservationEvent.Stable(
                StableCheckoutPage(observation(fingerprint, pkg), stableAt = clock.now)
            )
        )
    }

    private fun driveHandoffInvalidation(fingerprint: String, pkg: String = PKG) {
        clock.now += 100L
        drive(
            CheckoutObservationEvent.Invalidated(
                CheckoutObservationInvalidation(
                    reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                    shoppingPackage = pkg,
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

    // ---------- 基本会话机制 ----------

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

    // ---------- 旅程语义:指纹漂移不拆会话 ----------

    @Test
    fun fingerprintDriftWithoutCandidateRecapturesWithinSameJourney() {
        driveStable(FP_A)
        driveStable(FP_B)
        assertEquals(2, results.size)
        val first = results[0]
        val second = results[1]
        // 【S5b 有意变化】漂移不再拆会话:补截新指纹,但会话/旅程延续
        assertEquals("同一旅程延续", first.sessionId, second.sessionId)
        assertEquals(FP_B, second.pageFingerprint)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
    }

    @Test
    fun driftWithLiveCandidatePublishesDuplicateWithinSameJourney() {
        driveStable(FP_A)
        val sessionId = results[0].sessionId!!
        // 手动向 buffer 注入该会话的存活候选(模拟截图成功落盘)
        buffer.store(
            SnapshotCaptureRequest(
                slotId = SLOT,
                sessionId = sessionId,
                shoppingPackage = PKG,
                pageRole = CheckoutPageRole.CHECKOUT,
                pageFingerprint = FP_A,
                sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
                imageBytes = ByteArray(8) { 3 },
                isSensitivePage = false
            )
        )
        driveStable(FP_B)
        assertEquals(2, results.size)
        val duplicate = results[1]
        assertEquals("有存活候选的漂移去重,不重复截图", sessionId, duplicate.sessionId)
        assertEquals(FP_B, duplicate.pageFingerprint)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
    }

    // ---------- 交接挂起与旅程返回 ----------

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
    fun driftNoiseWithinTailDuringHandoffIsIgnored() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        // 尾窗内(100ms < 2.5s)的同旅程指纹漂移:等待支付,忽略
        driveStable(FP_B)
        assertEquals("尾窗内漂移噪声不触发任何动作", 1, results.size)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
        assertEquals(0, discardLogs("HANDOFF_RETURNED_TO_OTHER_CHECKOUT").size)
    }

    @Test
    fun handoffThenSameJourneyDifferentFingerprintAfterTailRestores() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        // 跳过尾窗:支付失败返回结算页并改动内容(指纹漂移)→ 旅程恢复并补截
        clock.now += HANDOFF_DRIFT_TAIL_WINDOW_MILLIS + 1
        driveStable(FP_B)
        assertEquals(2, results.size)
        assertEquals("同旅程恢复,会话延续", results[0].sessionId, results[1].sessionId)
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
        assertEquals(0, discardLogs("HANDOFF_RETURNED_TO_OTHER_CHECKOUT").size)
    }

    @Test
    fun journeyRestoreAfterConsumptionClearsTombstoneAndAllowsRecapture() {
        driveStable(FP_A)
        val sessionId = results[0].sessionId!!
        buffer.store(
            SnapshotCaptureRequest(
                slotId = SLOT,
                sessionId = sessionId,
                shoppingPackage = PKG,
                pageRole = CheckoutPageRole.CHECKOUT,
                pageFingerprint = FP_A,
                sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
                imageBytes = ByteArray(8) { 3 },
                isSensitivePage = false
            )
        )
        // 第一次交接消费快照(AI 派发语义),会话带上消费墓碑
        assertTrue(
            buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is CheckoutAiTriggerResult.Ready
        )
        driveHandoffInvalidation(FP_A)
        // 支付失败返回结算页并改动内容:旅程恢复并对新指纹发起补截
        // (0.0.76 真机 16:23:14:墓碑未清导致 JOURNEY_ALREADY_TRIGGERED)
        clock.now += HANDOFF_DRIFT_TAIL_WINDOW_MILLIS + 1
        driveStable(FP_B)
        // 影子环境截图必然失败(能力不可用),流程层验证到"协调器发起了
        // 同会话新指纹补截";墓碑清解除的成功入库由 buffer 级单测覆盖
        assertEquals(2, results.size)
        assertEquals("同旅程恢复,会话延续", sessionId, results[1].sessionId)
        assertEquals(FP_B, results[1].pageFingerprint)
    }

    @Test
    fun handoffThenCrossPackageCheckoutDiscardsRetainedSlot() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        // 跳过尾窗;跨包 = 锚点不同 = 旧旅程终结
        clock.now += HANDOFF_DRIFT_TAIL_WINDOW_MILLIS + 1
        driveStable(FP_B, pkg = PKG_2)
        assertEquals(
            1,
            discardLogs("HANDOFF_RETURNED_TO_OTHER_CHECKOUT").size
        )
        assertEquals(0, discardLogs("NEW_CHECKOUT_SESSION").size)
        assertEquals(2, results.size)
        assertNotEquals(results[0].sessionId, results[1].sessionId)
    }

    // ---------- 惰性死亡(D1/D2) ----------

    @Test
    fun checkoutStallTimeoutStartsFreshJourney() {
        driveStable(FP_A)
        clock.now += CHECKOUT_STALL_TIMEOUT_MILLIS + 1
        driveStable(FP_B)
        assertEquals(1, discardLogs("JOURNEY_TIMEOUT").size)
        assertEquals(2, results.size)
        assertNotEquals("停滞超时后开新旅程", results[0].sessionId, results[1].sessionId)
    }

    @Test
    fun waitingPaymentTimeoutDuringHandoffStartsFreshJourney() {
        driveStable(FP_A)
        driveHandoffInvalidation(FP_A)
        clock.now += WAITING_PAYMENT_TIMEOUT_MILLIS + 1
        driveStable(FP_A)
        assertEquals(1, discardLogs("JOURNEY_TIMEOUT").size)
        assertEquals(2, results.size)
        assertNotEquals("交接超时后开新旅程", results[0].sessionId, results[1].sessionId)
    }

    @Test
    fun journeyProgressWithinTimeoutDoesNotDie() {
        driveStable(FP_A)
        // 预留 driveStable 内部的 +100ms 推进,避免测试时钟恰好跨过边界
        clock.now += CHECKOUT_STALL_TIMEOUT_MILLIS - 1_000L
        driveStable(FP_A)
        // 推进刷新计时:不死亡、不丢弃
        assertEquals(0, discardLogs("JOURNEY_TIMEOUT").size)
        assertEquals(1, results.size)
    }
}
