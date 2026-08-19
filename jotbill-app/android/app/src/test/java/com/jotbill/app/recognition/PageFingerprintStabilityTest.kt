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
}
