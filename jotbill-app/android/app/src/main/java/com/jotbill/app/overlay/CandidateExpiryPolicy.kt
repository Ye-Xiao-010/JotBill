package com.jotbill.app.overlay

/**
 * 挂起候选过期语义（纯 Kotlin，可 JVM 单测）。
 *
 * ## 为什么存在（2026-10-29 支付宝 19.90 事故）
 *
 * 远程 log4 时间线：证据 22:33:42.706 产出 → 门禁 `NO_OVERLAY_PERMISSION`
 * 挂起（支付宝前台 HyperOS 收走 SYSTEM_ALERT_WINDOW）→ 进程冻结 31.2 秒 →
 * 30s TTL 定时器在**解冻瞬间**才执行（22:34:35.800）→ 无条件丢弃候选。
 * 冻结期间权限很可能已经恢复，但候选先被 TTL 扔了 —— 系统提供了"收权+冻结"
 * 的逆风，我们的 TTL 语义把"可恢复的延迟"变成了"永久丢失"。
 *
 * ## 判定规则
 *
 * 定时器迟到（overDue > [FREEZE_DETECT_MILLIS]）视为**疑似冻结恢复**：
 * - 未到 [HARD_DEADLINE_MILLIS] 硬上限 ⇒ 先做最后一次门禁重评
 *   （放行补弹 / 仍挡则延长到硬上限）；
 * - 已到硬上限 ⇒ 无论如何放弃（防候选无限续期占用槽位）。
 *
 * 正常到期（overDue ≤ 1s）保持原语义：放弃 + 通知兜底，不改变用户的
 * 30 秒注意力窗口。硬上限从候选 **createdAt** 起算，与续期次数无关。
 */
internal object CandidateExpiryPolicy {

    /** 定时器迟到超过该值即判定疑似冻结恢复（与 D7 的 SUSPECT_FREEZE 同判据）。 */
    const val FREEZE_DETECT_MILLIS = 1_000L

    /** 候选硬上限（从 createdAt 起算）：超过即无条件放弃，防无限续期。 */
    const val HARD_DEADLINE_MILLIS = 120_000L

    enum class Verdict {
        /** 正常到期：放弃 + 通知兜底（保持 30s 注意力窗口语义）。 */
        EXPIRE,

        /** 疑似冻结恢复且未到硬上限：先做最后一次门禁重评。 */
        FREEZE_RECOVERED,

        /** 已达硬上限：即使冻结恢复也放弃。 */
        EXPIRE_HARD_LIMIT
    }

    /**
     * @param ageMillis 候选存活时长（now - createdAt）
     * @param ttlMillis 挂起 TTL（[com.jotbill.app.pending.PendingPromptPolicy.CANDIDATE_TTL_MILLIS]）
     */
    fun verdict(ageMillis: Long, ttlMillis: Long): Verdict {
        val overDue = ageMillis - ttlMillis
        if (overDue <= FREEZE_DETECT_MILLIS) return Verdict.EXPIRE
        if (ageMillis >= HARD_DEADLINE_MILLIS) return Verdict.EXPIRE_HARD_LIMIT
        return Verdict.FREEZE_RECOVERED
    }
}
