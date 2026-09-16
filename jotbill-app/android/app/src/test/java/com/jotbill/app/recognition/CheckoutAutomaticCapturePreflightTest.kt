package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-16 = B 的**协调器路径**判别性用例。
 *
 * ## 为什么必须存在这一组用例
 *
 * "同指纹 + 已有手动候选 ⇒ 拦自动截图"这个判断在实现里有 **3 个**位置：
 * ① `CheckoutSnapshotBuffer.store` 内层守卫（返回 `SnapshotStoreRejection.MANUAL_CANDIDATE_PROTECTED`）
 * ② `CheckoutSnapshotCaptureCoordinator.handleStable` **前置守卫**（实时自动截图）
 * ③ 同文件 `drainQueuedCapture` **前置守卫**（排队自动截图）
 *
 * ②③ 在调用 `buffer.store` **之前**就 return，**根本走不到 ①**：
 * 只改 ① 时 D-16 完全不生效，而既有 Buffer 用例直接调 buffer、**测不到 ②③ ⇒ 会假绿**。
 * 本文件驱动的正是 ②（稳定结算页事件 ⇒ `handleStable`），它才是 D-16 的主战场。
 *
 * ## 判别性
 *
 * - **改前**（保留 ② 前置守卫）：自动截图被前置拒绝，发布
 *   `PROTECTED / MANUAL_CANDIDATE_PROTECTED`，`takeScreenshot` 从未发起。
 * - **改后**（删除 ②）：自动截图照常发起，结果落到截图能力门禁
 *   （Robolectric 无真实无障碍服务 ⇒ `UNAVAILABLE / CAPABILITY_UNAVAILABLE`），
 *   **不可能**再出现 `MANUAL_CANDIDATE_PROTECTED`。
 *
 * ⚠️ 断言止于"已推进到截图能力门禁"而非 `STORED`：`STORED` 需要真实
 * `ScreenshotResult` 与其编码（H/W 缓冲），JVM 单测无法产生 ⇒ 本用例取
 * JVM 可观测的最强等价信号。真机端到端判定点见报告（logcat 不再出现
 * `MANUAL_CANDIDATE_PROTECTED` / `PROTECTED`，且随后出现 `候选已写入 ... source=CHECKOUT_STABLE`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckoutAutomaticCapturePreflightTest {

    private companion object {
        const val PACKAGE = "com.jingdong.app.mall"
        const val SLOT = "shopping-package:com.jingdong.app.mall"
        const val SESSION = "session-fixed"
        const val NOW = 1_000_000L
        const val TTL = 300_000L
        val FP = "a".repeat(64)

        /** 已随 D-16 删除的拒绝原因；保留字面量用作"不得再现"的回归护栏。 */
        const val REMOVED_PROTECTED_REASON = "MANUAL_CANDIDATE_PROTECTED"
    }

    /** 无真实无障碍能力的最小实现：不 override takeScreenshot，避免依赖框架隐藏行为。 */
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

    private fun buffer(): CheckoutSnapshotBuffer = CheckoutSnapshotBuffer(
        snapshotTtlMillis = TTL,
        nowMillis = { NOW },
        snapshotIdFactory = { "snap-${idCounter++}" },
        expirationScheduler = NoopScheduler(),
        logSink = SilentLogSink()
    )

    private var idCounter = 0

    private fun request(
        sourceReason: SnapshotSourceReason,
        target: SnapshotCaptureTarget,
        fingerprint: String = FP
    ) = SnapshotCaptureRequest(
        slotId = SLOT,
        sessionId = SESSION,
        shoppingPackage = PACKAGE,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        sourceReason = sourceReason,
        imageBytes = ByteArray(16) { 7 },
        isSensitivePage = false,
        captureTarget = target
    )

    /** 通过结算门禁的页面观察（与 CheckoutManualRetryFallbackTest 同一形态）。 */
    private fun stablePage() = StableCheckoutPage(
        observation = CheckoutPageObservation(
            shoppingPackage = PACKAGE,
            pageRole = CheckoutPageRole.CHECKOUT,
            pageFingerprint = FP,
            hasFinalAmountSignal = true,
            hasCheckoutActionSignal = true,
            isSensitivePage = false,
            hasCheckoutClassSemantic = true
        ),
        stableAt = NOW
    )

    /**
     * 驱动一次真实稳定结算页事件，返回该事件期间发布的全部截图结果。
     *
     * `onEvent` 在主线程（Robolectric 测试线程）被调用时走同步分支，因此本调用是同步的。
     */
    private fun driveStableEvent(
        buffer: CheckoutSnapshotBuffer,
        seed: (CheckoutSnapshotBuffer) -> Unit
    ): List<SnapshotCaptureResult> {
        seed(buffer)
        val results = mutableListOf<SnapshotCaptureResult>()
        val coordinator = CheckoutSnapshotCaptureCoordinator(
            accessibilityService = Robolectric.buildService(TestAccessibilityService::class.java).get(),
            buffer = buffer,
            nowMillis = { NOW },
            sessionIdFactory = { SESSION }
        )
        try {
            coordinator.setResultListener { results += it }
            shadowOf(Looper.getMainLooper()).idle()
            coordinator.onEvent(CheckoutObservationEvent.Stable(stablePage()))
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            coordinator.close()
        }
        return results
    }

    /**
     * 【D-16 主判据】同指纹已有手动候选时，自动截图必须照常发起，不得被前置拒绝。
     *
     * 判别性：恢复 Coordinator 侧前置守卫 ⇒ 本用例必红
     * （结果变回 `PROTECTED / MANUAL_CANDIDATE_PROTECTED`）。
     */
    @Test
    fun stableAutoCaptureIsNotBlockedBySameFingerprintManualCandidate() {
        val buffer = buffer()
        val results = driveStableEvent(buffer) { b ->
            val seeded = b.store(
                request(SnapshotSourceReason.MANUAL_CAPTURE, SnapshotCaptureTarget.USER_RETRY)
            )
            assertTrue("前置条件：手动候选应写入成功", seeded is SnapshotStoreResult.Stored)
        }

        assertEquals("应恰好发布一条截图结果", 1, results.size)
        val result = results.single()
        assertFalse(
            "同指纹手动候选不得再前置拒绝自动截图（D-16 = B，该原因已删除）",
            result.reasonCode == REMOVED_PROTECTED_REASON
        )
        // 结果落到截图能力门禁 ⇒ 证明流程已越过前置判定、真正进入 beginCapture。
        assertEquals(
            "自动截图应已推进到截图能力门禁",
            SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE.wireValue,
            result.reasonCode
        )
        assertEquals(SnapshotSourceReason.CHECKOUT_STABLE, result.sourceReason)
        assertEquals(SnapshotCaptureStatus.UNAVAILABLE, result.status)
    }

    /**
     * 保留语义护栏：同指纹**自动**候选的去重（DUPLICATE）不受 D-16 影响。
     *
     * 删除前置守卫只应取消"手动候选 ⇒ 拦自动"，不应一并取消"自动候选已在槽内 ⇒ 不重复截图"。
     */
    @Test
    fun stableAutoCaptureStillDeduplicatesSameFingerprintAutomaticCandidate() {
        val buffer = buffer()
        val results = driveStableEvent(buffer) { b ->
            val seeded = b.store(
                request(SnapshotSourceReason.CHECKOUT_STABLE, SnapshotCaptureTarget.CHECKOUT)
            )
            assertTrue("前置条件：自动候选应写入成功", seeded is SnapshotStoreResult.Stored)
        }

        assertEquals(1, results.size)
        assertEquals(
            SnapshotCaptureStatus.DUPLICATE.wireValue,
            results.single().reasonCode
        )
        assertFalse(
            "去重分支不得退化为前置拒绝",
            results.single().reasonCode == REMOVED_PROTECTED_REASON
        )
    }
}
