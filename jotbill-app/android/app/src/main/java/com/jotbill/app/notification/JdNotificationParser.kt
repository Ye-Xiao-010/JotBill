package com.jotbill.app.notification

import com.jotbill.app.bookkeeping.AutoBookkeepingEngine
import java.security.MessageDigest

/**
 * 京东 App 退款通知解析（2026-11-16 真机样本）。
 *
 * 真机样本：
 * 「退款成功提醒 / 您的订单【退款-唯誠天然水晶原石铋…】已退款11.10元至微信，查看详情>>」
 *
 * 覆盖场景：京东退款到**微信/京东钱包/白条**等非银行卡渠道时没有银行退款短信，
 * 且京东退款详情页（Taro 渲染）对无障碍不可见（实测节点树零文本）——退款通知
 * 是该场景唯一可达的信号，带金额与退款去向。
 *
 * 只认退款；京东支付通知不入账（银行卡支付由短信链路、微信/支付宝支付由各自
 * 信号链路覆盖，避免同一笔多路建卡）。
 */
internal object JdNotificationParser {

    const val PACKAGE = "com.jingdong.app.mall"

    /** 金额紧跟「已退款」：「已退款11.10元」。 */
    private val REFUND_AMOUNT_PATTERN = Regex("""已退款\s*(\d+(?:\.\d{1,2})?)\s*元""")

    /** 退款去向：「已退款11.10元至微信」。 */
    private val REFUND_CHANNEL_PATTERN = Regex("""已退款\s*\d+(?:\.\d{1,2})?\s*元\s*至([^\s,，。；;]{1,10})""")

    private const val TIME_BUCKET_MILLIS = 15_000L

    fun parseRefund(content: String, occurredAt: Long): PaymentEvidenceInput? {
        if (content.isBlank() || !content.contains("退款")) return null
        if (!content.contains("退款成功") && !content.contains("已退款")) return null
        val amountText = REFUND_AMOUNT_PATTERN.find(content)?.groupValues?.get(1) ?: return null
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(amountText) ?: return null
        if (amountMinor <= 0L) return null
        val channel = REFUND_CHANNEL_PATTERN.find(content)?.groupValues?.get(1)
        val textHash = sha256(content)
        return PaymentEvidenceInput(
            sourcePackage = PACKAGE,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = "京东",
            orderId = null,
            paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
            // 键只取「包名+时间桶+金额」：同一条通知可能被系统重复投递。
            sourceEventKey = sha256(
                "jd-refund|$PACKAGE|${occurredAt / TIME_BUCKET_MILLIS}|$amountMinor"
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
