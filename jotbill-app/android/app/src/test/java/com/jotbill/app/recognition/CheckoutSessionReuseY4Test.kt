package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 批 V-2 改造 C（Y4）会话沿用判据的**协调器路径**判别性用例。
 *
 * ## 观测量为什么是"会话 id 是否被替换"
 *
 * 沿用的定义就是"沿用旧会话与槽位"（`driftWithinSameCheckout` 分支不调
 * `sessionIdFactory()` 且不 discard 旧槽）。因此 `SnapshotCaptureResult.sessionId`
 * 的身份变化**就是**判据的可观测结果，比日志或间接状态更硬。
 *
 * ## 四条判据（方向相反的必须各有一条）
 *
 * | 用例 | 场景 | 期望 |
 * |---|---|---|
 * | `fingerprintDriftWithUnchangedAmountReusesSession` | **R1/C-3 回归**：指纹变、金额不变 | **沿用** |
 * | `amountChangeWithinSamePackageStartsNewSession` | **判据 1**：金额变 | **新建** |
 * | `sessionOlderThanReuseLimitStartsNewSession` | **判据 2**：超龄 | **新建** |
 * | `unjudgeableAmountReusesSession` | **R3**：一侧金额无法判定 | **沿用** |
 *
 * ⚠️ JVM 边界：本用例驱动真实 `CheckoutSnapshotCaptureCoordinator`，但
 * `AccessibilityService` 无真实截图能力 ⇒ 结果止于 `CAPABILITY_UNAVAILABLE`；
 * 这不影响会话身份断言（会话在截图之前就已决定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckoutSessionReuseY4Test {

    private companion object {
        const val PACKAGE = "com.jingdong.app.mall"
        const val SLOT = "shopping-package:com.jingdong.app.mall"
        const val NOW = 1_000_000L
        const val TTL = 300_000L
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)

        /** ¥128.00 —— 恰好覆盖 P2-8 的"≥3 位数字"形态。 */
        const val AMOUNT = 12_800L

        /** ¥150.00 —— 金额判据里的"另一个订单"。 */
        const val OTHER_AMOUNT = 15_000L
    }

    private class TestAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

        override fun onInterrupt() = Unit
    }

    private class NoopScheduler : SnapshotExpirationScheduler {
        override fun postDelayed(runnable: Runnable, delayMillis: Long) = Unit

        override fun cancel(runnable: Runnable) = Unit
    }

    private class SilentLogSink : SnapshotBufferLogSink {
        override fun i(tag: String, message: String) = Unit

        override fun w(tag: String, message: String) = Unit
    }

    private var now = NOW
    private var snapshotCounter = 0
    private var sessionCounter = 0

    private fun buffer() = CheckoutSnapshotBuffer(
        snapshotTtlMillis = TTL,
        nowMillis = { now },
        snapshotIdFactory = { "snap-${snapshotCounter++}" },
        expirationScheduler = NoopScheduler(),
        logSink = SilentLogSink()
    )

    private fun observation(fingerprint: String, amountMinor: Long?) = CheckoutPageObservation(
        shoppingPackage = PACKAGE,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        hasFinalAmountSignal = true,
        hasCheckoutActionSignal = true,
        isSensitivePage = false,
        hasCheckoutClassSemantic = true,
        observedAt = now,
        finalAmountMinor = amountMinor
    )

    private fun request(fingerprint: String, sessionId: String) = SnapshotCaptureRequest(
        slotId = SLOT,
        sessionId = sessionId,
        shoppingPackage = PACKAGE,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
        imageBytes = ByteArray(16) { 7 },
        isSensitivePage = false,
        captureTarget = SnapshotCaptureTarget.CHECKOUT
    )

    private fun drive(
        coordinator: CheckoutSnapshotCaptureCoordinator,
        fingerprint: String,
        amountMinor: Long?
    ) {
        coordinator.onEvent(
            CheckoutObservationEvent.Stable(
                StableCheckoutPage(
                    observation = observation(fingerprint, amountMinor),
                    stableAt = now
                )
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 建会话 → 在旧槽位留候选 → 用第二帧稳定页触发"沿用与否"判定。
     *
     * @return 第一帧会话 id to 第二帧会话 id
     */
    private fun secondFrameSession(
        secondFingerprint: String,
        secondAmountMinor: Long?,
        advanceMillis: Long
    ): Pair<String, String?> {
        val buffer = buffer()
        val results = mutableListOf<SnapshotCaptureResult>()
        val coordinator = CheckoutSnapshotCaptureCoordinator(
            accessibilityService = Robolectric.buildService(TestAccessibilityService::class.java).get(),
            buffer = buffer,
            nowMillis = { now },
            sessionIdFactory = { "session-${++sessionCounter}" }
        )
        try {
            coordinator.setResultListener { results += it }
            shadowOf(Looper.getMainLooper()).idle()

            drive(coordinator, FP_A, AMOUNT)
            val firstSession = results.lastOrNull()?.sessionId
            assertNotNull("前置条件：第一帧稳定页应已建立会话并发布结果", firstSession)

            // 旧槽位留未消费候选 —— 这是"不 discard"的前提（hasRetainableCandidate）。
            val stored = buffer.store(request(FP_A, firstSession!!))
            assertTrue("前置条件：候选应写入旧槽位", stored is SnapshotStoreResult.Stored)

            results.clear()
            now += advanceMillis
            drive(coordinator, secondFingerprint, secondAmountMinor)
            return firstSession to results.lastOrNull()?.sessionId
        } finally {
            coordinator.close()
        }
    }

    /**
     * **R1 / C-3 回归保护**：同包内指纹变化但**金额不变** ⇒ 沿用旧会话。
     *
     * 这条不是新功能，而是修"真机支付交接只剩 NO_BUFFERED_SNAPSHOT"引入的既有行为，
     * **去掉会复发**（已截到的图会在提交订单前被清零）。
     *
     * 判别性：把金额判据写成"不等 **或** 任一侧无法判定就不沿用" ⇒ 本用例必红。
     */
    @Test
    fun fingerprintDriftWithUnchangedAmountReusesSession() {
        val (first, second) = secondFrameSession(
            secondFingerprint = FP_B,
            secondAmountMinor = AMOUNT,
            advanceMillis = 1_000L
        )
        assertEquals("轮播漂移（指纹变、金额不变）必须沿用旧会话", first, second)
    }

    /**
     * **判据 1（金额）**：同包内金额变化 ⇒ 不沿用 ⇒ 新建会话。
     *
     * 判别性：移除金额判据（模拟改造 C 未实现）⇒ 本用例必红（第二帧会沿用第一帧会话）。
     */
    @Test
    fun amountChangeWithinSamePackageStartsNewSession() {
        val (first, second) = secondFrameSession(
            secondFingerprint = FP_B,
            secondAmountMinor = OTHER_AMOUNT,
            advanceMillis = 1_000L
        )
        assertNotEquals("金额变化 ⇒ 新订单边界 ⇒ 必须新建会话", first, second)
    }

    /**
     * **判据 2（时间上限）**：会话沿用超龄 ⇒ 新建会话（即使金额不变）。
     *
     * 判别性：移除时间判据 ⇒ 本用例必红。
     */
    @Test
    fun sessionOlderThanReuseLimitStartsNewSession() {
        val (first, second) = secondFrameSession(
            secondFingerprint = FP_B,
            secondAmountMinor = AMOUNT,
            advanceMillis = CheckoutSnapshotCaptureCoordinator.MAX_SESSION_REUSE_MILLIS + 1
        )
        assertNotEquals("沿用超龄 ⇒ 必须新建会话", first, second)
    }

    /** 判据 2 边界：恰好等于上限时**仍沿用**（严格大于才超龄）。 */
    @Test
    fun sessionExactlyAtReuseLimitStillReuses() {
        val (first, second) = secondFrameSession(
            secondFingerprint = FP_B,
            secondAmountMinor = AMOUNT,
            advanceMillis = CheckoutSnapshotCaptureCoordinator.MAX_SESSION_REUSE_MILLIS
        )
        assertEquals("恰好等于上限不算超龄", first, second)
    }

    /**
     * **R3（保守）**：任一侧金额**无法判定**（null）⇒ 沿用旧会话。
     *
     * 判别性：把"无法判定"当作"不等"处理 ⇒ 本用例必红。
     */
    @Test
    fun unjudgeableAmountReusesSession() {
        val (first, second) = secondFrameSession(
            secondFingerprint = FP_B,
            secondAmountMinor = null,
            advanceMillis = 1_000L
        )
        assertEquals("金额无法判定必须保守沿用（宁可少新建，不可误新建）", first, second)
    }

    /**
     * 回归护栏：**指纹相同**时仍沿用旧会话（与金额无关）。
     *
     * 该路径不经过 drift（指纹未变 ⇒ `startsNewSession` 为 false），须确保 Y4 判据
     * 不会把它拆开。
     *
     * ⚠️ 本体不发布截图结果：同指纹第二次稳定帧会因 `attemptedAutomaticCaptures`
     * 短路（同会话同指纹不再重复截图，`Coordinator:1608`），故这里直接观测
     * **旧槽位是否被保留** —— 新建会话会 `discard` 旧槽位（`NEW_CHECKOUT_SESSION`）。
     */
    @Test
    fun identicalFingerprintStillReusesSession() {
        val buffer = buffer()
        val results = mutableListOf<SnapshotCaptureResult>()
        val coordinator = CheckoutSnapshotCaptureCoordinator(
            accessibilityService = Robolectric.buildService(TestAccessibilityService::class.java).get(),
            buffer = buffer,
            nowMillis = { now },
            sessionIdFactory = { "session-${++sessionCounter}" }
        )
        try {
            coordinator.setResultListener { results += it }
            shadowOf(Looper.getMainLooper()).idle()
            drive(coordinator, FP_A, AMOUNT)
            val firstSession = results.lastOrNull()?.sessionId
            assertNotNull("前置条件：第一帧应已建立会话", firstSession)
            assertTrue(buffer.store(request(FP_A, firstSession!!)) is SnapshotStoreResult.Stored)

            now += 1_000L
            // 指纹相同但金额不同 —— 若判据 1 被误用到非 drift 路径，这里会拆会话
            drive(coordinator, FP_A, OTHER_AMOUNT)

            assertEquals(
                "指纹未变 ⇒ 不得拆会话、不得 discard 旧槽位",
                listOf(firstSession),
                buffer.currentCandidates(SLOT).map { it.sessionId }
            )
        } finally {
            coordinator.close()
        }
    }
}
