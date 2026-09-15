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

    @Test
    fun parsesXlsxWithSerialDatesAndSkipsNeutralZeroAmount() {
        val file = File.createTempFile("wechat", ".xlsx")
        // 微信官方 XLSX：前置说明区 17 行，表头第 18 行，交易时间列为 Excel 序列数
        val shared = mutableListOf<String>()
        fun str(value: String): XlsxCell {
            shared += value
            return XlsxCell.Shared(shared.size - 1)
        }

        fun num(value: String) = XlsxCell.Number(value)

        val headerCells = listOf(
            "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
            "支付方式", "当前状态", "交易单号", "商户单号", "备注"
        ).map { str(it) }
        val rows = buildList {
            repeat(17) { add(listOf(str("微信支付账单明细说明${it + 1}"))) }
            add(headerCells)
            // 序列 45293.4375 = 2024-01-02 10:30:00（Excel 1900 日期系统）
            add(
                listOf(
                    num("45293.4375"), str("商户消费"), str("测试商户"), str("午餐"), str("支出"),
                    num("12.00"), str("零钱"), str("支付成功"), str("4200000001202401020000000001"),
                    str("M2024010200001"), str("")
                )
            )
            add(
                listOf(
                    num("45293.4513"), str("转账"), str(""), str("零钱通转入"), str("/"),
                    num("0.00"), str("零钱通"), str("支付成功"), str("4200000001202401020000000002"),
                    str(""), str("")
                )
            )
            add(
                listOf(
                    str("2024-01-02 10:32:00"), str("商户消费"), str("测试商户2"), str("晚餐"), str("收入"),
                    num("8.00"), str("零钱"), str("支付成功"), str("4200000001202401020000000003"),
                    str("M2024010200003"), str("")
                )
            )
        }
        writeXlsxFixture(file, shared, rows)

        val result = WechatBillAdapter.parse(file, null)
        assertEquals(0, result.errors.size)
        assertEquals(2, result.rows.size)
        assertEquals("0.00 中性零钱通行应计 skipped 而非解析错误", 1, result.skippedRecords)
        val expectedAt = java.time.LocalDateTime.of(2024, 1, 2, 10, 30, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("序列数时间应换算为本地毫秒", expectedAt, result.rows[0].occurredAt)
        assertEquals("expense", result.rows[0].type)
        assertEquals(1200L, result.rows[0].amountMinor)
        assertEquals("income", result.rows[1].type)
        assertEquals(
            "文本时间行仍走格式化器路径",
            java.time.LocalDateTime.of(2024, 1, 2, 10, 32, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            result.rows[1].occurredAt
        )
    }

    private sealed interface XlsxCell {
        data class Shared(val index: Int) : XlsxCell
        data class Number(val raw: String) : XlsxCell
    }

    /** 构造 XlsxTableReader 可读的最小 XLSX（ZIP + OOXML 四件套）。 */
    private fun writeXlsxFixture(file: File, shared: List<String>, rows: List<List<XlsxCell>>) {
        fun columnRef(row: Int, col: Int): String {
            var n = col
            var letters = ""
            do {
                letters = ('A' + n % 26) + letters
                n = n / 26 - 1
            } while (n >= 0)
            return "$letters${row + 1}"
        }

        fun xmlEscape(value: String) = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>"""
            )
            entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )
            entry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
            )
            entry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>"""
            )
            val sharedXml = if (shared.isEmpty()) {
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>"""
            } else {
                shared.joinToString("") { "<si><t>${xmlEscape(it)}</t></si>" }
                    .let { """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${shared.size}" uniqueCount="${shared.size}">$it</sst>""" }
            }
            entry(
                "xl/sharedStrings.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>$sharedXml"""
            )
            val sheetXml = rows.mapIndexed { rowIndex, row ->
                val cells = row.mapIndexed { col, cell ->
                    val ref = columnRef(rowIndex + 1, col)
                    when (cell) {
                        is XlsxCell.Shared -> """<c r="$ref" t="s"><v>${cell.index}</v></c>"""
                        is XlsxCell.Number -> """<c r="$ref"><v>${cell.raw}</v></c>"""
                    }
                }.joinToString("")
                """<row r="${rowIndex + 1}">$cells</row>"""
            }.joinToString("")
            entry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheetXml</sheetData></worksheet>"""
            )
        }
    }
}
