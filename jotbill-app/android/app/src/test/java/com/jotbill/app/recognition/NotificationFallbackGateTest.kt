package com.jotbill.app.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.pending.RecentPaymentCooldown
import com.jotbill.app.pending.RecentResultObservation
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
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
 * D-3 修订（2026-10-28）闸行为判别：
 * - 通知来源默认仍拒（原 D-3 语义不变）；
 * - conflictReason=NOTIFICATION_FALLBACK 时豁免放行（迟到兜底形态）；
 * - 豁免行的 sourceType 仍为 notification、conflictReason 带标记（用户可见来源）。
 *
 * 背景：HyperOS 2 支付期三层隔离下通知是唯一 100% 命中的通道（四笔实测），
 * 三分类器派生链路仍全禁 —— 本闸只豁免「通知监听直接解析出的支付证据」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationFallbackGateTest {

    private lateinit var database: JotBillDatabase
    private lateinit var matcher: CheckoutPaymentMatcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        matcher = CheckoutPaymentMatcher(database)
    }

    @After
    fun tearDown() {
        database.close()
        RecentPaymentCooldown.reset()
        RecentResultObservation.reset()
    }

    private fun signal(amountMinor: Long = 2713L, eventKey: String = "evt-1") =
        PaymentNotificationFallbackSignal(
            paymentPackage = "com.tencent.mm",
            amountMinor = amountMinor,
            occurredAt = 1_770_000_000_000L,
            merchant = "拼多多",
            orderId = null,
            sourceEventKey = eventKey,
            rawTextHash = null
        )

    @Test
    fun plainNotificationStillRejected() = runBlocking {
        // 原 D-3 语义：无标记的通知孤儿不落。
        matcher.recordOrphanPaymentSignal(signal(), sourceType = "NOTIFICATION")
        assertTrue(database.pendingTransactionDao().getPage(10, 0).isEmpty())
    }

    @Test
    fun fallbackMarkerExemptsGate() = runBlocking {
        // 迟到兜底形态：NOTIFICATION_FALLBACK 标记豁免闸。
        matcher.recordOrphanPaymentSignal(
            signal(),
            sourceType = "NOTIFICATION",
            conflictReason = NOTIFICATION_FALLBACK_CONFLICT_REASON
        )
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("notification", row.sourceType)
        assertEquals(NOTIFICATION_FALLBACK_CONFLICT_REASON, row.conflictReason)
        assertEquals(2713L, row.amountMinor)
    }

    @Test
    fun accessibilityPathUnaffected() = runBlocking {
        // 无障碍主路径不受修订影响（回归保护）。
        matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        assertEquals("accessibility", rows.single().sourceType)
    }

    @Test
    fun lateAccessibilityEvidenceMergesWithFallbackCard() = runBlocking {
        // 双通道防重：通知兜底先落卡，无障碍证据后到 —— 同 sourceEventKey
        // 幂等合并（冲突码合并），不得出现第二行。
        matcher.recordOrphanPaymentSignal(
            signal(),
            sourceType = "NOTIFICATION",
            conflictReason = NOTIFICATION_FALLBACK_CONFLICT_REASON
        )
        matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        val reasons = rows.single().conflictReason ?: ""
        assertTrue(
            "无障碍迟到证据的冲突码应合并进兜底卡，实际: $reasons",
            reasons.contains(NOTIFICATION_FALLBACK_CONFLICT_REASON)
        )
    }

    @Test
    fun consumedCooldownStillBlocksFallback() = runBlocking {
        // 兜底卡被处理后，15s 冷却仍挡住同额兜底重放（原防线不因豁免失效）。
        val dao = database.pendingTransactionDao()
        matcher.recordOrphanPaymentSignal(
            signal(),
            sourceType = "NOTIFICATION",
            conflictReason = NOTIFICATION_FALLBACK_CONFLICT_REASON
        )
        val card = dao.getPage(10, 0).single()
        // 冷却登记先于 emit（Matcher 保证）；走 removed 事件驱动 markConsumed。
        com.jotbill.app.pending.RecentPaymentCooldown.rememberCard(
            pendingId = card.id,
            paymentPackage = card.platform ?: "com.tencent.mm",
            amountMinor = card.amountMinor
        )
        com.jotbill.app.pending.RecentPaymentCooldown.markConsumed(
            pendingId = card.id,
            now = System.currentTimeMillis()
        )
        matcher.recordOrphanPaymentSignal(
            signal(),
            sourceType = "NOTIFICATION",
            conflictReason = NOTIFICATION_FALLBACK_CONFLICT_REASON
        )
        assertEquals(1, dao.getPage(10, 0).size)
    }
}
