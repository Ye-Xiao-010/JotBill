package com.jotbill.app.recognition

import java.math.BigDecimal
import java.security.MessageDigest

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
        "支付失败", "交易关闭", "支付取消", "付款失败"
    )

    /** 金额匹配正则 */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""¥\s*([0-9]+\.?[0-9]*)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元"""),
        Regex("""金额[：:]\s*([0-9]+\.?[0-9]*)""")
    )

    /** 订单号匹配正则 */
    private val ORDER_ID_PATTERNS = listOf(
        Regex("""订单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""交易号[：:]\s*([A-Za-z0-9\-]{6,40})""")
    )

    /** 商户名匹配正则 */
    private val MERCHANT_PATTERNS = listOf(
        Regex("""商户[：:]\s*([^\s,，。；;]{2,30})"""),
        Regex("""在(.{2,30}?)支付"""),
        Regex("""于(.{2,30}?)消费""")
    )

    /**
     * 解析支付结果页文本，返回结构化证据；非结果页或支付失败时返回 null。
     *
     * @param pageText 无障碍节点合并文本（已脱敏）
     * @param sourcePackage 结果页来源包名（微信/支付宝）
     * @param pageFingerprint 页面指纹（SHA-256）
     * @param observedAt 观察时间（毫秒）
     */
    fun parse(
        pageText: String,
        sourcePackage: String,
        pageFingerprint: String,
        observedAt: Long
    ): PaymentResultEvidence? {
        if (pageText.isBlank()) return null

        // 必须命中支付成功关键词才生成证据；失败关键词直接返回 null
        val isSuccess = SUCCESS_KEYWORDS.any { pageText.contains(it) }
        if (!isSuccess) return null

        // 支付失败关键词优先级高于成功（防止"支付失败"中含"支付"误判）
        if (FAILURE_KEYWORDS.any { pageText.contains(it) }) return null

        // 提取金额（分）
        val amountMinor = extractAmountMinor(pageText) ?: return null
        if (amountMinor <= 0L) return null

        // 提取订单号和商户
        val orderId = extractField(pageText, ORDER_ID_PATTERNS)
        val merchant = extractField(pageText, MERCHANT_PATTERNS)

        // 原文哈希（不存原文）
        val rawTextHash = sha256("result|$sourcePackage|$pageText")

        // sourceEventKey：来源包名 + 页面指纹 + 时间窗口（分钟级）+ 金额 + 订单号
        // 金额和订单号参与组合，避免同一分钟内同指纹页面的两笔不同支付被误判为重复
        val sourceEventKey = sha256(
            "result-event|$sourcePackage|$pageFingerprint|${observedAt / 60_000}|$amountMinor|${orderId.orEmpty()}"
        )

        return PaymentResultEvidence(
            amountMinor = amountMinor,
            merchant = merchant,
            orderId = orderId,
            paymentStatus = "SUCCESS",
            occurredAt = observedAt,
            rawTextHash = rawTextHash,
            sourceEventKey = sourceEventKey
        )
    }

    /** 从文本中提取金额并转换为分（Long），无法提取时返回 null。 */
    private fun extractAmountMinor(content: String): Long? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            val amountStr = match.groupValues.getOrNull(1) ?: continue
            return try {
                BigDecimal(amountStr).movePointRight(2).toLong()
            } catch (_: NumberFormatException) {
                null
            }
        }
        return null
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

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
