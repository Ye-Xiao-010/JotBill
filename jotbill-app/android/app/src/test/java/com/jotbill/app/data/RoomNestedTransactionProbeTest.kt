package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
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
 * 嵌套 Room 事务行为的永久探针（2026-11-06 终审实测钉死，守门回归钉）。
 *
 * 要测的问题：外层 `withTransaction` + 内层 `withTransaction` 抛异常 + 外层 catch
 * 之后**继续写库**——catch 后写入的行最终是否存在？
 *
 * **实测结论（2026-11-06，Robolectric + Room 内存库）：行不存在 —— 外层事务
 * 整体回滚。** 发现史：本探针初版按 Room 公开语义断言「行存在」，两个用例运行
 * 均 FAILED（`row=null`），实证嵌套 `withTransaction` 内层异常会使外层已 catch
 * 的后续写入随整体回滚一起消失。该结论与"嵌套合入外层事务、内层异常属普通传播"
 * 的公开文档表述不一致，故以此测试永久钉死实际行为，禁止凭断言式说法改回。
 *
 * 守门对象：任何「事务内 catch Room 事务内层异常后继续写库」的写法 —— 撞号回落、
 * N-2 失败恢复等**必须**用预检（不产生异常）或拆两段顶层事务（如
 * `CheckoutPaymentMatcher.recordOrphanPaymentSignal` 的 N2CommitFailedException
 * 形态），不得依赖 catch 后同事务写库。
 *
 * 探针 B 是最贴近生产形态的复现：内层异常由 `LedgerRepository.createBillWithDedup`
 * 的真实撞号路径（自身带 withTransaction）抛出，而不是直接 `throw`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomNestedTransactionProbeTest {

    private companion object {
        const val BASE = 1_800_000_000_000L
    }

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

    /** 构造最小待确认实体；只显式指定本探针关心的字段。 */
    private fun probePending(id: String) = PendingTransactionEntity(
        id = id, candidateId = null, sessionId = null,
        amountMinor = 100L, type = "transfer", occurredAt = BASE,
        platform = "com.tencent.mm", merchant = null, productDescription = null,
        orderId = null, accountId = null, categoryId = null,
        conflictReason = "PROBE", sourceType = "notification",
        createdAt = BASE, version = 1
    )

    /** 构造最小账单实体（供撞号场景复现用）。 */
    private fun probeBill(id: String, amount: Long, orderId: String?) = BillEntity(
        id = id, amountMinor = amount, type = "transfer", occurredAt = BASE,
        description = "probe", categoryId = null, accountId = "acc-a",
        targetAccountId = "acc-b", platform = null, merchant = null,
        productDescription = null, sourceType = "notification",
        originalTransactionId = null, orderId = orderId,
        createdAt = BASE, updatedAt = BASE, version = 1
    )

    /**
     * 探针 A：内层 withTransaction 直接抛 IllegalStateException。
     * 守门断言（实测真相）：外层 catch 后写入的行**不存在**（外层整体回滚）。
     * 若本断言失败，说明 Room 行为已变，所有预检式/两段式回落写法须重新评估。
     */
    @Test
    fun probeA_catchThenWriteIsRolledBackWithOuterTransaction() = runBlocking {
        database.withTransaction {
            try {
                database.withTransaction { throw IllegalStateException("boom") }
            } catch (_: IllegalStateException) {
                // 外层按生产回落模式继续使用事务
            }
            database.pendingTransactionDao().insertIfAbsent(probePending("probe-a"))
        }

        val row = database.pendingTransactionDao().getById("probe-a")
        assertNull(
            "外层 catch 后写入的行不存在（外层整体回滚，2026-11-06 实测钉死）: row=$row",
            row
        )
        assertEquals(0, database.pendingTransactionDao().count())
    }

    /**
     * 探针 B：内层异常来自真实撞号路径 —— `LedgerRepository.createBillWithDedup`
     * （自身在 withTransaction 内检查 L1 并抛 OrderConflictException）。
     * 守门断言（实测真相）：外层 catch 后回落写入的行**不存在**；「事务内 catch
     * 撞号异常后回落写库」的生产形态在嵌套事务下失效，必须预检或拆两段事务。
     */
    @Test
    fun probeB_catchOrderConflictThenFallbackWriteIsRolledBack() = runBlocking {
        // 预置账户（validateBillAccounts 要求启用账户）与撞号账单
        database.accountDao().insert(
            com.jotbill.app.data.entity.AccountEntity(
                id = "acc-a", name = "微信零钱", subtype = "cash", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
                icon = null, color = null, sortOrder = 0, isActive = true,
                createdAt = BASE, updatedAt = BASE
            )
        )
        database.accountDao().insert(
            com.jotbill.app.data.entity.AccountEntity(
                id = "acc-b", name = "招行储蓄", subtype = "cash", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
                icon = null, color = null, sortOrder = 0, isActive = true,
                createdAt = BASE, updatedAt = BASE
            )
        )
        database.billDao().insert(probeBill("bill-old", 3000L, "TX-1"))

        database.withTransaction {
            try {
                // 撞号：同 orderId 不同金额（3000 vs 2000）→ OrderConflictException
                LedgerRepository(database).createBillWithDedup(
                    probeBill("bill-new", 2000L, "TX-1")
                )
            } catch (_: OrderConflictException) {
                // 生产回落模式的 catch 点
            }
            database.pendingTransactionDao().insertIfAbsent(probePending("probe-b"))
        }

        val row = database.pendingTransactionDao().getById("probe-b")
        assertNull(
            "撞号 catch 后回落写入的行不存在（外层整体回滚，2026-11-06 实测钉死）: row=$row",
            row
        )
        assertEquals("撞号后不得产生新账单", 1, database.billDao().count())
    }
}
