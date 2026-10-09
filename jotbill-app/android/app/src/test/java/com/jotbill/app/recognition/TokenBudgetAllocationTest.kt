package com.jotbill.app.recognition

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本与语义标记容量配额的回归测试。
 *
 * 京东结算页真机故障：dump 显示页面仅 154 个节点、"立即支付"排在遍历流第 151 位，
 * 节点遍历上限 MAX_NODE_COUNT=320 根本不会触发，按钮本应被访问到。
 *
 * 真正的原因是 addText 与 addSemantic 共用同一个 tokens 容量上限：每个节点最多
 * 贡献 2 个语义标记（node:类名 + view:资源id），154 个节点可产生约 308 个语义
 * token，容量在 320 处封顶后，排在后半段的"立即支付"文本被静默丢弃，
 * hasCheckoutActionSignal 恒为 false，页面永远判定为 UNKNOWN，
 * 自动结算截图、支付交接、AI 识别全链路失效。
 *
 * 本测试锁定"文本配额必须显著大于语义配额"这一不变量。节点树无法在 JVM 单测中
 * 构造，因此通过配额常量的数量关系来验证。
 */
class TokenBudgetAllocationTest {

    /**
     * 语义标记配额必须小于总容量，否则语义标记可占满全部槽位、挤掉文本。
     */
    @Test
    fun 语义标记配额必须小于总容量() {
        assertTrue(
            "语义标记配额必须严格小于总容量，否则会挤占文本槽位",
            AccessibilityPageSummaryCollectorBudget.semanticBudget <
                AccessibilityPageSummaryCollectorBudget.totalTokenCapacity
        )
    }

    /**
     * 单页最多产生 2 * MAX_NODE_COUNT 个语义标记，总容量需为文本预留余量。
     *
     * 故障场景即为语义标记数量逼近总容量：154 节点产生约 308 语义标记，
     * 若总容量仅 320，留给文本的槽位不足 12 个。
     */
    @Test
    fun 总容量需在最坏语义标记数之上为文本预留余量() {
        val worstCaseSemanticTokens =
            AccessibilityPageSummaryCollectorBudget.maxNodeCount * 2
        assertTrue(
            "总容量(${AccessibilityPageSummaryCollectorBudget.totalTokenCapacity})" +
                "必须大于最坏情况语义标记数($worstCaseSemanticTokens)，否则文本会被挤掉",
            AccessibilityPageSummaryCollectorBudget.totalTokenCapacity >
                worstCaseSemanticTokens
        )
    }

    /**
     * 语义标记配额不应超过最坏语义标记数，避免配额形同虚设。
     */
    @Test
    fun 语义标记配额需真正起到限制作用() {
        val worstCaseSemanticTokens =
            AccessibilityPageSummaryCollectorBudget.maxNodeCount * 2
        assertTrue(
            "语义标记配额应小于最坏情况语义标记数，否则限制不生效",
            AccessibilityPageSummaryCollectorBudget.semanticBudget <=
                worstCaseSemanticTokens
        )
    }
}
