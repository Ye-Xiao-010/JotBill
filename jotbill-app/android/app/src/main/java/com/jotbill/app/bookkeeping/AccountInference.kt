package com.jotbill.app.bookkeeping

import android.content.Context
import com.jotbill.app.data.entity.AccountEntity
import org.json.JSONArray

data class BankCardEvidence(
    val bankName: String,
    val tail: String,
    val subtype: String?,
    val creditOnly: Boolean = false
)

/**
 * 账户推断结果（三态）：唯一命中 / 多候选需人工核对 / 无命中。
 *
 * 用于"账户解析读法一"链路（规则 → 信号推断 → 包名映射 → 现金兜底）中的
 * 信号推断层：多候选时**不得猜测**，由调用方转待确认。
 */
internal sealed interface AccountResolution {
    /**
     * 唯一命中，可安全使用。
     *
     * @param rank 证据等级（见 [AccountInference.Rank]）：后续信号只有等级更高时
     *   才允许升级账单账户，防止钱包信号把短信已经确定的银行卡覆盖回去。
     */
    data class Resolved(
        val accountId: String,
        val rank: Int = AccountInference.Rank.WALLET
    ) : AccountResolution

    /** 多候选命中（如同银行多账户），须转人工核对，不得猜测 */
    data object NeedsReview : AccountResolution

    /** 无任何候选命中，落到推断链的下一级 */
    data object Unresolved : AccountResolution
}

/**
 * 账户推断共享函数（信号层 + 包名映射 + 现金兜底）。
 *
 * 唯一事实源：自动入账器与结算匹配共用本对象，不写两份逻辑。
 * 推断顺序（2026-11-17 拍板）：
 * 1. **卡尾号**：短信卡号/尾号 → 用户填写了 cardTail 的账户，唯一命中才用；
 * 2. 渠道词（微信/支付宝/余额宝）→ 对应钱包账户；
 * 3. 短信银行名 → 本地账户名匹配（**唯一命中才用**，多命中 [AccountResolution.NeedsReview]）；
 * 4. 包名映射（微信/支付宝 → 对应平台账户）；
 * 5. 现金兜底（链尾）。
 *
 * 规则（rule_account）优先级更高，由调用方先行判定后才进入本对象。
 */
internal object AccountInference {

    /** 现金兜底账户 ID（链尾兜底，仅在规则/信号/包名映射全部无命中时使用）。 */
    const val CASH_FALLBACK_ACCOUNT_ID: String = "default-account-cash"

    /** 微信支付默认账户 ID（包名映射）。 */
    const val WECHAT_PAY_ACCOUNT_ID: String = "default-account-wechat"

    /** 支付宝默认账户 ID（包名映射）。 */
    const val ALIPAY_ACCOUNT_ID: String = "default-account-alipay"

    /** 微信支付包名（包名映射键）。 */
    internal const val WECHAT_PACKAGE: String = "com.tencent.mm"

    /** 支付宝包名（包名映射键）。 */
    internal const val ALIPAY_PACKAGE: String = "com.eg.android.AlipayGphone"

    /**
     * 账户证据等级：只升不降。
     *
     * 用户规则最高；银行短信（银行名/卡尾号）高于钱包渠道；钱包高于现金。
     * 同级证据不覆盖既有账户，避免微信/支付宝/银行之间来回翻转。
     */
    object Rank {
        const val NONE = 0
        const val CASH = 1
        const val WALLET = 2
        const val BANK = 3
        const val RULE = 4
    }

    /**
     * 从信号推断账户（读法一的信号层）。
     *
     * 当前唯一可用信号 = 短信的银行名/卡尾号；普通消费通知/结果页不暴露支付方式。
     * 只在**启用账户**中匹配（停用账户不命中）。
     */
    fun inferFromSignal(bankHint: String?, accounts: List<AccountEntity>): AccountResolution {
        val bankAccounts = accounts.filter { it.isActive && it.subtype in BANK_CARD_SUBTYPES }
        val matched = matchAccountsByName(bankAccounts, bankHint)
        return when {
            matched.size == 1 -> AccountResolution.Resolved(matched.first().id, Rank.BANK)
            matched.size > 1 -> AccountResolution.NeedsReview
            else -> AccountResolution.Unresolved
        }
    }

    /**
     * 信号 → 包名映射 → 现金兜底 的完整推断（规则之外的层级）。
     */
    fun inferSignalThenFallback(
        sourcePackage: String?,
        bankHint: String?,
        accounts: List<AccountEntity>
    ): AccountResolution {
        // 1. 信号层：短信银行名匹配（唯一命中才用）
        inferFromSignal(bankHint, accounts).let { if (it != AccountResolution.Unresolved) return it }
        // 2. 包名映射（微信/支付宝 → 平台账户）
        val mapped = when (sourcePackage) {
            WECHAT_PACKAGE -> WECHAT_PAY_ACCOUNT_ID
            ALIPAY_PACKAGE -> ALIPAY_ACCOUNT_ID
            else -> null
        }
        if (mapped != null) {
            val account = accounts.firstOrNull { it.id == mapped && it.isActive }
            if (account != null) return AccountResolution.Resolved(account.id, Rank.WALLET)
        }
        // 3. 现金兜底（链尾）
        val cash = accounts.firstOrNull { it.id == CASH_FALLBACK_ACCOUNT_ID && it.isActive }
        return if (cash != null) {
            AccountResolution.Resolved(cash.id, Rank.CASH)
        } else {
            AccountResolution.Unresolved
        }
    }

    /**
     * 目标账户名匹配（与 TransferClassifier 同语义）：先精确匹配（忽略大小写），
     * 再双向包含匹配（账户名至少 2 字符防误匹配）；返回**全部**命中账户供三态判定。
     */
    fun matchAccountsByName(accounts: List<AccountEntity>, hint: String?): List<AccountEntity> {
        if (hint.isNullOrBlank()) return emptyList()
        val trimmed = hint.trim()
        val exact = accounts.filter { it.name.equals(trimmed, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return accounts.filter { account ->
            account.name.length >= 2 && (account.name.contains(trimmed, ignoreCase = true) ||
                trimmed.contains(account.name, ignoreCase = true))
        }
    }

    /**
     * 信号内账户提示解析（「先提取，再兜底」）。
     *
     * 优先级：**卡尾号唯一匹配** > 渠道/余额宝 > 银行名 > 包名映射 > 现金。
     * 卡尾号来自短信卡号/「尾号2510」，用户建卡时填了 cardTail 才能命中；
     * 多候选不猜：尾号命中多户时用银行名再消歧，仍不唯一则继续走后续层级。
     */
    fun resolveByHints(
        sourcePackage: String?,
        hints: List<String?>,
        accounts: List<AccountEntity>
    ): AccountResolution {
        val active = accounts.filter { it.isActive }
        val cleaned = hints.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (cleaned.isEmpty()) return inferSignalThenFallback(sourcePackage, null, accounts)

        // 1. 卡尾号精准匹配（短信账户来源优先级最高）
        // 尾号不是跨行唯一键：有银行名提示时，先按银行名圈定账户再比尾号，
        // 否则「工商银行尾号2510」会命中用户唯一的「招商银行2510」账户。
        val bankName = cleaned.firstNotNullOfOrNull { hint ->
            BANK_NAME_PATTERN.find(hint)?.groupValues?.get(1)
        }
        val bankAccounts = active.filter { it.subtype in BANK_CARD_SUBTYPES }
        val tailScope = if (bankName != null) {
            matchAccountsByName(bankAccounts, bankName)
        } else {
            bankAccounts
        }
        for (tail in cleaned.mapNotNull(::extractCardTail).distinct()) {
            val matched = tailScope.filter { it.cardTail?.trim() == tail }
            if (matched.size == 1) {
                return AccountResolution.Resolved(matched.first().id, rankOf(matched.first()))
            }
            if (matched.size > 1) {
                val narrowed = matchAccountsByName(matched, bankName)
                if (narrowed.size == 1) {
                    return AccountResolution.Resolved(narrowed.first().id, rankOf(narrowed.first()))
                }
                if (narrowed.size > 1) {
                    return AccountResolution.NeedsReview
                }
            }
        }

        // 2. 渠道词 → 钱包账户
        for (hint in cleaned) {
            if (hint.contains("微信")) {
                active.firstOrNull { it.id == WECHAT_PAY_ACCOUNT_ID }?.let {
                    return AccountResolution.Resolved(it.id, Rank.WALLET)
                }
            }
            if (hint.contains("支付宝") || hint.contains("余额宝")) {
                active.firstOrNull { it.id == ALIPAY_ACCOUNT_ID }?.let {
                    return AccountResolution.Resolved(it.id, Rank.WALLET)
                }
            }
        }

        // 3. 银行名匹配（只匹配银行卡账户，避免把贷款/投资等同名账户当付款账户）
        // 必须有具体银行名（「XX银行」）才参与：JD/美团退款常给泛化的
        // 「银行卡/原路退回」提示，拿它做账户名包含匹配会命中错卡。
        for (hint in cleaned) {
            val name = BANK_NAME_PATTERN.find(hint)?.groupValues?.get(1) ?: continue
            val matched = matchAccountsByName(bankAccounts, name)
            if (matched.size == 1) {
                return AccountResolution.Resolved(matched.first().id, rankOf(matched.first()))
            }
            if (matched.size > 1) {
                return AccountResolution.NeedsReview
            }
        }
        return inferSignalThenFallback(sourcePackage, null, accounts)
    }

    /** 账户自身的证据等级（用于"只升不降"的当前值比较）。 */
    fun rankOf(account: AccountEntity?): Int = when {
        account == null -> Rank.NONE
        account.id == CASH_FALLBACK_ACCOUNT_ID || account.subtype == "cash" -> Rank.CASH
        account.id == WECHAT_PAY_ACCOUNT_ID ||
            account.id == ALIPAY_ACCOUNT_ID ||
            account.subtype == "e_wallet" -> Rank.WALLET
        account.subtype in BANK_CARD_SUBTYPES -> Rank.BANK
        // 贷款/其他账户不是付款资金来源，不作为 BANK 级证据
        else -> Rank.WALLET
    }

    val BANK_CARD_SUBTYPES = setOf("bank_debit", "credit_card")

    @Volatile
    private var bankAliasCache: List<Pair<String, String>>? = null

    fun bankCode(context: Context, name: String, exact: Boolean = false): String? {
        val aliases = bankAliasCache ?: runCatching {
            val json = context.assets.open("banks.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val bank = array.getJSONObject(i)
                    val code = bank.getString("code")
                    add(bank.getString("name") to code)
                    bank.optString("shortName").takeIf { it.isNotBlank() }?.let { add(it to code) }
                    val aliases = bank.optJSONArray("aliases") ?: continue
                    for (j in 0 until aliases.length()) add(aliases.getString(j) to code)
                }
            }.sortedByDescending { it.first.length }.also { bankAliasCache = it }
        }.getOrDefault(emptyList())
        return aliases.firstOrNull { (alias, _) ->
            if (exact) name.trim().equals(alias, ignoreCase = true) else name.contains(alias)
        }?.second
    }

    private val BANK_NAME_PATTERN = Regex("([\\u4e00-\\u9fa5]{2,10}银行)")

    /** 显式「尾号1234」优先；否则忽略空格/连字符后取整串数字的后 4 位。 */
    private val EXPLICIT_TAIL_PATTERN = Regex("尾号\\s*(\\d{4})")

    private fun extractCardTail(hint: String): String? {
        EXPLICIT_TAIL_PATTERN.find(hint)?.groupValues?.get(1)?.let { return it }
        val digits = hint.filter(Char::isDigit)
        return if (digits.length >= 4) digits.takeLast(4) else null
    }
}
