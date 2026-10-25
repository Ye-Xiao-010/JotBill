package com.jotbill.app.bookkeeping

/**
 * 节点树 → 页面 dump 文本（对齐小米 `ce.b` 的 e0()/f0()，格式逐行复刻）。
 *
 * dump 形如（4 空格缩进、叶子 ` />`、分支 ` >`+`</View>`、text/contentDesc
 * 无引号无转义、仅非空才输出）：
 *
 * ```
 * <View contentDesc=支付成功¥0.01 >
 *     <View text=xxx />
 * </View>
 * ```
 *
 * 内置 27 条规则的 extractPattern 全部按此格式书写 —— 本类的输出格式与小米
 * 有一丝偏差，规则就会整条失效，改动必须对照逆向源同步。
 *
 * 定位语义（对齐 f0）：locateAttribute=-1 时首节点即定位成功且不产出 dump；
 * 否则逐节点检查 locate 值（0=text / 1=contentDesc），token 包含或 pattern 命中
 * 即 located=true。locateMaxTraverseCount 只限制「搜索阶段」的节点预算 —— 一旦
 * located，剩余子树全部输出（小米原语义：预算防的是找不到锚点的超长遍历，不是
 * dump 截断）。
 */
object PageNodeDumper {

    /** dump 节点硬上限：安全熔断，正常规则预算（≤1000）永远到不了这里。 */
    const val MAX_DUMP_NODES = 4_000

    class DumpResult(
        val dump: String,
        val located: Boolean,
        val nodeCount: Int,
        val elapsedMs: Long
    )

    /** dump 页面并按 [rule] 的定位锚点判定是否命中。 */
    fun dump(root: PageNode?, rule: PageRule): DumpResult = dump(
        root = root,
        locateAttribute = rule.locateAttribute,
        maxTraverseCount = rule.locateMaxTraverseCount,
        locateMatches = rule::isLocatedBy
    )

    /** dump 页面并按二次确认锚点判定（对齐小米 k0()：确认页 DOM 遍历）。 */
    fun dumpForConfirm(root: PageNode?, anchor: PageRule.ConfirmAnchor): DumpResult = dump(
        root = root,
        locateAttribute = anchor.locateAttribute,
        maxTraverseCount = anchor.locateMaxTraverseCount,
        locateMatches = { value -> anchorLocates(anchor, value) }
    )

    private fun anchorLocates(anchor: PageRule.ConfirmAnchor, value: String?): Boolean {
        if (value == null) return false
        anchor.locateToken?.let { return value.contains(it) }
        anchor.locatePattern?.let { return it.matcher(value).find() }
        // 小米 Y()：token 与 pattern 皆空返回 false —— 确认锚点必须至少给一个。
        return false
    }

    private fun dump(
        root: PageNode?,
        locateAttribute: Int,
        maxTraverseCount: Int,
        locateMatches: (String?) -> Boolean
    ): DumpResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val state = State()
        val builder = StringBuilder()
        appendNode(root, builder, 0, locateAttribute, maxTraverseCount, locateMatches, state)
        if (builder.isNotEmpty()) {
            builder.deleteCharAt(builder.length - 1)
        }
        return DumpResult(
            dump = builder.toString(),
            located = state.located,
            nodeCount = state.count,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
        )
    }

    private class State {
        var located = false
        var count = 0
    }

    private fun appendNode(
        node: PageNode?,
        builder: StringBuilder,
        depth: Int,
        locateAttribute: Int,
        maxTraverseCount: Int,
        locateMatches: (String?) -> Boolean,
        state: State
    ) {
        if (node == null) {
            return
        }
        // 安全熔断（不足#7）：异常深的脏树（超出任何规则的预算语义）直接停，
        // 防主线程被一次 dump 拖死。资产规则预算最大 1000，此上限对正常规则零影响。
        if (state.count > MAX_DUMP_NODES) {
            return
        }
        if (locateAttribute == -1) {
            state.located = true
            return
        }
        state.count += 1
        if (!state.located && state.count > maxTraverseCount) {
            return
        }
        val indent = "    ".repeat(depth)
        builder.append(indent).append("<View")
        val text = node.text
        val desc = node.contentDescription
        val locateValue = when (locateAttribute) {
            0 -> text
            1 -> desc
            else -> null
        }
        if (locateMatches(locateValue)) {
            state.located = true
        }
        if (!text.isNullOrEmpty()) {
            builder.append(" text=").append(text)
        }
        if (!desc.isNullOrEmpty()) {
            builder.append(" contentDesc=").append(desc)
        }
        val childCount = node.childCount
        if (childCount <= 0) {
            builder.append(" />\n")
            return
        }
        builder.append(" >\n")
        for (i in 0 until childCount) {
            appendNode(
                node.childAt(i),
                builder,
                depth + 1,
                locateAttribute,
                maxTraverseCount,
                locateMatches,
                state
            )
        }
        builder.append(indent).append("</View>\n")
    }
}
