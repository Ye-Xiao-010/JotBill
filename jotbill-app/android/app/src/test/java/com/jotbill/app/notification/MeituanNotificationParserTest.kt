package com.jotbill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 美团退款通知解析测试（2026-11-16 真机样本）。
 *
 * 同一笔 ¥51.00 退款的两条通知：先「退款通知」，约 2 分 22 秒后「退款状态提醒」。
 */
class MeituanNotificationParserTest {

    @Test
    fun 退款通知解析为退款证据() {
        val refund = MeituanNotificationParser.parseRefund(
            "退款通知 您有一笔51.00元的退款，点击查看详情！",
            occurredAt = 1_000_000L
        )
        assertNotNull(refund)
        assertEquals(5100L, refund!!.amountMinor)
        assertEquals("美团", refund.merchant)
    }

    @Test
    fun 退款状态提醒解析含店铺与去向() {
        val refund = MeituanNotificationParser.parseRefund(
            "退款状态提醒 您在【塔斯汀·中国汉堡（百草路店）】有一笔【51.00】元的退款，" +
                "【微信支付】已受理，预计2个工作日内到账。",
            occurredAt = 1_000_000L
        )
        assertNotNull(refund)
        assertEquals(5100L, refund!!.amountMinor)
        assertEquals("塔斯汀·中国汉堡（百草路店）", refund.merchant)
        assertEquals("微信支付", refund.receivingAccountHint)
    }

    @Test
    fun 营销文案不产退款证据() {
        assertNull(
            MeituanNotificationParser.parseRefund(
                "退款无忧，美团外卖满30减9，快来下单",
                occurredAt = 1_000_000L
            )
        )
    }

    @Test
    fun 非退款通知不产证据() {
        assertNull(
            MeituanNotificationParser.parseRefund(
                "您的外卖订单已送达，点击评价",
                occurredAt = 1_000_000L
            )
        )
    }
}
