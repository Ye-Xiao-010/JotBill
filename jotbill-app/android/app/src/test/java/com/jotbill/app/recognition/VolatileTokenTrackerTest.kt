package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 跨帧易变 token 追踪器单测（Z1 修复，2026-09-10）。
 *
 * 真机取证（.tmp-ui1/.tmp-ui2.xml diff）：拼多多商品规格层 8 秒内 9 条文本
 * 变化（价格/折扣/规格/榜单），父链全为普通容器，结构排除法覆盖不到。
 * 本测试钉死追踪器的核心契约：同一 scope 同一位置的文本在观察窗口内
 * 出现 ≥2 个不同取值即判易变；scope/位置隔离；窗口过期重置；清空语义。
 */
class VolatileTokenTrackerTest {

    private class FakeClock(var now: Long = 0L)

    private fun tracker(clock: FakeClock) =
        VolatileTokenTracker(windowMillis = 15_000L, nowMillis = { clock.now })

    @Test
    fun 同一位置首次观察不判易变() {
        val t = tracker(FakeClock())
        // 同一文本重复出现（静态内容每帧重复采集）不是易变
        assertFalse(t.observeAndCheck("pdd|main", "TextView@price", "券后 ¥9.9"))
        assertFalse(t.observeAndCheck("pdd|main", "TextView@price", "券后 ¥9.9"))
        assertFalse(t.observeAndCheck("pdd|main", "TextView@price", "券后 ¥9.9"))
    }

    @Test
    fun 同一位置文本变化即判易变() {
        val t = tracker(FakeClock())
        // 真机轮播场景：同一位置 8 秒内从 7.77 换到 6.69
        assertFalse(t.observeAndCheck("pdd|main", "TextView@price", "秋季特惠价 ¥7.77"))
        assertTrue(t.observeAndCheck("pdd|main", "TextView@price", "秋季特惠价 ¥6.69"))
        // 易变判定持续有效（后续观察返回 true，调用方据此跳过指纹）
        assertTrue(t.observeAndCheck("pdd|main", "TextView@price", "秋季特惠价 ¥7.77"))
    }

    @Test
    fun 不同位置与不同作用域互相隔离() {
        val t = tracker(FakeClock())
        t.observeAndCheck("pdd|main", "TextView@a", "文本1")
        // 同 scope 不同位置：不受影响
        assertFalse(t.observeAndCheck("pdd|main", "TextView@b", "文本2"))
        // 不同 scope（如支付结果页扫描）同一位置键：不受影响
        assertFalse(t.observeAndCheck("wechat|result", "TextView@a", "文本2"))
    }

    @Test
    fun 观察窗口过期后重新开始() {
        val clock = FakeClock()
        val t = tracker(clock)
        t.observeAndCheck("pdd|main", "TextView@price", "¥7.77")
        // 超出 15s 窗口：旧观察过期，新文本不算同窗口内的变化
        clock.now += 16_000
        assertFalse(t.observeAndCheck("pdd|main", "TextView@price", "¥6.69"))
        // 新窗口内再换值 → 判易变（两次观察都在新窗口内）
        assertTrue(t.observeAndCheck("pdd|main", "TextView@price", "¥7.77"))
    }

    @Test
    fun 清空后一切从头开始() {
        val clock = FakeClock()
        val t = tracker(clock)
        t.observeAndCheck("pdd|main", "TextView@a", "x")
        assertTrue(t.observeAndCheck("pdd|main", "TextView@a", "y"))
        t.clear()
        assertFalse(t.observeAndCheck("pdd|main", "TextView@a", "x"))
    }

    @Test
    fun 空位置键不追踪() {
        val t = tracker(FakeClock())
        assertFalse(t.observeAndCheck("pdd|main", "", "任何文本"))
        assertFalse(t.observeAndCheck("pdd|main", "  ", "任何文本"))
    }
}
