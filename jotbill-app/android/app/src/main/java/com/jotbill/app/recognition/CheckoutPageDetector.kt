package com.jotbill.app.recognition

internal val DEFAULT_SUPPORTED_SHOPPING_PACKAGES = setOf(
    "com.taobao.taobao",
    "com.tmall.wireless",
    "com.jingdong.app.mall",
    "com.sankuai.meituan",
    "com.xunmeng.pinduoduo",
    "com.sdu.didi.psnger",
    "com.ss.android.ugc.aweme",
    "com.xingin.xhs"
)

/** 只有这些前台包名才可能代表从购物应用进入支付应用的交接。 */
internal val DEFAULT_SUPPORTED_PAYMENT_PACKAGES = setOf(
    "com.tencent.mm",
    "com.eg.android.AlipayGphone"
)

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

/** 经过稳定窗口确认的结算页，不包含截图、商户推断或网络请求。 */
data class StableCheckoutPage(
    val observation: CheckoutPageObservation,
    val stableAt: Long
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
    // 购物包名是阶段三固定边界，不能由调用方扩展。
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
        if (observation.pageRole != CheckoutPageRole.CHECKOUT) {
            return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.NOT_CHECKOUT_PAGE)
        }
        if (!observation.hasFinalAmountSignal) {
            return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_FINAL_AMOUNT)
        }
        // 动作信号缺失时允许类名结算语义替代：弹窗结算页的提交按钮会间歇不可见，
        // 与 classifyPageRole 保持同一放行条件，否则角色判为 CHECKOUT 仍会被此处拒掉。
        if (!observation.hasCheckoutActionSignal && !observation.hasCheckoutClassSemantic) {
            return CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_CHECKOUT_ACTION)
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
