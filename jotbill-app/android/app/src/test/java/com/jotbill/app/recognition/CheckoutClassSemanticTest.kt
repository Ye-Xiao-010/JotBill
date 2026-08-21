package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口类名结算语义的回归测试。
 *
 * 京东真机故障：弹窗结算页 PopupNewFillOrderActivity 的底部「立即支付」按钮
 * 间歇上报 isVisibleToUser=false，hasCheckoutActionSignal 随之丢失；页面又含
 * 「猜你喜欢」推荐位，命中 browsingTerms 兜底后被判为 SHOPPING_HOME，
 * 触发 INVALIDATED_NON_CHECKOUT_PAGE 清空刚存入的结算快照，
 * 后续支付证据只能落孤儿待确认。
 *
 * 修复方式是让类名中的 settlement/fillorder 在动作信号缺失时替代该信号。
 * 本测试锁定两条不变量：类名词表的命中范围，以及检测器的放行条件。
 */
class CheckoutClassSemanticTest {

    private val detector = CheckoutPageDetector()
    private val validFingerprint = "a".repeat(64)

    /**
     * 构造一个观察对象，默认满足除动作信号外的全部结算条件。
     *
     * 真机抖动样本的共同特征是 amountSignal=true、sensitive=false，
     * 仅 actionSignal 缺失，因此默认值按该场景设置。
     */
    private fun observation(
        hasCheckoutActionSignal: Boolean,
        hasCheckoutClassSemantic: Boolean,
        pageRole: CheckoutPageRole = CheckoutPageRole.CHECKOUT,
        hasFinalAmountSignal: Boolean = true
    ): CheckoutPageObservation = CheckoutPageObservation(
        shoppingPackage = "com.jingdong.app.mall",
        pageRole = pageRole,
        pageFingerprint = validFingerprint,
        hasFinalAmountSignal = hasFinalAmountSignal,
        hasCheckoutActionSignal = hasCheckoutActionSignal,
        isSensitivePage = false,
        hasCheckoutClassSemantic = hasCheckoutClassSemantic
    )

    /**
     * 京东弹窗结算页的真实类名必须命中结算语义。
     */
    @Test
    fun 京东弹窗结算页类名命中结算语义() {
        assertTrue(
            "settlement/fillorder 是京东结算模块的稳定类名段，必须命中",
            AccessibilityPageSummaryCollector.hasCheckoutClassSemantic(
                "com.jd.lib.settlement.fillorder.activity.popupnewfillorderactivity"
            )
        )
    }

    /**
     * 订单列表与订单详情页不得命中结算语义。
     *
     * 这两个页面同样出现在真机日志中，若词表收录 order 单词本身就会被误判为
     * 结算页，导致已完成订单页触发采集。
     */
    @Test
    fun 订单列表与详情页不命中结算语义() {
        assertFalse(
            "订单列表页不是结算页",
            AccessibilityPageSummaryCollector.hasCheckoutClassSemantic(
                "com.jd.lib.ordercenter.taro.orderlistactivitytaro"
            )
        )
        assertFalse(
            "订单详情页不是结算页",
            AccessibilityPageSummaryCollector.hasCheckoutClassSemantic(
                "com.jd.lib.ordercenter.taro.orderdetailactivitytaro"
            )
        )
    }

    /**
     * 微信支付相关页面不得命中结算语义，避免污染支付结果页判定。
     */
    @Test
    fun 支付应用页面不命中结算语义() {
        assertFalse(
            "微信 OrderHandlerUI 属于支付应用页面，不应命中购物结算语义",
            AccessibilityPageSummaryCollector.hasCheckoutClassSemantic(
                "com.tencent.mm.plugin.wallet_index.ui.orderhandlerui"
            )
        )
    }

    /**
     * 空类名不得命中，避免类名读取失败时被当作结算页放行。
     */
    @Test
    fun 空类名不命中结算语义() {
        assertFalse(AccessibilityPageSummaryCollector.hasCheckoutClassSemantic(""))
        assertFalse(AccessibilityPageSummaryCollector.hasCheckoutClassSemantic("   "))
    }

    /**
     * 动作信号缺失但类名命中结算语义时，检测器必须放行。
     *
     * 这是本次修复的核心：若此处仍被拒为 MISSING_CHECKOUT_ACTION，
     * classifyPageRole 判出的 CHECKOUT 会被下游抵消，修复完全失效。
     */
    @Test
    fun 动作信号缺失但类名命中时检测器放行() {
        val detection = detector.detect(
            observation(hasCheckoutActionSignal = false, hasCheckoutClassSemantic = true)
        )
        assertTrue(
            "类名结算语义应替代缺失的动作信号，实际=$detection",
            detection is CheckoutPageDetection.Accepted
        )
    }

    /**
     * 动作信号与类名语义同时缺失时，必须保持原有拒绝行为。
     */
    @Test
    fun 动作信号与类名语义均缺失时仍被拒绝() {
        val detection = detector.detect(
            observation(hasCheckoutActionSignal = false, hasCheckoutClassSemantic = false)
        )
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_CHECKOUT_ACTION),
            detection
        )
    }

    /**
     * 金额信号是必要条件，类名语义不得越过金额门禁。
     *
     * 结算类名下读不到应付金额说明页面尚未渲染完成，此时进入结算态会截到白屏。
     */
    @Test
    fun 类名语义不得越过金额门禁() {
        val detection = detector.detect(
            observation(
                hasCheckoutActionSignal = false,
                hasCheckoutClassSemantic = true,
                hasFinalAmountSignal = false
            )
        )
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.MISSING_FINAL_AMOUNT),
            detection
        )
    }

    /**
     * 类名语义不得越过页面角色门禁。
     *
     * 角色判定是唯一入口，类名语义只在角色已为 CHECKOUT 时起补充作用。
     */
    @Test
    fun 类名语义不得越过页面角色门禁() {
        val detection = detector.detect(
            observation(
                hasCheckoutActionSignal = false,
                hasCheckoutClassSemantic = true,
                pageRole = CheckoutPageRole.SHOPPING_HOME
            )
        )
        assertEquals(
            CheckoutPageDetection.Rejected(CheckoutDetectionRejection.NOT_CHECKOUT_PAGE),
            detection
        )
    }
}
