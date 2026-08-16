package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jotbill.app.data.entity.AuditEntryEntity

@Dao
interface AuditEntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditEntryEntity): Long

    /** 幂等插入：同一确定性 ID 的审计（如规则 ignore 决策）重复触发时静默跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries WHERE entity_type = :type AND entity_id = :id ORDER BY created_at DESC")
    suspend fun getByEntity(type: String, id: String): List<AuditEntryEntity>

    /**
     * 阶段六 T1/T6：按实体类型与操作者查询审计。
     * 导入撤销依赖 operator = batchId 反查批次新建账单与合并更新记录。
     */
    @Query(
        """
        SELECT * FROM audit_entries
        WHERE entity_type = :type AND operator = :operator
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getByEntityTypeAndOperator(type: String, operator: String): List<AuditEntryEntity>

    /**
     * 阶段六 T6：查询某操作者之后（导入批次之后）的用户更新审计。
     * 用于撤销保护判定——存在导入后的用户更新即视为用户已修改，不自动删除/回滚。
     */
    @Query(
        """
        SELECT * FROM audit_entries
        WHERE entity_type = :type AND operator = :operator AND created_at > :after
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getByEntityTypeAndOperatorAfter(
        type: String,
        operator: String,
        after: Long
    ): List<AuditEntryEntity>

    @Query("DELETE FROM audit_entries WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}
