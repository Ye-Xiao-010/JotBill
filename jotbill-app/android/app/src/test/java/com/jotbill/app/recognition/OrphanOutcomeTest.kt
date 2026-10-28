package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
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
 * N-0a：`recordOrphanPaymentSignal` 的落库结果语义。
 *
 * 背景：该函数内部有多条早退路径，此前一律返回 Unit，调用方无法区分「已落卡」
 * 与「被静默丢弃」—— `PaymentNotificationService` 因此无条件打「已转纯通知待确认」，
 * 真机排查会照着这条日志去找一条根本不存在的待确认。
 *
 * N-1 之后通知来源也放行落待确认（D-3 整体停用闸已删），故本测试同时钉死两条契约：
 * 1. 结果必须反映真实落库情况（N-0a）；
 * 2. 通知来源不再被静默丢弃（N-1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrphanOutcomeTest {

    private lateinit var database: JotBillDatabase
    private lateinit var matcher: CheckoutPaymentMatcher
    private var now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        matcher = CheckoutPaymentMatcher(database) { now }
        // 内存单例表是全局的，用例之间必须复位，否则互相污染。
        com.jotbill.app.pending.RecentPaymentCooldown.reset()
        com.jotbill.app.pending.RecentResultObservation.reset()
    }

    @After
    fun tearDown() {
        com.jotbill.app.pending.RecentPaymentCooldown.reset()
        com.jotbill.app.pending.RecentResultObservation.reset()
        database.close()
    }

    private fun signal(
        amountMinor: Long = 1234,
        sourceEventKey: String = "evt-1",
        occurredAt: Long = now
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = "com.tencent.mm",
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        sourceEventKey = sourceEventKey
    )

    /**
     * N-1 核心契约：通知来源的孤儿待确认**放行**（D-3 整体停用闸已删）。
     *
     * 背景：D-3 曾把通知来源整体停用，真机后果是 2026-10-28 三笔微信内嵌支付
     * 零痕迹丢失 —— 证据解析成功、金额全对，却被出口闸静默丢弃。
     * N-1 重新切分风险面：落待确认的误判代价只是"用户多点一次丢弃"，
     * 远小于继续拦导致的证据静默丢失。
     */
    @Test
    fun notificationSourceNowLandsPendingInsteadOfBeingDropped() = runBlocking {
        val outcome = matcher.recordOrphanPaymentSignal(signal(), sourceType = "NOTIFICATION")

        assertEquals(OrphanOutcome.Kind.STORED, outcome.kind)
        assertEquals(true, outcome.isStored)
        assertTrue("通知来源应带回 pendingId", outcome.pendingId != null)
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(
            "N-1 起通知来源的孤儿待确认必须落库（D-3 已不再整体拦截）",
            1,
            rows.size
        )
        assertEquals(rows.single().id, outcome.pendingId)
    }

    /** 结果页来源不受 D-3 影响：必须真落卡并回传 STORED 与 pendingId。 */
    @Test
    fun accessibilitySourceStoresPendingAndReportsStored() = runBlocking {
        val outcome = matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")

        assertEquals(OrphanOutcome.Kind.STORED, outcome.kind)
        assertEquals(true, outcome.isStored)
        assertTrue("STORED 必须带回 pendingId", outcome.pendingId != null)
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        assertEquals(rows.single().id, outcome.pendingId)
    }

    /** 信号字段非法时必须回传 SIGNAL_INVALID，而不是默认的「已落」。 */
    @Test
    fun invalidSignalReportsSignalInvalid() = runBlocking {
        val outcome = matcher.recordOrphanPaymentSignal(
            signal(amountMinor = 0L),
            sourceType = "ACCESSIBILITY"
        )

        assertEquals(OrphanOutcome.Kind.SIGNAL_INVALID, outcome.kind)
        assertEquals(false, outcome.isStored)
        assertTrue(
            "信号非法时不应落任何行",
            database.pendingTransactionDao().getPage(10, 0).isEmpty()
        )
    }

    /** 同键第二次提交走幂等跳过：回传 ALREADY_STORED 而非 STORED，且不产生第二行。 */
    @Test
    fun repeatedSignalReportsAlreadyStored() = runBlocking {
        val first = matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")
        val second = matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")

        assertEquals(OrphanOutcome.Kind.STORED, first.kind)
        assertEquals(OrphanOutcome.Kind.ALREADY_STORED, second.kind)
        assertEquals(first.pendingId, second.pendingId)
        assertEquals(1, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /** 来源类型非法时回传 SOURCE_TYPE_INVALID。 */
    @Test
    fun unknownSourceTypeReportsSourceTypeInvalid() = runBlocking {
        val outcome = matcher.recordOrphanPaymentSignal(signal(), sourceType = "TELEPATHY")

        assertEquals(OrphanOutcome.Kind.SOURCE_TYPE_INVALID, outcome.kind)
        assertEquals(false, outcome.isStored)
    }
}
