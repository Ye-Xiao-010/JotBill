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

    fun canBufferSnapshot(): Boolean = this == CHECKOUT

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

/** 会话中允许产生 AI 请求的购买意图信号。普通页面观察不属于此枚举。 */
enum class PurchaseIntent(val wireValue: String) {
    SUBMIT_ACTION("SUBMIT_ACTION"),
    PAYMENT_APP_HANDOFF("PAYMENT_APP_HANDOFF"),
    PAYMENT_PAGE_VISIBLE("PAYMENT_PAGE_VISIBLE"),
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

    /** 转换为当前 Native.d.ts 可识别的公开 SessionStatus 存储值。 */
    fun toCompatibleStorageValue(): String = toPublicSessionStatus().wireValue

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

/** 结算旅程的最小状态载体，后续由会话管理器映射到 CheckoutSessionEntity。 */
data class CheckoutJourney(
    val sessionId: String,
    val slotId: String,
    val state: CheckoutJourneyState,
    val shoppingPackage: String?,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String?,
    val snapshotId: String?,
    val checkoutRequestKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        require(slotId.isNotBlank()) { "slotId 不能为空" }
        require(updatedAt >= createdAt) { "updatedAt 不能早于 createdAt" }
        require(expiresAt >= createdAt) { "expiresAt 不能早于 createdAt" }
    }

    fun transitionTo(next: CheckoutJourneyState, at: Long): CheckoutJourney {
        require(at >= updatedAt) { "状态更新时间不能倒退" }
        require(state.canTransitionTo(next)) {
            "不允许从 ${state.wireValue} 转换到 ${next.wireValue}"
        }
        return copy(state = next, updatedAt = at)
    }

    fun bindRequestKey(key: CheckoutRequestKey, at: Long): CheckoutJourney {
        require(at >= updatedAt) { "请求键绑定时间不能倒退" }
        require(checkoutRequestKey == null || checkoutRequestKey == key.value) {
            "同一结算旅程不能替换 checkoutRequestKey"
        }
        return copy(checkoutRequestKey = key.value, updatedAt = at)
    }
}
