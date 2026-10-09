package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面金额（分）提取单测 —— 批 V-2 改造 C / Y4 判据 1 的前置基建。
 *
 * ## 为什么必须存在
 *
 * Y4 判据 1 的语义是"金额不等 ⇒ 不沿用旧会话"，而**误新建会话**会把已截到的图
 * 在提交订单前清零（真机曾致支付交接只剩 `NO_BUFFERED_SNAPSHOT`）⇒ 提取必须可测、
 * 且"无法判定"必须保守（返回 null ⇒ 沿用）。
 *
 * ## P2-8 的等价信号（不依赖真机）
 *
 * `sensitiveNumberPattern` 把 ≥3 位连续数字抹成 ` number `，`¥128.00` 因此变成
 * `currency  number .00`。修前 [AccessibilityPageSummaryCollector.hasFinalAmountSignal]
 * 从**已脱敏文本**里找数字 ⇒ 找不到 ⇒ `MISSING_FINAL_AMOUNT` ⇒ PDD ≥¥100 订单整链失效。
 * 本文件用「原始文本提取 + 生产判据直接断言」复现该链路（改前该断言为 false）。
 */
class PageAmountExtractorTest {

    // ------------------------------------------------------------ 提取

    @Test
    fun extractsPrefixedAmount() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("¥128.00")
        assertTrue(accumulator.hasCurrencyAmount)
        assertEquals(12800L, accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun extractsAmountWithThousandsSeparator() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("￥1,299.00")
        assertEquals(129900L, accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun extractsYuanSuffixedAmount() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("128元")
        assertEquals(12800L, accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun extractsTwoDigitAmountToo() {
        // 修复不得只覆盖 ≥3 位：两位金额原本就工作，必须保持
        val accumulator = PageAmountAccumulator()
        accumulator.observe("¥55.70")
        assertEquals(5570L, accumulator.resolveFinalAmountMinor())
    }

    /**
     * 截断保护：`12345` 不得被切成 `123`。
     *
     * 无分组分隔的 5 位数不被识别 ⇒ 返回 null ⇒ 调用方保守沿用旧会话
     * （错误提取比提取不到危险得多）。
     */
    @Test
    fun doesNotTruncateLongUnseparatedNumber() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("¥12345")
        assertFalse("不得把 12345 截成 123", accumulator.hasCurrencyAmount)
        assertNull(accumulator.resolveFinalAmountMinor())
    }

    // ------------------------------------------------------------ 歧义

    @Test
    fun labeledAmountWinsOverUnlabeledDiscount() {
        // 真实页常见形态：实付有标签、优惠金额无标签 ⇒ 后者不构成歧义
        val accumulator = PageAmountAccumulator()
        accumulator.observe("实付¥128.00")
        accumulator.observe("已优惠¥10.00")
        assertEquals(12800L, accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun repeatedSameValueIsNotAmbiguous() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("应付¥128.00")
        accumulator.observe("待支付¥128.00")
        assertEquals(12800L, accumulator.resolveFinalAmountMinor())
    }

    /**
     * 裁定 (A)：两个**带标签**的不同金额 ⇒ 歧义 ⇒ null ⇒ 保守沿用。
     *
     * 该页型（`订单金额` 与 `实付` 并存）已登记为**真机观察点**。提高命中率必须用
     * 显式分组，**不得**用词表顺序表达优先级（会让后人调整词表顺序时静默改变行为）。
     */
    @Test
    fun ambiguousLabeledAmountsResolveToNull() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("订单金额¥150.00")
        accumulator.observe("实付¥128.00")
        assertNull(accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun ambiguousUnlabeledAmountsResolveToNull() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("¥128.00")
        accumulator.observe("¥150.00")
        assertTrue("金额确实存在（信号通道用得到）", accumulator.hasCurrencyAmount)
        assertNull("多金额歧义 ⇒ 无法判定 ⇒ 沿用旧会话", accumulator.resolveFinalAmountMinor())
    }

    @Test
    fun noAmountOnPageResolvesToNull() {
        val accumulator = PageAmountAccumulator()
        accumulator.observe("提交订单")
        accumulator.observe("收货地址")
        assertFalse(accumulator.hasCurrencyAmount)
        assertNull(accumulator.resolveFinalAmountMinor())
    }

    // ------------------------------------------- 判据同源（P8 护栏）

    @Test
    fun finalAmountTokenPredicateCoversLabelAndCurrencyForm() {
        assertTrue("带标签", PageAmountExtractor.isFinalAmountToken("实付"))
        assertTrue("纯货币金额（PDD 无标签页）", PageAmountExtractor.isFinalAmountToken("¥128.00"))
        assertFalse("普通动作文案不是金额 token", PageAmountExtractor.isFinalAmountToken("提交订单"))
    }

    @Test
    fun finalAmountTermsStillContainCoreLabels() {
        // 词表为两条通道共用；此处钉住核心标签，防迁移/顺手改动
        for (term in listOf("实付", "应付", "订单金额", "待支付", "券后")) {
            assertTrue("词表应含 $term", FINAL_AMOUNT_TERMS.contains(term))
        }
    }

    // ------------------------------------------- P2-8 端到端等价信号

    /**
     * **P2-8 核心用例**：≥¥100 的 DPD 无标签订单，脱敏后仍必须判出最终金额信号。
     *
     * 链路复刻：`addText` 把 `128` 抹成 ` number `（`:637`），`normalizeToken` 再把
     * `¥` 抹成 `currency` ⇒ 已脱敏文本为 `提交订单 currency  number .00`。
     *
     * 判别性：将 [AccessibilityPageSummaryCollector.hasFinalAmountSignal] 的兜底分支
     * 退回"从已脱敏文本找数字" ⇒ 本用例的第二条 assert **必红**。
     */
    @Test
    fun pddOrderAtOrAbove100YuanKeepsFinalAmountSignalAfterDesensitization() {
        val desensitizedPageText = "提交订单 currency  number .00"

        val accumulator = PageAmountAccumulator()
        accumulator.observe("提交订单")
        accumulator.observe("¥128.00")

        assertTrue("≥¥100 金额必须能从原始文本提取", accumulator.hasCurrencyAmount)
        assertEquals(12800L, accumulator.resolveFinalAmountMinor())

        assertTrue(
            "P2-8：脱敏抹掉数字后，最终金额信号必须仍然成立（否则 MISSING_FINAL_AMOUNT）",
            AccessibilityPageSummaryCollector.hasFinalAmountSignal(
                text = desensitizedPageText,
                hasCurrencyAmount = accumulator.hasCurrencyAmount
            )
        )
        // 对照：这正是修前的口径（只看已脱敏文本）⇒ 必须为 false，证明上条断言有判别性
        assertFalse(
            "对照：仅凭已脱敏文本找不到任何数字，这就是 P2-8 的失效形态",
            AccessibilityPageSummaryCollector.hasFinalAmountSignal(
                text = desensitizedPageText,
                hasCurrencyAmount = false
            )
        )
    }

    /** 带标签的结算页（如"实付"）在修复前后都必须成立 —— 回归护栏。 */
    @Test
    fun labeledCheckoutPageKeepsSignal() {
        assertTrue(
            AccessibilityPageSummaryCollector.hasFinalAmountSignal(
                text = "实付 currency  number .00",
                hasCurrencyAmount = false
            )
        )
    }
}
