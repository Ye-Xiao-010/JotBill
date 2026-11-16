package com.jotbill.app.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.notification.PaymentResultNormalizer
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
 * 退款跨来源去重（2026-11-16 真机）：同一笔退款可能同时来自拼多多通知、
 * 微信/支付宝退款通知、银行到账短信 —— 各来源 sourceEventKey 不同，
 * 2 分钟内同额退款卡只建一张。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RefundLinkerCrossSourceDedupTest {

    private lateinit var database: JotBillDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            JotBillDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertCashAccount() = runBlocking {
        database.accountDao().insert(
            AccountEntity(
                id = "default-account-cash", name = "现金", subtype = "cash", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
                icon = null, color = null, sortOrder = 0,
                isActive = true, createdAt = 1L, updatedAt = 1L
            )
        )
    }

    private fun refundEvidence(
        sourcePackage: String,
        amountMinor: Long,
        occurredAt: Long,
        key: String
    ) = PaymentEvidenceInput(
        sourcePackage = sourcePackage,
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        merchant = "拼多多",
        orderId = null,
        paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
        sourceEventKey = key,
        rawTextHash = "hash-$key",
        receivingAccountHint = null
    )

    @Test
    fun 同额退款多来源只建一张卡() = runBlocking {
        insertCashAccount()
        val now = System.currentTimeMillis()
        val linker = RefundLinker(database)

        val first = linker.processRefund(
            refundEvidence("com.xunmeng.pinduoduo", 3990L, now, "pdd-refund-1")
        )
        assertTrue("首个退款信号应建卡", first is RefundLinker.RefundOutcome.PendingCreated)

        val second = linker.processRefund(
            refundEvidence("com.android.mms", 3990L, now + 30_000L, "sms-refund-1")
        )
        assertEquals(
            "2 分钟内同额退款来自不同来源应判重复",
            RefundLinker.RefundOutcome.Duplicate,
            second
        )
        assertEquals("只允许一张退款卡", 1, database.pendingTransactionDao().count())
    }

    @Test
    fun 不同金额退款各自建卡() = runBlocking {
        insertCashAccount()
        val now = System.currentTimeMillis()
        val linker = RefundLinker(database)

        linker.processRefund(refundEvidence("com.xunmeng.pinduoduo", 3990L, now, "pdd-refund-2"))
        val other = linker.processRefund(
            refundEvidence("com.xunmeng.pinduoduo", 4990L, now + 10_000L, "pdd-refund-3")
        )

        assertTrue("不同金额不是同一笔", other is RefundLinker.RefundOutcome.PendingCreated)
        assertEquals(2, database.pendingTransactionDao().count())
    }

    /**
     * 用户先确认了页面退款卡（已生成退款账单），随后银行退款短信才到 ——
     * pending 卡已不在，必须靠"近期同额退款账单"挡住第二条。
     */
    @Test
    fun 同额退款账单已存在时后续信号判重复() = runBlocking {
        insertCashAccount()
        val now = System.currentTimeMillis()
        database.billDao().insert(
            BillEntity(
                id = "refund-bill-existing",
                amountMinor = 3990L,
                type = "refund",
                occurredAt = now,
                description = "退款",
                categoryId = null,
                accountId = "default-account-cash",
                targetAccountId = null,
                platform = "com.xunmeng.pinduoduo",
                merchant = "拼多多",
                productDescription = null,
                sourceType = "accessibility",
                originalTransactionId = null,
                orderId = null,
                createdAt = now,
                updatedAt = now,
                version = 1
            )
        )

        val outcome = RefundLinker(database).processRefund(
            refundEvidence("com.android.mms", 3990L, now + 30_000L, "sms-after-confirm-1")
        )

        assertEquals(RefundLinker.RefundOutcome.Duplicate, outcome)
        assertEquals("不得再建退款卡", 0, database.pendingTransactionDao().count())
    }
}
