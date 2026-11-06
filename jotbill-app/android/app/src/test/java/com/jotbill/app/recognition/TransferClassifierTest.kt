package com.jotbill.app.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.notification.PaymentEvidenceInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P13 回归测试：转账/还款通知的待确认卡类型语义。
 *
 * 行为变更（2026-11-05 拍板）：收款人命中（转账给他人）的待确认卡**不再**
 * 预填 `type="expense"` —— 旧形态下 PendingPromptPolicy 只按 expense 放行
 * 付款确认弹窗，用户点一次确认就把「转账给他人」写成消费支出（方向错账）。
 * 新形态按业务语义预填 transfer/repayment，方向与账户在待确认中心由用户
 * 显式决策。旧断言解决的问题（他人转账不得静默记为自有账户互转）由
 * `ACCOUNT_UNRESOLVED` 冲突码继续承担，语义未削弱。
 *
 * 驱动：Robolectric + Room 内存库直驱 [TransferClassifier.processTransfer]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferClassifierTest {

    private companion object {
        const val BASE = 1_800_000_000_000L
        const val WECHAT = "com.tencent.mm"
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

    /** 构造转账/还款证据输入；仅本测试关心的字段显式赋值，其余取默认。 */
    private fun input(
        status: String,
        key: String,
        recipient: String? = null,
        targetHint: String? = null
    ) = PaymentEvidenceInput(
        sourcePackage = WECHAT,
        amountMinor = 2000L,
        occurredAt = BASE,
        merchant = null,
        orderId = null,
        paymentStatus = status,
        sourceEventKey = key,
        rawTextHash = "hash-$key",
        targetAccountHint = targetHint,
        recipientName = recipient
    )

    /** P13 核心：收款人命中的转账 ⇒ 待确认卡类型为 transfer，绝不再预填 expense。 */
    @Test
    fun recipientTransferPendingUsesTransferTypeNotExpense() = runBlocking {
        val outcome = TransferClassifier(database).processTransfer(
            input(status = "TRANSFER", key = "p13-transfer-1", recipient = "张三")
        )

        assertEquals(
            "收款人转账必须落待确认（账户无法自动判定）",
            "ACCOUNT_UNRESOLVED",
            (outcome as? TransferClassifier.TransferOutcome.PendingCreated)?.conflictReason
        )
        val pendingType =
            (outcome as TransferClassifier.TransferOutcome.PendingCreated).pendingType
        assertEquals("P13：待确认卡必须按 transfer 语义预填", "transfer", pendingType)
        assertNotEquals("P13：不得再预填为消费支出", "expense", pendingType)

        val stored = database.pendingTransactionDao().getById(
            (outcome as TransferClassifier.TransferOutcome.PendingCreated).pendingId
        )
        assertEquals("transfer", stored?.type)
    }

    /** P13 回归：还款通知（账户未匹配）⇒ 待确认卡类型为 repayment。 */
    @Test
    fun repaymentPendingUsesRepaymentType() = runBlocking {
        val outcome = TransferClassifier(database).processTransfer(
            input(status = "REPAYMENT", key = "p13-repayment-1", targetHint = "花呗")
        )

        val created = outcome as? TransferClassifier.TransferOutcome.PendingCreated
        assertEquals("ACCOUNT_UNRESOLVED", created?.conflictReason)
        assertEquals("repayment", created?.pendingType)
    }

    /** M2：结果页个人转账卡先在，通知腿同收款人转账 ⇒ 归并为单卡（不双卡）。 */
    @Test
    fun 通知腿转账与结果页卡归并为单卡() = runBlocking {
        // 结果页腿先落卡（recordPersonalTransfer，收款人存 merchant 列）
        CheckoutPaymentMatcher(database).recordPersonalTransfer(
            amountMinor = 2000L,
            paymentPackage = WECHAT,
            recipient = "张三",
            occurredAt = BASE
        )
        assertEquals(1, database.pendingTransactionDao().count())

        // 通知腿同笔转账到达 ⇒ M2 归并，不新建第二张卡
        val outcome = TransferClassifier(database).processTransfer(
            input(status = "TRANSFER", key = "m2-cross-1", recipient = "张三")
        )

        assertTrue(outcome is TransferClassifier.TransferOutcome.Duplicate)
        assertEquals("同一笔转账两腿必须收敛为一张卡", 1, database.pendingTransactionDao().count())
    }

    /** M2 反向：通知腿卡先在，结果页腿同收款人转账 ⇒ 归并为单卡。 */
    @Test
    fun 结果页腿转账与通知腿卡归并为单卡() = runBlocking {
        val created = TransferClassifier(database).processTransfer(
            input(status = "TRANSFER", key = "m2-cross-2", recipient = "李四")
        )
        assertTrue(created is TransferClassifier.TransferOutcome.PendingCreated)
        assertEquals(1, database.pendingTransactionDao().count())

        CheckoutPaymentMatcher(database).recordPersonalTransfer(
            amountMinor = 2000L,
            paymentPackage = WECHAT,
            recipient = "李四",
            occurredAt = BASE + 5_000L
        )

        assertEquals("同一笔转账两腿必须收敛为一张卡", 1, database.pendingTransactionDao().count())
    }

    /** M2 护栏：收款人不同的两笔真实转账 ⇒ 两张卡（绝不误并）。 */
    @Test
    fun 不同收款人的两笔转账出两卡() = runBlocking {
        CheckoutPaymentMatcher(database).recordPersonalTransfer(
            amountMinor = 2000L,
            paymentPackage = WECHAT,
            recipient = "张三",
            occurredAt = BASE
        )
        val outcome = TransferClassifier(database).processTransfer(
            input(status = "TRANSFER", key = "m2-diff-1", recipient = "王五")
        )

        assertTrue(
            "收款人不同必须各自成卡",
            outcome is TransferClassifier.TransferOutcome.PendingCreated
        )
        assertEquals("两笔不同收款人的转账不得合并", 2, database.pendingTransactionDao().count())
    }

    /**
     * 复核修复钉：自动入账撞号（同 orderId 不同金额）⇒ 回落建卡带
     * DEDUP_CHECK_FAILED，异常不逃出事务、证据不丢。
     * 触达方式：该分支生产上因 `isNotificationAutoCommitAllowed() = false`
     * 不可达，用构造参数测试缝 [TransferClassifier.autoCommitAllowed] 注入
     * true 把自动入账分支跑起来（与 CheckoutPaymentMatcher.createBillDelegate
     * 同款注入风格，生产行为零变化）。
     */
    @Test
    fun 自动入账撞号回落建卡不丢证据() = runBlocking {
        // 自动入账可达性准备：源=微信零钱（包名映射）、目标=招行储蓄（提示唯一命中）
        database.accountDao().insert(account("default-account-wechat", "微信零钱"))
        database.accountDao().insert(account("acc-target", "招行储蓄"))
        // 预置撞号账单：同 orderId、金额 3000（新证据 2000）⇒ M1 撞号
        database.billDao().insert(conflictBill(orderId = "TX-1", amount = 3000L))

        val outcome = TransferClassifier(database, autoCommitAllowed = { true })
            .processTransfer(
                input(status = "TRANSFER", key = "m1fix-1", targetHint = "招行储蓄")
                    .copy(orderId = "TX-1")
            )

        assertTrue(
            "撞号必须回落建卡（不得抛异常逃出事务）: $outcome",
            outcome is TransferClassifier.TransferOutcome.PendingCreated
        )
        assertEquals(
            com.jotbill.app.rules.DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED,
            (outcome as TransferClassifier.TransferOutcome.PendingCreated).conflictReason
        )
        assertEquals("撞号后新账单不得落库", 1, database.billDao().count())
        val stored = database.pendingTransactionDao().getById(
            (outcome as TransferClassifier.TransferOutcome.PendingCreated).pendingId
        )
        assertNotNull("回落的待确认行必须存在: stored=$stored", stored)
        val probeRows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals("证据必须以待确认卡存活: rows=$probeRows", 1, probeRows.size)
    }

    /** 构造启用账户（供包名映射与目标账户匹配）。 */
    private fun account(id: String, name: String) = com.jotbill.app.data.entity.AccountEntity(
        id = id, name = name, subtype = "cash", kind = "asset",
        initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    /** 构造预置撞号账单（同 orderId、金额与新证据不同）。 */
    private fun conflictBill(orderId: String, amount: Long) =
        com.jotbill.app.data.entity.BillEntity(
            id = "conflict-bill-1",
            amountMinor = amount,
            type = "transfer",
            occurredAt = BASE,
            description = "既有转账",
            categoryId = null,
            accountId = "default-account-wechat",
            targetAccountId = "acc-target",
            platform = null,
            merchant = null,
            productDescription = null,
            sourceType = "notification",
            originalTransactionId = null,
            orderId = orderId,
            createdAt = BASE,
            updatedAt = BASE,
            version = 1
        )
}
