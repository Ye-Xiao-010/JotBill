package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-19 金额容差（双向有界 `max(¥1.00, 支付金额 × 5%)`）测试。
 *
 * ## 为什么两层都要有用例
 *
 * 金额不对称有两处实现，**必须同改**：
 * ① `CheckoutPaymentMatcher.evaluate` —— 判定候选是否与支付金额属于同一笔；
 * ② `TransactionCandidateDao.findUnoccupiedByAmountCovering` —— 会话解析阶段的 SQL 检索带。
 *
 * ② 在 ① **之前**执行：只改 ① 时，**AI 金额更低**的候选会在会话解析阶段就被 SQL 滤掉，
 * 证据落孤儿待确认、根本走不到 `evaluate()` ⇒ 新容差在下行方向完全失效。
 * 而只跑 ① 的用例看不出来 ⇒ 本文件对两层分别设防（用例 2 = 层②；用例 4 = 层①）。
 *
 * ## 判别性
 *
 * - 层② 用例 2：SQL 退回单边 `amount_minor >= :amountMinor` ⇒ 候选被滤掉、返回 `NoMatch` ⇒ **必红**。
 * - 层① 用例 4：`evaluate` 退回单边不等式 ⇒ 判为 `AI_AMOUNT_LOWER_THAN_PAYMENT`、
 *   不自动入账 ⇒ **必红**。
 * - 用例 6 覆盖**本次新增的拦截方向**（AI 读高）：退回单边时该场景会被放行 ⇒ **必红**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckoutPaymentAmountToleranceTest {

    private companion object {
        const val PKG = "com.xunmeng.pinduoduo"
        const val PAYMENT_PKG = "com.tencent.mm"

        /** 与 CheckoutPaymentMatcher.WECHAT_PAY_ACCOUNT_ID 一致（对方为 private 常量）。 */
        const val WECHAT_ACCOUNT_ID = "default-account-wechat"
        val FP_A = "a".repeat(64)
        const val SESSION = "session-1"
        const val START_NOW = 1_700_000_000_000L

        /** 容差基准：支付 ¥100.00 ⇒ 容差 = max(100, 500) = 500 分（¥5.00）。 */
        const val PAYMENT_MINOR = 10_000L
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
        // 自动入账需要可解析的支付账户：缺失时 accountId 为 null，
        // autoCommitMatchedCandidate 会改道 ACCOUNT_UNAVAILABLE 待确认（与容差无关）。
        runBlocking {
            database.accountDao().insert(
                AccountEntity(
                    id = WECHAT_ACCOUNT_ID,
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
                    createdAt = START_NOW,
                    updatedAt = START_NOW
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------- 构造 ----------

    private fun checkoutReference() = CheckoutSnapshotReference(
        snapshotId = "snap-1",
        sessionId = SESSION,
        slotId = checkoutSnapshotSlotId(PKG),
        shoppingPackage = PKG,
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = FP_A,
        capturedAt = now,
        expiresAt = now + 120_000,
        candidateRevision = 1,
        sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
    )

    private fun paymentSignal(amountMinor: Long) = PaymentNotificationFallbackSignal(
        paymentPackage = PAYMENT_PKG,
        amountMinor = amountMinor,
        occurredAt = now,
        sourceEventKey = "evt-$amountMinor"
    )

    /** AI 识别结果候选：金额为页面读到的值，尚未与证据绑定。 */
    private fun aiCandidate(amountMinor: Long) = TransactionCandidateEntity(
        id = "candidate-$SESSION",
        sessionId = SESSION,
        amountMinor = amountMinor,
        platform = null,
        merchant = "测试商户",
        productDescription = null,
        evidenceAmountMinor = null,
        evidenceMerchant = null,
        evidenceOrderId = null,
        aiOrderId = null,
        aiOccurredAt = now,
        matched = false,
        conflictReason = null,
        appliedRules = "{}",
        createdAt = now,
        version = 1
    )

    private fun seedSessionAndCandidate(candidateAmountMinor: Long): CheckoutSnapshotReference =
        runBlocking {
            val reference = checkoutReference()
            matcher.ensureSession(
                reference = reference,
                requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
            )
            database.transactionCandidateDao().insertIfAbsent(aiCandidate(candidateAmountMinor))
            reference
        }

    private suspend fun storedCandidate() =
        database.transactionCandidateDao().getBySession(SESSION).single()

    // ---------- 容差公式（纯函数，两层共用同一口径） ----------

    @Test
    fun toleranceIsLargerOfFixedFloorAndFivePercent() {
        // 小额：比例项 (1000×5% = 50) 小于下限 ⇒ 取下限 ¥1.00
        assertEquals(100L, paymentAmountToleranceMinor(1_000L))
        // 临界：10000×5% = 500 = 下限的 5 倍 ⇒ 取比例项
        assertEquals(500L, paymentAmountToleranceMinor(10_000L))
        // 大额：比例项主导
        assertEquals(5_000L, paymentAmountToleranceMinor(100_000L))
    }

    @Test
    fun classifyKeepsDirectionOnlyOutsideToleranceBand() {
        // 带内：两个方向都视为同一笔（不区分方向）
        assertEquals(
            PaymentAmountMatch.WITHIN_TOLERANCE,
            classifyPaymentAmount(9_500L, PAYMENT_MINOR)
        )
        assertEquals(
            PaymentAmountMatch.WITHIN_TOLERANCE,
            classifyPaymentAmount(10_500L, PAYMENT_MINOR)
        )
        // 边界：恰等于容差仍在带内
        assertEquals(
            PaymentAmountMatch.WITHIN_TOLERANCE,
            classifyPaymentAmount(10_500L, PAYMENT_MINOR)
        )
        // 带外：保留方向
        assertEquals(
            PaymentAmountMatch.AI_LOWER,
            classifyPaymentAmount(9_499L, PAYMENT_MINOR)
        )
        assertEquals(
            PaymentAmountMatch.AI_HIGHER,
            classifyPaymentAmount(10_501L, PAYMENT_MINOR)
        )
    }

    // ---------- 层②：DAO 检索带（必须在会话解析阶段就命中） ----------

    @Test
    fun sessionResolvesWhenAiAmountLowerButWithinTolerance() = runBlocking {
        seedSessionAndCandidate(candidateAmountMinor = 9_500L)

        val resolution = matcher.resolveSessionForPayment(
            paymentPackage = PAYMENT_PKG,
            amountMinor = PAYMENT_MINOR,
            occurredAt = now
        )

        // AI 少读 ¥5.00（含运费差）落在 5% 容差内 ⇒ 必须能解析到会话，
        // 否则证据落孤儿待确认、根本到不了 evaluate()。
        assertTrue(
            "AI 金额更低但落在容差内时应解析到会话，实际=$resolution",
            resolution is PaymentSessionResolution.Matched
        )
        assertEquals(
            SESSION,
            (resolution as PaymentSessionResolution.Matched).reference.sessionId
        )
    }

    @Test
    fun sessionDoesNotResolveWhenAiAmountOutsideTolerance() = runBlocking {
        seedSessionAndCandidate(candidateAmountMinor = 8_000L)

        val resolution = matcher.resolveSessionForPayment(
            paymentPackage = PAYMENT_PKG,
            amountMinor = PAYMENT_MINOR,
            occurredAt = now
        )

        // 差 ¥20.00 远超容差 ¥5.00 ⇒ 检索带不得命中（防"放太宽"）
        assertFalse(
            "超出容差的候选不得进入会话解析，实际=$resolution",
            resolution is PaymentSessionResolution.Matched
        )
    }

    // ---------- 层①：evaluate 判定与自动入账 ----------

    @Test
    fun evidenceAutoCommitsWhenAiAmountLowerButWithinTolerance() = runBlocking {
        val reference = seedSessionAndCandidate(candidateAmountMinor = 9_500L)

        val result = matcher.recordPaymentEvidence(
            reference = reference,
            signal = paymentSignal(PAYMENT_MINOR),
            sourceType = "ACCESSIBILITY"
        )

        assertTrue(result is CheckoutPaymentMatchResult.Accepted)
        result as CheckoutPaymentMatchResult.Accepted
        assertTrue("AI 金额更低但落在容差内应正常匹配，实际 matched=${result.matched}", result.matched)
        assertNotNull("匹配成功应自动入账并返回账单 id", result.billId)
        assertEquals(1, database.billDao().count())
        assertEquals(
            "落待确认条目数应为 0",
            0,
            database.pendingTransactionDao().getPage(10, 0).size
        )

        val stored = storedCandidate()
        assertNull("容差带内不应产生冲突原因", stored.conflictReason)
        // 复核项③：带内一律以**支付金额**入账（支付金额是事实，AI 读的是页面）
        assertEquals(PAYMENT_MINOR, stored.amountMinor)
        assertEquals(PAYMENT_MINOR, stored.evidenceAmountMinor)
    }

    @Test
    fun evidenceConflictsWhenAiAmountLowerOutsideTolerance() = runBlocking {
        val reference = seedSessionAndCandidate(candidateAmountMinor = 8_000L)

        val result = matcher.recordPaymentEvidence(
            reference = reference,
            signal = paymentSignal(PAYMENT_MINOR),
            sourceType = "ACCESSIBILITY"
        )

        assertTrue(result is CheckoutPaymentMatchResult.Accepted)
        assertFalse((result as CheckoutPaymentMatchResult.Accepted).matched)
        assertEquals(0, database.billDao().count())
        assertEquals("AI_AMOUNT_LOWER_THAN_PAYMENT", storedCandidate().conflictReason)
        assertTrue(
            "冲突候选应转待确认",
            database.pendingTransactionDao().getPage(10, 0).isNotEmpty()
        )
    }

    @Test
    fun evidenceConflictsWhenAiAmountHigherOutsideTolerance() = runBlocking {
        // 本次新增的拦截方向：AI 误读放大（如 100 读成 1000）。
        // 改动前"AI 金额更高"一律放行 ⇒ 会带着错误金额静默入账。
        val reference = seedSessionAndCandidate(candidateAmountMinor = 20_000L)

        val result = matcher.recordPaymentEvidence(
            reference = reference,
            signal = paymentSignal(PAYMENT_MINOR),
            sourceType = "ACCESSIBILITY"
        )

        assertTrue(result is CheckoutPaymentMatchResult.Accepted)
        assertFalse((result as CheckoutPaymentMatchResult.Accepted).matched)
        assertEquals(0, database.billDao().count())
        assertEquals("AI_AMOUNT_HIGHER_THAN_PAYMENT", storedCandidate().conflictReason)
        assertTrue(
            "AI 读高的冲突候选应转待确认，不得静默入账",
            database.pendingTransactionDao().getPage(10, 0).isNotEmpty()
        )
    }
}
