package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaymentEvidenceGatePolicy 锁存语义(批 4 S3):
 * - 从未观察过敏感页时不拦截;
 * - 观察后窗口内拦截、窗口过期放行(阶段七 P0 的 60 秒语义);
 * - 每次敏感页观察都刷新窗口起点,覆盖密码页与空树帧交替场景。
 */
class PaymentEvidenceGatePolicyTest {

    @Test
    fun noObservationMeansLatchInactive() {
        val policy = PaymentEvidenceGatePolicy(nowMillis = { 1_000L })
        assertFalse(policy.isSensitiveLatchActive(1_000L))
        assertNull(policy.sensitiveLatchElapsedMillis(1_000L))
    }

    @Test
    fun latchBlocksWithinWindowAndReleasesAfterExpiry() {
        var now = 1_000_000L
        val policy = PaymentEvidenceGatePolicy(nowMillis = { now })
        policy.onSensitivePageObserved()
        assertTrue(policy.isSensitiveLatchActive(now + 59_999L))
        assertFalse(policy.isSensitiveLatchActive(now + 60_000L))
        assertEquals(59_999L, policy.sensitiveLatchElapsedMillis(now + 59_999L))
    }

    @Test
    fun eachObservationRefreshesWindowStart() {
        var now = 1_000_000L
        val policy = PaymentEvidenceGatePolicy(nowMillis = { now })
        policy.onSensitivePageObserved()
        now += 50_000L
        // 密码页与空树帧交替:窗口将过期前再次观察到敏感页,窗口重新计时
        policy.onSensitivePageObserved()
        assertTrue(policy.isSensitiveLatchActive(now + 59_000L))
        assertFalse(policy.isSensitiveLatchActive(now + 60_000L))
    }

    @Test
    fun customLatchWindowIsHonored() {
        var now = 0L
        val policy = PaymentEvidenceGatePolicy(sensitiveLatchMillis = 5_000L, nowMillis = { now })
        policy.onSensitivePageObserved()
        assertTrue(policy.isSensitiveLatchActive(now + 4_999L))
        assertFalse(policy.isSensitiveLatchActive(now + 5_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveLatchWindowRejected() {
        PaymentEvidenceGatePolicy(sensitiveLatchMillis = 0L)
    }
}
