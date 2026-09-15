package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.RuleEntity

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    // 排序与 RuleEngine 仲裁一致：priority 数字小者优先，同优先级按创建先后；
    // 此前列表用 DESC 展示，与引擎实际执行顺序相反，易误导排查
    @Query("SELECT * FROM rules WHERE is_active = 1 ORDER BY priority ASC, created_at ASC")
    suspend fun getActive(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY priority ASC, created_at ASC")
    suspend fun getAll(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: String): RuleEntity?

    /** 命中计数 +1；只累加 hit_count 不动 updated_at（命中不算规则编辑）。 */
    @Query("UPDATE rules SET hit_count = hit_count + 1 WHERE id = :id")
    suspend fun incrementHitCount(id: String)
}
