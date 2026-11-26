package com.jotbill.app.importing

import com.jotbill.app.rules.OrderIdNormalizer
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 阶段六 T3：微信官方账单适配器（docs/tdd/10_phase6_tasks.md 任务卡 T3）。
 *
 * 支持格式：
 * - CSV：UTF-8 / UTF-8 BOM / GBK，由 ImportFilePreparer 探测编码；
 * - XLSX：ZIP + XML 轻量解析（XlsxTableReader），交易时间列为 Excel 序列数，
 *   经 XlsxTableReader.excelSerialToEpochMillis 换算。
 *
 * 已登记表头版本：
 * - wechat-csv-v1：`交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注`
 * - wechat-csv-v2：上述表头的中文括号变体（`金额（元）`）或缺失 `当前状态` 的历史版本；
 *   列顺序变化由表头名映射吸收，不依赖固定列号。
 * - wechat-xlsx-v1：同字段的 XLSX 导出（表头约在第 18 行，前置为账单说明区）。
 *
 * 识别依据：文件头部存在同时含 `交易时间`、`收/支`、`金额`、`交易单号` 的表头行。
 * 未知版本不强行入账，返回带脱敏表头样本的解析错误。
 */
internal object WechatBillAdapter : BillImportAdapter {
    override val source = "wechat"
    override val supportedFormats = setOf(
        ImportFilePreparer.FORMAT_CSV,
        ImportFilePreparer.FORMAT_XLSX
    )
    override val formatVersion = "wechat-bill-v2"

    override fun canParse(file: File, encoding: String?): Boolean =
        runCatching { findHeader(file, encoding) != null }.getOrDefault(false)

    override fun parse(file: File, encoding: String?): ImportParseResult {
        val format = file.extension.lowercase(Locale.ROOT)
        val header = findHeader(file, encoding)
            ?: return ImportParseResult(
                rows = emptyList(),
                totalRecords = 0,
                validRecords = 0,
                filteredRecords = 0,
                errors = listOf(
                    ImportParseError(
                        lineNumber = 0,
                        message = "未识别的微信账单表头版本",
                        sample = maskSample(firstLinesSample(file, encoding, format))
                    )
                )
            )
        val columns = HeaderColumns(header.values)
        val rows = mutableListOf<ParsedImportRow>()
        val errors = mutableListOf<ImportParseError>()
        var totalRecords = 0
        var filteredRecords = 0
        var skippedRecords = 0

        val readRows: ((Int, List<String>) -> Boolean) -> Int = { onRow ->
            when (format) {
                ImportFilePreparer.FORMAT_XLSX -> XlsxTableReader.readRows(file, onRow)
                else -> CsvTableReader.readRows(file, encoding, onRow)
            }
        }
        val rowCount = readRows { lineNumber, values ->
            if (lineNumber <= header.lineNumber) return@readRows true
            val nonBlank = values.any { it.isNotBlank() }
            if (!nonBlank) return@readRows true
            totalRecords += 1
            if (isPreambleOrSummary(values)) {
                filteredRecords += 1
                return@readRows true
            }
            val parsed = parseRow(values, columns, lineNumber)
            when {
                parsed == null && isNeutralZeroAmountRow(values, columns) -> skippedRecords += 1
                parsed != null -> rows += parsed
                else -> errors += ImportParseError(
                    lineNumber = lineNumber,
                    message = "账单行缺少必要字段或金额/时间无法解析",
                    sample = maskSample(values.joinToString("|"))
                )
            }
            true
        }
        if (rowCount < header.lineNumber) {
            errors += ImportParseError(
                lineNumber = rowCount + 1,
                message = "账单文件在表头之后意外结束",
                sample = ""
            )
        }
        return ImportParseResult(
            rows = rows,
            totalRecords = totalRecords,
            validRecords = rows.size,
            filteredRecords = filteredRecords,
            errors = errors,
            skippedRecords = skippedRecords
        )
    }

    private data class Header(
        val lineNumber: Int,
        val values: List<String>
    )

    private data class HeaderColumns(
        val time: Int,
        val type: Int,
        val counterparty: Int,
        val product: Int,
        val direction: Int,
        val amount: Int,
        val paymentMethod: Int,
        val status: Int,
        val transactionId: Int,
        val merchantOrderId: Int,
        val remark: Int
    )

    private fun findHeader(file: File, encoding: String?): Header? {
        var found: Header? = null
        val readRows: ((Int, List<String>) -> Boolean) -> Int = { onRow ->
            if (file.extension.equals("xlsx", ignoreCase = true)) {
                XlsxTableReader.readRows(file, onRow)
            } else {
                CsvTableReader.readRows(file, encoding, onRow)
            }
        }
        readRows { lineNumber, values ->
            val normalized = values.map { normalizeHeader(it) }
            if (
                normalized.any { it == "交易时间" } &&
                normalized.any { it == "收/支" } &&
                normalized.any { amountHeaderOf(it) } &&
                normalized.any { it == "交易单号" }
            ) {
                found = Header(lineNumber, values)
                false
            } else {
                lineNumber < MAX_HEADER_SCAN_LINES
            }
        }
        return found
    }

    private fun parseRow(
        values: List<String>,
        columns: HeaderColumns,
        lineNumber: Int
    ): ParsedImportRow? {
        val timeText = valueAt(values, columns.time) ?: return null
        val direction = valueAt(values, columns.direction)
        val amountText = valueAt(values, columns.amount) ?: return null
        val transactionId = valueAt(values, columns.transactionId)
        val amountMinor = parseAmountMinor(amountText) ?: return null
        val occurredAt = parseOccurredAt(timeText) ?: return null
        val typeText = valueAt(values, columns.type)
        val rawStatus = valueAt(values, columns.status)
        val type = mapType(direction, typeText)
        val remark = buildRemark(valueAt(values, columns.remark), rawStatus, typeText, direction)
        return ParsedImportRow(
            occurredAt = occurredAt,
            amountMinor = amountMinor,
            type = type,
            merchant = valueAt(values, columns.counterparty),
            productDescription = valueAt(values, columns.product),
            paymentMethod = valueAt(values, columns.paymentMethod),
            transactionId = transactionId,
            // M5：商户单号提取点格式归一（与通知/结果页同一套 OrderIdNormalizer）
            merchantOrderId = OrderIdNormalizer.normalize(valueAt(values, columns.merchantOrderId)),
            remark = remark,
            rawStatus = rawStatus,
            sourceLineNumber = lineNumber
        )
    }

    private fun buildRemark(
        remark: String?,
        rawStatus: String?,
        typeText: String?,
        direction: String?
    ): String? {
        val parts = mutableListOf<String>()
        remark?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        typeText?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "交易类型:$it" }
        rawStatus?.trim()
            ?.takeIf { it.isNotEmpty() && it != "支付成功" && it != "已支付" }
            ?.let { parts += "状态:$it" }
        if (direction.isNullOrBlank() || direction == "/") {
            parts += "收/支:${direction ?: ""}"
        }
        return parts.joinToString("；").takeIf { it.isNotBlank() }
    }

    private fun mapType(direction: String?, typeText: String?): String {
        val tradeType = typeText.orEmpty()
        val directionText = direction.orEmpty()
        return when {
            tradeType.containsAny(REFUND_KEYWORDS) -> "refund"
            tradeType.containsAny(FEE_KEYWORDS) -> "fee"
            directionText.contains("收入") -> "income"
            directionText.contains("支出") -> "expense"
            tradeType.containsAny(REPAYMENT_KEYWORDS) -> "repayment"
            tradeType.containsAny(TRANSFER_KEYWORDS) -> "transfer"
            tradeType.contains("收入") -> "income"
            tradeType.contains("支出") -> "expense"
            // 中性未分类流水（理财/亲属卡/零钱通等）绝不落不可变的 adjustment 校准单：
            // 归类为 transfer，缺少目标账户时导入引擎会转待确认交由用户明确处理。
            else -> "transfer"
        }
    }

    private fun isPreambleOrSummary(values: List<String>): Boolean {
        val first = values.firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return false
        if (first.contains("微信支付账单明细")) return true
        if (first == "合计" || first == "总计") return true
        if (first.startsWith("共") && (first.contains("笔") || first.contains("条"))) return true
        if (values.none { it.isNotBlank() }) return true
        return false
    }

    /**
     * 收/支为空或"/"且金额无法解析（如 0.00 的零金额行）：按来源规则跳过，
     * 不计入解析错误污染 failed 口径。
     */
    private fun isNeutralZeroAmountRow(values: List<String>, columns: HeaderColumns): Boolean {
        val direction = valueAt(values, columns.direction)
        if (!direction.isNullOrBlank() && direction != "/" && direction != "不计收支") return false
        val amountText = valueAt(values, columns.amount) ?: return false
        return parseAmountMinor(amountText) == null
    }

    private fun parseAmountMinor(text: String): Long? {
        val normalized = text.trim()
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("\"", "")
            .replace("元", "")
        val match = AMOUNT_PATTERN.find(normalized) ?: return null
        return try {
            val amount = BigDecimal(match.value)
            if (amount.signum() <= 0 || amount.scale() > 2) return null
            amount.movePointRight(2).longValueExact().takeIf { it > 0L }
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseOccurredAt(text: String): Long? {
        val normalized = text.trim()
        return XlsxTableReader.excelSerialToEpochMillis(normalized) ?: runCatching {
            TIME_FORMATTERS.firstNotNullOf { formatter ->
                runCatching { LocalDateTime.parse(normalized, formatter) }.getOrNull()
            }.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun HeaderColumns(header: List<String>): HeaderColumns {
        val index = header.mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
        fun column(vararg names: String): Int {
            for (name in names) {
                index[name]?.let { return it }
            }
            return -1
        }
        return HeaderColumns(
            time = column("交易时间"),
            type = column("交易类型"),
            counterparty = column("交易对方"),
            product = column("商品", "商品说明", "商品名称"),
            direction = column("收/支", "收支"),
            amount = column("金额(元)", "金额（元）", "金额"),
            paymentMethod = column("支付方式"),
            status = column("当前状态"),
            transactionId = column("交易单号"),
            merchantOrderId = column("商户单号"),
            remark = column("备注")
        )
    }

    private fun valueAt(values: List<String>, index: Int): String? =
        values.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeHeader(value: String): String =
        value.trim().replace("\uFEFF", "").replace(" ", "")

    private fun amountHeaderOf(value: String): Boolean =
        value == "金额(元)" || value == "金额（元）" || value == "金额"

    private fun firstLinesSample(file: File, encoding: String?, format: String): String {
        val sample = StringBuilder()
        val readRows: ((Int, List<String>) -> Boolean) -> Int = { onRow ->
            if (format == ImportFilePreparer.FORMAT_XLSX) {
                XlsxTableReader.readRows(file, onRow)
            } else {
                CsvTableReader.readRows(file, encoding, onRow)
            }
        }
        readRows { lineNumber, values ->
            if (lineNumber > MAX_HEADER_SCAN_LINES) return@readRows false
            if (values.any { it.isNotBlank() }) {
                sample.append(values.joinToString("|").take(120)).append('\n')
            }
            true
        }
        return sample.toString()
    }

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { contains(it) }

    /** 表头扫描上限：微信 XLSX 表头约在第 18 行，支付宝 2026 回单在第 24 行附近，统一预留到 40。 */
    private const val MAX_HEADER_SCAN_LINES = 40
    private val AMOUNT_PATTERN = Regex("""[-+]?[0-9][0-9,]*(?:\.[0-9]{1,2})?""")
    private val REFUND_KEYWORDS = listOf("退款", "退回", "返还")
    private val FEE_KEYWORDS = listOf("手续费", "服务费", "提现费")
    private val REPAYMENT_KEYWORDS = listOf("信用卡还款", "花呗还款", "还款")
    private val TRANSFER_KEYWORDS = listOf("转账", "零钱通", "转入", "转出")
    private val TIME_FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)
    )
}
