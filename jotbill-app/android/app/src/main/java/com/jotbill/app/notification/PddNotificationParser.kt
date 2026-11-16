package com.jotbill.app.notification

import com.jotbill.app.bookkeeping.AutoBookkeepingEngine
import java.security.MessageDigest

/**
 * 拼多多通知解析（仅退款）。
 *
 * 真机实证（2026-11-16）：拼多多在支付环节**不发任何通知**，只在退款时发一条
 * 「退款成功通知 / 退款到账通知」。微信零钱、多多钱包等**不经过银行卡**的
 * 支付退款没有银行短信，此时拼多多退款通知是唯一可达的退款信号。
 *
 * 样本：
 * - 「退款成功通知 尊敬的用户，您的39.90元退款已退款成功。钱款已退回至您的微信账户中。」
 * - 「退款到账通知 你收到一笔19.8元退款，点击查看详情」
 *
 * 反误判：营销文案（如「退款无忧，满100元减20元」）既不含退款成功强语义、
 * 金额后也不紧跟「退款」词，不会产出证据。
 */
internal object PddNotificationParser {

    const val PACKAGE = "com.xunmeng.pinduoduo"

    private val REFUND_STRONG_MARKERS = listOf("退款成功", "退款到账", "退款已")

    /** 金额紧跟退款词（「39.90元退款」「19.8元退款」）。 */
    private val ADJACENT_REFUND_AMOUNT = Regex(
        """(\d+(?:\.\d{1,2})?)\s*元\s*(?:退款|退货|退回)"""
    )

    /** 强语义下的宽松金额（「退款成功…39.90元」）。 */
    private val ANY_AMOUNT = Regex("""(\d+(?:\.\d{1,2})?)\s*元""")

    private const val TIME_BUCKET_MILLIS = 15_000L

    /**
     * 解析退款通知；非退款/无金额返回 null。
     *
     * @param content 通知标题 + 正文 + bigText 拼接结果
     */
    fun parseRefund(content: String, occurredAt: Long): PaymentEvidenceInput? {
        if (content.isBlank() || !content.contains("退款")) return null
        val strong = REFUND_STRONG_MARKERS.any { content.contains(it) }
        val amountText = if (strong) {
            ANY_AMOUNT.find(content)?.groupValues?.get(1)
        } else {
            ADJACENT_REFUND_AMOUNT.find(content)?.groupValues?.get(1)
        } ?: return null
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(amountText) ?: return null
        if (amountMinor <= 0L) return null
        val textHash = sha256(content)
        return PaymentEvidenceInput(
            sourcePackage = PACKAGE,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = "拼多多",
            orderId = null,
            paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
            // 键只取「包名+时间桶+金额」：同一条退款通知可能被系统重复投递。
            sourceEventKey = sha256(
                "pdd-refund|$PACKAGE|${occurredAt / TIME_BUCKET_MILLIS}|$amountMinor"
            ),
            rawTextHash = textHash,
            receivingAccountHint = null
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
