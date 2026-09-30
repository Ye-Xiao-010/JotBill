package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金额取证设施的纯函数单测（2026-09-29）。
 *
 * ## 为什么存在
 *
 * 真机回归（2026-09-29）挖出：`finalAmountMinor` 恒为 null ⇒
 * `CheckoutPurchaseIntentCoordinator.attributePaymentResultLocked` 归属判定恒 0 命中
 * ⇒ 同一笔支付在待确认中心产生多条记录（2 笔支付 → 5 条待确认）。
 *
 * `finalAmountMinor` 返回 null 有三种成因，**修法性质完全不同**：
 * 带标签池有多个不同值（裁定 (A) 的正确行为）/ 带标签池空（提取通道的实现缺口）/
 * 两者都空。判据与格式化逻辑若不可测，真机日志出不来可复核的依据。
 *
 * ## 刻意不覆盖什么
 *
 * ⚠️ **不测 `PageAmountAccumulator.resolveFinalAmountMinor` 的 null 行为。**
 * 它是本轮的**取证对象**，不是已确认的正确行为；把它钉死等于给未来的修法
 * （假设 A / 假设 B）提前上锁。该判据的既有行为由 [PageAmountExtractorTest] 覆盖，
 * 本文件只测**新增的**取证代码。
 */
class PageAmountDiagnosticsTest {

    // ------------------------------------- 信号通道：分支判定（取证的核心）

    @Test
    fun labelTermBranchWinsFirst() {
        assertEquals(
            FinalAmountSignalEvidence.LABEL_TERM,
            finalAmountSignalEvidence(text = "实付 currency number .00 提交订单", hasCurrencyAmount = true)
        )
    }

    /**
     * 拼多多无标签结算页走这条 —— **信号通道的兜底，提取通道没有对应物**。
     *
     * 该分支成立而金额提取仍可能为 null，正是本轮要取证的缺口。
     */
    @Test
    fun actionWithCurrencyFallbackIsItsOwnBranch() {
        assertEquals(
            FinalAmountSignalEvidence.ACTION_WITH_CURRENCY,
            finalAmountSignalEvidence(text = "提交订单 currency number .00", hasCurrencyAmount = true)
        )
    }

    /** 动作词在但页面无货币金额 ⇒ 不得落到 ACTION_WITH_CURRENCY（严格 `&&`）。 */
    @Test
    fun actionWithoutCurrencyDoesNotHitTheAmountBranch() {
        assertEquals(
            FinalAmountSignalEvidence.NONE,
            finalAmountSignalEvidence(text = "提交订单 收货地址", hasCurrencyAmount = false)
        )
    }

    @Test
    fun subtotalWithOrderFallbackIsThirdBranch() {
        assertEquals(
            FinalAmountSignalEvidence.SUBTOTAL_WITH_ORDER,
            finalAmountSignalEvidence(text = "订单合计 收货地址", hasCurrencyAmount = false)
        )
    }

    @Test
    fun noSignalResolvesToNone() {
        assertEquals(
            FinalAmountSignalEvidence.NONE,
            finalAmountSignalEvidence(text = "猜你喜欢 推荐 商品详情", hasCurrencyAmount = false)
        )
    }

    /**
     * 等价性护栏：委托重构**不得**改变门禁布尔。
     *
     * `hasFinalAmountSignal` 已改为 `evidence != NONE`，故两者按构造恒等；
     * 此例的作用是钉住"重构后旧的布尔判据仍产出相同结果"。
     */
    @Test
    fun booleanSignalStaysEquivalentToEvidence() {
        val samples = listOf(
            "实付 currency number .00 提交订单" to true,
            "提交订单 currency number .00" to true,
            "提交订单 收货地址" to false,
            "订单合计 收货地址" to false,
            "猜你喜欢 推荐 商品详情" to false,
            "立即支付 合计 优惠后" to false
        )
        for ((text, currency) in samples) {
            val evidence = finalAmountSignalEvidence(text, currency)
            assertEquals(
                "evidence 与布尔必须一致: text=$text",
                evidence != FinalAmountSignalEvidence.NONE,
                hasFinalAmountSignal(text, currency)
            )
        }
    }

    // ------------------------------------------------- 格式化：字段不得丢

    @Test
    fun formatEmitsEveryEvidenceField() {
        val line = PageAmountDiagnostics.format(
            shoppingPackage = "com.xunmeng.pinduoduo",
            pageRole = CheckoutPageRole.CHECKOUT,
            windowClass = "com.xunmeng.pinduoduo.ui.activity.HomeActivity",
            visitedNodes = 154,
            tokenCount = 61,
            textDropped = false,
            labeledTerms = listOf("实付", "订单金额"),
            labeledAmounts = listOf(1176L),
            unlabeledAmounts = listOf(1176L, 1362L),
            finalAmountMinor = 1176L,
            evidence = FinalAmountSignalEvidence.LABEL_TERM,
            termHit = true,
            actionHit = true,
            currencyHit = true,
            subtotalHit = false
        )
        assertTrue("应带固定前缀", line.startsWith(PageAmountDiagnostics.LINE_PREFIX))
        for (fragment in listOf(
            "pkg=com.xunmeng.pinduoduo",
            "role=CHECKOUT",
            "nodes=154",
            "tokens=61",
            "dropped=false",
            "labeledTerms=[实付,订单金额]",
            "labeled=[1176]",
            "unlabeled=[1176,1362]",
            "final=1176",
            "signal=LABEL_TERM",
            "term=true",
            "action=true",
            "currency=true",
            "subtotal=false"
        )) {
            assertTrue("日志行缺少片段: $fragment\n实际: $line", line.contains(fragment))
        }
    }

    /**
     * 判读指纹 A：标签在页面上、带标签池却空。
     *
     * 这组合一旦在真机出现，即坐实"标签与金额分处不同节点"⇒ 提取通道的实现缺口
     * （⚠️ 推断，本例只保证该组合**能被日志如实表达**，不断言它在真机成立）。
     */
    @Test
    fun formatExposesLabelPresentButLabeledPoolEmpty() {
        val line = PageAmountDiagnostics.format(
            shoppingPackage = "com.xunmeng.pinduoduo",
            pageRole = CheckoutPageRole.CHECKOUT,
            windowClass = "HomeActivity",
            visitedNodes = 154,
            tokenCount = 61,
            textDropped = false,
            labeledTerms = listOf("实付", "订单金额"),
            labeledAmounts = emptyList(),
            unlabeledAmounts = listOf(1176L, 1362L),
            finalAmountMinor = null,
            evidence = FinalAmountSignalEvidence.LABEL_TERM,
            termHit = true,
            actionHit = true,
            currencyHit = true,
            subtotalHit = false
        )
        assertTrue(line.contains("labeledTerms=[实付,订单金额]"))
        assertTrue(line.contains("labeled=[]"))
        assertTrue(line.contains("final=null"))
    }

    /** 空集合输出为 `[]`、null 输出为字面量 `null` —— 避免出现空串被误读成"没这个字段"。 */
    @Test
    fun formatUsesExplicitEmptyAndNullMarkers() {
        val line = PageAmountDiagnostics.format(
            shoppingPackage = "pkg",
            pageRole = CheckoutPageRole.UNKNOWN,
            windowClass = null,
            visitedNodes = 3,
            tokenCount = 0,
            textDropped = true,
            labeledTerms = emptyList(),
            labeledAmounts = emptyList(),
            unlabeledAmounts = emptyList(),
            finalAmountMinor = null,
            evidence = FinalAmountSignalEvidence.NONE,
            termHit = false,
            actionHit = false,
            currencyHit = false,
            subtotalHit = false
        )
        assertTrue(line.contains("win=<null>"))
        assertTrue(line.contains("labeledTerms=[]"))
        assertTrue(line.contains("labeled=[]"))
        assertTrue(line.contains("unlabeled=[]"))
        assertTrue(line.contains("final=null"))
        assertTrue(line.contains("dropped=true"))
    }

    // --------------------------------------------------------- 去重键

    /**
     * 签名护栏：去重键**不得**接受每帧都变的字段（节点数 / 令牌数）。
     *
     * `collect` 由逐内容变更事件驱动，节点数每帧都变；带进去等于没有去重，
     * 一次结算页停留会把 diag 日志刷爆。本例守住签名，防止后人"顺手补全字段"时
     * 悄悄把去重废掉 —— 这类改动没有任何功能测试会红。
     */
    @Test
    fun dedupKeySignatureCarriesNoPerFrameCounter() {
        val dedupKey = PageAmountDiagnostics::class.java.declaredMethods
            .single { it.name == "dedupKey" }
        val parameterCount = dedupKey.parameterCount
        assertEquals(
            "去重键新增了字段：必须先确认它不是逐帧变化的值（节点数/令牌数）" +
                "，否则一次结算页停留会打满整屏日志。当前参数=$parameterCount",
            9,
            parameterCount
        )
    }

    /** 金额或证据一变必须补打新行 —— 去重不得把"金额变了"这一关键信号吞掉。 */
    @Test
    fun dedupKeyChangesWhenAmountOrEvidenceChanges() {
        assertNotEquals(dedupKey(), dedupKey(finalAmountMinor = 1362L))
        assertNotEquals(
            dedupKey(),
            dedupKey(evidence = FinalAmountSignalEvidence.ACTION_WITH_CURRENCY)
        )
        assertNotEquals(dedupKey(), dedupKey(textDropped = true))
        assertNotEquals(dedupKey(), dedupKey(pageRole = CheckoutPageRole.PRODUCT_DETAIL))
        assertNotEquals(dedupKey(), dedupKey(unlabeledAmounts = listOf(1176L, 1362L, 1500L)))
    }

    /** 同一状态下键必须稳定 —— 否则同一次采集的多次事件会各打一行。 */
    @Test
    fun dedupKeyIsStableForIdenticalState() {
        assertEquals(dedupKey(), dedupKey())
    }

    // ------------------------------------------------------ 诊断只读出口

    /**
     * 只读出口必须如实反映累积器的两池状态。
     *
     * 注意断言的形状：`已优惠¥3.00` **不含任何 [FINAL_AMOUNT_TERMS] 词**
     * （词表是 `优惠后合计/优惠后金额/优惠后应付`，不是裸 `优惠`）⇒ 300 只进无标签池。
     * 只断言"读数能看到"，**不断言 resolve 的结果** —— 那是取证对象不是裁定。
     */
    @Test
    fun diagnosticAccessorsExposeAccumulatorInternals() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("实付¥11.76")
        accumulator.observe("订单金额¥11.76")
        accumulator.observe("已优惠¥3.00")
        assertEquals(
            "同值的两个标签只留一个（labeledAmounts 本身即 LinkedHashSet）",
            listOf(1176L),
            accumulator.labeledAmountsForDiagnostics()
        )
        assertEquals(
            "无标签候选 = 全部金额去重（含带标签的那些）",
            listOf(1176L, 300L),
            accumulator.unlabeledAmountsForDiagnostics()
        )
    }

    /** 标签与金额分处两个节点（假设 A 的形态）⇒ 带标签池空，无标签池有两值。 */
    @Test
    fun labelAndAmountInSeparateNodesLeaveLabeledPoolEmpty() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("实付")
        accumulator.observe("订单金额")
        accumulator.observe("¥13.62")
        accumulator.observe("¥15.00")
        assertTrue("带标签池应为空", accumulator.labeledAmountsForDiagnostics().isEmpty())
        assertEquals(listOf(1362L, 1500L), accumulator.unlabeledAmountsForDiagnostics())
    }

    @Test
    fun emptyPageYieldsEmptySnapshots() {
        val accumulator = PageAmountAccumulator()
        assertFalse(accumulator.hasCurrencyAmount)
        assertTrue(accumulator.labeledAmountsForDiagnostics().isEmpty())
        assertTrue(accumulator.unlabeledAmountsForDiagnostics().isEmpty())
    }

    // ------------------------------------------------------- 私有工具

    private fun finalAmountSignalEvidence(
        text: String,
        hasCurrencyAmount: Boolean
    ): FinalAmountSignalEvidence =
        AccessibilityPageSummaryCollector.finalAmountSignalEvidence(text, hasCurrencyAmount)

    private fun hasFinalAmountSignal(text: String, hasCurrencyAmount: Boolean): Boolean =
        AccessibilityPageSummaryCollector.hasFinalAmountSignal(text, hasCurrencyAmount)

    private fun dedupKey(
        textDropped: Boolean = false,
        pageRole: CheckoutPageRole = CheckoutPageRole.CHECKOUT,
        labeledAmounts: List<Long> = listOf(1176L),
        unlabeledAmounts: List<Long> = listOf(1176L, 1362L),
        finalAmountMinor: Long? = 1176L,
        evidence: FinalAmountSignalEvidence = FinalAmountSignalEvidence.LABEL_TERM
    ): String = PageAmountDiagnostics.dedupKey(
        shoppingPackage = "com.xunmeng.pinduoduo",
        windowClass = "HomeActivity",
        pageRole = pageRole,
        textDropped = textDropped,
        labeledTerms = listOf("实付"),
        labeledAmounts = labeledAmounts,
        unlabeledAmounts = unlabeledAmounts,
        finalAmountMinor = finalAmountMinor,
        evidence = evidence
    )
}
