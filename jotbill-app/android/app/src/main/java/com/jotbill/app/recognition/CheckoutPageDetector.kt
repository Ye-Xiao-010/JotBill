package com.jotbill.app.recognition

/**
 * 全部购物包白名单（含 RESULT_ONLY 档）。B2 起为 [RecognitionPackages.SHOPPING_PACKAGES]
 * 的别名 —— 包名的唯一出处在 [RecognitionPackages]，此处保留变量名与语义，
 * 既有引用点（服务事件归属、[SensitivePageLatch]）零改动。
 */
internal val DEFAULT_SUPPORTED_SHOPPING_PACKAGES = RecognitionPackages.SHOPPING_PACKAGES

/**
 * 只有这些前台包名才可能代表从购物应用进入支付应用的交接。
 * B2 起为 [RecognitionPackages.PAYMENT_PACKAGES] 的别名（云闪付已加入）。
 */
internal val DEFAULT_SUPPORTED_PAYMENT_PACKAGES = RecognitionPackages.PAYMENT_PACKAGES

/**
 * 无障碍采集层提交给检测器的规范化页面摘要。
 *
 * 该类型不负责读取节点树、OCR 或截图，只把后续采集器得到的事实交给一个保守的
 * 结算页门禁，避免单个价格文本或“立即购买”按钮误触发截图缓冲。
 */
data class CheckoutPageObservation(
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val hasFinalAmountSignal: Boolean,
    val hasCheckoutActionSignal: Boolean,
    val isSensitivePage: Boolean,
    val windowClass: String = "",
    val normalizedTextHash: String = "",
    val observedAt: Long = 0L,
    /**
     * 窗口类名是否指向结算/下单流程。
     *
     * 弹窗式结算页（京东 PopupNewFillOrderActivity）的提交按钮会间歇上报
     * isVisibleToUser=false，hasCheckoutActionSignal 随之丢失。该标记让检测器
     * 在动作信号缺失时仍能凭类名证据放行，避免页面角色在结算态与浏览态之间抖动。
     */
    val hasCheckoutClassSemantic: Boolean = false,
    /**
     * 页面上的**订单总额**（分）。口径见 `docs/tdd/08_phase4_journey_spec.md` §3.1
     * （订单总额、分；订单号可见时订单号优先为身份，金额降为校验字段）。
     *
     * **null = 无法判定**（页面无货币金额，或存在多金额歧义）⇒ 消费方必须保守处理：
     * 改造 C / Y4 判据 1 在任一侧为 null 时**沿用旧会话**（宁可少新建，不可误新建）。
     *
     * 由 [PageAmountAccumulator] 从**原始**节点文本提取（脱敏会抹掉 ≥3 位数字，
     * 脱敏后无从取数 —— P2-8 同一根因）。
     */
    val finalAmountMinor: Long? = null
)

enum class CheckoutDetectionRejection(val wireValue: String) {
    UNSUPPORTED_SHOPPING_PACKAGE("UNSUPPORTED_SHOPPING_PACKAGE"),
    /** B2 降级分档：RESULT_ONLY 购物包只进站内结果页扫描腿，不进结算页观察腿。 */
    RESULT_PAGE_ONLY_PACKAGE("RESULT_PAGE_ONLY_PACKAGE"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    MISSING_FINAL_AMOUNT("MISSING_FINAL_AMOUNT"),
    MISSING_CHECKOUT_ACTION("MISSING_CHECKOUT_ACTION"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    MISSING_PAGE_FINGERPRINT("MISSING_PAGE_FINGERPRINT"),
    INVALID_PAGE_FINGERPRINT("INVALID_PAGE_FINGERPRINT")
}

sealed class CheckoutPageDetection {
    data class Accepted(val observation: CheckoutPageObservation) : CheckoutPageDetection()

    data class Rejected(val reason: CheckoutDetectionRejection) : CheckoutPageDetection()
}

/** 页面稳定观察对后续采集器公开的失效原因。 */
enum class CheckoutInvalidationReason(val wireValue: String) {
    PAYMENT_APP_HANDOFF("PAYMENT_APP_HANDOFF"),
    LEFT_SUPPORTED_SHOPPING_APP("LEFT_SUPPORTED_SHOPPING_APP"),
    NON_CHECKOUT_PAGE("NON_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    NODE_TREE_UNAVAILABLE("NODE_TREE_UNAVAILABLE"),
    SERVICE_INTERRUPTED("SERVICE_INTERRUPTED"),
    SERVICE_DESTROYED("SERVICE_DESTROYED")
}

/**
 * 经过稳定窗口确认的结算页，不包含截图、商户推断或网络请求。
 *
 * @property sessionId 本帧对应的**会话标识**（§8.3 sessionId 传递链路）。
 *
 * 该值**只能由截图协调器给出**：会话是 `CheckoutSnapshotCaptureCoordinator.handleStable`
 * 建立/沿用的（新建取 `sessionIdFactory()`、漂移沿用旧会话、支付返回恢复旧会话），
 * 因此必须等它处理完本帧之后才能解析，见 `currentCheckoutSessionId`。
 *
 * **null = 本次稳定帧未建立任何会话**（交接过渡窗内被有意忽略、协调器已关闭）。
 * 消费方不得凭空造会话，也不得回退到"上一帧的会话"——那正是改造 E 要消除的错误归属。
 * 默认值 null 只为兼容不关心会话的既有构造点（检测器纯判据与截图预检测试）。
 */
data class StableCheckoutPage(
    val observation: CheckoutPageObservation,
    val stableAt: Long,
    val sessionId: String? = null
)

/** 页面离开结算候选或服务生命周期中断时发送的失效事件。 */
data class CheckoutObservationInvalidation(
    val reason: CheckoutInvalidationReason,
    val shoppingPackage: String?,
    val pageFingerprint: String?,
    val invalidatedAt: Long,
    /** 当前前台包名，与产生结算快照的 shoppingPackage 明确区分。 */
    val foregroundPackage: String? = null
)

/** 后续截图、购买意图和会话协调器使用的单一观察事件流。 */
sealed class CheckoutObservationEvent {
    data class Stable(val page: StableCheckoutPage) : CheckoutObservationEvent()

    data class Invalidated(val detail: CheckoutObservationInvalidation) : CheckoutObservationEvent()
}

fun interface CheckoutObservationListener {
    fun onEvent(event: CheckoutObservationEvent)
}

/** 只判断是否满足创建内存快照的前置条件，不产生任何网络请求。 */
class CheckoutPageDetector {
    // 包名边界不能由调用方扩展（约束保留，B2 语义不变）；边界定义已收敛到
    // RecognitionPackages —— 改包名只改那里，本类不做任何包名推断。
    private val supportedPackages = DEFAULT_SUPPORTED_SHOPPING_PACKAGES
    private val paymentPackages = DEFAULT_SUPPORTED_PAYMENT_PACKAGES

    /** 包名只用于资格判断，不从包名推断平台、商户或订单字段。 */
    fun supportsShoppingPackage(shoppingPackage: String): Boolean = shoppingPackage in supportedPackages

    /** 支付包名只用于判断交接目标，不据此伪造商户、平台或支付结果。 */
    fun supportsPaymentPackage(packageName: String): Boolean = packageName in paymentPackages

    fun detect(observation: CheckoutPageObservation): CheckoutPageDetection {
        if (!supportsShoppingPackage(observation.shoppingPackage)) {
            return CheckoutPageDetection.Rejected(
                CheckoutDetectionRejection.UNSUPPORTED_SHOPPING_PACKAGE
            )
        }
        // B2 降级分档：RESULT_ONLY 购物包（得物/唯品会/苏宁）只进站内结果页扫描腿
        // （onAccessibilityEvent 的 PAYMENT_RESULT 分支），不进结算页观察腿 ——
        // 其页面角色词表未经真机校准，放行会搅浑结算页门禁与会话语义。
        if (observation.shoppingPackage !in RecognitionPackages.SHOPPING_PACKAGES_CORE) {
            return CheckoutPageDetection.Rejected(
                CheckoutDetectionRejection.RESULT_PAGE_ONLY_PACKAGE
            )
        }
        // 改造 A-2（批 V-4）：页面角色门禁改用可截角色集合（单一出处：CheckoutContract）。
        // 放宽到 PRODUCT_DETAIL / CART 是"详情页直达收银台"场景的前提；仍不放开
        // UNKNOWN / SHOPPING_HOME（见 AUTO_DISPATCHABLE_PAGE_ROLES 的说明）。
        if (observation.pageRole !in AUTO_DISPATCHABLE_PAGE_ROLES) {
            return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.NOT_CHECKOUT_PAGE)
        }
        // 批 V-4b：下面两条是**结算页专属**证据（订单总额 + 结算动作词）。它们与
        // `classifyPageRole` 的 CHECKOUT 分支**同源**（同一组布尔）⇒ 若对非结算角色
        // 沿用这两条，角色门禁的放宽会完全空转（真机不存在"角色非 CHECKOUT 且带结算
        // 证据"的帧，见 CheckoutWideIngestTest 的类注释）。故按角色分档：
        // 非结算角色只受【白名单包 / 非敏感页 / 合法指纹】三条红线约束。
        if (observation.pageRole == CheckoutPageRole.CHECKOUT) {
            if (!observation.hasFinalAmountSignal) {
                return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_FINAL_AMOUNT)
            }
            // 动作信号缺失时允许类名结算语义替代：弹窗结算页的提交按钮会间歇不可见，
            // 与 classifyPageRole 保持同一放行条件，否则角色判为 CHECKOUT 仍会被此处拒掉。
            if (!observation.hasCheckoutActionSignal && !observation.hasCheckoutClassSemantic) {
                return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_CHECKOUT_ACTION)
            }
        }
        if (observation.isSensitivePage) {
            return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.SENSITIVE_PAGE)
        }
        if (!isSha256Fingerprint(observation.pageFingerprint)) {
            return CheckoutPageDetection.Rejected(
                CheckoutDetectionRejection.INVALID_PAGE_FINGERPRINT
            )
        }
        return CheckoutPageDetection.Accepted(observation)
    }

    private fun isSha256Fingerprint(value: String): Boolean =
        value.length == 64 && value.all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }
}
