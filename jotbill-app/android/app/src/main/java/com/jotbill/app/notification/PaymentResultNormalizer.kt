package com.jotbill.app.notification

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

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

    /** 支付失败/进行中关键词：命中即排除，防止失败通知被误标为支付成功。 */
    private val FAILURE_KEYWORDS = listOf(
        "支付失败", "付款失败", "交易失败", "扣款失败", "支付未完成",
        "处理中", "等待付款", "待支付", "未支付", "已取消", "取消支付",
        "交易关闭", "失败", "异常"
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

    /** 交易时间匹配正则：HH:mm 或 HH:mm:ss（通知正文常见格式）。 */
    private val TIME_PATTERN = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?!\d)""")

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

        // 排除支付失败/进行中通知：宁可漏记不可误记，失败通知绝不能落成成功证据
        if (FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 必须命中至少一个支付关键词
        if (PAYMENT_KEYWORDS.none { fullContent.contains(it) }) return null

        // 提取金额（分）
        val amountMinor = extractAmountMinor(fullContent) ?: return null
        if (amountMinor <= 0L) return null

        // 提取订单号
        val orderId = extractField(fullContent, ORDER_ID_PATTERNS)

        // 提取商户
        val merchant = extractField(fullContent, MERCHANT_PATTERNS)

        // 发生时间：优先用正文中的交易时间；无法解析时回退通知发送时间
        val occurredAt = extractOccurredAt(fullContent, postTime)

        // 生成 sourceEventKey：包名 + 通知key + 通知ID + 发送时间 + 归一化金额 + 订单号
        val sourceEventKey = buildSourceEventKey(
            sourcePackage, notificationKey, notificationId, postTime, amountMinor, orderId
        )

        // 原文哈希（不存原文）
        val rawTextHash = sha256("raw|$sourcePackage|$fullContent")

        return PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
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
     * 优先使用系统通知 key；若不可用则用包名+ID+时间+金额+订单号组合，
     * 订单号参与组合避免同分钟内同金额的两笔支付被误判为重复。
     */
    private fun buildSourceEventKey(
        sourcePackage: String,
        notificationKey: String?,
        notificationId: Int,
        postTime: Long,
        amountMinor: Long,
        orderId: String?
    ): String {
        val keyPart = notificationKey?.takeIf { it.isNotBlank() }
            ?: "$sourcePackage|$notificationId|$postTime"
        return sha256("event|$keyPart|$amountMinor|${orderId.orEmpty()}")
    }

    /**
     * 从正文提取交易发生时间，优先级高于通知到达时间。
     *
     * 支持"HH:mm"和"HH:mm:ss"格式（通知正文常见），结合通知时间的日期部分还原时间戳；
     * 解析结果比通知时间晚超过 30 分钟视为跨午夜，回退一天。
     * 无法解析时返回通知时间。
     */
    private fun extractOccurredAt(content: String, postTime: Long): Long {
        val match = TIME_PATTERN.find(content) ?: return postTime
        val hour = match.groupValues[1].toIntOrNull() ?: return postTime
        val minute = match.groupValues[2].toIntOrNull() ?: return postTime
        if (hour !in 0..23 || minute !in 0..59) return postTime

        val postInstant = Instant.ofEpochMilli(postTime)
                .atZone(ZoneId.systemDefault())
        var occurred = postInstant.toLocalDate()
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
        // 解析出的时间比通知晚超过 30 分钟：多半是跨午夜（如 23:59 支付、00:01 收到通知）
        if (occurred.toInstant().toEpochMilli() - postTime > 30 * 60 * 1000L) {
            occurred = occurred.minusDays(1)
        }
        return occurred.toInstant().toEpochMilli()
    }

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
