package com.jotbill.app.pending

import com.jotbill.app.recognition.USER_ASSERTED_AMOUNT_MISSING_REASON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingPromptPolicy 门禁单测：逐条钉死每个拒绝分支与放行路径,
 * 弹窗触发策略的行为变化必须先改这里再改实现。
 */
class PendingPromptPolicyTest {

    private companion object {
        const val NOW = 1_700_000_000_000L

        /**
         * AI 失败占位的冲突原因。
         *
         * ⚠️ 不能引用产生方常量：`CheckoutPaymentMatcher` 里
         * `AI_RECOGNITION_FAILED_REASON` 是 **file-private**（`:98`）⇒ 测试不可见，
         * 只能按字面量复制。该字面量在 `TransactionCandidateDao:114`（SQL）也有
         * 一份；改动时须三处同查。
         */
        const val AI_FAILURE_REASON = "AI_RECOGNITION_FAILED"
    }

    private fun info(
        amountMinor: Long = 3580L,
        type: String = "expense",
        isNew: Boolean = true,
        removed: Boolean = false,
        sourceType: String = "notification",
        conflictReason: String? = null
    ) = PendingChangeInfo(
        pendingId = "pending-1",
        type = type,
        amountMinor = amountMinor,
        sourceType = sourceType,
        conflictReason = conflictReason,
        occurredAt = NOW,
        isNew = isNew,
        removed = removed
    )

    private fun evaluate(
        info: PendingChangeInfo = info(),
        merchant: String? = "测试商户",
        enabled: Boolean = true
    ) = PendingPromptPolicy.evaluate(info, merchant, enabled)

    @Test
    fun promptsExpenseWithDedupKey() {
        val decision = evaluate()
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|3580"),
            decision
        )
    }

    /**
     * 【有意行为变化 2026-09-10】用户断言通道(ai_vision + USER_ASSERTED)不弹窗。
     *
     * 设计原则:AI 识别只产信息,支付结果页(无障碍读取)/支付通知才是入账与弹窗的
     * 合法触发器。断言条目静默进待确认中心。真机取证:商品页误触按钮会以商品
     * 轮播价格(¥6.69/¥7.77)直出待确认+弹窗,随手确认即凭空记账。
     */
    @Test
    fun skipsUserAssertedPendingWithoutPaymentEvidence() {
        // ai_vision + USER_ASSERTED → 跳过(即使金额>0、类型为支出、未命中去重)
        assertEquals(
            PendingPromptPolicy.SkipReason.USER_ASSERTED,
            (evaluate(
                info(
                    sourceType = PendingSourceTypes.AI_VISION,
                    conflictReason = PENDING_CONFLICT_REASON_USER_ASSERTED
                )
            ) as PendingPromptPolicy.Decision.Skip).reason
        )
        // 对照 1:同为 ai_vision 来源但非断言标记 → 不受影响,走正常门禁
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|3580"),
            evaluate(info(sourceType = PendingSourceTypes.AI_VISION, conflictReason = null))
        )
        // 对照 2:其他来源携带断言标记字段值 → 不受影响(协议按 sourceType 分流)
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|3580"),
            evaluate(
                info(
                    sourceType = PendingSourceTypes.NOTIFICATION,
                    conflictReason = PENDING_CONFLICT_REASON_USER_ASSERTED
                )
            )
        )
    }

    @Test
    fun skipsRemovedUpdatedDisabledAndNonExpense() {
        assertEquals(
            PendingPromptPolicy.SkipReason.REMOVED,
            (evaluate(info(removed = true)) as PendingPromptPolicy.Decision.Skip).reason
        )
        assertEquals(
            PendingPromptPolicy.SkipReason.UPDATED_NOT_NEW,
            (evaluate(info(isNew = false)) as PendingPromptPolicy.Decision.Skip).reason
        )
        assertEquals(
            PendingPromptPolicy.SkipReason.DISABLED,
            (evaluate(enabled = false) as PendingPromptPolicy.Decision.Skip).reason
        )
        assertEquals(
            PendingPromptPolicy.SkipReason.NOT_EXPENSE,
            (evaluate(info(type = "transfer")) as PendingPromptPolicy.Decision.Skip).reason
        )
    }

    /**
     * 【有意行为变化 2026-09-16 · 批 V-1b/D-21】0 金额占位（AI 失败/金额缺失）
     * **允许弹窗** —— 原先被 ZERO_AMOUNT 跳过，只能进 App 内编辑。
     *
     * 真实来源（实测）：`CheckoutPaymentMatcher` AI 失败且无支付证据 ⇒
     * amountMinor=0L + conflictReason=AI_RECOGNITION_FAILED + sourceType=ai_vision。
     * "确认前强制补金额"不靠本策略保证，而由就绪判据 PendingConfirmReadiness
     * 与服务端 PendingConfirmService 兜底（两端同一口径）。
     */
    @Test
    fun promptsAiFailureWithMissingAmount() {
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|0"),
            evaluate(
                info(
                    amountMinor = 0L,
                    sourceType = PendingSourceTypes.AI_VISION,
                    conflictReason = AI_FAILURE_REASON
                )
            )
        )
        // 断言通道之外的其他来源同样放行（策略不看 sourceType 的 0 金额分支）
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|0"),
            evaluate(info(amountMinor = 0L, sourceType = PendingSourceTypes.NOTIFICATION))
        )
    }

    /**
     * 【Z2 回归 · 批 V-1b/D-21 前置 ①】断言通道 + 金额缺失 ⇒ **仍不弹窗**。
     *
     * 该变体（USER_ASSERTED_AMOUNT_MISSING）此前是被 ZERO_AMOUNT 闸"偶然"
     * 拦下的：移除 ZERO_AMOUNT 后必须靠断言判据本身拦住。故本用例是 ① 的
     * **判别性回归** —— 把判据退回精确相等（`== USER_ASSERTED`），本用例必红。
     */
    @Test
    fun skipsUserAssertedPendingWithMissingAmount() {
        val decision = evaluate(
            info(
                amountMinor = 0L,
                sourceType = PendingSourceTypes.AI_VISION,
                conflictReason = USER_ASSERTED_AMOUNT_MISSING_REASON
            )
        )
        assertEquals(
            PendingPromptPolicy.SkipReason.USER_ASSERTED,
            (decision as PendingPromptPolicy.Decision.Skip).reason
        )
        // 对照：同为零金额 + ai_vision，但非断言标记（AI 失败）⇒ 放行（见上条用例）
        assertEquals(
            PendingPromptPolicy.Decision.Prompt("测试商户|0"),
            evaluate(
                info(
                    amountMinor = 0L,
                    sourceType = PendingSourceTypes.AI_VISION,
                    conflictReason = AI_FAILURE_REASON
                )
            )
        )
    }

    /**
     * 【用户裁定 2026-10-12】去重与频控两条规则已废弃 —— 本用例钉死新行为。
     *
     * 原先同商户同金额 5 分钟内只弹一次（`DEDUP_WINDOW`）、1 小时最多 10 次
     * （`HOURLY_LIMIT`），两条都直接违反本版本目标"只要出现支付成功页就弹窗"。
     * 连续两笔同额是**两笔真实交易**，本就该弹两次。
     *
     * 判别性：把任一条判定加回 [PendingPromptPolicy.evaluate]，本用例必红。
     */
    @Test
    fun promptsRepeatedSameMerchantAndAmountWithinAnyWindow() {
        // 同商户同金额连续两笔 → 两次都放行（旧实现第二次是 DEDUP_WINDOW）
        repeat(2) {
            assertTrue(
                "第 ${it + 1} 笔同额应放行弹窗",
                evaluate() is PendingPromptPolicy.Decision.Prompt
            )
        }
        // 同一商户同金额短时间高频 12 笔 → 全部放行（旧实现第 11 笔起是 HOURLY_LIMIT）
        repeat(12) {
            assertTrue(
                "高频第 ${it + 1} 笔应放行弹窗",
                evaluate() is PendingPromptPolicy.Decision.Prompt
            )
        }
        // 不同商户/不同金额同样放行（确认没有残留的键级判定）
        assertTrue(evaluate(merchant = "另一家") is PendingPromptPolicy.Decision.Prompt)
        assertTrue(evaluate(info(amountMinor = 990L)) is PendingPromptPolicy.Decision.Prompt)
    }

    /**
     * `evaluate` 已是纯函数 —— 相同入参必得相同结果，且不含任何时间依赖。
     *
     * 2026-10-12 之前它要读 `recentDedupKeys` / `recentPromptTimes` 两个「仅主线程」
     * 集合，决策协程并发遍历它们会抛 ConcurrentModificationException 而**杀掉进程**。
     * 废弃两条规则后这两个集合已删除，本用例锁住"不再有隐藏状态"这一前提。
     */
    @Test
    fun evaluateIsPureAndRepeatable() {
        assertEquals(evaluate(), evaluate())
        assertEquals(evaluate(), evaluate())
    }
}
