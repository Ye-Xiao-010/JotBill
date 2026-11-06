package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 编辑/删除路径余额联动的 DB 级回归测试（Robolectric + Room 内存库，
 * 写法对齐 LedgerRepaymentDirectionTest）。
 *
 * 余额审查确认的测试空白（本类补口）：
 * 1. 编辑账单**改账户**：旧账户回滚、新账户应用必须双边断言（漏一边两账户同时错）；
 * 2. 删除回滚：expense 与 refund 两类型（此前只有 repayment 删除有测试）；
 * 3. B2：编辑路径的退款累计上限 —— 调大已有退款超额、支出改 refund 超额均拒绝，
 *    且失败后余额分毫不动（事务整体回滚）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerEditDeleteBalanceTest {

    private lateinit var database: JotBillDatabase
    private lateinit var ledger: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun account(id: String, balanceMinor: Long) = AccountEntity(
        id = id, name = id, subtype = "cash", kind = "asset",
        initialBalanceMinor = balanceMinor, currentBalanceMinor = balanceMinor, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    private fun bill(
        id: String,
        type: String,
        amountMinor: Long,
        accountId: String,
        targetAccountId: String? = null,
        originalTransactionId: String? = null,
        version: Int = 1
    ) = BillEntity(
        id = id, amountMinor = amountMinor, type = type,
        occurredAt = 1_000_000L, description = null, categoryId = null,
        accountId = accountId, targetAccountId = targetAccountId, platform = null,
        merchant = null, productDescription = null, sourceType = "manual",
        originalTransactionId = originalTransactionId, orderId = null,
        createdAt = 1L, updatedAt = 1L, version = version
    )

    private suspend fun balanceOf(id: String): Long =
        database.accountDao().getById(id)!!.currentBalanceMinor

    @Test
    fun editBillAccountMovesBalanceBothSides() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        database.accountDao().insert(account("acc-b", 500L))
        ledger.createBillWithDedup(bill("bill-1", "expense", 400L, "acc-a"))
        assertEquals("改前 A 已扣减", 9_600L, balanceOf("acc-a"))
        assertEquals("改前 B 未动", 500L, balanceOf("acc-b"))

        val existing = database.billDao().getById("bill-1")!!
        val updated = existing.copy(
            accountId = "acc-b",
            updatedAt = 2L,
            version = existing.version + 1
        )
        val applied = ledger.updateBillWithBalanceUpdate(existing, updated)

        assertEquals("编辑应成功", true, applied)
        assertEquals("A 应回滚到原值", 10_000L, balanceOf("acc-a"))
        assertEquals("B 应承担扣减", 100L, balanceOf("acc-b"))
    }

    @Test
    fun deleteExpenseRestoresBalance() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        ledger.createBillWithDedup(bill("bill-1", "expense", 400L, "acc-a"))
        assertEquals(9_600L, balanceOf("acc-a"))

        ledger.deleteBillWithBalanceUpdate(database.billDao().getById("bill-1")!!)

        assertEquals("删除支出后余额应回滚", 10_000L, balanceOf("acc-a"))
    }

    @Test
    fun deleteRefundRestoresBalance() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        ledger.createBillWithDedup(bill("original-1", "expense", 1_000L, "acc-a"))
        ledger.createBillWithDedup(
            bill("refund-1", "refund", 400L, "acc-a", originalTransactionId = "original-1")
        )
        assertEquals("退款入账后 = 10000 - 1000 + 400", 9_400L, balanceOf("acc-a"))

        ledger.deleteBillWithBalanceUpdate(database.billDao().getById("refund-1")!!)

        assertEquals("删除退款后应回到只有支出的状态", 9_000L, balanceOf("acc-a"))
    }

    @Test
    fun enlargingRefundBeyondOriginalRejected() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        ledger.createBillWithDedup(bill("original-1", "expense", 1_000L, "acc-a"))
        ledger.createBillWithDedup(
            bill("refund-1", "refund", 400L, "acc-a", originalTransactionId = "original-1")
        )
        val balanceBefore = balanceOf("acc-a")

        val existing = database.billDao().getById("refund-1")!!
        val updated = existing.copy(amountMinor = 700L, version = existing.version + 1)
        // 除自身外已退 0，700 <= 1000 应放行
        assertEquals(true, ledger.updateBillWithBalanceUpdate(existing, updated))

        val existing2 = database.billDao().getById("refund-1")!!
        val overCap = existing2.copy(amountMinor = 1_200L, version = existing2.version + 1)
        // 1200 > 1000：超额必须抛错，且余额/账单均不变（事务回滚）
        assertThrows(LedgerValidationException::class.java) {
            runBlocking { ledger.updateBillWithBalanceUpdate(existing2, overCap) }
        }
        assertEquals("失败后余额不得变动", 9_700L, balanceOf("acc-a"))
        assertEquals("账单金额应保持 700", 700L, database.billDao().getById("refund-1")!!.amountMinor)
        assertEquals("失败前余额基线核对", 9_700L, balanceBefore + 300L)
    }

    @Test
    fun convertingExpenseToRefundBeyondOriginalRejected() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        ledger.createBillWithDedup(bill("original-1", "expense", 400L, "acc-a"))
        ledger.createBillWithDedup(bill("bill-1", "expense", 500L, "acc-a"))
        val balanceBefore = balanceOf("acc-a")

        val existing = database.billDao().getById("bill-1")!!
        val asRefund = existing.copy(
            type = "refund",
            originalTransactionId = "original-1",
            version = existing.version + 1
        )
        // 0 + 500 > 400：支出改退款且超额，必须拒绝
        assertThrows(LedgerValidationException::class.java) {
            runBlocking { ledger.updateBillWithBalanceUpdate(existing, asRefund) }
        }
        assertEquals("失败后余额不得变动", balanceBefore, balanceOf("acc-a"))
        assertEquals("账单类型应保持 expense", "expense", database.billDao().getById("bill-1")!!.type)
    }
}
