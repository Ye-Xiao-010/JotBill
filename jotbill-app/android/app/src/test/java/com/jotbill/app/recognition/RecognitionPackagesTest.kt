package com.jotbill.app.recognition

import com.jotbill.app.notification.PaymentResultNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 包名白名单收敛测试：钉死 [RecognitionPackages] 三集合的派生关系、
 * CORE / RESULT_ONLY 降级分档，以及各消费方确实从单一出处取值。
 */
class RecognitionPackagesTest {

    private val detector = CheckoutPageDetector()

    /** 构造满足结算条件的观察对象（分档判定在结算条件之前，参数默认即可）。 */
    private fun observation(
        shoppingPackage: String,
        pageRole: CheckoutPageRole = CheckoutPageRole.CHECKOUT
    ): CheckoutPageObservation = CheckoutPageObservation(
        shoppingPackage = shoppingPackage,
        pageRole = pageRole,
        pageFingerprint = "a".repeat(64),
        hasFinalAmountSignal = true,
        hasCheckoutActionSignal = true,
        isSensitivePage = false,
        hasCheckoutClassSemantic = true
    )

    @Test
    fun 三集合内容正确() {
        assertEquals(
            setOf(
                "com.taobao.taobao",
                "com.tmall.wireless",
                "com.jingdong.app.mall",
                "com.sankuai.meituan",
                "com.xunmeng.pinduoduo",
                "com.sdu.didi.psnger",
                "com.ss.android.ugc.aweme",
                "com.xingin.xhs"
            ),
            RecognitionPackages.SHOPPING_PACKAGES_CORE
        )
        assertEquals(
            setOf("com.shizhuang.duapp", "com.achievo.vipshop", "com.suning.mobile.ebuy"),
            RecognitionPackages.SHOPPING_PACKAGES_RESULT_ONLY
        )
        assertTrue("云闪付 B2 加入支付包", "com.unionpay" in RecognitionPackages.PAYMENT_PACKAGES)
        assertEquals(
            setOf("com.tencent.mm", "com.eg.android.AlipayGphone"),
            RecognitionPackages.NOTIFICATION_PACKAGES
        )
    }

    @Test
    fun 分档集合关系正确() {
        assertEquals(
            "购物全集 = 核心档 + 降级档",
            RecognitionPackages.SHOPPING_PACKAGES_CORE + RecognitionPackages.SHOPPING_PACKAGES_RESULT_ONLY,
            RecognitionPackages.SHOPPING_PACKAGES
        )
        assertTrue(
            "支付包与购物包不得相交",
            RecognitionPackages.PAYMENT_PACKAGES.intersect(RecognitionPackages.SHOPPING_PACKAGES).isEmpty()
        )
        assertTrue(
            "通知解析包是支付包的真子集（云闪付无通知解析规则，不得混入）",
            RecognitionPackages.NOTIFICATION_PACKAGES.all { it in RecognitionPackages.PAYMENT_PACKAGES } &&
                "com.unionpay" !in RecognitionPackages.NOTIFICATION_PACKAGES
        )
    }

    @Test
    fun 降级档购物包不进结算页观察腿() {
        val result = detector.detect(observation("com.shizhuang.duapp"))
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.RESULT_PAGE_ONLY_PACKAGE),
            result
        )
    }

    @Test
    fun 降级档购物包仍属购物包() {
        // 降级档必须进购物分支（站内结果页扫描腿的前提），只是不进结算观察腿。
        assertTrue(detector.supportsShoppingPackage("com.achievo.vipshop"))
        assertTrue(detector.supportsShoppingPackage("com.suning.mobile.ebuy"))
    }

    @Test
    fun 核心档购物包结算页行为不变() {
        val result = detector.detect(observation("com.jingdong.app.mall"))
        assertTrue("核心包结算页仍应放行", result is CheckoutPageDetection.Accepted)
    }

    @Test
    fun 非白名单包仍被拒为不支持() {
        val result = detector.detect(observation("com.example.unknown"))
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.UNSUPPORTED_SHOPPING_PACKAGE),
            result
        )
    }

    @Test
    fun 支付包含云闪付且不含购物包() {
        assertTrue(detector.supportsPaymentPackage("com.unionpay"))
        assertTrue(detector.supportsPaymentPackage("com.tencent.mm"))
        assertFalse(detector.supportsPaymentPackage("com.xunmeng.pinduoduo"))
    }

    @Test
    fun 通知解析白名单从单一出处派生() {
        assertEquals(RecognitionPackages.NOTIFICATION_PACKAGES, PaymentResultNormalizer.SUPPORTED_PACKAGES)
        assertEquals(RecognitionPackages.SHOPPING_PACKAGES, DEFAULT_SUPPORTED_SHOPPING_PACKAGES)
        assertEquals(RecognitionPackages.PAYMENT_PACKAGES, DEFAULT_SUPPORTED_PAYMENT_PACKAGES)
    }
}
