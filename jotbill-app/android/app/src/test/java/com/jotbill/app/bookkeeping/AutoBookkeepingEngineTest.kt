package com.jotbill.app.bookkeeping

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎状态机行为测试（小米 ce.b 语义的关键不变量）：
 * 节流 / 窗口去重 / 认领注册表 / 通知规则路径 / 二次确认（页面锚点）。
 */
class AutoBookkeepingEngineTest {

    private class RecordingSink : AutoBookkeepingEngine.Sink {
        val records = mutableListOf<AutoBookkeepingEngine.ExtractedRecord>()

        override fun onRecordReady(record: AutoBookkeepingEngine.ExtractedRecord): Boolean {
            records += record
            val amount = AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"])
            return amount != null && amount > 0
        }
    }

    private class Harness {
        val clock = mutableMapOf("now" to 1_000_000L)
        val sink = RecordingSink()
        val registry = AtomicReference<PageRuleRegistry?>(PageRuleRegistry.fromJsonText(TEST_REGISTRY_TEXT))
        val engine = AutoBookkeepingEngine(
            registryRef = registry,
            channels = { true },
            sink = sink,
            clock = { clock["now"]!! }
        )

        fun windowEvent(
            className: String,
            windowId: Int = 1,
            packageName: String = "com.eg.android.AlipayGphone"
        ) = AutoBookkeepingEngine.EventSnapshot(
            eventType = AutoBookkeepingEngine.EVENT_WINDOW_STATE_CHANGED,
            packageName = packageName,
            className = className,
            windowId = windowId,
            eventTime = clock["now"]!!,
            notificationText = null,
            notificationContentDesc = null
        )

        fun contentEvent(
            eventTime: Long,
            windowId: Int = 1,
            className: String = "android.widget.TextView",
            packageName: String = "com.eg.android.AlipayGphone"
        ) = AutoBookkeepingEngine.EventSnapshot(
            eventType = AutoBookkeepingEngine.EVENT_WINDOW_CONTENT_CHANGED,
            packageName = packageName,
            className = className,
            windowId = windowId,
            eventTime = eventTime,
            notificationText = null,
            notificationContentDesc = null
        )

        fun notificationEvent(
            text: String?,
            className: String = "android.app.Notification",
            packageName: String = "com.eg.android.AlipayGphone"
        ) = AutoBookkeepingEngine.EventSnapshot(
            eventType = AutoBookkeepingEngine.EVENT_NOTIFICATION_STATE_CHANGED,
            packageName = packageName,
            className = className,
            windowId = 0,
            eventTime = clock["now"]!!,
            notificationText = text,
            notificationContentDesc = null
        )
    }

    private fun alipayResultPage(): FakePageNode = FakePageNode(
        text = null,
        contentDescription = null,
        children = listOf(
            FakePageNode("支付成功", null),
            FakePageNode(null, "支付成功¥12.34")
        )
    )

    @Test
    fun `窗口状态事件命中规则页并抽取交付`() {
        val h = Harness()
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            alipayResultPage()
        }
        assertEquals(1, h.sink.records.size)
        assertEquals(2000100, h.sink.records[0].ruleId)
        assertEquals("12.34", h.sink.records[0].fields["amount"])
    }

    @Test
    fun `认领注册表让同额弹窗让路不同额放行`() {
        val h = Harness()
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            alipayResultPage()
        }
        assertTrue(h.engine.shouldSuppressPopup("com.eg.android.AlipayGphone", 1234))
        assertFalse("不同金额不受抑制", h.engine.shouldSuppressPopup("com.eg.android.AlipayGphone", 999))
        // 认领窗（15s）过期后放行
        h.clock["now"] = h.clock["now"]!! + 16_000
        assertFalse(h.engine.shouldSuppressPopup("com.eg.android.AlipayGphone", 1234))
    }

    @Test
    fun `内容变化事件200毫秒节流且部分渲染页自愈重试`() {
        val h = Harness()
        // 窗口事件建立跟踪，页面尚未渲染出锚点 → 无交付
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            FakePageNode(null, null)
        }
        assertEquals(0, h.sink.records.size)
        // +100ms 内容变化（部分渲染）→ 未定位成功，不交付，但记录节流水位
        h.engine.onEvent(h.contentEvent(eventTime = 1_000_100)) { FakePageNode(null, null) }
        assertEquals(0, h.sink.records.size)
        // +200ms（距上次仅 100ms）页面已完整 → 必须被节流丢弃
        h.engine.onEvent(h.contentEvent(eventTime = 1_000_200)) { alipayResultPage() }
        assertEquals("节流窗内的内容变化不得处理", 0, h.sink.records.size)
        // +600ms（距上次 400ms）同窗口完整页 → 交付（部分渲染自愈）
        h.engine.onEvent(h.contentEvent(eventTime = 1_000_600)) { alipayResultPage() }
        assertEquals(1, h.sink.records.size)
    }

    @Test
    fun `抽取成功后同窗口内容变化不再处理`() {
        val h = Harness()
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            alipayResultPage()
        }
        assertEquals(1, h.sink.records.size)
        h.sink.records.clear()
        // 窗口已被标记 reported：内容变化事件直接忽略
        h.engine.onEvent(h.contentEvent(eventTime = 1_000_500)) { alipayResultPage() }
        assertEquals(0, h.sink.records.size)
    }

    @Test
    fun `通知事件走通知规则路径`() {
        val h = Harness()
        h.engine.onEvent(h.notificationEvent(text = "消费12.00元，已支出")) { null }
        assertEquals(1, h.sink.records.size)
        assertEquals(2000079, h.sink.records[0].ruleId)
        assertEquals("12.00", h.sink.records[0].fields["amount"])
    }

    @Test
    fun `带确认锚点的规则先挂起确认页到达才交付`() {
        val h = Harness()
        val confirmClass =
            "com.alibaba.android.ultron.vfw.weex2.highPerformance.widget.UltronTradeHybridActivity"
        // 收银台页面出现密码提示 → 规则 2000098 抽取成功但只挂二次确认
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity", windowId = 2)) {
            FakePageNode(
                null,
                null,
                children = listOf(
                    FakePageNode("请输入支付密码", null),
                    FakePageNode(null, "支付金额88.00元")
                )
            )
        }
        assertEquals("二次确认前不得交付", 0, h.sink.records.size)
        // 跳转支付宝支付成功页（确认锚点 locateAttribute=-1，页面到达即确认）
        h.engine.onEvent(h.windowEvent(confirmClass, windowId = 3)) { alipayResultPage() }
        assertEquals("确认锚点命中后应交付", 1, h.sink.records.size)
        assertEquals(2000098, h.sink.records[0].ruleId)
        assertEquals("88.00", h.sink.records[0].fields["amount"])
    }

    @Test
    fun `金额解析容忍货币符与千分位`() {
        assertEquals(1200L, AutoBookkeepingEngine.parseAmountMinor("12.00"))
        assertEquals(123450L, AutoBookkeepingEngine.parseAmountMinor("1,234.50"))
        assertEquals(990L, AutoBookkeepingEngine.parseAmountMinor("¥9.9"))
        assertEquals(null, AutoBookkeepingEngine.parseAmountMinor("abc"))
        assertEquals(null, AutoBookkeepingEngine.parseAmountMinor(null))
    }

    companion object {
        /** 支付宝结果页 / 淘宝收银台（带确认锚点）/ 支付宝通知 三条规则的最小集。 */
        private val TEST_REGISTRY_TEXT = """
        {
          "rules": {
            "com.alipay.android.msp.ui.views.MspContainerActivity": [
              {
                "ruleId": 2000100,
                "rulePkgId": 2000016,
                "note": "支付宝-支付结果页",
                "locateAttribute": 1,
                "locateToken": "支付成功",
                "locateMaxTraverseCount": 20,
                "extractPattern": "contentDesc=支付成功[¥￥](\\d+(\\.\\d{1,2})?)",
                "extractFields": {"amount": 1}
              },
              {
                "ruleId": 2000098,
                "rulePkgId": 2000016,
                "note": "淘宝-收银台",
                "locateAttribute": 0,
                "locatePattern": "(使用密码|请输入支付密码)",
                "locateMaxTraverseCount": 20,
                "locateConfirm": {
                  "activity": "com.alibaba.android.ultron.vfw.weex2.highPerformance.widget.UltronTradeHybridActivity",
                  "locateAttribute": -1
                },
                "extractPattern": "contentDesc=支付金额(\\d+(\\.\\d{1,2})?)元",
                "extractFields": {"amount": 1}
              }
            ],
            "com.eg.android.AlipayGphone/android.app.Notification": [
              {
                "ruleId": 2000079,
                "rulePkgId": 2000016,
                "note": "支付宝-通知",
                "locateAttribute": 0,
                "locateToken": "支出",
                "locateMaxTraverseCount": 1,
                "extractPattern": "(\\d+(\\.\\d{1,2})?)元.+?支出",
                "extractFields": {"amount": 1}
              }
            ]
          }
        }
        """.trimIndent()
    }
}
