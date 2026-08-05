package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "balance_calibrations",
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["created_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BalanceCalibrationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "before_balance_minor") val beforeBalanceMinor: Long,
    @ColumnInfo(name = "after_balance_minor") val afterBalanceMinor: Long,
    @ColumnInfo(name = "diff_minor") val diffMinor: Long,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
