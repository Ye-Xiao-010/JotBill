package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.ai.AiVisionFailureCode
import com.jotbill.app.ai.AiVisionFields
import com.jotbill.app.ai.AiVisionRequestMetadata
import com.jotbill.app.ai.AiVisionResult
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * 批 V-5（改造 D：结果页证据落库与 AI 派发的顺序）—— **弹窗时机**测试。
 *
 * ## 规格出处
 * `IMPL_PLAN_VERSION_B_2026-09-13.md` §2.4 D-2（改造后数据流）/ D-3（两个兜底）；
 * §4 批 V-5 行的验收项 = "弹窗时机测试"；§8.4 定案（`ignore` 显式终态）。
 *
 * ## 本批核心动作（一句话）
 * `pendingFromEvidenceOnly()` 的调用从"证据落库时"推迟到"AI 定论且需 review 时" ——
 * 也就是修 P5（弹窗过早 / 内容不全 / 自消失）。
 *
 * ## 为什么断言"待确认行"就等于断言"弹窗时机"
 * `PendingChangeHub.emit`（`isNew=true`）是结果页弹窗与系统提醒的**唯一触发源**，
 * 而 `upsertPendingForSession` 只在真正插入新行时 emit。故在本层：
 * - 待确认行从 0 → 1 ⇒ 那一刻发生了一次弹窗；
 * - 待确认行恒为 0 ⇒ 全程零弹窗。
 * 真机判定点见测试末尾注释（UI 层 JVM 测不到）。
 *
 * ## 判别性实证（回退后必须转红，见实施报告）
 * - 回退 ①（在 `recordPaymentEvidence` 证据先到分支恢复立即落卡）⇒ 用例 1 / 2 / 3 必红；
 * - 回退 ②（把 `ReleasedWithoutConsumer` 归为"已派发"）⇒
 *   `CheckoutPurchaseIntentCoordinatorGateTest.支付结果派发档位区分三类形态` 必红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckoutPendingTimingTest {

    private companion object {
        const val PKG = "com.xunmeng.pinduoduo"
        const val PAYMENT_PKG = "com.tencent.mm"
        const val WECHAT_ACCOUNT_ID = "default-account-wechat"
        val FP_A = "a".repeat(64)
        const val SESSION = "session-1"
        const val SESSION_2 = "session-2"
        const val START_NOW = 1_700_000_000_000L

        /** 支付 ¥100.00 ⇒ D-19 容差 = max(100, 500) = 500 分。 */
        const val PAYMENT_MINOR = 10_000L

        /** `CheckoutPaymentMatcher.EVIDENCE_ONLY_DECISION` 为 private，测试按字面值断言。 */
        const val EVIDENCE_ONLY_DECISION = "EVIDENCE_ONLY"
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
        // 自动入账需要可解析的支付账户：缺失时会改道 ACCOUNT_UNAVAILABLE 待确认（与本批无关的假红）。
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

    private fun checkoutReference(
        sessionId: String = SESSION,
        fingerprint: String = FP_A
    ) = CheckoutSnapshotReference(
        snapshotId = "snap-$sessionId",
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

    private fun paymentSignal(
        amountMinor: Long = PAYMENT_MINOR,
        sourceEventKey: String = "evt-1",
        merchant: String? = "测试商户"
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = PAYMENT_PKG,
        amountMinor = amountMinor,
        occurredAt = now,
        merchant = merchant,
        sourceEventKey = sourceEventKey
    )

    private fun metadata(sessionId: String = SESSION) = AiVisionRequestMetadata(
        requestKey = CheckoutRequestKey.from(sessionId, PKG, FP_A),
        intent = PurchaseIntent.PAYMENT_RESULT,
        sessionId = sessionId,
        snapshotId = "snap-$sessionId",
        slotId = checkoutSnapshotSlotId(PKG),
        shoppingPackage = PKG,
        pageFingerprint = FP_A,
        candidateRevision = 1,
        capturedAt = now,
        snapshotExpiresAt = now + 120_000
    )

    /** AI 识别成功；`occurredAt = null` 走"结算页常无时间字段"的真实形态（由证据时间回填）。 */
    private fun aiSucceeded(
        fieldsAmount: Long,
        productDescription: String? = "金桂白牡丹 500g",
        sessionId: String = SESSION
    ) = AiVisionResult.Succeeded(
        metadata = metadata(sessionId),
        fields = AiVisionFields(
            screenType = "CHECKOUT",
            platform = null,
            merchant = null,
            productDescription = productDescription,
            amountMinor = fieldsAmount,
            amountSemantic = "FINAL_PAYABLE",
            currency = "CNY",
            orderId = null,
            paymentMethod = null,
            occurredAt = null,
            confidence = 0.9,
            fieldConfidence = emptyMap()
        ),
        requestId = "req-1",
        provider = "test",
        model = "test",
        attempts = 1,
        tokensIn = null,
        tokensOut = null,
        latencyMillis = 1_000
    )

    /** AI 失败（含 32s 超时 ⇒ `TIMEOUT`），走 `AI_RECOGNITION_FAILED` 兜底路径。 */
    private fun aiFailed(
        code: AiVisionFailureCode = AiVisionFailureCode.TIMEOUT,
        sessionId: String = SESSION
    ) = AiVisionResult.Failed(
        metadata = metadata(sessionId),
        code = code,
        attempts = 2,
        requestId = "req-1",
        provider = "test",
        model = "test",
        latencyMillis = 32_000
    )

    private suspend fun pendingRows(): List<PendingTransactionEntity> =
        database.pendingTransactionDao().getPage(50, 0)

    private suspend fun sessionOf(sessionId: String = SESSION) =
        database.checkoutSessionDao().getById(sessionId)

    private suspend fun candidateOf(sessionId: String = SESSION) =
        database.transactionCandidateDao().getBySession(sessionId).singleOrNull()

    private fun decisionOf(appliedRules: String?): String? =
        appliedRules
            ?.let { runCatching { JSONObject(it).optString("decision") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /** 证据先到（AI 未返回）的起点：会话应停在 WAITING_ENRICHMENT + EVIDENCE_ONLY 占位。 */
    private suspend fun recordEvidenceFirst(
        sessionId: String = SESSION,
        sourceEventKey: String = "evt-1"
    ) {
        matcher.recordPaymentEvidence(
            reference = checkoutReference(sessionId = sessionId),
            signal = paymentSignal(sourceEventKey = sourceEventKey),
            sourceType = "ACCESSIBILITY"
        )
    }

    // ---------- a) 证据到 + AI 派发成功 + AI 无冲突 ⇒ 无弹窗（P5 的修复）----------

    @Test
    fun 证据先到不落待确认_AI无冲突时全程零弹窗() = runBlocking {
        recordEvidenceFirst()

        // ① 证据已落库（不丢）；② 会话进入 WAITING_ENRICHMENT 等待 AI 定论
        assertEquals(
            "证据先到时不得落待确认：P5 的弹窗必须等 AI 定论",
            0,
            pendingRows().size
        )
        val session = sessionOf()
        assertNotNull(session)
        assertEquals(
            "会话必须停在 WAITING_ENRICHMENT（等待 AI 定论的显式中间态）",
            CheckoutJourneyState.WAITING_ENRICHMENT.wireValue,
            session!!.journeyState
        )
        assertEquals(
            "证据必须已持久化（改造 D ① 立即落库，不丢）",
            1,
            database.paymentEvidenceDao().getBySession(SESSION).size
        )
        val candidate = candidateOf()
        assertNotNull("必须建立 EVIDENCE_ONLY 占位候选以承载会话关联", candidate)
        assertEquals(
            "占位候选必须带 EVIDENCE_ONLY 标记（启动期兜底扫描靠它识别'无 AI 结果'）",
            EVIDENCE_ONLY_DECISION,
            decisionOf(candidate!!.appliedRules)
        )

        // ③ AI 定论：无冲突 ⇒ 自动入账，且全程零弹窗
        matcher.recordAiResult(aiSucceeded(fieldsAmount = PAYMENT_MINOR))

        assertEquals("AI 无冲突 ⇒ 不得弹窗", 0, pendingRows().size)
        assertEquals("应自动入账", 1, database.billDao().count())
        assertEquals(
            "会话应到 COMPLETED",
            SessionStatus.COMPLETED.wireValue,
            sessionOf()!!.status
        )
    }

    // ---------- b) 证据到 + AI 定论需 review ⇒ 有弹窗且字段完整 ----------

    @Test
    fun AI定论需review时才落待确认且字段完整() = runBlocking {
        recordEvidenceFirst()
        assertEquals("定论之前必须没有卡", 0, pendingRows().size)

        // AI 读到 8000（差 2000 > 容差 500）⇒ 冲突 ⇒ 此时才落待确认
        matcher.recordAiResult(
            aiSucceeded(fieldsAmount = 8_000L, productDescription = "金桂白牡丹 500g")
        )

        val pending = pendingRows().single()
        assertEquals("AI 需 review ⇒ 恰好一次弹窗", 1, pendingRows().size)
        assertEquals(SESSION, pending.sessionId)
        assertNotNull("必须关联候选", pending.candidateId)
        assertEquals("AI_AMOUNT_LOWER_THAN_PAYMENT", pending.conflictReason)

        // 字段完整（P5 的"内容不全"修复）：金额取证据事实值，商品描述取 AI 定论值
        assertEquals("金额口径 = 支付证据金额", PAYMENT_MINOR, pending.amountMinor)
        assertEquals("商品描述必须来自 AI 定论（非空）", "金桂白牡丹 500g", pending.productDescription)
        assertEquals("商户来自支付证据", "测试商户", pending.merchant)
        assertTrue("发生时间必须有值", pending.occurredAt > 0L)
        // ⚠️ categoryId 恒为 null 属**已知现状**（session-linked 待确认的分类在入账时才确定；
        // 规则 set_category 的补全目前只在孤儿卡与账单上生效）⇒ 本批不混入，已登记待裁定。
        assertNull(pending.categoryId)

        assertEquals("冲突不得入账", 0, database.billDao().count())
        assertEquals(
            "会话应等用户确认",
            CheckoutJourneyState.NEEDS_CONFIRMATION.wireValue,
            sessionOf()!!.journeyState
        )
    }

    // ---------- c) 证据到 + AI 派发失败 ⇒ 立即有弹窗（兜底路径）----------

    @Test
    fun 派发失败立即落待确认且幂等() = runBlocking {
        recordEvidenceFirst()
        assertEquals("派发之前没有卡", 0, pendingRows().size)

        // 派发档位 = NOT_DISPATCHED（AI 未配置 / 派发失败 / 预算耗尽 / 门禁拒绝）时，
        // 服务层会调本方法立即补落待确认。
        val promoted = matcher.promoteEvidenceOnlySessionToPending(
            sessionId = SESSION,
            reason = PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE
        )

        assertTrue("派发失败必须立即落卡，不得静默", promoted)
        val pending = pendingRows().single()
        assertEquals("恰好一张卡", 1, pendingRows().size)
        assertEquals(PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE, pending.conflictReason)
        assertEquals(SESSION, pending.sessionId)
        assertEquals(PAYMENT_MINOR, pending.amountMinor)
        assertEquals(
            "会话应等用户确认",
            CheckoutJourneyState.NEEDS_CONFIRMATION.wireValue,
            sessionOf()!!.journeyState
        )

        // 幂等：重复调用（例如同一次派发失败被两条路径触发）不产生第二条卡
        matcher.promoteEvidenceOnlySessionToPending(
            sessionId = SESSION,
            reason = PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE
        )
        assertEquals("重复调用不得产生第二条卡", 1, pendingRows().size)
    }

    @Test
    fun 同会话第二笔证据到达仍不落待确认() = runBlocking {
        // 钉死第二个调用点（EVIDENCE_ONLY 占位复用分支）：只改第一个点会留下半条早弹窗路径
        recordEvidenceFirst(sourceEventKey = "evt-1")
        recordEvidenceFirst(sourceEventKey = "evt-1b")

        assertEquals("AI 未返回时第二笔证据同样不得落卡", 0, pendingRows().size)
        assertEquals(
            "会话仍停在 WAITING_ENRICHMENT",
            CheckoutJourneyState.WAITING_ENRICHMENT.wireValue,
            sessionOf()!!.journeyState
        )
    }

    // ---------- d) 兜底 1：AI 超时（32s）⇒ 落待确认 ----------

    @Test
    fun 兜底1_AI超时失败落待确认() = runBlocking {
        recordEvidenceFirst()
        assertEquals(0, pendingRows().size)

        matcher.recordAiResult(aiFailed(AiVisionFailureCode.TIMEOUT))

        val pending = pendingRows().single()
        assertEquals("超时兜底恰好一张卡", 1, pendingRows().size)
        assertEquals("AI_RECOGNITION_FAILED", pending.conflictReason)
        assertEquals("金额取证据", PAYMENT_MINOR, pending.amountMinor)
        assertEquals("失败不得入账", 0, database.billDao().count())
        assertEquals(
            "会话应等用户确认，不得长期悬置",
            CheckoutJourneyState.NEEDS_CONFIRMATION.wireValue,
            sessionOf()!!.journeyState
        )
    }

    // ---------- e) 兜底 2：启动扫描 ⇒ 落待确认；幂等；不与孤儿卡重复 ----------

    @Test
    fun 兜底2_启动扫描落待确认_幂等且不与孤儿卡重复() = runBlocking {
        // 会话 1：证据先到 + AI 未返回（等价于进程被杀，在飞请求随作用域消失）
        recordEvidenceFirst(sessionId = SESSION, sourceEventKey = "evt-1")
        assertEquals("重启前用户看不到该笔（证据停在中间态）", 0, pendingRows().size)

        // 启动期兜底扫描（Service 在服务连接时调用）
        val first = matcher.backfillEvidenceOnlyCandidates()
        assertEquals(
            "占位候选已存在 ⇒ 无需补齐候选",
            0,
            first.candidatesBackfilled
        )
        assertEquals("必须落待确认，避免静默丢失", 1, first.pendingsPromoted)
        val pending = pendingRows().single()
        assertEquals(AI_RESULT_MISSING_ON_STARTUP, pending.conflictReason)
        assertEquals(SESSION, pending.sessionId)

        // 二次扫描幂等：会话已推进到 NEEDS_CONFIRMATION ⇒ 不再命中，也不产生第二条卡
        val second = matcher.backfillEvidenceOnlyCandidates()
        assertEquals(0, second.pendingsPromoted)
        assertEquals("二次扫描不得产生第二条卡", 1, pendingRows().size)

        // 会话 2：同一笔已由协调器孤儿出口落卡（支付过程槽位缺图）⇒ 扫描不得再补一条
        recordEvidenceFirst(sessionId = SESSION_2, sourceEventKey = "evt-2")
        matcher.recordOrphanPaymentSignal(
            signal = paymentSignal(sourceEventKey = "evt-2"),
            sourceType = "ACCESSIBILITY",
            conflictReason = PAYMENT_PROCESS_SNAPSHOT_MISSING
        )
        matcher.backfillEvidenceOnlyCandidates()

        val rows = pendingRows()
        assertEquals("孤儿卡存在时不得再落 session-linked 卡（同笔双卡）", 2, rows.size)
        assertTrue(
            "会话 2 不得出现 session-linked 卡",
            rows.none { it.sessionId == SESSION_2 }
        )
        assertTrue(
            "协调器孤儿卡的锚点必须保留",
            rows.any { it.conflictReason == PAYMENT_PROCESS_SNAPSHOT_MISSING }
        )
    }

    // ---------- f) §8.4：ignore 的显式终态 ----------

    /**
     * 证据先到形态：规则在支付前就已配置 ⇒ AI 定论到达时命中 `ignore`。
     *
     * **修复前**该会话会停在 `WAITING_ENRICHMENT` 等过期回收 —— 既不写 Bill、不进待确认、
     * 也没有终态标记，即 P18 的静默悬置态（改造 D 之后更没人会再来推进它）。
     */
    @Test
    fun 规则ignore给会话显式终态_证据先到形态() = runBlocking {
        insertIgnoreRule()
        recordEvidenceFirst()
        assertEquals("AI 未返回时不落卡", 0, pendingRows().size)

        matcher.recordAiResult(aiSucceeded(fieldsAmount = PAYMENT_MINOR))

        assertEquals("ignore 不得入账", 0, database.billDao().count())
        assertEquals("ignore 不得弹窗", 0, pendingRows().size)
        val session = sessionOf()!!
        assertEquals(
            "ignore 必须落显式终态（不得写 COMPLETED，也不得悬置等过期）",
            SessionStatus.CANCELLED.wireValue,
            session.status
        )
        assertEquals(CheckoutJourneyState.DISMISSED.wireValue, session.journeyState)
    }

    /**
     * AI 先到形态：规则在候选生成**之后**新增 ⇒ 证据后到时才在"自动入账前"命中
     * `ignore`（时机二）。这条同时钉住"规则可在候选生成后被创建/修改"的复评语义。
     */
    @Test
    fun 规则ignore给会话显式终态_AI先到形态() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        matcher.recordAiResult(aiSucceeded(fieldsAmount = PAYMENT_MINOR))
        insertIgnoreRule()

        recordEvidenceFirst()

        assertEquals("ignore 不得入账", 0, database.billDao().count())
        assertEquals("ignore 不得弹窗", 0, pendingRows().size)
        val session = sessionOf()!!
        assertEquals("ignore 必须落显式终态", SessionStatus.CANCELLED.wireValue, session.status)
        assertEquals(CheckoutJourneyState.DISMISSED.wireValue, session.journeyState)
    }

    /**
     * ⚠️ 为什么不给 `autoCommitMatchedCandidate` 的 `decisionOf(...) == IGNORED` 预检单独造用例：
     * 批 V-5 实测发现该预检在当前调用图中**不可达** —— 两个调用点传入的都是
     * `candidateAfterPayment(...)` 之后的候选，而它会用 `mergeRulesJson` 把
     * `appliedRules.decision` 覆写为 `MATCHED` / `NEEDS_CONFIRMATION`
     * （见 `CheckoutPaymentMatcher.candidateAfterPayment`）⇒ 手工构造的 `IGNORED` 标记
     * 到不了该分支（首个版本的用例即因此转红：`ignore 不得入账 expected:<0> but was:<1>`）。
     * 真正的 `ignore` 拦截在**时机二** `ruleEngine.evaluateAndRecord`，已由上面两个用例覆盖。
     * 该预检处的终态写入作为**防御性**保留（若将来不再覆写 `decision`，它即为正确拦截）。
     */

    /** 造一条"忽略按 AI 视觉记账的交易"的用户规则（忽略任意商户/金额，只按来源命中）。 */
    private suspend fun insertIgnoreRule() {
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-ignore-test",
                name = "测试规则：忽略 AI 记账",
                condition = "source_equals",
                conditionValue = "ai_vision",
                action = "ignore",
                actionValue = null,
                priority = 1,
                isActive = true,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
