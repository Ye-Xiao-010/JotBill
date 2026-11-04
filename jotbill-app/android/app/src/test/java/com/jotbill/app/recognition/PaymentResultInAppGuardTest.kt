package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 站内结果页负向守卫测试（[PaymentResultParser.isLikelyOrderDetailPage]）。
 *
 * 覆盖点：
 * 1. 订单详情 / 订单列表 / 账单 / 交易记录页文案 ⇒ 守卫命中，零证据（负向）；
 * 2. 真站内支付成功页（含"查看订单详情"按钮 + 隐藏推荐位噪声）⇒ 守卫不误杀，
 *    正常产出证据；
 * 3. 敏感页文案（付款码 / 密码 / 银行卡）⇒ 敏感判定命中（「敏感页零采集」
 *    不变式的词表口径，接线顺序敏感先于解析由服务层保证）。
 *
 * ⚠️ 服务层接线（`onAccessibilityEvent` 购物包分流）在 JVM 不可测
 * （依赖 AccessibilityNodeInfo），替代验证手段 = 真机 logcat 采集核对
 * 「站内页面判为订单页…跳过证据提取」与「支付结果页解析到证据」两类日志。
 *
 * 样本按 `includeHiddenText = true` 的输入形态设计（含隐藏推荐/按钮噪声词）。
 */
class PaymentResultInAppGuardTest {

    private val observedAt = 1_800_000_000_000L

    /**
     * 模拟站内入口的组合判定（与服务层同序）：守卫命中 ⇒ 不进解析器（零证据）；
     * 未命中 ⇒ 走 [PaymentResultParser.parseResult]。
     */
    private fun inAppScan(pageText: String): PaymentResultParseResult? =
        if (PaymentResultParser.isLikelyOrderDetailPage(pageText)) {
            null
        } else {
            PaymentResultParser.parseResult(
                pageText = pageText,
                sourcePackage = "com.xunmeng.pinduoduo",
                pageFingerprint = "f".repeat(64),
                observedAt = observedAt
            )
        }

    @Test
    fun orderDetailPageIsGuardedAndYieldsNoEvidence() {
        // 订单详情页：标题在前、"已付款"状态行在后 —— 刚付完的订单页时间在
        // 30 分钟时效窗内，只有本守卫能挡住它。
        val pageText = "订单详情 订单号: D2026110312345 已付款 实付 ¥29.90 商户：测试小店 " +
            "再次购买 查看物流 猜你喜欢"

        assertTrue(PaymentResultParser.isLikelyOrderDetailPage(pageText))
        assertNull("订单页不得产出支付证据", inAppScan(pageText))
    }

    @Test
    fun orderListAndBillPagesAreGuarded() {
        val samples = listOf(
            "订单列表 全部订单 待付款 待收货 已完成 ¥19.90 已付款 拼多多",
            "我的订单 近三个月订单 交易完成 ¥88.00 美团外卖",
            "账单明细 交易记录 支出 ¥35.50 商户：京东",
            "交易明细 2026-11-03 12:30 交易完成 ¥66.00 抖音商城"
        )
        for (pageText in samples) {
            assertTrue("应判为订单页: $pageText", PaymentResultParser.isLikelyOrderDetailPage(pageText))
            assertNull(inAppScan(pageText))
        }
    }

    @Test
    fun orderPageWithoutSuccessKeywordIsGuarded() {
        // 双保险分支：有订单页标题词但无成功词（parseResult 本身也会拒绝）。
        val pageText = "订单详情 待付款 请尽快完成支付 ¥29.90"
        assertTrue(PaymentResultParser.isLikelyOrderDetailPage(pageText))
    }

    @Test
    fun realInAppResultPageWithOrderDetailButtonIsNotGuarded() {
        // 真站内结果页："支付成功"标题在前、"查看订单详情"按钮词在后（含隐藏
        // 推荐位噪声）—— 守卫不得误杀，须正常产出证据。
        val pageText = "支付成功 实付 ¥29.90 商户：测试小店 返回商城 查看订单详情 " +
            "猜你喜欢 为你推荐 热卖榜单"

        assertFalse(PaymentResultParser.isLikelyOrderDetailPage(pageText))
        val result = inAppScan(pageText)
        assertNotNull(result)
        assertTrue(result is PaymentResultParseResult.Success)
        val evidence = (result as PaymentResultParseResult.Success).evidence
        assertEquals(2990L, evidence.amountMinor)
        assertEquals("测试小店", evidence.merchant)
        assertEquals("SUCCESS", evidence.paymentStatus)
    }

    @Test
    fun plainShoppingPageWithoutOrderTermsIsNotGuarded() {
        // 无订单页标题词 ⇒ 守卫不动作（是否出证据由 parseResult 自行判定）。
        val pageText = "商品详情 选择规格 加入购物车 ¥29.90 立即购买"
        assertFalse(PaymentResultParser.isLikelyOrderDetailPage(pageText))
    }

    @Test
    fun blankTextIsNotGuarded() {
        assertFalse(PaymentResultParser.isLikelyOrderDetailPage(""))
        assertFalse(PaymentResultParser.isLikelyOrderDetailPage("   "))
    }

    @Test
    fun sensitivePageTermsAreDetected() {
        // 「敏感页零采集」不变式的词表口径：密码 / 付款码 / 银行卡 / OTP 命中。
        val sensitiveSamples = listOf(
            "请输入支付密码",
            "付款码 30分钟内有效",
            "银行卡号 6222 **** **** 1234",
            "Please enter your PIN",
            "OTP 123456 验证码"
        )
        for (text in sensitiveSamples) {
            assertTrue("敏感词应命中: $text", AccessibilityPageSummaryCollector.containsSensitiveSignal(text))
        }
    }

    @Test
    fun cleanShoppingPageIsNotSensitive() {
        val clean = "支付成功 实付 ¥29.90 商户：测试小店 返回商城"
        assertFalse(AccessibilityPageSummaryCollector.containsSensitiveSignal(clean))
    }
}
