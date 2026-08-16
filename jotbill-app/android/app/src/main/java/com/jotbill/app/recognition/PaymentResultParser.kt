package com.jotbill.app.recognition

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 支付结果页证据解析结果。
 *
 * 从无障碍节点文本中提取的结构化支付证据，用于 PaymentEvidence 落库。
 * 金额统一使用分（amountMinor），不包含节点原文。
 */
data class PaymentResultEvidence(
    val amountMinor: Long,
    val merchant: String?,
    val orderId: String?,
    val paymentStatus: String,
    val occurredAt: Long?,
    val rawTextHash: String,
    val sourceEventKey: String
)

/** 支付结果页解析的明确结果，区分失败页、非结果页和成功证据。 */
internal sealed class PaymentResultParseResult {
    data class Success(val evidence: PaymentResultEvidence) : PaymentResultParseResult()

    data class Failure(val reason: PaymentResultFailureReason) : PaymentResultParseResult()

    object NotPaymentResult : PaymentResultParseResult()
}

internal enum class PaymentResultFailureReason {
    PAYMENT_FAILED,
    INVALID_AMOUNT,
    INVALID_OCCURRED_AT
}

/**
 * 支付结果页证据解析器。
 *
 * 从无障碍节点文本中提取支付状态、金额、商户、订单号等结构化字段，
 * 生成 [PaymentResultEvidence] 供 PaymentEvidence 落库。
 *
 * 约束：
 * - 不截图支付结果页，只读取无障碍节点文本
 * - 不读密码、付款码、银行卡号、CVV 等敏感字段
 * - 支付失败的结果不生成成功证据
 * - sourceEventKey 按结果页来源包名 + 页面指纹 + 时间组合生成，幂等落库
 */
internal object PaymentResultParser {

    /** 支付成功关键词；"订单已提交"不代表支付成功（可能是货到付款/待支付），不得列入。 */
    private val SUCCESS_KEYWORDS = listOf(
        "支付成功", "付款成功", "交易完成", "支付完成",
        "已付款", "订单已支付"
    )

    /** 支付失败关键词 */
    private val FAILURE_KEYWORDS = listOf(
        "支付失败", "交易关闭", "支付取消", "付款失败", "订单取消", "订单关闭", "支付未完成"
    )

    /** 金额匹配正则 */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""(?:¥|￥)\s*([0-9]+(?:\.[0-9]+)?)"""),
        Regex("""([0-9]+(?:\.[0-9]+)?)\s*元"""),
        Regex("""金额[：:]\s*([0-9]+(?:\.[0-9]+)?)""")
    )

    private val FINAL_AMOUNT_PATTERNS = listOf(
        Regex("""(?:实付|实际支付|支付金额|付款金额|本次支付)\s*[:：]?\s*(?:¥|￥|CNY)?\s*([0-9]+(?:\.[0-9]+)?)"""),
        Regex("""(?:订单总额|订单金额|应付|合计|总计)\s*[:：]?\s*(?:¥|￥|CNY)?\s*([0-9]+(?:\.[0-9]+)?)"""),
        Regex("""(?:¥|￥|CNY)\s*([0-9]+(?:\.[0-9]+)?)\s*(?:实付|实际支付|支付金额|付款金额|本次支付)"""),
        Regex("""([0-9]+(?:\.[0-9]+)?)\s*元的?\s*(?:实付|实际支付|支付金额|付款金额|本次支付)""")
    )

    private val FULL_DATE_TIME_PATTERN = Regex(
        """(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})\s*(?:T\s*)?([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?(?!\d)"""
    )
    private val LABELED_TIME_PATTERN = Regex(
        """(?:支付|付款|交易|完成|成功)?时间\s*[:：]?\s*([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?"""
    )
    private val SENSITIVE_NUMBER_PATTERN = Regex(
        """(?<![\p{L}\p{N}])\d{3,}(?:[.,]\d+)?(?![\p{L}\p{N}])"""
    )
    private val MAX_AMOUNT_MINOR = BigDecimal.valueOf(Long.MAX_VALUE)

    /** 订单号匹配正则 */
    private val ORDER_ID_PATTERNS = listOf(
        Regex("""订单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""交易号[：:]\s*([A-Za-z0-9\-]{6,40})""")
    )

    /** 商户名匹配正则 */
    private val MERCHANT_PATTERNS = listOf(
        Regex("""商户[：:]\s*([^\s,，。；;]{2,30})"""),
        Regex("""(?:支付成功|付款成功|支付完成|交易完成)\s*([^\s，。；;￥¥0-9]{2,30}?)\s*(?:￥|¥|CNY|[0-9])"""),
        Regex("""在(.{2,30}?)支付"""),
        Regex("""于(.{2,30}?)消费""")
    )

    /** 保留原有调用形状，仅把成功解析结果返回给支付匹配层。 */
    fun parse(
        pageText: String,
        sourcePackage: String,
        pageFingerprint: String,
        observedAt: Long
    ): PaymentResultEvidence? =
        (parseResult(pageText, sourcePackage, pageFingerprint, observedAt)
            as? PaymentResultParseResult.Success)
            ?.evidence

    /** 解析结果页并保留失败语义；失败页不得伪造成成功支付信号。 */
    fun parseResult(
        pageText: String,
        sourcePackage: String,
        pageFingerprint: String,
        observedAt: Long
    ): PaymentResultParseResult {
        if (pageText.isBlank()) return PaymentResultParseResult.NotPaymentResult

        // 失败关键词优先于成功关键词，避免混合文案被误判为成功。
        if (FAILURE_KEYWORDS.any { pageText.contains(it) }) {
            return PaymentResultParseResult.Failure(PaymentResultFailureReason.PAYMENT_FAILED)
        }
        if (SUCCESS_KEYWORDS.none { pageText.contains(it) }) {
            return PaymentResultParseResult.NotPaymentResult
        }

        val occurredAt = parseOccurredAt(pageText, observedAt)
            ?: return PaymentResultParseResult.Failure(PaymentResultFailureReason.INVALID_OCCURRED_AT)
        val amountMinor = extractAmountMinor(pageText)
            ?: return PaymentResultParseResult.Failure(PaymentResultFailureReason.INVALID_AMOUNT)

        // 提取订单号和商户；结构化字段仍供核心匹配器使用，原文哈希不会包含其数字值。
        val orderId = extractField(pageText, ORDER_ID_PATTERNS)
        val merchant = extractField(pageText, MERCHANT_PATTERNS)
        val hashableText = sanitizeForHash(pageText, orderId)
        val rawTextHash = sha256("result|$sourcePackage|$hashableText")

        // 事件键不包含页面指纹：同一支付结果页在微信 LiteApp 中会随动态节点变化产生
        // 多个指纹，旧实现会生成多条幂等键不同的孤儿待确认；现在按包名+分钟+金额+订单号去重。
        val sourceEventKey = sha256(
            "result-event|$sourcePackage|${occurredAt / 120_000}|$amountMinor|${orderId.orEmpty()}"
        )

        return PaymentResultParseResult.Success(
            PaymentResultEvidence(
                amountMinor = amountMinor,
                merchant = merchant,
                orderId = orderId,
                paymentStatus = "SUCCESS",
                occurredAt = occurredAt,
                rawTextHash = rawTextHash,
                sourceEventKey = sourceEventKey
            )
        )
    }

    /** 优先解析页面显示时间；页面缺失或格式非法时回退观察时间。 */
    private fun parseOccurredAt(content: String, observedAt: Long): Long? {
        val normalized = content
            .replace('：', ':')
            .replace('年', '-')
            .replace('月', '-')
            .replace('日', ' ')
            .replace('时', ':')
            .replace('分', ':')
            .replace('秒', ' ')
            .replace('/', '-')
            .replace('.', '-')

        FULL_DATE_TIME_PATTERN.find(normalized)?.let { match ->
            parseDateTime(match)?.let { return it }
        }
        if (observedAt <= 0L) return null
        LABELED_TIME_PATTERN.find(normalized)?.let { match ->
            parseClosestTime(match, observedAt)?.let { return it }
        }
        return observedAt
    }

    private fun parseDateTime(match: MatchResult): Long? = runCatching {
        val second = match.groupValues[6].takeIf { it.isNotBlank() }?.toInt() ?: 0
        LocalDateTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            second
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseClosestTime(match: MatchResult, observedAt: Long): Long? = runCatching {
        val localTime = LocalTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].takeIf { it.isNotBlank() }?.toInt() ?: 0
        )
        val zone = ZoneId.systemDefault()
        val observed = Instant.ofEpochMilli(observedAt).atZone(zone)
        (-1L..1L)
            .map { offset ->
                observed.toLocalDate()
                    .plusDays(offset)
                    .atTime(localTime)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            }
            .minByOrNull { candidate -> abs(candidate - observedAt) }
    }.getOrNull()

    /** 从文本中提取金额并转换为分（Long），无法提取时返回 null。 */
    private fun extractAmountMinor(content: String): Long? {
        for (pattern in FINAL_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            parseAmountMinor(match.groupValues[1])?.let { return it }
        }
        for (pattern in AMOUNT_PATTERNS) {
            for (match in pattern.findAll(content)) {
                val amountStr = match.groupValues.getOrNull(1) ?: continue
                val amount = try {
                    BigDecimal(amountStr)
                } catch (_: NumberFormatException) {
                    continue
                }
                if (amount.signum() <= 0 || amount.scale() > 2) {
                    continue
                }
                val amountMinor = amount.movePointRight(2)
                if (amountMinor > MAX_AMOUNT_MINOR) {
                    continue
                }
                try {
                    return amountMinor.longValueExact()
                } catch (_: ArithmeticException) {
                    // 非整数分或超出 Long 范围时继续寻找其它候选金额。
                }
            }
        }
        return null
    }

    private fun parseAmountMinor(amountText: String): Long? {
        val amount = try {
            BigDecimal(amountText)
        } catch (_: NumberFormatException) {
            return null
        }
        if (amount.signum() <= 0 || amount.scale() > 2) return null
        val amountMinor = amount.movePointRight(2)
        if (amountMinor > MAX_AMOUNT_MINOR) return null
        return try {
            amountMinor.longValueExact().takeIf { it > 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** 用一组正则从文本中提取首个匹配的字段值。 */
    private fun extractField(content: String, patterns: List<Regex>): String? {
        for (pattern in patterns) {
            val match = pattern.find(content) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    /** 原文不落库；哈希前仍需去除独立敏感数字和完整订单号。 */
    private fun sanitizeForHash(content: String, orderId: String?): String {
        var sanitized = content
        orderId?.takeIf { it.isNotBlank() }?.let { value ->
            sanitized = sanitized.replace(value, "<order-id>")
        }
        return SENSITIVE_NUMBER_PATTERN.replace(sanitized, "<number>")
    }

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
