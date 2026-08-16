package com.jotbill.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rules",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["priority"])
    ]
)
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val condition: String,
    @ColumnInfo(name = "condition_value") val conditionValue: String,
    val action: String,
    @ColumnInfo(name = "action_value") val actionValue: String?,
    val priority: Int,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    /** 规则命中次数：引擎每次命中在事务内 +1，供规则页展示与冲突提示。 */
    @ColumnInfo(name = "hit_count", defaultValue = "0") val hitCount: Int = 0,
    /** 创建来源：manual（用户手动创建）或 from_correction（用户修正记录后创建）。 */
    @ColumnInfo(name = "source", defaultValue = "manual") val source: String = "manual",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_FROM_CORRECTION = "from_correction"
    }
}
