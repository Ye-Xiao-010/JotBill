package com.jotbill.app.importing

import com.jotbill.app.rules.OrderIdNormalizer
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 阶段六 T4：支付宝官方账单适配器（docs/tdd/10_phase6_tasks.md 任务卡 T4）。
 *
 * 支持格式：
 * - CSV：UTF-8 / UTF-8 BOM / GBK，由 ImportFilePreparer 探测编码；
 * - XLSX：ZIP + XML 轻量解析（XlsxTableReader），工作表流式逐行读取。
 *
 * 已登记表头版本：
 * - alipay-csv-v1：`交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,
 *   类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注,资金状态`
 * - alipay-xlsx-v1：同字段的 XLSX 导出。
 * - alipay-csv-v2：2026 年电子客户回单（表头不在首行、无独立交易号/付款时间列）：
 *   `交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,
 *   交易订单号,商家订单号,备注`。交易订单号充作交易号、交易时间充作付款时间。
 *
 * 识别依据（满足任一表头族）：v1 同时含 `交易号`、`商家订单号`、`金额`、`收/支`、
 * `交易状态`、`付款时间|交易创建时间`；v2 同时含 `交易时间`、`交易订单号`、
 * `商家订单号`、`金额`、`收/支`、`交易状态`。未知版本不强行入账。
 */
internal object AlipayBillAdapter : BillImportAdapter {
    override val source = "alipay"
    override val supportedFormats = setOf(
        ImportFilePreparer.FORMAT_CSV,
        ImportFilePreparer.FORMAT_XLSX
    )
    override val formatVersion = "alipay-bill-v2"

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
                        message = "未识别的支付宝账单表头版本",
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
        readRows { lineNumber, values ->
            if (lineNumber <= header.lineNumber) return@readRows true
            if (values.none { it.isNotBlank() }) return@readRows true
            totalRecords += 1
            if (isPreambleOrSummary(values)) {
                filteredRecords += 1
                return@readRows true
            }
            val parsed = parseRow(values, columns, lineNumber)
            when {
                parsed == null && valueAt(values, columns.status) == "交易关闭" -> skippedRecords += 1
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
        val tradeNo: Int,
        val merchantOrderNo: Int,
        val createdTime: Int,
        val paidTime: Int,
        val type: Int,
        val counterparty: Int,
        val product: Int,
        val amount: Int,
        val direction: Int,
        val status: Int,
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
            val isV1Header =
                normalized.any { it == "交易号" } &&
                    normalized.any { it == "商家订单号" } &&
                    normalized.any(::amountHeaderOf) &&
                    normalized.any { it == "收/支" } &&
                    normalized.any { it == "交易状态" } &&
                    normalized.any { it == "付款时间" || it == "交易创建时间" }
            val isV2Header =
                normalized.any { it == "交易时间" } &&
                    normalized.any { it == "交易订单号" } &&
                    normalized.any { it == "商家订单号" } &&
                    normalized.any(::amountHeaderOf) &&
                    normalized.any { it == "收/支" } &&
                    normalized.any { it == "交易状态" }
            if (isV1Header || isV2Header) {
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
        val status = valueAt(values, columns.status).orEmpty()
        if (status == "交易关闭") return null

        val timeText = firstNonBlank(
            valueAt(values, columns.paidTime),
            valueAt(values, columns.createdTime)
        ) ?: return null
        val amountText = valueAt(values, columns.amount) ?: return null
        val amountMinor = parseAmountMinor(amountText) ?: return null
        val occurredAt = parseOccurredAt(timeText) ?: return null
        val typeText = valueAt(values, columns.type)
        val product = valueAt(values, columns.product)
        val direction = valueAt(values, columns.direction)
        val transactionId = valueAt(values, columns.tradeNo) ?: return null
        val type = mapType(direction, typeText, product, valueAt(values, columns.remark))
        val remark = buildRemark(valueAt(values, columns.remark), status, typeText, direction)
        return ParsedImportRow(
            occurredAt = occurredAt,
            amountMinor = amountMinor,
            type = type,
            merchant = valueAt(values, columns.counterparty),
            productDescription = product,
            paymentMethod = "支付宝",
            transactionId = transactionId,
            // M5：商户单号提取点格式归一（与通知/结果页同一套 OrderIdNormalizer）
            merchantOrderId = OrderIdNormalizer.normalize(valueAt(values, columns.merchantOrderNo)),
            remark = remark,
            rawStatus = status,
            sourceLineNumber = lineNumber
        )
    }

    private fun mapType(
        direction: String?,
        typeText: String?,
        product: String?,
        remark: String?
    ): String {
        val tradeType = typeText.orEmpty()
        val productText = product.orEmpty()
        val directionText = direction.orEmpty()
        val remarkText = remark.orEmpty()
        return when {
            tradeType.containsAny(REFUND_KEYWORDS) ||
                productText.containsAny(REFUND_KEYWORDS) ||
                remarkText.containsAny(REFUND_KEYWORDS) -> "refund"
            tradeType.containsAny(FEE_KEYWORDS) || productText.containsAny(FEE_KEYWORDS) -> "fee"
            directionText.contains("收入") -> "income"
            directionText.contains("支出") -> "expense"
            tradeType.containsAny(REPAYMENT_KEYWORDS) ||
                productText.containsAny(REPAYMENT_KEYWORDS) -> "repayment"
            tradeType.containsAny(TRANSFER_KEYWORDS) ||
                productText.containsAny(TRANSFER_KEYWORDS) -> "transfer"
            tradeType.contains("收入") -> "income"
            tradeType.contains("支出") -> "expense"
            else -> "adjustment"
        }
    }

    private fun buildRemark(
        remark: String?,
        status: String?,
        typeText: String?,
        direction: String?
    ): String? {
        val parts = mutableListOf<String>()
        remark?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        typeText?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "类型:$it" }
        status?.trim()
            ?.takeIf { it.isNotEmpty() && it != "交易成功" && it != "支付成功" }
            ?.let { parts += "状态:$it" }
        if (direction.isNullOrBlank() || direction == "/") {
            parts += "收/支:${direction ?: ""}"
        }
        return parts.joinToString("；").takeIf { it.isNotBlank() }
    }

    private fun isPreambleOrSummary(values: List<String>): Boolean {        val first = values.firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return false
        if (first.contains("支付宝交易流水明细") || first.contains("支付宝账单明细")) return true
        if (first == "合计" || first == "总计") return true
        if (first.startsWith("共") && (first.contains("笔") || first.contains("条"))) return true
        return false
    }

    /**
     * 收/支为空或"/"且金额无法解析（如 0.00 的冻结/解冻行）：按来源规则跳过，
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
            tradeNo = column("交易号", "交易订单号"),
            merchantOrderNo = column("商家订单号"),
            createdTime = column("交易创建时间", "交易时间"),
            paidTime = column("付款时间", "交易时间"),
            type = column("类型", "交易类型"),
            counterparty = column("交易对方"),
            product = column("商品名称", "商品", "商品说明"),
            amount = column("金额（元）", "金额(元)", "金额"),
            direction = column("收/支", "收支"),
            status = column("交易状态"),
            remark = column("备注")
        )
    }

    private fun valueAt(values: List<String>, index: Int): String? =
        values.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun normalizeHeader(value: String): String =
        value.trim().replace("\uFEFF", "").replace(" ", "")

    private fun amountHeaderOf(value: String): Boolean =
        value == "金额（元）" || value == "金额(元)" || value == "金额"

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

    /** 表头扫描上限：2026 电子客户回单表头在第 24 行附近，预留到 40。 */
    private const val MAX_HEADER_SCAN_LINES = 40
    private val AMOUNT_PATTERN = Regex("""[-+]?[0-9][0-9,]*(?:\.[0-9]{1,2})?""")
    private val REFUND_KEYWORDS = listOf("退款", "退回", "返还")
    private val FEE_KEYWORDS = listOf("手续费", "服务费", "提现费")
    private val REPAYMENT_KEYWORDS = listOf("信用卡还款", "花呗还款", "还款")
    private val TRANSFER_KEYWORDS = listOf("转账", "余额宝", "转入", "转出", "零钱")
    private val TIME_FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)
    )
}
