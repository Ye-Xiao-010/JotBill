package com.jotbill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拼多多退款通知解析测试（2026-11-16 真机样本）。
 *
 * 现场：微信零钱支付后退款，无银行短信，拼多多「退款成功通知」是唯一退款信号。
 */
class PddNotificationParserTest {

    @Test
    fun 退款成功通知解析为退款证据() {
        val refund = PddNotificationParser.parseRefund(
            "退款成功通知 尊敬的用户，您的39.90元退款已退款成功。钱款已退回至您的微信账户中。",
            occurredAt = 1_000_000L
        )
        assertNotNull(refund)
        assertEquals(3990L, refund!!.amountMinor)
        assertEquals("拼多多", refund.merchant)
        assertEquals(PaymentResultNormalizer.PAYMENT_STATUS_REFUND, refund.paymentStatus)
    }

    @Test
    fun 退款到账通知解析为退款证据() {
        val refund = PddNotificationParser.parseRefund(
            "退款到账通知 你收到一笔19.8元退款，点击查看详情",
            occurredAt = 1_000_000L
        )
        assertNotNull(refund)
        assertEquals(1980L, refund!!.amountMinor)
    }

    @Test
    fun 营销文案不产退款证据() {
        val refund = PddNotificationParser.parseRefund(
            "退款无忧，全场满100元减20元，快来抢购",
            occurredAt = 1_000_000L
        )
        assertNull("营销文案不是退款", refund)
    }

    @Test
    fun 非退款通知不产证据() {
        assertNull(
            PddNotificationParser.parseRefund(
                "您的订单已发货，点击查看物流详情",
                occurredAt = 1_000_000L
            )
        )
    }
}
