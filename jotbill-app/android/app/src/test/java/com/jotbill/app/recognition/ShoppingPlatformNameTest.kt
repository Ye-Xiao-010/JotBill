package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 购物平台名映射测试。
 *
 * 真机故障回归：账单 platform 此前取自 AI 视觉识别，模型把它读成
 * "手机应用"这类无意义文字，导致账单详情页"来源"无法体现购物平台，
 * 实际显示为"手机应用 / 金桂白牡丹 / 25g*10盒"（平台+商品品牌+规格）。
 *
 * 修复后 platform 一律由系统提供的购物包名推导，不再采用 AI 返回值。
 */
class ShoppingPlatformNameTest {

    @Test
    fun 已收录购物包名映射为中文平台名() {
        assertEquals("拼多多", shoppingPlatformName("com.xunmeng.pinduoduo"))
        assertEquals("淘宝", shoppingPlatformName("com.taobao.taobao"))
        assertEquals("天猫", shoppingPlatformName("com.tmall.wireless"))
        assertEquals("京东", shoppingPlatformName("com.jingdong.app.mall"))
        assertEquals("美团", shoppingPlatformName("com.sankuai.meituan"))
        assertEquals("滴滴", shoppingPlatformName("com.sdu.didi.psnger"))
        assertEquals("抖音", shoppingPlatformName("com.ss.android.ugc.aweme"))
        assertEquals("小红书", shoppingPlatformName("com.xingin.xhs"))
    }

    @Test
    fun 支付应用包名不被当作购物平台() {
        assertNull(shoppingPlatformName("com.tencent.mm"))
        assertNull(shoppingPlatformName("com.eg.android.AlipayGphone"))
    }

    @Test
    fun 空值与未收录包名返回null() {
        assertNull(shoppingPlatformName(null))
        assertNull(shoppingPlatformName(""))
        assertNull(shoppingPlatformName("   "))
        assertNull(shoppingPlatformName("com.unknown.shop"))
    }

    @Test
    fun 包名两端空白被容忍() {
        assertEquals("拼多多", shoppingPlatformName("  com.xunmeng.pinduoduo  "))
    }
}
