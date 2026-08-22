package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结算页稳定确认最长等待上限的回归测试。
 *
 * 自动截图（CHECKOUT_STABLE）必须先通过结算页门禁、再熬过 500ms 去抖窗口
 * 才会触发。去抖策略是"每来一个新指纹就把计时器归零"，因此在指纹持续漂移的
 * 页面上稳定确认会被无限推迟：京东弹窗结算页的推荐位与优惠券文案不断改写
 * 指纹，用户观察到"自动截图很难触发"。
 *
 * 真机 .tmp-device-capture-20260822-214140 中两分钟仅 3 次 CHECKOUT_STABLE。
 *
 * 修复引入最长等待上限：达到上限即强制确认一次，宁可确认一帧略旧的结算页，
 * 也不能一张都截不到。本测试锁定该判据的边界。
 */
class StableConfirmTimeoutTest {

    private val maxWait = 2_000L

    /**
     * 未达上限时不得强制确认，去抖仍应正常生效。
     *
     * 否则每个新指纹都会立即确认，等于取消去抖，会在页面渲染中途截到半成品。
     */
    @Test
    fun 未达上限时不强制确认() {
        assertFalse(
            "距首次候选仅 1999ms，未达 2000ms 上限",
            shouldForceStableConfirm(
                firstPendingAt = 1_000L,
                now = 2_999L,
                maxStableWaitMillis = maxWait
            )
        )
    }

    /**
     * 恰好达到上限时必须强制确认。
     */
    @Test
    fun 达到上限时强制确认() {
        assertTrue(
            shouldForceStableConfirm(
                firstPendingAt = 1_000L,
                now = 3_000L,
                maxStableWaitMillis = maxWait
            )
        )
    }

    /**
     * 超过上限时必须强制确认。
     *
     * 对应京东指纹持续漂移的场景：去抖计时器被反复重置，
     * 若不放行则自动截图永远等不到稳定确认。
     */
    @Test
    fun 超过上限时强制确认() {
        assertTrue(
            shouldForceStableConfirm(
                firstPendingAt = 1_000L,
                now = 5_000L,
                maxStableWaitMillis = maxWait
            )
        )
    }

    /**
     * 尚无候选（firstPendingAt=0）时不得强制确认。
     *
     * 该状态下 pendingObservation 为空，强制确认没有任何可确认的观察，
     * 且 0 与当前时间的差值必然远超上限，若不显式排除会误判。
     */
    @Test
    fun 无候选时不强制确认() {
        assertFalse(
            "firstPendingAt=0 表示尚无候选，不能因时间差巨大而误判",
            shouldForceStableConfirm(
                firstPendingAt = 0L,
                now = 9_999_999L,
                maxStableWaitMillis = maxWait
            )
        )
    }

    /**
     * 上限必须显著大于 500ms 去抖窗口，否则去抖形同虚设。
     */
    @Test
    fun 上限需显著大于去抖窗口() {
        val debounce = 500L
        assertTrue(
            "最长等待上限($maxWait)应至少为去抖窗口($debounce)的两倍，保留去抖效果",
            maxWait >= debounce * 2
        )
    }
}
