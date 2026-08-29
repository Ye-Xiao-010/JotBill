package com.jotbill.app.recognition

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JourneyStateHolder 存储契约(批 4 S4):三个易失字段的独立读写与整体清理。
 * 单例状态在用例间用 clearAll 复位。
 */
class JourneyStateHolderTest {

    @After
    fun reset() {
        JourneyStateHolder.clearAll()
    }

    @Test
    fun storesAndClearsEachFieldIndependently() {
        val stable = StableCheckoutPage(
            observation = CheckoutPageObservation(
                shoppingPackage = "com.test.app",
                pageRole = CheckoutPageRole.CHECKOUT,
                pageFingerprint = "a".repeat(64),
                hasFinalAmountSignal = true,
                hasCheckoutActionSignal = true,
                isSensitivePage = false,
                observedAt = 1L
            ),
            stableAt = 1L
        )
        JourneyStateHolder.activeStableCheckout = stable
        JourneyStateHolder.handoffForegroundPackage = "com.tencent.mm"
        assertEquals(stable, JourneyStateHolder.activeStableCheckout)
        assertEquals("com.tencent.mm", JourneyStateHolder.handoffForegroundPackage)
        assertNull(JourneyStateHolder.handoffStableCheckout)

        JourneyStateHolder.activeStableCheckout = null
        assertNull(JourneyStateHolder.activeStableCheckout)
        assertEquals("com.tencent.mm", JourneyStateHolder.handoffForegroundPackage)
    }

    @Test
    fun clearAllResetsEverything() {
        JourneyStateHolder.handoffStableCheckout = null
        JourneyStateHolder.handoffForegroundPackage = "com.eg.android.AlipayGphone"
        JourneyStateHolder.clearAll()
        assertNull(JourneyStateHolder.activeStableCheckout)
        assertNull(JourneyStateHolder.handoffStableCheckout)
        assertNull(JourneyStateHolder.handoffForegroundPackage)
    }
}
