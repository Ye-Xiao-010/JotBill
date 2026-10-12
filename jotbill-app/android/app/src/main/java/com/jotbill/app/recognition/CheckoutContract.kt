package com.jotbill.app.recognition

import java.util.Locale
import java.util.UUID

/**
 * 购物页面角色。
 *
 * PAYMENT_HANDOFF 在现有 RN 契约中使用 PAYMENT_APP 存储，这里通过 storageValue
 * 保留兼容值；它只表示已经离开购物页，不允许重新捕获截图。
 */
enum class CheckoutPageRole(val storageValue: String) {
    UNKNOWN("UNKNOWN"),
    SHOPPING_HOME("SHOPPING_HOME"),
    PRODUCT_DETAIL("PRODUCT_DETAIL"),
    CART("CART"),
    CHECKOUT("CHECKOUT"),
    PAYMENT_HANDOFF("PAYMENT_APP"),
    PAYMENT_RESULT("PAYMENT_RESULT");

    /**
     * 自动通道可截图判据（`Buffer.store()` 的 `targetAllowed`）。
     *
     * 改造 A-2（批 V-4）：由"仅结算页"放宽为 [AUTO_DISPATCHABLE_PAGE_ROLES]
     * （商品详情页直达收银台的场景下，用户可能全程不停留在结算页）。
     */
    fun canBufferSnapshot(): Boolean = this in AUTO_DISPATCHABLE_PAGE_ROLES

    /** 用户主动截图允许引用任何非敏感的购物普通页；未知页与结算页由手动截图放宽稳定窗口。 */
    internal fun canBufferUserRetrySnapshot(): Boolean = this in setOf(
        SHOPPING_HOME,
        PRODUCT_DETAIL,
        CART,
        CHECKOUT,
        UNKNOWN
    )

    companion object {
        fun fromStorageValue(value: String?): CheckoutPageRole = when (value) {
            "BROWSING" -> SHOPPING_HOME
            "PAYMENT_HANDOFF" -> PAYMENT_HANDOFF
            "PAYMENT_APP" -> PAYMENT_HANDOFF
            else -> entries.firstOrNull { it.storageValue == value } ?: UNKNOWN
        }
    }
}

/**
 * 自动通道"可截图 / 可派发"的页面角色集合（改造 A-2，批 V-4 定案：`IMPL_PLAN` §8.5）。
 *
 * **单一出处**：本常量是该集合的唯一定义点，下列消费点**只允许写 `in/!in` 判定**：
 * 1. [CheckoutPageRole.canBufferSnapshot]（`Buffer.store()` 的 `targetAllowed`）；
 * 2. `CheckoutPageDetector.detect()` 的页面角色门禁；
 * 3. `CheckoutPurchaseIntentCoordinator.routeLocked` 的门禁 2；
 * 4. 批 V-4b：`CheckoutSnapshotBuffer` 的消费守卫与"槽内选择"判据。
 *
 * ⚠️ **严禁复用 `canBufferUserRetrySnapshot()`**：该集合含 `SHOPPING_HOME` / `UNKNOWN`
 *（G2.7 已证实为死配置集合），按字面复用会把首页与未知页放进自动通道门禁，
 * 与"宽进仅到可截三类、不放开 UNKNOWN/BROWSING"的红线冲突。
 *
 * ⚠️ 该集合**只约束自动通道**；断言通道（用户显式点击）不受页面分类约束，
 * 其判据是 [CheckoutPageRole.canBufferUserRetrySnapshot]，两者是不同分支、不得合并。
 */
internal val AUTO_DISPATCHABLE_PAGE_ROLES: Set<CheckoutPageRole> = setOf(
    CheckoutPageRole.PRODUCT_DETAIL,
    CheckoutPageRole.CART,
    CheckoutPageRole.CHECKOUT
)

/** 会话中允许产生 AI 请求的购买意图信号。普通页面观察不属于此枚举。 */
enum class PurchaseIntent(val wireValue: String) {
    // SUBMIT_ACTION 已随 D-11 删除（批 V-4）：自动通道的 AI 由 PAYMENT_RESULT 触发，
    // "提交订单点击"不再产生任何 AI 请求。
    PAYMENT_APP_HANDOFF("PAYMENT_APP_HANDOFF"),
    /**
     * 自动通道的唯一 AI 触发器（版本 B / 改造 A-1，批 V-3 落地消费侧、
     * 批 V-4 完成服务层结果页接线：证据落库后 trigger → 归属判定 →
     * `payment:<sessionId>` 取图 → 派 AI）。
     */
    PAYMENT_RESULT("PAYMENT_RESULT"),
    PAYMENT_NOTIFICATION_FALLBACK("PAYMENT_NOTIFICATION_FALLBACK"),
    USER_RETRY("USER_RETRY")
}

internal const val MAX_SAFE_MINOR_UNIT = 9_007_199_254_740_991L

/**
 * 通知服务传给识别层的最小结构化支付信号，不包含通知原文、页面文本或图片。
 * 金额统一使用分，避免跨服务传递 Double 时产生精度歧义。
 */
data class PaymentNotificationFallbackSignal(
    val paymentPackage: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String? = null,
    val orderId: String? = null,
    val sourceEventKey: String? = null,
    /** 已脱敏的结果页文本哈希；通知回退没有该字段时保持为空。 */
    val rawTextHash: String? = null
) {
    fun isValid(): Boolean =
        paymentPackage.isNotBlank() &&
            paymentPackage.length <= 255 &&
            amountMinor in 1L..MAX_SAFE_MINOR_UNIT &&
            occurredAt > 0L &&
            isOptionalTextValid(merchant) &&
            isOptionalTextValid(orderId) &&
            isOptionalTextValid(sourceEventKey) &&
            isOptionalTextValid(rawTextHash)

    private fun isOptionalTextValid(value: String?): Boolean =
        value == null || (value.isNotBlank() && value.length <= 256)
}

/**
 * D-3 / G3.1：v1 信号源收敛为「支付结果页」单一来源，支付通知整体推迟到版本更新。
 *
 * 依据：`CONFLICT_REVIEW_SESSION` G3.1「v1 只做支付结果页，通知整体推迟到版本更新；
 * 通知代码全部保留，仅摘除证据作用」，处置方式为**出口开关**——
 * 按已归一化的 sourceType 短路，采集与解析代码全部保留，新版本打开此闸即恢复。
 *
 * ⚠️ 必须比对**归一化后**的值：调用方传的是 `"NOTIFICATION"` 等大写形式。
 * ⚠️ 只约束**通知来源**；`accessibility`（结果页主路径 / 截图兜底）与
 * `manual`（用户断言通道）不受影响。
 *
 * ⚠️ **本闸必须覆盖全部 notification 出口，不能只挂在 Matcher 上**。
 * 此前只有 `recordPaymentEvidence` / `recordOrphanPaymentSignal` 两处调用它，
 * 而 `FeeExtractor` / `RefundLinker` / `TransferClassifier` 三条通知链路
 * 各自直写 Bill/PendingTransaction 并直接 emit 到 `PendingChangeHub`，
 * 完全绕过 Matcher ⇒ 门禁形同虚设：它们照样自动入账、改账户余额、进待确认、
 * 发系统通知。尤以 TransferClassifier 为甚：收款人命中时把 pending 定型为
 * `type="expense"`，而 `PendingPromptPolicy` 只按 `type == "expense"` 放行
 * ⇒ 一条纯通知来源的"转账给他人"会命中付款确认弹窗，用户点确认即写成**支出**。
 *
 * 因此另设 [isNotificationPipelineDisabledInV1] 供三个分类器在入口自查，
 * 语义与本闸一致但用于"整条链路早退"，避免各自重复实现判定。
 */
internal fun isNotificationSourceDisabledInV1(normalizedSourceType: String): Boolean =
    normalizedSourceType == "notification"

/**
 * 通知派生链路（手续费 / 退款关联 / 转账分类）的 v1 总闸。
 *
 * 与 [isNotificationSourceDisabledInV1] 同源同值，单独命名只为表达"整条链路早退"
 * 的用途，使三个分类器的入口判读意图明确、且不会与 Matcher 的证据级判据混淆。
 */
internal fun isNotificationPipelineDisabledInV1(): Boolean =
    isNotificationSourceDisabledInV1("notification")

/** 将支付证据来源收敛为 Native.d.ts 允许的三个小写值。 */
internal fun normalizePaymentEvidenceSourceType(value: String?): String? =
    when (value?.trim()?.lowercase(Locale.ROOT)) {
        "notification" -> "notification"
        "accessibility", "accessibility_result" -> "accessibility"
        "manual" -> "manual"
        else -> null
    }

/** 截图来源，和现有 Native.d.ts 的 SnapshotSourceReason 保持一致。 */
enum class SnapshotSourceReason(val wireValue: String) {
    CHECKOUT_STABLE("CHECKOUT_STABLE"),
    MANUAL_CAPTURE("MANUAL_CAPTURE")
}

/** 仅供内部截图协调器区分真实结算截图和用户主动重试截图。 */
internal enum class SnapshotCaptureTarget {
    CHECKOUT,
    USER_RETRY
}

/** 节点树确认后可用于手动截图的最小安全上下文，不包含原始页面文本。 */
internal data class UserRetryCaptureContext(
    val observation: CheckoutPageObservation,
    val sessionId: String? = null,
    val isNodeTreeAvailable: Boolean = true
) {
    val shoppingPackage: String
        get() = observation.shoppingPackage

    val pageRole: CheckoutPageRole
        get() = observation.pageRole

    val pageFingerprint: String
        get() = observation.pageFingerprint

    val isSensitivePage: Boolean
        get() = observation.isSensitivePage

    fun hasSafeDisplayContext(): Boolean =
        shoppingPackage.isNotBlank() &&
            pageFingerprint.isSha256PageFingerprint() &&
            !isSensitivePage &&
            pageRole.canBufferUserRetrySnapshot()

    fun isComplete(): Boolean =
        hasSafeDisplayContext() &&
            isNodeTreeAvailable

    fun withSessionId(value: String): UserRetryCaptureContext = copy(sessionId = value)
}

internal fun String?.isSha256PageFingerprint(): Boolean =
    !isNullOrBlank() &&
        length == 64 &&
        all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }

/**
 * 公开会话状态，严格对应 Native.d.ts 中声明的 8 个值。
 *
 * 阶段四 T1 起，CheckoutSessionEntity.status 只允许存这 8 个值；
 * 内部旅程状态（CheckoutJourneyState）存入独立 journey_state 列，
 * 通过 toPublicSessionStatus() 映射后才能写入 status 列或序列化给 RN。
 */
enum class SessionStatus(val wireValue: String) {
    OBSERVING("OBSERVING"),
    SNAPSHOT_BUFFERED("SNAPSHOT_BUFFERED"),
    AI_REQUESTING("AI_REQUESTING"),
    WAITING_ENRICHMENT("WAITING_ENRICHMENT"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    EXPIRED("EXPIRED");

    companion object {
        /** 终态集合，用于 DAO 过滤活跃会话；与 Native.d.ts 保持一致。 */
        val TERMINAL: Set<SessionStatus> = setOf(
            COMPLETED,
            FAILED,
            CANCELLED,
            EXPIRED
        )

        fun fromStorageValue(value: String?): SessionStatus = when (value) {
            "OBSERVING" -> OBSERVING
            "SNAPSHOT_BUFFERED" -> SNAPSHOT_BUFFERED
            "AI_REQUESTING" -> AI_REQUESTING
            "WAITING_ENRICHMENT" -> WAITING_ENRICHMENT
            "COMPLETED" -> COMPLETED
            "FAILED" -> FAILED
            "CANCELLED" -> CANCELLED
            "EXPIRED" -> EXPIRED
            // 兼容旧数据中可能残留的 DISMISSED 值，映射为 CANCELLED
            "DISMISSED" -> CANCELLED
            else -> WAITING_ENRICHMENT
        }
    }
}

/**
 * 结算旅程状态机。
 *
 * CHECKOUT_DETECTED 和 AI_PENDING 分别兼容旧契约中的 OBSERVING 和 AI_REQUESTING；
 * AI_TRIGGER_ARMED 在持久化到旧契约时仍按 SNAPSHOT_BUFFERED 处理，因为此时尚未联网。
 *
 * 阶段四 T1 起，内部旅程状态只存入 CheckoutSessionEntity.journeyState 列，
 * 不直接序列化给 RN；公开 status 列由 toPublicSessionStatus() 映射后写入。
 */
enum class CheckoutJourneyState(val wireValue: String) {
    CHECKOUT_DETECTED("CHECKOUT_DETECTED"),
    SNAPSHOT_BUFFERED("SNAPSHOT_BUFFERED"),
    AI_TRIGGER_ARMED("AI_TRIGGER_ARMED"),
    AI_PENDING("AI_PENDING"),
    AI_COMPLETED("AI_COMPLETED"),
    AI_FAILED("AI_FAILED"),
    WAITING_PAYMENT("WAITING_PAYMENT"),
    PAYMENT_CONFIRMED("PAYMENT_CONFIRMED"),
    WAITING_ENRICHMENT("WAITING_ENRICHMENT"),
    AUTO_COMMITTED("AUTO_COMMITTED"),
    NEEDS_CONFIRMATION("NEEDS_CONFIRMATION"),
    COMPLETED("COMPLETED"),
    DISMISSED("DISMISSED"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED"),
    EXPIRED("EXPIRED");

    fun canTransitionTo(next: CheckoutJourneyState): Boolean = when (this) {
        CHECKOUT_DETECTED -> next in setOf(SNAPSHOT_BUFFERED, CANCELLED, FAILED, EXPIRED)
        SNAPSHOT_BUFFERED -> next in setOf(
            AI_TRIGGER_ARMED,
            PAYMENT_CONFIRMED,
            WAITING_ENRICHMENT,
            CANCELLED,
            FAILED,
            EXPIRED
        )
        AI_TRIGGER_ARMED -> next in setOf(
            AI_PENDING,
            PAYMENT_CONFIRMED,
            WAITING_ENRICHMENT,
            CANCELLED,
            FAILED,
            EXPIRED
        )
        AI_PENDING -> next in setOf(
            AI_COMPLETED,
            AI_FAILED,
            WAITING_PAYMENT,
            PAYMENT_CONFIRMED,
            CANCELLED,
            FAILED,
            EXPIRED
        )
        AI_COMPLETED, AI_FAILED -> next in setOf(
            WAITING_PAYMENT,
            PAYMENT_CONFIRMED,
            WAITING_ENRICHMENT,
            NEEDS_CONFIRMATION,
            FAILED,
            EXPIRED
        )
        WAITING_PAYMENT -> next in setOf(PAYMENT_CONFIRMED, CANCELLED, FAILED, EXPIRED)
        PAYMENT_CONFIRMED -> next in setOf(
            AI_PENDING,
            WAITING_ENRICHMENT,
            AUTO_COMMITTED,
            NEEDS_CONFIRMATION,
            COMPLETED,
            FAILED,
            EXPIRED
        )
        WAITING_ENRICHMENT -> next in setOf(
            AI_PENDING,
            AI_COMPLETED,
            PAYMENT_CONFIRMED,
            AUTO_COMMITTED,
            NEEDS_CONFIRMATION,
            FAILED,
            EXPIRED
        )
        AUTO_COMMITTED -> next == COMPLETED
        NEEDS_CONFIRMATION -> next in setOf(AUTO_COMMITTED, COMPLETED, DISMISSED, CANCELLED)
        COMPLETED, DISMISSED, CANCELLED, FAILED, EXPIRED -> false
    }

    /**
     * 映射到 Native.d.ts 公开 SessionStatus 的 8 个值之一。
     * 按阶段四任务文档第 3.3 节映射表执行，不得新增第 9 个公开值。
     */
    fun toPublicSessionStatus(): SessionStatus = when (this) {
        CHECKOUT_DETECTED -> SessionStatus.OBSERVING
        SNAPSHOT_BUFFERED -> SessionStatus.SNAPSHOT_BUFFERED
        AI_TRIGGER_ARMED -> SessionStatus.SNAPSHOT_BUFFERED
        AI_PENDING -> SessionStatus.AI_REQUESTING
        AI_COMPLETED -> SessionStatus.WAITING_ENRICHMENT
        AI_FAILED -> SessionStatus.FAILED
        WAITING_PAYMENT -> SessionStatus.WAITING_ENRICHMENT
        PAYMENT_CONFIRMED -> SessionStatus.WAITING_ENRICHMENT
        WAITING_ENRICHMENT -> SessionStatus.WAITING_ENRICHMENT
        AUTO_COMMITTED -> SessionStatus.COMPLETED
        NEEDS_CONFIRMATION -> SessionStatus.WAITING_ENRICHMENT
        COMPLETED -> SessionStatus.COMPLETED
        DISMISSED -> SessionStatus.CANCELLED
        CANCELLED -> SessionStatus.CANCELLED
        FAILED -> SessionStatus.FAILED
        EXPIRED -> SessionStatus.EXPIRED
    }

    companion object {
        fun fromStorageValue(value: String?): CheckoutJourneyState = when (value) {
            "OBSERVING" -> CHECKOUT_DETECTED
            "AI_REQUESTING" -> AI_PENDING
            else -> entries.firstOrNull { it.wireValue == value } ?: FAILED
        }
    }
}

/**
 * 由会话、购物包名和页面指纹组成的稳定请求键。
 *
 * 触发来源不参与计算，因此提交、支付交接和通知回退只能命中同一个键。
 */
@JvmInline
value class CheckoutRequestKey(val value: String) {
    init {
        require(value.isNotBlank()) { "checkoutRequestKey 不能为空" }
    }

    companion object {
        fun from(sessionId: String, shoppingPackage: String, pageFingerprint: String): CheckoutRequestKey {
            require(sessionId.isNotBlank()) { "sessionId 不能为空" }
            require(shoppingPackage.isNotBlank()) { "shoppingPackage 不能为空" }
            require(pageFingerprint.isNotBlank()) { "pageFingerprint 不能为空" }
            return CheckoutRequestKey(
                "checkout|$shoppingPackage|$sessionId|$pageFingerprint"
            )
        }

        fun newSessionId(): String = UUID.randomUUID().toString()
    }
}

/**
 * ⚠️ 2026-10-12 删除：`CheckoutJourney` 这个 data class **从未被实例化过**。
 *
 * 全仓（含 test / JS）检索只命中该定义与两个成员方法的返回类型。会话状态实际由
 * `CheckoutJourneyState`（枚举，`:260`，仍在使用）+ `CheckoutSessionEntity`
 * （Room 实体，仍在使用）承载。保留一个从不构造的载体只会让人误以为
 * 有一条独立于这两者的状态流转路径。
 */
