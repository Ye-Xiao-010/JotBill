package com.jotbill.app.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.pending.RecentPaymentCooldown
import com.jotbill.app.pending.RecentResultObservation
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
        // 同一笔判据的内存表是单例：必须复位，否则用例之间互相污染。
        // init() 注册 removed 监听（生产在 MainApplication 调用）；重复注册无害，
        // forget 是幂等的。
        RecentPaymentCooldown.reset()
        RecentResultObservation.reset()
        RecentResultObservation.init()
    }

    @After
    fun tearDown() {
        RecentPaymentCooldown.reset()
        RecentResultObservation.reset()
        database.close()
    }

    // ---------- 契约 N:落待确认必须发事件,否则付款确认弹窗不弹 ----------

    /**
     * 三条 emit 契约（2026-10-12）。
     *
     * 背景：`PendingConfirmOverlayManager` 只订阅 `PendingChangeHub`，
     * 落库不等于会弹窗 —— 真机验收要求"识别到支付成功页就弹出来"，
     * 而此前有三条落库路径**只插库不 emit** 或 **emit 的 ID 不存在**。
     * 这三个用例就是把那条因果链接钉死在单测里，避免再退化。
     */
    @Test
    fun personalTransferEmitsPendingChangeWithInsertedId() = runBlocking {
        val seen = mutableListOf<PendingChangeInfo>()
        val listener: (PendingChangeInfo) -> Unit = { seen += it }
        PendingChangeHub.addListener(listener)
        try {
            matcher.recordPersonalTransfer(
                amountMinor = 5000L,
                paymentPackage = "com.tencent.mm",
                recipient = "张三",
                occurredAt = now
            )
            val rows = database.pendingTransactionDao().getPage(10, 0)
            assertEquals(1, rows.size)

            // 事件必须发出去 —— 这条断了，弹窗永远不会弹
            assertEquals(1, seen.size)
            // emit 的 ID 必须等于真正插入的主键 —— 弹窗按 ID 回查该行
            assertEquals(rows.single().id, seen.single().pendingId)
            assertEquals("transfer", seen.single().type)
            assertEquals(5000L, seen.single().amountMinor)
        } finally {
            PendingChangeHub.removeListener(listener)
        }
    }

    @Test
    fun personalTransferSourceTypeIsWithinNativeContract() = runBlocking {
        matcher.recordPersonalTransfer(
            amountMinor = 5000L,
            paymentPackage = "com.tencent.mm",
            recipient = "张三",
            occurredAt = now
        )
        val row = database.pendingTransactionDao().getPage(10, 0).single()
        // `normalizePaymentEvidenceSourceType` 只认这三个值，且 RN 侧
        // SOURCE_LABELS 按字面量映射中文 —— 越界值会把原始英文串显示给用户。
        // 转账的业务语义由 type="transfer" 承载，不该塞进 sourceType。
        assertTrue(
            "sourceType=${row.sourceType} 不在 Native.d.ts 契约内",
            normalizePaymentEvidenceSourceType(row.sourceType) != null
        )
        assertEquals("accessibility", row.sourceType)
    }

    @Test
    fun personalTransferDuplicateDoesNotEmitTwice() = runBlocking {
        matcher.recordPersonalTransfer(5000L, "com.tencent.mm", "张三", now)
        val seen = mutableListOf<PendingChangeInfo>()
        val listener: (PendingChangeInfo) -> Unit = { seen += it }
        PendingChangeHub.addListener(listener)
        try {
            matcher.recordPersonalTransfer(5000L, "com.tencent.mm", "张三", now)
            assertEquals(1, database.pendingTransactionDao().getPage(10, 0).size)
            // 幂等跳过时不得再发事件，否则弹窗会对同一张卡弹两次
            assertTrue("重复转账发出了多余事件: ${seen.size}", seen.isEmpty())
        } finally {
            PendingChangeHub.removeListener(listener)
        }
    }

    @Test
    fun ambiguousOrphanEmitsAnIdThatActuallyExists() = runBlocking {
        matcher.ensureSession(
            reference = checkoutReference(),
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A)
        )
        val seen = mutableListOf<PendingChangeInfo>()
        val listener: (PendingChangeInfo) -> Unit = { seen += it }
        PendingChangeHub.addListener(listener)
        try {
            matcher.recordOrphanPaymentSignal(
                paymentSignal(),
                sourceType = "ACCESSIBILITY",
                conflictReason = "AMBIGUOUS_SESSION_IDS:$SESSION",
                sessionIds = listOf(SESSION)
            )
            val rows = database.pendingTransactionDao().getPage(10, 0)
            assertEquals(1, rows.size)
            assertEquals(1, seen.size)
            // 核心断言：emit 的 ID 必须在库里真实存在。
            // 修复前这里发的是 stableId("pending-orphan", …)，而插入的是
            // stableId("pending-ambiguous", sessionId, …) —— 弹窗按 ID 回查必然落空。
            assertTrue(
                "emit 的 pendingId=${seen.single().pendingId} 在库中不存在",
                database.pendingTransactionDao().getById(seen.single().pendingId) != null
            )
            assertEquals(rows.single().id, seen.single().pendingId)
        } finally {
            PendingChangeHub.removeListener(listener)
        }
    }

    private fun paymentSignal(
        amountMinor: Long = 1234,
        sourceEventKey: String = "evt-1",
        paymentPackage: String = "com.tencent.mm",
        occurredAt: Long = now,
        rawTextHash: String? = null
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = paymentPackage,
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        sourceEventKey = sourceEventKey,
        rawTextHash = rawTextHash
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
        // ⚠️ 两笔用不同金额：同支付包 + 同金额 + 同文本哈希的信号会被判为同一笔的
        // 重复观测而不再建卡（见下方 `孤儿同哈希重复观测_*` 系列用例）。本用例验的是
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

    // ---------- 孤儿待确认重复观测去重（文本哈希主判据 + 时间窗兜底） ----------

    /**
     * 正例：同一笔支付的结果页被观测两次，事件键跨了时间桶 ⇒
     * `sourceEventKey` 幂等键挡不住 ⇒ 必须靠"同包同额 + 同文本哈希 + 15 秒内"补口。
     *
     * 真机数据（2026-09-29 拼多多 build 0.0.98，¥19.90）：
     * `occurredAt=1790687322157`（桶 59689577）与 `occurredAt=1790687342160`（桶 59689578），
     * 间隔 20 秒。旧实现落了两条待确认（`pending-orphan-2` / `pending-orphan-4`）。
     *
     * ⚠️ 2026-10-24：用户裁定"同额第二笔 15 秒内接受合并，超过即新交易"。
     * 本例间隔改为 **10 秒**（窗口内），仍钉死"同一笔不得建两张卡"；
     * 20 秒的场景由 `孤儿同哈希_超过15秒仍各自建卡` 覆盖（那里断言的是 2 张）。
     */
    @Test
    fun 孤儿同哈希重复观测_间隔10秒不新建第二张卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-a",
                occurredAt = now,
                rawTextHash = "hash-1990-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        // 跨桶：不同 sourceEventKey，但同包同额、同文本哈希、间隔 10 秒 ⇒ 同一笔的重复观测
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-b",
                occurredAt = now + 10_000L,
                rawTextHash = "hash-1990-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )

        val rows = database.pendingTransactionDao().getPage(10, 0)
        assertEquals("同包同额同文本哈希、15 秒内只落一张待确认卡", 1, rows.size)
        assertEquals(
            "重复观测不得重复追加同一码",
            "NO_ACTIVE_SESSION",
            rows.single().conflictReason
        )
    }

    /**
     * **真机场景 · 裁定取舍**（2026-09-30 10:05~10:06，小米 25128PNA1C / 拼多多 build 0.0.98）。
     *
     * 同一笔 ¥2.68 的微信结果页被观测两次，`textLength` 均为 20（同一页面），
     * `occurredAt` 分别为 1790733945898 / 1790734006291，相差 **60393 ms**。
     * 当时 `ORPHAN_DEDUP_WINDOW_MILLIS = 60_000` ⇒ **超了 393 毫秒，两条都建了卡**
     * （`pending-orphan-044f32b` / `pending-orphan-bf77dd6`）。
     *
     * ⚠️ **2026-10-24 用户裁定后，本例故意断言 2 张**：页面不显示时间时
     * `occurredAt` 回退观测时刻，60 秒已超出 15 秒窗口 ⇒ 同一笔会被再建一张卡。
     * 这是裁定明确接受的取舍 —— 宁可多一张可删的卡，也不静默吞掉真实的第二笔。
     *
     * 判别性：把窗口改回 90 秒，本例会变成 1 张 ⇒ 必红。
     */
    @Test
    fun 孤儿同哈希_超过15秒仍各自建卡_真机60393毫秒场景() = runBlocking {
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
                occurredAt = firstOccurredAt,
                rawTextHash = "hash-268-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        // 关键：哈希判据的 15 秒上限基于**观测时刻**（`nowMillis()`），不是 `occurredAt`。
        // 测试时钟若一直停在 START_NOW，两次调用的间隔会被算成 0 秒，本例就验不到
        // "超过 15 秒即新交易"。这里让时钟前进到第二次观测时刻。
        now = secondOccurredAt
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 268,
                sourceEventKey = "evt-real-b",
                occurredAt = secondOccurredAt,
                rawTextHash = "hash-268-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        assertEquals(
            "15 秒窗口外，同一页面也会各自建卡（裁定取舍：宁可误报不可误杀）",
            2,
            database.pendingTransactionDao().getPage(10, 0).size
        )
    }

    /**
     * 反例（2026-10-24 用户裁定）：同包同额、间隔 20 秒、**文本哈希不同** ⇒ 两笔真实交易。
     *
     * 这是"15 秒内合并、超过即视为新交易"的核心判别：时间窗已过，且页面内容不同，
     * 不得因为"同额"就静默吞掉第二笔（误杀不可恢复，误报只是多一张可删的卡）。
     */
    @Test
    fun 孤儿不同哈希_间隔20秒视为两笔真实交易() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-order-1",
                occurredAt = now,
                rawTextHash = "hash-1990-order-1"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-order-2",
                occurredAt = now + 20_000L,
                rawTextHash = "hash-1990-order-2"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )

        assertEquals(
            "同额但文本哈希不同、且超过 15 秒 ⇒ 必须建两张卡",
            2,
            database.pendingTransactionDao().getPage(10, 0).size
        )
    }

    /**
     * 反例：前一笔已确认（`removed=true`）⇒ 哈希登记必须解除，
     * 之后同额同文本的真实新支付不得被吞。
     *
     * 这是"内存表"方案最容易漏的一环：只登记不清除，就会把"确认后立刻又买一次同价商品"
     * 永久判成重复观测（误杀方向）。
     */
    @Test
    fun 孤儿同哈希_前一笔确认后再次出现视为新交易() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 990,
                sourceEventKey = "evt-repeat-1",
                occurredAt = now,
                rawTextHash = "hash-990-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        val first = database.pendingTransactionDao().getPage(10, 0).single()

        // 模拟用户在弹窗/待确认中心确认入账：LedgerRepository 删行后 emit removed=true
        PendingChangeHub.emit(
            PendingChangeInfo(
                pendingId = first.id,
                type = first.type,
                amountMinor = first.amountMinor,
                sourceType = first.sourceType,
                conflictReason = null,
                occurredAt = first.occurredAt,
                isNew = false,
                removed = true
            )
        )
        database.pendingTransactionDao().deleteByIdIfVersion(first.id, first.version)

        // 判别性：删掉 RecentResultObservation.init() 时，这里仍会命中残留登记
        // （下面的建卡兜底会在"行已不在"时自愈，掩盖接线缺失）⇒ 必须直接断言。
        assertNull(
            "removed 事件必须立即解除登记，而不是靠建卡时的兜底自愈",
            RecentResultObservation.findSameObservation(
                paymentPackage = "com.tencent.mm",
                amountMinor = 990L,
                rawTextHash = "hash-990-same-page",
                now = now
            )
        )

        // 同额同文本的第二笔真实支付
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 990,
                sourceEventKey = "evt-repeat-2",
                occurredAt = now + 20_000L,
                rawTextHash = "hash-990-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )

        assertEquals(
            "前一笔已确认后，同额同文本的新支付必须重新建卡",
            1,
            database.pendingTransactionDao().getPage(10, 0).size
        )
    }

    /** 重复观测**不得丢证据** —— 后到的 conflictReason 必须合并进既有卡。 */
    @Test
    fun 孤儿同哈希重复观测_冲突原因合并进既有卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-a",
                occurredAt = now,
                rawTextHash = "hash-1990-same-page"
            ),
            sourceType = "ACCESSIBILITY",
            conflictReason = "NO_ACTIVE_SESSION"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-bucket-b",
                occurredAt = now + 10_000L,
                rawTextHash = "hash-1990-same-page"
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
     * 负例：**超过窗口**必须各自建卡。
     *
     * ⚠️ 2026-10-24 修订：窗口由 90 秒收窄至 15 秒。原用例用 `now + 150_000L`
     * 仍在窗口外，断言不变（2 张）。改用 `now + 20_000L` 更能体现新窗口的边界：
     * 20 秒已超出 15 秒，且哈希缺失（通知回退场景）⇒ 只能靠时间窗，必须各自建卡。
     *
     * 判别性：若窗口误写成 90 秒，本用例必红。
     */
    @Test
    fun 孤儿去重_超过15秒窗口且无哈希的同额两笔各自建卡() = runBlocking {
        matcher.recordOrphanPaymentSignal(
            paymentSignal(amountMinor = 1990, sourceEventKey = "evt-t0", occurredAt = now),
            sourceType = "ACCESSIBILITY"
        )
        matcher.recordOrphanPaymentSignal(
            paymentSignal(
                amountMinor = 1990,
                sourceEventKey = "evt-t20",
                occurredAt = now + 20_000L
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

}
