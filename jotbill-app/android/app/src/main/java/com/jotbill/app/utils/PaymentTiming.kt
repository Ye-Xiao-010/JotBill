package com.jotbill.app.utils

/**
 * 支付结果页"同一笔"判定的时间口径（2026-10-24 统一）。
 *
 * ## 为什么要有这个对象
 *
 * "15 秒"这一个数字原先散落在四处，各自写死：
 *
 * | 位置 | 原写法 |
 * |---|---|
 * | `PaymentResultParser` 事件键桶宽 | 内联 `occurredAt / 30_000` |
 * | `CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS` | `15_000L` |
 * | `CheckoutAccessibilityService.PAYMENT_RESULT_EVENT_MEMORY_MILLIS` | `15_000L` |
 * | `RecentResultObservation` 哈希匹配窗 | **缺失**（只有 10 分钟保留期） |
 *
 * 四处是同一语义的四个副本，改一处漏一处就会让"15 秒"名存实亡
 * （本项目已多次踩"改一处漏一处"）。统一到这里后，只有一处可改。
 *
 * ## 15 秒的语义（用户 2026-10-24 裁定）
 *
 * **同包同额的两笔支付，若发生在 15 秒内，接受合并；超过 15 秒一律视为新交易。**
 *
 * 依据：物理上不可能在 15 秒内完成"离开支付 App → 回商户应用 → 重新下单 →
 * 拉起支付 → 输密码"全流程。因此 15 秒内的同额证据只可能是同一笔的重复观测。
 *
 * ## 边界
 *
 * 判据统一为**闭区间**（`|Δt| <= 15_000`）：15.000 秒算合并，15.001 秒算新交易。
 * SQL 侧 `BETWEEN` 也是闭区间，两侧口径一致。
 *
 * ## 不适用于
 *
 * 本对象只管"同一笔判定"。弹窗生命周期（候选 TTL / 自动关闭 / 观察帧新鲜度，
 * 均为 30 秒）、会话匹配（5 分钟）、证据合并（2 分钟）语义独立，**不要**引用这里。
 */
internal object PaymentTiming {

    /**
     * 同额判定的时间边界：15 秒。
     *
     * 事件键桶宽、D-25 孤儿去重窗、事件记忆窗、哈希匹配窗**共用此值**。
     */
    const val SAME_TXN_WINDOW_MILLIS = 15_000L

    /**
     * 该笔被确认/忽略后，抑制同额重复观测的时长。
     *
     * 与 [SAME_TXN_WINDOW_MILLIS] 同值是有意的：两套阈值不一致时，
     * 先失效的那道形同虚设（`RecentPaymentCooldown` 与去重窗的分工见各自 KDoc）。
     */
    const val CONSUMED_COOLDOWN_MILLIS = 15_000L
}
