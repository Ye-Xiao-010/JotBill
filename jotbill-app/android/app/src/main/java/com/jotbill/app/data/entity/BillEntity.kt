package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["category_id"]),
        Index(value = ["account_id"]),
        Index(value = ["target_account_id"]),
        Index(value = ["platform"]),
        Index(value = ["order_id"]),
        Index(value = ["source_type"]),
        Index(value = ["source_type", "import_record_id"]),
        Index(value = ["original_transaction_id"]),
        Index(value = ["occurred_at", "type"]),
        Index(value = ["type", "occurred_at", "amount_minor"]),
        Index(value = ["source_type", "order_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_account_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BillEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val description: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "target_account_id") val targetAccountId: String?,
    val platform: String?,
    val merchant: String?,
    @ColumnInfo(name = "product_description") val productDescription: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "original_transaction_id") val originalTransactionId: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Int,
    /**
     * 阶段五 T5：用户锁定的字段名集合（JSON 数组字符串，如 `["platform","categoryId"]`）。
     * 锁定字段不被任何低优先级来源（规则/证据/AI/会话/默认）覆盖，只产生冲突提示；
     * 旧库迁移与新建账单默认空数组，行为与锁定功能引入前完全一致。
     * 锁定只作用于字段级，不支持行级整体锁。
     */
    @ColumnInfo(name = "field_locks", defaultValue = "[]") val fieldLocks: String = "[]",
    /**
     * 阶段六 T1：导入文件记录标识（如微信交易单号/支付宝交易订单号）。
     * 仅 sourceType = "import" 的账单使用；旧库迁移与实时链路账单保持 NULL。
     */
    @ColumnInfo(name = "import_record_id") val importRecordId: String? = null
)
