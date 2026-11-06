package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1 回归测试：L1 去重（orderId 合并）的金额/类型一致性。
 *
 * 行为变更（2026-11-06 拍板）：同 orderId 不再无条件静默合并 ——
 * 金额与类型必须一致才合并（Consistent），撞号（改价重付/单号复用/
 * 类型相异）抛 [OrderConflictException]，由调用方转待确认保留新证据。
 * 旧断言解决的问题（同单同额的真实重复信号必须合并）由
 * [同单同额同类型仍合并进既有账单] 回归钉承接。
 *
 * 驱动：Robolectric + Room 内存库直驱 [LedgerRepository.createBillWithDedup]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerLevel1ConsistencyTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ACCOUNT_ID = "default-account-wechat"
    }

    private lateinit var database: JotBillDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(database)
        runBlocking {
            database.accountDao().insert(
                AccountEntity(
                    id = ACCOUNT_ID, name = "微信零钱", subtype = "cash",
                    kind = "asset", initialBalanceMinor = 0L, currentBalanceMinor = 0L,
                    currency = "CNY", icon = null, color = null, sortOrder = 0,
                    isActive = true, createdAt = NOW, updatedAt = NOW
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 构造最小合法账单；只显式指定本测试关心的字段。 */
    private fun bill(
        id: String,
        amount: Long,
        orderId: String?,
        type: String = "expense",
        merchant: String? = null
    ) = BillEntity(
        id = id,
        amountMinor = amount,
        type = type,
        occurredAt = NOW,
        description = merchant,
        categoryId = null,
        accountId = ACCOUNT_ID,
        targetAccountId = null,
        platform = "com.tencent.mm",
        merchant = merchant,
        productDescription = null,
        sourceType = "notification",
        originalTransactionId = null,
        orderId = orderId,
        createdAt = NOW,
        updatedAt = NOW,
        version = 1
    )

    /** 回归钉：同单号同金额同类型 ⇒ 真实重复信号仍合并（只补空，不产生第二笔）。 */
    @Test
    fun 同单同额同类型仍合并进既有账单() = runBlocking {
        repository.createBillWithDedup(bill("bill-1", 1000L, "ORD-1", merchant = null))

        val outcome = repository.createBillWithDedup(
            bill("bill-2", 1000L, "ORD-1", merchant = "测试商户")
        )

        assertTrue("同单同额必须合并（MergedInto）", outcome is CreateBillOutcome.MergedInto)
        assertEquals("不得产生第二笔账单", 1, database.billDao().count())
        val settled = database.billDao().getById("bill-1")!!
        assertEquals("合并只补空字段（merchant 填入）", "测试商户", settled.merchant)
        assertEquals("金额绝不被合并改动", 1000L, settled.amountMinor)
    }

    /** M1 核心：同单号不同金额 ⇒ 撞号抛异常，不落新账、不动余额、不吞证据。 */
    @Test
    fun 同单号不同金额撞号抛异常不吞证据() = runBlocking {
        repository.createBillWithDedup(bill("bill-1", 1000L, "ORD-2"))

        val error = runCatching {
            repository.createBillWithDedup(bill("bill-2", 2000L, "ORD-2"))
        }.exceptionOrNull()

        assertTrue("撞号必须抛 OrderConflictException: $error", error is OrderConflictException)
        assertEquals("bill-1", (error as OrderConflictException).existingBillId)
        assertEquals("撞号后新账单不得落库", 1, database.billDao().count())
        val account = database.accountDao().getById(ACCOUNT_ID)!!
        assertEquals("撞号后余额不得变动", -1000L, account.currentBalanceMinor)
    }

    /** M1 同口径：同单号不同类型（支出撞收入）⇒ 撞号抛异常。 */
    @Test
    fun 同单号不同类型撞号抛异常() = runBlocking {
        repository.createBillWithDedup(bill("bill-1", 1000L, "ORD-3", type = "expense"))

        val error = runCatching {
            repository.createBillWithDedup(bill("bill-2", 1000L, "ORD-3", type = "income"))
        }.exceptionOrNull()

        assertTrue(error is OrderConflictException)
        assertEquals(1, database.billDao().count())
    }

    /** M5：大小写/空白不同的同一单号 ⇒ 归一后仍合并；确实两笔不同单 ⇒ 不并。 */
    @Test
    fun 单号格式归一后仍合并不同单不并() = runBlocking {
        repository.createBillWithDedup(bill("bill-1", 1000L, "abc-100"))

        // 大小写 + 首尾空白差异：M5 归一后视为同一单号
        val merged = repository.createBillWithDedup(bill("bill-2", 1000L, " ABC-100 "))
        assertTrue("归一后同单号必须合并", merged is CreateBillOutcome.MergedInto)

        // 确实不同的两笔：不得合并
        val committed = repository.createBillWithDedup(bill("bill-3", 1000L, "ABC-200"))
        assertTrue(committed is CreateBillOutcome.Committed)
        assertEquals("两笔不同单号各自成账", 2, database.billDao().count())
        assertNotNull(database.billDao().getById("bill-3"))
    }

    /** M5 归一器：trim / 全角转半角 / 大写 / 空白即无。 */
    @Test
    fun 归一器格式处理() {
        assertEquals("ABC123", com.jotbill.app.rules.OrderIdNormalizer.normalize(" abc123 "))
        assertEquals("ABC123", com.jotbill.app.rules.OrderIdNormalizer.normalize("ＡＢＣ１２３"))
        assertEquals("ORD-99", com.jotbill.app.rules.OrderIdNormalizer.normalize("ord-99"))
        assertEquals(null, com.jotbill.app.rules.OrderIdNormalizer.normalize("   "))
        assertEquals(null, com.jotbill.app.rules.OrderIdNormalizer.normalize(null))
    }

    /** M5-补②：存量小写单号账单 ⇒ 归一后的退款原单查询仍能命中（UPPER(TRIM()) 双侧归一）。 */
    @Test
    fun 存量小写单号的退款原单查询仍命中() = runBlocking {
        // 模拟存量数据：orderId 以小写原始写法落库（归一化之前的历史写法）
        repository.createBillWithDedup(bill("bill-1", 1000L, "ord-777"))

        val hits = database.billDao().findExpensesByOrderId("ORD-777")
        assertEquals("归一后的退款号必须命中存量小写单号", 1, hits.size)
        assertEquals("bill-1", hits.first().id)
    }
}
