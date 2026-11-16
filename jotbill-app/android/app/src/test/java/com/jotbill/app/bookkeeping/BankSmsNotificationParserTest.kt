package com.jotbill.app.bookkeeping

import com.jotbill.app.notification.PaymentResultNormalizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 银行短信通知监听链路解析测试（2026-11-16 真机样本形态）。
 *
 * 样本取自真机短信（发送方 106980096633 / 95580），仅改动无关数字；
 * 覆盖三类：支出短信、退款到账短信、非交易短信。
 */
class BankSmsNotificationParserTest {

    private fun registry(): PageRuleRegistry {
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        assertTrue("内置规则资产不存在: ${asset.absolutePath}", asset.isFile)
        return PageRuleRegistry.fromJsonText(asset.readText())
    }

    @Test
    fun 四川农商支出短信命中通用规则() {
        val record = BankSmsNotificationParser.parseExpense(
            registry(),
            "【四川农商银行】您尾号2510的账户11月16日09:59通过快捷支付支出人民币20.6元 " +
                "(拼多多-实物消费-拼多多平台商户) , 余额28.32元。",
            extractTime = 1_000_000L
        )
        assertNotNull("支出短信必须产出记录", record)
        assertEquals(2000106, record!!.ruleId)
        assertEquals("2", record.fields["detailType"])
        assertEquals(2060L, AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"]))
    }

    @Test
    fun 邮储支出短信命中通用规则() {
        val record = BankSmsNotificationParser.parseExpense(
            registry(),
            "【邮储银行】26年11月16日08:24您尾号5607账户快捷支付-支付宝，支出金额6.00元，余额1782.59元",
            extractTime = 1_000_000L
        )
        assertNotNull(record)
        assertEquals(2000106, record!!.ruleId)
        assertEquals("2", record.fields["detailType"])
        assertEquals(600L, AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"]))
    }

    @Test
    fun 退款到账短信判定为退款证据() {
        val refund = BankSmsNotificationParser.parseRefund(
            "【四川农商银行】您尾号2510的账户11月16日09:59到账人民币20.6元 " +
                "(拼多多-实物消费-拼多多平台商户) , 余额48.92元,对方账号尾号为3857,户名为拼多多平台商户。",
            occurredAt = 1_000_000L
        )
        assertNotNull("到账+商户语义必须判为退款", refund)
        assertEquals(2060L, refund!!.amountMinor)
        assertEquals("拼多多", refund.merchant)
        assertEquals(PaymentResultNormalizer.PAYMENT_STATUS_REFUND, refund.paymentStatus)
        assertNull("银行短信无原订单号，由 RefundLinker 走待确认", refund.orderId)
    }

    /** 四川农商简版退款格式：「到账人民币19.8元(拼多多)」——无「退款/商户」字样。 */
    @Test
    fun 简版到账短信括号商户名判为退款() {
        val refund = BankSmsNotificationParser.parseRefund(
            "【四川农商银行】您尾号2510的账户11月16日10:21到账人民币19.8元(拼多多)，余额48.92元。",
            occurredAt = 1_000_000L
        )
        assertNotNull("括号商户名的到账必须判为退款", refund)
        assertEquals(1980L, refund!!.amountMinor)
        assertEquals("拼多多", refund.merchant)
    }

    @Test
    fun 工资入账的括号词不判退款() {
        val refund = BankSmsNotificationParser.parseRefund(
            "【四川农商银行】您尾号2510的账户11月16日10:21到账人民币5000.00元（工资），余额5000.00元。",
            occurredAt = 1_000_000L
        )
        assertNull("工资入账不是退款", refund)
    }

    @Test
    fun 普通到账非商户不入退款() {
        val refund = BankSmsNotificationParser.parseRefund(
            "【招商银行】您账户1234于11月16日10:00到账人民币500.00元，对方户名为某某科技有限公司。",
            occurredAt = 1_000_000L
        )
        assertNull("无退款/商户语义的到账不是退款", refund)
    }

    @Test
    fun 验证码短信既非支出也非退款() {
        val text = "【招商银行】验证码123456，您正在快捷支付，请勿泄露给他人。"
        assertNull(BankSmsNotificationParser.parseExpense(registry(), text, 1_000_000L))
        assertNull(BankSmsNotificationParser.parseRefund(text, 1_000_000L))
    }

    @Test
    fun 理财收益短信不入账() {
        val text = "【招商银行】您理财收益88.00元已入账，请登录手机银行查看。"
        assertNull(BankSmsNotificationParser.parseExpense(registry(), text, 1_000_000L))
        assertNull(BankSmsNotificationParser.parseRefund(text, 1_000_000L))
    }
}
