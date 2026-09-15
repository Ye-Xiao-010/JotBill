package com.jotbill.app.recognition

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 稳定协调器"离开上报"资格的回归测试（Y4 会话跨页污染）。
 *
 * 真机故障时序（2026-09-10 15:41，0.0.97 B/D 场景，证据
 * docs/qa/device/2026-09-10-bd-checkout-and-c-assert.logcat.txt）：
 * - 15:41:38.9x 规格层抖动帧：observe(ACCEPTED) 后紧跟同包 invalidate，
 *   候选经抖动保留（keptAcrossInvalidation）在 15:41:39.5 强制确认稳定，
 *   会话 ab3a9a3e 锚定——但 hasActiveCheckout 仍为 false；
 * - 15:41:46~52 用户关层逛主页/商品页：每次 invalidate() 都被入口
 *   !hasActiveCheckout 吞掉（整段旅程零"观察失效"，协调器对离开失明）；
 * - 15:42:00 真结算页稳定事件被"指纹漂移沿用原会话"吞掉，
 *   规格层截图跨 4 次页面切换活到支付交接栅栏。
 *
 * 修复：抖动保留路径确认稳定后恢复 hasActiveCheckout，此后"离开结算页"
 * 的失效正常上报，协调器把会话转入挂起由交接过渡窗约束时效。
 * 本测试同时钉死反向边界：无稳定会话时入口门槛仍然拦截（防失效刷屏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StabilityCoordinatorDepartureTest {

    private val pdd = "com.xunmeng.pinduoduo"

    private fun observation(fingerprint: String) = CheckoutPageObservation(
        shoppingPackage = pdd,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        hasFinalAmountSignal = true,
        hasCheckoutActionSignal = true,
        isSensitivePage = false
    )

    /**
     * 抖动保留锚定的会话，其后的离开失效必须上报。
     *
     * 这是本次修复的核心场景：修复前第二步 invalidate 返回 false 且零回调，
     * 协调器永远收不到"用户已离开结算页"。
     */
    @Test
    fun `抖动锚定后的离开失效必须上报`() {
        val stablePages = mutableListOf<StableCheckoutPage>()
        val invalidations = mutableListOf<CheckoutObservationInvalidation>()
        val coordinator = CheckoutStabilityCoordinator(
            handler = Handler(Looper.getMainLooper()),
            stableDelayMillis = 500L,
            nowMillis = { 0L },
            onStable = { stablePages += it },
            onInvalidated = { invalidations += it }
        )

        // 规格层抖动：ACCEPTED 帧进入候选队列。
        coordinator.observe(observation("fp-spec-1"))
        // 同包非结算帧：候选保留（keptAcrossInvalidation），失效照常上报一次。
        assertTrue(
            "抖动帧的失效应正常上报（此时 hasActiveCheckout 仍为 true）",
            coordinator.invalidate(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                shoppingPackage = pdd
            )
        )
        assertEquals(1, invalidations.size)

        // 保留候选经去抖窗口强制确认稳定——会话锚定（Y4 漏网点）。
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        assertEquals("保留候选应确认稳定并锚定会话", 1, stablePages.size)

        // 用户关层离开结算页（同包非结算页）——修复前此处被入口吞掉。
        val reported = coordinator.invalidate(
            reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
            shoppingPackage = pdd
        )
        assertTrue(
            "稳定确认放行后，离开失效必须上报，否则协调器对用户离开永久失明",
            reported
        )
        assertEquals("离开失效应到达协调器（Y4 回归点）", 2, invalidations.size)
    }

    /**
     * 无任何稳定会话时，失效仍被入口拦截。
     *
     * 修复只恢复"已锚定会话"的上报资格，不得打开失效刷屏的口子：
     * 购物 App 内每个普通页都会产生 NON_CHECKOUT_PAGE 失效。
     */
    @Test
    fun `无稳定会话时失效仍被拦截`() {
        val invalidations = mutableListOf<CheckoutObservationInvalidation>()
        val coordinator = CheckoutStabilityCoordinator(
            handler = Handler(Looper.getMainLooper()),
            stableDelayMillis = 500L,
            nowMillis = { 0L },
            onStable = { },
            onInvalidated = { invalidations += it }
        )

        assertFalse(
            "从未 observe 过结算页时，失效不得上报（防刷屏门槛保持原样）",
            coordinator.invalidate(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                shoppingPackage = pdd
            )
        )
        assertEquals(0, invalidations.size)
    }

    /**
     * 正常路径（无抖动）的失效上报行为不变。
     *
     * observe() 本身置位 hasActiveCheckout，离开失效始终可上报；
     * 确认本修复未改变正常路径语义。
     */
    @Test
    fun `正常路径的离开失效上报不变`() {
        val invalidations = mutableListOf<CheckoutObservationInvalidation>()
        val coordinator = CheckoutStabilityCoordinator(
            handler = Handler(Looper.getMainLooper()),
            stableDelayMillis = 500L,
            nowMillis = { 0L },
            onStable = { },
            onInvalidated = { invalidations += it }
        )

        coordinator.observe(observation("fp-normal-1"))
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        assertTrue(
            "observe 后未经历失效的会话，离开时照常上报",
            coordinator.invalidate(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                shoppingPackage = pdd
            )
        )
        assertEquals(1, invalidations.size)
    }
}
