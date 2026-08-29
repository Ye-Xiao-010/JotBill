package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CheckoutPaymentMatcher 证据链集成测试(Robolectric + Room 内存库)。
 *
 * 钉死三条批 4 重构期间不可破坏的契约:
 * 1. 孤儿支付信号落待确认:sourceType 归一化、type=expense、幂等键防重;
 * 2. 会话引用恢复:ensureSession → findReferenceForPayment 在窗口内可恢复、过期后不可;
 * 3. AI 未验证证据永不自动落账:证据状态标记为 SUCCESS_AI_UNVERIFIED,
 *    账单表保持为空,证据转入待确认由用户核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckoutPaymentMatcherIntegrationTest {

    private companion object {
        val PKG = "com.xunmeng.pinduoduo"
        val FP_A = "a".repeat(64)
        const val SESSION = "session-1"
        const val START_NOW = 1_700_000_000_000L
    }

    private lateinit var database: JotBillDatabase
    private lateinit var matcher: CheckoutPaymentMatcher
    private var now: Long = START_NOW

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        matcher = CheckoutPaymentMatcher(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun paymentSignal(
        amountMinor: Long = 1234,
        sourceEventKey: String = "evt-1"
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = "com.tencent.mm",
        amountMinor = amountMinor,
        occurredAt = now,
        sourceEventKey = sourceEventKey
    )

    private fun checkoutReference(
        sessionId: String = SESSION,
        fingerprint: String = FP_A
    ) = CheckoutSnapshotReference(
        snapshotId = "snap-1",
        sessionId = sessionId,
        slotId = checkoutSnapshotSlotId(PKG),
        shoppingPackage = PKG,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = fingerprint,
        capturedAt = now,
        expiresAt = now + 120_000,
        candidateRevision = 1,
        sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
    )

    // ---------- 契约 1:孤儿支付信号落待确认 ----------

    @Test
    fun orphanPendingInsertedWithNormalizedSourceAndIdempotentKey() = runBlocking {
        matcher.recordOrphanPaymentSignal(paymentSignal(), sourceType = "NOTIFICATION")
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("expense", row.type)
        assertEquals("notification", row.sourceType)
        assertEquals("NO_ACTIVE_SESSION", row.conflictReason)
        assertEquals(1234L, row.amountMinor)

        // 同一 sourceEventKey 重复处理(通知与无障碍双路径竞态)不产生第二行
        matcher.recordOrphanPaymentSignal(paymentSignal(), sourceType = "ACCESSIBILITY")
        assertEquals(1, database.pendingTransactionDao().getPage(10, 0).size)
    }

    @Test
    fun orphanSourceTypeVariantsNormalizeToWireValues() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(sourceEventKey = "evt-a"),
            sourceType = "ACCESSIBILITY_RESULT"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(sourceEventKey = "evt-b"),
            sourceType = "MANUAL"
        )
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(2, rows.size)
        assertEquals(
            setOf("accessibility", "manual"),
            rows.map { it.sourceType }.toSet()
        )
    }

    // ---------- 契约 2:会话引用恢复 ----------

    @Test
    fun ensureSessionRoundTripRecoverableWithinWindowOnly() = runBlocking {
        val reference = checkoutReference()
        matcher.ensureSession(
            reference = reference,
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        val found = matcher.findReferenceForPayment(PKG, FP_A)
        assertTrue(found != null)
        assertEquals(SESSION, found!!.sessionId)
        assertEquals(CheckoutPageRole.CHECKOUT, found.pageRole)

        // 超出会话有效窗口后不再恢复,避免把过期会话的快照拉回匹配
        now += 300_000
        assertNull(matcher.findReferenceForPayment(PKG, FP_A))
    }

    // ---------- 契约 3:AI 未验证证据永不自动落账 ----------

    @Test
    fun aiUnverifiedEvidenceNeverAutoCommitsBill() = runBlocking {
        val reference = checkoutReference()
        matcher.ensureSession(
            reference = reference,
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        val result = matcher.recordPaymentEvidence(
            reference = reference,
            signal = paymentSignal(),
            sourceType = "ACCESSIBILITY",
            aiUnverified = true
        )
        // 核心不变量:账单表必须为空——AI 未验证证据只能进待确认
        assertEquals(0, database.billDao().count())
        val evidence = database.paymentEvidenceDao().getBySourceEventKey("evt-1")
        assertTrue("AI 未验证证据必须落库", evidence != null)
        assertEquals("SUCCESS_AI_UNVERIFIED", evidence!!.paymentStatus)
        // 待确认侧:证据先于 AI 候选到达时以证据占位待确认,等待用户核对
        val pendings = database.pendingTransactionDao().getPage(10, 0)
        assertTrue(
            "AI 未验证证据应转入待确认(result=$result)",
            pendings.isNotEmpty()
        )
    }
}
