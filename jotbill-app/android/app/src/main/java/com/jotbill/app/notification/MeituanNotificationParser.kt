package com.jotbill.app.notification

import com.jotbill.app.bookkeeping.AutoBookkeepingEngine
import java.security.MessageDigest

/**
 * 美团 App 退款通知解析（2026-11-16 真机样本）。
 *
 * 真机样本（同一笔 ¥51.00 退款的两条通知）：
 * 1. 「退款通知 / 您有一笔51.00元的退款，点击查看详情！」
 * 2. 「退款状态提醒 / 您在【塔斯汀·中国汉堡（百草路店）】有一笔【51.00】元的退款，
 *    【微信支付】已受理，预计2个工作日内到账。」
 *
 * 覆盖场景：美团退款到微信/美团余额等非银行卡渠道时没有银行退款短信；
 * 两条通知描述同一笔退款，由 [RefundNotificationMemory] 按「包名+金额」30 分钟
 * 去重，避免重复建卡。
 */
internal object MeituanNotificationParser {

    const val PACKAGE = "com.sankuai.meituan"

    /** 「有一笔51.00元的退款」「有一笔【51.00】元的退款」。 */
    private val REFUND_AMOUNT_PATTERN = Regex(
        """有一笔\s*[【\[]?\s*(\d+(?:\.\d{1,2})?)\s*[】\]]?\s*元的?退款"""
    )

    /** 商户（店铺名）：「您在【塔斯汀·中国汉堡（百草路店）】有一笔」。 */
    private val SHOP_PATTERN = Regex("""您在【([^】]{2,40})】有一笔""")

    /** 退款去向：「【微信支付】已受理」。 */
    private val CHANNEL_PATTERN = Regex("""【(微信支付|支付宝|美团支付|银行卡|原路退回)】""")

    private const val TIME_BUCKET_MILLIS = 15_000L

    fun parseRefund(content: String, occurredAt: Long): PaymentEvidenceInput? {
        if (content.isBlank() || !content.contains("退款")) return null
        if (!content.contains("退款通知") && !content.contains("退款状态")) return null
        val amountText = REFUND_AMOUNT_PATTERN.find(content)?.groupValues?.get(1) ?: return null
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(amountText) ?: return null
        if (amountMinor <= 0L) return null
        val shop = SHOP_PATTERN.find(content)?.groupValues?.get(1)?.trim()
        val channel = CHANNEL_PATTERN.find(content)?.groupValues?.get(1)
        val textHash = sha256(content)
        return PaymentEvidenceInput(
            sourcePackage = PACKAGE,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = shop?.takeIf { it.isNotEmpty() } ?: "美团",
            orderId = null,
            paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
            sourceEventKey = sha256(
                "meituan-refund|$PACKAGE|${occurredAt / TIME_BUCKET_MILLIS}|$amountMinor"
            ),
            rawTextHash = textHash,
            receivingAccountHint = channel
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
