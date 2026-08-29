package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JourneyLifecyclePolicy 生命周期全矩阵(批 4 规格 §3,2026-08-29 定稿参数)。
 * 钉死:锚点判定、D1/D2/D3 死亡边界、阶段推进刷新计时、超时参数不可为非正数。
 */
class JourneyLifecyclePolicyTest {

    private companion object {
        const val JOURNEY = "journey-1"
        val ANCHOR = JourneyAnchor("com.xunmeng.pinduoduo", "com.jd.lib.settlement.fillorder")
        const val BORN = 1_000_000L
    }

    private fun journey(
        stage: JourneyLifecyclePolicy.Stage,
        lastProgressAt: Long = BORN,
        anchor: JourneyAnchor = ANCHOR
    ) = JourneyLifecyclePolicy.JourneyRuntime(
        journeyId = JOURNEY, anchor = anchor, bornAt = BORN,
        stage = stage, lastProgressAt = lastProgressAt
    )

    @Test
    fun sameAnchorIsSameJourneyEvenAfterFingerprintDrift() {
        val policy = JourneyLifecyclePolicy()
        // 指纹漂移不影响锚点判定:锚点集合里没有指纹
        assertTrue(policy.isSameJourney(journey(JourneyLifecyclePolicy.Stage.CHECKOUT_STABLE), ANCHOR))
        assertFalse(
            policy.isSameJourney(
                journey(JourneyLifecyclePolicy.Stage.CHECKOUT_STABLE),
                JourneyAnchor("com.xunmeng.pinduoduo", "com.other.checkout")
            )
        )
        assertFalse(
            policy.isSameJourney(
                journey(JourneyLifecyclePolicy.Stage.CHECKOUT_STABLE),
                JourneyAnchor("com.taobao.taobao", ANCHOR.windowClass)
            )
        )
    }

    @Test
    fun waitingPaymentDiesAtEightMinutes() {
        var now = BORN
        val policy = JourneyLifecyclePolicy(nowMillis = { now })
        val j = journey(JourneyLifecyclePolicy.Stage.WAITING_PAYMENT)
        now = BORN + 8 * 60_000L - 1
        assertFalse(policy.shouldDie(j))
        now = BORN + 8 * 60_000L
        assertTrue(policy.shouldDie(j))
    }

    @Test
    fun checkoutStallDiesAtTenMinutes() {
        var now = BORN
        val policy = JourneyLifecyclePolicy(nowMillis = { now })
        val j = journey(JourneyLifecyclePolicy.Stage.CHECKOUT_STABLE)
        now = BORN + 10 * 60_000L - 1
        assertFalse(policy.shouldDie(j))
        now = BORN + 10 * 60_000L
        assertTrue(policy.shouldDie(j))
    }

    @Test
    fun leftShoppingDiesAtThreeMinutes() {
        var now = BORN
        val policy = JourneyLifecyclePolicy(nowMillis = { now })
        val j = journey(JourneyLifecyclePolicy.Stage.LEFT_SHOPPING)
        now = BORN + 3 * 60_000L - 1
        assertFalse(policy.shouldDie(j))
        now = BORN + 3 * 60_000L
        assertTrue(policy.shouldDie(j))
    }

    @Test
    fun advanceRefreshesStageAndProgressClock() {
        var now = BORN
        val policy = JourneyLifecyclePolicy(nowMillis = { now })
        val checkout = journey(JourneyLifecyclePolicy.Stage.CHECKOUT_STABLE)
        now = BORN + 9 * 60_000L
        // 停滞 9 分钟的结算旅程:尚未死亡,但已交接 → 推进后从 D2 换成 D1 计时
        assertFalse(policy.shouldDie(checkout))
        val waiting = policy.advance(checkout, JourneyLifecyclePolicy.Stage.WAITING_PAYMENT)
        now = BORN + 9 * 60_000L + 7 * 60_000L
        // 若沿用出生时间计算已超 D1,但推进刷新了计时起点:7 分钟 < 8 分钟存活
        assertFalse(policy.shouldDie(waiting))
        now = BORN + 9 * 60_000L + 8 * 60_000L
        assertTrue(policy.shouldDie(waiting))
    }

    @Test
    fun defaultParametersMatchSpecDecision() {
        assertEquals(8 * 60_000L, JourneyLifecyclePolicy.DEFAULT_WAITING_PAYMENT_TIMEOUT_MILLIS)
        assertEquals(10 * 60_000L, JourneyLifecyclePolicy.DEFAULT_CHECKOUT_STALL_TIMEOUT_MILLIS)
        assertEquals(3 * 60_000L, JourneyLifecyclePolicy.DEFAULT_LEFT_SHOPPING_TIMEOUT_MILLIS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveTimeoutRejected() {
        JourneyLifecyclePolicy(waitingPaymentTimeoutMillis = 0L)
    }
}
