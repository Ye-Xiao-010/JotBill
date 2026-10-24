package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-3（2026-10-24 审查修复）的判别性测试：确认**失败**（行不存在 / 版本冲突）
 * 不得广播 `removed=true` —— 失败分支根本没删行，误广播会让一条仍存活的待确认
 * 被当成终态：系统通知被撤、15s 冷却启动、哈希登记被清，同额新支付被静默抑制。
 * 删掉 `confirmPendingTransaction` 里 `if (result.success)` 守卫，本用例必须转红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerRepositoryConfirmEmitTest {

    private lateinit var database: JotBillDatabase
    private lateinit var ledger: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepository(database)
        received.clear()
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

    private fun expensePending(id: String, version: Int) = PendingTransactionEntity(
        id = id, candidateId = null, sessionId = null,
        amountMinor = 1_000L, type = "expense", occurredAt = 2_000_000L,
        platform = "com.tencent.mm", merchant = "测试商户", productDescription = null,
        orderId = "order-1", accountId = "acc-1", categoryId = null,
        conflictReason = null, sourceType = "accessibility",
        appliedRules = "{}", originalBillId = null,
        createdAt = 1L, version = version
    )

    private fun bill(id: String = "bill-1") = BillEntity(
        id = id, amountMinor = 1_000L, type = "expense",
        occurredAt = 2_000_000L, description = null, categoryId = null,
        accountId = "acc-1", targetAccountId = null, platform = "com.tencent.mm",
        merchant = "测试商户", productDescription = null, sourceType = "accessibility",
        originalTransactionId = null, orderId = "order-1",
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    /** 失败路径一：版本冲突（另一入口已先处理）。 */
    @Test
    fun versionConflictConfirmDoesNotBroadcastRemoved() = runBlocking {
        database.accountDao().insert(account())
        database.pendingTransactionDao().insertIfAbsent(expensePending("p-1", version = 1))
        val stale = expensePending("p-1", version = 2)

        val result = ledger.confirmPendingTransaction(stale, bill())

        assertTrue(!result.success)
        assertTrue(
            "失败确认不得广播 removed —— 行还活着，广播会撤通知/启冷却/清哈希登记",
            received.isEmpty()
        )
    }

    /** 失败路径二：行不存在（已被其他入口确认删除）。 */
    @Test
    fun missingRowConfirmDoesNotBroadcastRemoved() = runBlocking {
        val result = ledger.confirmPendingTransaction(expensePending("ghost", version = 1), bill())

        assertTrue(!result.success)
        assertTrue(received.isEmpty())
    }

    /** 成功路径必须照常广播 removed（守卫不能把正向路径也拦掉）。 */
    @Test
    fun successfulConfirmBroadcastsRemoved() = runBlocking {
        database.accountDao().insert(account())
        database.pendingTransactionDao().insertIfAbsent(expensePending("p-1", version = 1))

        val result = ledger.confirmPendingTransaction(expensePending("p-1", version = 1), bill())

        assertTrue(result.success)
        val removed = received.filter { it.pendingId == "p-1" && it.removed }
        assertEquals(1, removed.size)
    }

    companion object {
        /**
         * Hub 是进程级单例且无移除接口：监听在类级注册一次，
         * 各用例的隔离靠 setUp 里的 `received.clear()`。
         */
        private val received = mutableListOf<PendingChangeInfo>()

        @BeforeClass
        @JvmStatic
        fun wireHub() {
            PendingChangeHub.addListener { info: PendingChangeInfo -> received.add(info) }
        }
    }
}
