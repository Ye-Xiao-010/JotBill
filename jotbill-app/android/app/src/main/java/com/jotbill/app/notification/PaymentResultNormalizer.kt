package com.jotbill.app.notification

import com.jotbill.app.recognition.RecognitionPackages
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

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
    val rawTextHash: String,
    /** 退款接收账户提示文本（如"退回至余额"），仅退款路径提取；MVP 不映射具体账户。 */
    val receivingAccountHint: String? = null,
    /** 转账/还款目标账户提示文本（如"转到银行卡""还款至花呗"），由 TransferClassifier 匹配本地账户。 */
    val targetAccountHint: String? = null,
    /** 转账收款人姓名（如"转账给张三"）：命中即视为他人转账，不自动判定为自有账户转账。 */
    val recipientName: String? = null
)

/**
 * 阶段五 T4：手续费通知解析结果。
 *
 * 同一通知可能同时携带主交易（提现/转账/还款）与手续费两笔语义：
 * - [feePart] 手续费证据：type=fee 独立进待确认；amountMinor=0 表示金额解析失败；
 * - [mainPart] 主交易证据：非空时由调用方继续走 TransferClassifier 对应类型路径，
 *   与手续费的 sourceEventKey 通过语义后缀（fee/main）互相区分。
 */
data class FeeNotificationParse(
    val feePart: PaymentEvidenceInput,
    val mainPart: PaymentEvidenceInput?
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

    /** 阶段五 T2：退款证据状态标记，区分消费支付（SUCCESS）。 */
    const val PAYMENT_STATUS_REFUND = "REFUND"

    /** 阶段五 T3：转账证据状态标记。 */
    const val PAYMENT_STATUS_TRANSFER = "TRANSFER"

    /** 阶段五 T3：还款证据状态标记。 */
    const val PAYMENT_STATUS_REPAYMENT = "REPAYMENT"

    /** 阶段五 T4：手续费证据状态标记。 */
    const val PAYMENT_STATUS_FEE = "FEE"

    /**
     * 支持的支付应用包名白名单（有通知解析规则的包）。
     * B2 起为 `RecognitionPackages.NOTIFICATION_PACKAGES` 的别名 —— 包名的唯一
     * 出处在 [com.jotbill.app.recognition.RecognitionPackages]，本行不得再写字面量。
     */
    val SUPPORTED_PACKAGES: Set<String> = RecognitionPackages.NOTIFICATION_PACKAGES

    /** 优先匹配最终支付栏，避免把原价或优惠金额当成实际支付金额。 */
    private val FINAL_AMOUNT_PATTERNS = listOf(
        Regex("""(?:实付|实际支付|支付金额|付款金额|本次支付)\s*[:：]?\s*(?:¥|￥|CNY)?\s*([0-9]+\.?[0-9]*)"""),
        Regex("""(?:订单总额|订单金额|应付|合计|总计)\s*[:：]?\s*(?:¥|￥|CNY)?\s*([0-9]+\.?[0-9]*)"""),
        Regex("""(?:¥|￥|CNY)\s*([0-9]+\.?[0-9]*)\s*(?:实付|实际支付|支付金额|付款金额|本次支付)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元的?\s*(?:实付|实际支付|支付金额|付款金额|本次支付)""")
    )

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

    /**
     * 内置支付关键词表（N-0c）：供采集路径在用户配置为空时回退，
     * 与 [normalize] 的 `paymentKeywords` 默认值同源同值。
     */
    fun paymentKeywords(): List<String> = PAYMENT_KEYWORDS

    // ---------- 微信通知：身份闸与专属词表（W-3，依据真机样本） ----------

    /**
     * 「微信支付」服务通知的身份标识。
     *
     * 微信的聊天/群/公众号消息与支付通知**共用同一个包名**，聊天消息的 title 是联系人昵称、
     * 正文以昵称开头。没有这道闸门就会把营销文案记成账 —— 真实误判样本
     * （ControlECO 2026-10-23，两条都被自动入账）：
     * - `title=啊小号`　正文 `满0.62元消费冲击620元立减券` → 记成 ¥0.62
     * - `title=.。o0O`　正文 `玩赚中心｜消费抽620元` → 记成 ¥620.00
     *
     * 判据取自真机样本：微信账务通知两种形态（`title=微信支付 text=已支付¥12.80`、
     * `title=微信支付 text=退款到账通知`）都带这个标识。
     */
    private const val WECHAT_PAY_MARK = "微信支付"

    /**
     * 微信支出金额**锚点词**（正/反向正则与「词+¥」正则共用同一组）。
     *
     * ⚠️ 这组词**只用于把金额锚到词上**，不作为「是否算支付通知」的闸门 ——
     * 闸门仍走通用 [paymentKeywords]。原因：JotBill 侧尚无微信通知文案的真机样本，
     * 而 2026-10-28 真机上三条微信支付通知是用通用词表解析成功的，收窄词表有把能用的
     * 路弄断的风险。ControlECO 的误判样本（营销文案含「消费/支出/付款」）已由
     * [WECHAT_PAY_MARK] 身份闸拦住，不需要再靠词表收窄。
     */
    private val WECHAT_EXPENSE_WORDS = listOf("已支付", "已扣费", "自动扣款")

    private fun wechatWordAlt(): String = WECHAT_EXPENSE_WORDS.joinToString("|")

    /**
     * 金额捕获组：**不截断小数位**，完整捕获后交 [parseAmountMinor] 判非法（scale>2 即拒绝）。
     * 若在这里就限制成两位小数，`¥12.345` 会被截成 `12.34` 反而变成合法金额。
     */
    private const val WECHAT_AMT_GROUP = "([0-9]+(?:\\.[0-9]+)?)"

    /**
     * 微信支出金额（正向）：词后紧跟金额 +「元」收尾。
     *
     * 必须有「元」收尾 —— 关键词后的营销文案常带裸数字（真实样本
     * 「…自动扣款支付，点击领取6个支付宝积分」曾把 6 当成金额）。
     */
    private val WECHAT_EXPENSE_AMOUNT =
        Regex("""(?:${wechatWordAlt()})[^\d￥¥.]{0,8}${WECHAT_AMT_GROUP}元""")

    /**
     * 微信支出金额（反向）：金额在前（`12.80元的自动扣款`）。
     * 标点（，。）作硬边界，防止跨越营销尾巴去够更远的数字。
     */
    private val WECHAT_REVERSED_AMOUNT =
        Regex("""$WECHAT_AMT_GROUP\s*元[^，。\d]{0,8}的?\s*(?:${wechatWordAlt()})""")

    /** 微信兜底：文本里直接出现 ¥ 金额（`已支付¥12.80`）。 */
    private val WECHAT_CURRENCY_AMOUNT = Regex("""[￥¥]\s*$WECHAT_AMT_GROUP""")

    /**
     * 微信「词 + ¥金额」写法（`已支付¥12.80`）：词与 ¥ 之间不许有数字/货币符。
     *
     * 这条必须排在 [WECHAT_CURRENCY_AMOUNT] **之前** —— 兜底正则只认 ¥，
     * 遇到 `满¥620消费立减，已支付¥12.80` 会抓成 620（营销数字）。
     */
    private val WECHAT_WORD_THEN_CURRENCY =
        Regex("""(?:${wechatWordAlt()})[^\d￥¥.]{0,8}[￥¥]\s*$WECHAT_AMT_GROUP""")

    /**
     * 微信通知身份闸：`com.tencent.mm` 的通知只有「微信支付」服务通知才参与账务解析。
     * 非微信来源恒为 true —— 本闸只管微信，支付宝/云闪付不受影响。
     */
    internal fun wechatIdentityOk(sourcePackage: String, fullContent: String): Boolean =
        sourcePackage != WECHAT_PACKAGE || fullContent.contains(WECHAT_PAY_MARK)

    /**
     * 微信支出金额提取：先按词紧邻的正/反向正则，再退回 ¥ 兜底。
     * 与通用 [extractFirstValidAmount] 的差别是**不允许无词锚点的裸 `N 元`**
     * —— 那正是营销数字混进来的入口。
     */
    /**
     * 微信支出金额提取：先按词锚点的正/反向正则，再退回 ¥ 兜底。
     * 与通用 [extractFirstValidAmount] 的差别是**不允许无词锚点的裸 `N 元`**
     * —— 那正是营销数字混进来的入口。
     *
     * 每条正则都要**遍历全部候选**并跳过非法金额（0 / 超两位小数 / 超 Long），
     * 与 [extractFirstValidAmount] 同口径：不能因首个候选非法就丢掉整条通知。
     */
    private fun extractWechatAmount(content: String): Long? {
        for (pattern in listOf(
            WECHAT_EXPENSE_AMOUNT,
            WECHAT_REVERSED_AMOUNT,
            WECHAT_WORD_THEN_CURRENCY,
            WECHAT_CURRENCY_AMOUNT,
        )) {
            for (match in pattern.findAll(content)) {
                parseAmountMinor(match.groupValues[1])?.let { return it }
            }
        }
        return null
    }

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

    /**
     * 阶段五 T2：退款语义关键词。
     * 退款通知此前被归入非消费语义直接排除，现改为独立识别路径。
     */
    private val REFUND_KEYWORDS = listOf("退款", "退回", "返还")

    /** 退款失败/进行中关键词：命中即排除，防止"申请退款""退款处理中"被当成退款到账。 */
    private val REFUND_FAILURE_KEYWORDS = listOf(
        "退款失败", "退回失败", "退款处理中", "退款中", "待退款",
        "申请退款", "退款申请", "退款审核"
    )

    /** 退款金额匹配正则：优先匹配"退款金额/已退回"等明确退款金额表述。 */
    private val REFUND_AMOUNT_PATTERNS = listOf(
        Regex("""(?:退款金额|退回金额|返还金额|退款)\s*(?:人民币|CNY)?\s*(?:¥|￥)?\s*([0-9]+\.?[0-9]*)"""),
        Regex("""(?:¥|￥|CNY)\s*([0-9]+\.?[0-9]*)\s*(?:退款|退回|返还|已退回)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元?\s*(?:已退回|已退款|退款成功|退回|返还)""")
    )

    /** 退款订单号匹配正则：原交易号/原订单号优先，再回退通用订单号。 */
    private val REFUND_ORDER_ID_PATTERNS = listOf(
        Regex("""原交易号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""原订单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""订单号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""交易号[：:]\s*([A-Za-z0-9\-]{6,40})"""),
        Regex("""单号[：:]\s*([A-Za-z0-9\-]{6,40})""")
    )

    /** 退款接收账户提示匹配正则：如"退回至余额""已退回至招商银行储蓄卡"。 */
    private val RECEIVING_ACCOUNT_PATTERNS = listOf(
        Regex("""(?:退回至|退款至|返回至|已退回至)\s*([^\s,，。；;]{2,20})""")
    )

    /**
     * 阶段五 T3：转账语义关键词（资金离开支付应用的方向）。
     * "转入"（他人转入=收入）不在此列，由收入排除关键词先行过滤。
     */
    private val TRANSFER_KEYWORDS = listOf("转出", "转账", "转到", "转至", "提现")

    /** 阶段五 T3：还款语义关键词。 */
    private val REPAYMENT_KEYWORDS = listOf("还款", "已还", "偿还")

    /** 收入/入账语义排除关键词：转账识别前先排除他人转入、收款等场景。 */
    private val TRANSFER_INCOME_EXCLUDE_KEYWORDS = listOf(
        "收款", "到账", "收到", "转入", "红包", "领取", "发放", "工资", "报销"
    )

    /**
     * 转账/还款金额匹配正则：优先匹配明确的方向性金额表述，
     * 避免把余额数字或对方账户尾号当成金额。
     */
    private val TRANSFER_AMOUNT_PATTERNS = listOf(
        Regex("""(?:转出金额|转账金额|转至金额|转到金额|提现金额|还款金额|还款本金|已还金额)\s*[:：]?\s*(?:¥|￥|CNY)?\s*([0-9]+\.?[0-9]*)"""),
        Regex("""(?:¥|￥|CNY)\s*([0-9]+\.?[0-9]*)\s*(?:元)?\s*(?:转出|转账|转至|转到|提现|还款|已还)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元?\s*(?:已转出|已转账|已转至|已转到|已提现|已还款|还款成功)""")
    )

    /** 转账目标账户提示匹配正则：如"转至零钱""转到银行卡""提现到招商银行储蓄卡"。 */
    private val TRANSFER_TARGET_PATTERNS = listOf(
        Regex("""(?:转至|转到|转账至|提现至|提现到)\s*([^\s,，。；;(（]{2,20})""")
    )

    /** 还款目标账户提示匹配正则：如"还款至花呗""已还信用卡""花呗自动还款"。 */
    private val REPAY_TARGET_PATTERNS = listOf(
        Regex("""(?:还款至|还款到|偿还)\s*([^\s,，。；;(（]{2,20})"""),
        Regex("""已还\s*([^\s,，。；;(（]{2,20})"""),
        Regex("""(花呗|白条|借呗|信用卡|网商贷|备用金)\s*(?:自动)?还款""")
    )

    /** 他人转账收款人匹配正则：如"转账给张三""转给李四"；"付款给"是扫码消费语义，不在此列。 */
    private val RECIPIENT_PATTERNS = listOf(
        Regex("""(?:转账给|转给)\s*([^\s,，。；;(（]{1,20})""")
    )

    /**
     * 阶段五 T4：手续费语义关键词。
     * 手续费金额小、特征弱，一律进待确认，不自动入账。
     */
    private val FEE_KEYWORDS = listOf("手续费", "服务费", "提现费")

    /**
     * 手续费金额匹配正则：数字后不允许紧跟 %（排除"服务费率0.6%"类费率表述）。
     * 覆盖"手续费1元""服务费：0.5""收取手续费¥1""1元手续费"等常见格式。
     */
    private val FEE_AMOUNT_PATTERNS = listOf(
        Regex("""(?:手续费|服务费|提现费)\s*(?:为|是|共)?\s*[:：]?\s*(?:人民币|CNY)?\s*(?:¥|￥)?\s*([0-9]+\.?[0-9]*)(?!%)"""),
        Regex("""(?:收取|扣收|代收)\s*(?:手续费|服务费|提现费)\s*(?:¥|￥)?\s*([0-9]+\.?[0-9]*)(?!%)"""),
        Regex("""(?:¥|￥)\s*([0-9]+\.?[0-9]*)\s*(?:手续费|服务费|提现费)"""),
        Regex("""([0-9]+\.?[0-9]*)\s*元?\s*(?:手续费|服务费|提现费)""")
    )

    /**
     * 混合通知主交易金额匹配正则：动作词紧邻金额（如"提现1000元""还款2000"）。
     * 与 [TRANSFER_AMOUNT_PATTERNS]（方向性表述优先）互补，回退时排除手续费金额。
     */
    private val MAIN_AMOUNT_PATTERNS = listOf(
        Regex("""(?:提现|转出|转账|转至|转到|还款|偿还)\s*(?:人民币|CNY)?\s*(?:¥|￥)?\s*([0-9]+\.?[0-9]*)(?!%)""")
    )

    /** 交易时间匹配正则：HH:mm 或 HH:mm:ss（通知正文常见格式）。 */
    private val TIME_PATTERN = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?(?!\d)""")

    /**
     * 带业务标签的时间（P16）：通知正文常含多个时间（支付时间/到账时间/退款时间…），
     * 裸正则取「第一个时间」会取错 —— 优先认业务标签紧邻的时间，无标签时才回退
     * 裸时间。标签词覆盖四条语义路径（支付/退款/转账/手续费）。
     */
    private val LABELED_BUSINESS_TIME_PATTERN = Regex(
        """(?:支付时间|付款时间|交易时间|完成时间|退款时间|到账时间|转账时间|还款时间)""" +
            """\s*[:：]?\s*(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?"""
    )

    /** P6 业务键族的时间桶宽：同笔业务的重复通知按分钟桶归一为同一键。 */
    private const val BUSINESS_KEY_TIME_BUCKET_MILLIS = 60_000L
    private val SENSITIVE_NUMBER_PATTERN = Regex("""(?<![\p{L}\p{N}])\d{3,}(?:[.,]\d+)?(?![\p{L}\p{N}])""")

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
        notificationId: Int,
        titleBig: String = "",
        /**
         * 支付关键词（N-0c）：至少命中一个才视为消费支付。默认内置表；
         * 由 `PaymentNotificationConfig.filterKeywords` 传入用户配置。
         * **仅作用于本支付路径** —— 手续费/转账/退款各自的关键词仍是内置表
         * （设置页文案写的是"识别支付通知的关键词"，与此边界一致）。
         * 调用方传入空列表时应自行回退内置表，本函数不放大匹配面。
         */
        paymentKeywords: List<String> = PAYMENT_KEYWORDS
    ): PaymentEvidenceInput? {
        if (sourcePackage !in SUPPORTED_PACKAGES) return null

        val fullContent = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullContent.isBlank()) return null

        // W-3：微信身份闸 —— 聊天/群/公众号消息与支付通知共用同一个包名，
        // 不含「微信支付」标识的一律不参与账务解析（真实误判样本见 WECHAT_PAY_MARK）。
        if (!wechatIdentityOk(sourcePackage, fullContent)) return null

        // 排除非消费语义
        if (EXCLUDE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 排除支付失败/进行中通知：宁可漏记不可误记，失败通知绝不能落成成功证据
        if (FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 必须命中至少一个支付关键词（微信与其余来源同一套，见 WECHAT_EXPENSE_WORDS 的说明）
        if (paymentKeywords.isEmpty() || paymentKeywords.none { fullContent.contains(it) }) return null

        // 提取金额（分）：微信走词锚点正则（不允许裸 `N 元`），其余走通用
        val amountMinor = (
            if (sourcePackage == WECHAT_PACKAGE) extractWechatAmount(fullContent)
            else extractFirstValidAmount(fullContent)
            ) ?: return null
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
        val rawTextHash = sha256(
            "raw|$sourcePackage|${sanitizeForHash(fullContent, orderId)}"
        )

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

    /**
     * 阶段五 T2：解析退款通知正文，返回结构化退款证据输入；非退款通知返回 null。
     *
     * 与消费支付解析（[normalize]）分离：
     * - 退款关键词命中是前置条件（退款/退回/返还）；
     * - 排除退款失败/申请中语义（申请退款≠退款到账）；
     * - 金额优先按退款表述提取（避免把原订单金额当成退款金额）；
     * - 订单号优先取"原交易号/原订单号"，作为原交易关联键；
     * - sourceEventKey 使用 refund- 前缀，与支付事件键空间隔离；
     * - paymentStatus = REFUND，由调用方路由到退款链路（RefundLinker）。
     */
    fun normalizeRefund(
        sourcePackage: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        summaryText: String,
        tickerText: String,
        postTime: Long,
        notificationKey: String?,
        notificationId: Int,
        titleBig: String = ""
    ): PaymentEvidenceInput? {
        if (sourcePackage !in SUPPORTED_PACKAGES) return null

        val fullContent = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullContent.isBlank()) return null

        // 必须命中退款关键词
        if (REFUND_KEYWORDS.none { fullContent.contains(it) }) return null

        // 排除退款失败/申请中语义，再叠加通用失败关键词兜底
        if (REFUND_FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null
        if (FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 提取退款金额（分）：优先退款表述，回退通用金额格式
        val amountMinor = extractRefundAmountMinor(fullContent) ?: return null
        if (amountMinor <= 0L) return null

        // 提取订单号（原交易号优先）
        val orderId = extractField(fullContent, REFUND_ORDER_ID_PATTERNS)

        // 提取商户
        val merchant = extractField(fullContent, MERCHANT_PATTERNS)

        // 提取退款接收账户提示（如"退回至余额"）
        val receivingAccountHint = extractField(fullContent, RECEIVING_ACCOUNT_PATTERNS)

        // 发生时间：优先正文时间，回退通知时间
        val occurredAt = extractOccurredAt(fullContent, postTime)

        // sourceEventKey：refund- 前缀与支付事件键隔离。
        // P6 修复：键族改用**业务字段**（包名+金额+原交易单号+业务时间分钟桶），
        // 不再混入 notificationKey/notificationId/postTime —— 同一条退款通知被系统
        // 或应用重发时会拿到新通知 id/新送达时间，旧键族因此失效、重复退款各落一卡。
        // 业务字段对"同一笔退款"稳定：正文含时间时 occurredAt 由正文解析，重发不变；
        // 正文无时间时回退 postTime（与旧实现同样不稳，不更差）。
        val sourceEventKey = sha256(
            "refund-event|$sourcePackage|$amountMinor|${orderId.orEmpty()}|" +
                occurredAt / BUSINESS_KEY_TIME_BUCKET_MILLIS
        )

        val rawTextHash = sha256(
            "refund-raw|$sourcePackage|${sanitizeForHash(fullContent, orderId)}"
        )

        return PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = merchant,
            orderId = orderId,
            paymentStatus = PAYMENT_STATUS_REFUND,
            sourceEventKey = sourceEventKey,
            rawTextHash = rawTextHash,
            receivingAccountHint = receivingAccountHint
        )
    }

    /** 退款金额提取：先按退款表述匹配，再回退通用金额格式。 */
    private fun extractRefundAmountMinor(content: String): Long? {
        for (pattern in REFUND_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1]) ?: continue
        }
        return extractAmountMinor(content)
    }

    /**
     * 阶段五 T3：解析转账/还款通知正文，返回结构化证据输入；非转账/还款通知返回 null。
     *
     * 语义路由（还款优先于转账判断，"信用卡还款"也含"还款"关键词）：
     * - 先排除收入/入账语义（他人转入、收款、红包等），避免"收到转账"被误判为转出；
     * - 排除手续费/服务费通知（属于阶段五 T4 手续费路径，不在本函数处理）；
     * - 还款关键词命中 → paymentStatus = REPAYMENT；否则转账关键词命中 → TRANSFER；
     * - 金额优先按方向性表述提取（避免把余额或尾号当金额）；
     * - 目标账户提示与收款人姓名分别提取：收款人命中即他人转账，
     *   由 TransferClassifier 按支出语义转待确认，不自动判定为自有账户转账；
     * - sourceEventKey 使用 transfer- 前缀（还款同样归入该键空间），与支付/退款事件键隔离。
     */
    fun normalizeTransfer(
        sourcePackage: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        summaryText: String,
        tickerText: String,
        postTime: Long,
        notificationKey: String?,
        notificationId: Int,
        titleBig: String = ""
    ): PaymentEvidenceInput? {
        if (sourcePackage !in SUPPORTED_PACKAGES) return null

        val fullContent = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullContent.isBlank()) return null

        // 手续费/服务费通知属于 T4 路径，此处直接放弃，防止被当成转账本金
        if (fullContent.contains("手续费") || fullContent.contains("服务费")) return null

        // 排除收入/入账语义：转账识别只关心资金离开支付应用的方向
        if (TRANSFER_INCOME_EXCLUDE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 语义判定：还款优先（"信用卡还款"同时含"还款"；"已还"不与转账关键词共存）
        val isRepayment = REPAYMENT_KEYWORDS.any { fullContent.contains(it) }
        val isTransfer = !isRepayment && TRANSFER_KEYWORDS.any { fullContent.contains(it) }
        if (!isRepayment && !isTransfer) return null

        // 排除失败/进行中语义（叠加通用失败关键词兜底）
        if (FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 金额：优先方向性表述，回退通用金额格式
        val amountMinor = extractTransferAmountMinor(fullContent) ?: return null
        if (amountMinor <= 0L) return null

        val orderId = extractField(fullContent, ORDER_ID_PATTERNS)

        // 目标账户提示：还款取还款目标模式，转账取转账目标模式
        val targetAccountHint = if (isRepayment) {
            extractField(fullContent, REPAY_TARGET_PATTERNS)
        } else {
            extractField(fullContent, TRANSFER_TARGET_PATTERNS)
        }

        // 收款人：仅转账语义提取（还款无收款人概念）
        val recipientName = if (isRepayment) null else extractField(fullContent, RECIPIENT_PATTERNS)

        val occurredAt = extractOccurredAt(fullContent, postTime)

        // P6 修复：键族改用业务字段（包名+金额+单号+目标账户/收款人提示+业务时间
        // 分钟桶），不再混入 notificationKey/notificationId/postTime —— 通知重发
        // 换系统 id/送达时间会让旧键失效，同一笔转账重复落卡。目标提示参与键构成：
        // 同额不同目标（还招行 vs 还工行）是两笔，不得共键吞并。
        val sourceEventKey = sha256(
            "transfer-event|$sourcePackage|$amountMinor|${orderId.orEmpty()}|" +
                "${targetAccountHint.orEmpty()}|${recipientName.orEmpty()}|" +
                occurredAt / BUSINESS_KEY_TIME_BUCKET_MILLIS
        )

        val rawTextHash = sha256(
            "transfer-raw|$sourcePackage|${sanitizeForHash(fullContent, orderId)}"
        )

        return PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            merchant = null,
            orderId = orderId,
            paymentStatus = if (isRepayment) PAYMENT_STATUS_REPAYMENT else PAYMENT_STATUS_TRANSFER,
            sourceEventKey = sourceEventKey,
            rawTextHash = rawTextHash,
            targetAccountHint = targetAccountHint,
            recipientName = recipientName
        )
    }

    /** 转账/还款金额提取：先按方向性表述匹配，再回退通用金额格式。 */
    private fun extractTransferAmountMinor(content: String): Long? {
        for (pattern in TRANSFER_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1]) ?: continue
        }
        return extractAmountMinor(content)
    }

    /**
     * 阶段五 T4：解析手续费通知正文，返回手续费与主交易的拆分结果；非手续费通知返回 null。
     *
     * 接管条件（防止"订单30元含服务费"类消费通知被误抢）：
     * - 手续费关键词（手续费/服务费/提现费）命中，且满足其一：
     *   a) 同时含转账/还款主交易语义（如"提现1000元，手续费1元"）→ 拆分为 fee + main；
     *   b) 纯手续费通知（无任何消费支付关键词）→ 仅 fee；
     * - 含消费支付关键词但无转账/还款语义的通知（消费含服务费）不强拆，返回 null 走消费路径。
     *
     * 拆分口径：
     * - 手续费金额优先按手续费表述提取；解析失败时返回 amountMinor=0 的 feePart，
     *   由 FeeExtractor 整条进待确认并标记解析失败（FEE_AMOUNT_UNPARSED），不强行入账；
     * - 主交易金额按方向性表述 → 动作词紧邻金额 → 通用金额（排除手续费数值）提取，
     *   提取失败则不生成 mainPart（只记录手续费）；
     * - sourceEventKey 语义后缀区分：fee 键空间 fee-event|...|fee，
     *   main 键空间 transfer-event|...|main，同一通知两笔互不冲突；
     * - 主交易的金额解析复用 [normalizeTransfer] 同款字段（目标账户/收款人），
     *   由调用方交给 TransferClassifier 走既有转账/还款路径。
     */
    fun normalizeFee(
        sourcePackage: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        summaryText: String,
        tickerText: String,
        postTime: Long,
        notificationKey: String?,
        notificationId: Int,
        titleBig: String = ""
    ): FeeNotificationParse? {
        if (sourcePackage !in SUPPORTED_PACKAGES) return null

        val fullContent = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (fullContent.isBlank()) return null

        // 必须命中手续费关键词
        if (FEE_KEYWORDS.none { fullContent.contains(it) }) return null

        // 排除失败/进行中语义
        if (FAILURE_KEYWORDS.any { fullContent.contains(it) }) return null

        // 主交易语义判定：还款优先，其次转账；先排除收入/入账语义
        val hasIncomeSemantics = TRANSFER_INCOME_EXCLUDE_KEYWORDS.any { fullContent.contains(it) }
        val hasMainSemantics = !hasIncomeSemantics && (
            REPAYMENT_KEYWORDS.any { fullContent.contains(it) } ||
                TRANSFER_KEYWORDS.any { fullContent.contains(it) }
            )

        // 消费支付关键词存在但无主交易语义：不强拆，走消费路径
        val hasPaymentKeywords = PAYMENT_KEYWORDS.any { fullContent.contains(it) }
        if (!hasMainSemantics && hasPaymentKeywords) return null

        // ---- 手续费金额：解析失败返回 0 标记（待确认由 FeeExtractor 落解析失败原因）----
        val feeAmountMinor = extractFeeAmountMinor(fullContent) ?: 0L

        // ---- 主交易金额：方向性表述 > 动作词紧邻金额 > 通用金额（排除手续费数值）----
        val mainAmountMinor = if (hasMainSemantics) {
            extractMainAmountMinor(fullContent, excludeMinor = feeAmountMinor)
        } else {
            null
        }

        val occurredAt = extractOccurredAt(fullContent, postTime)
        val orderId = extractField(fullContent, ORDER_ID_PATTERNS)

        // ---- 手续费证据：fee-event 键空间 + fee 语义后缀 ----
        // P6 修复：键族改用业务字段（原因见 normalizeRefund 同段注释）。
        val feeSourceEventKey = sha256(
            "fee-event|$sourcePackage|$feeAmountMinor|${orderId.orEmpty()}|fee|" +
                occurredAt / BUSINESS_KEY_TIME_BUCKET_MILLIS
        )
        val feePart = PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = feeAmountMinor,
            occurredAt = occurredAt,
            merchant = null,
            orderId = orderId,
            paymentStatus = PAYMENT_STATUS_FEE,
            sourceEventKey = feeSourceEventKey,
            rawTextHash = sha256(
                "fee-raw|$sourcePackage|${sanitizeForHash(fullContent, orderId)}"
            )
        )
        if (mainAmountMinor == null || mainAmountMinor <= 0L) {
            return FeeNotificationParse(feePart, mainPart = null)
        }

        // ---- 主交易证据：还款优先判定，复用转账/还款字段提取 ----
        val isRepayment = REPAYMENT_KEYWORDS.any { fullContent.contains(it) }
        val targetAccountHint = if (isRepayment) {
            extractField(fullContent, REPAY_TARGET_PATTERNS)
        } else {
            extractField(fullContent, TRANSFER_TARGET_PATTERNS)
        }
        val recipientName = if (isRepayment) null else extractField(fullContent, RECIPIENT_PATTERNS)

        // transfer-event 键空间 + main 语义后缀，与纯转账通知键天然区分
        // P6 修复：键族改用业务字段（原因见 normalizeRefund 同段注释）。
        val mainSourceEventKey = sha256(
            "transfer-event|$sourcePackage|$mainAmountMinor|${orderId.orEmpty()}|main|" +
                occurredAt / BUSINESS_KEY_TIME_BUCKET_MILLIS
        )

        val mainPart = PaymentEvidenceInput(
            sourcePackage = sourcePackage,
            amountMinor = mainAmountMinor,
            occurredAt = occurredAt,
            merchant = null,
            orderId = orderId,
            paymentStatus = if (isRepayment) PAYMENT_STATUS_REPAYMENT else PAYMENT_STATUS_TRANSFER,
            sourceEventKey = mainSourceEventKey,
            rawTextHash = sha256(
                "transfer-raw|$sourcePackage|${sanitizeForHash(fullContent, orderId)}"
            ),
            targetAccountHint = targetAccountHint,
            recipientName = recipientName
        )
        return FeeNotificationParse(feePart, mainPart)
    }

    /** 手续费金额提取：只按手续费表述匹配，失败返回 null（不回退通用模式，防止误取主交易金额）。 */
    private fun extractFeeAmountMinor(content: String): Long? {
        for (pattern in FEE_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1]) ?: continue
        }
        return null
    }

    /**
     * 混合通知主交易金额提取：方向性表述 > 动作词紧邻金额 > 通用金额。
     * 通用回退逐个检查命中的金额，跳过与手续费数值相同的匹配，防止把手续费当成主交易。
     */
    private fun extractMainAmountMinor(content: String, excludeMinor: Long): Long? {
        for (pattern in TRANSFER_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1]) ?: continue
        }
        for (pattern in MAIN_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1]) ?: continue
        }
        // 通用回退：取第一个与手续费金额不同的有效金额
        for (pattern in AMOUNT_PATTERNS) {
            for (match in pattern.findAll(content)) {
                val amount = parseAmountMinor(match.groupValues[1]) ?: continue
                if (amount != excludeMinor) return amount
            }
        }
        return null
    }

    /** 从文本中提取金额并转换为分（Long），无法提取时返回 null。 */
    private fun extractAmountMinor(content: String): Long? {
        for (pattern in FINAL_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            return parseAmountMinor(match.groupValues[1])
        }
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            val amountStr = match.groupValues.getOrNull(1) ?: continue
            return parseAmountMinor(amountStr)
        }
        return null
    }

    /**
     * 主消费证据金额提取：优先最终支付栏；匹配到非法候选（0、超两位小数、超 Long）
     * 时继续尝试后续候选，不因首个匹配失败而丢弃整条通知。
     */
    private fun extractFirstValidAmount(content: String): Long? {
        for (pattern in FINAL_AMOUNT_PATTERNS) {
            val match = pattern.find(content) ?: continue
            parseAmountMinor(match.groupValues[1])?.let { return it }
        }
        for (pattern in AMOUNT_PATTERNS) {
            for (match in pattern.findAll(content)) {
                parseAmountMinor(match.groupValues.getOrNull(1) ?: continue)?.let { return it }
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
        return try {
            amount.movePointRight(2).longValueExact().takeIf { it > 0L }
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

    /** 原文只在内存中参与解析，哈希前移除订单号和连续敏感数字。 */
    private fun sanitizeForHash(content: String, orderId: String?): String {
        var sanitized = content
        orderId?.takeIf { it.isNotBlank() }?.let { value ->
            sanitized = sanitized.replace(value, "<order-id>")
        }
        return SENSITIVE_NUMBER_PATTERN.replace(sanitized, "<number>")
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
        return sha256(
            "notification-event|$sourcePackage|${notificationKey.orEmpty()}|" +
                "$notificationId|$postTime|$amountMinor|${orderId.orEmpty()}"
        )
    }

    /**
     * 从正文提取交易发生时间，优先级高于通知到达时间。
     *
     * 支持"HH:mm"和"HH:mm:ss"格式（通知正文常见），结合通知时间的日期部分还原时间戳；
     * 正文只有时分时，在通知日期前一天/当天/后一天中选最接近 postTime 的时间，
     * 覆盖跨午夜的双向场景；解析失败时返回通知时间。
     *
     * P16：多时间字段并存时**优先带业务标签的时间**（支付时间/退款时间等），
     * 无标签时间才回退「正文第一个 HH:mm」—— 旧实现直接取第一个时间，
     * "支付时间 10:00 … 到账时间 10:05" 类通知可能误取后一个无关时间。
     * 无法解析时返回通知时间。
     */
    private fun extractOccurredAt(content: String, postTime: Long): Long {
        val match = LABELED_BUSINESS_TIME_PATTERN.find(content)
            ?: TIME_PATTERN.find(content)
            ?: return postTime
        val hour = match.groupValues[1].toIntOrNull() ?: return postTime
        val minute = match.groupValues[2].toIntOrNull() ?: return postTime
        val second = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return postTime

        val postInstant = Instant.ofEpochMilli(postTime)
        .atZone(ZoneId.systemDefault())
        val occurred = (-1L..1L)
            .map { offset ->
                postInstant.toLocalDate().plusDays(offset)
                    .atTime(hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
        }
        return occurred.minByOrNull { candidate -> abs(candidate - postTime) } ?: postTime
    }

    /** 计算 SHA-256 哈希，返回十六进制字符串。 */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
