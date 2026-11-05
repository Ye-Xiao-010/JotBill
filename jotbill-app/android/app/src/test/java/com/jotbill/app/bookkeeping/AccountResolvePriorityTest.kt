package com.jotbill.app.bookkeeping

import com.jotbill.app.data.entity.AccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账户解析优先级（读法一：规则 → 信号推断 → 包名映射 → 现金兜底，2026-11-05 拍板）测试。
 *
 * 规则优先（set_account）由 AutoBookkeeper.resolveAccountId 的 patch 分支保证，
 * 并由 BankSmsNegativeTest 的"规则命中强制待确认"用例锁定；本类覆盖信号层与兜底层：
 * 4. 短信银行名**唯一命中** → 映射到该账户；
 * 5. 短信银行名**多账户命中** → NeedsReview（调用方转 RULE_ACCOUNT_NEEDS_REVIEW，不猜测）；
 * 6. 零命中 → 包名映射 → 现金兜底（链尾）；全无 → Unresolved。
 */
class AccountResolvePriorityTest {

    /** 构造启用账户。 */
    private fun account(id: String, name: String, active: Boolean = true) = AccountEntity(
        id = id,
        name = name,
        subtype = "savings",
        kind = "asset",
        initialBalanceMinor = 0L,
        currentBalanceMinor = 0L,
        currency = "CNY",
        icon = null,
        color = null,
        sortOrder = 0,
        isActive = active,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** 用例 4：银行名唯一命中 → Resolved 到对应账户。 */
    @Test
    fun bankHintUniqueMatchResolvesAccount() {
        val accounts = listOf(
            account("cmb", "招商银行储蓄卡"),
            account("cash", "现金")
        )
        val resolution = AccountInference.inferFromSignal("招商银行", accounts)
        assertTrue(resolution is AccountResolution.Resolved)
        assertEquals("cmb", (resolution as AccountResolution.Resolved).accountId)
    }

    /** 用例 5：同银行多账户命中 → NeedsReview（不得猜测）。 */
    @Test
    fun bankHintMultiMatchNeedsReview() {
        val accounts = listOf(
            account("cmb1", "招商银行储蓄卡"),
            account("cmb2", "招商银行信用卡")
        )
        assertEquals(AccountResolution.NeedsReview, AccountInference.inferFromSignal("招商银行", accounts))
    }

    /** 零命中落到下一级：包名映射（微信 → 微信零钱）。 */
    @Test
    fun noSignalFallsBackToPackageMapping() {
        val accounts = listOf(
            account("default-account-wechat", "微信零钱"),
            account("default-account-cash", "现金")
        )
        val resolution = AccountInference.inferSignalThenFallback("com.tencent.mm", null, accounts)
        assertEquals(
            AccountResolution.Resolved("default-account-wechat"),
            resolution
        )
    }

    /** 用例 6（链尾）：无规则、无信号、包名不可映射 → 现金兜底。 */
    @Test
    fun nothingMatchesFallsBackToCash() {
        val accounts = listOf(account("default-account-cash", "现金"))
        val resolution = AccountInference.inferSignalThenFallback("com.unionpay", null, accounts)
        assertEquals(AccountResolution.Resolved("default-account-cash"), resolution)
    }

    /** 信号层优先于包名映射：短信命中时不得落包名/现金。 */
    @Test
    fun signalLayerBeatsPackageMapping() {
        val accounts = listOf(
            account("cmb", "招商银行储蓄卡"),
            account("default-account-cash", "现金")
        )
        val resolution = AccountInference.inferSignalThenFallback("com.android.mms", "招商银行", accounts)
        assertEquals(AccountResolution.Resolved("cmb"), resolution)
    }

    /** NeedsReview 短路：多命中不得继续落到包名/现金（不猜测）。 */
    @Test
    fun multiMatchShortCircuitsBeforeFallback() {
        val accounts = listOf(
            account("cmb1", "招商银行储蓄卡"),
            account("cmb2", "招商银行信用卡"),
            account("default-account-cash", "现金")
        )
        assertEquals(
            AccountResolution.NeedsReview,
            AccountInference.inferSignalThenFallback("com.android.mms", "招商银行", accounts)
        )
    }

    /** 负向：账户全部停用时不得命中（Unresolved，由调用方决定去向）。 */
    @Test
    fun inactiveAccountsNeverMatch() {
        val accounts = listOf(account("cmb", "招商银行储蓄卡", active = false))
        assertEquals(AccountResolution.Unresolved, AccountInference.inferFromSignal("招商银行", accounts))
    }
}
