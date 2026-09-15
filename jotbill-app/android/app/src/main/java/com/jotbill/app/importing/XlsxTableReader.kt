package com.jotbill.app.importing

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.roundToLong

/**
 * 阶段六 T4：XLSX 轻量读取器（docs/tdd/10_phase6_tasks.md 第 3.8 节）。
 *
 * XLSX 即 ZIP + XML，本类只读取首个 worksheet 与 sharedStrings：
 * - worksheet 流式逐行解析，不在内存整表展开；
 * - sharedStrings 预读为字符串表（仅索引表，非整表数据）；
 * - 数值单元格、共享字符串、内联字符串均兼容；日期序列数由适配器换算。
 */
internal object XlsxTableReader {

    private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    private val EXCEL_SERIAL_PATTERN = Regex("""^[0-9]+(?:\.[0-9]+)?$""")

    fun readRows(
        file: File,
        onRow: (rowNumber: Int, columns: List<String>) -> Boolean
    ): Int {
        var rowsRead = 0
        var rowNumber = 0
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val sheetEntry = findFirstSheet(zip)
                ?: throw IllegalArgumentException("XLSX 中找不到工作表")
            val parser = newParser()
            zip.getInputStream(sheetEntry).use { input ->
                parser.setInput(input, "UTF-8")
                var currentRowColumns = mutableListOf<String>()
                var currentRowCells = mutableListOf<Pair<Int, String>>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "row" -> {
                                    currentRowCells = mutableListOf()
                                }
                                "c" -> {
                                    val reference = parser.getAttributeValue(null, "r")
                                    val cellType = parser.getAttributeValue(null, "t")
                                    val value = readCellValue(parser, cellType, sharedStrings)
                                    val column = reference?.let(::columnIndex) ?: (currentRowCells.size)
                                    if (value.isNotEmpty()) {
                                        currentRowCells += column to value
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "row") {
                                rowNumber += 1
                                val width = (currentRowCells.maxOfOrNull { it.first } ?: -1) + 1
                                currentRowColumns = MutableList(width.coerceAtLeast(0)) { "" }
                                currentRowCells.forEach { (column, value) -> currentRowColumns[column] = value }
                                if (!onRow(rowNumber, currentRowColumns)) {
                                    rowsRead = rowNumber
                                    return@use
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            rowsRead = rowNumber
        }
        return rowsRead
    }

    private fun readCellValue(
        parser: XmlPullParser,
        cellType: String?,
        sharedStrings: List<String>
    ): String {
        var value = ""
        var depth = 0
        var event = parser.eventType
        val startedDepth = parser.depth
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth += 1
                    when (parser.name) {
                        "v" -> {
                            value = parser.nextText()
                            return when (cellType) {
                                "s" -> sharedStrings.getOrElse(value.toIntOrNull() ?: -1) { "" }
                                "inlineStr" -> value
                                else -> value
                            }
                        }
                        "t" -> {
                            value = parser.nextText()
                            return value
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth -= 1
                    if (parser.name == "c" && depth <= startedDepth) break
                }
            }
            event = parser.next()
        }
        return value
    }

    /**
     * Excel 1900 日期系统序列数 → epoch millis（非纯数字返回 null）。
     *
     * 闰年 bug 口径：Excel 误认为 1900 年是闰年（序列 60 是虚构的 1900-02-29）。
     * 锚定 1899-12-30：序列 ≥ 61 时日期 = 1899-12-30 + 序列 天（虚构日已被该锚点
     * 吸收，如序列 36526 = 2000-01-01）；序列 ≤ 59 需再 +1 天（1900-01-01 =
     * 1899-12-31 + 1）；序列 60 无对应真实日期。不得再额外减 1，否则全天日期提前。
     */
    fun excelSerialToEpochMillis(text: String): Long? {
        if (!text.matches(EXCEL_SERIAL_PATTERN)) return null
        val serial = text.toDoubleOrNull() ?: return null
        if (serial < 1) return null
        val days = serial.toLong()
        val adjustedDays = when {
            days >= 61 -> days
            days == 60L -> return null
            else -> days + 1
        }
        val fraction = serial - days
        val millisOfDay = (fraction * MILLIS_PER_DAY).roundToLong()
        return runCatching {
            LocalDate.of(1899, 12, 30)
                .plusDays(adjustedDays)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() + millisOfDay
        }.getOrNull()
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val values = mutableListOf<String>()
        val parser = newParser()
        zip.getInputStream(entry).use { input ->
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "si") {
                    values += readSharedString(parser)
                }
                event = parser.next()
            }
        }
        return values
    }

    private fun readSharedString(parser: XmlPullParser): String {
        val builder = StringBuilder()
        val startDepth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        builder.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si" && parser.depth == startDepth) break
                }
            }
            event = parser.next()
        }
        return builder.toString()
    }

    private fun findFirstSheet(zip: ZipFile): ZipEntry? {
        val sheetIds = mutableListOf<String>()
        parseWorkbook(zip)?.use { input ->
            val parser = newParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val id = attributeByLocalName(parser, "id")
                    id?.takeIf { it.startsWith("rId") }?.let { sheetIds += it }
                }
                event = parser.next()
            }
        }
        if (sheetIds.isNotEmpty()) {
            val relationships = readRelationships(zip)
            for (id in sheetIds) {
                val target = relationships[id] ?: continue
                val entry = zip.getEntry(normalizeSheetTarget(target))
                if (entry != null) return entry
            }
        }
        return zip.entries().asSequence()
            .map { it.name }
            .filter { it.matches(Regex("""xl/worksheets/sheet[0-9]+\.xml""")) }
            .sorted()
            .firstNotNullOfOrNull { zip.getEntry(it) }
    }

    private fun parseWorkbook(zip: ZipFile): java.io.InputStream? {
        val entry = zip.getEntry("xl/workbook.xml") ?: return null
        return zip.getInputStream(entry)
    }

    private fun readRelationships(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        zip.getInputStream(entry).use { input ->
            val parser = newParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id")
                    val target = parser.getAttributeValue(null, "Target")
                    if (id != null && target != null) result[id] = target
                }
                event = parser.next()
            }
        }
        return result
    }

    private fun attributeByLocalName(parser: XmlPullParser, localName: String): String? {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index).substringAfter(':')
            if (name == localName) return parser.getAttributeValue(index)
        }
        return null
    }

    private fun normalizeSheetTarget(target: String): String {
        val normalized = target.removePrefix("/")
        return if (normalized.startsWith("xl/")) normalized else "xl/$normalized"
    }

    private fun columnIndex(reference: String): Int {
        var index = 0
        for (char in reference) {
            if (char !in 'A'..'Z') break
            index = index * 26 + (char - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun newParser(): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        }
}
