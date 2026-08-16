package com.jotbill.app.rules

import com.jotbill.app.data.entity.BillEntity
import org.json.JSONArray

/**
 * 阶段五 T5：统一字段优先级合并器（docs/tdd/09_phase5_tasks.md 第 3.3 节 7 级优先级）。
 *
 * 优先级从高到低（rank 数值小者优先）：
 * 1. [Source.USER_LOCK]   用户明确编辑并锁定的值（fieldLocks）；
 * 2. [Source.RULE]        用户确认的个人规则结果；
 * 3. [Source.IMPORT]      带官方交易号的账单导入字段（阶段六启用，本阶段预留）；
 * 4. [Source.EVIDENCE]    支付成功通知/结果页的结构化字段；
 * 5. [Source.AI]          AI 视觉识别的高置信度字段；
 * 6. [Source.SESSION]     支付会话推断的平台字段；
 * 7. [Source.DEFAULT]     默认规则和历史建议。
 *
 * 合并规则：
 * - 低优先级数据不得覆盖更高优先级数据，只产生 [FieldConflict] 冲突提示
 *   （供待确认展示或审计），不静默覆写；
 * - 锁定字段（fieldLocks 命中）只接受用户来源写入，其余来源一律拒绝并记冲突；
 * - 目标字段当前为空（null）时任何来源都允许补全；
 * - 锁定集合为空且无冲突候选时，合并结果与直接赋值完全一致（纯增量语义）。
 *
 * 纯函数实现：不访问数据库、不做 IO，供所有自动写入路径
 * （规则、证据回填、AI 回填、默认建议）统一调用。
 */
object FieldPriorityResolver {

    /** 7 级数据来源（rank 数值越小优先级越高）。 */
    enum class Source(val rank: Int) {
        /** 1 级：用户明确编辑并锁定的值。 */
        USER_LOCK(1),

        /** 2 级：用户确认的个人规则结果。 */
        RULE(2),

        /** 3 级：带官方交易号的账单导入字段（阶段六启用）。 */
        IMPORT(3),

        /** 4 级：支付成功通知/结果页的结构化字段。 */
        EVIDENCE(4),

        /** 5 级：AI 视觉识别的高置信度字段。 */
        AI(5),

        /** 6 级：支付会话推断的平台字段。 */
        SESSION(6),

        /** 7 级：默认规则和历史建议。 */
        DEFAULT(7)
    }

    /**
     * 可锁定的字段清单（字段级锁定，不支持行级整体锁）。
     * 与 [BillEntity] 字段名一一对应；amountMinor/occurredAt 等核心记账字段同样可锁。
     */
    val LOCKABLE_FIELDS: Set<String> = setOf(
        "amountMinor",
        "type",
        "occurredAt",
        "description",
        "categoryId",
        "accountId",
        "targetAccountId",
        "platform",
        "merchant",
        "productDescription",
        "orderId"
    )

    /** 单个字段的写入候选：字段名 + 候选值 + 数据来源级别。 */
    data class Assignment(
        val field: String,
        val value: Any?,
        val source: Source
    )

    /** 字段当前已解析状态：值 + 写入该值时的来源级别（null 表示未知来源，按保守处理）。 */
    data class Resolved(
        val value: Any?,
        val source: Source?
    )

    /**
     * 低优先级候选被拒的冲突提示：保留值与被拒值都记录，
     * 供待确认展示或审计使用，不静默丢弃。
     */
    data class FieldConflict(
        val field: String,
        val keptValue: Any?,
        val rejectedValue: Any?,
        val keptSource: Source?,
        val rejectedSource: Source,
        /** true 表示因字段被用户锁定而拒绝；false 表示因来源优先级不足而拒绝。 */
        val locked: Boolean
    )

    /** 合并结果：每字段最终采用的值与来源 + 全部冲突提示。 */
    data class MergeResult(
        val values: Map<String, Any?>,
        val sources: Map<String, Source>,
        val conflicts: List<FieldConflict>
    )

    /**
     * 逐字段合并决策：候选按来源优先级竞争写入目标字段。
     *
     * @param current 各字段当前状态（值 + 写入来源）；来源未记录时视为 EVIDENCE 级保守基准
     * @param candidates 本轮全部写入候选（可含同字段多来源，自动按优先级竞争）
     * @param locks 用户锁定的字段名集合（[parseLocks] 解析结果）
     * @return 合并结果与冲突提示
     */
    fun resolve(
        current: Map<String, Resolved>,
        candidates: List<Assignment>,
        locks: Set<String>
    ): MergeResult {
        val values = current.mapValues { it.value.value }.toMutableMap()
        val sources = current.mapNotNull { (f, r) -> r.source?.let { f to it } }.toMap().toMutableMap()
        val conflicts = mutableListOf<FieldConflict>()

        // 同字段多候选先内部竞争：优先级高（rank 小）者胜，同 rank 保持传入顺序（先到先得）
        val ordered = candidates.sortedBy { it.source.rank }
        for (candidate in ordered) {
            val field = candidate.field
            val locked = field in locks
            val existingValue = values[field]
            val existingSource = sources[field] ?: Source.EVIDENCE

            when {
                // 锁定字段：仅用户来源可写，其余来源拒绝并记冲突
                locked && candidate.source != Source.USER_LOCK -> {
                    conflicts += FieldConflict(
                        field = field,
                        keptValue = existingValue,
                        rejectedValue = candidate.value,
                        keptSource = existingSource,
                        rejectedSource = candidate.source,
                        locked = true
                    )
                }
                // 字段为空：任何来源允许补全
                existingValue == null -> {
                    values[field] = candidate.value
                    sources[field] = candidate.source
                }
                // 候选来源优先级更高（rank 更小）：覆盖
                candidate.source.rank < existingSource.rank -> {
                    values[field] = candidate.value
                    sources[field] = candidate.source
                }
                // 优先级不足或相同：拒绝并记冲突提示，不覆写
                else -> {
                    conflicts += FieldConflict(
                        field = field,
                        keptValue = existingValue,
                        rejectedValue = candidate.value,
                        keptSource = existingSource,
                        rejectedSource = candidate.source,
                        locked = false
                    )
                }
            }
        }
        return MergeResult(values, sources, conflicts)
    }

    /**
     * 账单更新路径的锁定保护：非用户来源更新已有账单时，
     * 锁定字段的值变化被回退为既有值，并返回逐字段冲突清单。
     *
     * 用户来源（USER_LOCK）不受限制——用户编辑可以改锁定字段本身，改完仍保持锁定。
     * 锁定集合为空时返回原更新值（与现状完全一致）。
     *
     * @param existing 既有账单（锁定集合与字段值基准）
     * @param proposed 拟写入的更新账单
     * @param source 本次更新的来源级别
     * @return 保护后的账单副本与被拒绝的字段变化清单
     */
    fun protectLockedUpdate(
        existing: BillEntity,
        proposed: BillEntity,
        source: Source
    ): Pair<BillEntity, List<FieldConflict>> {
        val locks = parseLocks(existing.fieldLocks)
        if (locks.isEmpty() || source == Source.USER_LOCK) {
            return proposed to emptyList()
        }
        var protected = proposed
        val conflicts = mutableListOf<FieldConflict>()
        for (field in locks) {
            val before = billFieldValue(existing, field)
            val after = billFieldValue(proposed, field)
            if (before != after) {
                conflicts += FieldConflict(
                    field = field,
                    keptValue = before,
                    rejectedValue = after,
                    keptSource = Source.USER_LOCK,
                    rejectedSource = source,
                    locked = true
                )
                protected = withBillFieldValue(protected, field, before)
            }
        }
        return protected to conflicts
    }

    /** 读取账单指定字段的当前值；字段名不在 [LOCKABLE_FIELDS] 时抛异常。 */
    fun billFieldValue(bill: BillEntity, field: String): Any? = when (field) {
        "amountMinor" -> bill.amountMinor
        "type" -> bill.type
        "occurredAt" -> bill.occurredAt
        "description" -> bill.description
        "categoryId" -> bill.categoryId
        "accountId" -> bill.accountId
        "targetAccountId" -> bill.targetAccountId
        "platform" -> bill.platform
        "merchant" -> bill.merchant
        "productDescription" -> bill.productDescription
        "orderId" -> bill.orderId
        else -> throw IllegalArgumentException("Unknown lockable field: $field")
    }

    /** 返回替换指定字段值后的账单副本；字段名不在 [LOCKABLE_FIELDS] 时抛异常。 */
    fun withBillFieldValue(bill: BillEntity, field: String, value: Any?): BillEntity = when (field) {
        "amountMinor" -> bill.copy(amountMinor = value as Long)
        "type" -> bill.copy(type = value as String)
        "occurredAt" -> bill.copy(occurredAt = value as Long)
        "description" -> bill.copy(description = value as String?)
        "categoryId" -> bill.copy(categoryId = value as String?)
        "accountId" -> bill.copy(accountId = value as String?)
        "targetAccountId" -> bill.copy(targetAccountId = value as String?)
        "platform" -> bill.copy(platform = value as String?)
        "merchant" -> bill.copy(merchant = value as String?)
        "productDescription" -> bill.copy(productDescription = value as String?)
        "orderId" -> bill.copy(orderId = value as String?)
        else -> throw IllegalArgumentException("Unknown lockable field: $field")
    }

    /** 解析 field_locks JSON 数组字符串为字段名集合；非法 JSON 或未知字段按空集合处理。 */
    fun parseLocks(fieldLocksJson: String?): Set<String> {
        if (fieldLocksJson.isNullOrBlank()) return emptySet()
        val array = runCatching { JSONArray(fieldLocksJson) }.getOrNull() ?: return emptySet()
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
            .toSet()
    }

    /** 序列化字段名集合为 field_locks JSON 数组字符串（顺序稳定，便于测试与审计对比）。 */
    fun serializeLocks(locks: Set<String>): String {
        val array = JSONArray()
        locks.sorted().forEach { array.put(it) }
        return array.toString()
    }

    /** 校验字段名集合全部在 [LOCKABLE_FIELDS] 内，返回非法字段名清单。 */
    fun invalidLockFields(locks: Collection<String>): Set<String> =
        locks.filterNot { it in LOCKABLE_FIELDS }.toSet()
}
