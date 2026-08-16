package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待确认交易实体。
 *
 * 阶段四 T6 起新增 status 列区分流转状态：
 * - pending：等待用户在待确认中心处理（默认值）；
 * - ignored：用户忽略，不写 Bill、不更新余额，但保留记录供审计，不物理删除。
 * 确认入账的记录在写 Bill 成功后物理删除（由 Bill 与审计日志承接追溯）。
 */
@Entity(
    tableName = "pending_transactions",
    indices = [
        Index(value = ["candidate_id"]),
        Index(value = ["session_id"]),
        Index(value = ["created_at"]),
        Index(value = ["status"])
    ]
)
data class PendingTransactionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String?,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val platform: String?,
    val merchant: String?,
    @ColumnInfo(name = "product_description") val productDescription: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "conflict_reason") val conflictReason: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    /**
     * 规则引擎命中记录（阶段五 T1）：JSON 对象，键为 `rule:<规则ID>`、值为动作摘要，
     * 与候选 appliedRules 约定一致；纯通知路径没有候选，命中记录落在本列。
     * 桥接层暂不序列化（Native.d.ts 的 PendingTransaction 未声明该字段）。
     */
    @ColumnInfo(name = "applied_rules", defaultValue = "{}") val appliedRules: String = "{}",
    /**
     * 阶段五 T2：退款待确认关联的原交易账单 ID。
     * 原交易号唯一命中时记录；确认入账时由 LedgerRepository 回填到 Bill.originalTransactionId，
     * 保证多次部分退款的累计口径。其余路径为 null。
     */
    @ColumnInfo(name = "original_bill_id") val originalBillId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val version: Int,
    @ColumnInfo(name = "status", defaultValue = "pending") val status: String = "pending"
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_IGNORED = "ignored"
    }
}
