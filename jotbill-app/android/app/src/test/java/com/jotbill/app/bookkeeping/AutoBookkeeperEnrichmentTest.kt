package com.jotbill.app.bookkeeping

import com.jotbill.app.data.entity.BillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 二次抽取字段回填的合并决策不变式：**只补空缺，绝不覆盖非空字段**，
 * 以及 8 渠道标注完整性（渠道/商户识别复查的钉子）。
 */
class AutoBookkeeperEnrichmentTest {

    private fun bill(
        merchant: String? = null,
        orderId: String? = null,
        categoryId: String? = null
    ): BillEntity = BillEntity(
        id = "bill-1",
        amountMinor = 1234L,
        type = "expense",
        occurredAt = 1_000L,
        description = "支付宝支出",
        categoryId = categoryId,
        accountId = "default-account-alipay",
        targetAccountId = null,
        platform = "支付宝",
        merchant = merchant,
        productDescription = null,
        sourceType = "accessibility",
        originalTransactionId = null,
        orderId = orderId,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        version = 1
    )

    @Test
    fun `空缺字段被回填`() {
        val fills = AutoBookkeeper.computeFieldFills(
            existing = bill(merchant = null, orderId = null, categoryId = null),
            merchant = "某某超市",
            orderId = "202610250001",
            categoryId = "default-category-expense-food"
        )
        assertEquals("某某超市", fills.merchant)
        assertEquals("202610250001", fills.orderId)
        assertEquals("default-category-expense-food", fills.categoryId)
    }

    @Test
    fun `非空字段绝不被覆盖`() {
        val fills = AutoBookkeeper.computeFieldFills(
            existing = bill(
                merchant = "用户已改名的商户",
                orderId = "已有订单号",
                categoryId = "default-category-expense-shopping"
            ),
            merchant = "页面抽取的商户",
            orderId = "新订单号",
            categoryId = "default-category-expense-food"
        )
        assertNull(fills.merchant)
        assertNull(fills.orderId)
        assertNull(fills.categoryId)
    }

    @Test
    fun `二次抽取缺字段时只补有的`() {
        val fills = AutoBookkeeper.computeFieldFills(
            existing = bill(merchant = "已有商户"),
            merchant = null,
            orderId = "NEW-1",
            categoryId = null
        )
        assertNull(fills.merchant)
        assertEquals("NEW-1", fills.orderId)
        assertNull(fills.categoryId)
    }

    @Test
    fun `八个支付渠道全部有名字`() {
        val labels = listOf(
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.unionpay",
            "com.jingdong.app.mall",
            "com.sankuai.meituan",
            "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme",
            "com.android.mms"
        ).map { AutoBookkeeper.channelLabel(it) }
        assertEquals(
            listOf("支付宝", "微信支付", "云闪付", "京东", "美团", "拼多多", "抖音", "银行短信"),
            labels
        )
        // 未知包绝不能显示成某个具体渠道
        assertEquals("自动记账", AutoBookkeeper.channelLabel("com.unknown.app"))
    }
}
