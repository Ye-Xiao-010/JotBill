package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProactiveScanPolicy] 停止判定的双向用例。
 *
 * 写宽（该停不停）⇒ 时钟空转耗电；写窄（不该停而停）⇒ 重新漏弹 ——
 * 这是 v5 修复的全部风险面，每条边界都必须钉死。时间轴语义取自
 * `docs/qa/device/2026-10-27-remote-user1/ANALYSIS.md` 的四份实测日志。
 */
class ProactiveScanPolicyTest {

    private val paymentPackages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    private fun supportsPayment(pkg: String) = pkg in paymentPackages

    private val start = 1_000_000L

    @Test
    fun scansWhileInPaymentApp() {
        // 事件死区中活动根仍指向微信（log4 22:31:51 场景）：必须持续扫。
        assertEquals(
            ProactiveScanPolicy.Verdict.SCAN,
            ProactiveScanPolicy.verdict(
                startedAt = start,
                lastSeenAt = start,
                activeRootPackage = "com.tencent.mm",
                now = start + 60_000L, // 死区内 60 秒（实测 75~155s）
                supportsPayment = ::supportsPayment
            )
        )
    }

    @Test
    fun waitsDuringWindowSwitchInstant() {
        // 确认键→成功页切换瞬间活动根短暂为空（log4 22:31:51.319 的 GAP 特征）：
        // 宽限窗内不得停 —— 停了就漏掉切换后第一帧成功页。
        assertEquals(
            ProactiveScanPolicy.Verdict.WAIT,
            ProactiveScanPolicy.verdict(
                startedAt = start,
                lastSeenAt = start,
                activeRootPackage = "",
                now = start + 5_000L, // < 10s 宽限
                supportsPayment = ::supportsPayment
            )
        )
    }

    @Test
    fun stopsAfterLeavingPaymentBeyondLinger() {
        // 离开支付环境超过宽限窗：停（用户已完成/放弃，时钟空转无意义）。
        assertEquals(
            ProactiveScanPolicy.Verdict.STOP_LEFT_PAYMENT,
            ProactiveScanPolicy.verdict(
                startedAt = start,
                lastSeenAt = start,
                activeRootPackage = "",
                now = start + 11_000L, // > 10s 宽限
                supportsPayment = ::supportsPayment
            )
        )
    }

    @Test
    fun stopsAtDurationCap() {
        // 总时长超 3 分钟上限：即使仍停在支付页也停（最坏场景封顶，防时钟永续）。
        assertEquals(
            ProactiveScanPolicy.Verdict.STOP_TIMEOUT,
            ProactiveScanPolicy.verdict(
                startedAt = start,
                lastSeenAt = start + 179_000L,
                activeRootPackage = "com.eg.android.AlipayGphone",
                now = start + 181_000L, // > 180s
                supportsPayment = ::supportsPayment
            )
        )
    }

    @Test
    fun nonPaymentRootWithinLingerWaitsNotScans() {
        // 活动根是 systemui（HyperOS 2 支付宝窗口全空时 systemui 弹层携带文本，
        // log4 22:33:42 场景）：不扫（root 不是支付根，scanPaymentResultCore
        // 的 roots 判定会把 systemui 文本交给窗口列表路径），也不立即停。
        assertEquals(
            ProactiveScanPolicy.Verdict.WAIT,
            ProactiveScanPolicy.verdict(
                startedAt = start,
                lastSeenAt = start,
                activeRootPackage = "com.android.systemui",
                now = start + 2_000L,
                supportsPayment = ::supportsPayment
            )
        )
    }

    @Test
    fun lastSeenRefreshExtendsLinger() {
        // 宽限窗从"最近一次确认在支付环境"起算，不是从启动起算：用户在微信里
        // 停留很久后短暂切走，不应按启动时刻误判超时。
        val verdict = ProactiveScanPolicy.verdict(
            startedAt = start,
            lastSeenAt = start + 120_000L, // 两分钟时还确认在支付环境
            activeRootPackage = "",
            now = start + 125_000L, // 切走 5 秒
            supportsPayment = ::supportsPayment
        )
        assertEquals(ProactiveScanPolicy.Verdict.WAIT, verdict)
    }

    @Test
    fun lingerBoundariesAreExact() {
        // 边界值即语义：恰好 10s 仍 WAIT（> 才停，与 KDoc 一致）；10s+1ms 停。
        assertEquals(
            ProactiveScanPolicy.Verdict.WAIT,
            ProactiveScanPolicy.verdict(start, start, "", start + 10_000L, ::supportsPayment)
        )
        assertEquals(
            ProactiveScanPolicy.Verdict.STOP_LEFT_PAYMENT,
            ProactiveScanPolicy.verdict(start, start, "", start + 10_001L, ::supportsPayment)
        )
    }

    @Test
    fun durationCapBoundaryIsExact() {
        // 恰好 180s 仍 SCAN；180s+1ms 停 —— 上限裁定的精确口径。
        assertEquals(
            ProactiveScanPolicy.Verdict.SCAN,
            ProactiveScanPolicy.verdict(
                start, start, "com.tencent.mm", start + 180_000L, ::supportsPayment
            )
        )
        assertEquals(
            ProactiveScanPolicy.Verdict.STOP_TIMEOUT,
            ProactiveScanPolicy.verdict(
                start, start, "com.tencent.mm", start + 180_001L, ::supportsPayment
            )
        )
    }
}
