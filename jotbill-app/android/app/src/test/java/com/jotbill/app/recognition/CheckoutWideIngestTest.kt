package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 V-4「改造 A-2 宽进」与「改造 A-3 派发信号」的判别性回归。
 *
 * ## 为什么需要这个文件（本批最重要的规格落差）
 *
 * `CheckoutPageDetector.detect()` 的判据与 `AccessibilityPageSummaryCollector.classifyPageRole()`
 * 的 CHECKOUT 判据**同源** —— 两者消费的是同一组布尔（`hasFinalAmountSignal` /
 * `hasCheckoutActionSignal` / `hasCheckoutClassSemantic`），由采集器一次性算出。
 * 因此**真机上不存在**"角色为 PRODUCT_DETAIL / CART 且能通过结算页金额/动作判据"的帧。
 *
 * 该结论有两个直接后果，本文件分别用**人造帧**（角色与信号不一致）钉死：
 * 1. 只改 `canBufferSnapshot()` 而不改 `detect()` ⇒ 宽进完全不生效（两处都必须改）；
 * 2. 只放宽"页面角色门禁"而不放宽"金额/动作判据" ⇒ 宽进同样不生效（批 V-4b 的范围）。
 *
 * ⚠️ 本文件**不**声称真机宽进已生效：V-4a 只完成角色门禁的放宽，判据按角色分支属 V-4b。
 */
class CheckoutWideIngestTest {

    private companion object {
        const val SLOT = "shopping-package:com.taobao.taobao"
        const val SESSION = "session-wide"

        /** 白名单购物包：`detect()` 的包名门禁在角色门禁之前，必须用真实白名单值。 */
        const val SHOPPING_PACKAGE = "com.taobao.taobao"
        val FP = "a".repeat(64)
    }

    // ---------- 集合契约：单一出处与两通道分离 ----------

    /**
     * 可截/可派发角色恰为三类。
     *
     * 该断言是"单一出处"的守门人：任何人在某处写死另一套列表，都会先在这里暴露。
     */
    @Test
    fun 可截角色集合恰为三类() {
        assertEquals(
            setOf(
                CheckoutPageRole.PRODUCT_DETAIL,
                CheckoutPageRole.CART,
                CheckoutPageRole.CHECKOUT
            ),
            AUTO_DISPATCHABLE_PAGE_ROLES
        )
    }

    /**
     * 自动门禁**不得**含首页 / 未知页 / 支付交接页 / 结果页。
     *
     * 规格红线："放宽仅到可截三类，不放开 UNKNOWN/BROWSING"。
     */
    @Test
    fun 自动门禁不得含首页与未知页() {
        assertFalse("UNKNOWN 不得进入自动门禁", CheckoutPageRole.UNKNOWN.canBufferSnapshot())
        assertFalse("SHOPPING_HOME 不得进入自动门禁", CheckoutPageRole.SHOPPING_HOME.canBufferSnapshot())
        assertFalse(CheckoutPageRole.PAYMENT_HANDOFF.canBufferSnapshot())
        assertFalse(CheckoutPageRole.PAYMENT_RESULT.canBufferSnapshot())
    }

    /**
     * 自动门禁与"手动断言可截集合"是**两个集合**，不得合并。
     *
     * 断言通道（用户显式点击，绕过页面分类）允许首页与未知页 —— 这是刻意设计；
     * 把两者合并会同时破坏两条语义（自动放开 UNKNOWN + 断言失去宽面）。
     */
    @Test
    fun 自动门禁与手动断言门禁是两个集合() {
        assertTrue(CheckoutPageRole.UNKNOWN.canBufferUserRetrySnapshot())
        assertTrue(CheckoutPageRole.SHOPPING_HOME.canBufferUserRetrySnapshot())
        assertFalse(CheckoutPageRole.UNKNOWN.canBufferSnapshot())
        assertFalse(CheckoutPageRole.SHOPPING_HOME.canBufferSnapshot())
    }

    // ---------- 宽进落点 ①：Buffer.store 的 targetAllowed ----------

    /**
     * 判别轴：回退 `canBufferSnapshot()` 为 `this == CHECKOUT` ⇒ 本用例红。
     */
    @Test
    fun 自动截图_详情页与购物车页角色可入槽() {
        for (role in listOf(CheckoutPageRole.PRODUCT_DETAIL, CheckoutPageRole.CART)) {
            val fixture = BufferFixture()
            val result = fixture.buffer.store(request(role, SnapshotCaptureTarget.CHECKOUT))
            assertTrue("角色=$role 的自动截图应可入槽，实际=$result", result is SnapshotStoreResult.Stored)
        }
    }

    @Test
    fun 自动截图_首页与未知页角色仍被拒() {
        for (role in listOf(CheckoutPageRole.SHOPPING_HOME, CheckoutPageRole.UNKNOWN)) {
            val fixture = BufferFixture()
            assertEquals(
                "角色=$role 必须仍被拒",
                SnapshotStoreResult.Rejected(SnapshotStoreRejection.NOT_CHECKOUT_PAGE),
                fixture.buffer.store(request(role, SnapshotCaptureTarget.CHECKOUT))
            )
        }
    }

    /** 断言通道的 store 判据是另一分支（`USER_RETRY`），不受宽进影响。 */
    @Test
    fun 断言通道_首页帧仍可入槽() {
        val fixture = BufferFixture()
        val result = fixture.buffer.store(
            request(CheckoutPageRole.SHOPPING_HOME, SnapshotCaptureTarget.USER_RETRY)
        )
        assertTrue("断言通道允许首页帧（绕过页面分类的刻意设计），实际=$result", result is SnapshotStoreResult.Stored)
    }

    // ---------- 宽进落点 ②：CheckoutPageDetector.detect 的角色门禁 ----------

    /**
     * 判别轴：回退 `detect()` 的角色门禁为 `!= CHECKOUT` ⇒ 本用例红。
     *
     * ⚠️ 用例帧是**人造**的（PRODUCT_DETAIL/CART 同时带金额与动作信号）：
     * 真机上该组合会被 `classifyPageRole` 判为 CHECKOUT，故不能用它证明真机放行；
     * 它的作用是钉死"角色门禁必须按集合判定"，并作为 V-4b 判据分支的回归轴。
     */
    @Test
    fun 检测器_三类角色均可通过角色门禁() {
        val detector = CheckoutPageDetector()
        for (role in AUTO_DISPATCHABLE_PAGE_ROLES) {
            val detection = detector.detect(observation(role))
            assertTrue("角色=$role 应被接受，实际=$detection", detection is CheckoutPageDetection.Accepted)
        }
    }

    @Test
    fun 检测器_非三类角色仍被角色门禁拒绝() {
        val detector = CheckoutPageDetector()
        for (role in listOf(
            CheckoutPageRole.SHOPPING_HOME,
            CheckoutPageRole.UNKNOWN,
            CheckoutPageRole.PAYMENT_HANDOFF,
            CheckoutPageRole.PAYMENT_RESULT
        )) {
            assertEquals(
                "角色=$role 必须仍被拒为 NOT_CHECKOUT_PAGE",
                CheckoutPageDetection.Rejected(CheckoutDetectionRejection.NOT_CHECKOUT_PAGE),
                detector.detect(observation(role))
            )
        }
    }

    // ---------- 改造 A-3：证据 → 归属信号 ----------

    @Test
    fun 证据到归属信号_字段无损透传() {
        val signal = buildPaymentResultAttributionSignal(
            paymentPackage = "com.tencent.mm",
            evidence = PaymentResultEvidence(
                amountMinor = 12_800L,
                merchant = "示例商户",
                orderId = "order-1",
                paymentStatus = "SUCCESS",
                occurredAt = 1_700_000_000_000L,
                rawTextHash = "hash-abc",
                sourceEventKey = "evt-1"
            )
        )
        assertEquals("com.tencent.mm", signal.paymentPackage)
        assertEquals(12_800L, signal.amountMinor)
        assertEquals(1_700_000_000_000L, signal.occurredAt)
        assertEquals("示例商户", signal.merchant)
        assertEquals("order-1", signal.orderId)
        assertEquals("evt-1", signal.sourceEventKey)
        assertEquals("hash-abc", signal.rawTextHash)
    }

    /**
     * 证据缺 `occurredAt` 时按注入时钟补全。
     *
     * 归属判定的时间窗依赖该值，若补错（例如恒 0）会让时间窗判定失效却不报错。
     */
    @Test
    fun 证据缺发生时刻时按注入时钟补全() {
        val signal = buildPaymentResultAttributionSignal(
            paymentPackage = "com.tencent.mm",
            evidence = PaymentResultEvidence(
                amountMinor = 1L,
                merchant = null,
                orderId = null,
                paymentStatus = "SUCCESS",
                occurredAt = null,
                rawTextHash = "h",
                sourceEventKey = "e"
            ),
            nowMillis = { 42L }
        )
        assertEquals(42L, signal.occurredAt)
    }

    // ---------- 批 V-4b：判据分档 + 消费侧 / 搬运侧 ----------

    /**
     * 判别轴：把 `detect()` 的金额/动作判据改回"对三类角色一律要求" ⇒ 本用例红。
     *
     * 与上方"人造帧"用例不同，本用例的帧是**真机会出现的**形态：
     * 详情页 / 购物车页本来就拿不到"订单总额 + 结算动作词"，否则
     * `classifyPageRole` 早就判成 CHECKOUT 了。
     */
    @Test
    fun 检测器_宽进角色不再要求结算页证据() {
        val detector = CheckoutPageDetector()
        for (role in listOf(CheckoutPageRole.PRODUCT_DETAIL, CheckoutPageRole.CART)) {
            val detection = detector.detect(
                observation(
                    role,
                    hasFinalAmountSignal = false,
                    hasCheckoutActionSignal = false
                )
            )
            assertTrue(
                "角色=$role 的详情/购物车帧应放行（宽进真生效的前提），实际=$detection",
                detection is CheckoutPageDetection.Accepted
            )
        }
    }

    /** 结算页角色的判据必须保持原样（V-2 P2-8 与 Y3 类名语义修复的回归保护）。 */
    @Test
    fun 检测器_结算页角色的金额与动作门禁保持不变() {
        val detector = CheckoutPageDetector()
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_FINAL_AMOUNT),
            detector.detect(observation(CheckoutPageRole.CHECKOUT, hasFinalAmountSignal = false))
        )
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_CHECKOUT_ACTION),
            detector.detect(
                observation(
                    CheckoutPageRole.CHECKOUT,
                    hasCheckoutActionSignal = false,
                    hasCheckoutClassSemantic = false
                )
            )
        )
    }

    /** 宽进不得越过红线：白名单包 / 敏感页 / 合法指纹对三类角色一律生效。 */
    @Test
    fun 检测器_宽进角色仍受敏感页与指纹红线约束() {
        val detector = CheckoutPageDetector()
        for (role in listOf(CheckoutPageRole.PRODUCT_DETAIL, CheckoutPageRole.CART)) {
            assertEquals(
                "角色=$role 敏感页必须拒",
                CheckoutPageDetection.Rejected(CheckoutDetectionRejection.SENSITIVE_PAGE),
                detector.detect(observation(role, isSensitivePage = true))
            )
            assertEquals(
                "角色=$role 非法指纹必须拒",
                CheckoutPageDetection.Rejected(CheckoutDetectionRejection.INVALID_PAGE_FINGERPRINT),
                detector.detect(observation(role, pageFingerprint = "not-a-fingerprint"))
            )
            assertEquals(
                "角色=$role 非白名单包必须拒",
                CheckoutPageDetection.Rejected(CheckoutDetectionRejection.UNSUPPORTED_SHOPPING_PACKAGE),
                detector.detect(observation(role, shoppingPackage = "com.example.unknown"))
            )
        }
    }

    /**
     * 判别轴：把 `consumeForAi` 的角色守卫改回硬编 `CHECKOUT` ⇒ 本用例红。
     */
    @Test
    fun 消费侧_详情页自动候选可被自动意图消费() {
        val fixture = BufferFixture()
        fixture.buffer.store(
            request(CheckoutPageRole.PRODUCT_DETAIL, SnapshotCaptureTarget.CHECKOUT)
        )
        val result = fixture.buffer.consumeForAi(
            slotId = SLOT,
            intent = PurchaseIntent.PAYMENT_APP_HANDOFF,
            pageFingerprint = FP
        )
        assertTrue(
            "宽进后自动意图应可消费详情页候选，实际=$result",
            result is CheckoutAiTriggerResult.Ready
        )
    }

    /**
     * 守卫放宽**不得**连带放开首页/未知页，且 USER_RETRY 豁免必须原样保留
     * （断言通道是独立旁路）。
     */
    @Test
    fun 消费侧_非三类角色仍被自动意图拒但断言通道可用() {
        val fixture = BufferFixture()
        fixture.buffer.store(
            request(CheckoutPageRole.SHOPPING_HOME, SnapshotCaptureTarget.USER_RETRY)
        )
        val auto = fixture.buffer.consumeForAi(
            slotId = SLOT,
            intent = PurchaseIntent.PAYMENT_APP_HANDOFF,
            pageFingerprint = FP
        )
        assertTrue("首页图不得被自动意图消费，实际=$auto", auto is CheckoutAiTriggerResult.Rejected)

        val asserted = fixture.buffer.consumeForAi(
            slotId = SLOT,
            intent = PurchaseIntent.USER_RETRY,
            pageFingerprint = FP
        )
        assertTrue("断言通道必须仍可消费（不受宽进影响），实际=$asserted", asserted is CheckoutAiTriggerResult.Ready)
    }

    /**
     * 判别轴：把搬运的候选选择判据改回硬编 `CHECKOUT` ⇒ 本用例红
     * （B-1 会搬不到图 ⇒ 支付成功时恒落 `PAYMENT_PROCESS_SNAPSHOT_MISSING`）。
     */
    @Test
    fun 搬运侧_详情页自动候选可搬入支付过程槽位() {
        val fixture = BufferFixture()
        fixture.buffer.store(
            request(CheckoutPageRole.PRODUCT_DETAIL, SnapshotCaptureTarget.CHECKOUT)
        )
        val result = fixture.buffer.moveToPaymentProcess(
            sourceSlotId = SLOT,
            sessionId = SESSION,
            pageFingerprint = FP
        )
        assertTrue("宽进后搬运应能搬走详情页候选，实际=$result", result is PaymentProcessMoveResult.Moved)
    }

    // ---------- 批 V-4 处置：孤儿冲突原因合并 ----------

    @Test
    fun 冲突码合并_既有码在前新码追加() {
        assertEquals(
            "NO_ACTIVE_SESSION,PAYMENT_PROCESS_SNAPSHOT_MISSING",
            mergeOrphanConflictReason(
                existing = "NO_ACTIVE_SESSION",
                incoming = PAYMENT_PROCESS_SNAPSHOT_MISSING
            )
        )
    }

    @Test
    fun 冲突码合并_同码与已含码保持原值() {
        assertEquals(
            "NO_ACTIVE_SESSION",
            mergeOrphanConflictReason("NO_ACTIVE_SESSION", "NO_ACTIVE_SESSION")
        )
        assertEquals(
            "NO_ACTIVE_SESSION,PAYMENT_PROCESS_SNAPSHOT_MISSING",
            mergeOrphanConflictReason(
                "NO_ACTIVE_SESSION,PAYMENT_PROCESS_SNAPSHOT_MISSING",
                PAYMENT_PROCESS_SNAPSHOT_MISSING
            )
        )
    }

    @Test
    fun 冲突码合并_空值两侧与段数上限() {
        assertEquals("A", mergeOrphanConflictReason(null, "A"))
        assertEquals("A", mergeOrphanConflictReason("A", null))
        assertEquals("A", mergeOrphanConflictReason("A", "  "))
        // 段数上限：满 3 段后不再追加，避免异常路径无限增长
        assertEquals(
            "A,B,C",
            mergeOrphanConflictReason("A,B,C", "D")
        )
    }

    /**
     * 合并**必须**保住 D-21 的前缀语义：手动断言的冲突码恒在首位。
     */
    @Test
    fun 冲突码合并_用户断言前缀恒在首位() {
        val merged = mergeOrphanConflictReason(
            "USER_ASSERTED_AMOUNT_MISSING",
            PAYMENT_PROCESS_SNAPSHOT_MISSING
        )
        assertTrue("实际=$merged", merged.startsWith("USER_ASSERTED"))
        assertTrue(
            "手动断言判据必须仍命中",
            merged.startsWith("USER_ASSERTED")
        )
    }

    // ---------- 测试夹具 ----------

    private class MutableClock(var now: Long = 1_000_000L)

    private class NoopScheduler : SnapshotExpirationScheduler {
        override fun postDelayed(runnable: Runnable, delayMillis: Long) = Unit

        override fun cancel(runnable: Runnable) = Unit
    }

    private class SilentLogSink : SnapshotBufferLogSink {
        val lines = mutableListOf<String>()

        override fun i(tag: String, message: String) {
            lines.add("I: $message")
        }

        override fun w(tag: String, message: String) {
            lines.add("W: $message")
        }
    }

    private class BufferFixture {
        val clock = MutableClock()
        val logSink = SilentLogSink()
        val buffer = CheckoutSnapshotBuffer(
            snapshotTtlMillis = 300_000L,
            nowMillis = { clock.now },
            snapshotIdFactory = { "snap-wide-1" },
            expirationScheduler = NoopScheduler(),
            logSink = logSink
        )
    }

    private fun request(
        pageRole: CheckoutPageRole,
        target: SnapshotCaptureTarget
    ) = SnapshotCaptureRequest(
        slotId = SLOT,
        sessionId = SESSION,
        shoppingPackage = SHOPPING_PACKAGE,
        pageRole = pageRole,
        pageFingerprint = FP,
        sourceReason = if (target == SnapshotCaptureTarget.CHECKOUT) {
            SnapshotSourceReason.CHECKOUT_STABLE
        } else {
            SnapshotSourceReason.MANUAL_CAPTURE
        },
        imageBytes = ByteArray(16) { 1 },
        isSensitivePage = false,
        captureTarget = target
    )

    private fun observation(
        pageRole: CheckoutPageRole,
        hasFinalAmountSignal: Boolean = true,
        hasCheckoutActionSignal: Boolean = true,
        hasCheckoutClassSemantic: Boolean = false,
        isSensitivePage: Boolean = false,
        pageFingerprint: String = FP,
        shoppingPackage: String = SHOPPING_PACKAGE
    ) = CheckoutPageObservation(
        shoppingPackage = shoppingPackage,
        pageRole = pageRole,
        pageFingerprint = pageFingerprint,
        hasFinalAmountSignal = hasFinalAmountSignal,
        hasCheckoutActionSignal = hasCheckoutActionSignal,
        isSensitivePage = isSensitivePage,
        hasCheckoutClassSemantic = hasCheckoutClassSemantic
    )
}
