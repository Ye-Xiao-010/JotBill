package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面指纹稳定性测试。
 *
 * 真机上拼多多结算页用 ViewSwitcher 轮播"N 人已拼"等动态文案，
 * 这类文本进入指纹会每隔数秒生成新哈希，被下游误判为新订单并重复截图。
 */
class PageFingerprintStabilityTest {

    @Test
    fun 轮播容器按简单类名识别() {
        assertTrue(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "android.widget.ViewSwitcher"
            )
        )
        assertTrue(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "android.widget.ViewFlipper"
            )
        )
        assertTrue(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "android.widget.TextSwitcher"
            )
        )
    }

    @Test
    fun 普通容器与文本控件不被误判为轮播() {
        assertFalse(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "android.widget.FrameLayout"
            )
        )
        assertFalse(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "android.widget.TextView"
            )
        )
        assertFalse(
            AccessibilityPageSummaryCollector.isRotatingWidgetClass(
                "androidx.recyclerview.widget.RecyclerView"
            )
        )
    }

    @Test
    fun 空类名不触发轮播判定() {
        assertFalse(AccessibilityPageSummaryCollector.isRotatingWidgetClass(null))
        assertFalse(AccessibilityPageSummaryCollector.isRotatingWidgetClass(""))
        assertFalse(AccessibilityPageSummaryCollector.isRotatingWidgetClass("   "))
    }

    // ---------- Y3 商品规格选择层判定（2026-09-10 真机取证；同日回归修正） ----------

    /** 拼多多商品规格弹层真机 dump 文本（.tmp-ui1.xml）：应命中规格层判定。 */
    @Test
    fun 商品规格弹层真机文本命中判定() {
        // 数量增减成对出现（规格选择态，无提交订单按钮）
        assertTrue(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "已选： 新鲜采摘 净重5斤【50-55mm】 减少数量 1 增加数量 平台大促已直降7.63元 现在买，仅 ¥7.77",
                hasCheckoutActionSignal = false
            )
        )
        // 仅规格引导词
        assertTrue(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "已选： 新鲜采摘 净重5斤",
                hasCheckoutActionSignal = false
            )
        )
        // 半角冒号变体
        assertTrue(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "已选: 白色 XL",
                hasCheckoutActionSignal = false
            )
        )
    }

    /**
     * 【回归修正 2026-09-10 14:08】真结算页不得被规格层判据误杀。
     *
     * 拼多多"确认订单"结算层与规格层共用 wd2.f0 窗口且同样携带"已选："与
     * 数量对文案——若规格层判据独立生效，真结算页被归 PRODUCT_DETAIL，
     * 自动截图链路全断（零 ACCEPTED，支付落孤立证据 CHECKOUT_REFERENCE_
     * INVALID）。动作信号（提交订单按钮）是结算层铁证，并存时结算层优先。
     */
    @Test
    fun 真结算页带规格文案时不被误杀() {
        // 14:08:02.248 真机帧形态：金额+动作双真 + "已选："文案共存
        assertFalse(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "已选： 新鲜采摘 净重5斤 合计 ¥79.00 提交订单 收货地址",
                hasCheckoutActionSignal = true
            )
        )
        // 数量对与提交订单共存（部分结算层保留数量入口）同样不排除
        assertFalse(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "减少数量 1 增加数量 合计 ¥79.00 确认订单",
                hasCheckoutActionSignal = true
            )
        )
    }

    /** 结算页特征文本不得被误判为规格层（护栏：真结算页必须保留 CHECKOUT 通道）。 */
    @Test
    fun 结算页文本不误判为规格层() {
        // 真结算页：有金额标签与提交动作，无数量增减对、无规格引导词
        assertFalse(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "实付金额 ¥55.70 提交订单 收货地址 浙江省宁波市 微信支付",
                hasCheckoutActionSignal = true
            )
        )
        // 单侧"减少数量"不构成证据（避免结算页"再买N件"类文案误伤）
        assertFalse(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "实付金额 ¥55.70 提交订单 减少：满减优惠",
                hasCheckoutActionSignal = true
            )
        )
        // 裸"已选"不带冒号不构成证据
        assertFalse(
            AccessibilityPageSummaryCollector.isProductSpecSelectionLayer(
                "实付 ¥55.70 已选购1件 提交订单",
                hasCheckoutActionSignal = true
            )
        )
    }
}
