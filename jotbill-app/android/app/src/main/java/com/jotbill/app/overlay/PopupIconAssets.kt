package com.jotbill.app.overlay

import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jotbill.app.R

/**
 * 弹窗 chips 的图标资产映射（2026-10-25，用户裁定：分类/账户接入 lucide 与品牌图标）。
 *
 * 与 RN 侧 `CategoryIcon`（0a80e72）同一设计语言 —— lucide 优先、emoji 回退：
 * - 分类：按待确认行的 `icon` 字符串匹配 lucide 图标名（后端种子 0a80e72 的 13 枚）。
 *   emoji（Android 本地首启种子 `DefaultDataInitializer` / 自定义分类）无矢量资产，
   原样文字显示 —— 数据同步到 lucide 名后自动升级，无需改代码；
 * - 账户：品牌图标优先（微信/支付宝，按账户 id 精确、名称包含匹配，**固定品牌色不随选中变色**），
 *   其次按 `subtype` 给线性 lucide（cash→banknote / e_wallet→wallet / credit_card→credit-card），
 *   自定义账户沿用 emoji 文字。
 *
 * 矢量资产由项目依赖 `lucide-react-native@0.563.0` 的图标数据生成
 * （viewport 24、strokeWidth 2.5，与 RN 侧同源同参），运行时经 [apply]
 * 按 TextView 文字色着色 —— 选中态变色时由 selectCategory/selectAccount 重着色。
 */
internal object PopupIconAssets {

    /** 与后端种子（0a80e72 categories.service.ts）的 lucide 图标名一一对应。 */
    private val categoryIcons = mapOf(
        "utensils" to R.drawable.ic_lucide_utensils,
        "bus" to R.drawable.ic_lucide_bus,
        "shopping-bag" to R.drawable.ic_lucide_shopping_bag,
        "gamepad-2" to R.drawable.ic_lucide_gamepad_2,
        "pill" to R.drawable.ic_lucide_pill,
        "book-open" to R.drawable.ic_lucide_book_open,
        "house" to R.drawable.ic_lucide_house,
        "circle-ellipsis" to R.drawable.ic_lucide_circle_ellipsis,
        "banknote" to R.drawable.ic_lucide_banknote,
        "trophy" to R.drawable.ic_lucide_trophy,
        "chart-line" to R.drawable.ic_lucide_chart_line,
        "laptop" to R.drawable.ic_lucide_laptop,
        "circle-dollar-sign" to R.drawable.ic_lucide_circle_dollar_sign
    )

    /** 无品牌覆盖的账户按 subtype 给线性图标（与 DefaultDataInitializer 的 subtype 值一致）。 */
    private val subtypeIcons = mapOf(
        "cash" to R.drawable.ic_lucide_banknote,
        "e_wallet" to R.drawable.ic_lucide_wallet,
        "credit_card" to R.drawable.ic_lucide_credit_card
    )

    private val brandIconsById = mapOf(
        "default-account-wechat" to R.drawable.ic_brand_wechat,
        "default-account-alipay" to R.drawable.ic_brand_alipay
    )

    private val brandIconsByName = mapOf(
        "微信" to R.drawable.ic_brand_wechat,
        "支付宝" to R.drawable.ic_brand_alipay
    )

    private val brandDrawables = brandIconsById.values.toSet()

    /** 分类 chip：icon 字符串是 lucide 名（后端种子）返回资源；emoji/空返回 null（保持文字回退）。 */
    fun forCategory(icon: String?): Int? =
        icon?.trim()?.takeIf { it.isNotEmpty() }?.let { categoryIcons[it] }

    /** 账户 chip：品牌优先（id 精确 → 名称包含），subtype 线性兜底；null = emoji 文字回退。 */
    fun forAccount(accountId: String, name: String?, subtype: String?): Int? {
        brandIconsById[accountId]?.let { return it }
        if (!name.isNullOrBlank()) {
            brandIconsByName.entries.firstOrNull { name.contains(it.key) }?.let { return it.value }
        }
        return subtypeIcons[subtype]
    }

    /**
     * 把图标放到 chip 文字起始侧（16dp，间距 6dp）。
     *
     * @param tint 线性 lucide 图标按文字色着色（选中态变化时传新色）；品牌图标传任意值
     *   都**不会**被着色 —— 品牌色已烘焙进资源，不随选中态变化。
     */
    fun apply(textView: TextView, resId: Int, tint: Int?) {
        val drawable = ContextCompat.getDrawable(textView.context, resId)?.mutate() ?: return
        if (tint != null && resId !in brandDrawables) {
            drawable.setTint(tint)
        }
        val density = textView.resources.displayMetrics.density
        val size = (16 * density).toInt()
        drawable.setBounds(0, 0, size, size)
        textView.setCompoundDrawablesRelative(drawable, null, null, null)
        textView.compoundDrawablePadding = (6 * density).toInt()
    }
}
