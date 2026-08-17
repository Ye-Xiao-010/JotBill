package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentResultParserTest {

    private val observedAt = 1_800_000_000_000L

    @Test
    fun parsesSuccessfulPaymentEvidence() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥12.34 订单号: ABC123456",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Success)
        val evidence = (result as PaymentResultParseResult.Success).evidence
        assertEquals(1234L, evidence.amountMinor)
        assertEquals("ABC123456", evidence.orderId)
        assertEquals("SUCCESS", evidence.paymentStatus)
    }

    @Test
    fun failedPaymentPageNeverProducesSuccessEvidence() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付失败 支付成功 实付 ¥12.34",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Failure)
        assertEquals(
            PaymentResultFailureReason.PAYMENT_FAILED,
            (result as PaymentResultParseResult.Failure).reason
        )
    }

    @Test
    fun parsesWechatLiteAppContentDescriptionOnlySuccessPage() {
        // 微信支付成功页可把所有语义放在 contentDescription：支付成功/商户/金额/返回商家。
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 拼多多 ￥27.45 返回商家",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "a".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Success)
        val evidence = (result as PaymentResultParseResult.Success).evidence
        assertEquals(2745L, evidence.amountMinor)
        assertEquals("拼多多", evidence.merchant)
        assertEquals("SUCCESS", evidence.paymentStatus)
    }

    @Test
    fun invalidFirstAmountFallsBackToNextValidAmount() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥12.345，支付金额 ¥8.80",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Success)
        assertEquals(
            880L,
            (result as PaymentResultParseResult.Success).evidence.amountMinor
        )
    }

    /** 微信好友转账成功页（强语义：转账给+收款人）不得落消费证据。 */
    @Test
    fun personalTransferPageProducesTransferNotExpense() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 已转账给张三 ￥29.75 转账已送达",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.PersonalTransfer)
        val transfer = (result as PaymentResultParseResult.PersonalTransfer).transfer
        assertEquals(2975L, transfer.amountMinor)
        assertEquals("张三", transfer.recipient)
    }

    /** 弱语义：仅"转账"关键词且无商户无订单号，同样视为个人转账。 */
    @Test
    fun softTransferKeywordWithoutMerchantOrOrderIsTransfer() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 ￥59.00 转账 已送达",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.PersonalTransfer)
        assertEquals(5900L, (result as PaymentResultParseResult.PersonalTransfer).transfer.amountMinor)
    }

    /** 商户消费页含"转账"字样但有商户/订单号时仍按消费证据处理。 */
    @Test
    fun merchantPaymentWithOrderStillProducesExpense() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 商户: 拼多多 订单号: ABC123456 转账 实付 ¥39.60",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Success)
        assertEquals(
            3960L,
            (result as PaymentResultParseResult.Success).evidence.amountMinor
        )
    }

    /** 历史账单详情页（显示时间远早于观察时刻）不得落证据。 */
    @Test
    fun historicalBillDetailPageIsRejected() {
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 网鱼网咖 实付 ¥39.60 2026-08-15 20:11",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.NotPaymentResult)
    }

    /** 页面时间接近观察时刻（当次支付结果页）正常落证据。 */
    @Test
    fun currentPageTimeStillProducesEvidence() {
        // 动态取 observedAt 的本地时分，保证与时区无关地落在时效窗口内。
        val localTime = java.time.Instant.ofEpochMilli(observedAt)
            .atZone(java.time.ZoneId.systemDefault())
        val hhmm = "%02d:%02d".format(localTime.hour, localTime.minute)

        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 拼多多 实付 ¥39.60 支付时间: $hhmm",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.Success)
    }
}
