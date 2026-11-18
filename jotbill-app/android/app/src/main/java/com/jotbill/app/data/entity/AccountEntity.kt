package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["sort_order"]),
        Index(value = ["is_active"])
    ]
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtype: String,
    val kind: String,
    @ColumnInfo(name = "initial_balance_minor") val initialBalanceMinor: Long,
    @ColumnInfo(name = "current_balance_minor") val currentBalanceMinor: Long,
    val currency: String,
    val icon: String?,
    val color: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * 银行卡尾号（后 4 位，可空）：银行短信/退款短信里的卡号尾号据此精准匹配账户。
     * 未填写时回退按银行名匹配；填写后只在**唯一命中**时使用，不猜测。
     */
    @ColumnInfo(name = "card_tail") val cardTail: String? = null
)
