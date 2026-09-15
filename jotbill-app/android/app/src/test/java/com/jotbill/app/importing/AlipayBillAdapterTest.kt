package com.jotbill.app.importing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlipayBillAdapterTest {
    @Test
    fun parsesCsvTypesAndSkipsClosedTrades() {
        val file = File.createTempFile("alipay", ".csv")
        file.writeText(
            "支付宝交易流水明细,,,,,,,,,,,,,,,\n" +
                "交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态\n" +
                "20240102000000000001,M2024010200001,2024-01-02 10:30:00,2024-01-02 10:30:01,2024-01-02 10:30:01,其他,即时到账交易,测试商户,午餐,\"1,234.56\",支出,交易成功,0.00,0.00,,已支出\n" +
                "20240102000000000002,,2024-01-02 10:31:00,2024-01-02 10:31:01,,账户,转账,张三,余额宝-单次转入,100.00,不计收支,交易成功,0.00,0.00,,已转移\n" +
                "20240102000000000003,,2024-01-02 10:32:00,2024-01-02 10:32:01,,账户,信用卡还款,招商银行信用卡,,500.00,不计收支,交易成功,0.00,0.00,,还款\n" +
                "20240102000000000004,,2024-01-02 10:33:00,2024-01-02 10:33:01,,交易,退款,测试商户,午餐退款,20.00,不计收支,退款成功,0.00,20.00,,退款\n" +
                "20240102000000000005,,2024-01-02 10:34:00,2024-01-02 10:34:01,,交易,提现,测试商户,提现费,0.10,支出,交易成功,0.10,0.00,,手续费\n" +
                "20240102000000000006,,2024-01-02 10:35:00,2024-01-02 10:35:01,,交易,即时到账交易,测试商户,未付款,20.00,支出,交易关闭,0.00,0.00,,\n"
        )
        val result = AlipayBillAdapter.parse(file, "UTF-8")
        assertEquals(5, result.rows.size)
        assertEquals(1, result.skippedRecords)
        assertEquals(0, result.errors.size)
        assertEquals(123456L, result.rows[0].amountMinor)
        assertEquals("expense", result.rows[0].type)
        assertEquals("transfer", result.rows[1].type)
        assertEquals("repayment", result.rows[2].type)
        assertEquals("refund", result.rows[3].type)
        assertEquals("fee", result.rows[4].type)
    }

    @Test
    fun parses2026ReceiptHeaderBeyondTwentyLines() {
        val file = File.createTempFile("alipay-v2", ".csv")
        // 2026 电子客户回单：表头不在首行（约第 24 行），无独立交易号/付款时间列
        val preamble = (1..23).joinToString("") { "支付宝说明行$it,,,,\n" }
        file.writeText(
            preamble +
                "------------------------支付宝科技有限公司  电子客户回单------------------------,,\n" +
                "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,\n" +
                "2026-08-29 17:42:23,餐饮美食,测试商户,merchant@a.com,商品说明A,支出,12.00,余额,交易成功,TD20260829123456,MO20260829001,备注A,\n" +
                "2026-08-28 22:54:52,交通出行,测试商户2,merchant2@a.com,地铁出行,收入,5.00,余额,交易成功,TD20260828123456,MO20260828001,,\n" +
                "2026-08-27 10:00:00,其他,测试商户3,merchant3@a.com,冻结,/,0.00,余额,交易成功,TD20260827123456,, ,\n"
        )
        val result = AlipayBillAdapter.parse(file, "UTF-8")
        assertEquals(0, result.errors.size)
        assertEquals(2, result.rows.size)
        assertEquals("0.00 冻结行应计 skipped 而非解析错误", 1, result.skippedRecords)
        assertEquals("expense", result.rows[0].type)
        assertEquals("交易订单号应充作交易号", "TD20260829123456", result.rows[0].transactionId)
        assertEquals("商家订单号应作合并键", "MO20260829001", result.rows[0].merchantOrderId)
        assertEquals("income", result.rows[1].type)
    }
}
