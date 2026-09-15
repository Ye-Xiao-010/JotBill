package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 确认入账路径的退款累计上限与手续费关联回归测试（Robolectric + Room 内存库）。
 *
 * 审查发现缺陷：
 * 1. "累计退款不得超过原交易金额"只在 RefundLinker 自动入账路径校验，confirm
 *    路径不校验——两笔同原交易退款首笔尚在待确认时第二笔已自动入账（sum=0），
 *    再确认首笔即累计 2×原金额；
 * 2. fee 型待确认携带的 originalBillId（FeeExtractor 关联主交易）在确认入账时
 *    被静默丢弃，手续费账单与主交易的结构化关联断链（与已修复的退款回填同族）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfirmRefundCapTest {

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

    private fun account(id: String = "acc-1") = AccountEntity(
        id = id, name = "测试账户", subtype = "cash", kind = "asset",
        initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    private fun expenseBill(id: String = "original-1", amountMinor: Long = 1_000L) = BillEntity(
        id = id, amountMinor = amountMinor, type = "expense",
        occurredAt = 1_000_000L, description = "原交易", categoryId = null,
        accountId = "acc-1", targetAccountId = null, platform = "pdd",
        merchant = "拼多多", productDescription = null, sourceType = "manual",
        originalTransactionId = null, orderId = "order-1",
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    private fun refundPending(id: String, amountMinor: Long) = PendingTransactionEntity(
        id = id, candidateId = null, sessionId = null,
        amountMinor = amountMinor, type = "refund", occurredAt = 2_000_000L,
        platform = "pdd", merchant = "拼多多", productDescription = null,
        orderId = "order-1", accountId = "acc-1", categoryId = null,
        conflictReason = null, sourceType = "notification",
        appliedRules = "{}", originalBillId = "original-1",
        createdAt = 1L, version = 1
    )

    private fun refundBill(id: String, amountMinor: Long) = BillEntity(
        id = id, amountMinor = amountMinor, type = "refund",
        occurredAt = 2_000_000L, description = null, categoryId = null,
        accountId = "acc-1", targetAccountId = null, platform = "pdd",
        merchant = "拼多多", productDescription = null, sourceType = "notification",
        originalTransactionId = "original-1", orderId = "order-1",
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    @Test
    fun refundWithinCapConfirmsAndBackfillsOriginal() = runBlocking {
        database.accountDao().insert(account())
        database.billDao().insert(expenseBill())
        database.pendingTransactionDao().insert(refundPending("pending-1", 400L))

        val result = ledger.confirmPendingTransaction(
            database.pendingTransactionDao().getById("pending-1")!!,
            refundBill("refund-1", 400L)
        )

        assertTrue(result.success)
        // 自动关联的 originalBillId 应回填到落库账单
        assertEquals("original-1", result.committed?.originalTransactionId)
    }

    @Test
    fun successiveRefundsUpToOriginalAmountAllowed() = runBlocking {
        database.accountDao().insert(account())
        database.billDao().insert(expenseBill(amountMinor = 1_000L))
        database.pendingTransactionDao().insert(refundPending("pending-1", 600L))
        database.pendingTransactionDao().insert(refundPending("pending-2", 400L))

        val first = ledger.confirmPendingTransaction(
            database.pendingTransactionDao().getById("pending-1")!!,
            refundBill("refund-1", 600L)
        )
        assertTrue(first.success)
        // 600 + 400 = 1000，恰好等于原交易金额，允许
        val second = ledger.confirmPendingTransaction(
            database.pendingTransactionDao().getById("pending-2")!!,
            refundBill("refund-2", 400L)
        )
        assertTrue(second.success)
    }

    @Test
    fun refundExceedingOriginalRejected() = runBlocking {
        database.accountDao().insert(account())
        database.billDao().insert(expenseBill(amountMinor = 1_000L))
        database.pendingTransactionDao().insert(refundPending("pending-1", 600L))
        database.pendingTransactionDao().insert(refundPending("pending-2", 500L))

        ledger.confirmPendingTransaction(
            database.pendingTransactionDao().getById("pending-1")!!,
            refundBill("refund-1", 600L)
        )

        // 600 + 500 = 1100 > 1000，确认路径必须拒绝
        assertThrows(LedgerValidationException::class.java) {
            runBlocking {
                ledger.confirmPendingTransaction(
                    database.pendingTransactionDao().getById("pending-2")!!,
                    refundBill("refund-2", 500L)
                )
            }
        }
        Unit
    }

    @Test
    fun feeConfirmBackfillsOriginalBillLink() = runBlocking {
        database.accountDao().insert(account())
        database.billDao().insert(expenseBill(id = "main-1"))
        val feePending = PendingTransactionEntity(
            id = "pending-fee-1", candidateId = null, sessionId = null,
            amountMinor = 100L, type = "fee", occurredAt = 2_000_000L,
            platform = "com.tencent.mm", merchant = null, productDescription = null,
            orderId = null, accountId = "acc-1", categoryId = null,
            conflictReason = null, sourceType = "notification",
            appliedRules = "{}", originalBillId = "main-1",
            createdAt = 1L, version = 1
        )
        database.pendingTransactionDao().insert(feePending)
        val feeBill = BillEntity(
            id = "fee-bill-1", amountMinor = 100L, type = "fee",
            occurredAt = 2_000_000L, description = null, categoryId = null,
            accountId = "acc-1", targetAccountId = null, platform = "com.tencent.mm",
            merchant = null, productDescription = null, sourceType = "notification",
            originalTransactionId = null, orderId = null,
            createdAt = 1L, updatedAt = 1L, version = 1
        )

        val result = ledger.confirmPendingTransaction(feePending, feeBill)

        assertTrue("手续费确认应成功", result.success)
        assertEquals("手续费账单应回填主交易关联", "main-1", result.committed?.originalTransactionId)
    }
}
