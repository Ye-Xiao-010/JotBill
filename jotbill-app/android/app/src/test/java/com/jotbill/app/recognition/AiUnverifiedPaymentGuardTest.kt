package com.jotbill.app.recognition

import com.jotbill.app.data.entity.PaymentEvidenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图 AI 未证实支付结果的守卫测试。
 *
 * 核心不变量：截图 AI 推断出的支付结果即使通过全部一致性校验，也不得自动入账；
 * 但它仍必须被视为"支付已发生"，以便关联候选并进入待确认。
 */
class AiUnverifiedPaymentGuardTest {

    /** 构造指定支付状态的证据实体，仅用于状态判定断言。 */
    private fun evidenceWithStatus(status: String?): PaymentEvidenceEntity =
        PaymentEvidenceEntity(
            id = "payment-test",
            sessionId = "session-test",
            sourceType = "accessibility",
            amountMinor = 270L,
            merchant = null,
            platform = "com.tencent.mm",
            orderId = null,
            paymentMethod = null,
            paymentStatus = status,
            occurredAt = 1_000L,
            rawTextHash = null,
            sourceEventKey = "event-test",
            capturedAt = 1_000L
        )

    @Test
    fun 已证实的支付结果在无冲突时允许自动入账() {
        assertNull(resolveConflictWithAiUnverifiedGuard(null, "SUCCESS"))
    }

    @Test
    fun 截图AI未证实的支付结果即使无冲突也必须转待确认() {
        assertEquals(
            AI_UNVERIFIED_PAYMENT_REASON,
            resolveConflictWithAiUnverifiedGuard(null, PAYMENT_STATUS_AI_UNVERIFIED)
        )
    }

    @Test
    fun 已有冲突原因优先于未证实守卫() {
        assertEquals(
            "EVIDENCE_AMOUNT_CONFLICT",
            resolveConflictWithAiUnverifiedGuard(
                "EVIDENCE_AMOUNT_CONFLICT",
                PAYMENT_STATUS_AI_UNVERIFIED
            )
        )
    }

    @Test
    fun 未证实证据仍被视为支付已发生以便关联候选() {
        assertTrue(evidenceWithStatus(PAYMENT_STATUS_AI_UNVERIFIED).indicatesPaidOutcome())
        assertTrue(evidenceWithStatus("SUCCESS").indicatesPaidOutcome())
    }

    @Test
    fun 失败与缺失状态不视为支付已发生() {
        assertFalse(evidenceWithStatus("FAILED").indicatesPaidOutcome())
        assertFalse(evidenceWithStatus(null).indicatesPaidOutcome())
    }
}
