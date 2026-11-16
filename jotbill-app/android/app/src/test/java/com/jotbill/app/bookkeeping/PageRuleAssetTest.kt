package com.jotbill.app.bookkeeping

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置规则资产完整性：直接解析真实 assets 文件（小米 18 key / 27 条快照 +
 * jotbillType 标注）。规则 schema 或资产被改坏时这里第一时间红。
 */
class PageRuleAssetTest {

    private fun loadRegistry(): PageRuleRegistry {
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        assertTrue("内置规则资产不存在: ${asset.absolutePath}", asset.isFile)
        return PageRuleRegistry.fromJsonText(asset.readText())
    }

    @Test
    fun `内置规则全部解析且条数守恒`() {
        val registry = loadRegistry()
        assertEquals("规则 key 数（含逗号拆分展开）", EXPECTED_KEYS, registry.keyCount)
        assertEquals("唯一规则条数（逗号拆分前）", EXPECTED_RULES, registry.parsedRuleCount)
    }

    @Test
    fun `核心渠道规则在位`() {
        val registry = loadRegistry()
        // 支付宝收银台（裸类名 key）
        assertEquals(
            4,
            registry.rulesFor("com.alipay.android.msp.ui.views.MspContainerActivity")?.size
        )
        // 微信支付结果页（裸类名 key）
        assertEquals(
            2,
            registry.rulesFor("com.tencent.mm.framework.app.UIPageFragmentActivity")?.size
        )
        // 通知形态 key
        assertNotNull(registry.rulesFor("com.eg.android.AlipayGphone/android.app.Notification"))
        // 通配形态 key（经 matchKey 归一）
        assertEquals(
            "com.xunmeng.pinduoduo/*",
            registry.matchKey("com.xunmeng.pinduoduo", "anything.ui.Activity")
        )
        // 逗号 key 拆分（抖音团购 4 段 key 共享同一组规则实例）
        assertEquals(
            registry.rulesFor("com.bytedance.locallife.page.poi.LifePoiActivity"),
            registry.rulesFor("com.bytedance.ies.bullet.service.popup.ui.primary.PrimaryDialog")
        )
    }

    @Test
    fun `收入规则标注在位`() {
        val registry = loadRegistry()
        val incomeRules = registry.keyCount.let {
            var count = 0
            // 遍历全部规则统计 income 标注
            listOf(
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
                "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
                "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
                "com.android.mms/android.app.Notification"
            ).forEach { key ->
                registry.rulesFor(key)?.forEach { rule ->
                    if (rule.jotbillType == "income") count++
                }
            }
            count
        }
        assertEquals(5, incomeRules)
    }

    @Test
    fun `拼多多退款页规则抽取金额与退款渠道`() {
        val registry = loadRegistry()
        val rule = registry.rulesFor("com.xunmeng.pinduoduo/*")!!
            .first { it.ruleId == 2000210 }
        assertEquals("refund", rule.jotbillType)
        val dump = PageNodeDumper.dump(
            FakePageNode(
                text = null,
                contentDescription = null,
                children = listOf(
                    FakePageNode("已退款", null),
                    FakePageNode("67.8元将退到微信钱包（预计20分钟内到账）", null)
                )
            ),
            rule
        )
        assertTrue("退款页应定位成功", dump.located)
        val fields = rule.extractFrom(dump.dump)
        assertNotNull("退款金额应抽取成功: dump=${dump.dump}", fields)
        assertEquals("67.8", fields!!["amount"])
        assertEquals("微信钱包", fields["refundChannel"])
    }

    @Test
    fun `支付宝结果页规则在真实格式 dump 上抽取金额`() {
        val registry = loadRegistry()
        val rule = registry.rulesFor("com.alipay.android.msp.ui.views.MspContainerActivity")!!
            .first { it.ruleId == 2000100 }
        // locateAttribute=1：金额在 contentDesc 里（支付宝结果页的实际渲染形态）
        val page = FakePageNode(
            text = null,
            contentDescription = null,
            children = listOf(
                FakePageNode("支付成功", null),
                FakePageNode(null, "支付成功¥12.34")
            )
        )
        val dump = PageNodeDumper.dump(page, rule)
        assertTrue("dump 应定位成功", dump.located)
        val fields = rule.extractFrom(dump.dump)
        assertNotNull("金额应抽取成功: dump=${dump.dump}", fields)
        assertEquals("12.34", fields!!["amount"])
    }

    @Test
    fun `微信账单详情规则的 detailType 映射生效`() {
        val registry = loadRegistry()
        val rule = registry.rulesFor("com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI")!!
            .first { it.ruleId == 2000108 }
        val dump = PageNodeDumper.dump(
            FakePageNode(
                text = null,
                contentDescription = null,
                children = listOf(
                    FakePageNode("某某超市,支出12.00元 >", null),
                    FakePageNode("当前状态", null),
                    FakePageNode("支付成功", null),
                    FakePageNode("时间", null),
                    FakePageNode("2026年10月25日 14:30:01 />", null)
                )
            ),
            rule
        )
        assertTrue(dump.located)
        val fields = rule.extractFrom(dump.dump)
        assertNotNull("账单详情应抽取成功: dump=${dump.dump}", fields)
        assertEquals("小米已把 支出 归一化为 2", "2", fields!!["detailType"])
        assertEquals("12.00", fields["amount"])
    }

    @Test
    fun `可选类型组缺失时 mapper 的 defaultValue 仍生效`() {
        // mipay R() 语义：捕获组为空时仍应用 defaultValue —— 微信账单详情
        // 无「支出/收入」字样时按 defaultValue=1 判收入。回归钉子。
        val registry = loadRegistry()
        val rule = registry.rulesFor("com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI")!!
            .first { it.ruleId == 2000108 }
        val dump = PageNodeDumper.dump(
            FakePageNode(
                text = null,
                contentDescription = null,
                children = listOf(
                    FakePageNode("某某退款,12.00元 >", null),
                    FakePageNode("当前状态", null),
                    FakePageNode("支付成功", null),
                    FakePageNode("时间", null),
                    FakePageNode("2026年10月25日 14:30:01 />", null)
                )
            ),
            rule
        )
        assertTrue(dump.located)
        val fields = rule.extractFrom(dump.dump)
        assertNotNull("缺类型字样的账单详情也应抽取成功: dump=${dump.dump}", fields)
        assertEquals("捕获组缺失时 detailType 应取 defaultValue=1", "1", fields!!["detailType"])
    }

    companion object {
        /**
         * mocked_rule.json 原始 18 个 key，其中抖音团购一个逗号 key 拆成 4 → 展开 21。
         */
        private const val EXPECTED_KEYS = 21

        private const val EXPECTED_RULES = 28
    }
}
