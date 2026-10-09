package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import com.jotbill.app.pending.isUserAssertedConflictReason
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
        sourceEventKey: String = "evt-1",
        paymentPackage: String = "com.tencent.mm",
        occurredAt: Long = now
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = paymentPackage,
        amountMinor = amountMinor,
        occurredAt = occurredAt,
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

    /**
     * ⚠️ 2026-09-29 修正：本用例原先以 `sourceType = "NOTIFICATION"` 作为主路径。
     *
     * D-3 / G3.1 已裁定「v1 信号源收敛为支付结果页单一来源，通知整体推迟到版本更新」，
     * 处置方式为**粒度 2**（出口开关）：通知来源的孤儿待确认**不再落**。本次在
     * `recordOrphanPaymentSignal` 内补上该闸（此前只补了 `recordPaymentEvidence`，
     * 属漏实现），故本用例改为以结果页路径（`ACCESSIBILITY`）为主路径。
     *
     * 通知闸本身的判别性用例见 `CheckoutAttributionLeftoversTest`。
     */
    @Test
    fun orphanPendingInsertedWithNormalizedSourceAndIdempotentKey() = runBlocking {
        matcher.recordOrphanPaymentSignal(paymentSignal(), sourceType = "ACCESSIBILITY")
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("expense", row.type)
        assertEquals("accessibility", row.sourceType)
        assertEquals("NO_ACTIVE_SESSION", row.conflictReason)
        assertEquals(1234L, row.amountMinor)

        // 同一 sourceEventKey 重复处理不产生第二行（幂等键与来源类型无关）
        matcher.recordOrphanPaymentSignal(paymentSignal(), sourceType = "ACCESSIBILITY")
        assertEquals(1, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /**
     * 实测（批 V-4）：改造 A-3 接线后，同一笔支付可能经**两条路径**各落一次孤儿。
     *
     * 调用序（由 A-3 的接线位置决定，实测确认）：
     * 1. **Service 侧先落** —— 主路径 `scope.launch { recordPaymentResultEvidence(...) }`
     *    在同一协程内**同步挂起**到事务提交，才轮到 `dispatchPaymentResultIntent`；
     * 2. **协调器侧后落** —— `trigger(PAYMENT_RESULT)` → 归属成功但支付过程槽位无图 ⇒
     *    `PAYMENT_PROCESS_SNAPSHOT_MISSING`，其落库出口是**另一个协程**
     *    （`configurePaymentSignalOrphanSink` 内 `scope.launch`）⇒ 必然晚于第 1 步。
     *
     * 两条路径共用 `stableId("pending-orphan", sourceEventKey)`。**改前实测**：后到的
     * `PAYMENT_PROCESS_SNAPSHOT_MISSING` 被幂等跳过并丢弃 ⇒ R2 的"量化搬运漏检率"
     * 锚点丢失。**处置后**：合并保留（既有码在前 + 新码追加），本用例钉死该行为。
     */
    @Test
    fun 孤儿双落库_后到的搬运漏检原因被合并保留() = runBlocking {
        val signal = paymentSignal(sourceEventKey = "evt-orphan-dual-path")

        // ① Service 侧（先）
        matcher.recordOrphanPaymentSignal(
            signal,
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        // ② 协调器侧（后）—— 搬运漏检的量化锚点
        matcher.recordOrphanPaymentSignal(
            signal,
            sourceType = "ACCESSIBILITY",
            conflictReason = PAYMENT_PROCESS_SNAPSHOT_MISSING
        )

        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals("同一 sourceEventKey 只落一条待确认（无重复卡片）", 1, rows.size)
        assertEquals(
            "既有码必须在前（保住 startsWith 前缀语义），新码追加在后",
            "NO_ACTIVE_SESSION,$PAYMENT_PROCESS_SNAPSHOT_MISSING",
            rows.single().conflictReason
        )

        // ③ 再次重复落库：不得重复追加同一码（幂等）
        matcher.recordOrphanPaymentSignal(
            signal,
            sourceType = "ACCESSIBILITY",
            conflictReason = PAYMENT_PROCESS_SNAPSHOT_MISSING
        )
        assertEquals(
            "重复落库不得重复追加",
            "NO_ACTIVE_SESSION,$PAYMENT_PROCESS_SNAPSHOT_MISSING",
            database.pendingTransactionDao().getPage(10, 0).single().conflictReason
        )
    }

    /**
     * 合并不得破坏 D-21 的 `isUserAssertedConflictReason` 前缀语义。
     *
     * 手动断言通道的冲突码以 `USER_ASSERTED` 开头，`PendingChangeHub` 用
     * `startsWith` 识别"用户断言"分支；若合并时把新码插到前面，该分支会失效
     * ⇒ 手动断言产生的待确认会被当成普通冲突（弹窗与文案都错）。
     */
    @Test
    fun 孤儿冲突码合并保住用户断言前缀语义() = runBlocking {
        val signal = paymentSignal(sourceEventKey = "evt-orphan-asserted")
        matcher.recordOrphanPaymentSignal(
            signal,
            sourceType = "MANUAL",
            conflictReason = "USER_ASSERTED_AMOUNT_MISSING"
        )
        matcher.recordOrphanPaymentSignal(
            signal,
            sourceType = "MANUAL",
            conflictReason = PAYMENT_PROCESS_SNAPSHOT_MISSING
        )
        val reason = database.pendingTransactionDao().getPage(10, 0).single().conflictReason
        assertTrue("前缀语义必须保留，实际=$reason", reason!!.startsWith("USER_ASSERTED"))
        assertTrue("锚点必须同时保留，实际=$reason", reason.contains(PAYMENT_PROCESS_SNAPSHOT_MISSING))
        assertTrue(isUserAssertedConflictReason(reason))
    }

    @Test
    fun orphanSourceTypeVariantsNormalizeToWireValues() = runBlocking {
        // ⚠️ 两笔用不同金额：D-25 起同支付包 + 同金额 + 60 秒内的信号会被判为同一笔的
        // 重复观测而不再建卡（见下方 `孤儿跨桶重复观测_*` 系列用例）。本用例验的是
        // 来源类型归一化，两条必须互相独立，否则第二个信号会被去重挡掉、验不到归一化。
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1234, sourceEventKey = "evt-a"),
            sourceType = "ACCESSIBILITY_RESULT"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 5678, sourceEventKey = "evt-b"),
            sourceType = "MANUAL"
        )
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(2, rows.size)
        assertEquals(
            setOf("accessibility", "manual"),
            rows.map { it.sourceType }.toSet()
        )
    }

    // ---------- D-25：孤儿待确认跨桶重复观测去重 ----------

    /**
     * D-25 正例：同一笔支付的结果页被观测两次，事件键跨了 30 秒时间桶 ⇒
     * `sourceEventKey` 幂等键挡不住 ⇒ 必须靠"同包同额 + 60 秒窗"补口。
     *
     * 真机数据（2026-09-29 拼多多 build 0.0.98，¥19.90）：
     * `occurredAt=1790687322157`（桶 59689577）与 `occurredAt=1790687342160`（桶 59689578），
     * 间隔 20 秒。旧实现落了两条待确认（`pending-orphan-2` / `pending-orphan-4`）。
     */
    @Test
    fun 孤儿跨桶重复观测_同包同额60秒内不新建第二张卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1990, sourceEventKey = "evt-bucket-a", occurredAt = now),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        // 跨桶：不同 sourceEventKey，但同包同额、间隔 20 秒 ⇒ 同一笔的重复观测
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-b",
                occurredAt = now + 20_000L
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )

        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals("同包同额 90 秒内只落一张待确认卡", 1, rows.size)
        assertEquals(
            "重复观测不得重复追加同一码",
            "NO_ACTIVE_SESSION",
            rows.single().conflictReason
        )
    }

    /**
     * **真机回归用例**（2026-09-30 10:05~10:06，小米 25128PNA1C / 拼多多 build 0.0.98）。
     *
     * 同一笔 ¥2.68 的微信结果页被观测两次，`textLength` 均为 20（同一页面），
     * `occurredAt` 分别为 1790733945898 / 1790734006291，相差 **60393 ms**。
     * 当时 `ORPHAN_DEDUP_WINDOW_MILLIS = 60_000` ⇒ **超了 393 毫秒，两条都建了卡**
     * （`pending-orphan-044f32b` / `pending-orphan-bf77dd6`）。
     *
     * 本例用真机原值钉死该场景，防止窗口被改回 60 秒。
     * 判别性：窗口 ≤ 60393 ms 时本例必红。
     */
    @Test
    fun 孤儿去重_真机实测60393毫秒的同一结果页重复观测只建一张卡() = runBlocking {
        val firstOccurredAt = 1_790_733_945_898L
        val secondOccurredAt = 1_790_734_006_291L
        assertEquals(
            "真机两次观测的间隔应与日志一致（60393 ms）",
            60_393L,
            secondOccurredAt - firstOccurredAt
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 268,
                sourceEventKey = "evt-real-a",
                occurredAt = firstOccurredAt
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 268,
                sourceEventKey = "evt-real-b",
                occurredAt = secondOccurredAt
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        assertEquals(
            "真机实测的 60393 ms 重复必须被去重挡下（窗口 ≤ 该值时本例必红）",
            1,
            database.pendingTransactionDao().getPage(10, 0).size
        )
    }

    /** D-25：重复观测**不得丢证据** —— 后到的 conflictReason 必须合并进既有卡。 */
    @Test
    fun 孤儿跨桶重复观测_冲突原因合并进既有卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1990, sourceEventKey = "evt-bucket-a", occurredAt = now),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-b",
                occurredAt = now + 20_000L
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = PAYMENT_RESULT_NO_MATCHING_SESSION
        )
        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals(1, rows.size)
        assertEquals(
            "既有码在前、新码追加（保住 startsWith 前缀语义）",
            "NO_ACTIVE_SESSION,$PAYMENT_RESULT_NO_MATCHING_SESSION",
            rows.single().conflictReason
        )
    }

    /**
     * D-25 负例：**超过窗口**必须各自建卡。
     *
     * ⚠️ 2026-09-30 修订：本例原用 `now + 90_000L`（当时的"超过 60 秒"）。
     * 窗口调至 90 秒后该值**正好卡在边界上**（`BETWEEN` 两端闭区间 ⇒ 仍会去重），
     * 用它当负例会假绿。改用 `now + 150_000L`：落在窗口外，且**仍在 D-6 的 2 分钟内**
     * —— 顺带证明本窗口确实窄于 D-6，没有把"同额两单撞键"的风险引回来。
     *
     * 判别性：若窗口写错（误用 `PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 的 2 分钟），
     * 本用例必红 —— 而那正好会重现 D-6 明确要避免的"同包同金额、2 分钟内两单撞键"。
     */
    @Test
    fun 孤儿去重_超过90秒窗口的同额两笔各自建卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1990, sourceEventKey = "evt-t0", occurredAt = now),
            sourceType = "ACCESSIBILITY"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-t150",
                occurredAt = now + 150_000L
            ),
            sourceType = "ACCESSIBILITY"
        )
        assertEquals(2, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /** D-25 负例：不同金额不合并（D-6「同额两笔都保留」的对偶，必须守住）。 */
    @Test
    fun 孤儿去重_不同金额不合并() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1990, sourceEventKey = "evt-amt-a", occurredAt = now),
            sourceType = "ACCESSIBILITY"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 2990,
                sourceEventKey = "evt-amt-b",
                occurredAt = now + 20_000L
            ),
            sourceType = "ACCESSIBILITY"
        )
        assertEquals(2, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /** D-25 负例：不同支付包不合并（微信结果页与支付宝结果页各自独立）。 */
    @Test
    fun 孤儿去重_不同支付包不合并() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-pkg-a",
                paymentPackage = "com.tencent.mm",
                occurredAt = now
            ),
            sourceType = "ACCESSIBILITY"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-pkg-b",
                paymentPackage = "com.eg.android.AlipayGphone",
                occurredAt = now + 20_000L
            ),
            sourceType = "ACCESSIBILITY"
        )
        assertEquals(2, database.pendingTransactionDao().getPage(10, 0).size)
    }

    /**
     * D-25 负例：已被用户忽略的记录**不再吸收**新的冲突码，也不阻新建卡。
     *
     * 判别性：若 DAO 查询漏了 `status = 'pending'` 条件，本用例必红。
     */
    @Test
    fun 孤儿去重_已忽略的记录不吸收也不阻新建() = runBlocking {
        val first = paymentSignal(amountMinor = 1990, sourceEventKey = "evt-ign-a", occurredAt = now)
        matcher.recordOrphanPaymentSignal(first, sourceType = "ACCESSIBILITY")
        val dao = database.pendingTransactionDao()
        val row = dao.getPage(10, 0).single()
        dao.markIgnoredIfVersion(row.id, row.version)
        assertEquals(0, dao.getPage(10, 0).size)

        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-ign-b",
                occurredAt = now + 20_000L
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = PAYMENT_RESULT_NO_MATCHING_SESSION
        )
        assertEquals("已忽略的卡不得吸收新码，且新卡照常建", 1, dao.getPage(10, 0).size)
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

    /**
     * 批 V-5（改造 D）适配：证据先到**不再**立即落待确认（推迟到 AI 定论 / 定论不可得）。
     *
     * 本用例的不变量**未变**——「AI 未验证证据不得自动入账，必须进待确认等用户核对」；
     * 变化的只是**落卡时点**：
     * 旧行为 = 证据落库时落卡（P5 弹窗过早）→ 新行为 = AI 定论时落卡，而截图兜底路径
     * 本就不会有结算页 AI 定论（它直调视觉客户端），故由服务层显式补落。
     * 这里直接调用同一出口，钉死"该形态最终一定有卡"。
     */
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
        // 批 V-5:证据先到时**不落卡**（弹窗推迟到 AI 定论之后）
        assertEquals(
            "证据先到不得立即落待确认(批 V-5,result=$result)",
            0,
            database.pendingTransactionDao().getPage(10, 0).size
        )
        // 截图兜底形态没有结算页 AI 定论 ⇒ 由"定论不可得"出口落卡,证据不静默
        assertTrue(
            matcher.promoteEvidenceOnlySessionToPending(
                sessionId = SESSION,
                reason = PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE
            )
        )
        val pendings = database.pendingTransactionDao().getPage(10, 0)
        assertEquals("AI 未验证证据最终必须进待确认,等待用户核对", 1, pendings.size)
        assertEquals(SESSION, pendings.single().sessionId)
        assertEquals(
            PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE,
            pendings.single().conflictReason
        )
        // 落卡后仍不得入账
        assertEquals(0, database.billDao().count())
    }

    // ---------- 契约 4:用户断言截图会话收口为待确认 ----------

    private fun assertedCandidate(
        sessionId: String = SESSION,
        amountMinor: Long? = 1234L
    ) = TransactionCandidateEntity(
        id = "candidate-$sessionId",
        sessionId = sessionId,
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

    @Test
    fun promoteAssertedSessionCreatesPendingFromCandidateWithoutEvidence() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        database.transactionCandidateDao().insertIfAbsent(assertedCandidate())

        assertTrue(matcher.promoteAssertedSessionToPending(SESSION))
        val pending = database.pendingTransactionDao().getPage(10, 0).single()
        assertEquals(1234L, pending.amountMinor)
        assertEquals("USER_ASSERTED", pending.conflictReason)
        assertEquals("ai_vision", pending.sourceType)
        assertEquals("expense", pending.type)
        assertEquals(SESSION, pending.sessionId)
        assertEquals("测试商户", pending.merchant)
    }

    @Test
    fun promoteAssertedSessionIsIdempotent() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        database.transactionCandidateDao().insertIfAbsent(assertedCandidate())
        matcher.promoteAssertedSessionToPending(SESSION)
        matcher.promoteAssertedSessionToPending(SESSION)
        assertEquals(1, database.pendingTransactionDao().getPage(10, 0).size)
    }

    @Test
    fun promoteAssertedSessionFallsBackToZeroAmountWhenCandidateHasNoAmount() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        database.transactionCandidateDao().insertIfAbsent(assertedCandidate(amountMinor = null))
        assertTrue(matcher.promoteAssertedSessionToPending(SESSION))
        val pending = database.pendingTransactionDao().getPage(10, 0).single()
        assertEquals(0L, pending.amountMinor)
        assertEquals("USER_ASSERTED_AMOUNT_MISSING", pending.conflictReason)
    }

    @Test
    fun promoteAssertedSessionSkipsWhenPaidEvidenceAlreadyExists() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        database.transactionCandidateDao().insertIfAbsent(assertedCandidate())
        database.paymentEvidenceDao().insert(
            PaymentEvidenceEntity(
                id = "evidence-1",
                sessionId = SESSION,
                sourceType = "notification",
                amountMinor = 1234L,
                merchant = "测试商户",
                platform = "com.tencent.mm",
                orderId = null,
                paymentMethod = null,
                paymentStatus = "SUCCESS",
                occurredAt = now,
                rawTextHash = "hash-1",
                sourceEventKey = "evt-asserted-1",
                capturedAt = now
            )
        )
        // 已有支付成功证据：正常匹配链路负责自动入账或冲突转待确认，promote 不抢跑
        assertTrue(!matcher.promoteAssertedSessionToPending(SESSION))
        assertEquals(0, database.pendingTransactionDao().getPage(10, 0).size)
    }

    @Test
    fun promoteAssertedSessionReturnsFalseWithoutCandidate() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        assertTrue(!matcher.promoteAssertedSessionToPending(SESSION))
        assertEquals(0, database.pendingTransactionDao().getPage(10, 0).size)
    }
}
