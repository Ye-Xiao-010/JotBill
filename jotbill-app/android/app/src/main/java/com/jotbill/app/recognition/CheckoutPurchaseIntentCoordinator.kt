package com.jotbill.app.recognition

import android.util.Log
import kotlin.math.abs

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

    /**
     * 把结算页槽位中本会话的候选图搬进 `payment:<sessionId>`（改造 B-1）。
     *
     * 所有权转移、不复制字节；候选选择为"指纹优先 + 槽内选择"，目标槽位一单一图。
     * 语义与失败原因见 [CheckoutSnapshotBuffer.moveToPaymentProcess]。
     */
    fun moveToPaymentProcess(
        sourceSlotId: String,
        sessionId: String,
        pageFingerprint: String? = null
    ): PaymentProcessMoveResult
}

/**
 * 结果页证据用于归属判定（改造 E-2）的最小输入，同时也是孤儿落库的信号载体。
 *
 * 字段与 [PaymentNotificationFallbackSignal] 同构（纯结构化元数据，不含页面文本），
 * 不复用该类型是因为：`trigger` 的通知形参受 `INVALID_PAYMENT_NOTIFICATION` 门禁约束
 * （通知信号不得随其他意图传入），归属信号需要自己的形参与校验分支。
 */
internal data class PaymentResultAttributionSignal(
    val paymentPackage: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String? = null,
    val orderId: String? = null,
    val sourceEventKey: String? = null,
    val rawTextHash: String? = null
)

/** 孤儿落库需要一个结构化信号；归属信号与其字段同构，按序转换。 */
internal fun PaymentResultAttributionSignal.toPaymentNotificationFallbackSignal():
    PaymentNotificationFallbackSignal =
    PaymentNotificationFallbackSignal(
        paymentPackage = paymentPackage,
        amountMinor = amountMinor,
        occurredAt = occurredAt,
        merchant = merchant,
        orderId = orderId,
        sourceEventKey = sourceEventKey,
        rawTextHash = rawTextHash
    )

/** 孤儿待确认 conflictReason：归属判定 0 命中（不猜测，改造 E-2）。 */
internal const val PAYMENT_RESULT_NO_MATCHING_SESSION = "PAYMENT_RESULT_NO_MATCHING_SESSION"

/**
 * 结果页证据 → 归属信号（改造 A-3，批 V-4）。
 *
 * 纯映射、无副作用，**供 JVM 单测直接断言字段是否无损透传**（服务层是
 * `AccessibilityService`，接线点本身在 JVM 测不到，故把可测部分抽到这里）。
 * `occurredAt` 缺失时补当前时刻，与 Service 侧证据落库链路的同一纪律一致。
 */
internal fun buildPaymentResultAttributionSignal(
    paymentPackage: String,
    evidence: PaymentResultEvidence,
    nowMillis: () -> Long = { System.currentTimeMillis() }
): PaymentResultAttributionSignal = PaymentResultAttributionSignal(
    paymentPackage = paymentPackage,
    amountMinor = evidence.amountMinor,
    occurredAt = evidence.occurredAt ?: nowMillis(),
    merchant = evidence.merchant,
    orderId = evidence.orderId,
    sourceEventKey = evidence.sourceEventKey,
    rawTextHash = evidence.rawTextHash
)

/**
 * 孤儿待确认 conflictReason：支付过程槽位缺图/过期（改造 B-3 兜底）。
 * 该标记同时是量化"搬运漏检率"的日志锚点，不得改名。
 */
internal const val PAYMENT_PROCESS_SNAPSHOT_MISSING = "PAYMENT_PROCESS_SNAPSHOT_MISSING"

/**
 * 待确认/日志锚点：支付结果页证据**拿不到 AI 定论**（批 V-5，改造 D-2 ②）。
 *
 * 覆盖三种"定论不可得"形态：
 * 1. AI 未配置或派发失败（`readyDispatcher` 缺失 / 抛异常 ⇒ `ReleasedWithoutConsumer`）；
 * 2. 未进入快照消费的分支（预算耗尽、页面/敏感页/上下文门禁拒绝）；
 * 3. 截图兜底路径 —— 它直调视觉客户端 `recognizePaymentResult`，**不经**结算页 AI
 *    定论链路，本就不会有定论（由服务层显式落卡）。
 *
 * "定论会到来"的形态**不属于**本码：`AiDispatched` 与 `Accepted(AlreadyTriggered)`。
 *
 * ⚠️ **也不属于本码**：支付过程槽位缺图（[PAYMENT_PROCESS_SNAPSHOT_MISSING]）、归属
 * 0 命中（[PAYMENT_RESULT_NO_MATCHING_SESSION]）、归属歧义 —— 这三者已在
 * `routeLocked` 内各自调用孤儿出口落卡，本码不得再补一条（同一笔支付会出两张卡）。
 */
internal const val PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE = "PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE"

/**
 * 支付结果无法自动归属时的落库出口（改造 B-3 / E-2 的兜底）。
 *
 * 协调器是纯内存调度器、不触碰数据库；"落待确认"由服务层接入
 * `CheckoutPaymentMatcher.recordOrphanPaymentSignal`。三种情形必须经此出口，
 * 不得静默丢弃：归属 0 命中、归属 ≥2 命中（Ambiguous）、支付过程槽位缺图/过期。
 */
internal fun interface PaymentSignalOrphanSink {
    fun onUnattributed(
        signal: PaymentResultAttributionSignal,
        conflictReason: String,
        sessionId: String?,
        sessionIds: List<String>
    )
}

/** 购买意图在进入 AI 调度前可能遇到的页面、生命周期或预算门禁结果。 */
enum class PurchaseIntentGateRejection(val wireValue: String) {
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED"),
    COORDINATOR_CLOSED("COORDINATOR_CLOSED"),
    NO_CHECKOUT_CONTEXT("NO_CHECKOUT_CONTEXT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    REQUEST_BUDGET_EXHAUSTED("REQUEST_BUDGET_EXHAUSTED"),
    // PAYMENT_HANDOFF_ALREADY_ATTEMPTED 已随改造 B-2 删除：交接改为搬运后不再有单次门禁。
    PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED("PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED"),
    INVALID_PAYMENT_NOTIFICATION("INVALID_PAYMENT_NOTIFICATION"),
    BUFFER_UNAVAILABLE("BUFFER_UNAVAILABLE"),
    /** PAYMENT_RESULT 意图缺归属信号，或非 PAYMENT_RESULT 意图携带了归属信号。 */
    INVALID_PAYMENT_RESULT("INVALID_PAYMENT_RESULT"),
    /** 改造 E-2：归属判定 0 命中 ⇒ 孤儿待确认（不猜测、不回退最近活跃会话）。 */
    PAYMENT_RESULT_NO_MATCHING_SESSION("PAYMENT_RESULT_NO_MATCHING_SESSION"),
    /** 改造 E-2：归属判定 ≥2 命中 ⇒ Ambiguous ⇒ 待确认（安全侧，不误账）。 */
    PAYMENT_RESULT_AMBIGUOUS_SESSION("PAYMENT_RESULT_AMBIGUOUS_SESSION")
}

/**
 * 支付结果页"派发尝试"之后的处置档位（批 V-5，改造 D-2 ②）。
 *
 * 服务层据此决定是否**立即补落待确认**（AI 定论不可得时的第一道安全网）。
 */
internal enum class PaymentResultDispatchOutcome {
    /** 图已交给 AI（本次派发成功，或该 requestKey 此前已派发）⇒ 定论必到 ⇒ 不落待确认。 */
    AI_HANDED_OFF,

    /** 协调器已在内部把该笔转入孤儿待确认（槽位缺图 / 归属 0 命中 / Ambiguous）⇒ 不重复落卡。 */
    COORDINATOR_ALREADY_RECORDED,

    /** 未派发且无人落卡（AI 未配置 / 派发失败 / 预算耗尽 / 门禁拒绝）⇒ 服务层立即落待确认。 */
    NOT_DISPATCHED
}

/**
 * `trigger(PAYMENT_RESULT)` 返回值的处置档位（纯映射，供 JVM 单测直接断言）。
 *
 * ⚠️ **不能用 Boolean 表达**：`Accepted(AlreadyTriggered)` 表示"该 requestKey 此前已派发"
 * ⇒ AI 在飞或已返回；若按"非 `AiDispatched` 即失败"处理会**重现 P5 的过早弹窗**
 * （在 AI 定论到达前就弹待确认）。
 *
 * ⚠️ **`Rejected.consumeResult != null` 与两个归属失败门禁**：`routeLocked` 在这三条
 * 分支内**已同步调用孤儿出口**（`notifyOrphanSinkLocked`）⇒ 归入
 * [PaymentResultDispatchOutcome.COORDINATOR_ALREADY_RECORDED]，服务层不得再补一条。
 */
internal fun PurchaseIntentCoordinationResult.paymentResultDispatchOutcome():
    PaymentResultDispatchOutcome = when (this) {
    is PurchaseIntentCoordinationResult.AiDispatched ->
        PaymentResultDispatchOutcome.AI_HANDED_OFF

    is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer ->
        PaymentResultDispatchOutcome.NOT_DISPATCHED

    // `deliver()` 只在释放 Ready 后才返回 ReleasedWithoutConsumer；一旦返回 Accepted，
    // aiResult 必为 AlreadyTriggered（Ready 已在交接中消费）。
    is PurchaseIntentCoordinationResult.Accepted -> when (aiResult) {
        is CheckoutAiTriggerResult.AlreadyTriggered ->
            PaymentResultDispatchOutcome.AI_HANDED_OFF

        // 不可达：Ready 不会以 Accepted 形态回到调用方。取保守侧（落卡好过静默）。
        is CheckoutAiTriggerResult.Ready -> PaymentResultDispatchOutcome.NOT_DISPATCHED

        is CheckoutAiTriggerResult.Rejected -> PaymentResultDispatchOutcome.NOT_DISPATCHED
    }

    is PurchaseIntentCoordinationResult.Rejected -> when {
        consumeResult != null -> PaymentResultDispatchOutcome.COORDINATOR_ALREADY_RECORDED

        gateReason == PurchaseIntentGateRejection.PAYMENT_RESULT_NO_MATCHING_SESSION ||
            gateReason == PurchaseIntentGateRejection.PAYMENT_RESULT_AMBIGUOUS_SESSION ->
            PaymentResultDispatchOutcome.COORDINATOR_ALREADY_RECORDED

        else -> PaymentResultDispatchOutcome.NOT_DISPATCHED
    }
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
        val pageFingerprint: String? = null,
        /** 归属判定 ≥2 命中时的候选会话（改造 E-2），供孤儿落库与诊断。 */
        val sessionIds: List<String> = emptyList()
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
    private val maxRequestsPerSession: Int = DEFAULT_MAX_REQUESTS_PER_SESSION,
    /** 归属判定的时钟（改造 E-2 的交接时刻）；注入以便 JVM 单测控制时间窗。 */
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : CheckoutObservationListener {
    companion object {
        private const val TAG = "CheckoutPurchaseIntent"
        private const val DEFAULT_MAX_REQUESTS_PER_SESSION = 2
        private const val RELEASED_NO_CONSUMER = "SNAPSHOT_RELEASED_NO_CONSUMER"
        private const val RELEASED_DISPATCH_FAILED = "SNAPSHOT_RELEASED_AI_DISPATCH_FAILED"

        /**
         * 归属判定的时间窗（5 分钟）。
         *
         * 与 `CheckoutPaymentMatcher.PAYMENT_MATCH_WINDOW_MILLIS` 同口径（金额 + 时间窗
         * + 未占用的判定纪律），但分别属于 DB 侧与内存侧、语义载体不同，不强行合并常量。
         */
        private const val PAYMENT_RESULT_MATCH_WINDOW_MILLIS = 5 * 60 * 1000L

        /**
         * `handoffContexts` 容量上限。
         *
         * ⚠️ 已定值 = 4（`DECISIONS.md` D-24 行明示"容量上限 4"），实现不得自行调整；
         * 超出时淘汰最旧（LinkedHashMap 插入序）。
         */
        private const val MAX_PENDING_HANDOFF_CONTEXTS = 4
    }

    private data class SafeCheckoutContext(
        /**
         * 本会话标识（§8.3）。
         *
         * 变换 B（支付过程槽位 `payment:<sessionId>`）与变换 E（`handoffContexts` 的键）
         * 共用**同一个** sessionId，来源唯一 = 截图协调器建立会话时的决策。
         */
        val sessionId: String,
        val slotId: String,
        val shoppingPackage: String,
        val pageRole: CheckoutPageRole,
        val pageFingerprint: String,
        val isSensitivePage: Boolean,
        /**
         * 页面上的订单总额（分），改造 C 新增字段的透传；null = 无法判定。
         * 归属判定（E-2）遇 null **不命中** —— 不猜。
         */
        val finalAmountMinor: Long? = null,
        /**
         * 交接登记时刻（E-2 时间窗起算点）。仅当该上下文进入 [handoffContexts] 时由
         * [registerHandoffLocked] 写入；观察上下文恒为 0（未登记交接）。
         */
        val handoffAtMillis: Long = 0L
    )

    private var activeContext: SafeCheckoutContext? = null

    /**
     * 已交接、等待支付结果的会话上下文（改造 E-2：按 sessionId 键控）。
     *
     * 与改造 B 的支付过程槽位（`payment:<sessionId>`）**同一键空间、同一 sessionId 来源**。
     * 生命周期：
     * - 交接失效事件时登记/覆盖（同 sessionId 重新交接 = 覆盖）；
     * - 上限 [MAX_PENDING_HANDOFF_CONTEXTS]，超出淘汰最旧；
     * - PAYMENT_RESULT 消费成功（含 AlreadyTriggered）后移除（"未占用"语义）；
     * - 普通页面失效**不清空**（否则"订单1 支付中 → 用户返回下订单2"路径上的同包
     *   非结算页事件会把订单1 的归属抹掉，E-1 的修复失效）；时效由 E-2 的时间窗
     *   （[PAYMENT_RESULT_MATCH_WINDOW_MILLIS]）与容量上限共同收敛；
     * - 服务中断/销毁整体清空。
     *
     * ⚠️ 仅在 `synchronized(this)` 内读写（LinkedHashMap 非线程安全）。
     */
    private val handoffContexts = LinkedHashMap<String, SafeCheckoutContext>()

    private var userRetryContext: SafeCheckoutContext? = null
    private val attemptsBySession = mutableMapOf<String, Int>()
    private var notificationFallbackAttempted = false
    private var resultConsumer: PurchaseIntentResultCallback? = null
    private var readyDispatcher: PurchaseIntentReadyDispatcher? = null

    /** 支付结果无法自动归属时的落库出口（改造 B-3/E-2）；由服务层接入，协调器不触碰 DB。 */
    private var orphanSink: PaymentSignalOrphanSink? = null
    private var closed = false
    private val pageDetector = CheckoutPageDetector()

    init {
        require(maxRequestsPerSession > 0) { "maxRequestsPerSession 必须大于 0" }
    }

    /** 稳定事件只更新上下文；支付交接改为"搬运"（改造 B-2），AI 改由支付成功信号触发。 */
    override fun onEvent(event: CheckoutObservationEvent) {
        var handoffSource: SafeCheckoutContext? = null
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
                        // 购买上下文：保留 activeContext 等待切支付应用时把快照搬进
                        // 支付过程槽位（改造 B-2），等待支付成功信号再派 AI。
                        val isPaymentInterstitial =
                            event.detail.reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE &&
                                activeContext != null &&
                                event.detail.shoppingPackage == activeContext?.shoppingPackage
                        if (isPaymentHandoff) {
                            val retained = activeContext
                                ?: handoffContexts.values.lastOrNull()
                            activeContext = null
                            if (retained != null) {
                                handoffSource = retained
                                registerHandoffLocked(retained)
                            }
                            // 结算页指纹高频漂移（倒计时/控件刷新）时，稳定协调器转发的
                            // 指纹可能是漂移中间态；搬运按"指纹优先 + 槽内选择"兜底，
                            // 不会因指纹漂移而漏搬（见 buffer.moveToPaymentProcess 的选择规则）。
                            handoffFingerprint = retained?.pageFingerprint
                                ?: event.detail.pageFingerprint?.takeIf { it.isNotBlank() }
                        } else if (!isPaymentInterstitial) {
                            clearObservationContextsLocked()
                        }
                    }
                }
            }
        }

        // 版本 B（改造 B-2）：交接只**搬运**，不再派发 AI —— AI 由支付成功信号触发。
        // 搬运有意不经过 routeLocked 的门禁：那些门禁（角色/敏感/预算）保护的是"派发 AI"，
        // 搬运本身不联网、不消费快照，被门禁拒掉只会平白丢图（支付成功时无图可送）。
        val retained = handoffSource ?: return
        moveRetainedSnapshotToPaymentProcess(retained, handoffFingerprint)
    }

    /**
     * 交接搬运（改造 B-2）：把挂起会话的结算快照搬进 `payment:<sessionId>`。
     *
     * 失败只记日志，不派 AI、不落待确认 —— "支付过程槽位无图"的兜底在 PAYMENT_RESULT
     * 消费路径（改造 B-3）统一处理：那里的 `PAYMENT_PROCESS_SNAPSHOT_MISSING` 才是量化
     * "搬运漏检率"的单一出口，此处重复落库会造成双份待确认。
     */
    private fun moveRetainedSnapshotToPaymentProcess(
        retained: SafeCheckoutContext,
        pageFingerprint: String?
    ) {
        val result = try {
            buffer.moveToPaymentProcess(
                sourceSlotId = retained.slotId,
                sessionId = retained.sessionId,
                pageFingerprint = pageFingerprint
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "支付交接搬运失败", error)
            return
        }
        val detail = when (result) {
            is PaymentProcessMoveResult.Moved ->
                "Moved(target=${result.targetSlotId}, fp=${result.pageFingerprint.take(12)}, " +
                    "expiresAt=+${PAYMENT_PROCESS_TTL_MILLIS}ms)"
            is PaymentProcessMoveResult.AlreadyOccupied ->
                "AlreadyOccupied(target=${result.targetSlotId})"
            is PaymentProcessMoveResult.Rejected ->
                "Rejected(${result.reason.wireValue})"
        }
        Log.i(
            TAG,
            "支付交接搬运: session=${retained.sessionId.take(8)}, source=${retained.slotId}, " +
                "requested=${pageFingerprint?.take(12) ?: "null"}, result=$detail"
        )
    }

    /**
     * 为提交、支付页、通知回退、用户重试和支付结果提供统一同步入口。
     *
     * 改造 E-2：PAYMENT_RESULT 必须携带 [paymentResult]（证据的金额与发生时刻），
     * 归属判定所需的其他输入（handoffContexts 的页面金额与交接时刻）由协调器自持。
     * 仍不传 sessionId / pageFingerprint（§8.1）：sessionId 由归属判定产出、
     * 消费指纹交给 buffer 的无指纹路径（规避结算页指纹漂移）。
     */
    fun trigger(
        intent: PurchaseIntent,
        pageFingerprint: String? = null,
        callback: PurchaseIntentResultCallback? = null,
        paymentNotification: PaymentNotificationFallbackSignal? = null,
        paymentResult: PaymentResultAttributionSignal? = null
    ): PurchaseIntentCoordinationResult {
        val routed = synchronized(this) {
            routeLocked(
                intent = intent,
                requestedFingerprint = pageFingerprint?.takeIf { it.isNotBlank() },
                paymentNotification = paymentNotification,
                paymentResult = paymentResult,
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
                paymentResult = null,
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

    /**
     * 接入孤儿落库出口（改造 B-3 / E-2）。
     *
     * 服务层把它连到 `recordOrphanPaymentSignal`；未接入时协调器只记日志
     * （落库动作缺失会在日志中显式可见，不构成静默丢弃）。
     */
    fun setPaymentSignalOrphanSink(sink: PaymentSignalOrphanSink?) {
        synchronized(this) {
            orphanSink = sink
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
                clearAllContextsLocked()
            }
        }
    }

    /** 服务销毁时清理观察上下文、预算和回调引用。 */
    fun close() {
        synchronized(this) {
            if (!closed) {
                clearAllContextsLocked()
                resultConsumer = null
                readyDispatcher = null
                orphanSink = null
                closed = true
            }
        }
    }

    private fun updateStableContext(page: StableCheckoutPage) {
        userRetryContext = null
        val observation = page.observation
        if (pageDetector.detect(observation) !is CheckoutPageDetection.Accepted) {
            // 改造 E-2：这里**不得**清空 handoffContexts —— "订单1 支付中 → 用户返回下订单2"
            // 的路径上会先出现若干非结算/被拒帧，清空会让订单1 的归属提前丢失。
            clearObservationContextsLocked()
            return
        }

        // §8.3：只有截图协调器真正建立/沿用了会话的帧才允许登记上下文。
        // sessionId 为空说明该帧被截图协调器有意忽略（交接过渡窗内同包迟到稳定事件）
        // 或协调器已关闭：此时保留既有上下文**不做任何改动**——既不能凭空造会话，
        // 也不能回退到上一帧的会话（错误归属），更不能顺手清空正在等待支付结果的挂起上下文。
        val sessionId = page.sessionId
        if (sessionId.isNullOrBlank()) {
            return
        }

        val next = SafeCheckoutContext(
            sessionId = sessionId,
            slotId = checkoutSnapshotSlotId(observation.shoppingPackage),
            shoppingPackage = observation.shoppingPackage,
            pageRole = observation.pageRole,
            pageFingerprint = observation.pageFingerprint,
            isSensitivePage = observation.isSensitivePage,
            finalAmountMinor = observation.finalAmountMinor
        )
        val isNewContext = activeContext == null || activeContext != next
        if (isNewContext) {
            notificationFallbackAttempted = false
        }
        activeContext = next
        // 改造 E-2：新的稳定帧**不清空** handoffContexts —— 交接过渡窗内同包迟到稳定帧
        // 与"返回结算页后再下新单"都不得抹掉仍在等待支付结果的会话；条目按
        // "消费即移除 + 时间窗 + 容量上限"收敛（见 handoffContexts 的生命周期说明）。
    }

    /**
     * 登记待归属的交接上下文（改造 E-2）。
     *
     * 同 sessionId 重新交接 = 覆盖（返回结算页后再次提交属同一会话）；
     * 超容量淘汰最旧（[MAX_PENDING_HANDOFF_CONTEXTS]，D-24 已定值 4）。
     */
    private fun registerHandoffLocked(context: SafeCheckoutContext) {
        handoffContexts[context.sessionId] = context.copy(handoffAtMillis = nowMillis())
        while (handoffContexts.size > MAX_PENDING_HANDOFF_CONTEXTS) {
            val eldestKey = handoffContexts.keys.first()
            handoffContexts.remove(eldestKey)
            Log.i(
                TAG,
                "交接映射超限淘汰最旧: session=${eldestKey.take(8)}, " +
                    "上限=$MAX_PENDING_HANDOFF_CONTEXTS, 剩余=${handoffContexts.size}"
            )
        }
    }

    private fun contextFor(intent: PurchaseIntent): SafeCheckoutContext? = when (intent) {
        // 改造 E-2：单槽 handoffContext 的映射化版本 —— 取"最近一次交接"。
        PurchaseIntent.PAYMENT_APP_HANDOFF ->
            handoffContexts.values.lastOrNull() ?: activeContext

        PurchaseIntent.PAYMENT_NOTIFICATION_FALLBACK,
        PurchaseIntent.USER_RETRY -> activeContext ?: handoffContexts.values.lastOrNull()

        // PAYMENT_RESULT 不取任何"当前上下文"：归属判定（E-2）产出 sessionId 后
        // 才允许取 handoffContexts[sessionId]，禁止回退 activeContext / 最近活跃会话。
        PurchaseIntent.PAYMENT_RESULT -> null
    }

    /** 普通页面失效 / 稳定帧被拒：清观察上下文与预算，但**保留** [handoffContexts]。 */
    private fun clearObservationContextsLocked() {
        activeContext = null
        userRetryContext = null
        attemptsBySession.clear()
        notificationFallbackAttempted = false
    }

    /** 服务中断 / 销毁：整体清空（支付过程槽位由截图协调器 / buffer 负责清理）。 */
    private fun clearAllContextsLocked() {
        clearObservationContextsLocked()
        handoffContexts.clear()
    }

    private fun routeLocked(
        intent: PurchaseIntent,
        requestedFingerprint: String?,
        paymentNotification: PaymentNotificationFallbackSignal?,
        paymentResult: PaymentResultAttributionSignal?,
        explicitUserRetryContext: SafeCheckoutContext?
    ): PurchaseIntentCoordinationResult {
        if (closed) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.COORDINATOR_CLOSED
            )
        }

        // 归属信号形参校验（与 INVALID_PAYMENT_NOTIFICATION 同构）：
        // PAYMENT_RESULT 必须带证据；其他意图不得携带（防止误接线绕过门禁）。
        if (
            (intent == PurchaseIntent.PAYMENT_RESULT && paymentResult == null) ||
            (paymentResult != null && intent != PurchaseIntent.PAYMENT_RESULT)
        ) {
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.INVALID_PAYMENT_RESULT
            )
        }

        val context: SafeCheckoutContext = if (intent == PurchaseIntent.PAYMENT_RESULT) {
            // 改造 E-2：归属判定（金额 + 时间窗）产出 sessionId，不回退猜测。
            val signal = paymentResult!!
            when (val attribution = attributePaymentResultLocked(signal)) {
                is PaymentResultAttribution.Attributed -> attribution.context
                is PaymentResultAttribution.NoCandidate -> {
                    Log.w(
                        TAG,
                        "支付结果归属 0 命中，转孤儿待确认: amount=${signal.amountMinor}, " +
                            "occurredAt=${signal.occurredAt}, pendingHandoffs=${handoffContexts.size}"
                    )
                    notifyOrphanSinkLocked(
                        signal = signal,
                        conflictReason = PAYMENT_RESULT_NO_MATCHING_SESSION,
                        sessionId = null,
                        sessionIds = emptyList()
                    )
                    return PurchaseIntentCoordinationResult.Rejected(
                        intent = intent,
                        gateReason = PurchaseIntentGateRejection.PAYMENT_RESULT_NO_MATCHING_SESSION
                    )
                }
                is PaymentResultAttribution.Ambiguous -> {
                    Log.w(
                        TAG,
                        "支付结果归属歧义(${attribution.sessionIds.size}个)，转待确认: " +
                            "amount=${signal.amountMinor}, sessions=" +
                            attribution.sessionIds.joinToString(",") { it.take(8) }
                    )
                    notifyOrphanSinkLocked(
                        signal = signal,
                        conflictReason = "AMBIGUOUS_SESSION_IDS:" +
                            attribution.sessionIds.joinToString(","),
                        sessionId = null,
                        sessionIds = attribution.sessionIds
                    )
                    return PurchaseIntentCoordinationResult.Rejected(
                        intent = intent,
                        gateReason =
                            PurchaseIntentGateRejection.PAYMENT_RESULT_AMBIGUOUS_SESSION,
                        sessionIds = attribution.sessionIds
                    )
                }
            }
        } else {
            explicitUserRetryContext ?: contextFor(intent)
                ?: return PurchaseIntentCoordinationResult.Rejected(
                    intent = intent,
                    gateReason = PurchaseIntentGateRejection.NO_CHECKOUT_CONTEXT
                )
        }
        val isInternalNonCheckoutRetry =
            intent == PurchaseIntent.USER_RETRY && explicitUserRetryContext != null
        // 改造 A-2（批 V-4）：门禁 2 改用"可派发角色集合"（单一出处 CheckoutContract，
        // 与 canBufferSnapshot() / CheckoutPageDetector.detect() 同一常量）。
        // 宽进后交接上下文的角色可能是 PRODUCT_DETAIL / CART，用 `!= CHECKOUT` 会把
        // 它们误拒为 NOT_CHECKOUT_PAGE。
        // ⚠️ 只作用于自动通道（coordinator）；断言通道是独立旁路，不经过本函数。
        if (context.pageRole !in AUTO_DISPATCHABLE_PAGE_ROLES && !isInternalNonCheckoutRetry) {
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
        // 改造 B-2：交接已改为 onEvent 内直接搬运，不再有"派发型"交接触发点，
        // 因此 handoffAttempted 单次门禁及其守卫随之删除（见 IMPL_PLAN §2.2 B-2）。
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
        val candidateSessionId = if (intent == PurchaseIntent.PAYMENT_RESULT) {
            // 支付过程槽位一单一图 ⇒ 预算按归属会话直接预检；无图时由下方兜底兜住，
            // 不提前吞掉 SNAPSHOT_EXPIRED 等原始语义。
            context.sessionId
        } else {
            findSelectedCandidateSessionId(
                slotId = context.slotId,
                pageFingerprint = requestedFingerprint
            )
        }
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

        // 改造 B-3：PAYMENT_RESULT 从支付过程槽位取图（`payment:<sessionId>`）。
        val consumeSlotId = if (intent == PurchaseIntent.PAYMENT_RESULT) {
            paymentProcessSlotId(context.sessionId)
        } else {
            context.slotId
        }
        val consumeResult = try {
            // 显式传入 slotId；无指纹时交给 buffer 返回歧义，禁止协调器猜测候选。
            // 支付交接时结算页指纹高频漂移（倒计时/控件刷新），意图上下文可能领先于
            // 槽内实际快照；此时用槽内唯一候选的真实指纹消费，避免快照白白过期。
            val effectiveFingerprint = when {
                intent == PurchaseIntent.PAYMENT_APP_HANDOFF &&
                    requestedFingerprint != null ->
                    resolveHandoffFingerprint(context.slotId, requestedFingerprint)

                else -> requestedFingerprint
            }
            buffer.consumeForAi(
                slotId = consumeSlotId,
                intent = intent,
                pageFingerprint = effectiveFingerprint
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "消费结算快照失败", error)
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                gateReason = PurchaseIntentGateRejection.BUFFER_UNAVAILABLE,
                slotId = consumeSlotId,
                pageFingerprint = requestedFingerprint
            )
        }

        if (
            intent == PurchaseIntent.PAYMENT_RESULT &&
            consumeResult is CheckoutAiTriggerResult.Rejected
        ) {
            // 改造 B-3 兜底（必须）：支付过程槽位缺图/过期/无匹配 ⇒ **不得静默丢弃**。
            // conflictReason 固定携带 PAYMENT_PROCESS_SNAPSHOT_MISSING（量化"搬运漏检率"
            // 的日志锚点），原始消费原因保留在 consumeResult 中一并上报。
            Log.w(
                TAG,
                "PAYMENT_PROCESS_SNAPSHOT_MISSING: session=${context.sessionId.take(8)}, " +
                    "slot=$consumeSlotId, reason=${consumeResult.reason.wireValue}, " +
                    "amount=${paymentResult?.amountMinor}"
            )
            notifyOrphanSinkLocked(
                signal = paymentResult!!,
                conflictReason = PAYMENT_PROCESS_SNAPSHOT_MISSING,
                sessionId = context.sessionId,
                sessionIds = emptyList()
            )
            return PurchaseIntentCoordinationResult.Rejected(
                intent = intent,
                consumeResult = consumeResult,
                slotId = consumeSlotId,
                pageFingerprint = requestedFingerprint
            )
        }
        if (
            intent == PurchaseIntent.PAYMENT_RESULT &&
            consumeResult !is CheckoutAiTriggerResult.Rejected
        ) {
            // 改造 E-2"未占用"语义：归属已用掉（Ready = 本次派发；AlreadyTriggered =
            // 该会话此前已派发过），从待归属映射移除，避免同一会话被重复归属。
            handoffContexts.remove(context.sessionId)
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

    /** 归属判定（改造 E-2）的三态结果。 */
    private sealed class PaymentResultAttribution {
        data class Attributed(val context: SafeCheckoutContext) : PaymentResultAttribution()

        /** 0 命中：孤儿待确认，不猜测。 */
        object NoCandidate : PaymentResultAttribution()

        /** ≥2 命中：Ambiguous ⇒ 待确认（安全侧，不误账）。 */
        data class Ambiguous(val sessionIds: List<String>) : PaymentResultAttribution()
    }

    /**
     * 归属判定（改造 E-2）：在 [handoffContexts] 中按"页面金额 + 交接时间窗"挑出本笔
     * 支付属于哪个会话。纯内存判定，复用 `Matcher.resolveSessionForPayment` 的纪律
     * （金额 + 时间窗 + 未占用），不调用该函数本身（它需 DB 访问，见 §8.2）。
     *
     * - 金额：**精确相等**。页面金额口径 = 订单总额/实付（改造 C，`finalAmountMinor`），
     *   与支付证据金额同源同币种；不等 ⇒ 不命中。页面金额为 null（"订单金额 + 实付"
     *   并存等歧义，改造 C 裁定 A）⇒ 无法判定 ⇒ 不命中 —— 宁可落待确认，不猜。
     * - 时间窗：|occurredAt - handoffAtMillis| ≤ [PAYMENT_RESULT_MATCH_WINDOW_MILLIS]。
     * - 未占用：派发成功即从映射移除 ⇒ 已消费会话天然不参与（见 routeLocked）。
     *
     * 根本限制（E-3，不因本判定消除）：结果页不携带订单号 ⇒ 同金额两单无法自动区分，
     * 判定为 Ambiguous 落待确认即为本设计的正确行为。
     */
    private fun attributePaymentResultLocked(
        signal: PaymentResultAttributionSignal
    ): PaymentResultAttribution {
        val matched = handoffContexts.values.filter { context ->
            val pageAmount = context.finalAmountMinor ?: return@filter false
            pageAmount == signal.amountMinor &&
                abs(signal.occurredAt - context.handoffAtMillis) <=
                PAYMENT_RESULT_MATCH_WINDOW_MILLIS
        }
        return when {
            matched.size == 1 -> PaymentResultAttribution.Attributed(matched.single())
            matched.isEmpty() -> PaymentResultAttribution.NoCandidate
            else -> PaymentResultAttribution.Ambiguous(matched.map { it.sessionId })
        }
    }

    /** 通知孤儿落库出口；出口未接入时仅记日志（缺失显式可见，不构成静默丢弃）。 */
    private fun notifyOrphanSinkLocked(
        signal: PaymentResultAttributionSignal,
        conflictReason: String,
        sessionId: String?,
        sessionIds: List<String>
    ) {
        val sink = orphanSink
        if (sink == null) {
            Log.w(
                TAG,
                "支付信号待落库但孤儿出口未接入: reason=$conflictReason, " +
                    "amount=${signal.amountMinor}, sessions=" +
                    (sessionIds.joinToString(",") { it.take(8) }.ifBlank { sessionId?.take(8) ?: "none" })
            )
            return
        }
        try {
            sink.onUnattributed(signal, conflictReason, sessionId, sessionIds)
        } catch (error: RuntimeException) {
            Log.w(TAG, "孤儿落库出口处理失败: reason=$conflictReason", error)
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
            // §8.3：手动通道本来就有 sessionId，转换时不得丢弃（旧实现在此把它丢掉，
            // 使 USER_RETRY 上下文无法参与按会话键控的槽位与映射）。
            sessionId = sessionId,
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = pageFingerprint,
            isSensitivePage = isSensitivePage,
            finalAmountMinor = observation.finalAmountMinor
        )
    }
}
