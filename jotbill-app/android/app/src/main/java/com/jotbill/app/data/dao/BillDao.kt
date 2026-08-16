package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.model.BillSummary
import com.jotbill.app.data.model.CategoryStatRow
import com.jotbill.app.data.model.PlatformStatRow
import com.jotbill.app.data.model.TrendRow
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bill: BillEntity): Long

    @Update
    suspend fun update(bill: BillEntity)

    @Query(
        """
        UPDATE bills SET
          amount_minor = :amountMinor,
          type = :type,
          occurred_at = :occurredAt,
          description = :description,
          category_id = :categoryId,
          account_id = :accountId,
          target_account_id = :targetAccountId,
          platform = :platform,
          merchant = :merchant,
          product_description = :productDescription,
          source_type = :sourceType,
          original_transaction_id = :originalTransactionId,
          order_id = :orderId,
          field_locks = :fieldLocks,
          updated_at = :updatedAt,
          version = :newVersion
        WHERE id = :id AND version = :expectedVersion
        """
    )
    suspend fun updateIfVersion(
        id: String,
        expectedVersion: Int,
        amountMinor: Long,
        type: String,
        occurredAt: Long,
        description: String?,
        categoryId: String?,
        accountId: String?,
        targetAccountId: String?,
        platform: String?,
        merchant: String?,
        productDescription: String?,
        sourceType: String,
        originalTransactionId: String?,
        orderId: String?,
        fieldLocks: String,
        updatedAt: Long,
        newVersion: Int
    ): Int

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: String): BillEntity?

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: String): Flow<BillEntity?>

    @Query("SELECT COUNT(*) FROM bills WHERE category_id = :categoryId")
    suspend fun countByCategoryId(categoryId: String): Int

    @Query(
        """
        SELECT * FROM bills
        WHERE (:type IS NULL OR type = :type)
          AND (:categoryId IS NULL OR category_id = :categoryId)
          AND (:accountId IS NULL OR account_id = :accountId)
          AND (:platform IS NULL OR platform = :platform)
          AND (:merchant IS NULL OR merchant = :merchant)
          AND (:sourceType IS NULL OR source_type = :sourceType)
          AND (:startDate IS NULL OR occurred_at >= :startDate)
          AND (:endDate IS NULL OR occurred_at <= :endDate)
          AND (
              :keyword IS NULL
              OR description LIKE '%' || :keyword || '%'
              OR merchant LIKE '%' || :keyword || '%'
              OR product_description LIKE '%' || :keyword || '%'
          )
        ORDER BY
          CASE WHEN :orderBy = 'date' AND :orderDirection = 'asc' THEN occurred_at END ASC,
          CASE WHEN :orderBy = 'date' AND :orderDirection = 'desc' THEN occurred_at END DESC,
          CASE WHEN :orderBy = 'amount' AND :orderDirection = 'asc' THEN amount_minor END ASC,
          CASE WHEN :orderBy = 'amount' AND :orderDirection = 'desc' THEN amount_minor END DESC,
          CASE WHEN :orderBy = 'createdAt' AND :orderDirection = 'asc' THEN created_at END ASC,
          CASE WHEN :orderBy = 'createdAt' AND :orderDirection = 'desc' THEN created_at END DESC,
          id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun query(
        type: String?,
        categoryId: String?,
        accountId: String?,
        platform: String?,
        merchant: String?,
        sourceType: String?,
        startDate: Long?,
        endDate: Long?,
        keyword: String?,
        orderBy: String,
        orderDirection: String,
        limit: Int,
        offset: Int
    ): List<BillEntity>

    @Query(
        """
        SELECT COUNT(*) FROM bills
        WHERE (:type IS NULL OR type = :type)
          AND (:categoryId IS NULL OR category_id = :categoryId)
          AND (:accountId IS NULL OR account_id = :accountId)
          AND (:platform IS NULL OR platform = :platform)
          AND (:merchant IS NULL OR merchant = :merchant)
          AND (:sourceType IS NULL OR source_type = :sourceType)
          AND (:startDate IS NULL OR occurred_at >= :startDate)
          AND (:endDate IS NULL OR occurred_at <= :endDate)
          AND (
              :keyword IS NULL
              OR description LIKE '%' || :keyword || '%'
              OR merchant LIKE '%' || :keyword || '%'
              OR product_description LIKE '%' || :keyword || '%'
          )
        """
    )
    suspend fun countFiltered(
        type: String?,
        categoryId: String?,
        accountId: String?,
        platform: String?,
        merchant: String?,
        sourceType: String?,
        startDate: Long?,
        endDate: Long?,
        keyword: String?
    ): Int

    @Query("SELECT COUNT(*) FROM bills")
    suspend fun count(): Int

    @Query("SELECT * FROM bills ORDER BY occurred_at ASC, id ASC")
    suspend fun getAllForExport(): List<BillEntity>

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN type IN ('income', 'refund') THEN amount_minor ELSE 0 END), 0) AS totalIncome,
          COALESCE(SUM(CASE WHEN type IN ('expense', 'fee') THEN amount_minor ELSE 0 END), 0) AS totalExpense,
          COUNT(CASE WHEN type IN ('income', 'refund') THEN 1 END) AS incomeCount,
          COUNT(CASE WHEN type IN ('expense', 'fee') THEN 1 END) AS expenseCount
        FROM bills
        WHERE occurred_at >= :startDate AND occurred_at <= :endDate
        """
    )
    suspend fun getSummary(startDate: Long, endDate: Long): BillSummary

    @Query(
        """
        SELECT
          b.category_id AS categoryId,
          c.name AS categoryName,
          c.icon AS categoryIcon,
          SUM(b.amount_minor) AS amountMinor,
          COUNT(*) AS count
        FROM bills b
        LEFT JOIN categories c ON b.category_id = c.id
        WHERE (
              b.type = :type
              OR (:type = 'income' AND b.type = 'refund')
              OR (:type = 'expense' AND b.type = 'fee')
          )
          AND b.occurred_at >= :startDate
          AND b.occurred_at <= :endDate
        GROUP BY b.category_id, c.name, c.icon
        ORDER BY amountMinor DESC
        """
    )
    suspend fun getCategoryStats(
        type: String,
        startDate: Long,
        endDate: Long
    ): List<CategoryStatRow>

    @Query(
        """
        SELECT
          platform AS platform,
          SUM(amount_minor) AS amountMinor,
          COUNT(*) AS count
        FROM bills
        WHERE type IN ('expense', 'fee')
          AND occurred_at >= :startDate
          AND occurred_at <= :endDate
          AND platform IS NOT NULL
        GROUP BY platform
        ORDER BY amountMinor DESC
        """
    )
    suspend fun getPlatformStats(startDate: Long, endDate: Long): List<PlatformStatRow>

    @Query(
        """
        SELECT
          strftime('%Y-%m-%d', occurred_at / 1000, 'unixepoch', 'localtime') AS period,
          CASE
            WHEN type IN ('income', 'refund') THEN 'income'
            WHEN type IN ('expense', 'fee') THEN 'expense'
            ELSE type
          END AS type,
          SUM(amount_minor) AS amountMinor
        FROM bills
        WHERE occurred_at >= :startDate
          AND occurred_at <= :endDate
          AND type IN ('income', 'refund', 'expense', 'fee')
        GROUP BY period, type
        ORDER BY period ASC
        """
    )
    suspend fun getDailyTrends(startDate: Long, endDate: Long): List<TrendRow>

    @Query(
        """
        SELECT
          strftime('%Y-%m', occurred_at / 1000, 'unixepoch', 'localtime') AS period,
          CASE
            WHEN type IN ('income', 'refund') THEN 'income'
            WHEN type IN ('expense', 'fee') THEN 'expense'
            ELSE type
          END AS type,
          SUM(amount_minor) AS amountMinor
        FROM bills
        WHERE occurred_at >= :startDate
          AND occurred_at <= :endDate
          AND type IN ('income', 'refund', 'expense', 'fee')
        GROUP BY period, type
        ORDER BY period ASC
        """
    )
    suspend fun getMonthlyTrends(startDate: Long, endDate: Long): List<TrendRow>

    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0)
        FROM bills
        WHERE type IN ('expense', 'fee')
          AND occurred_at >= :startDate
          AND occurred_at <= :endDate
          AND (:categoryId IS NULL OR category_id = :categoryId)
        """
    )
    suspend fun sumExpenses(
        categoryId: String?,
        startDate: Long,
        endDate: Long
    ): Long

    @Query("SELECT * FROM bills WHERE order_id = :orderId AND amount_minor = :amountMinor LIMIT 1")
    suspend fun findByOrderAndAmount(orderId: String, amountMinor: Long): BillEntity?

    /** 按来源溯源 ID（如结算 sessionId）查账单，用于判断该会话是否已手动确认入账。 */
    @Query("SELECT * FROM bills WHERE original_transaction_id = :originalTransactionId LIMIT 1")
    suspend fun findByOriginalTransactionId(originalTransactionId: String): BillEntity?

    @Query(
        """
        SELECT * FROM bills
        WHERE order_id = :orderId AND type = 'expense'
        ORDER BY occurred_at DESC
        LIMIT 1
        """
    )
    suspend fun findOriginalForRefund(orderId: String): BillEntity?

    /**
     * 阶段五 T2：按订单号查全部支出账单。
     * 退款原交易关联需要感知多笔命中（同订单号多笔支出时不自动关联，转待确认由用户选择）。
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE order_id = :orderId AND type = 'expense'
        ORDER BY occurred_at DESC
        """
    )
    suspend fun findExpensesByOrderId(orderId: String): List<BillEntity>

    /**
     * 阶段五 T2：原交易已入账退款合计（多次部分退款的累计口径）。
     * 统计 original_transaction_id 指向原账单且 type = 'refund' 的全部退款。
     */
    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0)
        FROM bills
        WHERE original_transaction_id = :originalBillId AND type = 'refund'
        """
    )
    suspend fun sumRefundsByOriginalId(originalBillId: String): Long

    /**
     * 阶段五 T2：无订单号时的退款原交易组合候选。
     * 条件：支出类型、金额不小于退款额（部分退款时原金额≥退款额）、
     * 发生时间在退款时间前的窗口内、商户精确匹配或平台匹配。
     * 仅用于待确认提示，不作为自动入账依据。
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE type = 'expense'
          AND amount_minor >= :minAmountMinor
          AND occurred_at >= :startDate
          AND occurred_at <= :endDate
          AND (
              (:merchant IS NOT NULL AND merchant = :merchant)
              OR (:platform IS NOT NULL AND platform = :platform)
          )
        ORDER BY occurred_at DESC
        LIMIT :limit
        """
    )
    suspend fun findRefundCandidates(
        merchant: String?,
        platform: String?,
        minAmountMinor: Long,
        startDate: Long,
        endDate: Long,
        limit: Int
    ): List<BillEntity>
}
