package com.jotbill.app.bookkeeping

import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.notification.PaymentResultNormalizer
import java.security.MessageDigest

/**
 * 银行短信的规则解析（通知监听链路）。
 *
 * ## 为什么不再走无障碍通知事件
 *
 * 原设计：短信经无障碍 `TYPE_NOTIFICATION_STATE_CHANGED` 投递给规则引擎。
 * 真机 2026-11-16（Xiaomi 25128PNA1C / Android 17）实证：银行短信通知为
 * **静默通知**，系统不向无障碍服务投递通知事件 —— 两笔真实支付与退款共
 * 4 条银行短信在引擎侧零日志，短信渠道等于死链。改为由已在运行的
 * `NotificationListenerService`（PaymentNotificationService）投递，监听器
 * 不受 alerting 限制，且能拿到 bigText 全文。
 *
 * ## 解析口径
 *
 * - 消费/支出：完全复用内置规则资产 `com.android.mms/android.app.Notification`
 *   （招行精确规则 + 通用银行短信 singleField 提取器），与引擎同源；
 * - 退款到账：银行短信（如「到账人民币20.6元 (拼多多-实物消费-拼多多平台商户)」）
 *   不含「退款」字样，通用规则反而会因正文里的「消费」误判为支出 —— 必须先由
 *   [parseRefund] 用「到账 + 商户语义 + 金额」判据截走，交给 RefundLinker 处理
 *   （无原单时落待确认卡，不静默丢、不误记支出）。
 */
internal object BankSmsNotificationParser {

    const val PACKAGE = "com.android.mms"
    const val SICHUAN_RURAL_BANK = "四川农商银行"

    /** 规则资产中的通知规则 key（与 BookkeepingChannels 的 mms 渠道同包名）。 */
    const val NOTIFICATION_KEY = "$PACKAGE/android.app.Notification"

    /** 退款到账判定：到账 + 商户语义（退款/退货/退回显式词、商户关键词或括号商户名）。 */
    private val REFUND_EXPLICIT_MARKERS = listOf("退款", "退货", "退回")

    private val MERCHANT_REFUND_MARKERS = listOf("平台商户", "商户")

    /**
     * 括号商户形态：「到账人民币19.8元(拼多多)」——四川农商银行简版格式，
     * 邮件/工资类入账也会带括号（如「（工资）」），用排除词区分，宁可漏判
     * 也不给非商户入账造退款卡。
     */
    private val MERCHANT_PAREN_PATTERN = Regex("""(?:元|¥|￥)\s*[（(]([^)）\-]{2,20})[)）]""")

    private val NON_MERCHANT_PAREN_TERMS = listOf(
        "工资", "奖金", "利息", "理财", "报销", "补贴", "分红", "税", "贷款"
    )

    /**
     * 退款金额：必须紧跟「到账/退款/退回/退货」语义且带货币符号，避免把
     * 「退款申请已受理」这类无金额通知或广告里的裸数字误当退款。
     */
    private val REFUND_AMOUNT_PATTERN = Regex(
        """(?:到账|退款|退回|退货)[^0-9]{0,12}(?:人民币|RMB|¥|￥)\s*(\d+(?:[.,]\d+)?)\s*元?"""
    )

    /**
     * 简版退款金额：「退货，收入金额39.90元」（邮储 2026-11-16 真机格式）——
     * 无货币符号，靠「收入金额」标签定位；仅在显式退款词（退货/退款/退回）
     * 已命中的前提下使用。
     */
    private val REFUND_AMOUNT_FALLBACK_PATTERN = Regex(
        """(?:收入金额|退款金额|退货金额|退回金额)\s*[:：]?\s*(\d+(?:[.,]\d+)?)\s*元?"""
    )

    /** 商户前缀：「(拼多多-实物消费-拼多多平台商户)」→ 拼多多。 */
    private val MERCHANT_PREFIX_PATTERN = Regex("""[（(]([^)）\-]{2,20})-""")

    private val BANK_NAME_BRACKET_PATTERN = Regex("""[【\[]([\p{IsHan}]{2,20}银行)[】\]]""")
    private val OWN_CARD_PATTERN = Regex(
        """您(?:的)?\s*(?:(信用卡|借记卡|储蓄卡|银行卡|账户|卡)\s*)?""" +
            """(?:尾号(?:为)?\s*([0-9]{4})(?![0-9])|卡号\s*([0-9]{4,19}(?:[ \t-]+[0-9]{3,4}(?![0-9年月日:：])){0,4})(?![0-9]))""" +
            """(?:\s*(?:的)?\s*(信用卡|借记卡|储蓄卡|银行卡|账户|卡))?"""
    )
    private val POSTAL_DEPOSIT_PATTERN = Regex(
        """您(?:的)?\s*尾号\s*[0-9]{4}(?:的)?账户\s*(?:快捷支付|退货|退款).*?[，,；;。]\s*余额\s*[0-9]"""
    )
    private val SICHUAN_DEPOSIT_PATTERN = Regex(
        """您(?:的)?\s*尾号(?:为)?\s*[0-9]{4}\s*(?:的)?\s*账户\s*""" +
            """(?:(?:[0-9]{2,4}年)?[0-9]{1,2}月[0-9]{1,2}日\s*[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\s*)?""" +
            """(?:(?:通过)?快捷支付\s*)?(?:支出|扣款|到账|退款|退货)[^。\r\n]{0,120}""" +
            """[，,；;]\s*(?:账户)?余额\s*[:：]?\s*[0-9]"""
    )
    private val POSTAL_BANK_NAMES = setOf("邮储银行", "中国邮政储蓄银行", "邮政储蓄银行")
    private val CREDIT_TERMS = Regex("信用卡|可用额度|应还|信用额度")

    private fun extractBankCard(content: String): BankCardEvidence? {
        val bankName = BANK_NAME_BRACKET_PATTERN.findAll(content)
            .map { it.groupValues[1] }.distinct().toList().singleOrNull() ?: return null
        val cards = OWN_CARD_PATTERN.findAll(content).toList()
        val tails = cards.map { card ->
            val number = card.groupValues[2].ifEmpty { card.groupValues[3] }.filter { it in '0'..'9' }
            if (number.length !in 4..19) return null
            number.takeLast(4)
        }
        val tail = tails.distinct().singleOrNull() ?: return null
        val types = cards.flatMap { listOf(it.groupValues[1], it.groupValues[4]) }.mapNotNull {
            when (it) {
                "信用卡" -> "credit_card"
                "借记卡", "储蓄卡" -> "bank_debit"
                else -> null
            }
        }.distinct()
        val depositTemplate = when (bankName) {
            in POSTAL_BANK_NAMES -> POSTAL_DEPOSIT_PATTERN
            SICHUAN_RURAL_BANK -> SICHUAN_DEPOSIT_PATTERN
            else -> null
        }
        val hasCreditTerms = CREDIT_TERMS.containsMatchIn(content)
        val subtype = when {
            types.size == 1 -> types.single()
            types.isEmpty() && !hasCreditTerms && depositTemplate?.containsMatchIn(content) == true -> "bank_debit"
            else -> null
        }
        return BankCardEvidence(bankName, tail, subtype, creditOnly = subtype == null && hasCreditTerms)
    }

    private const val TIME_BUCKET_MILLIS = 15_000L

    /**
     * 消费/支出短信：按内置规则资产抽取；命中返回待入账记录，未命中返回 null。
     *
     * 规则定位统一使用拼接全文（标题 + 正文 + bigText），通知链路拿不到
     * contentDescription，[PageRule.locateAttribute] 的 0/1 在此同为全文匹配。
     */
    fun parseExpense(
        registry: PageRuleRegistry,
        content: String,
        extractTime: Long
    ): AutoBookkeepingEngine.ExtractedRecord? {
        if (content.isBlank()) return null
        val rules = registry.rulesFor(NOTIFICATION_KEY) ?: return null
        for (rule in rules) {
            if (rule.locateAttribute !in 0..1) continue
            if (!rule.isLocatedBy(content)) continue
            val fields = rule.extractFrom(content) ?: continue
            val amountMinor =
                AutoBookkeepingEngine.parseAmountMinor(fields["amount"]) ?: 0L
            return AutoBookkeepingEngine.ExtractedRecord(
                fields = fields,
                ruleId = rule.ruleId,
                ruleNote = rule.note,
                fromApp = rule.fromApp,
                jotbillType = rule.jotbillType,
                sourcePackage = PACKAGE,
                extractTime = extractTime,
                // 幂等键：包名 + 时间桶 + 金额 + 正文指纹。短信分组更新会重投，
                // 正文指纹区分不同笔；同笔重投由 60 秒补全窗兜底归并。
                sourceEventKey = sha256(
                    "bank-sms-expense|$PACKAGE|${extractTime / TIME_BUCKET_MILLIS}|" +
                        "$amountMinor|${content.hashCode()}"
                ),
                bankCard = extractBankCard(content)
            )
        }
        return null
    }

    /**
     * 退款到账短信：命中返回退款证据（paymentStatus=REFUND），否则 null。
     *
     * 判据（缺一不可）：
     * 1. 含「到账」；
     * 2. 含退款显式词，或商户回款形态（平台商户/商户）；
     * 3. 能解析出紧跟语义的金额。
     *
     * 典型样本（四川农商银行）：
     * 「【四川农商银行】您尾号2510的账户11月16日09:59到账人民币20.6元
     *  (拼多多-实物消费-拼多多平台商户) , …户名为拼多多平台商户。」
     */
    fun parseRefund(content: String, occurredAt: Long): PaymentEvidenceInput? {
        if (content.isBlank()) return null
        val credit = content.contains("到账")
        val explicit = REFUND_EXPLICIT_MARKERS.any { content.contains(it) }
        // 商户语义只在「到账」语境下成立：支出短信的商户括号不是退款。
        val merchantRefund = credit && MERCHANT_REFUND_MARKERS.any { content.contains(it) }
        val parenMerchant = if (credit) {
            MERCHANT_PAREN_PATTERN.find(content)?.groupValues?.get(1)
                ?.takeIf { name -> NON_MERCHANT_PAREN_TERMS.none { name.contains(it) } }
        } else {
            null
        }
        if (!explicit && !merchantRefund && parenMerchant == null) return null
        val amountText = REFUND_AMOUNT_PATTERN.find(content)?.groupValues?.get(1)
            ?: REFUND_AMOUNT_FALLBACK_PATTERN.find(content)?.groupValues?.get(1)
            ?: return null
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(amountText) ?: return null
        if (amountMinor <= 0L) return null
        val merchant = MERCHANT_PREFIX_PATTERN.find(content)?.groupValues?.get(1) ?: parenMerchant
        val textHash = sha256(content)
        val bankCard = extractBankCard(content)
        val receivingHint = bankCard?.let { "${it.bankName} 尾号${it.tail}" }
            ?: BANK_NAME_BRACKET_PATTERN.find(content)?.groupValues?.get(1)
        return PaymentEvidenceInput(
            sourcePackage = PACKAGE,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = merchant,
            orderId = null,
            paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
            // 键只取「包名+时间桶+金额」：短信通知会随分组内容更新反复重投
            // （[2条]→[3条] 文本变化），带正文哈希会为同一笔退款生成多张卡。
            sourceEventKey = sha256(
                "bank-sms-refund|$PACKAGE|${occurredAt / TIME_BUCKET_MILLIS}|$amountMinor"
            ),
            rawTextHash = textHash,
            receivingAccountHint = receivingHint,
            bankCard = bankCard
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
