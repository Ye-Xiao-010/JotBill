package com.jotbill.app.recognition

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

/**
 * 通知服务传给识别层的最小结构化支付信号，不包含通知原文、页面文本或图片。
 * 金额统一使用分，避免跨服务传递 Double 时产生精度歧义。
 */
data class PaymentNotificationFallbackSignal(
    val paymentPackage: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String? = null,
    val orderId: String? = null
) {
    fun isValid(): Boolean =
        paymentPackage.isNotBlank() &&
            paymentPackage.length <= 255 &&
            amountMinor > 0L &&
            occurredAt > 0L &&
            isOptionalTextValid(merchant) &&
            isOptionalTextValid(orderId)

    private fun isOptionalTextValid(value: String?): Boolean =
        value == null || (value.isNotBlank() && value.length <= 256)
}

/** 截图来源，和现有 Native.d.ts 的 SnapshotSourceReason 保持一致。 */
enum class SnapshotSourceReason(val wireValue: String) {
    CHECKOUT_STABLE("CHECKOUT_STABLE"),
    MANUAL_CAPTURE("MANUAL_CAPTURE")
}

/**
 * 结算旅程状态机。
 *
 * CHECKOUT_DETECTED 和 AI_PENDING 分别兼容旧契约中的 OBSERVING 和 AI_REQUESTING；
 * AI_TRIGGER_ARMED 在持久化到旧契约时仍按 SNAPSHOT_BUFFERED 处理，因为此时尚未联网。
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
        WAITING_ENRICHMENT -> next in setOf(AI_PENDING, AUTO_COMMITTED, NEEDS_CONFIRMATION, FAILED, EXPIRED)
        AUTO_COMMITTED -> next == COMPLETED
        NEEDS_CONFIRMATION -> next in setOf(COMPLETED, DISMISSED, CANCELLED)
        COMPLETED, DISMISSED, CANCELLED, FAILED, EXPIRED -> false
    }

    /** 转换为当前 Native.d.ts 可识别的旧状态值，未定义的状态保留语义化新值。 */
    fun toCompatibleStorageValue(): String = when (this) {
        CHECKOUT_DETECTED -> "OBSERVING"
        AI_TRIGGER_ARMED -> "SNAPSHOT_BUFFERED"
        AI_PENDING -> "AI_REQUESTING"
        else -> wireValue
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
