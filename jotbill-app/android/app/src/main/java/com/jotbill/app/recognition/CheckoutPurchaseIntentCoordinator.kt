package com.jotbill.app.recognition

import android.util.Log

/** 购买意图在进入 AI 调度前可能遇到的页面、生命周期或预算门禁结果。 */
enum class PurchaseIntentGateRejection(val wireValue: String) {
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED"),
    COORDINATOR_CLOSED("COORDINATOR_CLOSED"),
    NO_CHECKOUT_CONTEXT("NO_CHECKOUT_CONTEXT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    REQUEST_BUDGET_EXHAUSTED("REQUEST_BUDGET_EXHAUSTED"),
    PAYMENT_HANDOFF_ALREADY_ATTEMPTED("PAYMENT_HANDOFF_ALREADY_ATTEMPTED"),
    PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED("PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED"),
    INVALID_PAYMENT_NOTIFICATION("INVALID_PAYMENT_NOTIFICATION"),
    BUFFER_UNAVAILABLE("BUFFER_UNAVAILABLE")
}

/** Ready.snapshot 的所有权状态；协调器不会长期持有 AI 输入副本。 */
enum class PurchaseIntentSnapshotOwnership(val wireValue: String) {
    CALLER_MUST_RELEASE("CALLER_MUST_RELEASE"),
    NOT_OWNED("NOT_OWNED")
}

/** 购买意图协调器对外报告的路由结果，底层消费结果仍复用 CheckoutAiTriggerResult。 */
sealed class PurchaseIntentCoordinationResult {
    abstract val intent: PurchaseIntent

    /** 协调器内部的消费结果；Ready 仅在交接前存在，交接后对外改报 AiDispatched。 */
    data class Accepted(
        override val intent: PurchaseIntent,
        val aiResult: CheckoutAiTriggerResult,
        val snapshotOwnership: PurchaseIntentSnapshotOwnership
    ) : PurchaseIntentCoordinationResult() {
        init {
            require(
                when (aiResult) {
                    is CheckoutAiTriggerResult.Ready ->
                        snapshotOwnership == PurchaseIntentSnapshotOwnership.CALLER_MUST_RELEASE
                    is CheckoutAiTriggerResult.AlreadyTriggered ->
                        snapshotOwnership == PurchaseIntentSnapshotOwnership.NOT_OWNED
                    is CheckoutAiTriggerResult.Rejected -> false
                }
            ) { "Accepted 只能携带 Ready 或 AlreadyTriggered" }
        }
    }

    /** Ready 已交给 AI 客户端；回调只收到元数据，不再拥有图片副本。 */
    data class AiDispatched(
        override val intent: PurchaseIntent,
        val requestKey: CheckoutRequestKey,
        val snapshotId: String,
        val sessionId: String,
        val slotId: String,
        val pageFingerprint: String
    ) : PurchaseIntentCoordinationResult()

    /** 没有 AI 接管器或派发失败时，Ready.snapshot 已被协调器清零并释放。 */
    data class ReleasedWithoutConsumer(
        override val intent: PurchaseIntent,
        val requestKey: CheckoutRequestKey,
        val snapshotId: String,
        val sessionId: String,
        val slotId: String,
        val pageFingerprint: String,
        val reasonCode: String
    ) : PurchaseIntentCoordinationResult()

    /** 页面门禁或 buffer 消费失败；consumeResult 保留原始过期、无快照和歧义语义。 */
    data class Rejected(
        override val intent: PurchaseIntent,
        val gateReason: PurchaseIntentGateRejection? = null,
        val consumeResult: CheckoutAiTriggerResult.Rejected? = null,
        val slotId: String? = null,
        val pageFingerprint: String? = null
    ) : PurchaseIntentCoordinationResult() {
        init {
            require(gateReason != null || consumeResult != null) {
                "Rejected 必须包含门禁原因或 buffer 消费结果"
            }
        }

        val reasonCode: String
            get() = consumeResult?.reason?.wireValue
                ?: gateReason?.wireValue
                ?: "REJECTED"
    }
}

/** 后续 AI 调度器、通知协调器或 RN bridge 可注册的单一结果消费者。 */
fun interface PurchaseIntentResultCallback {
    /** 回调可能收到 AiDispatched 或 ReleasedWithoutConsumer，均不携带图片所有权。 */
    fun onResult(result: PurchaseIntentCoordinationResult)
}

/** 接管 Ready 的内部入口；返回 true 才表示图片所有权已经转移给 AI 客户端。 */
internal fun interface PurchaseIntentReadyDispatcher {
    fun dispatch(ready: CheckoutAiTriggerResult.Ready): Boolean
}

/**
 * 将稳定结算页、支付应用交接和后续显式信号路由到同一个 CheckoutSnapshotBuffer。
 *
 * 本类不执行网络、OCR、通知匹配或记账；consumeForAi() 原子完成快照有效期、未消费、
 * 指纹选择和 checkoutRequestKey 幂等检查，本类只负责触发门禁、请求预算和结果所有权。
 */
internal class CheckoutPurchaseIntentCoordinator(
    private val buffer: CheckoutSnapshotBuffer,
    private val maxRequestsPerSession: Int = DEFAULT_MAX_REQUESTS_PER_SESSION
) : CheckoutObservationListener {
    companion object {
        private const val TAG = "CheckoutPurchaseIntent"
        private const val DEFAULT_MAX_REQUESTS_PER_SESSION = 2
        private const val RELEASED_NO_CONSUMER = "SNAPSHOT_RELEASED_NO_CONSUMER"
        private const val RELEASED_DISPATCH_FAILED = "SNAPSHOT_RELEASED_AI_DISPATCH_FAILED"
    }

    private data class SafeCheckoutContext(
        val slotId: String,
        val shoppingPackage: String,
        val pageRole: CheckoutPageRole,
        val pageFingerprint: String,
        val isSensitivePage: Boolean
    )

    private var activeContext: SafeCheckoutContext? = null
    private var handoffContext: SafeCheckoutContext? = null
    private val attemptsBySession = mutableMapOf<String, Int>()
    private var handoffAttempted = false
    private var notificationFallbackAttempted = false
    private var resultConsumer: PurchaseIntentResultCallback? = null
    private var readyDispatcher: PurchaseIntentReadyDispatcher? = null
    private var closed = false
    private val pageDetector = CheckoutPageDetector()

    init {
        require(maxRequestsPerSession > 0) { "maxRequestsPerSession 必须大于 0" }
    }

    /** 稳定事件只更新上下文；只有显式购买意图或支付交接才会调用 consumeForAi。 */
    override fun onEvent(event: CheckoutObservationEvent) {
        var shouldTriggerHandoff = false
        var handoffFingerprint: String? = null
        synchronized(this) {
            if (!closed) {
                when (event) {
                    is CheckoutObservationEvent.Stable -> updateStableContext(event.page)
                    is CheckoutObservationEvent.Invalidated -> {
                        val isPaymentHandoff =
                            event.detail.reason == CheckoutInvalidationReason.PAYMENT_APP_HANDOFF &&
                                event.detail.foregroundPackage
                                    ?.let(pageDetector::supportsPaymentPackage) == true
                        if (isPaymentHandoff) {
                            val retained = activeContext ?: handoffContext
                            activeContext = null
                            handoffContext = retained
                            shouldTriggerHandoff = retained != null
                            handoffFingerprint = event.detail.pageFingerprint
                                ?.takeIf { it.isNotBlank() }
                                ?: retained?.pageFingerprint
                        } else {
                            clearContextsLocked()
                        }
                    }
                }
            }
        }

        if (shouldTriggerHandoff) {
            // 只有白名单支付包名交接才触发购买意图；具体快照有效性仍由统一 buffer 原子判断。
            trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, handoffFingerprint)
        }
    }

    /** 为提交、支付页、通知回退和用户重试提供统一同步入口。 */
    fun trigger(
        intent: PurchaseIntent,
        pageFingerprint: String? = null,
        callback: PurchaseIntentResultCallback? = null,
        paymentNotification: PaymentNotificationFallbackSignal? = null
    ): PurchaseIntentCoordinationResult {
        val routed = synchronized(this) {
            routeLocked(
                intent = intent,
                requestedFingerprint = pageFingerprint?.takeIf { it.isNotBlank() },
                paymentNotification = paymentNotification
            )
        }
        val consumer = synchronized(this) { callback ?: resultConsumer }
        return deliver(routed, consumer)
    }

    /** 设置唯一结果消费者；新的消费者替换旧引用，服务销毁时会清除。 */
    fun setResultConsumer(consumer: PurchaseIntentResultCallback?) {
        synchronized(this) {
            resultConsumer = consumer
        }
    }

    /** 设置 Ready 的唯一接管者；服务未能接入 AI 时由协调器负责释放图片。 */
    fun setReadyDispatcher(dispatcher: PurchaseIntentReadyDispatcher?) {
        synchronized(this) {
            readyDispatcher = dispatcher
        }
    }

    /** 服务中断只清理协调器上下文；截图协调器负责清理共享 buffer。 */
    fun onServiceInterrupted() {
        synchronized(this) {
            if (!closed) {
                clearContextsLocked()
            }
        }
    }

    /** 服务销毁时清理观察上下文、预算和回调引用。 */
    fun close() {
        synchronized(this) {
            if (!closed) {
                clearContextsLocked()
                resultConsumer = null
                readyDispatcher = null
                closed = true
            }
        }
    }

    private fun updateStableContext(page: StableCheckoutPage) {
        val observation = page.observation
        if (pageDetector.detect(observation) !is CheckoutPageDetection.Accepted) {
            clearContextsLocked()
            return
        }

        val next = SafeCheckoutContext(
            slotId = checkoutSnapshotSlotId(observation.shoppingPackage),
            shoppingPackage = observation.shoppingPackage,
            pageRole = observation.pageRole,
            pageFingerprint = observation.pageFingerprint,
            isSensitivePage = observation.isSensitivePage
        )
        val isNewContext = activeContext == null || activeContext != next
        if (isNewContext) {
            handoffAttempted = false
            notificationFallbackAttempted = false
        }
        activeContext = next
        handoffContext = null
    }

    private fun contextFor(intent: PurchaseIntent): SafeCheckoutContext? = when (intent) {
        PurchaseIntent.SUBMIT_ACTION,
        PurchaseIntent.PAYMENT_PAGE_VISIBLE -> activeContext

        PurchaseIntent.PAYMENT_APP_HANDOFF -> handoffContext ?: activeContext

        PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
        PurchaseIntent.USER_RETRY -> activeContext ?: handoffContext
    }

    private fun clearContextsLocked() {
        activeContext = null
        handoffContext = null
        attemptsBySession.clear()
        handoffAttempted = false
        notificationFallbackAttempted = false
    }

    private fun routeLocked(
        intent: PurchaseIntent,
        requestedFingerprint: String?,
        paymentNotification: PaymentNotificationFallbackSignal?
    ): PurchaseIntentCoordinationResult {
        if (closed) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.COORDINATOR_CLOSED
            )
        }

        val context = contextFor(intent)
            ?: return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT
            )
        if (context.pageRole != CheckoutPageRole.CHECKOUT) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.NOT_CHECKOUT_PAGE,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint ?: context.pageFingerprint
            )
        }
        if (context.isSensitivePage) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.SENSITIVE_PAGE,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint ?: context.pageFingerprint
            )
        }
        if (
            (intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK &&
                (paymentNotification == null ||
                    !paymentNotification.isValid() ||
                    !pageDetector.supportsPaymentPackage(paymentNotification.paymentPackage))) ||
            (paymentNotification != null && intent != PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK)
        ) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.INVALID_PAYMENT_NOTIFICATION,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint ?: context.pageFingerprint
            )
        }
        if (intent == PurchaseIntent.PAYMENT_APP_HANDOFF && handoffAttempted) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.PAYMENT_HANDOFF_ALREADY_ATTEMPTED,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
            )
        }
        if (
            intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK &&
            notificationFallbackAttempted
        ) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
            )
        }

        val candidateSessionId = findSelectedCandidateSessionId(
            slotId = context.slotId,
            pageFingerprint = requestedFingerprint
        )
        if (
            candidateSessionId != null &&
            (attemptsBySession[candidateSessionId] ?: 0) >= maxRequestsPerSession
        ) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.REQUEST_BUDGET_EXHAUSTED,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
            )
        }

        if (intent == PurchaseIntent.PAYMENT_APP_HANDOFF) {
            handoffAttempted = true
        }
        if (intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK) {
            notificationFallbackAttempted = true
        }

        val consumeResult = try {
            // 显式传入 slotId；无指纹时交给 buffer 返回歧义，禁止协调器猜测候选。
            buffer.consumeForAi(
                slotId = context.slotId,
                intent = intent,
                pageFingerprint = requestedFingerprint
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "消费结算快照失败", error)
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
            )
        }

        return when (consumeResult) {
            is CheckoutAiTriggerResult.Ready -> {
                val ready = if (intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK) {
                    consumeResult.copy(paymentNotification = paymentNotification)
                } else {
                    consumeResult
                }
                attemptsBySession[ready.snapshot.sessionId] =
                    (attemptsBySession[ready.snapshot.sessionId] ?: 0) + 1
                PurchaseIntentCoordinationResult.Accepted(
                    intent = intent,
                    aiResult = ready,
                    snapshotOwnership = PurchaseIntentSnapshotOwnership.CALLER_MUST_RELEASE
                )
            }

            is CheckoutAiTriggerResult.AlreadyTriggered ->
                PurchaseIntentCoordinationResult.Accepted(
                    intent = intent,
                    aiResult = consumeResult,
                    snapshotOwnership = PurchaseIntentSnapshotOwnership.NOT_OWNED
                )

            is CheckoutAiTriggerResult.Rejected ->
                PurchaseIntentCoordinationResult.Rejected(
                    intent = intent,
                    consumeResult = consumeResult,
                    slotId = context.slotId,
                    pageFingerprint = requestedFingerprint
                )
        }
    }

    /**
     * 只为预算预检读取副本并立即释放；真正的有效期、未消费和歧义判定仍在 consumeForAi 中完成。
     * 预检找不到候选时不能提前返回，否则会丢失 SNAPSHOT_EXPIRED 等原始结果语义。
     */
    private fun findSelectedCandidateSessionId(
        slotId: String,
        pageFingerprint: String?
    ): String? {
        val candidates = try {
            buffer.currentCandidates(slotId)
        } catch (error: RuntimeException) {
            Log.w(TAG, "预检结算快照失败", error)
            return null
        }
        return try {
            val selected = if (pageFingerprint == null) {
                candidates.singleOrNull()
            } else {
                candidates.firstOrNull { it.pageFingerprint == pageFingerprint }
            }
            selected?.sessionId
        } finally {
            candidates.forEach { it.release() }
        }
    }

    private fun deliver(
        result: PurchaseIntentCoordinationResult,
        consumer: PurchaseIntentResultCallback?
    ): PurchaseIntentCoordinationResult {
        if (
            result !is PurchaseIntentCoordinationResult.Accepted ||
            result.aiResult !is CheckoutAiTriggerResult.Ready
        ) {
            consumer?.let { notifyConsumer(it, result) }
            return result
        }

        val ready = result.aiResult as CheckoutAiTriggerResult.Ready
        val dispatcher = synchronized(this) { readyDispatcher }
        if (dispatcher != null) {
            val dispatched = try {
                dispatcher.dispatch(ready)
            } catch (error: RuntimeException) {
                Log.w(TAG, "AI Ready 派发失败", error)
                false
            }
            if (dispatched) {
                val handoff = PurchaseIntentCoordinationResult.AiDispatched(
                    intent = result.intent,
                    requestKey = ready.requestKey,
                    snapshotId = ready.snapshot.snapshotId,
                    sessionId = ready.snapshot.sessionId,
                    slotId = ready.snapshot.slotId,
                    pageFingerprint = ready.snapshot.pageFingerprint
                )
                consumer?.let { notifyConsumer(it, handoff) }
                return handoff
            }
        }

        ready.snapshot.release()
        val released = releasedWithoutConsumer(
            intent = result.intent,
            ready = ready,
            reasonCode = if (dispatcher == null) {
                RELEASED_NO_CONSUMER
            } else {
                RELEASED_DISPATCH_FAILED
            }
        )
        consumer?.let { notifyConsumer(it, released) }
        return released
    }

    private fun notifyConsumer(
        consumer: PurchaseIntentResultCallback,
        result: PurchaseIntentCoordinationResult
    ) {
        try {
            consumer.onResult(result)
        } catch (error: RuntimeException) {
            Log.w(TAG, "购买意图结果消费者处理失败", error)
        }
    }

    private fun releasedWithoutConsumer(
        intent: PurchaseIntent,
        ready: CheckoutAiTriggerResult.Ready,
        reasonCode: String = RELEASED_NO_CONSUMER
    ): PurchaseIntentCoordinationResult.ReleasedWithoutConsumer =
        PurchaseIntentCoordinationResult.ReleasedWithoutConsumer(
            intent = intent,
            requestKey = ready.requestKey,
            snapshotId = ready.snapshot.snapshotId,
            sessionId = ready.snapshot.sessionId,
            slotId = ready.snapshot.slotId,
            pageFingerprint = ready.snapshot.pageFingerprint,
            reasonCode = reasonCode
        )
}
