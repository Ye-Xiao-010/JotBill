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
}
