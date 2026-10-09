package com.jotbill.app.recognition

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结算页手动补截兜底能力的回归测试。
 *
 * 真机数据（.tmp-device-capture-20260822-214140）显示结算态存活极短：
 * 京东 a23f589b 仅 469ms、e3b01a29 为 5566ms，拼多多 813ed186 为 2067ms。
 * 结算态转入支付交接挂起后 activeCheckout 被置空，用户在结算页上的点击
 * 连续 14 次返回 NO_ACTIVE_CHECKOUT，"手动截图上下文缺失"同时出现。
 *
 * 根因是服务在 CheckoutPageDetection.Accepted 分支调用
 * clearUserRetryCaptureContext()，在最需要手动补截的页面上移除了兜底能力。
 * 修复改为 setUserRetryCaptureContext(observation)。
 *
 * 本测试锁定该修复依赖的前提：CHECKOUT 角色必须被允许承载手动重试快照，
 * 且结算页观察必须能构成完整的手动截图上下文。
 */
class CheckoutManualRetryFallbackTest {

    private val validFingerprint = "b".repeat(64)

    /**
     * 构造一个通过结算门禁的页面观察。
     */
    private fun checkoutObservation(
        isSensitivePage: Boolean = false
    ): CheckoutPageObservation = CheckoutPageObservation(
        shoppingPackage = "com.jingdong.app.mall",
        pageRole = CheckoutPageRole.CHECKOUT,
        pageFingerprint = validFingerprint,
        hasFinalAmountSignal = true,
        hasCheckoutActionSignal = true,
        isSensitivePage = isSensitivePage,
        hasCheckoutClassSemantic = true
    )

    /**
     * 结算页角色必须允许承载手动重试快照。
     *
     * 若 CHECKOUT 被排除在允许集合外，setUserRetryCaptureContext 会因
     * hasSafeDisplayContext() 为 false 而立即清空上下文，修复将完全失效。
     */
    @Test
    fun 结算页角色允许承载手动重试快照() {
        assertTrue(
            "CHECKOUT 必须在手动重试允许集合内，否则结算页无法手动补截",
            CheckoutPageRole.CHECKOUT.canBufferUserRetrySnapshot()
        )
    }

    /**
     * 结算页观察必须能构成完整可用的手动截图上下文。
     */
    @Test
    fun 结算页观察可构成完整手动截图上下文() {
        val context = UserRetryCaptureContext(observation = checkoutObservation())
        assertTrue("结算页上下文应具备安全展示条件", context.hasSafeDisplayContext())
        assertTrue("结算页上下文应为完整可用状态", context.isComplete())
    }

    /**
     * 敏感结算页不得构成手动截图上下文。
     *
     * 手动补截能力的放开不能削弱敏感页禁截这条红线。
     */
    @Test
    fun 敏感结算页不得构成手动截图上下文() {
        val context = UserRetryCaptureContext(
            observation = checkoutObservation(isSensitivePage = true)
        )
        assertTrue(
            "敏感页必须被排除在手动截图之外",
            !context.hasSafeDisplayContext() && !context.isComplete()
        )
    }
}
