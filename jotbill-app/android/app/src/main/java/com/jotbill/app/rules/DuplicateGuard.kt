package com.jotbill.app.rules

import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.BillEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段五 T6：多级去重判定器（docs/tdd/09_phase5_tasks.md 第 3.6 节）。
 *
 * 四级判定流程（写入 Bill 前按序执行，命中即短路）：
 *
 * | 级别 | 匹配条件 | 命中行为 |
 * |---|---|---|
 * | 1 | 来源订单号精确匹配 `(sourceType, orderId)` | 复用/更新原账单可变字段（经 T5 合并器，EVIDENCE 级只补空），不产生第二笔 |
 * | 2 | 同一来源事件标识 `sourceEventKey` | 沿用现状：确定性主键幂等 + 用户确认墓碑（T2/T3/T4 已接入同一键族），本类不重复实现 |
 * | 3 | 导入文件记录标识 `(sourceType=import, importRecordId)` | 命中即跳过导入行并返回既有账单供上层计数 |
 * | 4 | 金额+币种+时间窗口+账户+商户相似度综合 | 只提示不合并：标记 SUSPECTED_DUPLICATE 附因素清单，用户仍可确认两笔并存 |
 *
 * 退款豁免：退款支持多次部分退款，按退款单号去重（第 2 级幂等键族），
 * 不按原交易号走第 1 级合并——[checkLevel1] 对 type=refund 恒返回未命中。
 *
 * 去重原则：宁可进入待确认，不丢弃证据、不静默合并。查询异常向上抛出，
 * 由调用方决定转待确认（自动入账路径）或失败回滚（用户手动路径）。
 */
internal class DuplicateGuard(
    private val database: JotBillDatabase
) {

    /** 第 4 级相似命中：账单 + 命中的相似度因素清单（供待确认展示与审计）。 */
    data class SimilarHit(
        val bill: BillEntity,
        val factors: List<String>
    )

    /**
     * 第 1 级：按 (sourceType, orderId) 精确查重。
     *
     * @param sourceType 账单来源类型
     * @param orderId 来源订单号；空值视为无订单号，直接未命中
     * @param type 交易类型；refund 豁免（多次部分退款不按订单号合并）
     * @param excludeId 排除的账单 ID（更新场景排除自身）
     * @return 命中的既有账单；未命中返回 null
     */
    suspend fun checkLevel1(
        sourceType: String,
        orderId: String?,
        type: String,
        excludeId: String? = null
    ): BillEntity? {
        if (orderId.isNullOrBlank()) return null
        if (type == "refund") return null
        return database.billDao().findBySourceAndOrderId(sourceType, orderId, excludeId)
    }

    /**
     * 阶段六 T1 第 3 级：导入文件记录标识精确查重（docs/tdd/10_phase6_tasks.md 第 3.4 节）。
     *
     * 判定口径：sourceType 固定为 "import"，以账单文件的记录唯一标识
     * （微信交易单号/支付宝交易订单号）精确匹配既有账单；命中返回既有账单，
     * 调用方计为 skipped 且不产生第二笔。空标识不参与判定。
     *
     * @param importRecordId 导入文件记录标识；空值视为无标识，直接未命中
     * @param excludeId 排除的账单 ID（更新场景排除自身），传 null 表示不排除
     */
    suspend fun checkLevel3(
        importRecordId: String?,
        excludeId: String? = null
    ): BillEntity? {
        if (importRecordId.isNullOrBlank()) return null
        return database.billDao().findBySourceTypeAndImportRecordId(
            sourceType = IMPORT_SOURCE_TYPE,
            importRecordId = importRecordId,
            excludeId = excludeId
        )
    }

    /**
     * 第 4 级：疑似重复综合匹配（只提示不合并）。
     *
     * 判定因素与阈值：
     * - 基础（SQL 过滤）：金额完全相等 + 类型相同 + 时间窗口 ±[SIMILAR_WINDOW_MILLIS]；
     * - 币种：账单无独立币种列，由账户币种（种子数据 CNY）隐含；
     * - 辅因素（至少命中一项才判定疑似重复，降低误报）：
     *   a) 账户相同（accountId 相等且非空）；
     *   b) 商户相似（完全相等，或一方包含另一方，忽略首尾空白）；
     *   c) 双方商户均为空（同金额同时段同类型无商户信息，弱相似）。
     *
     * @param type 交易类型
     * @param amountMinor 金额（分）
     * @param occurredAt 发生时间（窗口中心）
     * @param accountId 账户 ID（可空，空不参与比较）
     * @param merchant 商户名（可空，空不参与比较）
     * @param excludeId 排除的账单 ID（如同一确定性主键）
     * @return 相似命中清单（可能多条），空列表表示未命中
     */
    suspend fun findSimilar(
        type: String,
        amountMinor: Long,
        occurredAt: Long,
        accountId: String?,
        merchant: String?,
        excludeId: String? = null
    ): List<SimilarHit> {
        if (amountMinor <= 0L || occurredAt <= 0L) return emptyList()
        val candidates = database.billDao().findSimilarAmountWindow(
            type = type,
            amountMinor = amountMinor,
            fromAt = occurredAt - SIMILAR_WINDOW_MILLIS,
            toAt = occurredAt + SIMILAR_WINDOW_MILLIS,
            excludeId = excludeId
        )
        return candidates.mapNotNull { candidate -> similarFactors(candidate, accountId, merchant)
            ?.takeIf { it.isNotEmpty() }
            ?.let { SimilarHit(candidate, it) }
        }
    }

    /** 组装第 4 级命中的 appliedRules 提示 JSON（重复账单 ID 与因素）。 */
    fun suspectedDuplicateHint(hits: List<SimilarHit>): String = JSONObject().apply {
        put("suspectedDuplicate", true)
        put("duplicateOf", JSONArray().apply { hits.forEach { put(it.bill.id) } })
        put(
            "factors",
            JSONArray().apply { hits.forEach { hit -> hit.factors.forEach { put(it) } } }
        )
    }.toString()

    /** 单候选辅因素判定：命中返回因素清单，基础不匹配返回 null。 */
    private fun similarFactors(
        candidate: BillEntity,
        accountId: String?,
        merchant: String?
    ): List<String>? {
        val factors = mutableListOf<String>()
        when {
            !accountId.isNullOrBlank() && candidate.accountId == accountId ->
                factors += "ACCOUNT_SAME"
            else -> Unit
        }
        val normalizedMerchant = merchant?.trim()?.takeIf { it.isNotEmpty() }
        val candidateMerchant = candidate.merchant?.trim()?.takeIf { it.isNotEmpty() }
        when {
            normalizedMerchant != null && candidateMerchant != null -> {
                if (normalizedMerchant == candidateMerchant) {
                    factors += "MERCHANT_EQUAL"
                } else if (
                    normalizedMerchant.contains(candidateMerchant) ||
                    candidateMerchant.contains(normalizedMerchant)
                ) {
                    factors += "MERCHANT_CONTAINS"
                }
            }
            normalizedMerchant == null && candidateMerchant == null ->
                factors += "MERCHANT_BOTH_EMPTY"
            else -> Unit
        }
        return factors
    }

    companion object {
        /** 待确认冲突原因：第 4 级疑似重复命中（只提示不合并，用户可确认两笔并存）。 */
        const val CONFLICT_SUSPECTED_DUPLICATE = "SUSPECTED_DUPLICATE"

        /** 待确认冲突原因：去重检查异常（查询失败/并发冲突），宁可转待确认不静默入账。 */
        const val CONFLICT_DEDUP_CHECK_FAILED = "DEDUP_CHECK_FAILED"

        /** 疑似重复审计实体类型（确认入账前提示留痕，供前端 T7 展示）。 */
        const val AUDIT_SUSPECTED_DUPLICATE = "suspected_duplicate"

        /** 阶段六导入账单的 sourceType 常量（第 3 级去重固定在此来源内）。 */
        const val IMPORT_SOURCE_TYPE = "import"

        /** 第 4 级时间窗口（毫秒）：发生时间前后 10 分钟。 */
        const val SIMILAR_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
