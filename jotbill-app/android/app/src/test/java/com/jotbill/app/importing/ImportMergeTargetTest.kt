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

    private companion object {
        /** P3 用：固定"当天"基准（本地日 2027-01-15 08:00 附近，同日窗判定稳定）。 */
        const val BASE_DAY = 1_800_000_000_000L
    }

    private lateinit var database: JotBillDatabase
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
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
        originalTransactionId: String? = null,
        occurredAt: Long = 1_000_000L,
        amountMinor: Long = 1_000L,
        merchant: String? = "拼多多"
    ) = BillEntity(
        id = id, amountMinor = amountMinor, type = type,
        occurredAt = occurredAt, description = null, categoryId = null,
        accountId = null, targetAccountId = null, platform = "pdd",
        merchant = merchant, productDescription = null, sourceType = "manual",
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

    // ── P3 拍板③：无商户订单号的强一致合并（金额精确 + 同日 + 商户模糊）──

    private fun importRow(
        occurredAt: Long,
        amountMinor: Long,
        merchant: String?
    ) = ParsedImportRow(
        occurredAt = occurredAt,
        amountMinor = amountMinor,
        type = "expense",
        merchant = merchant,
        productDescription = null,
        paymentMethod = null,
        transactionId = "txn-strong-1",
        merchantOrderId = null,
        remark = null,
        rawStatus = "SUCCESS",
        sourceLineNumber = 1
    )

    /** 三条件全部命中且唯一 ⇒ 强一致合并目标命中（MERGED）。 */
    @Test
    fun strongIdentityAllConditionsHitMerges() = runBlocking {
        database.billDao().insert(
            bill(
                id = "target-1", type = "expense", orderId = null,
                occurredAt = BASE_DAY + 3_600_000L, amountMinor = 5_000L, merchant = "拼多多"
            )
        )

        val target = ImportEngine(context, database).findStrongIdentityMergeTarget(
            importRow(
                occurredAt = BASE_DAY + 7_200_000L,
                amountMinor = 5_000L,
                merchant = "拼多多旗舰店"
            )
        )

        assertEquals("金额精确+同日+商户模糊全中应唯一命中", "target-1", target?.id)
    }

    /** 缺任一条件（金额不同 / 跨日 / 商户不匹配）或多候选 ⇒ 退回只提示（null）。 */
    @Test
    fun strongIdentityMissingAnyConditionReturnsNull() = runBlocking {
        val engine = ImportEngine(context, database)
        database.billDao().insert(
            bill(
                id = "target-2", type = "expense", orderId = null,
                occurredAt = BASE_DAY + 3_600_000L, amountMinor = 5_000L, merchant = "拼多多"
            )
        )

        assertEquals(
            "金额不同不得合并",
            null,
            engine.findStrongIdentityMergeTarget(
                importRow(BASE_DAY + 3_600_000L, amountMinor = 5_001L, merchant = "拼多多")
            )?.id
        )
        assertEquals(
            "跨日不得合并",
            null,
            engine.findStrongIdentityMergeTarget(
                importRow(BASE_DAY + 86_400_000L, amountMinor = 5_000L, merchant = "拼多多")
            )?.id
        )
        assertEquals(
            "商户不匹配不得合并",
            null,
            engine.findStrongIdentityMergeTarget(
                importRow(BASE_DAY + 3_600_000L, amountMinor = 5_000L, merchant = "美团外卖")
            )?.id
        )
        assertEquals(
            "商户缺失不得合并（模糊匹配无对象）",
            null,
            engine.findStrongIdentityMergeTarget(
                importRow(BASE_DAY + 3_600_000L, amountMinor = 5_000L, merchant = null)
            )?.id
        )

        // 多候选 ⇒ 只提示不合并
        database.billDao().insert(
            bill(
                id = "target-3", type = "expense", orderId = null,
                occurredAt = BASE_DAY + 5_400_000L, amountMinor = 5_000L, merchant = "拼多多"
            )
        )
        assertEquals(
            "多候选命中不得擅自择一合并",
            null,
            engine.findStrongIdentityMergeTarget(
                importRow(BASE_DAY + 3_600_000L, amountMinor = 5_000L, merchant = "拼多多")
            )?.id
        )
    }
}
