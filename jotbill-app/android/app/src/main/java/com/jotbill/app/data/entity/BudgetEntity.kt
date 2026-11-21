package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["category_id"]),
        Index(value = ["is_active"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val period: String,
    /** 月周期起始日 1..31；31 表示该月最后一天。 */
    @ColumnInfo(name = "start_day") val startDay: Int? = null,
    /** 周周期起始星期（ISO：1=周一 … 7=周日）；双周由 anchorAt 决定。 */
    @ColumnInfo(name = "week_start") val weekStart: Int? = null,
    /** 双周周期的锚点日（本地时区当日 00:00 的 epoch millis）。 */
    @ColumnInfo(name = "anchor_at") val anchorAt: Long? = null,
    /** 一次性区间开始（含当日）。 */
    @ColumnInfo(name = "range_start_at") val rangeStartAt: Long? = null,
    /** 一次性区间结束（含当日，存当日最后一毫秒）。 */
    @ColumnInfo(name = "range_end_at") val rangeEndAt: Long? = null,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "alert_at") val alertAt: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
