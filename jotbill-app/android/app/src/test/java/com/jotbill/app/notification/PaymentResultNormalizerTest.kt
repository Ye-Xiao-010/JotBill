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
}
