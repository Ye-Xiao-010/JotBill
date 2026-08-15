package com.jotbill.app.notification

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant

/**
 * 支付通知归一化结果。
 *
 * 不包含通知原文，只保留结构化字段和原文哈希，用于 PaymentEvidence 落库。
 * 金额统一使用分（amountMinor），避免跨服务传递 Double 时产生精度歧义。
 */
data class PaymentEvidenceInput(
    val sourcePackage: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String?,
    val orderId: String?,
    val paymentStatus: String,
    val sourceEventKey: String,
    val rawTextHash: String
)

/**
 * 支付通知解析器。
 *
 * 负责把微信/支付宝的系统通知正文解析为结构化 [PaymentEvidenceInput]，
 * 排除收款、到账、转入、红包、AA、提现等非消费语义。
 *
 * 解析器只做文本提取和语义判断，不写数据库、不弹窗、不创建账单。
 * 重复通知由调用方按 sourceEventKey 幂等处理。
 */
internal object PaymentResultNormalizer {

    /** 微信包名 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 支付宝包名 */
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 支持的支付应用包名白名单 */
    val SUPPORTED_PACKAGES: Set<String> = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)

    /** 非消费语义排除关键词：收款、到账、转入、红包、AA、提现等 */
    private val EXCLUDE_KEYWORDS = listOf(
        "收款", "到账", "收到", "转入", "红包", "转账", "转出",
        "提现", "退款", "退回", "返还", "领取", "发放"
    )

    /** 支付成功关键词（至少命中一个才视为消费支付） */
    private val PAYMENT_KEYWORDS = listOf(
        "支付", "付款", "消费", "扣款", "支出", "交易提醒",
        "花呗", "余额宝", "已支付", "已扣款"
    )

    /** 金额匹配正则表达式，覆盖常见支付通知金额格式 */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""¥\s*([0-9]+\.?[0-9]*)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元"""),
        Regex("""CNY\s*([0-9]+\.?[0-9]*)"""),
        Regex("""人民币\s*([0-9]+\.?[0-9]*)"""),
        Regex("""金额[：:]\s*([0-9]+\.?[0-9]*)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元的""")
    )

    /** 订单号匹配正则：优先匹配较长的数字或字母数字组合 */
    private val ORDER_ID_PATTERNS = listOf(
        Regex("""订单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""交易号[：:]\s*([A-Za-z0-9\-]{6,40})""")
    )

    /** 商户名匹配正则 */
    private val MERCHANT_PATTERNS = listOf(
        Regex("""商户[：:]\s*([^\s,，。；;]{2,30})"""),
        Regex("""在(.{2,30}?)消费"""),
        Regex("""于(.{2,30}?)支付""")
    )

    /**
     * 解析通知正文，返回结构化证据输入；非消费通知或金额无效时返回 null。
     *
     * @param sourcePackage 通知来源包名
     * @param title 通知标题
     * @param text 通知正文
     * @param bigText 通知展开文本
     * @param subText 子文本
     * @param summaryText 摘要文本
     * @param tickerText 滚动文本
     * @param postTime 通知发送时间（毫秒）
     * @param notificationKey 通知系统 key（用于幂等）
     * @param notificationId 通知 ID
     */
    fun normalize(
        sourcePackage: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        summaryText: String,
        tickerText: String,
        postTime: Long,
        notificationKey: String?,
        notificationId: Int
    ): PaymentEvidenceInput? {
        if (sourcePackage !in SUPPORTED_PACKAGES) return null

        val fullContent = listOf(title, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullContent.isBlank()) return null

        // 排除非消费语义
        if (EXCLUDE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 必须命中至少一个支付关键词
        if (PAYMENT_KEYWORDS.none { fullContent.contains(it) }) return null

        // 提取金额（分）
        val amountMinor = extractAmountMinor(fullContent) ?: return null
        if (amountMinor <= 0L) return null

        // 提取订单号
        val orderId = extractField(fullContent, ORDER_ID_PATTERNS)

        // 提取商户
        val merchant = extractField(fullContent, MERCHANT_PATTERNS)

        // 生成 sourceEventKey：包名 + 通知key + 通知ID + 发送时间 + 归一化金额
        val sourceEventKey = buildSourceEventKey(
            sourcePackage, notificationKey, notificationId, postTime, amountMinor
        )

        // 原文哈希（不存原文）
        val rawTextHash = sha256("raw|$sourcePackage|$fullContent")

        return PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = amountMinor,
            occurredAt = postTime,
            merchant = merchant,
            orderId = orderId,
            paymentStatus = "SUCCESS",
            sourceEventKey = sourceEventKey,
            rawTextHash = rawTextHash
        )
    }

    /** 从文本中提取金额并转换为分（Long），无法提取时返回 null。 */
    private fun extractAmountMinor(content: String): Long? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            val amountStr = match.groupValues.getOrNull(1) ?: continue
            return try {
                val amount = BigDecimal(amountStr)
                amount.movePointRight(2).toLong()
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

    /**
     * 构建 sourceEventKey，用于通知幂等。
     * 优先使用系统通知 key；若不可用则用包名+ID+时间+金额组合。
     */
    private fun buildSourceEventKey(
        sourcePackage: String,
        notificationKey: String?,
        notificationId: Int,
        postTime: Long,
        amountMinor: Long
    ): String {
        val keyPart = notificationKey?.takeIf { it.isNotBlank() }
            ?: "$sourcePackage|$notificationId|$postTime"
        return sha256("event|$keyPart|$amountMinor")
    }

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
