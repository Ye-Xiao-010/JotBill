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
}
