package com.jotbill.app.recognition

private val DEFAULT_SUPPORTED_SHOPPING_PACKAGES = setOf(
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
    val isSensitivePage: Boolean
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

/** 只判断是否满足创建内存快照的前置条件，不产生任何网络请求。 */
class CheckoutPageDetector(
    supportedShoppingPackages: Set<String> = DEFAULT_SUPPORTED_SHOPPING_PACKAGES
) {
    private val supportedPackages = supportedShoppingPackages.toSet()

    fun detect(observation: CheckoutPageObservation): CheckoutPageDetection {
        if (observation.shoppingPackage !in supportedPackages) {
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
