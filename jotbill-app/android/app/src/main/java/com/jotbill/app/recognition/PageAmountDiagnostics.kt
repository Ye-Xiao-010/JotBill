package com.jotbill.app.recognition

/**
 * 结算页「金额」两条通道的取证格式化（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么存在
 *
 * 2026-09-29 真机回归：用户支付 2 笔，待确认中心出现 5 条。定位到
 * `CheckoutPurchaseIntentCoordinator.attributePaymentResultLocked` 的
 * `val pageAmount = context.finalAmountMinor ?: return@filter false`
 * —— `finalAmountMinor` 恒为 null ⇒ 归属判定恒 0 命中。
 *
 * ## 为什么必须落中间态而不是只看结果
 *
 * `finalAmountMinor` 返回 null 有**三种成因**，修法性质完全不同：
 *
 * | 中间态 | 含义 |
 * |---|---|
 * | 带标签池**有 2 个不同值** | 页面同时出现两个带标签的**不同**金额（如 `订单金额¥15.00` + `实付¥11.76`）⇒ 裁定 (A) 的**正确行为**，要提高命中率须走新裁定流程 |
 * | 带标签池**空** + 无标签候选 ≥2 个 | 标签与金额分处不同节点，无法在同一节点内配对 ⇒ 提取通道的**实现缺口** |
 * | 两个池都空 | 页面根本没有可提取的货币金额（另有第三种可能：文本被容量截断，`dropped=true`） |
 *
 * 只打 `finalAmountMinor=null` 无法区分三者，故本类把中间态全量落盘。
 *
 * ## 与信号通道的关系（⚠️ 推断，待真机证据）
 *
 * [AccessibilityPageSummaryCollector.hasFinalAmountSignal] 有一条
 * 「结算动作词 + 页面存在货币金额」的无标签兜底（PDD 无标签结算页靠它过门禁），
 * 而金额**提取**通道（[PageAmountAccumulator.resolveFinalAmountMinor]）没有对应兜底
 * ⇒ 门禁放行 ≠ 提取得出数值。**这是推断，本类只负责把它变成可观测数据。**
 *
 * ## 边界
 *
 * - **纯格式化，不做任何判定** —— 判据只有 [AccessibilityPageSummaryCollector] 一处。
 * - **不含敏感信息**：只输出金额（分）、金额标签词、页面角色；不输出订单号/地址/账号。
 * - **不改变行为**：调用方仅在诊断分支调用本类。
 */
internal object PageAmountDiagnostics {

    /** 日志行前缀；`Select-String` 抓这个串即可。 */
    const val LINE_PREFIX = "AmountDiag"

    /**
     * 拼出一行完整的金额取证信息。
     *
     * 单行输出（便于 grep / 逐行判读）。各字段以 ` | ` 分段，**金额单位为分**。
     *
     * @param labeledTerms 页面上实际出现的 [FINAL_AMOUNT_TERMS] 词（**已脱敏文本**里命中）。
     *   与 [labeledAmounts] 配合可判定"标签在页面上、却没进带标签池"——那正是
     *   "标签与金额分处不同节点"（提取通道缺口）的指纹。
     * @param evidence [hasFinalAmountSignal] 命中的分支；与 `term/action/currency/subtotal`
     *   四个入参一起构成该判据的**完整可复核依据**。
     */
    fun format(
        shoppingPackage: String,
        pageRole: CheckoutPageRole,
        windowClass: String?,
        visitedNodes: Int,
        tokenCount: Int,
        textDropped: Boolean,
        labeledTerms: List<String>,
        labeledAmounts: List<Long>,
        unlabeledAmounts: List<Long>,
        finalAmountMinor: Long?,
        evidence: FinalAmountSignalEvidence,
        termHit: Boolean,
        actionHit: Boolean,
        currencyHit: Boolean,
        subtotalHit: Boolean
    ): String = buildString {
        append(LINE_PREFIX)
        append(" pkg=").append(shoppingPackage)
        append(" role=").append(pageRole.storageValue)
        append(" win=").append(windowClass.orEmpty().ifBlank { "<null>" })
        append(" nodes=").append(visitedNodes)
        append(" tokens=").append(tokenCount)
        append(" dropped=").append(textDropped)
        append(" | labeledTerms=").append(joinOrNone(labeledTerms))
        append(" labeled=").append(joinOrNone(labeledAmounts))
        append(" unlabeled=").append(joinOrNone(unlabeledAmounts))
        append(" final=").append(finalAmountMinor?.toString() ?: "null")
        append(" 单位=分")
        append(" | signal=").append(evidence.name)
        append(" term=").append(termHit)
        append(" action=").append(actionHit)
        append(" currency=").append(currencyHit)
        append(" subtotal=").append(subtotalHit)
    }

    /**
     * 去重键：相同页面状态下重复采集只打一行，避免 [collect] 的逐事件调用把日志刷爆。
     *
     * 刻意**不包含** `visitedNodes` / `tokenCount` —— 节点数每帧都变，带进去等于没有去重。
     * 调用方为 [AccessibilityPageSummaryCollector.collect]，其**逐内容变更事件**驱动。
     */
    fun dedupKey(
        shoppingPackage: String,
        windowClass: String?,
        pageRole: CheckoutPageRole,
        textDropped: Boolean,
        labeledTerms: List<String>,
        labeledAmounts: List<Long>,
        unlabeledAmounts: List<Long>,
        finalAmountMinor: Long?,
        evidence: FinalAmountSignalEvidence
    ): String = listOf(
        shoppingPackage,
        windowClass.orEmpty(),
        pageRole.storageValue,
        textDropped.toString(),
        joinOrNone(labeledTerms),
        joinOrNone(labeledAmounts),
        joinOrNone(unlabeledAmounts),
        finalAmountMinor?.toString() ?: "null",
        evidence.name
    ).joinToString("|")

    private fun joinOrNone(values: List<*>): String =
        if (values.isEmpty()) "[]" else values.joinToString(",", "[", "]")
}
