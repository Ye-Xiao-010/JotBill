package com.jotbill.app.bookkeeping

import android.content.Context
import com.jotbill.app.utils.JotBillLog
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 页面规则仓库（对齐小米 `ce.p1` RuleManager 的本地部分）。
 *
 * M1/M2 阶段规则只来自 APK 内置资产 `assets/bookkeeping/bookkeeping_builtin_rules.json`
 * （小米 18 key / 27 条兜底规则快照 + jotbillType 收入标注）；M3 接服务端
 * `/rules` 版本协商后，云端规则在此覆盖内置集。
 *
 * key 形态与匹配优先级（对齐小米 U()，前缀松散匹配的教训见设计方案 §2.6）：
 * 1. `包名/完整类名`（页面事件、通知事件的 `包名/android.app.Notification`）；
 * 2. 包名通配 key（"包名/星号"，该包任意页面，如拼多多收银台）；
 * 3. 裸完整类名（任意包下的同名页面，内置规则主体形态）。
 * key 本身含逗号时拆成多 key 共享同一规则列表（对齐小米 c.b() 的逗号拆分）。
 */
class PageRuleRegistry private constructor(
    private val rulesByKey: Map<String, List<PageRule>>,
    /** 解析期规则条数（逗号 key 展开前），即资产中的唯一规则数。 */
    val parsedRuleCount: Int,
    /** 资产加载失败原因；非 null 时规则集为空（引擎空转），设置页据此显式警示。 */
    val loadError: String? = null
) {
    /** 命中的 key；未命中返回 null。 */
    fun matchKey(packageName: String?, className: String?): String? {
        if (className.isNullOrEmpty()) return null
        if (packageName != null) {
            rulesByKey["$packageName/$className"]?.let { return "$packageName/$className" }
            rulesByKey["$packageName/*"]?.let { return "$packageName/*" }
        }
        if (rulesByKey.containsKey(className)) return className
        return null
    }

    /** 该 key 下的规则列表；key 不存在返回 null（与小米 V() 一致，用于区分“无此 key”）。 */
    fun rulesFor(key: String): List<PageRule>? = rulesByKey[key]

    val keyCount: Int get() = rulesByKey.size

    val ruleCount: Int get() = rulesByKey.values.sumOf { it.size }

    companion object {
        private const val TAG = "PageRuleRegistry"

        private const val ASSET_PATH = "bookkeeping/bookkeeping_builtin_rules.json"

        @Volatile
        private var instance: PageRuleRegistry? = null

        /** 进程内单例；首次调用从资产加载，失败返回空仓库（引擎将空转，不崩溃）。 */
        fun get(context: Context): PageRuleRegistry = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        /** 供单测直接注入原始 JSON。 */
        fun fromJsonText(text: String): PageRuleRegistry {
            val root = JSONObject(text)
            val raw = root.optJSONObject("rules") ?: return PageRuleRegistry(emptyMap(), 0)
            val expanded = mutableMapOf<String, MutableList<PageRule>>()
            var parsed = 0
            var dropped = 0
            for (rawKey in raw.keys()) {
                val array = raw.optJSONArray(rawKey) ?: continue
                val rules = mutableListOf<PageRule>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    try {
                        rules += PageRule.fromJson(ruleIdFallback = -1, json = obj)
                        parsed++
                    } catch (error: RuntimeException) {
                        dropped++
                        JotBillLog.w(TAG, "规则解析失败已丢弃: key=$rawKey, error=${error.message}")
                    }
                }
                for (key in rawKey.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    expanded.getOrPut(key) { mutableListOf() }.addAll(rules)
                }
            }
            if (dropped > 0) {
                JotBillLog.w(TAG, "规则解析完成: parsed=$parsed, dropped=$dropped")
            }
            return PageRuleRegistry(expanded, parsed)
        }

        private fun load(context: Context): PageRuleRegistry = try {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val registry = fromJsonText(text)
            if (registry.parsedRuleCount == 0) {
                JotBillLog.w(TAG, "内置页面规则解析结果为空，自动记账将空转")
            } else {
                JotBillLog.i(
                    TAG,
                    "内置页面规则已加载: keys=${registry.keyCount}, " +
                        "rules=${registry.parsedRuleCount}"
                )
            }
            registry
        } catch (error: Exception) {
            JotBillLog.w(TAG, "内置页面规则加载失败，自动记账将空转", error)
            PageRuleRegistry(emptyMap(), 0, error.message ?: error.javaClass.simpleName)
        }
    }
}
