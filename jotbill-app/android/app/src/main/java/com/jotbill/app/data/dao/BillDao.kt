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

    /**
     * 去重第 1 级：按 **orderId** 精确查重（跨来源互认）。
     *
     * 命中说明同一笔交易已入账，新证据应复用/更新原账单而非产生第二笔。
     *
     * ⚠️ **不按 source_type 区分**：同一笔被页面规则、通知监听、账单导入任一通道
     * 先入账后，其余通道到达时都应归并到同一条 —— 这是「多信号合成一条正确账」的
     * 唯一客观判据。历史上按 `(sourceType, orderId)` 查导致跨通道同笔无法归并
     * （只能落 SUSPECTED_DUPLICATE 待确认）；而同仓的
     * [findByOrderIdForImportMerge] 一直只看 orderId，两条口径不一致。
     *
     * **例外：`source_type = 'manual'` 不参与互认** —— 用户手写的账可能带刻意调整
     * 的金额/备注，不得被自动链路擅自吸收或改写。
     *
     * @param excludeId 排除的账单 ID（更新场景排除自身），传 null 表示不排除
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE UPPER(TRIM(order_id)) = UPPER(TRIM(:orderId))
          AND source_type != 'manual'
          AND (:excludeId IS NULL OR id != :excludeId)
        LIMIT 1
        """
    )
    suspend fun findByOrderIdForAutoMerge(
        orderId: String,
        excludeId: String? = null
    ): BillEntity?

    /**
     * 阶段六 T1 去重第 3 级：按导入文件记录标识精确查重。
     * 命中说明同一账单文件的同一记录已入账，执行时应跳过该行。
     *
     * @param excludeId 排除的账单 ID（更新场景排除自身），传 null 表示不排除
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE source_type = :sourceType
          AND import_record_id = :importRecordId
          AND (:excludeId IS NULL OR id != :excludeId)
        LIMIT 1
        """
    )
    suspend fun findBySourceTypeAndImportRecordId(
        sourceType: String,
        importRecordId: String,
        excludeId: String? = null
    ): BillEntity?

    /**
     * 阶段六 T5 跨来源订单号合并候选：导入行的商户订单号命中既有账单。
     * 不限制 sourceType（含自动识别、手动、历史导入），由导入引擎经
     * FieldPriorityResolver(Source.IMPORT) 决定可变字段是否写入。
     * 退款行由引擎豁免，不调用本查询。
     * 合并目标同样排除退款/带原交易关联的账单：RefundLinker 创建的退款账单
     * 携带与原单相同的 order_id，若命中会把 refund 覆写为 expense 并造成
     * 余额双重扣减（行侧豁免 + 目标侧豁免必须成对）。
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE UPPER(TRIM(order_id)) = UPPER(TRIM(:orderId))
          AND type != 'refund'
          AND original_transaction_id IS NULL
        ORDER BY occurred_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findByOrderIdForImportMerge(orderId: String): BillEntity?

    /**
     * 阶段五 T6 去重第 4 级候选查询：金额相等 + 类型相同 + 时间窗口内。
     * 币种因素由账户币种（CNY）隐含；账户/商户相似度由 DuplicateGuard 在内存判定。
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE type = :type
          AND amount_minor = :amountMinor
          AND occurred_at BETWEEN :fromAt AND :toAt
          AND (:excludeId IS NULL OR id != :excludeId)
        """
    )
    suspend fun findSimilarAmountWindow(
        type: String,
        amountMinor: Long,
        fromAt: Long,
        toAt: Long,
        excludeId: String? = null
    ): List<BillEntity>

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
              OR description LIKE '%' || :keyword || '%' ESCAPE '~'
              OR merchant LIKE '%' || :keyword || '%' ESCAPE '~'
              OR product_description LIKE '%' || :keyword || '%' ESCAPE '~'
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

    /**
     * 按筛选条件计数（供分页 total 使用）。
     *
     * ⚠️ 三条 LIKE 必须与 [query] **逐字一致**（含 `ESCAPE '~'`）：`DataModule.getBills`
     * 把同一个已由 `escapeLikeKeyword` 转义过的 keyword 同时喂给两者。若此处漏 ESCAPE，
     * 搜索 `100%` 时 `~` 是普通字符而 `%` 仍是通配符 → total 虚高；
     * 搜索含 `_` 时 `_` 变单字符通配符 → total 失真，`hasMore` 随之出错、翻页出空白页。
     */
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
              OR description LIKE '%' || :keyword || '%' ESCAPE '~'
              OR merchant LIKE '%' || :keyword || '%' ESCAPE '~'
              OR product_description LIKE '%' || :keyword || '%' ESCAPE '~'
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
          COALESCE(parent.id, b.category_id) AS categoryId,
          COALESCE(parent.name, c.name) AS categoryName,
          COALESCE(parent.icon, c.icon) AS categoryIcon,
          SUM(b.amount_minor) AS amountMinor,
          COUNT(*) AS count
        FROM bills b
        LEFT JOIN categories c ON b.category_id = c.id
        LEFT JOIN categories parent ON parent.id = c.parent_id
        WHERE (
              b.type = :type
              OR (:type = 'income' AND b.type = 'refund')
              OR (:type = 'expense' AND b.type = 'fee')
          )
          AND b.occurred_at >= :startDate
          AND b.occurred_at <= :endDate
        GROUP BY COALESCE(parent.id, b.category_id),
                 COALESCE(parent.name, c.name),
                 COALESCE(parent.icon, c.icon)
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
          AND (:categoryId IS NULL OR category_id = :categoryId
               OR category_id IN (
                   SELECT id FROM categories WHERE parent_id = :categoryId
               ))
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

    /** 该商户最近一次归类用的支出分类，供付款确认弹窗预选（merchant 精确匹配）。 */
    @Query(
        "SELECT category_id FROM bills " +
            "WHERE merchant = :merchant AND category_id IS NOT NULL AND type = 'expense' " +
            "ORDER BY occurred_at DESC LIMIT 1"
    )
    suspend fun findLastExpenseCategoryIdByMerchant(merchant: String): String?

    /** 最近一次支出的分类，供付款确认弹窗在无商户命中时预选。 */
    @Query(
        "SELECT category_id FROM bills " +
            "WHERE category_id IS NOT NULL AND type = 'expense' " +
            "ORDER BY occurred_at DESC LIMIT 1"
    )
    suspend fun findLastExpenseCategoryId(): String?

    @Query(
        """
        SELECT * FROM bills
        WHERE UPPER(TRIM(order_id)) = UPPER(TRIM(:orderId)) AND type = 'expense'
        ORDER BY occurred_at DESC
        LIMIT 1
        """
    )
    suspend fun findOriginalForRefund(orderId: String): BillEntity?

    /**
     * 阶段五 T2：按订单号查全部支出账单。
     * 退款原交易关联需要感知多笔命中（同订单号多笔支出时不自动关联，转待确认由用户选择）。
     *
     * M5 归一：与 [findByOrderIdForAutoMerge] 同款 `UPPER(TRIM())` 双侧归一 ——
     * 存量小写/带空白单号的账单对归一后的退款号（OrderIdNormalizer 大写半角）
     * 不再 miss（复核发现的退款原单关联漏配缺口）。
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE UPPER(TRIM(order_id)) = UPPER(TRIM(:orderId)) AND type = 'expense'
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

/**
 * LIKE 关键字转义：query() 的 LIKE 子句带 ESCAPE '~'（Room 校验器不接受 '\'），
 * 调用方传入用户可控的 keyword 前必须先经过本函数，否则 % 和 _ 会被当通配符
 * （搜索 "100%" 变全匹配）。
 */
internal fun escapeLikeKeyword(value: String?): String? =
    value
        ?.replace("~", "~~")
        ?.replace("%", "~%")
        ?.replace("_", "~_")
        ?.takeIf { it.isNotBlank() }
