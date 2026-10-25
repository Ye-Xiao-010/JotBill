package com.jotbill.app.bookkeeping

import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条「页面规则」：某个支付结果 / 收银台页面出现时，如何从页面 dump 文本里抠出账单字段。
 *
 * 模型 1:1 对齐小米钱包 AI 记账的兜底规则 schema（逆向源 `ce.p1.b`，规则快照
 * assets/bookkeeping/bookkeeping_builtin_rules.json）。字段语义：
 * - [locateAttribute] 0=text / 1=contentDesc / -1=不做内容定位（页面出现即命中）；
 * - [locateToken]/[locatePattern] 定位锚点：页面文本包含该词 / 命中该正则；
 * - [extractPattern]+[extractFields] 单正则抽取：捕获组号 → 字段名；
 * - [ruleType]="singleField" 时改用 [singleFieldExtractPattern]：每字段一组候选
 *   正则（顺序尝试，首个命中生效），[necessaryFields] 缺一即整条放弃 —— 这是
 *   小米「微信结构性失效」教训后的修复形态（每字段独立正则多候选）；
 * - [extractFieldsMapper] 值归一化：精确 mapping → mappingEx 正则 → defaultValue；
 * - [locateConfirm] 二次确认锚点：抽取成功后不立即入账，等待确认页 / 确认通知；
 * - [matchMode]=1 且带 locateConfirm 时，抽取后不清除窗口跟踪（小米原语义）。
 *
 * 所有正则以 [Pattern.DOTALL] 编译 —— 小米原实现如此（`.` 跨行匹配），dump 文本
 * 本身多行，漏掉该 flag 会让全部规则失效。
 */
class PageRule private constructor(
    val ruleId: Int,
    val rulePkgId: Int,
    val note: String?,
    val fromApp: Int,
    val jotbillType: String?,
    val ruleType: String?,
    val matchMode: Int,
    val locateAttribute: Int,
    val locateToken: String?,
    private val locatePatternText: String?,
    val locateMaxTraverseCount: Int,
    private val extractPatternText: String?,
    val extractFields: Map<String, Int>,
    private val extractFieldsMapperRaw: Map<String, FieldMapper>,
    val singleFieldExtractPattern: Map<String, List<SingleFieldPattern>>,
    val necessaryFields: Set<String>,
    val locateConfirm: ConfirmAnchor?
) {
    private val locatePattern: Pattern? =
        locatePatternText?.takeIf { it.isNotEmpty() }?.let { Pattern.compile(it, Pattern.DOTALL) }
    private val extractPattern: Pattern? =
        extractPatternText?.takeIf { it.isNotEmpty() }?.let { Pattern.compile(it, Pattern.DOTALL) }

    /** 值归一化器：精确 mapping → mappingEx 正则 → defaultValue 三级（对齐小米 R()）。 */
    class FieldMapper(
        val defaultValue: String?,
        private val mapping: Map<String, String>,
        private val mappingEx: List<Pair<Pattern, String>>
    ) {
        fun apply(value: String): String? {
            mapping[value]?.let { return it }
            for ((pattern, mapped) in mappingEx) {
                if (pattern.matcher(value).find()) return mapped
            }
            return defaultValue
        }
    }

    /** singleField 形态的单字段候选：命中后取组 1，或直接采用 staticValue。 */
    class SingleFieldPattern(val pattern: Pattern?, val staticValue: String?)

    /**
     * 二次确认锚点（对齐小米 `p1.b.C0029b`，继承规则定位字段）：
     * - [activity] 确认页 key 集合（pkg/class、包名通配 key、裸类名，逗号分隔）；
     * - [notification] 确认通知的 `pkg/事件类名` 精确匹配；
     * - [locateToken]/[locatePattern]/[locateAttribute] 在确认内容上做与主规则
     *   相同的包含 / 命中判定。
     */
    class ConfirmAnchor(
        val activity: Set<String>,
        val notification: String?,
        val locateAttribute: Int,
        val locateToken: String?,
        val locatePattern: Pattern?,
        val locateMaxTraverseCount: Int
    )

    /** 定位判定（对齐小米 Y()）：token 包含或 pattern 命中；两者皆空返回 false。 */
    fun isLocatedBy(value: String?): Boolean {
        if (value == null) return false
        locateToken?.let { return value.contains(it) }
        locatePattern?.let { return it.matcher(value).find() }
        return false
    }

    /**
     * 在页面 dump 文本上抽取字段（对齐小米 Q()/R()/S()）。
     * 返回 null 表示规则未命中（定位失败 / 正则不匹配 / 必填字段缺失）。
     */
    fun extractFrom(dump: String): Map<String, String>? {
        if (ruleType == "singleField") return extractSingleField(dump) ?: return null
        val pattern = extractPattern ?: return null
        val matcher = pattern.matcher(dump)
        if (!matcher.find()) return null
        val fields = mutableMapOf<String, String>()
        for ((field, group) in extractFields) {
            val raw = try {
                matcher.group(group)
            } catch (_: RuntimeException) {
                null
            } ?: continue
            fields[field] = extractFieldsMapperRaw[field]?.apply(raw) ?: raw
        }
        return fields
    }

    private fun extractSingleField(dump: String): Map<String, String>? {
        val fields = mutableMapOf<String, String>()
        for ((field, candidates) in singleFieldExtractPattern) {
            for (candidate in candidates) {
                val pattern = candidate.pattern ?: continue
                val matcher = pattern.matcher(dump)
                if (!matcher.find()) continue
                fields[field] = candidate.staticValue ?: matcher.group(1) ?: continue
                break
            }
        }
        for (necessary in necessaryFields) {
            if (!fields.containsKey(necessary)) return null
        }
        return fields
    }

    companion object {
        /** 从规则 JSON 对象解析并编译；解析失败抛异常由上层决定丢弃整批还是单条。 */
        fun fromJson(ruleIdFallback: Int, json: JSONObject): PageRule = PageRule(
            ruleId = json.optInt("ruleId", ruleIdFallback),
            rulePkgId = json.optInt("rulePkgId", 0),
            note = json.optStringOrNull("note"),
            fromApp = json.optInt("fromApp", 0),
            jotbillType = json.optStringOrNull("jotbillType"),
            ruleType = json.optStringOrNull("ruleType"),
            matchMode = json.optInt("matchMode", 0),
            locateAttribute = json.optInt("locateAttribute", 0),
            locateToken = json.optStringOrNull("locateToken"),
            locatePatternText = json.optStringOrNull("locatePattern"),
            locateMaxTraverseCount = json.optInt("locateMaxTraverseCount", 0),
            extractPatternText = json.optStringOrNull("extractPattern"),
            extractFields = json.optJSONObject("extractFields")?.let { obj ->
                buildMap {
                    for (key in obj.keys()) {
                        put(key, obj.optInt(key))
                    }
                }
            } ?: emptyMap(),
            extractFieldsMapperRaw = json.optJSONObject("extractFieldsMapper")?.let(::parseMappers)
                ?: emptyMap(),
            singleFieldExtractPattern = json.optJSONObject("singleFieldExtractPattern")
                ?.let(::parseSingleFields) ?: emptyMap(),
            necessaryFields = json.optJSONArray("necessaryFields")?.let { array ->
                buildSet {
                    for (i in 0 until array.length()) {
                        array.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            } ?: emptySet(),
            locateConfirm = json.optJSONObject("locateConfirm")?.let(::parseConfirm)
        )

        private fun parseMappers(root: JSONObject): Map<String, FieldMapper> = buildMap {
            for (field in root.keys()) {
                val obj = root.optJSONObject(field) ?: continue
                put(
                    field,
                    FieldMapper(
                        defaultValue = obj.optStringOrNull("defaultValue"),
                        mapping = obj.optJSONObject("mapping")?.let { m ->
                            buildMap {
                                for (k in m.keys()) put(k, m.optString(k))
                            }
                        } ?: emptyMap(),
                        mappingEx = obj.optJSONObject("mappingEx")?.let { m ->
                            buildList {
                                for (k in m.keys()) {
                                    add(Pattern.compile(k, Pattern.DOTALL) to m.optString(k))
                                }
                            }
                        } ?: emptyList()
                    )
                )
            }
        }

        private fun parseSingleFields(root: JSONObject): Map<String, List<SingleFieldPattern>> =
            buildMap {
                for (field in root.keys()) {
                    val array: JSONArray = root.optJSONArray(field) ?: continue
                    val candidates = buildList {
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i) ?: continue
                            val patternText = obj.optStringOrNull("pattern")
                            add(
                                SingleFieldPattern(
                                    pattern = patternText
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { Pattern.compile(it, Pattern.DOTALL) },
                                    staticValue = obj.optStringOrNull("staticValue")
                                )
                            )
                        }
                    }
                    put(field, candidates)
                }
            }

        private fun parseConfirm(json: JSONObject): ConfirmAnchor {
            val activityText = json.optStringOrNull("activity")
            return ConfirmAnchor(
                activity = activityText
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet(),
                notification = json.optStringOrNull("notification"),
                locateAttribute = json.optInt("locateAttribute", 0),
                locateToken = json.optStringOrNull("locateToken"),
                locatePattern = json.optStringOrNull("locatePattern")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Pattern.compile(it, Pattern.DOTALL) },
                locateMaxTraverseCount = json.optInt("locateMaxTraverseCount", 0)
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            val value = optString(key, "")
            return value.ifEmpty { null }
        }
    }
}
