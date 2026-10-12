package com.jotbill.app.recognition

/**
 * 购买意图协调结果契约 —— 从 `CheckoutPurchaseIntentCoordinator.kt` 抽出（2026-10-12 精简）。
 *
 * 协调器实现（1,245 行）随截图识别链路整链删除，但**契约本身不能一起删**：两个活着的
 * 消费者都依赖它做 `when` 分支穷尽匹配。
 *
 * · `PaymentNotificationService.kt:268-269` —— `tryFallbackSignal()` 判
 *   `Accepted || AiDispatched` 决定"证据是否已被无障碍链路接走"，否则走孤儿落库。
 * · `RecognitionModule.kt:151-165` —— `retryAiRecognition()` 的 RN 错误码映射入口，
 *   按变体抛 `SERVICE_UNAVAILABLE` / `SESSION_EXPIRED` / `DUPLICATE_TRANSACTION`。
 *
 * 因此这里保留 sealed 结构的**变体名与 `reasonCode` 字面契约**，只删掉引用已删类型的字段：
 * `Accepted.aiResult`（`CheckoutAiTriggerResult`）、`Accepted.snapshotOwnership`
 * （`PurchaseIntentSnapshotOwnership`）、`Rejected.consumeResult`（同 `CheckoutAiTriggerResult`）。
 *
 * ⚠️ 精简后 `Accepted` / `AiDispatched` / `ReleasedWithoutConsumer` **已无任何产出方**
 * （协调器是唯一生产者），两个消费者的 `is` 判定恒为 false。它们保留是为了让 `when`
 * 保持穷尽、以及不改动那两处的判定语义 —— 属于有意识的契约保留，不是遗漏。
 */

/**
 * 孤儿待确认的 `conflictReason` 线缆词汇（仅保留字符串常量）。
 *
 * 这些码原本定义在 `CheckoutPurchaseIntentCoordinator.kt` 顶部，由协调器的 `routeLocked`
 * 产出（槽位缺图 / 归属 0 命中 / 归属歧义 / AI 派发不可得）。协调器整链删除后**本分支
 * 不再产出它们**，但**必须保留常量本身**：
 *   · `ReviewScreen.tsx:1113-1114` 的 `CONFLICT_REASON_LABELS` 按字面量映射中文文案；
 *   · `RecognitionModule.kt:252` 的 `mapRejectionCode` 按字面量映射 RN 错误码；
 *   · `CheckoutPaymentMatcher.mergeOrphanConflictReason` 会把这些码与新码**合并**后
 *     写进 `pending.conflictReason`。历史已落库的 pending 带着这些码，删掉常量会让
 *     升级后老数据走到 `translateConflictReason` 的 `else` 分支显示原始英文串。
 *
 * 即：**产出方已删，消费方仍在**。这是有意识的保留，不是遗漏。
 */
internal const val PAYMENT_PROCESS_SNAPSHOT_MISSING = "PAYMENT_PROCESS_SNAPSHOT_MISSING"

/** 孤儿待确认的 conflictReason：归属判定 0 命中（不猜测、不回退最近活跃会话）。 */
internal const val PAYMENT_RESULT_NO_MATCHING_SESSION = "PAYMENT_RESULT_NO_MATCHING_SESSION"

/** 孤儿待确认的 conflictReason：支付结果页证据拿不到 AI 定论。 */
internal const val PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE = "PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE"

/** 购买意图在进入 AI 调度前可能遇到的页面、生命周期或预算门禁结果。 */
enum class PurchaseIntentGateRejection(val wireValue: String) {
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED"),
    COORDINATOR_CLOSED("COORDINATOR_CLOSED"),
    NO_CHECKOUT_CONTEXT("NO_CHECKOUT_CONTEXT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    REQUEST_BUDGET_EXHAUSTED("REQUEST_BUDGET_EXHAUSTED"),
    PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED("PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED"),
    INVALID_PAYMENT_NOTIFICATION("INVALID_PAYMENT_NOTIFICATION"),
    BUFFER_UNAVAILABLE("BUFFER_UNAVAILABLE"),
    /** PAYMENT_RESULT 意图缺归属信号，或非 PAYMENT_RESULT 意图携带了归属信号。 */
    INVALID_PAYMENT_RESULT("INVALID_PAYMENT_RESULT"),
    /** 改造 E-2：归属判定 0 命中 ⇒ 孤儿待确认（不猜测、不回退最近活跃会话）。 */
    PAYMENT_RESULT_NO_MATCHING_SESSION("PAYMENT_RESULT_NO_MATCHING_SESSION"),
    /** 改造 E-2：归属判定 >1 命中（Ambiguous）⇒ 待确认（安全侧，不误账）。 */
    PAYMENT_RESULT_AMBIGUOUS_SESSION("PAYMENT_RESULT_AMBIGUOUS_SESSION")
}

/** 协调器对外报告的路由结果。 */
sealed class PurchaseIntentCoordinationResult {
    abstract val intent: PurchaseIntent

    data class Accepted(
        override val intent: PurchaseIntent
    ) : PurchaseIntentCoordinationResult()

    /** Ready 已交 AI 客户端；回调只收到元数据，不拥有图片副本。 */
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

    /** 页面门禁或缓冲区消费失败。 */
    data class Rejected(
        override val intent: PurchaseIntent,
        val gateReason: PurchaseIntentGateRejection,
        val slotId: String? = null,
        val pageFingerprint: String? = null,
        /** 归属判定 >1 命中时的候选会话（改造 E-2），供孤儿落库与诊断。 */
        val sessionIds: List<String> = emptyList()
    ) : PurchaseIntentCoordinationResult() {
        val reasonCode: String get() = gateReason.wireValue
    }
}

/** 后续协调器或 RN bridge 可注册的单一结果消费者。 */
fun interface PurchaseIntentResultCallback {
    /** 回调可能收到 AiDispatched / ReleasedWithoutConsumer，均不携带图片所有权。 */
    fun onResult(result: PurchaseIntentCoordinationResult)
}