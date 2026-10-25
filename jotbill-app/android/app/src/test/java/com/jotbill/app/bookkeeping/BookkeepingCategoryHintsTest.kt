package com.jotbill.app.bookkeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内置分类词典：关键词命中、收入语义直判、保守落空三条路径。 */
class BookkeepingCategoryHintsTest {

    @Test
    fun `商户关键词命中对应支出分类`() {
        assertEquals(
            "default-category-expense-food",
            BookkeepingCategoryHints.guess("美团外卖", "支付宝", null, "expense")
        )
        assertEquals(
            "default-category-expense-transport",
            BookkeepingCategoryHints.guess("滴滴出行", "微信支付", null, "expense")
        )
        assertEquals(
            "default-category-expense-shopping",
            BookkeepingCategoryHints.guess(null, null, "拼多多-多多支付收银台", "expense")
        )
    }

    @Test
    fun `收入按规则语义直判`() {
        assertEquals(
            "default-category-income-red-packet",
            BookkeepingCategoryHints.guess("张三", "微信支付", "微信-确认收红包结果页（收入）", "income")
        )
        assertEquals(
            "default-category-income-other",
            BookkeepingCategoryHints.guess("李四", "微信支付", "微信-确认收转账结果页（收入-金额在后）", "income")
        )
    }

    @Test
    fun `无法判断时保守落空不猜`() {
        assertNull(BookkeepingCategoryHints.guess("某个体户", "支付宝", "支付宝-支付结果页", "expense"))
        assertNull(BookkeepingCategoryHints.guess(null, null, null, "expense"))
    }

    @Test
    fun `平台渠道名本身不触发购物分类`() {
        // "支付宝"不是购物关键词：纯渠道支付（无商户词）必须落未分类而不是误判
        assertNull(BookkeepingCategoryHints.guess(null, "支付宝", "支付宝-支付结果页", "expense"))
        assertNull(BookkeepingCategoryHints.guess(null, "云闪付", "云闪付-支付结果页", "expense"))
    }
}
