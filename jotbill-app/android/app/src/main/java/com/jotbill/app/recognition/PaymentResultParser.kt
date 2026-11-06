package com.jotbill.app.recognition

import com.jotbill.app.rules.OrderIdNormalizer
import com.jotbill.app.utils.PaymentTiming
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

/**
 * 个人转账事实：微信/支付宝向好友（个人）转账。
 *
 * 收款人是个人而非商户，不应记录为消费证据；由上层转待确认（type=transfer）。
 */
data class PaymentPersonalTransfer(
    val recipient: String?,
    val amountMinor: Long,
    val occurredAt: Long?
)

/** 支付结果页解析的明确结果，区分失败页、非结果页和成功证据。 */
internal sealed class PaymentResultParseResult {
    data class Success(val evidence: PaymentResultEvidence) : PaymentResultParseResult()

    data class PersonalTransfer(val transfer: PaymentPersonalTransfer) : PaymentResultParseResult()

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
 * - sourceEventKey 按**结果页来源包名 + 15 秒时间桶 + 金额 + 订单号**生成
 *   （**不含页面指纹** —— 同一结果页在微信 LiteApp 中会随动态节点变化产生多个指纹），幂等落库
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

    /**
     * 订单页标题特征词（B1 站内结果页负向守卫专用）。
     *
     * 站内支付结果页接线后，购物 App 的订单详情/订单列表/账单页也会进入本解析器
     * （它们常含"已付款/交易完成"+金额+时间，30 分钟时效闸对"刚付完的订单页"无效）。
     * 这些词是**页面级标题词**（出现在页面顶部），与结果页的"支付成功"标题互斥共存。
     * 不收"账单"裸词（结果页可能带"账单"按钮）、不收"订单"（过泛）。
     */
    private val ORDER_PAGE_TITLE_TERMS = listOf(
        "订单详情", "订单列表", "全部订单", "我的订单", "账单明细", "交易明细", "交易记录"
    )

    /**
     * 强支付成功语义（P8）：订单详情页的支付状态行惯用「已付款/交易完成」弱语义，
     * **不会**出现「支付成功/付款成功/支付完成」—— 这三个词是当次支付结果页的
     * 标志性标题。订单页标题词与强语义同时出现时按结果页放行（修复 P8：旧判据
     * 只比先后位置，「订单详情标题 + 支付成功横幅」的当次页被误杀漏卡）。
     */
    private val STRONG_PAYMENT_SUCCESS_TERMS = listOf("支付成功", "付款成功", "支付完成")

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
    /**
     * 金额上界：与全应用安全整数分单位口径**同源**（[MAX_SAFE_MINOR_UNIT]）。
     *
     * 原为 `BigDecimal.valueOf(Long.MAX_VALUE)`，是全应用最宽松的上界，与
     * `PendingConfirmReadiness.isValidAmount` / `LedgerRepository.validateSafeAmount`
     * 的口径错配 —— 后两者都以 `MAX_SAFE_MINOR_UNIT` 为准，会出现
     * "解析层放行、就绪层与账本层拒绝"的错配。
     *
     * **精简版下这条尤其要紧**：完整版还有截图 AI 二次校验金额，解析器误取一个
     * 异常大数会被 AI 复核挡下；停用截图识别后只剩这一条文本路径，
     * 结果页文本里任何 `¥1234567890123` 都会被当成真实金额落库。
     * 统一为 `MAX_SAFE_MINOR_UNIT` 后行为**更保守**（多拒而不是少拒），
     * 与项目"落袋优先于激进"的既有取向一致。
     */
    private val MAX_AMOUNT_MINOR = BigDecimal.valueOf(MAX_SAFE_MINOR_UNIT)

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

    /** 个人转账强语义："转账给/已转账给/转给 + 收款人"，命中即不作为消费证据。 */
    private val PERSONAL_TRANSFER_RECIPIENT_PATTERNS = listOf(
        Regex("""(?:已转账给|转账给|转给)\s*([^\s,，。；;￥¥0-9]{1,20})""")
    )

    /** 个人转账弱语义关键词：配合"无商户且无订单号"共同判定。 */
    private val PERSONAL_TRANSFER_SOFT_KEYWORDS = listOf("转账", "已转账")

    /**
     * 结果页时效窗口：当次支付结果页显示的时间就是支付当刻，与观察时刻相差
     * 应在几分钟内。用户翻看微信账单详情、聊天里的历史支付凭证时，页面同样
     * 含"支付成功+金额+商户"，但显示时间是历史时间（如几天前），据此拒绝。
     */
    private const val RESULT_PAGE_FRESHNESS_MILLIS = 30 * 60 * 1000L

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

        val occurredAtResolution = resolveOccurredAt(pageText, observedAt)
            ?: return PaymentResultParseResult.Failure(PaymentResultFailureReason.INVALID_OCCURRED_AT)
        // 历史信息回放闸门：页面显示时间距观察时刻超过时效窗口，说明用户在
        // 翻看历史账单详情或聊天里的支付凭证（页面同样含"支付成功+金额+商户"），
        // 不是当次支付结果页；静默拒绝，避免落出"商户陌生、时间错乱"的证据。
        if (
            occurredAtResolution.fromPage &&
            abs(occurredAtResolution.value - observedAt) > RESULT_PAGE_FRESHNESS_MILLIS
        ) {
            return PaymentResultParseResult.NotPaymentResult
        }
        val occurredAt = occurredAtResolution.value
        val amountMinor = extractAmountMinor(pageText)
            ?: return PaymentResultParseResult.Failure(PaymentResultFailureReason.INVALID_AMOUNT)

        // 提取订单号和商户；结构化字段仍供核心匹配器使用，原文哈希不会包含其数字值。
        // M5：订单号在提取点格式归一（trim/全角转半角/大写），跨源归并不被写法差异漏并。
        val orderId = OrderIdNormalizer.normalize(extractField(pageText, ORDER_ID_PATTERNS))
        val merchant = extractField(pageText, MERCHANT_PATTERNS)

        // 个人转账语义优先于消费证据：微信/支付宝向好友转账的成功页
        // （"转账给张三 ¥29.75"）收款人是个人而非商户，若落消费证据会把
        // 好友名当商户。强语义（转账给+收款人）直接判定；弱语义（仅"转账"
        // 关键词）需同时满足无商户且无订单号才判定，避免误伤电商支付。
        val recipient = extractField(pageText, PERSONAL_TRANSFER_RECIPIENT_PATTERNS)
        val isPersonalTransfer = recipient != null ||
            (
                PERSONAL_TRANSFER_SOFT_KEYWORDS.any { pageText.contains(it) } &&
                    merchant == null &&
                    orderId == null
                )
        if (isPersonalTransfer) {
            return PaymentResultParseResult.PersonalTransfer(
                PaymentPersonalTransfer(
                    recipient = recipient,
                    amountMinor = amountMinor,
                    occurredAt = occurredAt
                )
            )
        }

        val hashableText = sanitizeForHash(pageText, orderId)
        // orderId 在 hashableText 里已被替换成 `<order-id>`，单凭文本无法区分两笔不同
        // 订单的支付（同商户同额时会得到相同哈希）。把订单号的 SHA-256 摘要单独拼进
        // 哈希输入：保留区分力，且落库的仍只是不可逆摘要、不是原文。
        val orderIdDigest = orderId
            ?.takeIf { it.isNotBlank() }
            ?.let { sha256("order|$it") }
            .orEmpty()
        val rawTextHash = sha256("result|$sourcePackage|$orderIdDigest|$hashableText")

        // 事件键不包含页面指纹：同一支付结果页在微信 LiteApp 中会随动态节点变化产生
        // 多个指纹，旧实现会生成多条幂等键不同的孤儿待确认；现在按包名+时间桶+金额+订单号去重。
        // 桶宽 = [PaymentTiming.SAME_TXN_WINDOW_MILLIS]（15 秒）：桶宽必须 ≤ 去重窗口，
        // 否则相隔 15~30 秒的两笔仍会落进同一桶 ⇒ 同 `pendingId` ⇒ 被幂等直接合并，
        // 文本哈希判据根本走不到。
        val sourceEventKey = sha256(
            "result-event|$sourcePackage|${occurredAt / PaymentTiming.SAME_TXN_WINDOW_MILLIS}|" +
                "$amountMinor|${orderId.orEmpty()}"
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

    /**
     * B1 站内结果页负向守卫：判定页面文本是否更像「订单详情 / 订单列表 / 账单」页
     * 而非「当次支付结果页」。仅供站内结果页入口（购物包上下文）在调用
     * [parseResult] 之前使用；**支付包结果页腿不经过本守卫**（既有行为不变）。
     *
     * 判定口径（位置启发式，纯文本）：
     * 1. 无 [ORDER_PAGE_TITLE_TERMS] 任一标题词 ⇒ 不是订单页（false）；
     * 2. 含**强支付成功语义**（[STRONG_PAYMENT_SUCCESS_TERMS]，P8）⇒ 按结果页
     *    放行（false）—— 订单详情页的状态行惯用「已付款/交易完成」弱语义，
     *    不写「支付成功」；订单标题 + 支付成功横幅是当次支付结果页的典型形态；
     * 3. 有订单页标题词但无成功词 ⇒ 判为订单页（true，与 [parseResult] 双保险）；
     * 4. 两者皆有（弱语义）⇒ **先出现者代表页面标题**：订单页标题先于成功词 ⇒
     *    订单页（典型：「订单详情」标题 + "已付款"状态行）；成功词在前 ⇒ 真结果页
     *    （典型：「支付成功」标题 + "查看订单详情"按钮 —— 按钮词在后，不误杀）。
     *
     * ⚠️ 已知取舍：弱语义（已付款/交易完成）与订单标题共存的页面仍被抑制（宁可
     * 漏一张可补的卡，不可把可反复回看的历史订单页当新支付落卡）。P8 起强语义
     * 页面不再误杀，该取舍只作用于弱语义场景。真机校准见 B1 验证。
     *
     * @param pageText 页面文本（含隐藏文本，站内入口与支付包入口同款输入形态）。
     * @return true 表示疑似订单页，调用方不得据此产出支付证据。
     */
    internal fun isLikelyOrderDetailPage(pageText: String): Boolean {
        if (pageText.isBlank()) return false
        val orderTitleIndex = ORDER_PAGE_TITLE_TERMS
            .mapNotNull { term -> pageText.indexOf(term).takeIf { it >= 0 } }
            .minOrNull()
            ?: return false
        // P8：强支付成功语义直接放行 —— 与订单标题词的先后位置无关。
        if (STRONG_PAYMENT_SUCCESS_TERMS.any { pageText.contains(it) }) return false
        val successIndex = SUCCESS_KEYWORDS
            .mapNotNull { term -> pageText.indexOf(term).takeIf { it >= 0 } }
            .minOrNull()
            ?: return true
        return orderTitleIndex < successIndex
    }

    /** 时间解析结果：fromPage 区分"页面显示时间"与"回退观察时间"。 */
    private data class OccurredAtResolution(
        val value: Long,
        val fromPage: Boolean
    )

    /** 优先解析页面显示时间；页面缺失或格式非法时回退观察时间（fromPage=false）。 */
    private fun resolveOccurredAt(content: String, observedAt: Long): OccurredAtResolution? {
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
            parseDateTime(match)?.let { return OccurredAtResolution(it, fromPage = true) }
        }
        if (observedAt <= 0L) return null
        LABELED_TIME_PATTERN.find(normalized)?.let { match ->
            parseClosestTime(match, observedAt)?.let { return OccurredAtResolution(it, fromPage = true) }
        }
        return OccurredAtResolution(observedAt, fromPage = false)
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

    /**
     * 随帧变化的时间字样（P2）：支付成功页常带「05:59 后自动关闭」「12:30」等
     * 倒计时/时钟文本，逐帧变化会让同一笔的重复观测得到不同 rawTextHash ——
     * 「同文本哈希判同一笔」的去重主判据因此失效，页面停留超过 15 秒即双卡
     * （2026-09-30 ¥2.68 双卡事故的残留形态）。这些词只从哈希输入剥离，
     * 不参与任何字段解析（occurredAt 提取读的是未剥离的原文）。
     */
    private val COUNTDOWN_TEXT_PATTERN = Regex(
        """(?i)(还剩|剩余|倒计时|countdown)?\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?"""
    )
    private val CLOCK_TEXT_PATTERN = Regex(
        """\b(?:[01]?\d|2[0-3])\s*[:：]\s*[0-5]\d(?:\s*[:：]\s*[0-5]\d)?\b"""
    )
    private val RELATIVE_TIME_TEXT_PATTERN = Regex(
        """(?i)(刚刚|今天|昨天|前天|\d+\s*(秒|分钟|小时|天)前)"""
    )

    /**
     * 原文不落库；哈希前去除完整订单号、随帧变化的时间字样（P2）与独立敏感数字，
     * 保证同一笔支付结果页的重复观测得到稳定哈希（跨帧去重主判据）。
     */
    private fun sanitizeForHash(content: String, orderId: String?): String {
        var sanitized = content
        orderId?.takeIf { it.isNotBlank() }?.let { value ->
            sanitized = sanitized.replace(value, "<order-id>")
        }
        sanitized = COUNTDOWN_TEXT_PATTERN.replace(sanitized, "<time>")
        sanitized = CLOCK_TEXT_PATTERN.replace(sanitized, "<time>")
        sanitized = RELATIVE_TIME_TEXT_PATTERN.replace(sanitized, "<time>")
        return SENSITIVE_NUMBER_PATTERN.replace(sanitized, "<number>")
    }

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
