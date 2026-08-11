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
    val invalidatedAt: Long
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
class CheckoutPageDetector(
    supportedShoppingPackages: Set<String> = DEFAULT_SUPPORTED_SHOPPING_PACKAGES
) {
    private val supportedPackages = supportedShoppingPackages.toSet()

    /** 包名只用于资格判断，不从包名推断平台、商户或订单字段。 */
    fun supportsShoppingPackage(shoppingPackage: String): Boolean = shoppingPackage in supportedPackages

    fun detect(observation: CheckoutPageObservation): CheckoutPageDetection {
        if (!supportsShoppingPackage(observation.shoppingPackage)) {
            return CheckoutPageDetection.Rejected(
                CheckoutDetectionRejection.UNSUPPORTED_SHOPPING_PACKAGE
            )
        }
        if (!observation.pageRole.canBufferSnapshot()) {
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
        if (observation.pageFingerprint.isBlank()) {
            return CheckoutPageDetection.Rejected(
                CheckoutDetectionRejection.MISSING_PAGE_FINGERPRINT
            )
        }
        return CheckoutPageDetection.Accepted(observation)
    }
}
