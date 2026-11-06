package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B4 余额一致性抽检的回归测试（Robolectric + Room 内存库）。
 *
 * 锁定口径：current = initial + Σ账单delta（源侧+目标侧）+ Σ校准diff。
 * 关键防线：**校准过的账户不误报** —— 校准直接设余额且 adjustment 账单 delta=0，
 * 口径不含校准差额时每次启动都会误报（用户习惯性无视 = 没做）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BalanceReconcilerTest {

    private lateinit var database: JotBillDatabase
    private lateinit var reconciler: BalanceReconciler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reconciler = BalanceReconciler(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun account(id: String, initialMinor: Long, currentMinor: Long = initialMinor) =
        AccountEntity(
            id = id, name = id, subtype = "cash", kind = "asset",
            initialBalanceMinor = initialMinor, currentBalanceMinor = currentMinor, currency = "CNY",
            icon = null, color = null, sortOrder = 0, isActive = true,
            createdAt = 1L, updatedAt = 1L
        )

    private fun bill(
        id: String,
        type: String,
        amountMinor: Long,
        accountId: String?,
        targetAccountId: String? = null
    ) = BillEntity(
        id = id, amountMinor = amountMinor, type = type,
        occurredAt = 1_000_000L, description = null, categoryId = null,
        accountId = accountId, targetAccountId = targetAccountId, platform = null,
        merchant = null, productDescription = null, sourceType = "manual",
        originalTransactionId = null, orderId = null,
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    @Test
    fun consistentAccountsProduceNoMismatch() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        database.accountDao().insert(account("acc-b", 500L))
        val ledger = LedgerRepository(database)
        ledger.createBillWithDedup(bill("b-1", "expense", 400L, "acc-a"))
        ledger.createBillWithDedup(bill("b-2", "transfer", 300L, "acc-a", "acc-b"))
        // 期望：A = 10000 - 400 - 300 = 9300；B = 500 + 300 = 800（由入账联动保证）

        val mismatches = reconciler.check()

        assertTrue("一致账户不得误报: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun driftIsDetectedWithCorrectDiff() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        LedgerRepository(database).createBillWithDedup(bill("b-1", "expense", 400L, "acc-a"))
        // 制造漂移：绕过账单直接把余额改小 100
        database.accountDao().updateBalance("acc-a", 9_500L, 2L)

        val mismatches = reconciler.check()

        assertEquals(1, mismatches.size)
        val mismatch = mismatches.single()
        assertEquals("acc-a", mismatch.accountId)
        assertEquals("应有余额 = 10000 - 400", 9_600L, mismatch.expectedMinor)
        assertEquals(9_500L, mismatch.actualMinor)
        assertEquals("diff = actual - expected = -100", -100L, mismatch.diffMinor)
    }

    @Test
    fun calibratedAccountIsNotFalsePositive() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        val ledger = LedgerRepository(database)
        ledger.createBillWithDedup(bill("b-1", "expense", 400L, "acc-a"))
        // 用户校准：账面设为 9_000（差 -600，含一笔对不上的现金损耗）
        ledger.calibrateAccountBalance("acc-a", 9_000L, "月末对账")

        val mismatches = reconciler.check()

        assertTrue("校准差额已纳入口径，校准过的账户不得误报: $mismatches", mismatches.isEmpty())
        // 校准后再入账/漂移仍应被监管：直接改余额制造新漂移
        database.accountDao().updateBalance("acc-a", 8_800L, 3L)
        val afterDrift = reconciler.check()
        assertEquals("校准后新漂移仍要报", 1, afterDrift.size)
        assertEquals(-200L, afterDrift.single().diffMinor)
    }

    @Test
    fun adjustmentBillContributesZeroDelta() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        // 校准会写入一笔 type=adjustment 的账单（delta=0）；本测试确认聚合口径
        // 对它计 0 —— 否则校准过的账户反而被 adjustment 金额误报
        LedgerRepository(database).calibrateAccountBalance("acc-a", 4_000L, null)
        val adjustments = database.billDao().getById(
            database.balanceCalibrationDao().getByAccount("acc-a").single().id
        )
        assertEquals("校准应落 adjustment 账单", "adjustment", adjustments!!.type)

        assertTrue(reconciler.check().isEmpty())
    }

    @Test
    fun auditMismatchesIsDailyIdempotent() = runBlocking {
        database.accountDao().insert(account("acc-a", 10_000L))
        database.accountDao().updateBalance("acc-a", 1L, 2L)

        reconciler.auditMismatches()
        reconciler.auditMismatches()

        val audits = database.auditEntryDao().getByEntity("balance_reconcile", "acc-a")
        assertEquals("同日重复抽检只留一条审计（防启动刷屏）", 1, audits.size)
        assertEquals("mismatch", audits.single().action)
    }
}
