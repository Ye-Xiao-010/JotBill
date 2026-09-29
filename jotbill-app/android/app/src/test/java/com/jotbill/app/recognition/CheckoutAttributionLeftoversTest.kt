package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
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
 * 批 V-1~V-5 落地后遗留的三条实现缺口回归（2026-09-29 补正）。
 *
 * 三条都在 9-28 的进度复盘中登记为「遗留缺陷」，但**代码未兑现**，直到本次才补上：
 *
 * | # | 缺口 | 原状 | 本次处置 |
 * |---|---|---|---|
 * | ① | `findReferenceForPayment` 第三层拦阻 | 硬判 `pageRole != CHECKOUT`，而同批改造已在 `CheckoutPurchaseIntentCoordinator:765` / `CheckoutSnapshotBuffer:798` / `CheckoutPageDetector:145` 三处改用 `AUTO_DISPATCHABLE_PAGE_ROLES` ⇒ **详情页直达支付整链失效**（会话角色为 `PRODUCT_DETAIL`，证据落库路径判 null）⇒ 宽进打折 | 判据统一为 `AUTO_DISPATCHABLE_PAGE_ROLES` |
 * | ② | `findRecentCheckoutReferenceForPayment` 兜底未摘除 | 归属判定 0 命中时退到「最近活跃结算会话」＝**猜测**，与 D-24/R4「0 命中不回退猜测」冲突；三处调用点仍在 | 三处调用点改为直落孤儿待确认，方法整体删除 |
 * | ③ | D-3/G3.1「v1 通知链路停用」未实现 | `sourceType == "notification"` 短路全库零命中 ⇒ v1 仍可能产生纯通知待确认 | `recordPaymentEvidence` / `recordOrphanPaymentSignal` **成对**加出口闸 |
 *
 * ## 为什么 ③ 必须成对加
 *
 * 只加 `recordPaymentEvidence` 的闸不够 —— 调用方对 `Rejected` 的反应是
 * `recordOrphanPaymentSignal(...)`，通知仍会经孤儿路径落一条待确认。
 * 两条路径同源同 key，必须一起关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckoutAttributionLeftoversTest {

    private companion object {
        val PKG = "com.xunmeng.pinduoduo"
        val FP = "a".repeat(64)
        const val SESSION = "session-leftover"
        const val NOW = 1_700_000_000_000L
    }

    private lateinit var database: JotBillDatabase
    private lateinit var matcher: CheckoutPaymentMatcher
    private var now: Long = NOW

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

    private fun reference(
        pageRole: CheckoutPageRole,
        sessionId: String = SESSION,
        fingerprint: String = FP
    ) = CheckoutSnapshotReference(
        snapshotId = "snap-1",
        sessionId = sessionId,
        slotId = checkoutSnapshotSlotId(PKG),
        shoppingPackage = PKG,
        pageRole = pageRole,
        pageFingerprint = fingerprint,
        capturedAt = now,
        expiresAt = now + 120_000,
        candidateRevision = 1,
        sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
    )

    private fun ensureSessionFor(role: CheckoutPageRole, sessionId: String = SESSION, fp: String = FP) =
        runBlocking {
            matcher.ensureSession(
                reference = reference(role, sessionId, fp),
                requestKey = CheckoutRequestKey.from(sessionId, PKG, fp)
            )
        }

    private fun signal(key: String = "evt-1") = PaymentNotificationFallbackSignal(
        paymentPackage = "com.tencent.mm",
        amountMinor = 1234,
        occurredAt = now,
        sourceEventKey = key
    )

    // ---------- 缺陷①：`findReferenceForPayment` 的角色判据须与宽进集合同源 ----------

    /**
     * 详情页直达支付时，会话角色是 `PRODUCT_DETAIL` 而不是 `CHECKOUT` ——
     * 这正是 V-4「宽进真生效」的第三个落点，也是此前打折的根因。
     *
     * 改前此处硬判 `!= CHECKOUT` ⇒ 命中不到会话 ⇒ 服务层证据落库路径拿不到引用。
     */
    @Test
    fun 会话角色为商品详情页时仍可恢复支付引用() = runBlocking {
        ensureSessionFor(CheckoutPageRole.PRODUCT_DETAIL)

        val found = matcher.findReferenceForPayment(PKG, FP)

        assertNotNull("PRODUCT_DETAIL 会话必须可恢复（宽进第三层拦阻已摘除）", found)
        assertEquals(SESSION, found!!.sessionId)
        assertEquals(CheckoutPageRole.PRODUCT_DETAIL, found.pageRole)
    }

    /** 购物车页同理（宽进三元组的第二类）。 */
    @Test
    fun 会话角色为购物车页时仍可恢复支付引用() = runBlocking {
        ensureSessionFor(CheckoutPageRole.CART)

        val found = matcher.findReferenceForPayment(PKG, FP)

        assertNotNull("CART 会话必须可恢复", found)
        assertEquals(CheckoutPageRole.CART, found!!.pageRole)
    }

    /**
     * 放宽**不得**越界：首页 / 未知页仍不可恢复。
     *
     * 规格红线（`AUTO_DISPATCHABLE_PAGE_ROLES`）：「放宽仅到可截三类，不放开
     * UNKNOWN / BROWSING」。本用例钉死该红线，防止有人图省事写成"任何角色都放行"。
     */
    @Test
    fun 首页与未知页会话仍不可恢复() = runBlocking {
        ensureSessionFor(CheckoutPageRole.SHOPPING_HOME, sessionId = "s-home")
        assertNull(matcher.findReferenceForPayment(PKG, FP))

        database.clearAllTables()

        ensureSessionFor(CheckoutPageRole.UNKNOWN, sessionId = "s-unknown")
        assertNull(matcher.findReferenceForPayment(PKG, FP))
    }

    /** 原 CHECKOUT 行为不得回退 —— 本次是放宽，不是替换。 */
    @Test
    fun 结算页会话恢复行为不变() = runBlocking {
        ensureSessionFor(CheckoutPageRole.CHECKOUT)

        val found = matcher.findReferenceForPayment(PKG, FP)

        assertNotNull(found)
        assertEquals(CheckoutPageRole.CHECKOUT, found!!.pageRole)
    }

    // ---------- 缺陷③：D-3「v1 通知链路停用」 ----------

    /**
     * v1 信号源只有「支付结果页」。通知证据**不参与匹配**，
     * 且必须以明确的拒绝码暴露（便于 logcat 量化，不静默）。
     */
    @Test
    fun 通知证据不参与匹配并给出明确拒绝码() = runBlocking {
        ensureSessionFor(CheckoutPageRole.CHECKOUT)
        val ref = reference(CheckoutPageRole.CHECKOUT)

        val result = matcher.recordPaymentEvidence(ref, signal(), sourceType = "NOTIFICATION")

        assertTrue("通知来源必须被拒绝，实际=$result", result is CheckoutPaymentMatchResult.Rejected)
        assertEquals(
            "PAYMENT_NOTIFICATION_DISABLED_V1",
            (result as CheckoutPaymentMatchResult.Rejected).reason
        )
        assertNull(
            "不得留下支付证据行",
            database.paymentEvidenceDao().getBySourceEventKey("evt-1")
        )
    }

    /**
     * **成对**闸：孤儿路径同样不得落。
     *
     * 这是 ③ 最容易被漏掉的一半 —— 调用方对 `Rejected` 的反应就是转孤儿，
     * 只关主路径的话通知仍会产生一条纯通知待确认（正是 D-3 要消除的形态）。
     */
    @Test
    fun 通知孤儿信号不落待确认() = runBlocking {
        matcher.recordOrphanPaymentSignal(signal(), sourceType = "NOTIFICATION")

        assertEquals(
            "v1 停用通知后不得产生纯通知待确认",
            0,
            database.pendingTransactionDao().getPage(10, 0).size
        )
    }

    /** 结果页路径（accessibility）不受影响 —— 这是 v1 的唯一信号源。 */
    @Test
    fun 结果页路径不受通知停用影响() = runBlocking {
        matcher.recordOrphanPaymentSignal(signal(), sourceType = "ACCESSIBILITY")

        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        assertEquals("accessibility", rows.single().sourceType)
    }

    /** 用户断言通道（manual）同样不受影响 —— 刻意绕过页面分类与信号约束。 */
    @Test
    fun 断言通道不受通知停用影响() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            signal(),
            sourceType = "MANUAL",
            conflictReason = USER_ASSERTED_AMOUNT_MISSING_REASON
        )

        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        assertEquals("manual", rows.single().sourceType)
    }

    /** 归一化必须先于判定：大写形式同样被关（调用方传的是 `"NOTIFICATION"`）。 */
    @Test
    fun 通知来源判定在归一化之后() = runBlocking {
        ensureSessionFor(CheckoutPageRole.CHECKOUT)

        val result = matcher.recordPaymentEvidence(
            reference(CheckoutPageRole.CHECKOUT),
            signal(),
            sourceType = "notification"
        )

        assertTrue(result is CheckoutPaymentMatchResult.Rejected)
    }

    // ---------- 缺陷②：0 命中不回退猜测 ----------

    /**
     * D-24/R4 的归属三分支中「0 命中 ⇒ 孤儿待确认」在调用方侧的落点。
     *
     * 原实现在此之前有一层「最近活跃结算会话」兜底（`findRecentCheckoutReferenceForPayment`），
     * 它会把证据配到「时间窗内唯一一个活跃结算会话」——若用户此时开了第二单，
     * 就可能配错。摘除后 0 命中一律落孤儿，由用户裁决（安全侧）。
     */
    @Test
    fun 归属零命中时落孤儿待确认而非猜测() = runBlocking {
        // 存在一个活跃结算会话，但指纹对不上 ⇒ 归属判定 0 命中
        ensureSessionFor(CheckoutPageRole.CHECKOUT)

        val resolution = matcher.resolveSessionForPayment(
            paymentPackage = "com.tencent.mm",
            amountMinor = 1234,
            occurredAt = now
        )

        assertTrue("应当是 NoMatch，实际=$resolution", resolution is PaymentSessionResolution.NoMatch)
    }

    /** 被删除的方法不得复活 —— 用反射钉死"不存在"这个事实。 */
    @Test
    fun 最近活跃会话兜底方法已不存在() {
        val methods = CheckoutPaymentMatcher::class.java.methods.map { it.name }

        assertTrue(
            "D-24/R4 要求显式摘除 findRecentCheckoutReferenceForPayment，方法不得复活",
            !methods.contains("findRecentCheckoutReferenceForPayment")
        )
    }
}
