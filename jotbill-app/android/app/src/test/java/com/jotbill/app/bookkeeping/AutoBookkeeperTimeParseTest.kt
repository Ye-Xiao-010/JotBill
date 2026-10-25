package com.jotbill.app.bookkeeping

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * businessTime 解析全套（审查指出此函数此前零覆盖且已抓到跨年真 bug）。
 * 以固定时区驱动，钉死各格式与跨零点/跨年回退语义。
 */
class AutoBookkeeperTimeParseTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun parse(text: String?, at: LocalDateTime): Long =
        AutoBookkeeper.parseBusinessTimeMillis(
            text,
            fallback = at.atZone(zone).toInstant().toEpochMilli()
        )

    private fun epoch(at: LocalDateTime): Long = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `完整年月日时分秒格式`() {
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 14, 30, 25)),
            parse("2026年10月25日 14:30:25", LocalDateTime.of(2026, 10, 25, 20, 0))
        )
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 14, 30, 0)),
            parse("2026年10月25日 14:30", LocalDateTime.of(2026, 10, 25, 20, 0))
        )
    }

    @Test
    fun `月日时分按当年解释`() {
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 9, 5)),
            parse("10月25日 9:05", LocalDateTime.of(2026, 10, 25, 10, 0))
        )
        // 紧凑形态（招行短信「10月25日14:30」无空格）
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 14, 30)),
            parse("10月25日14:30", LocalDateTime.of(2026, 10, 25, 20, 0))
        )
    }

    @Test
    fun `月日时分跨年回退`() {
        // 1 月 2 日收到「12月28日 23:00」的短信 → 去年 12 月 28 日
        assertEquals(
            epoch(LocalDateTime.of(2025, 12, 28, 23, 0)),
            parse("12月28日 23:00", LocalDateTime.of(2026, 1, 2, 8, 0))
        )
    }

    @Test
    fun `短横线日期格式与跨年容差`() {
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 14, 30)),
            parse("10-25 14:30", LocalDateTime.of(2026, 10, 25, 20, 0))
        )
        // 审查 P2-2 修复钉子：此前 dayTimeDash 无跨年容差，1 月初的 12-28 记成未来 11 个月
        assertEquals(
            epoch(LocalDateTime.of(2025, 12, 28, 14, 30)),
            parse("12-28 14:30", LocalDateTime.of(2026, 1, 2, 8, 0))
        )
    }

    @Test
    fun `纯时刻按当天解释跨零点回退`() {
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 25, 8, 30)),
            parse("8:30", LocalDateTime.of(2026, 10, 25, 12, 0))
        )
        // 00:10 收到「23:50」的支付 → 昨天 23:50
        assertEquals(
            epoch(LocalDateTime.of(2026, 10, 24, 23, 50)),
            parse("23:50", LocalDateTime.of(2026, 10, 25, 0, 10))
        )
    }

    @Test
    fun `解析失败回退抽取时刻`() {
        val fallback = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
        assertEquals(fallback, AutoBookkeeper.parseBusinessTimeMillis("垃圾文本", fallback))
        assertEquals(fallback, AutoBookkeeper.parseBusinessTimeMillis("", fallback))
        assertEquals(fallback, AutoBookkeeper.parseBusinessTimeMillis(null, fallback))
    }

    @Test
    fun `订单号判别：双方都有且不同是两笔真实交易`() {
        assertTrue("任一方缺失 = 无法排除同笔，保守吸收", AutoBookkeeper.isSameTransaction(null, "A1"))
        assertTrue(AutoBookkeeper.isSameTransaction("A1", null))
        assertTrue("同订单号 = 同一笔", AutoBookkeeper.isSameTransaction("A1", "A1"))
        assertFalse("不同订单号 = 不得吸收", AutoBookkeeper.isSameTransaction("A1", "B2"))
        // 既有账单缺单号 + 详情页补出新单号 = 典型回填场景，保守视为同笔
        assertTrue(AutoBookkeeper.isSameTransaction("", "B2"))
    }
}
