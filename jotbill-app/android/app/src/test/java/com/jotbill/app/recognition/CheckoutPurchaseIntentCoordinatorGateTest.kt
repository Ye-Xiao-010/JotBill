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
    }

    private class Fixture(private val maxRequests: Int = 2) {
        val gateway = FakeBufferGateway()
        val coordinator = CheckoutPurchaseIntentCoordinator(gateway, maxRequests)
        val dispatched = mutableListOf<CheckoutAiTriggerResult.Ready>()

        init {
            coordinator.setReadyDispatcher { ready ->
                dispatched.add(ready)
                true
            }
        }

        fun ready(
            fingerprint: String = FP_A,
            sessionId: String = SESSION
        ): CheckoutAiTriggerResult.Ready = CheckoutAiTriggerResult.Ready(
            requestKey = CheckoutRequestKey.from(sessionId, PKG, fingerprint),
            intent = PurchaseIntent.SUBMIT_ACTION,
            snapshot = snapshot(fingerprint = fingerprint, sessionId = sessionId)
        )

        fun stable(fingerprint: String = FP_A) {
            coordinator.onEvent(
                CheckoutObservationEvent.Stable(
                    StableCheckoutPage(observation(fingerprint = fingerprint), stableAt = 1L)
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

    // ---------- 上下文与关闭门禁 ----------

    @Test
    fun triggerWithoutStableContextIsNoCheckoutContext() {
        val f = Fixture()
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.COORDINATOR_CLOSED,
            (result as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertTrue(f.gateway.consumeCalls.isEmpty())
    }

    // ---------- Ready 派发与所有权 ----------

    @Test
    fun readyWithDispatcherBecomesAiDispatchedAndConsumesBudget() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult = f.ready()
        f.gateway.candidates = listOf(snapshot())
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.AiDispatched)
        result as PurchaseIntentCoordinationResult.AiDispatched
        assertEquals(1, f.dispatched.size)
        assertEquals(result.requestKey, f.dispatched.single().requestKey)
        assertEquals(listOf(Triple(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)), f.gateway.consumeCalls)
    }

    @Test
    fun readyWithoutDispatcherIsReleasedAndBytesZeroed() {
        val f = Fixture()
        f.coordinator.setReadyDispatcher(null)
        f.stable()
        val snap = snapshot()
        f.gateway.consumeResult = CheckoutAiTriggerResult.Ready(
            requestKey = CheckoutRequestKey.from(SESSION, PKG, FP_A),
            intent = PurchaseIntent.SUBMIT_ACTION,
            snapshot = snap
        )
        f.gateway.candidates = listOf(snapshot())
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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
            intent = PurchaseIntent.SUBMIT_ACTION,
            snapshot = snap
        )
        f.gateway.candidates = listOf(snapshot())
        f.coordinator.setReadyDispatcher { false }
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(result is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer)
        assertEquals(
            "SNAPSHOT_RELEASED_AI_DISPATCH_FAILED",
            (result as PurchaseIntentCoordinationResult.ReleasedWithoutConsumer).reasonCode
        )
        assertTrue(snap.imageBytes.all { it == 0.toByte() })
    }

    // ---------- 一次性标记:被拒不烧,成功才烧 ----------

    @Test
    fun handoffRejectionDoesNotBurnOneShotButSuccessDoes() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult =
            CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        val rejected = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (rejected as PurchaseIntentCoordinationResult.Rejected).consumeResult?.reason
        )
        // 被拒不烧标记:修复 buffer 后重试仍能到达消费层
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        // 成功后标记置位:第三次直接门禁拒绝,不再触碰 buffer
        val third = f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertEquals(
            PurchaseIntentGateRejection.PAYMENT_HANDOFF_ALREADY_ATTEMPTED,
            (third as PurchaseIntentCoordinationResult.Rejected).gateReason
        )
        assertEquals(2, f.gateway.consumeCalls.size)
    }

    @Test
    fun newStableContextResetsHandoffOneShot() {
        val f = Fixture()
        f.stable()
        f.gateway.consumeResult = f.ready()
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        // 新的稳定上下文(指纹漂移出的新指纹)重置一次性标记
        f.stable(FP_B)
        f.gateway.consumeResult = f.ready(FP_B)
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)
                is PurchaseIntentCoordinationResult.AiDispatched
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
                PurchaseIntent.SUBMIT_ACTION,
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
            f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertTrue(
            f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
                is PurchaseIntentCoordinationResult.AiDispatched
        )
        assertEquals(
            PurchaseIntentGateRejection.REQUEST_BUDGET_EXHAUSTED,
            (f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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
        val result = f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
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

    @Test
    fun nonHandoffIntentKeepsRequestedFingerprintUnchanged() {
        val f = Fixture()
        f.stable(FP_A)
        f.gateway.candidates = listOf(snapshot(fingerprint = FP_B))
        f.gateway.consumeResult = f.ready(FP_B)
        f.coordinator.trigger(PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertEquals(
            listOf(Triple(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)),
            f.gateway.consumeCalls
        )
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
    sensitive: Boolean = false
) = CheckoutPageObservation(
    shoppingPackage = "com.xunmeng.pinduoduo",
    pageRole = role,
    pageFingerprint = fingerprint,
    hasFinalAmountSignal = true,
    hasCheckoutActionSignal = true,
    isSensitivePage = sensitive,
    observedAt = 1L
)
