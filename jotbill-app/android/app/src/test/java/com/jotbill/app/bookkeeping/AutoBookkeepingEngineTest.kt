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

    /** 手动调度器：镜像宿主单槽语义，测试里用它驱动「虚拟时间」。 */
    class ManualScheduler : AutoBookkeepingEngine.RescanScheduler {
        val pending = mutableListOf<() -> Unit>()
        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            pending.clear()
            pending += action
        }

        override fun cancelAll() {
            pending.clear()
        }

        fun runNext() {
            if (pending.isNotEmpty()) {
                pending.removeAt(0).invoke()
            }
        }
    }

    private class Harness {
        val clock = mutableMapOf("now" to 1_000_000L)
        val sink = RecordingSink()
        val scheduler = ManualScheduler()
        val registry = AtomicReference<PageRuleRegistry?>(PageRuleRegistry.fromJsonText(TEST_REGISTRY_TEXT))
        val engine = AutoBookkeepingEngine(
            registryRef = registry,
            channels = { true },
            sink = sink,
            rescanScheduler = scheduler,
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
    fun `同包名同金额认领窗内的第二条记录按指纹丢弃`() {
        val h = Harness()
        // 第一条：结果页规则入账（认领登记）
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            alipayResultPage()
        }
        assertEquals(1, h.sink.records.size)
        // 第二条：几秒后支付宝「支出」通知命中通知规则（同金额 12.34）
        // —— 页面+通知双路径同一笔支付，引擎级指纹去重必须丢弃
        h.engine.onEvent(h.notificationEvent(text = "消费12.34元，已支出")) { null }
        assertEquals("同额同包名 15s 内不得二次交付", 1, h.sink.records.size)
        // 不同金额的通知不受影响
        h.engine.onEvent(h.notificationEvent(text = "消费56.78元，已支出")) { null }
        assertEquals(2, h.sink.records.size)
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

    @Test
    fun `静止页重扫兜底补上迟到的金额渲染`() {
        val h = Harness()
        // 可变页面容器：真实场景里 rootProvider 每次读的是实时活动窗口
        var page: FakePageNode = FakePageNode(null, null)
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            page
        }
        assertEquals("金额未渲染时不得交付", 0, h.sink.records.size)
        assertEquals("分析失败应排一次重扫", 1, h.scheduler.pending.size)
        // 1 秒后重扫执行，页面此时已渲染完整 —— P2-5 自愈语义
        page = alipayResultPage()
        h.scheduler.runNext()
        assertEquals(1, h.sink.records.size)
        assertEquals("成功后清空补扫队列", 0, h.scheduler.pending.size)
    }

    @Test
    fun `重扫预算用尽后静默停止`() {
        val h = Harness()
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity")) {
            FakePageNode(null, null)
        }
        repeat(20) { h.scheduler.runNext() }
        assertEquals("预算内不产生交付", 0, h.sink.records.size)
        // 8 次预算（事件路径 1 次 + 重扫 7 次）后队列必须清空
        assertEquals(0, h.scheduler.pending.size)
    }

    @Test
    fun `换窗后重扫预算复位`() {
        val h = Harness()
        // 窗口 A：宽 key（MMWebViewUI 类比）空页烧光全部预算
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity", windowId = 1)) {
            FakePageNode(null, null)
        }
        repeat(20) { h.scheduler.runNext() }
        assertEquals(0, h.scheduler.pending.size)
        // 窗口 B：新 32 事件建立新跟踪 —— 预算必须复位（审查 P2-1），
        // 静止页迟到渲染场景的重扫兜底不能被上一窗口的消耗连累
        var page: FakePageNode = FakePageNode(null, null)
        h.engine.onEvent(h.windowEvent("com.alipay.android.msp.ui.views.MspContainerActivity", windowId = 2)) {
            page
        }
        assertEquals("换窗后应重新排重扫", 1, h.scheduler.pending.size)
        page = alipayResultPage()
        h.scheduler.runNext()
        assertEquals(1, h.sink.records.size)
    }

    /**
     * P4：同包同额认领窗内，两侧订单号都非空且**不同** ⇒ 客观两笔真单，
     * 不得按同额指纹吞并；同订单号的重复记录仍被丢弃（幂等语义不回归）。
     */
    @Test
    fun `P4 同额不同订单号的两笔不按指纹吞并`() {
        val clockValue = mutableMapOf("now" to 1_000_000L)
        val sink = RecordingSink()
        val engine = AutoBookkeepingEngine(
            registryRef = AtomicReference(PageRuleRegistry.fromJsonText(ORDER_ID_REGISTRY_TEXT)),
            channels = { true },
            sink = sink,
            rescanScheduler = ManualScheduler(),
            clock = { clockValue["now"]!! }
        )

        fun orderPage(orderId: String) = FakePageNode(
            null, null,
            children = listOf(
                FakePageNode("订单号$orderId", null),
                FakePageNode("实付12.34元", null)
            )
        )

        fun window(id: Int) = AutoBookkeepingEngine.EventSnapshot(
            eventType = AutoBookkeepingEngine.EVENT_WINDOW_STATE_CHANGED,
            packageName = "com.test.shop",
            className = "com.test.Checkout",
            windowId = id,
            eventTime = clockValue["now"]!!,
            notificationText = null,
            notificationContentDesc = null
        )

        engine.onEvent(window(1)) { orderPage("ORDER-1") }
        assertEquals(1, sink.records.size)
        // P4 护栏：同额但订单号不同 ⇒ 第二笔必须放行（旧实现按同额指纹吞并）
        engine.onEvent(window(2)) { orderPage("ORDER-2") }
        assertEquals("不同订单号的两笔不得吞并", 2, sink.records.size)
        // 回归：同订单号的重复记录仍须按指纹丢弃
        engine.onEvent(window(3)) { orderPage("ORDER-1") }
        assertEquals("同订单号的重复记录仍须丢弃", 2, sink.records.size)
    }

    /** P11：服务重绑复位（resetTracking）不再丢弃二次确认挂起记录。 */
    @Test
    fun `P11 服务重绑复位不丢二次确认挂起`() {
        val h = Harness()
        val confirmClass =
            "com.alibaba.android.ultron.vfw.weex2.highPerformance.widget.UltronTradeHybridActivity"
        // 收银台密码页抽取成功，挂入二次确认（尚未交付）
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
        // 模拟无障碍服务解绑→重绑：宿主在 onServiceConnected 调用 resetTracking
        h.engine.resetTracking()
        // 确认页到达：P11 前挂起已被清空（漏记），现必须按队列消费交付
        h.engine.onEvent(h.windowEvent(confirmClass, windowId = 3)) { alipayResultPage() }
        assertEquals("复位后确认锚点命中仍应交付", 1, h.sink.records.size)
        assertEquals(2000098, h.sink.records[0].ruleId)
        assertEquals("88.00", h.sink.records[0].fields["amount"])
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

        /** P4 测试专用：抽取 orderNo + amount 的最小规则集。 */
        private val ORDER_ID_REGISTRY_TEXT = """
        {
          "rules": {
            "com.test.Checkout": [
              {
                "ruleId": 900001,
                "note": "测试-订单号规则",
                "locateAttribute": 0,
                "locateToken": "实付",
                "locateMaxTraverseCount": 20,
                "extractPattern": "text=订单号([A-Z0-9\\-]+).+?text=实付(\\d+(\\.\\d{1,2})?)元",
                "extractFields": {"orderNo": 1, "amount": 2}
              }
            ]
          }
        }
        """.trimIndent()
    }
}
