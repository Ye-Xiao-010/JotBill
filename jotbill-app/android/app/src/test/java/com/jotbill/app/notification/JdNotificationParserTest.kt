package com.jotbill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 京东退款通知解析测试（2026-11-16 真机样本）。
 *
 * 现场：京东退款到微信（非银行卡），无银行退款短信；退款详情页为 Taro 渲染，
 * 对无障碍不可见 —— 退款通知是唯一可达信号。
 */
class JdNotificationParserTest {

    @Test
    fun 退款成功提醒解析为退款证据() {
        val refund = JdNotificationParser.parseRefund(
            "退款成功提醒 您的订单【退款-唯誠天然水晶原石铋…】已退款11.10元至微信，查看详情>>",
            occurredAt = 1_000_000L
        )
        assertNotNull(refund)
        assertEquals(1110L, refund!!.amountMinor)
        assertEquals("京东", refund.merchant)
        assertEquals("微信", refund.receivingAccountHint)
        assertEquals(PaymentResultNormalizer.PAYMENT_STATUS_REFUND, refund.paymentStatus)
    }

    @Test
    fun 支付通知不产退款证据() {
        assertNull(
            JdNotificationParser.parseRefund(
                "您的订单已支付，支付金额11.10元，点击查看订单",
                occurredAt = 1_000_000L
            )
        )
    }

    @Test
    fun 营销文案不产退款证据() {
        assertNull(
            JdNotificationParser.parseRefund(
                "退款无忧，全场满100元减20元，快来抢购",
                occurredAt = 1_000_000L
            )
        )
    }

    @Test
    fun 发货通知不产退款证据() {
        assertNull(
            JdNotificationParser.parseRefund(
                "您的订单已发货，点击查看物流详情",
                occurredAt = 1_000_000L
            )
        )
    }
}
