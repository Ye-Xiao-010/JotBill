package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 还款/转账双账户余额方向的回归测试（Robolectric + Room 内存库）。
 *
 * 审查发现缺陷（P0）：applyBalanceDelta 对负债目标账户取 direction=-1（假设
 * "正余额=欠款"），与全系统存储口径"负数=欠款"（normalizeBalance 取负入库、
 * getNetWorth 取 ABS、前端提交负数）相反，导致每次还款都放大欠款。
 *
 * 本测试锁定修复后行为：
 * - 还款：源资产 -amount；负债目标 +amount（欠款减少，余额向 0 靠近）；
 * - 删除还款账单：双向回滚；
 * - 资产间转账：源 -amount、目标 +amount。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerRepaymentDirectionTest {

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

    private fun assetAccount(id: String, balanceMinor: Long) = AccountEntity(
        id = id, name = id, subtype = "cash", kind = "asset",
        initialBalanceMinor = balanceMinor, currentBalanceMinor = balanceMinor, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    private fun liabilityAccount(id: String, balanceMinor: Long) = AccountEntity(
        id = id, name = id, subtype = "credit_card", kind = "liability",
        initialBalanceMinor = balanceMinor, currentBalanceMinor = balanceMinor, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    private fun bill(id: String, type: String, amountMinor: Long, accountId: String, targetAccountId: String?) =
        BillEntity(
            id = id, amountMinor = amountMinor, type = type,
            occurredAt = 1_000_000L, description = null, categoryId = null,
            accountId = accountId, targetAccountId = targetAccountId, platform = null,
            merchant = null, productDescription = null, sourceType = "manual",
            originalTransactionId = null, orderId = null,
            createdAt = 1L, updatedAt = 1L, version = 1
        )

    private suspend fun balanceOf(id: String): Long =
        database.accountDao().getById(id)!!.currentBalanceMinor

    @Test
    fun repaymentDecreasesLiabilityDebt() = runBlocking {
        database.accountDao().insert(assetAccount("acc-cash", 10_000L))
        // 负数=欠款：欠 1000 元存 -1000
        database.accountDao().insert(liabilityAccount("acc-card", -1_000L))

        ledger.createBillWithBalanceUpdate(
            bill("repay-1", "repayment", 400L, "acc-cash", "acc-card")
        )

        assertEquals("源资产应扣减 400", 9_600L, balanceOf("acc-cash"))
        assertEquals("还款后欠款应减少（-1000 + 400 = -600）", -600L, balanceOf("acc-card"))
    }

    @Test
    fun deletingRepaymentRestoresBothSides() = runBlocking {
        database.accountDao().insert(assetAccount("acc-cash", 10_000L))
        database.accountDao().insert(liabilityAccount("acc-card", -1_000L))
        val repay = bill("repay-1", "repayment", 400L, "acc-cash", "acc-card")
        ledger.createBillWithBalanceUpdate(repay)

        ledger.deleteBillWithBalanceUpdate(repay)

        assertEquals("删除后源资产应回滚", 10_000L, balanceOf("acc-cash"))
        assertEquals("删除后欠款应回滚到 -1000", -1_000L, balanceOf("acc-card"))
    }

    @Test
    fun transferBetweenAssetsMovesAmount() = runBlocking {
        database.accountDao().insert(assetAccount("acc-cash", 10_000L))
        database.accountDao().insert(assetAccount("acc-bank", 500L))

        ledger.createBillWithBalanceUpdate(
            bill("transfer-1", "transfer", 400L, "acc-cash", "acc-bank")
        )

        assertEquals(9_600L, balanceOf("acc-cash"))
        assertEquals("资产转入应加余额", 900L, balanceOf("acc-bank"))
    }
}
