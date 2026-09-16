package com.jotbill.app.recognition

/**
 * 页面金额（分）提取 + "最终金额 token"判据（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 口径（唯一出处）
 *
 * `docs/tdd/08_phase4_journey_spec.md` §3.1：**订单总额、单位分**；订单号可见时
 * 订单号优先为身份、金额降为校验字段（本文件只负责金额分量）。该口径同时服务
 * 版本 B 改造 C 的金额判据与 journey 锚点，属**共享基建**（`IMPL_PLAN` §0.1 战略裁决·甲）。
 *
 * ## 为什么与 Collector 同址放词表
 *
 * G6.3 要求：金额提取的判据必须与 [`AccessibilityPageSummaryCollector`] 的
 * "命中金额标签的 token 永不入易变池"（指纹通道）**同源**，否则会出现
 * "信号通道认、指纹通道不认"的双通道分裂（该问题已记为 P8）。
 * 因此**词表与判据只定义一次**（[FINAL_AMOUNT_TERMS] / [PageAmountExtractor.isFinalAmountToken]），
 * 两个通道各自引用，不得各写一份。
 *
 * > **⚠️ 同源当前是 no-op，但仍必须保持**：脱敏（`sensitiveNumberPattern`）会把
 * > 金额数字抹成 ` number `，于是 `¥128.00` 与 `¥55.70` 归一到**同一个** token 字符串，
 * > 易变追踪器本来就判不出差别 ⇒ 把"货币形态"并入判据在今日**不改变任何输出**。
 * > 保留同源是为**结构性护栏**：一旦后人调整脱敏时机（提前/移除），两通道判据会
 * > 立刻分叉，届时本护栏是唯一防线。
 */

/**
 * 最终金额标签词表。
 *
 * 命中即认为所在区域是"应付金额"语义。原定义在 `AccessibilityPageSummaryCollector`，
 * 为与判据同址（见文件头注释）迁入本文件。
 */
internal val FINAL_AMOUNT_TERMS = listOf(
    "应付",
    "实付",
    "订单金额",
    "订单总额",
    "优惠后合计",
    "优惠后金额",
    "优惠后应付",
    "待支付",
    "待付款",
    "需付款",
    "付款金额",
    // 拼多多确认订单页常见金额标签：折后/券后/大促价/到手价。
    "折后",
    "券后",
    "大促价",
    "到手价",
    "拼单价",
    "单买价"
)

/**
 * 货币金额的数字形态（不含符号）。
 *
 * 中文页面约定：`,`/`，` 只作**千分位**分组，`.` 才是小数点。
 * 前后 `(?<![0-9])` / `(?![0-9])` 保证不会**截断**更长的数字串
 * （如 `12345` 不匹配 ⇒ 提取失败 ⇒ 保守沿用，而不是错误提取成 `123`）。
 */
private const val AMOUNT_NUMBER = """(?<![0-9])[0-9]{1,3}(?:[,，][0-9]{3})*(?:\.[0-9]{1,2})?(?![0-9])"""

/** `¥128.00` / `￥1,299.00` / `$55.70` 形态。 */
private val PREFIXED_AMOUNT_PATTERN = Regex("""[¥￥$＄€£]\s*($AMOUNT_NUMBER)""")

/** `128元` / `1,299.00 元` 形态（`元` 与货币符号在脱敏中同样归为 currency）。 */
private val SUFFIXED_AMOUNT_PATTERN = Regex("""($AMOUNT_NUMBER)\s*元""")

internal object PageAmountExtractor {

    /**
     * 该**原始** token 是否携带"最终金额"语义。
     *
     * 单一出处，供两条通道共用：
     * - 信号/提取通道（[PageAmountAccumulator]）：判定"带标签金额候选"；
     * - 指纹通道（Collector 的易变检测豁免）：命中即永不入易变池。
     *
     * ⚠️ 入参必须是**未脱敏**的原始节点文本 —— 脱敏后数字已被抹成 ` number `。
     */
    fun isFinalAmountToken(rawValue: String): Boolean =
        FINAL_AMOUNT_TERMS.any { rawValue.contains(it) } ||
            currencyAmounts(rawValue).isNotEmpty()

    /** 从一段原始文本中取出所有货币金额（分）。 */
    fun currencyAmounts(rawValue: String): List<Long> {
        if (rawValue.isBlank()) return emptyList()
        val found = ArrayList<Long>(2)
        for (match in PREFIXED_AMOUNT_PATTERN.findAll(rawValue)) {
            parseMinor(match.groupValues[1])?.let { found += it }
        }
        for (match in SUFFIXED_AMOUNT_PATTERN.findAll(rawValue)) {
            parseMinor(match.groupValues[1])?.let { found += it }
        }
        return found
    }

    /** `"1,299.00"` → `129900`；越界或非法返回 null。 */
    private fun parseMinor(number: String): Long? {
        val plain = number.replace(",", "").replace("，", "")
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "").padEnd(2, '0').take(2).ifEmpty { "00" }
        val wholeValue = whole.toLongOrNull() ?: return null
        val fractionValue = fraction.toLongOrNull() ?: return null
        val minor = wholeValue * 100 + fractionValue
        return minor.takeIf { it in 1L..MAX_SAFE_MINOR_UNIT }
    }
}

/**
 * 一次页面采集期间的金额候选累积器。
 *
 * 逐节点 [observe]（**原始**文本），采集结束后 [resolveFinalAmountMinor] 收口。
 *
 * ## 歧义判据（保守，`IMPL_PLAN` §2.3 C-2 明列"多金额歧义 ⇒ 无法判定"）
 *
 * | 情形 | 结果 |
 * |---|---|
 * | 无货币金额候选 | `null`（无金额） |
 * | 去重后仅 1 个值 | 该值 |
 * | 去重后 ≥2 个不同值 | `null`（**歧义**） |
 *
 * **分级**：带 [FINAL_AMOUNT_TERMS] 标签的候选优先；只有在**无任何带标签候选**时
 * 才采用无标签候选（PDD 无标签结算页兜底）。⇒ 真实页常见的
 * `实付¥128.00` + `优惠¥10.00` 中后者无标签，**不构成歧义**。
 *
 * 🔴 **保守原则（`IMPL_PLAN` §2.3 R3）**：任何"无法判定"一律返回 `null`，
 * 由调用方**沿用旧会话** —— 宁可少新建，不可误新建。
 *
 * > ⚠️ **已裁定不做标签优先级**（用户 2026-09-16）：`订单金额¥150.00` 与
 * > `实付¥128.00` **并存**（两者都在词表内）⇒ 本类返回 `null` ⇒ 沿用旧会话。
 * > 这是**照规格的保守行为**；提高命中率必须用**显式分组**，**不得**靠
 * > `FINAL_AMOUNT_TERMS` 的列表顺序表达优先级 —— 那会让"后人调整词表顺序"
 * > 静默改变行为，是本项目已多次吃亏的同类隐患。该页型已登记为真机观察点。
 */
internal class PageAmountAccumulator {

    private val amounts = ArrayList<Long>(4)

    /** 出现在**带最终金额标签**的文本里的金额。 */
    private val labeledAmounts = LinkedHashSet<Long>()

    /** 页面是否出现过货币金额（非严格：任一候选即可）。 */
    val hasCurrencyAmount: Boolean
        get() = amounts.isNotEmpty()

    /**
     * 喂入一个节点的**原始**文本。
     *
     * @param rawValue 脱敏**之前**的节点 text/contentDescription/hint/tooltip。
     */
    fun observe(rawValue: String) {
        val found = PageAmountExtractor.currencyAmounts(rawValue)
        if (found.isEmpty()) {
            return
        }
        amounts += found
        if (FINAL_AMOUNT_TERMS.any { rawValue.contains(it) }) {
            labeledAmounts += found
        }
    }

    /**
     * 收口：本页"订单总额"（分）。
     *
     * @return null 表示**无法判定**（无金额或多金额歧义）⇒ 调用方必须保守沿用旧会话。
     */
    fun resolveFinalAmountMinor(): Long? {
        if (labeledAmounts.isNotEmpty()) {
            return labeledAmounts.singleOrNull()
        }
        val distinct = amounts.distinct()
        return distinct.singleOrNull()
    }
}
