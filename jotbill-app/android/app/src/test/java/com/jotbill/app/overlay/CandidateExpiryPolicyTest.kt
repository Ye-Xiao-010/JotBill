package com.jotbill.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CandidateExpiryPolicy] 的边界用例。
 *
 * 事故背景（docs/qa/device/2026-10-27-remote-user1/log4）：30s TTL 定时器
 * 在进程冻结 31.2s 后迟到执行，解冻瞬间无条件丢弃候选 —— 而权限很可能已在
 * 冻结期间恢复。判定写窄（冻结也不重评）⇒ 复现事故；写宽（正常到期也续期）
 * ⇒ 改变 30s 注意力窗口语义并占用槽位。两个方向都必须钉死。
 */
class CandidateExpiryPolicyTest {

    private val ttl = 30_000L

    @Test
    fun normalExpiryStaysExpire() {
        // 按时到期：overDue=0，保持原语义（放弃 + 通知兜底）。
        assertEquals(
            CandidateExpiryPolicy.Verdict.EXPIRE,
            CandidateExpiryPolicy.verdict(ageMillis = ttl, ttlMillis = ttl)
        )
    }

    @Test
    fun freezeBoundaryIsExact() {
        // overDue=1000ms 仍算正常；1001ms 起判定疑似冻结恢复。
        assertEquals(
            CandidateExpiryPolicy.Verdict.EXPIRE,
            CandidateExpiryPolicy.verdict(ageMillis = ttl + 1_000L, ttlMillis = ttl)
        )
        assertEquals(
            CandidateExpiryPolicy.Verdict.FREEZE_RECOVERED,
            CandidateExpiryPolicy.verdict(ageMillis = ttl + 1_001L, ttlMillis = ttl)
        )
    }

    @Test
    fun freezeRecoveryTriggersRecheckBelowHardDeadline() {
        // 事故样本：冻结 31.2s → age≈61.2s（<120s）⇒ 必须走"最后重评"。
        assertEquals(
            CandidateExpiryPolicy.Verdict.FREEZE_RECOVERED,
            CandidateExpiryPolicy.verdict(ageMillis = 61_200L, ttlMillis = ttl)
        )
    }

    @Test
    fun hardDeadlineIsExact() {
        // 硬上限 120s：119_999ms 仍重评；120_000ms 无条件放弃。
        assertEquals(
            CandidateExpiryPolicy.Verdict.FREEZE_RECOVERED,
            CandidateExpiryPolicy.verdict(ageMillis = 119_999L, ttlMillis = ttl)
        )
        assertEquals(
            CandidateExpiryPolicy.Verdict.EXPIRE_HARD_LIMIT,
            CandidateExpiryPolicy.verdict(ageMillis = 120_000L, ttlMillis = ttl)
        )
    }

    @Test
    fun hardDeadlineWinsOverFreezeRecovery() {
        // 超过硬上限后即使判定为冻结恢复也放弃 —— 防候选无限续期。
        assertEquals(
            CandidateExpiryPolicy.Verdict.EXPIRE_HARD_LIMIT,
            CandidateExpiryPolicy.verdict(ageMillis = 300_000L, ttlMillis = ttl)
        )
    }
}
