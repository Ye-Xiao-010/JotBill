package com.jotbill.app.importing

/**
 * 阶段六 T3/T4：账单适配器共用的结构化导入行。
 *
 * 微信/支付宝官方账单统一输出此结构，导入引擎（T5）不再感知各来源列名差异。
 */
data class ParsedImportRow(
    val occurredAt: Long,
    val amountMinor: Long,
    val type: String,
    val merchant: String?,
    val productDescription: String?,
    val paymentMethod: String?,
    val transactionId: String?,
    val merchantOrderId: String?,
    val remark: String?,
    val rawStatus: String?,
    val sourceLineNumber: Int
)

/** 解析错误：保留行号与脱敏样本，供预览统计与排障，不丢弃证据文件。 */
data class ImportParseError(
    val lineNumber: Int,
    val message: String,
    val sample: String
)

/** 适配器解析结果与报告计数。 */
data class ImportParseResult(
    val rows: List<ParsedImportRow>,
    val totalRecords: Int,
    val validRecords: Int,
    val filteredRecords: Int,
    val errors: List<ImportParseError>,
    /** 支付宝"交易关闭"等按来源规则跳过、不视为解析失败的行数。 */
    val skippedRecords: Int = 0
)

/**
 * 账单文件适配器接口。
 *
 * T3/T4 分别实现微信/支付宝版本；[parse] 为纯函数式，不写库、不弹窗。
 */
internal interface BillImportAdapter {
    val source: String

    /** 支持的文件格式集合（与 [ImportFilePreparer] 的格式常量一致）。 */
    val supportedFormats: Set<String>

    /** 已识别的格式版本说明，如 wechat-csv-v1。 */
    val formatVersion: String

    /** 只做表头签名/结构探测，不产生行数据。 */
    fun canParse(file: java.io.File, encoding: String?): Boolean

    fun parse(file: java.io.File, encoding: String?): ImportParseResult
}

/** 错误样本脱敏：数字与疑似长标识符替换为 *，避免开发交接物携带账单明文。 */
internal fun maskSample(text: String, maxLength: Int = 80): String {
    val masked = text
        .replace(Regex("[0-9]"), "*")
        .replace(Regex("[A-Za-z]{8,}"), "********")
    return if (masked.length <= maxLength) masked else masked.take(maxLength) + "…"
}
