package com.jotbill.app.bookkeeping

import com.jotbill.app.data.entity.AccountEntity

/**
 * 账户推断结果（三态）：唯一命中 / 多候选需人工核对 / 无命中。
 *
 * 用于"账户解析读法一"链路（规则 → 信号推断 → 包名映射 → 现金兜底）中的
 * 信号推断层：多候选时**不得猜测**，由调用方转待确认。
 */
internal sealed interface AccountResolution {
    /** 唯一命中，可安全使用该账户 */
    data class Resolved(val accountId: String) : AccountResolution

    /** 多候选命中（如同银行多账户），须转人工核对，不得猜测 */
    data object NeedsReview : AccountResolution

    /** 无任何候选命中，落到推断链的下一级 */
    data object Unresolved : AccountResolution
}

/**
 * 账户推断共享函数（信号层 + 包名映射 + 现金兜底）。
 *
 * 唯一事实源：[AutoBookkeeper.resolveAccountId] 与
 * [com.jotbill.app.recognition.CheckoutPaymentMatcher.defaultAccountIdForPayment]
 * 共用本对象，不写两份逻辑。推断顺序（拍板读法一）：
 * 1. 短信银行名 → 本地账户名匹配（**唯一命中才用**，多命中 [AccountResolution.NeedsReview]）；
 * 2. 包名映射（微信/支付宝 → 对应平台账户）；
 * 3. 现金兜底（链尾）。
 * 规则（rule_account）优先级更高，由调用方先行判定后才进入本函数。
 */
internal object AccountInference {

    /** 现金兜底账户 ID（链尾兜底，仅在规则/信号/包名映射全部无命中时使用）。 */
    const val CASH_FALLBACK_ACCOUNT_ID: String = "default-account-cash"

    /** 微信支付默认账户 ID（包名映射）。 */
    const val WECHAT_PAY_ACCOUNT_ID: String = "default-account-wechat"

    /** 支付宝默认账户 ID（包名映射）。 */
    const val ALIPAY_ACCOUNT_ID: String = "default-account-alipay"

    /** 微信支付包名（包名映射键）。 */
    private const val WECHAT_PACKAGE: String = "com.tencent.mm"

    /** 支付宝包名（包名映射键）。 */
    private const val ALIPAY_PACKAGE: String = "com.eg.android.AlipayGphone"

    /**
     * 从信号推断账户（读法一的信号层）。
     *
     * 当前唯一可用信号 = 短信的银行名（`bank` 字段，如"招商银行"）→ 本地账户名匹配；
     * 普通消费通知/结果页**不暴露"支付方式"**（实测无"零钱/银行卡"等字样），
     * 不参与账户推断 —— 刻意不为取不到的信息造字段（payMethod 已拍板不做）。
     * 只在**启用账户**中匹配（与 TransferClassifier 同语义，停用账户不命中）。
     *
     * @param bankHint 短信解析出的银行名提示（可空）
     * @param accounts 本地启用账户列表（匹配范围）
     */
    fun inferFromSignal(bankHint: String?, accounts: List<AccountEntity>): AccountResolution {
        val matched = matchAccountsByName(accounts.filter { it.isActive }, bankHint)
        return when {
            matched.size == 1 -> AccountResolution.Resolved(matched.first().id)
            matched.size > 1 -> AccountResolution.NeedsReview
            else -> AccountResolution.Unresolved
        }
    }

    /**
     * 信号 → 包名映射 → 现金兜底 的完整推断（规则之外的层级）。
     *
     * @param sourcePackage 信号来源包名（如 com.android.mms / 支付包名）
     * @param bankHint 短信银行名提示（其它信号恒 null）
     * @param accounts 本地启用账户列表
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
            if (account != null) return AccountResolution.Resolved(account.id)
        }
        // 3. 现金兜底（链尾）
        val cash = accounts.firstOrNull { it.id == CASH_FALLBACK_ACCOUNT_ID && it.isActive }
        return if (cash != null) AccountResolution.Resolved(cash.id) else AccountResolution.Unresolved
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
}
