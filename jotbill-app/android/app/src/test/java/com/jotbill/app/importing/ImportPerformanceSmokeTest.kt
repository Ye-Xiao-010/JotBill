package com.jotbill.app.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 阶段七 T4：1 万行合成样本解析性能冒烟。
 *
 * 本测试是开发机 JVM 抽样，只证明解析器吞吐与复杂度，不作为真机性能证据；
 * 真机 1 万行导入 < 30 秒由阶段七 T8/T9 采集验收。
 */
class ImportPerformanceSmokeTest {
    @Test
    fun parseTenThousandWechatRowsWithinThirtySeconds() {
        val file = File.createTempFile("wechat-perf", ".csv")
        val header = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"
        val builder = StringBuilder("微信支付账单明细,,,,,,,,,,\n").append(header)
        for (index in 1..10_000) {
            builder.append("2024-01-01 00:00:00,商户消费,商户$index,商品$index,支出,\"¥1,234.56\",零钱,支付成功,420000000120240101$index,M$index,\n")
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
        val startedAt = System.nanoTime()
        val result = WechatBillAdapter.parse(file, "UTF-8")
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals(10_000, result.validRecords)
        assertEquals(0, result.errors.size)
        assertTrue("开发机 1 万行解析耗时 $elapsedMillis ms，超过 30 秒冒烟上限", elapsedMillis < 30_000L)
    }
}
