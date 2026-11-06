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
    fun sameTextDifferentOrderIdProducesDifferentRawTextHash() {
        // 场景 5 承诺的根基（2026-10-24 场景审查补，此前零覆盖）：同商户同额
        // 同文本、仅订单号不同的两笔，哈希必须不同 —— 否则 15 秒内的真实第二笔
        // 会被"同哈希合并"吞掉。区分力来自 orderIdDigest（sha256("order|…")），
        // 页面文本其余部分经 sanitizeForHash 收敛为同一形态。
        val first = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥19.90 订单号: ORDER1000001",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )
        val second = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥19.90 订单号: ORDER2000002",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(first is PaymentResultParseResult.Success)
        assertTrue(second is PaymentResultParseResult.Success)
        assertNotEquals(
            "仅订单号不同 ⇒ 哈希必须不等",
            (first as PaymentResultParseResult.Success).evidence.rawTextHash,
            (second as PaymentResultParseResult.Success).evidence.rawTextHash
        )
    }

    @Test
    fun samePageRepeatedObservationProducesStableRawTextHash() {
        // 反向不变式：同一页面（同订单号）重复观测，哈希必须稳定 —— 否则
        // "同一笔不重复建卡"的合并判据永远不命中。
        val text = "支付成功 实付 ¥19.90 订单号: ORDER3000003"
        val first = PaymentResultParser.parseResult(
            pageText = text,
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )
        val second = PaymentResultParser.parseResult(
            pageText = text,
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt + 20_000L
        )

        assertTrue(first is PaymentResultParseResult.Success)
        assertEquals(
            (first as PaymentResultParseResult.Success).evidence.rawTextHash,
            (second as PaymentResultParseResult.Success).evidence.rawTextHash
        )
    }

    @Test
    fun countdownAndClockTextDoesNotDestabilizeRawTextHash() {
        // P2 负向钉：结果页带倒计时/时钟等随帧变化字样时，同一笔的重复观测
        // 哈希必须稳定 —— 旧实现把这些字样计入哈希，「同文本哈希判同一笔」
        // 判据失效，页面停留超 15 秒即双卡（2026-09-30 ¥2.68 双卡残留形态）。
        // 反向边界同时钉住：金额不同的两笔必须仍得到不同哈希（不得误并）。
        val first = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥29.90 商户：测试小店 05:59 后自动关闭",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )
        val second = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥29.90 商户：测试小店 05:41 后自动关闭",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt + 18_000L
        )
        val differentAmount = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥30.90 商户：测试小店 05:59 后自动关闭",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt + 18_000L
        )

        assertTrue(first is PaymentResultParseResult.Success)
        assertTrue(second is PaymentResultParseResult.Success)
        assertTrue(differentAmount is PaymentResultParseResult.Success)
        assertEquals(
            "仅倒计时不同的同一笔 ⇒ 哈希必须相等",
            (first as PaymentResultParseResult.Success).evidence.rawTextHash,
            (second as PaymentResultParseResult.Success).evidence.rawTextHash
        )
        assertNotEquals(
            "金额不同的两笔 ⇒ 哈希必须不等",
            first.evidence.rawTextHash,
            (differentAmount as PaymentResultParseResult.Success).evidence.rawTextHash
        )
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
     * 同一结果页在 15 秒内的重复读取必须收敛到同一事件键。
     *
     * 防噪原意保留：无障碍结果页会秒级连发多条事件，同一页不得落多条证据。
     * ⚠️ 2026-10-24 桶宽由 30 秒收窄至 15 秒（用户裁定：同额第二笔 15 秒内接受合并）。
     */
    @Test
    fun sameResultPageWithinFifteenSecondBucketKeepsSingleEventKey() {
        val pageText = "支付成功 拼多多 实付 ¥39.60"

        assertEquals(
            "同一结果页 5 秒内重复读取应收敛到同一事件键（防噪语义保留）",
            eventKeyOf(pageText, observedAt),
            eventKeyOf(pageText, observedAt + 5_000L)
        )
    }

    /**
     * 同包同金额两单相隔 20 秒时事件键必须不同。
     *
     * 否则第二单证据会被 `markPaymentResultEvent` 静默吞掉（不落待确认、不派 AI）。
     *
     * **判别性（2026-10-24 修订）**：`observedAt` 恰为 30 秒桶边界
     * （`1_800_000_000_000 % 30_000 == 0`），20 秒后**仍落在同一 30 秒桶内**、
     * 但已跨过 15 秒桶。因此：
     * - 桶宽 15 秒 ⇒ 两键不同（本例通过）；
     * - 桶宽回退到 30 秒 ⇒ 两键相同（本例必红）。
     *
     * 原先用 40 秒间隔时，30 秒桶下也已跨桶 ⇒ 回退到 30 秒照样通过，用例不判别。
     */
    @Test
    fun sameResultPageAcrossFifteenSecondBucketProducesDistinctEventKeys() {
        val pageText = "支付成功 拼多多 实付 ¥39.60"
        val secondOrderObservedAt = observedAt + 20_000L

        // 前提自检：这两次读取在 30 秒桶下确实同桶 —— 本用例判别力的来源。
        assertEquals(
            "前提：相隔 20 秒的两次读取仍落在同一 30 秒桶内",
            observedAt / 30_000L,
            secondOrderObservedAt / 30_000L
        )

        assertNotEquals(
            "同包同金额两单相隔 20 秒不得撞键，否则第二单被静默丢弃",
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

    // ---------- B1：站内结果页接入文本解析管线 ----------

    @Test
    fun inAppResultPageParsesWithPackageScopedStableEventKey() {
        // 站内（购物包）支付成功页文案：解析为成功证据，事件键按来源包名 + 15 秒桶
        // + 金额 + 订单号生成 —— 同输入幂等、不同包名不撞键（D-6 单一生成点复用）。
        val pageText = "支付成功 实付 ¥29.90 订单号: PDD20261103001"

        val first = PaymentResultParser.parseResult(
            pageText = pageText,
            sourcePackage = "com.xunmeng.pinduoduo",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )
        val second = PaymentResultParser.parseResult(
            pageText = pageText,
            sourcePackage = "com.xunmeng.pinduoduo",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt + 5_000L
        )
        val otherPackage = PaymentResultParser.parseResult(
            pageText = pageText,
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(first is PaymentResultParseResult.Success)
        val evidence = (first as PaymentResultParseResult.Success).evidence
        assertEquals(2990L, evidence.amountMinor)
        assertEquals("PDD20261103001", evidence.orderId)
        assertEquals(
            "同输入重复观测应收敛到同一事件键",
            evidence.sourceEventKey,
            (second as PaymentResultParseResult.Success).evidence.sourceEventKey
        )
        assertNotEquals(
            "不同来源包名不得撞键",
            evidence.sourceEventKey,
            (otherPackage as PaymentResultParseResult.Success).evidence.sourceEventKey
        )
    }

    @Test
    fun historicalVoucherWithOldPageTimeIsRejected() {
        // 30 分钟时间闸回归（B1 站内入口复用本闸，不得绕过）：页面显示时间距观察
        // 时刻超过时效窗 ⇒ 历史订单/凭证回放，静默拒绝，不产出成功证据。
        val result = PaymentResultParser.parseResult(
            pageText = "支付成功 实付 ¥12.34 商户：历史小店 2021-06-01 10:30 订单号: OLD20210601",
            sourcePackage = "com.xunmeng.pinduoduo",
            pageFingerprint = "f".repeat(64),
            observedAt = observedAt
        )

        assertTrue(result is PaymentResultParseResult.NotPaymentResult)
    }
}
