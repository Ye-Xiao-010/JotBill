package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * routeLocked 门禁序列特征测试:钉死购买意图进入 AI 调度前的门禁次序与预算语义。
 *
 * 通过注入受控的 [CheckoutBufferGateway] 在 JVM 上驱动协调器,重点保证:
 * - 每个门禁的触发条件与拒绝原因;
 * - 一次性标记(交接/通知回退)只在消费成功后置位,被拒不消耗重试机会;
 * - 每会话请求预算在 Ready 消费时递增,耗尽后拒绝;
 * - buffer 的 RuntimeException 收敛为 BUFFER_UNAVAILABLE,拒绝结果保留原始消费语义。
 */
class CheckoutPurchaseIntentCoordinatorGateTest {

    private companion object {
        const val SLOT = "shopping-package:com.xunmeng.pinduoduo"
        const val PKG = "com.xunmeng.pinduoduo"
        const val SESSION = "session-1"
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
    }

    private class FakeBufferGateway : CheckoutBufferGateway {
        var consumeResult: CheckoutAiTriggerResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        var consumeError: RuntimeException? = null
        var candidates: List<CheckoutSnapshot> = emptyList()
        val consumeCalls = mutableListOf<Triple<String, PurchaseIntent, String?>>()

        var moveResult: PaymentProcessMoveResult =
            PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.NO_SOURCE_SLOT)
        val moveCalls = mutableListOf<Triple<String, String, String?>>()

        override fun consumeForAi(
            slotId: String,
            intent: PurchaseIntent,
            pageFingerprint: String?
        ): CheckoutAiTriggerResult {
            consumeCalls.add(Triple(slotId, intent, pageFingerprint))
            consumeError?.let { throw it }
            return consumeResult
        }

        override fun currentCandidates(slotId: String): List<CheckoutSnapshot> = candidates

        override fun moveToPaymentProcess(
            sourceSlotId: String,
            sessionId: String,
            pageFingerprint: String?
        ): PaymentProcessMoveResult {
            moveCalls.add(Triple(sourceSlotId, sessionId, pageFingerprint))
            return moveResult
        }
    }

    private class MutableClock(var now: Long = 1_000_000L)

    /** 孤儿出口的调用记录（改造 B-3/E-2 判别断言用）。 */
    private data class OrphanRecord(
        val conflictReason: String,
        val sessionId: String?,
        val sessionIds: List<String>,
        val amountMinor: Long
    )

    private class Fixture(private val maxRequests: Int = 2) {
        val clock = MutableClock()
        val gateway = FakeBufferGateway()
        val coordinator = CheckoutPurchaseIntentCoordinator(
            gateway,
            maxRequests,
            nowMillis = { clock.now }
        )
        val dispatched = mutableListOf<CheckoutAiTriggerResult.Ready>()
        val orphanSignals = mutableListOf<OrphanRecord>()

        init {
            coordinator.setReadyDispatcher { ready ->
                dispatched.add(ready)
                true
            }
            coordinator.setPaymentSignalOrphanSink { signal, conflictReason, sessionId, sessionIds ->
                orphanSignals.add(
                    OrphanRecord(conflictReason, sessionId, sessionIds, signal.amountMinor)
                )
            }
        }

        fun ready(
            fingerprint: String = FP_A,
            sessionId: String = SESSION
        ): CheckoutAiTriggerResult.Ready = CheckoutAiTriggerResult.Ready(
            requestKey = CheckoutRequestKey.from(sessionId, PKG, fingerprint),
            intent = PurchaseIntent.PAYMENT_APP_HANDOFF,
            snapshot = snapshot(fingerprint = fingerprint, sessionId = sessionId)
        )

        fun stable(fingerprint: String = FP_A, sessionId: String? = SESSION) {
            coordinator.onEvent(
                CheckoutObservationEvent.Stable(
                    StableCheckoutPage(
                        observation = observation(fingerprint = fingerprint),
                        stableAt = 1L,
                        sessionId = sessionId
                    )
                )
            )
        }

        /**
         * 造一次"交接"：稳定帧建立会话 → 支付交接失效事件触发搬运与映射登记（改造 B-2/E-2）。
         * 金额写入页面观察（finalAmountMinor），作为归属判定的输入。
         */
        fun handoff(
            fingerprint: String = FP_A,
            sessionId: String = SESSION,
            amountMinor: Long? = 12_800L,
            at: Long = clock.now
        ) {
            clock.now = at
            coordinator.onEvent(
                CheckoutObservationEvent.Stable(
                    StableCheckoutPage(
                        observation = observation(
                            fingerprint = fingerprint,
                            amountMinor = amountMinor
                        ),
                        stableAt = at,
                        sessionId = sessionId
                    )
                )
            )
            coordinator.onEvent(
                CheckoutObservationEvent.Invalidated(
                    CheckoutObservationInvalidation(
                        reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
                        shoppingPackage = PKG,
                        pageFingerprint = fingerprint,
                        invalidatedAt = at,
                        foregroundPackage = "com.tencent.mm"
                    )
                )
            )
        }
    }

    private fun paymentSignal(
        pkg: String = "com.tencent.mm"
    ) = PaymentNotificationFallbackSignal(
        paymentPackage = pkg,
        amountMinor = 100,
        occurredAt = 1L,
        sourceEventKey = "evt-1"
    )

    private fun paymentResultSignal(
        amountMinor: Long,
        occurredAt: Long
    ) = PaymentResultAttributionSignal(
        paymentPackage = "com.tencent.mm",
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        sourceEventKey = "evt-$occurredAt-$amountMinor"
    )

    /** 生成互不相同的 64 位十六进制指纹（a~e 覆盖 5 个会话以内）。 */
    private fun fingerprintOf(index: Int): String = ('a' + index).toString().repeat(64)

    // ---------- 上下文与关闭门禁 ----------

    @Test
    fun triggerWithoutStableContextIsNoCheckoutContext() {
        val f = Fixture()
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    @Test
    fun serviceInterruptedClearsContext() {
        val f = Fixture()
        f.stable()
        f.coordinator.onServiceInterrupted()
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
    }

    @Test
    fun closedCoordinatorAlwaysCoordinatorClosed() {
        val f = Fixture()
        f.stable()
        f.coordinator.close()
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.COORDINATOR_CLOSED,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    // ---------- §8.3 会话标识链路 ----------

    /**
     * sessionId 为空的稳定帧（截图协调器有意忽略交接过渡窗内的迟到帧）不得建立上下文。
     *
     * 若此处放行，就会凭空造出没有会话的上下文；改造 B/E 依赖 sessionId 作为槽位键与
     * 映射键，无会话的上下文只能靠"回退猜测"补键——这正是改造 E 要消除的错误归属。
     */
    @Test
    fun stableFrameWithoutSessionDoesNotEstablishContext() {
        val f = Fixture()
        f.stable(sessionId = null)
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    /**
     * sessionId 为空的稳定帧不得清空、也不得覆盖既有上下文。
     *
     * 真机形态：订单1 交接后支付应用在前台，购物包偶发上报一个同包迟到稳定帧，
     * 截图协调器判定"不代表新订单"而保留挂起会话；购买意图协调器必须做同样的决定，
     * 否则订单1 的上下文会在支付过程中被抹掉。
     */
    @Test
    fun stableFrameWithoutSessionKeepsExistingContext() {
        val f = Fixture()
        f.stable()
        f.stable(fingerprint = FP_B, sessionId = null)
        f.gateway.consumeResult = f.ready()
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(
            listOf(Triple(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)),
            f.gateway.consumeCalls
        )
    }

    // ---------- Ready 派发与所有权 ----------

    @Test
    fun readyWithDispatcherBecomesAiDispatchedAndConsumesBudget() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult = f.ready()
        f.gateway.candidates = listOf(snapshot())
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        result as PurchaseIntentCoordinationResult.AiDispatched
        assertEquals(1, f.dispatched.size)
        assertEquals(result.requestKey, f.dispatched.single().requestKey)
        assertEquals(listOf(Triple(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)), f.gateway.consumeCalls)
    }

    @Test
    fun readyWithoutDispatcherIsReleasedAndBytesZeroed() {
        val f = Fixture()
        f.coordinator.setReadyDispatcher(null)
        f.stable()
        val snap = snapshot()
        f.gateway.consumeResult = CheckoutAiTriggerResult.Ready(
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A),
            intent = PurchaseIntent.PAYMENT_APP_HANDOFF,
            snapshot = snap
        )
        f.gateway.candidates = listOf(snapshot())
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer)
        assertEquals(
            "SNAPSHOT_RELEASED_NO_CONSUMER",
            (result as PurchaseIntentCoordinationResult.ReleasedWithoutConsumer).reasonCode
        )
        assertTrue("无消费者时快照必须清零", snap.imageBytes.all { it == 0.toByte() })
    }

    @Test
    fun dispatchFailureReleasesSnapshotWithFailureReason() {
        val f = Fixture()
        f.stable()
        val snap = snapshot()
        f.gateway.consumeResult = CheckoutAiTriggerResult.Ready(
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A),
            intent = PurchaseIntent.PAYMENT_APP_HANDOFF,
            snapshot = snap
        )
        f.gateway.candidates = listOf(snapshot())
        f.coordinator.setReadyDispatcher { false }
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer)
        assertEquals(
            "SNAPSHOT_RELEASED_AI_DISPATCH_FAILED",
            (result as PurchaseIntentCoordinationResult.ReleasedWithoutConsumer).reasonCode
        )
        assertTrue(snap.imageBytes.all { it == 0.toByte() })
    }

    // ---------- 一次性标记:被拒不烧,成功才烧 ----------

    /**
     * 改造 B-2 后交接改为搬运，"交接单次门禁"不复存在：
     * 被拒不烧重试机会，成功也不置位任何标记；显式 PAYMENT_APP_HANDOFF 触发
     * （公共桥入口）可重复到达消费层，只受每会话请求预算约束。
     */
    @Test
    fun handoffTriggerHasNoOneShotGateAnymore() {
        val f = Fixture()
        f.stable()
        // 预算预检读取槽内候选；不给候选则预检跳过，预算约束不生效
        f.gateway.candidates = listOf(snapshot())
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        val rejected = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (rejected as PurchaseIntentCoordinationResult.Rejected).consumeResult?.reason
        )
        // 被拒不烧预算：重试仍能到达消费层
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        // 第二次成功消费（预算 2/2），第三次被预算门禁拒绝 —— 约束只来自预算
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertEquals(
            PurchaseIntentGateRejection.REQUEST_BUDGET_EXHAUSTED,
            (f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertEquals(3, f.gateway.consumeCalls.size)
    }

    @Test
    fun newStableContextServesExplicitHandoffWithNewFingerprint() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        // 新的稳定上下文(指纹漂移出的新指纹)后：显式交接触发按新上下文/新指纹消费
        f.stable(FP_B)
        f.gateway.consumeResult = f.ready(FP_B)
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertEquals(
            listOf(
                Triple(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A),
                Triple(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)
            ),
            f.gateway.consumeCalls
        )
    }

    @Test
    fun notificationRejectionDoesNotBurnOneShotButSuccessDoes() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        val rejected = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
            paymentNotification = paymentSignal()
        )
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (rejected as PurchaseIntentCoordinationResult.Rejected).consumeResult?.reason
        )
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                paymentNotification = paymentSignal()
            ) is PurchaseIntentCoordinationResult.AiDispatched
        )
        val third = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
            paymentNotification = paymentSignal()
        )
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED,
            (third as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
    }

    // ---------- 支付通知信号校验 ----------

    @Test
    fun notificationFallbackRequiresValidWhitelistedSignal() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult = f.ready()
        // 缺信号
        assertEquals(
            PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
            (f.coordinator.trigger(PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK)
                as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        // 非白名单支付包
        assertEquals(
            PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
            (f.coordinator.trigger(
                PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
                paymentNotification = paymentSignal(pkg = "com.unknown.pay")
            ) as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        // 非通知意图携带通知信号同样拒绝
        assertEquals(
            PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
            (f.coordinator.trigger(
                PurchaseIntent.PAYMENT_APP_HANDOFF,
                FP_A,
                paymentNotification = paymentSignal()
            ) as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    // ---------- 预算 ----------

    @Test
    fun budgetExhaustedAfterMaxSuccessfulConsumesPerSession() {
        val f = Fixture(maxRequests = 2)
        f.stable()
        f.gateway.candidates = listOf(snapshot())
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertEquals(
            PurchaseIntentGateRejection.REQUEST_BUDGET_EXHAUSTED,
            (f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertEquals(2, f.gateway.consumeCalls.size)
    }

    // ---------- buffer 异常与拒绝语义 ----------

    @Test
    fun bufferRuntimeExceptionConvergesToBufferUnavailable() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeError = IllegalStateException("slots corrupted")
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
    }

    @Test
    fun bufferRejectionPreservesOriginalConsumeSemantics() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
        val result = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        result as PurchaseIntentCoordinationResult.Rejected
        assertEquals(SnapshotConsumeRejection.SNAPSHOT_EXPIRED, result.consumeResult?.reason)
        assertEquals("SNAPSHOT_EXPIRED", result.reasonCode)
        assertEquals(SLOT, result.slotId)
    }

    // ---------- 支付交接指纹校正 ----------

    @Test
    fun handoffFingerprintCorrectedToSingleCandidateBeforeConsume() {
        val f = Fixture()
        f.stable(FP_A)
        // 槽内唯一候选指纹与意图上下文不一致(指纹漂移):用候选真实指纹消费
        f.gateway.candidates = listOf(snapshot(fingerprint = FP_B))
        f.gateway.consumeResult = f.ready(FP_B)
        f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            listOf(Triple(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)),
            f.gateway.consumeCalls
        )
    }

    // D-11（批 V-4）：原 `nonHandoffIntentKeepsRequestedFingerprintUnchanged` 用例已删除。
    // 它用 SUBMIT_ACTION 表达"除支付交接外的自动意图不做指纹校正"；该意图随 D-11
    // 整链路删除后，自动通道只剩 PAYMENT_RESULT —— 而它走**无指纹消费**（交接指纹由
    // buffer 的槽内选择决定），根本不进入校正分支 ⇒ 该用例失去表达对象。
    // 校正行为由上方 `handoffFingerprintCorrectedToSingleCandidateBeforeConsume` 正向覆盖。

    // ---------- 改造 B-2：交接只搬运，不再派发 AI ----------

    private fun paymentHandoffEvent(
        fingerprint: String = FP_A
    ) = CheckoutObservationEvent.Invalidated(
        CheckoutObservationInvalidation(
            reason = CheckoutInvalidationReason.PAYMENT_APP_HANDOFF,
            shoppingPackage = PKG,
            pageFingerprint = fingerprint,
            invalidatedAt = 1L,
            foregroundPackage = "com.tencent.mm"
        )
    )

    /** 交接失效 ⇒ 搬运（不 consumeForAi、不派 AI），搬运参数 = 挂起会话 + 交接指纹。 */
    @Test
    fun paymentHandoffInvalidationMovesSnapshotInsteadOfDispatchingAi() {
        val f = Fixture()
        f.stable()
        f.gateway.moveResult = PaymentProcessMoveResult.Moved(
            sessionId = SESSION,
            targetSlotId = paymentProcessSlotId(SESSION),
            pageFingerprint = FP_A,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
            expiresAt = 1L
        )
        f.coordinator.onEvent(paymentHandoffEvent())
        assertEquals(listOf(Triple(SLOT, SESSION, FP_A)), f.gateway.moveCalls)
        // 交接不再消费快照、不再派发 AI：AI 由支付成功信号（PAYMENT_RESULT）触发
        assertTrue(f.gateway.consumeCalls.isEmpty())
        assertTrue(f.dispatched.isEmpty())
    }

    @Test
    fun paymentHandoffWithoutContextDoesNotMoveAnything() {
        val f = Fixture()
        f.coordinator.onEvent(paymentHandoffEvent())
        assertTrue(f.gateway.moveCalls.isEmpty())
        assertTrue(f.gateway.consumeCalls.isEmpty())
        assertTrue(f.dispatched.isEmpty())
    }

    /** 搬运失败（如源槽位已空）只记日志：不得退化成消费/派发，兜底在 PAYMENT_RESULT 路径。 */
    @Test
    fun handoffMoveFailureDoesNotFallBackToConsumeOrDispatch() {
        val f = Fixture()
        f.stable()
        f.gateway.moveResult =
            PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.NO_SOURCE_SLOT)
        f.gateway.consumeResult = f.ready()
        f.coordinator.onEvent(paymentHandoffEvent())
        assertEquals(1, f.gateway.moveCalls.size)
        assertTrue(f.gateway.consumeCalls.isEmpty())
        assertTrue(f.dispatched.isEmpty())
    }

    /** 同一会话的重复交接事件必须重复搬运（幂等由 buffer 的一单一图不变式兜住）。 */
    @Test
    fun repeatedHandoffInvalidationMovesAgainForSameSession() {
        val f = Fixture()
        f.stable()
        f.coordinator.onEvent(paymentHandoffEvent())
        f.coordinator.onEvent(paymentHandoffEvent())
        assertEquals(2, f.gateway.moveCalls.size)
        assertTrue(f.gateway.moveCalls.all { it.second == SESSION })
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    // ---------- 改造 E：归属判定三态 + 改造 B-3 取图与兜底 ----------

    /** 恰好 1 命中 ⇒ 用其 sessionId 定位支付过程槽位（无指纹消费）⇒ 派 AI。 */
    @Test
    fun paymentResultWithSingleAmountMatchAttributesThatSession() {
        val f = Fixture()
        f.handoff(fingerprint = FP_A, sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        f.gateway.consumeResult = f.ready(fingerprint = FP_A, sessionId = "session-1")
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_100_000L)
        )
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(1, f.dispatched.size)
        assertEquals(
            listOf(Triple("payment:session-1", PurchaseIntent.PAYMENT_RESULT, null)),
            f.gateway.consumeCalls
        )
        assertTrue(f.orphanSignals.isEmpty())
    }

    /** 0 命中 ⇒ 孤儿待确认（不猜测、不回退最近活跃会话）。 */
    @Test
    fun paymentResultWithNoMatchGoesOrphanWithoutGuessing() {
        val f = Fixture()
        f.handoff(sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 5_500L, occurredAt = 1_000_000L)
        )
        val rejected = result as PurchaseIntentCoordinationResult.Rejected
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_RESULT_NO_MATCHING_SESSION,
            rejected.gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
        assertTrue(f.dispatched.isEmpty())
        assertEquals(1, f.orphanSignals.size)
        assertEquals(PAYMENT_RESULT_NO_MATCHING_SESSION, f.orphanSignals.single().conflictReason)
    }

    /** 页面金额无法判定（null）的会话不参与归属 —— 不猜。 */
    @Test
    fun handoffContextWithoutPageAmountIsNotAttributed() {
        val f = Fixture()
        f.handoff(sessionId = "session-1", amountMinor = null, at = 1_000_000L)
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_000_000L)
        )
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_RESULT_NO_MATCHING_SESSION,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    /**
     * ≥2 命中 ⇒ Ambiguous ⇒ 待确认（安全侧，不误账）。
     * 判别性：若实现退化为"任取一个候选"，consumeCalls 非空 ⇒ 本用例必红。
     */
    @Test
    fun paymentResultWithTwoAmountMatchesIsAmbiguous() {
        val f = Fixture()
        f.handoff(fingerprint = FP_A, sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        f.handoff(fingerprint = FP_B, sessionId = "session-2", amountMinor = 12_800L, at = 1_000_000L)
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_000_000L)
        )
        val rejected = result as PurchaseIntentCoordinationResult.Rejected
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_RESULT_AMBIGUOUS_SESSION,
            rejected.gateReason
        )
        assertEquals(listOf("session-1", "session-2"), rejected.sessionIds)
        assertTrue(f.gateway.consumeCalls.isEmpty())
        assertTrue(f.dispatched.isEmpty())
        assertEquals(1, f.orphanSignals.size)
        assertEquals(
            "AMBIGUOUS_SESSION_IDS:session-1,session-2",
            f.orphanSignals.single().conflictReason
        )
        assertEquals(listOf("session-1", "session-2"), f.orphanSignals.single().sessionIds)
    }

    /**
     * 核心场景（改造 E 要修的 E-1）：订单1 支付中 → 订单2 交接 → **订单1 结果页到达**
     * ⇒ 仍能定位订单1。回退为单槽覆盖（旧实现）⇒ 本用例必红。
     */
    @Test
    fun firstOrderResultPageStillAttributedAfterSecondOrderHandoff() {
        val f = Fixture()
        f.handoff(fingerprint = FP_A, sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        f.handoff(fingerprint = FP_B, sessionId = "session-2", amountMinor = 5_500L, at = 1_050_000L)
        f.gateway.consumeResult = f.ready(fingerprint = FP_A, sessionId = "session-1")
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_200_000L)
        )
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(
            listOf(Triple("payment:session-1", PurchaseIntent.PAYMENT_RESULT, null)),
            f.gateway.consumeCalls
        )
    }

    /** 消费成功后映射移除（"未占用"）：同一会话再次到达的结果页 ⇒ 0 命中 ⇒ 孤儿。 */
    @Test
    fun consumedHandoffContextIsRemovedFromAttribution() {
        val f = Fixture()
        f.handoff(sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        f.gateway.consumeResult = f.ready(fingerprint = FP_A, sessionId = "session-1")
        f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_000_000L)
        )
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_000_000L)
        )
        // 第二次：0 命中（映射已移除）⇒ 孤儿，而不是再次消费/派发
        assertEquals(1, f.gateway.consumeCalls.size)
        assertEquals(1, f.orphanSignals.size)
        assertEquals(PAYMENT_RESULT_NO_MATCHING_SESSION, f.orphanSignals.single().conflictReason)
    }

    /** 容量上限 4（D-24 已定值）：第 5 笔交接淘汰最旧 ⇒ 最旧会话的结果页 0 命中。 */
    @Test
    fun handoffMapEvictsOldestBeyondCapacityOfFour() {
        val f = Fixture()
        listOf(1_100L, 2_200L, 3_300L, 4_400L, 5_500L).forEachIndexed { index, amount ->
            f.handoff(
                fingerprint = fingerprintOf(index),
                sessionId = "session-${index + 1}",
                amountMinor = amount,
                at = 1_000_000L + index * 1_000L
            )
        }
        val eldest = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 1_100L, occurredAt = 1_004_000L)
        )
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_RESULT_NO_MATCHING_SESSION,
            (eldest as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        // 次旧（session-2）仍在 ⇒ 归属命中
        f.gateway.consumeResult = f.ready(fingerprint = fingerprintOf(1), sessionId = "session-2")
        val second = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 2_200L, occurredAt = 1_004_000L)
        )
        assertTrue(second is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(
            listOf(Triple("payment:session-2", PurchaseIntent.PAYMENT_RESULT, null)),
            f.gateway.consumeCalls
        )
    }

    /** 同 sessionId 重新交接 = 覆盖：若错误地"追加"，映射会超限并把本会话挤出。 */
    @Test
    fun repeatedHandoffForSameSessionOverwritesInsteadOfGrowing() {
        val f = Fixture()
        listOf(1_100L, 2_200L, 3_300L, 4_400L).forEachIndexed { index, amount ->
            f.handoff(
                fingerprint = fingerprintOf(index),
                sessionId = "session-${index + 1}",
                amountMinor = amount,
                at = 1_000_000L + index * 1_000L
            )
        }
        f.handoff(
            fingerprint = fingerprintOf(0),
            sessionId = "session-1",
            amountMinor = 1_100L,
            at = 1_005_000L
        )
        f.handoff(
            fingerprint = fingerprintOf(0),
            sessionId = "session-1",
            amountMinor = 1_100L,
            at = 1_006_000L
        )
        f.gateway.consumeResult = f.ready(fingerprint = fingerprintOf(0), sessionId = "session-1")
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 1_100L, occurredAt = 1_006_000L)
        )
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(
            listOf(Triple("payment:session-1", PurchaseIntent.PAYMENT_RESULT, null)),
            f.gateway.consumeCalls
        )
    }

    /**
     * R2 红线：归属命中但支付槽位缺图 ⇒ 不得静默丢弃，必须上报
     * PAYMENT_PROCESS_SNAPSHOT_MISSING（判别性：删掉该兜底分支 ⇒ 本用例必红）。
     */
    @Test
    fun paymentResultWithMissingSnapshotReportsSnapshotMissing() {
        val f = Fixture()
        f.handoff(sessionId = "session-1", amountMinor = 12_800L, at = 1_000_000L)
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        val result = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_RESULT,
            paymentResult = paymentResultSignal(amountMinor = 12_800L, occurredAt = 1_000_000L)
        )
        val rejected = result as PurchaseIntentCoordinationResult.Rejected
        assertEquals(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT, rejected.consumeResult?.reason)
        assertEquals("payment:session-1", rejected.slotId)
        assertTrue(f.dispatched.isEmpty())
        assertEquals(1, f.orphanSignals.size)
        val orphan = f.orphanSignals.single()
        assertEquals(PAYMENT_PROCESS_SNAPSHOT_MISSING, orphan.conflictReason)
        assertEquals("session-1", orphan.sessionId)
        assertEquals(12_800L, orphan.amountMinor)
    }

    /** PAYMENT_RESULT 必须携带归属信号；其他意图不得携带（防误接线）。 */
    @Test
    fun paymentResultRequiresAttributionSignal() {
        val f = Fixture()
        val missing = f.coordinator.trigger(PurchaseIntent.PAYMENT_RESULT)
        assertEquals(
            PurchaseIntentGateRejection.INVALID_PAYMENT_RESULT,
            (missing as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        f.stable()
        f.gateway.consumeResult = f.ready()
        val mismatched = f.coordinator.trigger(
            PurchaseIntent.PAYMENT_APP_HANDOFF,
            FP_A,
            paymentResult = paymentResultSignal(amountMinor = 100L, occurredAt = 1L)
        )
        assertEquals(
            PurchaseIntentGateRejection.INVALID_PAYMENT_RESULT,
            (mismatched as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    // ---------- 用户重试内部通道 ----------

    @Test
    fun userRetryWithMatchingContextConsumesAndClearsRetryContext() {
        val f = Fixture()
        val retryContext = UserRetryCaptureContext(
            observation = observation(
                fingerprint = FP_A,
                role = CheckoutPageRole.UNKNOWN
            ),
            sessionId = SESSION
        )
        f.coordinator.setUserRetryContext(retryContext)
        f.gateway.consumeResult = f.ready()
        val result = f.coordinator.triggerUserRetry(retryContext)
        // Fixture 注册了派发器:路由层 Accepted 在 deliver 阶段转为 AiDispatched
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        assertEquals(
            listOf(Triple(SLOT, PurchaseIntent.USER_RETRY, FP_A)),
            f.gateway.consumeCalls
        )
        // 成功后重试上下文清空:同一上下文二次重试被拒
        val second = f.coordinator.triggerUserRetry(retryContext)
        assertEquals(
            PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
            (second as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
    }

    @Test
    fun userRetryWithoutRegisteredContextIsRejected() {
        val f = Fixture()
        val retryContext = UserRetryCaptureContext(
            observation = observation(role = CheckoutPageRole.UNKNOWN),
            sessionId = SESSION
        )
        val result = f.coordinator.triggerUserRetry(retryContext)
        assertEquals(
            PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
    }
}

private var snapshotCounter = 0

private fun snapshot(
    fingerprint: String = "a".repeat(64),
    sessionId: String = "session-1"
) = CheckoutSnapshot(
    snapshotId = "snap-${++snapshotCounter}",
    sessionId = sessionId,
    slotId = "shopping-package:com.xunmeng.pinduoduo",
    shoppingPackage = "com.xunmeng.pinduoduo",
    pageRole = CheckoutPageRole.CHECKOUT,
    pageFingerprint = fingerprint,
    capturedAt = 1L,
    expiresAt = Long.MAX_VALUE,
    candidateRevision = 1,
    sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
    imageBytes = ByteArray(4) { 1 }
)

private fun observation(
    fingerprint: String = "a".repeat(64),
    role: CheckoutPageRole = CheckoutPageRole.CHECKOUT,
    sensitive: Boolean = false,
    amountMinor: Long? = null
) = CheckoutPageObservation(
    shoppingPackage = "com.xunmeng.pinduoduo",
    pageRole = role,
    pageFingerprint = fingerprint,
    hasFinalAmountSignal = true,
    hasCheckoutActionSignal = true,
    isSensitivePage = sensitive,
    observedAt = 1L,
    finalAmountMinor = amountMinor
)
