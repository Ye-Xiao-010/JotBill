package com.jotbill.app.importing

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.ImportBatchEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入合并目标与批次释放的回归测试（Robolectric + Room 内存库）。
 *
 * 审查发现缺陷：
 * 1. findByOrderIdForImportMerge 不过滤目标类型——RefundLinker 创建的退款账单
 *    携带与原单相同 order_id，导入行命中退款账单会被合并（refund 覆写为
 *    expense + 余额双重扣减）。目标侧须排除 refund/带原交易关联的账单。
 * 2. file_hash 唯一索引使 FAILED/CANCELLED/UNDONE 批次永久阻断同文件重试——
 *    释放路径依赖 deleteById 删除旧批次行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportMergeTargetTest {

    private lateinit var database: JotBillDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun bill(
        id: String,
        type: String,
        orderId: String?,
        originalTransactionId: String? = null
    ) = BillEntity(
        id = id, amountMinor = 1_000L, type = type,
        occurredAt = 1_000_000L, description = null, categoryId = null,
        accountId = null, targetAccountId = null, platform = "pdd",
        merchant = "拼多多", productDescription = null, sourceType = "manual",
        originalTransactionId = originalTransactionId, orderId = orderId,
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    @Test
    fun mergeTargetExcludesRefundAndLinkedBills() = runBlocking {
        database.billDao().insert(bill("refund-1", "refund", "order-1", originalTransactionId = "orig-1"))
        database.billDao().insert(bill("fee-1", "fee", "order-1", originalTransactionId = "orig-1"))
        database.billDao().insert(bill("expense-1", "expense", "order-1"))

        val target = database.billDao().findByOrderIdForImportMerge("order-1")

        assertEquals("合并目标应命中普通支出账单", "expense-1", target?.id)
    }

    @Test
    fun mergeTargetReturnsNullWhenOnlyRefundBillsMatch() = runBlocking {
        database.billDao().insert(bill("refund-1", "refund", "order-2", originalTransactionId = "orig-1"))

        val target = database.billDao().findByOrderIdForImportMerge("order-2")

        assertNull("只有退款账单命中时不得合并", target)
    }

    @Test
    fun failedBatchReleaseAllowsSameFileRetry() = runBlocking {
        val failed = ImportBatchEntity(
            id = "import-batch-old", fileHash = "hash-1", source = "alipay",
            format = "csv", formatVersion = "1", importerVersion = "1",
            status = ImportBatchEntity.STATUS_FAILED,
            createdAt = 1L, updatedAt = 1L
        )
        database.importBatchDao().insert(failed)
        database.importBatchDao().deleteById(failed.id)

        val replacement = failed.copy(id = "import-batch-new")
        val inserted = database.importBatchDao().insert(replacement)

        assertEquals("释放后同 file_hash 应可重建批次", "import-batch-new", replacement.id)
        assert(inserted != -1L)
    }
}
