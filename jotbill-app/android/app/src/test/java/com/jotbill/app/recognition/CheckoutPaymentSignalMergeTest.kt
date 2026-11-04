package com.jotbill.app.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.PendingTransactionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多信号合并回归（P1/P4 拍板 2026-11-04）：同一笔真实订单多信号只出一张卡，
 * 两笔真实不同的支付不误并。
 *
 * - 正向收敛：① 跳包同 orderId（跨包 2min 窗）→ 1 卡；② 跨源晚到同 orderId → 1 卡；
 *   ③ 归并留痕 mergedSourceEventKeys 记下被并信号。
 * - 负向不误并：④ orderId 冲突（同包同额 15s 内两单号不同）→ 2 卡（拍板①核心）；
 *   ⑤ 跨包同额无 orderId → 2 卡；⑥ 同包同额 >15s 两笔真单 → 2 卡（回归）；
 *   ⑦ refund/fee 不参与归并（回归）；⑧ 短信 + 支付信号 → 2 卡（拍板②）。
 *
 * 驱动：Robolectric + Room 内存库直驱 `CheckoutPaymentMatcher.recordOrphanPaymentSignal`
 * （ACCESSIBILITY 来源，避开 N-2 强证据自动入账；N-2 与卡并存的缺口见批次报告遗留）。
 * 各用例金额互不相同，避免 `RecentPaymentCooldown` 进程单例跨用例串扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckoutPaymentSignalMergeTest {

    private companion object {
        const val BASE = 1_800_000_000_000L
        const val PDD = "com.xunmeng.pinduoduo"
        const val WECHAT = "com.tencent.mm"
        const val MMS = "com.android.mms"
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

    private fun signal(
        pkg: String,
        amount: Long,
        occurredAt: Long,
        orderId: String?,
        key: String
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = pkg,
        amountMinor = amount,
        occurredAt = occurredAt,
        merchant = "测试商户",
        orderId = orderId,
        sourceEventKey = key,
        rawTextHash = null
    )

    private fun deliver(signal: PaymentNotificationFallbackSignal): OrphanOutcome = runBlocking {
        CheckoutPaymentMatcher(database)
            .recordOrphanPaymentSignal(signal, sourceType = "ACCESSIBILITY")
    }

    private suspend fun pendingCount(): Int = database.pendingTransactionDao().count()

    /** ① 跳包支付（拼多多站内腿 + 微信结果页）同 orderId ⇒ 跨包归并为 1 卡。 */
    @Test
    fun 跳包同订单号归并为一张卡() = runBlocking {
        deliver(signal(PDD, 21001L, BASE, "JD-ORDER-1", "evt-pdd-1"))
        deliver(signal(WECHAT, 21001L, BASE + 60_000L, "JD-ORDER-1", "evt-wx-1"))

        assertEquals("跳包同 orderId 2min 内必须收敛为一张卡", 1, pendingCount())
    }

    /** ② 结果页先落卡、同包第二条信号 90s 晚到（跨 15s 桶、<2min）同 orderId ⇒ 1 卡。 */
    @Test
    fun 跨源晚到同订单号归并为一张卡() = runBlocking {
        deliver(signal(WECHAT, 22001L, BASE, "JD-ORDER-2", "evt-late-1"))
        deliver(signal(WECHAT, 22001L, BASE + 90_000L, "JD-ORDER-2", "evt-late-2"))

        assertEquals("同 orderId 2min 内的跨源晚到必须收敛为一张卡", 1, pendingCount())
    }

    /** ③ 合并留痕：被并信号的 sourceEventKey 记入同一张卡 appliedRules.mergedSourceEventKeys。 */
    @Test
    fun 归并留痕记录被并信号键() = runBlocking {
        deliver(signal(PDD, 23001L, BASE, "JD-ORDER-3", "evt-trace-1"))
        deliver(signal(WECHAT, 23001L, BASE + 30_000L, "JD-ORDER-3", "evt-trace-2"))

        val card = database.pendingTransactionDao().findRecentPendingByOrderId(
            orderId = "JD-ORDER-3",
            windowStart = 0L,
            windowEnd = Long.MAX_VALUE
        )
        assertTrue("归并后应只剩一张卡", card != null)
        val keys = JSONObject(card!!.appliedRules).optJSONArray("mergedSourceEventKeys")
        assertTrue("留痕键数组必须存在", keys != null && keys.length() == 1)
        assertEquals("被并信号的 sourceEventKey 必须留痕", "evt-trace-2", keys!!.getString(0))
    }

    /** ④ 拍板①核心：同包同额 15s 内 orderId 冲突（两单号不同）⇒ 显式拒绝归并 → 2 卡。 */
    @Test
    fun 订单号冲突拒绝归并出两张卡() = runBlocking {
        deliver(signal(WECHAT, 24001L, BASE, "ORDER-A", "evt-conflict-1"))
        deliver(signal(WECHAT, 24001L, BASE + 5_000L, "ORDER-B", "evt-conflict-2"))

        assertEquals("orderId 冲突的两笔真单不得吞并", 2, pendingCount())
    }

    /** ⑤ 跨包同额无 orderId ⇒ 不归并（维持可删双卡的安全侧）。 */
    @Test
    fun 跨包同额无订单号出两张卡() = runBlocking {
        deliver(signal(PDD, 25001L, BASE, null, "evt-noorder-1"))
        deliver(signal(WECHAT, 25001L, BASE + 5_000L, null, "evt-noorder-2"))

        assertEquals("跨包无 orderId 不得归并", 2, pendingCount())
    }

    /** ⑥ 回归：同包同额两笔真单相隔 >15s（无 orderId）⇒ 2 卡（既有裁定边界不动）。 */
    @Test
    fun 同包同额超窗两笔真单出两张卡() = runBlocking {
        deliver(signal(WECHAT, 26001L, BASE, null, "evt-far-1"))
        deliver(signal(WECHAT, 26001L, BASE + 20_000L, null, "evt-far-2"))

        assertEquals("超过 15s 窗的同额第二笔不得吞并", 2, pendingCount())
    }

    /** ⑦ 回归：refund/fee 卡键族独立，不得被普通支付信号按 orderId 归并吸收。 */
    @Test
    fun 退款手续费卡不参与归并() = runBlocking {
        database.pendingTransactionDao().insertIfAbsent(
            PendingTransactionEntity(
                id = "pending-refund-7",
                candidateId = null,
                sessionId = null,
                amountMinor = 27001L,
                type = "refund",
                occurredAt = BASE,
                platform = WECHAT,
                merchant = "测试商户",
                productDescription = null,
                orderId = "JD-ORDER-7",
                accountId = null,
                categoryId = null,
                conflictReason = "ORIGINAL_MISSING",
                sourceType = "notification",
                appliedRules = "{}",
                createdAt = BASE,
                version = 1
            )
        )

        deliver(signal(WECHAT, 27001L, BASE + 5_000L, "JD-ORDER-7", "evt-refund-1"))

        assertEquals("expense 信号不得并进 refund 卡，也不得被其吞并", 2, pendingCount())
    }

    /** ⑧ 拍板②：短信（orderId 恒空）与支付信号不归并 ⇒ 2 卡。 */
    @Test
    fun 短信与支付信号同笔仍出两张卡() = runBlocking {
        deliver(signal(MMS, 28001L, BASE, null, "evt-sms-1"))
        deliver(signal(WECHAT, 28001L, BASE + 5_000L, "JD-ORDER-8", "evt-pay-1"))

        assertEquals("短信与支付信号不归并（拍板②）", 2, pendingCount())
    }
}
