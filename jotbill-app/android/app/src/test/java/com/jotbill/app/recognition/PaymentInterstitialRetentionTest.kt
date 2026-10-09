package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付中间页挂起判定的回归测试。
 *
 * 京东真机故障时序：
 * - 10:54:27 结算快照写入，同包中间页触发预交接，
 *   交接栅栏正确设置（current=3c91b93b, interstitial=true, retainForHandoff=true）；
 * - 10:54:57 同包内又一个非结算页事件到达，此时预交接已把 activeCheckout 置空，
 *   旧判定要求 current != null 因而落空（current=null, retained=3c91b93b,
 *   interstitial=false, retainForHandoff=false），唯一快照在 consumed=false
 *   的状态下被清空；
 * - 10:55:10 支付交接时槽位已空，交接栅栏设置失败，支付证据落孤儿待确认。
 *
 * 修复方式是让中间页判定同时接受已挂起会话的包名。本测试锁定该判定的边界：
 * 既要在挂起期间保留快照，也不能把"真正离开购物应用"误判为中间页。
 */
class PaymentInterstitialRetentionTest {

    private val jd = "com.jingdong.app.mall"
    private val pdd = "com.xunmeng.pinduoduo"

    /**
     * 预交接挂起期间，同包非结算页事件必须继续按中间页处理。
     *
     * 这是本次修复的核心场景：handoffSourcePackage 来自已挂起会话而非当前会话。
     */
    @Test
    fun 挂起期间同包非结算页仍判定为中间页() {
        assertTrue(
            "activeCheckout 已置空时应回退到挂起会话的包名，否则快照会被清空",
            isSamePackagePaymentInterstitial(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = jd,
                handoffSourcePackage = jd
            )
        )
    }

    /**
     * 跨包的非结算页事件不是中间页，快照应被清空以免旧图误用。
     */
    @Test
    fun 跨购物包的非结算页不判定为中间页() {
        assertFalse(
            "从京东会话切到拼多多页面属于真正离开，不能保留旧快照",
            isSamePackagePaymentInterstitial(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = pdd,
                handoffSourcePackage = jd
            )
        )
    }

    /**
     * 既无当前会话也无挂起会话时不得判定为中间页。
     *
     * 此时没有任何快照需要保留，判定为中间页只会掩盖状态异常。
     */
    @Test
    fun 无会话来源时不判定为中间页() {
        assertFalse(
            isSamePackagePaymentInterstitial(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = jd,
                handoffSourcePackage = null
            )
        )
    }

    /**
     * 失效包名缺失时不得判定为中间页。
     *
     * 空包名无法证明仍停留在同一购物应用内，放行会让快照在用户已离开后滞留。
     */
    @Test
    fun 失效包名缺失时不判定为中间页() {
        assertFalse(
            isSamePackagePaymentInterstitial(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = null,
                handoffSourcePackage = jd
            )
        )
        assertFalse(
            "空白包名与空白挂起包名不得相等匹配",
            isSamePackagePaymentInterstitial(
                reason = CheckoutInvalidationReason.NON_CHECKOUT_PAGE,
                invalidatedPackage = "   ",
                handoffSourcePackage = "   "
            )
        )
    }

    /**
     * 只有 NON_CHECKOUT_PAGE 才可能是中间页。
     *
     * 离开购物应用、敏感页、节点树不可用等原因必须保持原有清空行为，
     * 否则敏感页快照会被保留、跨应用旧图会被误用。
     */
    @Test
    fun 其他失效原因不判定为中间页() {
        val otherReasons = listOf(
            CheckoutInvalidationReason.LEFT_SUPPORTED_SHOPPING_APP,
            CheckoutInvalidationReason.SENSITIVE_PAGE,
            CheckoutInvalidationReason.NODE_TREE_UNAVAILABLE,
            CheckoutInvalidationReason.SERVICE_INTERRUPTED,
            CheckoutInvalidationReason.SERVICE_DESTROYED,
            CheckoutInvalidationReason.PAYMENT_APP_HANDOFF
        )
        otherReasons.forEach { reason ->
            assertFalse(
                "${reason.wireValue} 不应被判定为同包支付中间页",
                isSamePackagePaymentInterstitial(
                    reason = reason,
                    invalidatedPackage = jd,
                    handoffSourcePackage = jd
                )
            )
        }
    }
}
