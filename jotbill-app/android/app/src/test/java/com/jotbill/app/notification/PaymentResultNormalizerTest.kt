package com.jotbill.app.notification

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PaymentResultNormalizerTest {

    private fun parse(
        text: String,
        postTime: Long
    ): PaymentEvidenceInput? = PaymentResultNormalizer.normalize(
        sourcePackage = PaymentResultNormalizer.WECHAT_PACKAGE,
        title = "微信支付",
        text = text,
        bigText = "",
        subText = "",
        summaryText = "",
        tickerText = "",
        postTime = postTime,
        notificationKey = "test-key",
        notificationId = 1
    )

    private fun epochMillis(dayOffset: Long, hour: Int, minute: Int): Long =
        LocalDate.of(2026, 8, 15)
            .plusDays(dayOffset)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun crossMidnightPaymentUsesPreviousDay() {
        // 支付 23:58，通知在次日 00:05 到达；通知正文只有 HH:mm。
        val postTime = epochMillis(0, 0, 5)
        val result = parse("支付金额 ¥12.00 交易时间 23:58", postTime)

        assertNotNull(result)
        assertEquals(epochMillis(-1, 23, 58), result?.occurredAt)
    }

    @Test
    fun sameDayPaymentKeepsSameDate() {
        val postTime = epochMillis(0, 9, 0)
        val result = parse("支付金额 ¥8.00 交易时间 08:00", postTime)

        assertNotNull(result)
        assertEquals(epochMillis(0, 8, 0), result?.occurredAt)
    }

    @Test
    fun invalidFirstAmountFallsBackToNextValidAmount() {
        // “实付”优先匹配到非法小数位金额，应继续寻找后面的有效金额。
        val result = parse(
            "实付 ¥12.345，支付金额 ¥8.80",
            epochMillis(0, 9, 0)
        )

        assertNotNull(result)
        assertEquals(880L, result?.amountMinor)
    }

    @Test
    fun nonConsumptionNotificationIsExcluded() {
        val result = parse("收款到账 ¥100.00", epochMillis(0, 9, 0))

        assertEquals(null, result)
    }

    @Test
    fun labeledBusinessTimePreferredOverFirstBareTime() {
        // P16：正文含多个时间时，裸时间在前、业务标签时间在后 —— 必须取标签时间
        // （旧实现取第一个时间，会误取 09:41 这类无关时间）。
        val postTime = epochMillis(0, 12, 0)
        val result = parse("支付金额 ¥12.00 09:41 支付时间 10:05", postTime)

        assertNotNull(result)
        assertEquals(epochMillis(0, 10, 5), result?.occurredAt)
    }

    @Test
    fun refundKeyIsStableAcrossNotificationResend() {
        // P6：退款键族改用业务字段 —— 同一笔退款通知被系统/应用重发（新通知 id、
        // 新送达时间）必须得到同一 sourceEventKey；不同业务事实必须不同键。
        val pkg = "com.eg.android.AlipayGphone"
        val text = "退款 ¥5.00 退回至余额 原交易号: ABC123456 退款时间 10:00"
        val first = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 11, 0), notificationKey = "key-1", notificationId = 101
        )
        val second = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 11, 30), notificationKey = "key-2", notificationId = 202
        )
        val otherAmount = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝",
            text = "退款 ¥6.00 退回至余额 原交易号: ABC123456 退款时间 10:00",
            bigText = "", subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 11, 30), notificationKey = "key-3", notificationId = 303
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(
            "同一笔退款重发不得换键",
            first?.sourceEventKey,
            second?.sourceEventKey
        )
        assertNotNull(otherAmount)
        org.junit.Assert.assertNotEquals(
            "不同金额的退款必须不同键",
            first?.sourceEventKey,
            otherAmount?.sourceEventKey
        )
    }

    @Test
    fun transferKeyIsStableAcrossNotificationResend() {
        // P6：转账键族同口径 —— 收款人提示参与键构成，重发换系统 id 不再换键。
        val pkg = "com.eg.android.AlipayGphone"
        val text = "转账 ¥20.00 转账给 张三 转账时间 09:30"
        val first = PaymentResultNormalizer.normalizeTransfer(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 10, 0), notificationKey = "key-1", notificationId = 1
        )
        val second = PaymentResultNormalizer.normalizeTransfer(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 10, 30), notificationKey = "key-2", notificationId = 2
        )
        val otherRecipient = PaymentResultNormalizer.normalizeTransfer(
            sourcePackage = pkg, title = "支付宝",
            text = "转账 ¥20.00 转账给 李四 转账时间 09:30",
            bigText = "", subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 10, 30), notificationKey = "key-3", notificationId = 3
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(
            "同一笔转账重发不得换键",
            first?.sourceEventKey,
            second?.sourceEventKey
        )
        assertNotNull(otherRecipient)
        org.junit.Assert.assertNotEquals(
            "不同收款人的转账必须不同键",
            first?.sourceEventKey,
            otherRecipient?.sourceEventKey
        )
    }

    @Test
    fun refundKeyWithoutContentTimeUsesContentHashAndStaysStable() {
        // M3：正文无可解析时间时，键的时间分量改用脱敏内容哈希 ——
        // 重发换 postTime 不再换键；不同内容必须异键。
        val pkg = "com.eg.android.AlipayGphone"
        val text = "退款 ¥5.00 退回至余额 原交易号: ABC123456"
        val first = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 11, 0), notificationKey = "k1", notificationId = 1
        )
        val second = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝", text = text, bigText = "",
            subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(1, 8, 0), notificationKey = "k2", notificationId = 2
        )
        val other = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝",
            text = "退款 ¥6.00 退回至余额 原交易号: ABC123456",
            bigText = "", subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(1, 8, 0), notificationKey = "k3", notificationId = 3
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(
            "正文无时间时重发必须同键（内容哈希兜底）",
            first?.sourceEventKey,
            second?.sourceEventKey
        )
        assertNotNull(other)
        org.junit.Assert.assertNotEquals(
            "不同内容必须异键",
            first?.sourceEventKey,
            other?.sourceEventKey
        )
    }

    @Test
    fun refundKeyDistinguishesTwoRefundsWithinSameMinute() {
        // M6：时间桶收窄到秒级 —— 同分钟内同额同单号的两笔部分退款不再同键。
        val pkg = "com.eg.android.AlipayGphone"
        val earlier = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝",
            text = "退款 ¥5.00 退回至余额 原交易号: ABC123456 退款时间 10:00:05",
            bigText = "", subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 10, 1), notificationKey = "k1", notificationId = 1
        )
        val later = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = pkg, title = "支付宝",
            text = "退款 ¥5.00 退回至余额 原交易号: ABC123456 退款时间 10:00:30",
            bigText = "", subText = "", summaryText = "", tickerText = "",
            postTime = epochMillis(0, 10, 1), notificationKey = "k2", notificationId = 2
        )

        assertNotNull(earlier)
        assertNotNull(later)
        org.junit.Assert.assertNotEquals(
            "同分钟内不同秒的两笔退款必须异键（M6）",
            earlier?.sourceEventKey,
            later?.sourceEventKey
        )
    }
}
