package com.jotbill.app.recognition

import android.util.Log

/**
 * 协调器视角的缓冲区最小视图。
 *
 * 抽象只为 JVM 单测服务:routeLocked 的门禁序列测试需要注入受控的消费结果,
 * 而 CheckoutSnapshotBuffer 因默认 Android 调度器无法在 JVM 直接实例化。
 * 生产路径唯一实现是 CheckoutSnapshotBuffer;接口保持 public 以便
 * public 类实现时不产生可见性冲突。
 */
interface CheckoutBufferGateway {
    fun consumeForAi(
        slotId: String,
        intent: PurchaseIntent,
        pageFingerprint: String? = null
    ): CheckoutAiTriggerResult

    fun currentCandidates(slotId: String): List<CheckoutSnapshot>
}

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
    private val buffer: CheckoutBufferGateway,
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
    private var userRetryContext: SafeCheckoutContext? = null
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
                        // 同购物包内的非结算中间页（拼多多提交订单后的收银台）不清空
                        // 购买上下文：保留 activeContext 等待切支付应用时由
                        // PAYMENT_APP_HANDOFF 消费快照并预写 AI 会话。
                        val isPaymentInterstitial =
                            event.detail.reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE &&
                                activeContext != null &&
                                event.detail.shoppingPackage == activeContext?.shoppingPackage
                        if (isPaymentHandoff) {
                            val retained = activeContext ?: handoffContext
                            activeContext = null
                            handoffContext = retained
                            shouldTriggerHandoff = retained != null
                            // 结算页指纹高频漂移（倒计时/控件刷新）时，稳定协调器转发的
                            // 指纹可能是漂移中间态；消费必须使用挂起上下文对应的原始
                            // 指纹，否则会撞上支付交接栅栏的 NO_MATCHING_PAGE_FINGERPRINT。
                            handoffFingerprint = retained?.pageFingerprint
                                ?: event.detail.pageFingerprint?.takeIf { it.isNotBlank() }
                        } else if (!isPaymentInterstitial) {
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
                paymentNotification = paymentNotification,
                explicitUserRetryContext = null
            )
        }
        val consumer = synchronized(this) { callback ?: resultConsumer }
        return deliver(routed, consumer)
    }

    /** 仅由手动截图成功回调调用；普通页面不能通过公开 USER_RETRY 入口绕过此上下文门禁。 */
    fun triggerUserRetry(
        context: UserRetryCaptureContext,
        callback: PurchaseIntentResultCallback? = null
    ): PurchaseIntentCoordinationResult {
        val routed = synchronized(this) {
            val safeContext = context.toSafeCheckoutContext()
            if (
                !context.isComplete() ||
                context.pageRole == CheckoutPageRole.CHECKOUT ||
                safeContext == null ||
                userRetryContext != safeContext
            ) {
                return@synchronized PurchaseIntentCoordinationResult.Rejected(
                    intent = PurchaseIntent.USER_RETRY,
                    gateReason = PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
                    slotId = safeContext?.slotId,
                    pageFingerprint = context.pageFingerprint
                )
            }
            routeLocked(
                intent = PurchaseIntent.USER_RETRY,
                requestedFingerprint = context.pageFingerprint,
                paymentNotification = null,
                explicitUserRetryContext = safeContext
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

    /** 更新服务最近一次确认的非敏感购物页上下文；不完整上下文只用于禁止重试。 */
    fun setUserRetryContext(context: UserRetryCaptureContext?) {
        synchronized(this) {
            if (closed) {
                return
            }
            userRetryContext = if (
                context != null &&
                context.isComplete() &&
                !context.sessionId.isNullOrBlank() &&
                context.pageRole.canBufferUserRetrySnapshot()
            ) {
                context.toSafeCheckoutContext()
            } else {
                null
            }
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
        userRetryContext = null
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
        userRetryContext = null
        attemptsBySession.clear()
        handoffAttempted = false
        notificationFallbackAttempted = false
    }

    private fun routeLocked(
        intent: PurchaseIntent,
        requestedFingerprint: String?,
        paymentNotification: PaymentNotificationFallbackSignal?,
        explicitUserRetryContext: SafeCheckoutContext?
    ): PurchaseIntentCoordinationResult {
        if (closed) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.COORDINATOR_CLOSED
            )
        }

        val context = explicitUserRetryContext ?: contextFor(intent)
            ?: return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT
            )
        val isInternalNonCheckoutRetry =
            intent == PurchaseIntent.USER_RETRY && explicitUserRetryContext != null
        if (context.pageRole != CheckoutPageRole.CHECKOUT && !isInternalNonCheckoutRetry) {
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
            isInternalNonCheckoutRetry &&
            (userRetryContext != context ||
                context.pageRole == CheckoutPageRole.CHECKOUT ||
                requestedFingerprint != context.pageFingerprint)
        ) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT,
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
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

        val consumeResult = try {
            // 显式传入 slotId；无指纹时交给 buffer 返回歧义，禁止协调器猜测候选。
            // 支付交接时结算页指纹高频漂移（倒计时/控件刷新），意图上下文可能领先于
            // 槽内实际快照；此时用槽内唯一候选的真实指纹消费，避免快照白白过期。
            val effectiveFingerprint = if (
                intent == PurchaseIntent.PAYMENT_APP_HANDOFF &&
                requestedFingerprint != null
            ) {
                resolveHandoffFingerprint(context.slotId, requestedFingerprint)
            } else {
                requestedFingerprint
            }
            buffer.consumeForAi(
                slotId = context.slotId,
                intent = intent,
                pageFingerprint = effectiveFingerprint
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

        // 交接标记只在快照真正被消费（Ready/AlreadyTriggered）后置位：
        // 指纹漂移等原因导致的 Rejected 不消耗重试机会，补发路径仍可再次消费。
        if (
            intent == PurchaseIntent.PAYMENT_APP_HANDOFF &&
            consumeResult !is CheckoutAiTriggerResult.Rejected
        ) {
            handoffAttempted = true
        }
        if (
            intent == PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK &&
            consumeResult !is CheckoutAiTriggerResult.Rejected
        ) {
            notificationFallbackAttempted = true
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
                if (isInternalNonCheckoutRetry) {
                    userRetryContext = null
                }
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
     * 支付交接时的指纹校正：指纹漂移会让意图上下文领先于槽内实际快照。
     *
     * 槽内存在唯一未过期候选且指纹与意图指纹不一致时，返回候选的真实指纹；
     * 该指纹仍指向槽内真实快照，不构成对页面指纹校验的弱化。其余情况保持
     * 原指纹，由 buffer 的栅栏与指纹匹配逻辑做最终裁决。
     */
    private fun resolveHandoffFingerprint(slotId: String, requestedFingerprint: String): String {
        val candidates = try {
            buffer.currentCandidates(slotId)
        } catch (_: RuntimeException) {
            return requestedFingerprint
        }
        return try {
            val selected = candidates.singleOrNull()
            if (
                selected != null &&
                selected.pageFingerprint != requestedFingerprint
            ) {
                Log.i(
                    TAG,
                    "支付交接指纹校正: intent=${requestedFingerprint.take(12)}, " +
                        "slotCandidate=${selected.pageFingerprint.take(12)}, " +
                        "session=${selected.sessionId.take(8)}"
                )
                selected.pageFingerprint
            } else {
                requestedFingerprint
            }
        } finally {
            candidates.forEach { it.release() }
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
        Log.i(TAG, "购买意图结果: ${describe(result)}")
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
                Log.i(
                    TAG,
                    "购买意图已派发 AI: session=${handoff.sessionId.take(8)}, " +
                        "intent=${handoff.intent.wireValue}, requestKey=${handoff.requestKey.value.take(24)}"
                )
                consumer?.let { notifyConsumer(it, handoff) }
                return handoff
            }
        }

        Log.i(
            TAG,
            "购买意图无 AI 接管，快照已释放: intent=${result.intent.wireValue}, " +
                "reason=${if (dispatcher == null) "DISPATCHER_MISSING" else "DISPATCH_FAILED"}"
        )
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

    private fun describe(result: PurchaseIntentCoordinationResult): String = when (result) {
        is PurchaseIntentCoordinationResult.Accepted -> when (val ai = result.aiResult) {
            is CheckoutAiTriggerResult.Ready ->
                "Accepted(intent=${result.intent.wireValue}, session=${ai.snapshot.sessionId.take(8)}, " +
                    "fingerprint=${ai.snapshot.pageFingerprint.take(12)})"
            is CheckoutAiTriggerResult.AlreadyTriggered ->
                "Accepted(intent=${result.intent.wireValue}, alreadyTriggered=${ai.requestKey.value.take(24)})"
            is CheckoutAiTriggerResult.Rejected ->
                "Accepted?rejected(intent=${result.intent.wireValue}, reason=${ai.reason.wireValue})"
        }
        is PurchaseIntentCoordinationResult.AiDispatched ->
            "AiDispatched(intent=${result.intent.wireValue}, session=${result.sessionId.take(8)})"
        is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer ->
            "ReleasedWithoutConsumer(intent=${result.intent.wireValue}, session=${result.sessionId.take(8)}, " +
                "reason=${result.reasonCode})"
        is PurchaseIntentCoordinationResult.Rejected ->
            "Rejected(intent=${result.intent.wireValue}, reason=${result.reasonCode}, " +
                "slot=${result.slotId.orEmpty()}, fingerprint=${result.pageFingerprint.orEmpty().take(12)})"
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

    private fun UserRetryCaptureContext.toSafeCheckoutContext(): SafeCheckoutContext? {
        if (!isComplete() || sessionId.isNullOrBlank()) {
            return null
        }
        return SafeCheckoutContext(
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = pageFingerprint,
            isSensitivePage = isSensitivePage
        )
    }
}
