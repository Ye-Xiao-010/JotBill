package com.jotbill.app.rules

import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.RuleEntity
import org.json.JSONObject

/**
 * 阶段五 T1：个人规则引擎执行核心。
 *
 * 契约（见 docs/tdd/09_phase5_tasks.md 第 3.4 节）：
 * - 判定是纯函数式的：输入候选/待确认字段投影与规则集合，输出补全结果、动作与命中清单；
 *   引擎本身不写 Bill、不弹窗、不更新余额。
 * - 规则在两个时机执行：候选生成时（AI 候选落库前、纯通知待确认创建前）和入账前
 *   （自动入账决策后、用户确认写 Bill 前），由调用方挂接。
 * - 多条规则命中同一字段且取值冲突时，按优先级（数字小者优先）+ 创建时间（早者优先）
 *   确定将应用规则，冲突信息返回给调用方展示，不静默选择。
 * - `ignore` 作用于自动路径（不写 Bill、不进待确认）；用户在待确认中心的显式确认
 * 优先级高于规则，`ignore` 不得阻断用户确认。
 * - 命中次数累加在事务内完成，避免并发丢失。
 */
class RuleEngine(private val database: JotBillDatabase) {

    /** 规则判定的目标字段投影（来自 AI 候选、支付证据或待确认交易）。 */
    data class Subject(
        val amountMinor: Long? = null,
        val platform: String? = null,
        val merchant: String? = null,
        val description: String? = null,
        val sourceType: String? = null
    )

    /** 单条规则命中摘要：记录规则 ID、动作与优先级，供 appliedRules 与审计使用。 */
    data class RuleHit(
        val ruleId: String,
        val ruleName: String,
        val action: String,
        val actionValue: String?,
        val priority: Int
    )

    /** 字段冲突摘要：同字段被多条规则命中且取值不同，展示将应用规则与被挡下的规则。 */
    data class FieldConflict(
        val field: String,
        val appliedRuleId: String,
        val appliedValue: String,
        val conflictingRuleId: String,
        val conflictingValue: String
    )

    /** set_* 动作产出的字段补全结果；null 表示该字段未被规则设置。 */
    data class FieldPatch(
        val platform: String? = null,
        val merchant: String? = null,
        val categoryId: String? = null,
        val accountId: String? = null
    )

    /** 规则判定结果：Continue 走默认路径，Ignored 跳过记账，ToReview 强制进入待确认。 */
    sealed class Decision {
        abstract val hits: List<RuleHit>
        abstract val conflicts: List<FieldConflict>

        /** 无规则命中或仅字段补全：调用方按原有链路处理（自动入账或既有冲突逻辑）。 */
        data class Continue(
            val patch: FieldPatch,
            override val hits: List<RuleHit>,
            override val conflicts: List<FieldConflict>
        ) : Decision()

        /** `ignore` 动作：不写 Bill、不更新余额、不进待确认，由调用方落审计记录。 */
        data class Ignored(
            override val hits: List<RuleHit>,
            override val conflicts: List<FieldConflict>
        ) : Decision()

        /** `send_to_review` 动作：强制进入待确认中心，不自动入账。 */
        data class ToReview(
            val reason: String,
            override val hits: List<RuleHit>,
            override val conflicts: List<FieldConflict>
        ) : Decision()
    }

    /**
     * 读取启用规则并执行判定，同时在事务内累加命中规则的 hit_count。
     *
     * 判定本身是纯函数（见 [evaluate]）；本方法只负责取数与命中计数持久化，
     * 供两个执行时机直接调用。
     */
    suspend fun evaluateAndRecord(subject: Subject): Decision = database.withTransaction {
        val rules = database.ruleDao().getActive()
            .sortedWith(compareBy({ it.priority }, { it.createdAt }))
        val decision = evaluate(subject, rules)
        decision.hits.forEach { hit ->
            database.ruleDao().incrementHitCount(hit.ruleId)
        }
        decision
    }

    /**
     * 纯函数式规则判定：按（priority ASC, createdAt ASC）顺序求值条件并应用动作。
     *
     * - 条件不匹配的规则不产生任何效果；
     * - `set_*` 对同一字段先到先得（高优先级胜出），低优先级不同取值记入冲突；
     * - `ignore` / `send_to_review` 命中即短路，按排序先到者胜出；
     * - 条件或取值格式非法的规则视为未命中，不中断整批判定。
     */
    fun evaluate(subject: Subject, rules: List<RuleEntity>): Decision {
        val hits = mutableListOf<RuleHit>()
        val conflicts = mutableListOf<FieldConflict>()
        val patchFields = mutableMapOf<String, Pair<String, String>>() // field -> (ruleId, value)

        for (rule in rules) {
            if (!matchesCondition(subject, rule)) {
                continue
            }
            val hit = RuleHit(
                ruleId = rule.id,
                ruleName = rule.name,
                action = rule.action,
                actionValue = rule.actionValue,
                priority = rule.priority
            )
            when (rule.action) {
                ACTION_IGNORE -> {
                    hits += hit
                    return Decision.Ignored(hits.toList(), conflicts.toList())
                }
                ACTION_SEND_TO_REVIEW -> {
                    hits += hit
                    return Decision.ToReview(
                        reason = "RULE_FORCED_REVIEW",
                        hits = hits.toList(),
                        conflicts = conflicts.toList()
                    )
                }
                ACTION_SET_PLATFORM, ACTION_SET_MERCHANT,
                ACTION_SET_CATEGORY, ACTION_SET_ACCOUNT -> {
                    val field = actionField(rule.action)
                    val value = rule.actionValue?.trim()?.takeIf { it.isNotEmpty() }
                    if (value != null) {
                        val existing = patchFields[field]
                        if (existing == null) {
                            patchFields[field] = rule.id to value
                        } else if (existing.second != value) {
                            conflicts += FieldConflict(
                                field = field,
                                appliedRuleId = existing.first,
                                appliedValue = existing.second,
                                conflictingRuleId = rule.id,
                                conflictingValue = value
                            )
                        }
                    }
                    hits += hit
                }
                // 未知动作按契约视为未命中，避免脏数据中断判定。
                else -> Unit
            }
        }
        val patch = FieldPatch(
            platform = patchFields[FIELD_PLATFORM]?.second,
            merchant = patchFields[FIELD_MERCHANT]?.second,
            categoryId = patchFields[FIELD_CATEGORY]?.second,
            accountId = patchFields[FIELD_ACCOUNT]?.second
        )
        return Decision.Continue(patch, hits.toList(), conflicts.toList())
    }

    /**
     * 将规则命中摘要合并进 appliedRules JSON 对象。
     *
     * appliedRules 列的既有约定是 JSON 对象：元数据键（decision、billId 等）与
     * 规则命中键（`rule:<ruleId>`，值为 `action:actionValue` 摘要）共存；
     * 桥接层解析时输出键名数组，与 Native.d.ts 的 `appliedRules?: string[]` 对齐。
     *
     * @param decisionOverride 需要同时覆盖的 decision 元数据值（如 IGNORED），null 表示不动
     */
    fun mergeAppliedRules(
        existingJson: String,
        hits: List<RuleHit>,
        decisionOverride: String? = null
    ): String {
        val merged = runCatching { JSONObject(existingJson) }.getOrDefault(JSONObject())
        if (decisionOverride != null) {
            merged.put(DECISION_KEY, decisionOverride)
        }
        hits.forEach { hit ->
            merged.put(
                "rule:${hit.ruleId}",
                listOfNotNull(hit.action, hit.actionValue).joinToString(":")
            )
        }
        return merged.toString()
    }

    /** 解析候选/待确认 appliedRules 中的决策元数据键；解析失败返回 null。 */
    fun decisionOf(appliedRulesJson: String?): String? {
        if (appliedRulesJson.isNullOrBlank()) return null
        return runCatching { JSONObject(appliedRulesJson).optString(DECISION_KEY) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 校验补全字段引用的账户/分类仍然存在：规则创建后账户停用或分类被删时，
     * 引用置空回退默认路径，避免外键约束导致整个入账事务回滚。
     * platform/merchant 无外键约束，不做校验。
     */
    suspend fun sanitizePatch(patch: FieldPatch): FieldPatch = patch.copy(
        accountId = patch.accountId?.takeIf {
            database.accountDao().getById(it)?.isActive == true
        },
        categoryId = patch.categoryId?.takeIf {
            database.categoryDao().getById(it) != null
        }
    )

    /**
     * 阶段五 T5：把 set_* 动作补全结果映射为优先级合并器候选（RULE 级，3.3 节第 2 级）。
     *
     * 调用方组装账单/待确认字段时，把本结果与证据、AI、默认建议等来源的候选
     * 一并交给 [FieldPriorityResolver.resolve]：规则（rank 2）可覆盖会话推断与默认建议
     * （rank 6/7），但不得覆盖用户锁定字段（rank 1），被拒候选以冲突提示呈现。
     * 字段名对齐 BillEntity：category → categoryId、account → accountId。
     */
    fun patchAssignments(patch: FieldPatch): List<FieldPriorityResolver.Assignment> =
        listOfNotNull(
            patch.platform?.let {
                FieldPriorityResolver.Assignment(FIELD_PLATFORM, it, FieldPriorityResolver.Source.RULE)
            },
            patch.merchant?.let {
                FieldPriorityResolver.Assignment(FIELD_MERCHANT, it, FieldPriorityResolver.Source.RULE)
            },
            patch.categoryId?.let {
                FieldPriorityResolver.Assignment(
                    "categoryId", it, FieldPriorityResolver.Source.RULE
                )
            },
            patch.accountId?.let {
                FieldPriorityResolver.Assignment(
                    "accountId", it, FieldPriorityResolver.Source.RULE
                )
            }
        )

    /** 逐条求值规则条件；条件类型或取值非法按未命中处理。 */
    private fun matchesCondition(subject: Subject, rule: RuleEntity): Boolean {
        val expected = rule.conditionValue.trim()
        return when (rule.condition) {
            CONDITION_PLATFORM_EQUALS ->
                subject.platform?.trim()?.equals(expected, ignoreCase = true) == true
            CONDITION_MERCHANT_EQUALS ->
                subject.merchant?.trim()?.equals(expected) == true
            CONDITION_MERCHANT_CONTAINS ->
                subject.merchant?.contains(expected) == true
            CONDITION_DESCRIPTION_CONTAINS ->
                subject.description?.contains(expected) == true
            CONDITION_SOURCE_EQUALS ->
                subject.sourceType?.trim()?.equals(expected, ignoreCase = true) == true
            CONDITION_AMOUNT_RANGE -> matchesAmountRange(subject.amountMinor, expected)
            else -> false
        }
    }

    /**
     * 金额区间条件：conditionValue 格式为 `min-max`（单位：分）。
     * 支持空端点：`1000-` 表示 ≥1000，`-5000` 表示 ≤5000，
     * `1000-5000` 表示闭区间，`-` 或空串表示不限。
     */
    private fun matchesAmountRange(amountMinor: Long?, expected: String): Boolean {
        if (amountMinor == null) return false
        val parts = expected.split("-", limit = 2)
        if (parts.size != 2) return false
        val min = parts[0].trim().toLongOrNull()
        val max = parts[1].trim().toLongOrNull()
        if (min != null && amountMinor < min) return false
        if (max != null && amountMinor > max) return false
        return true
    }

    /** set_* 动作到补全字段的映射。 */
    private fun actionField(action: String): String = when (action) {
        ACTION_SET_PLATFORM -> FIELD_PLATFORM
        ACTION_SET_MERCHANT -> FIELD_MERCHANT
        ACTION_SET_CATEGORY -> FIELD_CATEGORY
        ACTION_SET_ACCOUNT -> FIELD_ACCOUNT
        else -> action
    }

    companion object {
        /** 条件类型（与 Native.d.ts RuleCondition 一致）。 */
        const val CONDITION_PLATFORM_EQUALS = "platform_equals"
        const val CONDITION_MERCHANT_EQUALS = "merchant_equals"
        const val CONDITION_MERCHANT_CONTAINS = "merchant_contains"
        const val CONDITION_DESCRIPTION_CONTAINS = "description_contains"
        const val CONDITION_SOURCE_EQUALS = "source_equals"
        const val CONDITION_AMOUNT_RANGE = "amount_range"

        /** 动作类型（与 Native.d.ts RuleAction 一致）。 */
        const val ACTION_SET_PLATFORM = "set_platform"
        const val ACTION_SET_MERCHANT = "set_merchant"
        const val ACTION_SET_CATEGORY = "set_category"
        const val ACTION_SET_ACCOUNT = "set_account"
        const val ACTION_IGNORE = "ignore"
        const val ACTION_SEND_TO_REVIEW = "send_to_review"

        /** 补全字段内部标识。 */
        const val FIELD_PLATFORM = "platform"
        const val FIELD_MERCHANT = "merchant"
        const val FIELD_CATEGORY = "category"
        const val FIELD_ACCOUNT = "account"

        /** appliedRules 决策元数据键（沿用候选既有约定）。 */
        const val DECISION_KEY = "decision"

        /** 决策值：规则 ignore 命中，跳过记账。 */
        const val DECISION_IGNORED = "IGNORED"

        /** 决策值：规则 send_to_review 命中，强制进入待确认。 */
        const val DECISION_FORCED_REVIEW = "FORCED_REVIEW"

        /** 待确认冲突原因：send_to_review 命中。 */
        const val CONFLICT_RULE_FORCED_REVIEW = "RULE_FORCED_REVIEW"
    }
}
