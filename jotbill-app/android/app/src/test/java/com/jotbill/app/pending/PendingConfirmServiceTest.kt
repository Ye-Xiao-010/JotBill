package com.jotbill.app.pending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PendingConfirmService 共享确认入口集成测试(Robolectric + Room 内存库)。
 *
 * 钉死三条契约:
 * 1. 覆写字段优先生效,账单落库、待确认删除、来源绑定 = 待确认 sessionId;
 * 2. 非法金额(0/负)返回 Invalid 而非抛异常;
 * 3. 已处理的待确认返回 AlreadyHandled,不产生第二笔账单。
 * 桥接层与付款确认弹窗共用本入口,行为变化必须先改这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingConfirmServiceTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PENDING_ID = "pending-1"
        const val SESSION_ID = "session-1"
        const val ACCOUNT_ID = "default-account-wechat"
        const val CATEGORY_ID = "cat-food"
    }

    private lateinit var database: JotBillDatabase
    private lateinit var service: PendingConfirmService

    @Before
    fun setUp() {
        runBlocking { seedReferenceData() }
    }

    private suspend fun seedReferenceData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = PendingConfirmService(database)
        // 弹窗/确认路径的账单写入需要真实分类与账户(FK RESTRICT)
        database.categoryDao().insertIgnore(
            CategoryEntity(
                id = CATEGORY_ID,
                name = "餐饮",
                type = "expense",
                icon = "🍜",
                color = null,
                isDefault = true,
                sortOrder = 0,
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        database.accountDao().insert(
            AccountEntity(
                id = ACCOUNT_ID,
                name = "微信零钱",
                subtype = "cash",
                kind = "asset",
                initialBalanceMinor = 0L,
                currentBalanceMinor = 0L,
                currency = "CNY",
                icon = null,
                color = null,
                sortOrder = 0,
                isActive = true,
                createdAt = NOW,
                updatedAt = NOW
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertPending(amountMinor: Long = 3580L) = runBlocking {
        database.pendingTransactionDao().insertIfAbsent(
            PendingTransactionEntity(
                id = PENDING_ID,
                candidateId = null,
                // 扫码支付等孤儿待确认本就无结算会话(matcher.recordOrphanPaymentSignal 同款)
                sessionId = null,
                amountMinor = amountMinor,
                type = "expense",
                occurredAt = NOW,
                platform = "com.tencent.mm",
                merchant = "测试商户",
                productDescription = null,
                orderId = null,
                accountId = null,
                categoryId = null,
                conflictReason = "NO_ACTIVE_SESSION",
                sourceType = "notification",
                createdAt = NOW,
                version = 1
            )
        )
    }

    @Test
    fun confirmAppliesOverridesSettlesPendingAndBindsSession() = runBlocking {
        insertPending()
        val outcome = service.confirm(
            pendingId = PENDING_ID,
            overrides = PendingConfirmService.Overrides(
                amountMinor = 3580L,
                categoryId = CATEGORY_ID,
                accountId = ACCOUNT_ID,
                description = "午饭"
            )
        )
        assertTrue("确认应成功: $outcome", outcome is PendingConfirmService.Outcome.Confirmed)
        val billId = (outcome as PendingConfirmService.Outcome.Confirmed).bill.id
        val bill = database.billDao().getById(billId)
        assertNotNull(bill)
        assertEquals(3580L, bill!!.amountMinor)
        assertEquals("expense", bill.type)
        assertEquals(CATEGORY_ID, bill.categoryId)
        assertEquals(ACCOUNT_ID, bill.accountId)
        assertEquals("午饭", bill.description)
        assertEquals("测试商户", bill.merchant)
        // 来源绑定 = 待确认 sessionId;孤儿待确认无会话时保持 null(与 App 内确认一致)
        assertNull(bill.originalTransactionId)
        // 待确认已被终态删除
        assertNull(database.pendingTransactionDao().getById(PENDING_ID))
    }

    @Test
    fun confirmReturnsAlreadyHandledForMissingPending() = runBlocking {
        val outcome = service.confirm(
            pendingId = "no-such-pending",
            overrides = PendingConfirmService.Overrides(amountMinor = 100L)
        )
        assertTrue(outcome is PendingConfirmService.Outcome.AlreadyHandled)
    }

    @Test
    fun confirmRejectsZeroAmountAsInvalid() = runBlocking {
        insertPending(amountMinor = 0L)
        val outcome = service.confirm(
            pendingId = PENDING_ID,
            overrides = PendingConfirmService.Overrides()
        )
        assertTrue(outcome is PendingConfirmService.Outcome.Invalid)
        assertEquals(0, database.billDao().count())
    }

    /**
     * 悬空分类引用必须返回 Invalid，而不是让 FK 约束在插入时抛 SQL 异常。
     *
     * 场景：RN 待确认中心的快捷确认（`handleConfirm` 传 `input={}`）不传分类，
     * 服务层回退到 `pending.categoryId`；若该分类已被删除（`deleteCategory`
     * 只检查 bills 不检查 pending），旧实现会在 `withTransaction` 里抛 FK 异常、
     * 英文透传，且 RN 编辑面板没有分类选择器 ⇒ 永久无法入账。
     */
    @Test
    fun confirmRejectsDeletedCategoryAsInvalid() = runBlocking {
        insertPending()
        // 给待确认挂一个不存在的分类（模拟"分类已删、pending 悬空"）
        val pending = database.pendingTransactionDao().getById(PENDING_ID)!!
        database.pendingTransactionDao().update(
            pending.copy(categoryId = "cat-deleted", version = pending.version)
        )

        val outcome = service.confirm(
            pendingId = PENDING_ID,
            overrides = PendingConfirmService.Overrides(amountMinor = 3580L)
        )
        assertTrue("悬空分类应返回 Invalid: $outcome", outcome is PendingConfirmService.Outcome.Invalid)
        assertEquals("不落账单", 0, database.billDao().count())
        assertEquals("待确认保留，等用户改选分类", 1, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /** 悬空账户引用同理（FK RESTRICT 提前到服务层拒绝）。 */
    @Test
    fun confirmRejectsDeletedAccountAsInvalid() = runBlocking {
        insertPending()
        val pending = database.pendingTransactionDao().getById(PENDING_ID)!!
        database.pendingTransactionDao().update(
            pending.copy(accountId = "account-deleted", version = pending.version)
        )

        val outcome = service.confirm(
            pendingId = PENDING_ID,
            overrides = PendingConfirmService.Overrides(
                amountMinor = 3580L,
                categoryId = CATEGORY_ID
            )
        )
        assertTrue("悬空账户应返回 Invalid: $outcome", outcome is PendingConfirmService.Outcome.Invalid)
        assertEquals("不落账单", 0, database.billDao().count())
    }
}
