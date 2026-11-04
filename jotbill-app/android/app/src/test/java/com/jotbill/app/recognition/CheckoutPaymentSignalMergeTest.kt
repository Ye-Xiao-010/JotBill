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

    // ── N-2 吸收（拍板 2026-11-04）：强证据入账吸收同单待确认卡 ──────────

    /** 建一张可被 N-2 吸收的待确认卡（模拟结果页腿先落卡）。 */
    private suspend fun insertCard(
        id: String,
        amount: Long,
        orderId: String?,
        type: String = "expense",
        merchant: String? = "测试商户",
        productDescription: String? = null
    ) {
        database.pendingTransactionDao().insertIfAbsent(
            PendingTransactionEntity(
                id = id,
                candidateId = null,
                sessionId = null,
                amountMinor = amount,
                type = type,
                occurredAt = BASE,
                platform = WECHAT,
                merchant = merchant,
                productDescription = productDescription,
                orderId = orderId,
                accountId = null,
                categoryId = null,
                conflictReason = "NO_ACTIVE_SESSION",
                sourceType = "accessibility",
                appliedRules = "{}",
                createdAt = BASE,
                version = 1
            )
        )
    }

    /** N-2 前置：支付平台种子账户存在（createBillWithDedup 要求启用账户）。 */
    private suspend fun insertWechatAccount() {
        database.accountDao().insert(
            com.jotbill.app.data.entity.AccountEntity(
                id = "default-account-wechat", name = "微信零钱", subtype = "wallet",
                kind = "asset", initialBalanceMinor = 0L, currentBalanceMinor = 100_000L,
                currency = "CNY", icon = null, color = null, sortOrder = 0,
                isActive = true, createdAt = 1L, updatedAt = 1L
            )
        )
    }

    /** N-2 强证据信号（NOTIFICATION 来源 + 单号 + 可解析账户 ⇒ 自动入账）。 */
    private fun strongSignal(amount: Long, orderId: String?, key: String) =
        signal(WECHAT, amount, BASE, orderId, key)

    /** N-2 ①：同单卡 + 强证据 ⇒ 只剩 1 笔账单、0 卡，吸收留痕审计。 */
    @Test
    fun 强证据吸收同单卡入账后零卡留痕() = runBlocking {
        insertWechatAccount()
        insertCard(id = "pending-n2-1", amount = 31001L, orderId = "JD-N2-1")

        CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
            strongSignal(31001L, "JD-N2-1", "evt-n2-1"),
            sourceType = "NOTIFICATION"
        )

        val bill = database.billDao().findByOrderAndAmount("JD-N2-1", 31001L)
        assertEquals("强证据应入账", "JD-N2-1", bill?.orderId)
        assertEquals("同单卡必须被吸收", 0, pendingCount())
        val audit = database.auditEntryDao().getByEntity("absorbed_pending", bill!!.id)
        assertTrue("吸收必须留痕审计", audit.isNotEmpty())
    }

    /** N-2 ②：不同 orderId 的卡不得被删。 */
    @Test
    fun 异订单号卡不被吸收() = runBlocking {
        insertWechatAccount()
        insertCard(id = "pending-n2-2", amount = 32001L, orderId = "OTHER-ORDER")

        CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
            strongSignal(32001L, "JD-N2-2", "evt-n2-2"),
            sourceType = "NOTIFICATION"
        )

        assertEquals("异 orderId 的卡必须保留", 1, pendingCount())
        assertTrue(database.billDao().findByOrderAndAmount("JD-N2-2", 32001L) != null)
    }

    /** N-2 ③：无 orderId 的卡不受影响。 */
    @Test
    fun 无订单号卡不被吸收() = runBlocking {
        insertWechatAccount()
        insertCard(id = "pending-n2-3", amount = 33001L, orderId = null)

        CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
            strongSignal(33001L, "JD-N2-3", "evt-n2-3"),
            sourceType = "NOTIFICATION"
        )

        assertEquals("无 orderId 的卡必须保留", 1, pendingCount())
    }

    /** N-2 ④：refund 卡键族独立，不被吸收。 */
    @Test
    fun 退款卡不被吸收() = runBlocking {
        insertWechatAccount()
        insertCard(
            id = "pending-n2-4", amount = 34001L, orderId = "JD-N2-4",
            type = "refund"
        )

        CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
            strongSignal(34001L, "JD-N2-4", "evt-n2-4"),
            sourceType = "NOTIFICATION"
        )

        assertEquals("refund 卡必须保留", 1, pendingCount())
        assertTrue(database.billDao().findByOrderAndAmount("JD-N2-4", 34001L) != null)
    }

    /** N-2 ⑤：重复通知幂等 —— 第二次同键强证据不再产生第二笔账单。 */
    @Test
    fun 重复强证据通知幂等不重复入账() = runBlocking {
        insertWechatAccount()
        insertCard(id = "pending-n2-5", amount = 35001L, orderId = "JD-N2-5")
        val matcher = CheckoutPaymentMatcher(database)
        val dup = strongSignal(35001L, "JD-N2-5", "evt-n2-5")

        matcher.recordOrphanPaymentSignal(dup, sourceType = "NOTIFICATION")
        matcher.recordOrphanPaymentSignal(dup, sourceType = "NOTIFICATION")

        assertEquals("重复通知不得产生第二笔账单", 1, database.billDao().count())
        assertEquals(0, pendingCount())
    }

    /** N-2 ⑥：卡字段只补空 —— 卡上的页面字段（商户/商品）随吸收并入账单。 */
    @Test
    fun 吸收时卡字段补空并入账单() = runBlocking {
        insertWechatAccount()
        insertCard(
            id = "pending-n2-6", amount = 36001L, orderId = "JD-N2-6",
            merchant = "页面商户", productDescription = "测试商品"
        )

        CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
            PaymentNotificationFallbackSignal(
                paymentPackage = WECHAT,
                amountMinor = 36001L,
                occurredAt = BASE,
                merchant = null,
                orderId = "JD-N2-6",
                sourceEventKey = "evt-n2-6",
                rawTextHash = null
            ),
            sourceType = "NOTIFICATION"
        )

        val bill = database.billDao().findByOrderAndAmount("JD-N2-6", 36001L)
        assertTrue(bill != null)
        assertEquals("卡商户补空并入账单", "页面商户", bill!!.merchant)
        assertEquals("卡商品描述补空并入账单", "测试商品", bill.productDescription)
    }
}
