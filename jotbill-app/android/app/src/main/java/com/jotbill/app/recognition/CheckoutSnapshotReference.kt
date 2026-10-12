package com.jotbill.app.recognition

/**
 * 结算页槽位与快照引用 —— 从 `CheckoutSnapshotBuffer.kt` 抽出（2026-10-12 精简）。
 *
 * 截图识别链路整链删除后，`CheckoutSnapshotBuffer` 本体（1,113 行，含图片缓冲、
 * 过期调度、消费交接、移动到支付过程槽等）已无任何生产调用方，整文件删除。但其中
 * 三个符号**仍被活跃的支付结果页文本解析路径使用**，不能一起带走：
 *
 *   · `CheckoutPaymentMatcher.kt:303/319/447-453/2547` 构造与消费
 *     `CheckoutSnapshotReference` —— 文本解析出来的证据要挂到一个"引用"上，
 *     该引用描述这段证据对应哪个购物包/槽位/时间窗，是待确认弹窗归因的依据；
 *   · `CheckoutPaymentMatcher.kt:322/453` 与
 *     `CheckoutAccessibilityService.kt:2303/2624` 调用 `checkoutSnapshotSlotId()`
 *     生成槽位键 —— 与旧缓冲区的键空间保持字面一致，避免两处算法漂移。
 *
 * 因此单独成文件，与被删的缓冲区不再有任何耦合。
 */

/** 结算截图槽位前缀：key = `shopping-package:<包名>`。 */
internal const val CHECKOUT_SNAPSHOT_SLOT_PREFIX = "shopping-package:"

/** 结算截图与购买意图共用同一购物包槽位命名。 */
internal fun checkoutSnapshotSlotId(shoppingPackage: String): String =
    CHECKOUT_SNAPSHOT_SLOT_PREFIX + shoppingPackage

/** 快照元数据，不携带图片字节。 */
internal data class CheckoutSnapshotReference(
    val snapshotId: String,
    val sessionId: String,
    val slotId: String,
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val candidateRevision: Int,
    val sourceReason: SnapshotSourceReason
)