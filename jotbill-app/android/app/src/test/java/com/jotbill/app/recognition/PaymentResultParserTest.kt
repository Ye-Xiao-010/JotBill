package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---------- D-6：结果页事件去重键桶宽（主路径 = 文本解析成功） ----------

    /**
     * 同一结果页在 30 秒内的重复读取必须收敛到同一事件键。
     *
     * 防噪原意保留：无障碍结果页会秒级连发多条事件，同一页不得落多条证据。
     */
    @Test
    fun sameResultPageWithinThirtySecondBucketKeepsSingleEventKey() {
        val pageText = "支付成功 拼多多 实付 ¥39.60"

        assertEquals(
            "同一结果页 5 秒内重复读取应收敛到同一事件键（防噪语义保留）",
            eventKeyOf(pageText, observedAt),
            eventKeyOf(pageText, observedAt + 5_000L)
        )
    }

    /**
     * 同包同金额两单相隔 40 秒（< 2 分钟）时事件键必须不同。
     *
     * 否则第二单证据会被 `markPaymentResultEvent` 静默吞掉（不落待确认、不派 AI）。
     *
     * **判别性**：40 秒的间隔仍落在同一个 120 秒桶内 ⇒ 旧实现（`/ 120_000`）两键相同、
     * 本用例必红；收窄为 30 秒桶后两键必须不同。该用例专钉**文本主路径**
     * （`PaymentResultParser`），因为只测截图兜底路径会漏掉这一处的漏改。
     */
    @Test
    fun sameResultPageAcrossThirtySecondBucketProducesDistinctEventKeys() {
        val pageText = "支付成功 拼多多 实付 ¥39.60"
        val secondOrderObservedAt = observedAt + 40_000L

        // 前提自检：这两次读取在旧 120 秒桶下确实撞键 —— 本用例判别力的来源。
        assertEquals(
            "前提：相隔 40 秒的两次读取仍落在同一 120 秒桶内",
            observedAt / 120_000L,
            secondOrderObservedAt / 120_000L
        )

        assertNotEquals(
            "同包同金额两单相隔 40 秒不得撞键，否则第二单被静默丢弃",
            eventKeyOf(pageText, observedAt),
            eventKeyOf(pageText, secondOrderObservedAt)
        )
    }

    /** 取文本主路径生成的事件键；未解析成功时直接判失败（避免测试假绿）。 */
    private fun eventKeyOf(pageText: String, at: Long): String {
        val result = PaymentResultParser.parseResult(
            pageText = pageText,
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = at
        )
        assertTrue("期望文本主路径解析成功，实际为 $result", result is PaymentResultParseResult.Success)
        return (result as PaymentResultParseResult.Success).evidence.sourceEventKey
    }
}
