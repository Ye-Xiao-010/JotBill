package com.jotbill.app.overlay

import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jotbill.app.R
import org.json.JSONArray

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

    /** 与默认分类种子的 lucide 图标名一一对应（前 13 个来自后端种子 0a80e72，
     *  其余为本地特有分类新增，2026-10-27 随种子 lucide 化一并接入）。 */
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
        "circle-dollar-sign" to R.drawable.ic_lucide_circle_dollar_sign,
        "smartphone" to R.drawable.ic_lucide_smartphone,
        "shopping-basket" to R.drawable.ic_lucide_shopping_basket,
        "party-popper" to R.drawable.ic_lucide_party_popper,
        "plane" to R.drawable.ic_lucide_plane,
        "paw-print" to R.drawable.ic_lucide_paw_print,
        "package" to R.drawable.ic_lucide_package,
        "briefcase" to R.drawable.ic_lucide_briefcase,
        "trending-up" to R.drawable.ic_lucide_trending_up,
        "gift" to R.drawable.ic_lucide_gift,
        "hand-coins" to R.drawable.ic_lucide_hand_coins,
        "rotate-ccw" to R.drawable.ic_lucide_rotate_ccw,
        "receipt" to R.drawable.ic_lucide_receipt
    )

    /** 无品牌覆盖的账户按 subtype 给线性图标（与 DefaultDataInitializer 的 subtype 值一致）。 */
    private val subtypeIcons = mapOf(
        "cash" to R.drawable.ic_lucide_banknote,
        "e_wallet" to R.drawable.ic_lucide_wallet,
        "credit_card" to R.drawable.ic_lucide_credit_card
    )

    /** 银行 code → 方形徽标（与 `icons/banks/banks.json` 的 code 一致，96px PNG）。 */
    private val bankIconsByCode = mapOf(
        "icbc" to R.drawable.ic_bank_icbc,
        "ccb" to R.drawable.ic_bank_ccb,
        "abchina" to R.drawable.ic_bank_abchina,
        "boc" to R.drawable.ic_bank_boc,
        "bankcomm" to R.drawable.ic_bank_bankcomm,
        "psbc" to R.drawable.ic_bank_psbc,
        "cmbchina" to R.drawable.ic_bank_cmbchina,
        "citicbank" to R.drawable.ic_bank_citicbank,
        "spdb" to R.drawable.ic_bank_spdb,
        "cmbc" to R.drawable.ic_bank_cmbc,
        "cib" to R.drawable.ic_bank_cib,
        "cebbank" to R.drawable.ic_bank_cebbank,
        "hxb" to R.drawable.ic_bank_hxb,
        "cgbchina" to R.drawable.ic_bank_cgbchina,
        "pingan" to R.drawable.ic_bank_pingan,
        "cbhb" to R.drawable.ic_bank_cbhb,
        "hfbank" to R.drawable.ic_bank_hfbank,
        "czbank" to R.drawable.ic_bank_czbank,
        "bankofbeijing" to R.drawable.ic_bank_bankofbeijing,
        "bosc" to R.drawable.ic_bank_bosc,
        "jsbchina" to R.drawable.ic_bank_jsbchina,
        "nbcb" to R.drawable.ic_bank_nbcb,
        "njcb" to R.drawable.ic_bank_njcb
    )

    /**
     * 银行名称/别名 → code 索引（从 assets/banks.json 懒加载，最长别名优先）。
     *
     * 不再硬编码：与 RN 侧 `constants/accountIcons.ts` 共用同一份 JSON 作
     * 单一事实源，避免「RN 能匹配、原生匹配不到」的漂移
     * （`BankIconAssetsTest` 锁定两侧对每个 code/别名都能解析出图标）。
     */
    @Volatile
    private var bankAliasCache: List<Pair<String, String>>? = null

    private fun bankAliases(context: Context): List<Pair<String, String>> {
        bankAliasCache?.let { return it }
        val parsed = runCatching {
            val json = context.assets.open("banks.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val bank = array.getJSONObject(i)
                    val code = bank.getString("code")
                    add(bank.getString("name") to code)
                    bank.optString("shortName").takeIf { it.isNotBlank() }?.let { add(it to code) }
                    val aliases = bank.optJSONArray("aliases") ?: continue
                    for (j in 0 until aliases.length()) add(aliases.getString(j) to code)
                }
            }.sortedByDescending { it.first.length }
        }.getOrElse {
            android.util.Log.w("PopupIconAssets", "读取 banks.json 失败，银行图标按显式指定生效", it)
            emptyList()
        }
        bankAliasCache = parsed
        return parsed
    }

    private val brandIconsById = mapOf(
        "default-account-wechat" to R.drawable.ic_brand_wechat,
        "default-account-alipay" to R.drawable.ic_brand_alipay
    )

    private val brandIconsByName = mapOf(
        "微信" to R.drawable.ic_brand_wechat,
        "支付宝" to R.drawable.ic_brand_alipay
    )

    /** 位图类资源（品牌色/银行徽标已烘焙）不着色；只有线性 lucide 跟随文字色。 */
    private val bitmapDrawables = brandIconsById.values.toSet() +
        bankIconsByCode.values.toSet()

    /** 分类 chip：icon 字符串是 lucide 名（后端种子）返回资源；emoji/空返回 null（保持文字回退）。 */
    fun forCategory(icon: String?): Int? =
        icon?.trim()?.takeIf { it.isNotEmpty() }?.let { categoryIcons[it] }

    /**
     * 账户 chip：银行/品牌位图优先（`icon` 显式指定 → 品牌 id/名称 → 银行别名），
     * subtype 线性兜底；null = emoji 文字回退。
     */
    fun forAccount(
        context: Context,
        accountId: String,
        name: String?,
        subtype: String?,
        icon: String? = null
    ): Int? {
        // 1. 显式指定：bank:<code> / brand:<code>
        val explicit = icon?.trim()
        if (!explicit.isNullOrEmpty()) {
            val parts = explicit.split(":", limit = 2)
            if (parts.size == 2) {
                when (parts[0]) {
                    "bank" -> bankIconsByCode[parts[1]]?.let { return it }
                    "brand" -> when (parts[1]) {
                        "wechat" -> return R.drawable.ic_brand_wechat
                        "alipay" -> return R.drawable.ic_brand_alipay
                    }
                }
            }
        }
        // 2. 品牌名/id
        brandIconsById[accountId]?.let { return it }
        if (!name.isNullOrBlank()) {
            brandIconsByName.entries.firstOrNull { name.contains(it.key) }?.let { return it.value }
            // 3. 银行名别名（最长优先）
            val bankCode = bankAliases(context).firstOrNull { name.contains(it.first) }?.second
            if (bankCode != null) bankIconsByCode[bankCode]?.let { return it }
        }
        // 4. subtype 线性兜底
        return subtypeIcons[subtype]
    }

    /**
     * 把图标放到 chip 文字起始侧（16dp，间距 6dp）。
     *
     * @param tint 线性 lucide 图标按文字色着色（选中态变化时传新色）；品牌/银行位图
     *   传任意值都**不会**被着色 —— 颜色已烘焙进资源，不随选中态变化。
     */
    fun apply(textView: TextView, resId: Int, tint: Int?) {
        val drawable = ContextCompat.getDrawable(textView.context, resId)?.mutate() ?: return
        if (tint != null && resId !in bitmapDrawables) {
            drawable.setTint(tint)
        }
        val density = textView.resources.displayMetrics.density
        val size = (16 * density).toInt()
        drawable.setBounds(0, 0, size, size)
        textView.setCompoundDrawablesRelative(drawable, null, null, null)
        textView.compoundDrawablePadding = (6 * density).toInt()
    }
}
