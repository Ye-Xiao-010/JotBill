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
 * 待确认退款确认入账的回归测试（Robolectric + Room 内存库）。
 *
 * 审查发现缺陷：退款待确认"手动关联原交易"功能失效——桥接层硬编码
 * originalTransactionId = pending.sessionId（退款 pending 的 sessionId 为 null），
 * 用户在编辑面板选择的原交易被静默丢弃；且 LedgerRepository 对退款非自动关联
 * 场景还要求 originalTransactionId == sessionId，即使透传也会被错误拒绝。
 *
 * 本测试锁定修复后的行为：
 * - 退款且用户手动指定原交易（originalTransactionId 指向已存在账单）→ 确认成功并保留该关联；
 * - 退款未手动指定且无自动关联（originalBillId = null）→ 确认成功，originalTransactionId 保持 null；
 * - 非退款（expense）来源绑定不可改写 → 传入与 sessionId 不同的 originalTransactionId 被拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingRefundConfirmTest {

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

    private fun expenseBill(id: String = "original-bill-1", amountMinor: Long = 1_000L) = BillEntity(
        id = id, amountMinor = amountMinor, type = "expense",
        occurredAt = 1_000_000L, description = "原交易", categoryId = null,
        accountId = "acc-1", targetAccountId = null, platform = "pdd",
        merchant = "拼多多", productDescription = null, sourceType = "manual",
        originalTransactionId = null, orderId = "order-1",
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    private fun pendingRefund(
        id: String = "pending-refund-1",
        amountMinor: Long = 100L,
        originalBillId: String? = null
    ) = PendingTransactionEntity(
        id = id, candidateId = null, sessionId = null,
        amountMinor = amountMinor, type = "refund", occurredAt = 2_000_000L,
        platform = "pdd", merchant = "拼多多", productDescription = null,
        orderId = "order-1", accountId = "acc-1", categoryId = null,
        conflictReason = null, sourceType = "notification",
        appliedRules = "{}", originalBillId = originalBillId,
        createdAt = 1L, version = 1
    )

    private fun refundBill(
        amountMinor: Long = 100L,
        originalTransactionId: String?
    ) = BillEntity(
        id = "refund-bill-1", amountMinor = amountMinor, type = "refund",
        occurredAt = 2_000_000L, description = null, categoryId = null,
        accountId = "acc-1", targetAccountId = null, platform = "pdd",
        merchant = "拼多多", productDescription = null, sourceType = "notification",
        originalTransactionId = originalTransactionId, orderId = "order-1",
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    @Test
    fun userPickedRefundOriginalIsHonored() = runBlocking {
        database.accountDao().insert(account())
        database.billDao().insert(expenseBill())
        val pending = pendingRefund(originalBillId = null)
        database.pendingTransactionDao().insert(pending)

        // 用户从编辑面板手动选择了原交易（originalBillId 未自动关联，桥接层透传 originalTransactionId）
        val result = ledger.confirmPendingTransaction(
            pending,
            refundBill(originalTransactionId = "original-bill-1")
        )

        assertTrue("退款手动关联原交易应确认成功", result.success)
        val settled = result.committed
            ?: throw AssertionError("预期新建退款账单")
        assertEquals("用户选择的原交易应被保留", "original-bill-1", settled.originalTransactionId)
    }

    @Test
    fun refundWithoutOriginalKeepsNullBinding() = runBlocking {
        database.accountDao().insert(account())
        val pending = pendingRefund(originalBillId = null)
        database.pendingTransactionDao().insert(pending)

        // 用户未手动指定、无自动关联：originalTransactionId 保持 null，不绑定会话
        val result = ledger.confirmPendingTransaction(
            pending,
            refundBill(originalTransactionId = null)
        )

        assertTrue("无原交易的退款应确认成功", result.success)
        assertEquals(null, result.committed?.originalTransactionId)
    }

    @Test
    fun nonRefundSourceBindingIsImmutable() {
        runBlocking {
            database.accountDao().insert(account())
            val pending = PendingTransactionEntity(
                id = "pending-expense-1", candidateId = null, sessionId = "session-1",
                amountMinor = 500L, type = "expense", occurredAt = 2_000_000L,
                platform = null, merchant = null, productDescription = null,
                orderId = null, accountId = "acc-1", categoryId = null,
                conflictReason = null, sourceType = "notification",
                appliedRules = "{}", originalBillId = null,
                createdAt = 1L, version = 1
            )
            database.pendingTransactionDao().insert(pending)
            val bill = refundBill(amountMinor = 500L, originalTransactionId = "hacker-bill")

            // 非退款必须绑定 sessionId，传入其他 originalTransactionId 应被拒绝
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    ledger.confirmPendingTransaction(pending, bill)
                }
            }
        }
    }
}
