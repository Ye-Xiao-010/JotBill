package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁抖动期间保留结算候选的回归测试。
 *
 * 京东弹窗结算页在 ACCEPTED 与 NOT_CHECKOUT_PAGE 之间高频抖动，
 * 真机 .tmp-device-capture-20260823-144208 的判定序列：
 *   14:42:42.432 ACCEPTED → 14:42:43.099 NOT_CHECKOUT_PAGE  (667ms)
 *   14:43:11.881 ACCEPTED → 14:43:11.897 NOT_CHECKOUT_PAGE  (16ms)
 *   14:43:23.194 ACCEPTED → 14:43:23.222 NOT_CHECKOUT_PAGE  (28ms)
 * 每次 ACCEPTED 都是孤立单点，京东 6 次 ACCEPTED 仅换来 1 次自动截图；
 * 拼多多因 ACCEPTED 能连续出现（14:43:38.950 与 14:43:42.366）故 2/2 成功。
 *
 * 根因是 invalidate() 把 pendingObservation 与 firstPendingAt 一并清零，
 * 使 d8531e4 引入的最长等待上限永远攒不满。
 *
 * 本测试锁定"何种失效应保留候选"这一判据：既要让同包抖动不清零，
 * 也不能把真正的旅程终止误判为抖动而带着过期候选进入下一段旅程。
 */
class PendingCandidateRetentionTest {

    private val jd = "com.jingdong.app.mall"
    private val pdd = "com.xunmeng.pinduoduo"

    /**
     * 同包非结算页失效应保留候选与累计等待。
     *
     * 这是本次修复的核心场景：京东结算页抖动时不清零，
     * 让最长等待上限有机会攒满并强制确认。
     */
    @Test
    fun 同包非结算页失效保留候选() {
        assertTrue(
            "同包抖动不应清零候选，否则最长等待上限永远攒不满",
            shouldKeepPendingAcrossInvalidation(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = jd,
                pendingPackage = jd
            )
        )
    }

    /**
     * 跨包失效不得保留候选。
     *
     * 从京东切到拼多多是真正的旅程切换，保留候选会把京东的观察
     * 误确认到拼多多的槽位上。
     */
    @Test
    fun 跨包失效不保留候选() {
        assertFalse(
            shouldKeepPendingAcrossInvalidation(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = pdd,
                pendingPackage = jd
            )
        )
    }

    /**
     * 无候选时不得判定为保留。
     */
    @Test
    fun 无候选时不保留() {
        assertFalse(
            shouldKeepPendingAcrossInvalidation(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = jd,
                pendingPackage = null
            )
        )
        assertFalse(
            "空白包名不得与空白候选包名相等匹配",
            shouldKeepPendingAcrossInvalidation(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = "   ",
                pendingPackage = "   "
            )
        )
    }

    /**
     * 明确的旅程终止原因一律不保留候选。
     *
     * 支付交接、离开购物应用、敏感页、节点树不可用、服务中断都意味着
     * 当前结算旅程已结束；保留候选会让过期观察在下一段旅程被误确认。
     */
    @Test
    fun 旅程终止原因不保留候选() {
        listOf(
            CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
            CheckoutInvalidationReason.LEFT_SUPPORTED_SHOPPING_APP,
            CheckoutInvalidationReason.SENSITIVE_PAGE,
            CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
            CheckoutInvalidationReason.SERVICE_INTERRUPTED,
            CheckoutInvalidationReason.SERVICE_DESTROYED
        ).forEach { reason ->
            assertFalse(
                "${reason.wireValue} 属旅程终止，不得保留候选",
                shouldKeepPendingAcrossInvalidation(
                    reason = reason,
                    invalidatedPackage = jd,
                    pendingPackage = jd
                )
            )
        }
    }

    /**
     * 保留候选后，累计等待必须能凑足强制确认。
     *
     * 这条串联验证两个判据的协作：抖动保留候选（本次修复）+
     * 达到上限强制确认（d8531e4）。真机京东抖动间隔 16~667ms，
     * 只要不清零，2000ms 上限在数次抖动后即可达成。
     */
    @Test
    fun 保留候选后累计等待可凑足强制确认() {
        val firstPendingAt = 1_000L
        val maxWait = 2_000L
        assertTrue(
            "抖动保留候选后应能达到强制确认条件",
            shouldKeepPendingAcrossInvalidation(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = jd,
                pendingPackage = jd
            ) && shouldForceStableConfirm(
                firstPendingAt = firstPendingAt,
                now = firstPendingAt + maxWait,
                maxStableWaitMillis = maxWait
            )
        )
    }
}
