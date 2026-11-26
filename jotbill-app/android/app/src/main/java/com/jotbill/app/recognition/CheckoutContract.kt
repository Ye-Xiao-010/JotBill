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
 * 自动通道"可派发"的页面角色集合（改造 A-2，批 V-4 定案：`IMPL_PLAN` §8.5）。
 *
 * **单一出处**：本常量是该集合的唯一定义点，消费点只允许写 `in/!in` 判定
 * （如 `CheckoutPageDetector.detect()` 与 `CheckoutPaymentMatcher.findReferenceForPayment()`）。
 * 不得放开 `SHOPPING_HOME` / `UNKNOWN`。
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
 * N-1：通知来源是否允许**自动入账**（直接写 Bill 并联动账户余额）。
 *
 * ## 为什么从 D-3 的"整体停用"改为只扣自动入账
 *
 * D-3 / G3.1 曾按「v1 只做支付结果页」把通知来源整体停用
 * （`isNotificationSourceDisabledInV1`，已删）。真机后果：2026-10-28 三笔微信内嵌支付
 * **零痕迹丢失** —— 通知解析成功、金额全对，却被出口闸静默丢弃，待确认一张都没有。
 *
 * N-1 重新切分 D-3 的风险面，两者代价完全不同：
 * - **落待确认**：只是把证据摆到用户面前，误判的代价是用户多点一次「丢弃」；
 * - **自动入账**：直接改账本与账户余额，误判的代价是错账。
 *
 * D-3 记载的两个难点（扣款账户归属、同一支付多条通知）决定的是**自动入账**的正确性，
 * 与"要不要把证据给用户看"无关 ⇒ N-1 放开前者、继续扣住后者。
 *
 * ## 触点要求
 *
 * - 本闸只约束**通知来源**；`accessibility`（结果页主路径）与 `manual`（用户断言）不受影响。
 * - **必须覆盖全部 notification 自动入账出口**：`RefundLinker` / `TransferClassifier`
 *   各自直写 Bill 并联动余额、绕过 Matcher，需在入口自查；`FeeExtractor` 只落待确认，
 *   无需本闸。
 * - N-2 在补齐「扣款账户归属」与「多条通知防重」后打开本闸。
 */
internal fun isNotificationAutoCommitAllowed(): Boolean = false

/**
 * N-1：通知来源暂不自动入账时的冲突码。
 *
 * 出现在 `RefundLinker` / `TransferClassifier` 的待确认上，表示"证据没问题，
 * 只是这一版不替你入账"。放在各分类器自己的冲突码（`ORIGINAL_MISSING` 等）
 * **之后**判定：有更具体的原因时优先展示具体原因。
 * N-2 打开 [isNotificationAutoCommitAllowed] 后本码不再产生。
 */
internal const val CONFLICT_AUTO_COMMIT_DEFERRED = "AUTO_COMMIT_DEFERRED"

/**
 * 通知证据落库前等待无障碍通道先产出的宽限期（2026-10-28 D-3 修订引入）。
 *
 * HyperOS 2 支付期无障碍死区可达 155s，通知通常比无障碍证据早到 —— 通知服务
 * 等这么久再按 sourceEventKey 复查，避免同一次支付既落会话证据又落孤儿待确认。
 * D-3 的"通知整体停用"闸已由 N-1 删除，本常量只剩复查窗口语义。
 */
internal const val NOTIFICATION_FALLBACK_GRACE_MILLIS = 8_000L

/** 将支付证据来源收敛为 Native.d.ts 允许的三个小写值。 */
internal fun normalizePaymentEvidenceSourceType(value: String?): String? =
    when (value?.trim()?.lowercase(Locale.ROOT)) {
        "notification" -> "notification"
        "accessibility", "accessibility_result" -> "accessibility"
        "sms" -> "sms"
        "manual" -> "manual"
        else -> null
    }

/** 截图来源，和现有 Native.d.ts 的 SnapshotSourceReason 保持一致。 */
enum class SnapshotSourceReason(val wireValue: String) {
    CHECKOUT_STABLE("CHECKOUT_STABLE"),
    MANUAL_CAPTURE("MANUAL_CAPTURE")
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
