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
        enabled: Boolean = true,
        now: Long = NOW,
        dedup: Map<String, Long> = emptyMap(),
        recent: List<Long> = emptyList()
    ) = PendingPromptPolicy.evaluate(info, merchant, enabled, now, dedup, recent)

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

    @Test
    fun dedupWindowSuppressesSameMerchantAndAmountWithinFiveMinutes() {
        val first = evaluate()
        assertTrue(first is PendingPromptPolicy.Decision.Prompt)
        val key = (first as PendingPromptPolicy.Decision.Prompt).dedupKey
        // 同商户同金额 5 分钟内 → 去重
        val suppressed = evaluate(
            now = NOW + 60_000L,
            dedup = mapOf(key to NOW)
        )
        assertEquals(
            PendingPromptPolicy.SkipReason.DEDUP_WINDOW,
            (suppressed as PendingPromptPolicy.Decision.Skip).reason
        )
        // 超过 5 分钟 → 再次放行
        val later = evaluate(
            now = NOW + PendingPromptPolicy.DEDUP_WINDOW_MILLIS + 1,
            dedup = mapOf(key to NOW)
        )
        assertTrue(later is PendingPromptPolicy.Decision.Prompt)
    }

    @Test
    fun differentMerchantOrAmountNotDeduped() {
        val key = "测试商户|3580"
        assertEquals(
            PendingPromptPolicy.SkipReason.DEDUP_WINDOW,
            (evaluate(dedup = mapOf(key to NOW)) as PendingPromptPolicy.Decision.Skip).reason
        )
        assertTrue(
            evaluate(info(amountMinor = 990L), dedup = mapOf(key to NOW))
                is PendingPromptPolicy.Decision.Prompt
        )
        assertTrue(
            evaluate(merchant = "另一家", dedup = mapOf(key to NOW))
                is PendingPromptPolicy.Decision.Prompt
        )
    }

    @Test
    fun hourlyLimitBlocksEleventhPromptWithinWindow() {
        val tenRecent = (1..10).map { NOW - it * 60_000L }
        assertEquals(
            PendingPromptPolicy.SkipReason.HOURLY_LIMIT,
            (evaluate(recent = tenRecent) as PendingPromptPolicy.Decision.Skip).reason
        )
        // 窗口外（2 小时前）的弹窗不计数
        val oldOnly = (1..10).map { NOW - 2 * 60 * 60 * 1000L }
        assertTrue(evaluate(recent = oldOnly) is PendingPromptPolicy.Decision.Prompt)
    }
}
