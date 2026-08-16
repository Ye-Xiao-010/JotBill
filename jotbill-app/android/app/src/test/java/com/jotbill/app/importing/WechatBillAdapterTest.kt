package com.jotbill.app.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WechatBillAdapterTest {
    @Test
    fun parsesStandardHeaderAndFiveSemanticTypes() {
        val file = File.createTempFile("wechat", ".csv")
        file.writeText(
            "微信支付账单明细,,,,,,,,,,\n" +
                "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n" +
                "2024-01-02 10:30:00,商户消费,测试商户,午餐,支出,\"¥1,234.56\",零钱,支付成功,4200000001202401020000000001,M2024010200001,\n" +
                "2024/01/02 10:31,转账,张三,零钱提现,不计收支,\"¥100.00\",零钱,支付成功,4200000001202401020000000002,,备注\n" +
                "2024-01-02 10:32,信用卡还款,招商银行信用卡,,不计收支,\"¥500.00\",招商银行,支付成功,4200000001202401020000000003,,自动还款\n" +
                "2024-01-02 10:33,退款,测试商户,午餐退款,收入,\"¥20.00\",零钱,已全额退款,4200000001202401020000000004,M2024010200001,\n" +
                "2024-01-02 10:34,提现手续费,,,支出,\"¥0.10\",零钱,支付成功,4200000001202401020000000005,,提现费\n"
        )
        val result = WechatBillAdapter.parse(file, "UTF-8")
        assertEquals(5, result.rows.size)
        assertEquals(5, result.validRecords)
        assertEquals(0, result.errors.size)
        assertEquals(123456L, result.rows[0].amountMinor)
        assertEquals("expense", result.rows[0].type)
        assertEquals("transfer", result.rows[1].type)
        assertEquals("repayment", result.rows[2].type)
        assertEquals("refund", result.rows[3].type)
        assertEquals("fee", result.rows[4].type)
        assertTrue(result.rows[3].remark!!.contains("状态:已全额退款"))
    }

    @Test
    fun filtersSummaryAndTotalRows() {
        val file = File.createTempFile("wechat-summary", ".csv")
        file.writeText(
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n" +
                "2024-01-02 10:30:00,商户消费,测试商户,午餐,支出,1.00,零钱,支付成功,4200000001202401020000000001,,,\n" +
                "共1笔,,,,,,,,,,\n" +
                "合计,1.00,,,,,,,,,\n"
        )
        val result = WechatBillAdapter.parse(file, "UTF-8")
        assertEquals(1, result.rows.size)
        assertEquals(3, result.totalRecords)
        assertEquals(2, result.filteredRecords)
    }
}
