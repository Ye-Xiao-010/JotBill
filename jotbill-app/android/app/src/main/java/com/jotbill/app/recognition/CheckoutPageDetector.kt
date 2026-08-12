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
    val observedAt: Long = 0L
)

enum class CheckoutDetectionRejection(val wireValue: String) {
    UNSUPPORTED_SHOPPING_PACKAGE("UNSUPPORTED_SHOPPING_PACKAGE"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    MISSING_FINAL_AMOUNT("MISSING_FINAL_AMOUNT"),
    MISSING_CHECKOUT_ACTION("MISSING_CHECKOUT_ACTION"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    MISSING_PAGE_FINGERPRINT("MISSING_PAGE_FINGERPRINT")
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
        if (!observation.hasCheckoutActionSignal) {
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
