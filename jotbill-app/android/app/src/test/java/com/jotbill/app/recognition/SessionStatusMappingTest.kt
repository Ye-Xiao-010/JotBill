package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusMappingTest {

    @Test
    fun publicSessionStatusStaysWithinEightValues() {
        val publicStatuses = CheckoutJourneyState.entries
            .map { it.toPublicSessionStatus() }
            .toSet()

        assertEquals(SessionStatus.entries.toSet(), publicStatuses)
    }

    @Test
    fun aiFailedMapsToPublicFailed() {
        assertEquals(
            SessionStatus.FAILED,
            CheckoutJourneyState.AI_FAILED.toPublicSessionStatus()
        )
    }

    @Test
    fun paymentConfirmedNeedsConfirmationMapToWaitingEnrichment() {
        assertEquals(
            SessionStatus.WAITING_ENRICHMENT,
            CheckoutJourneyState.PAYMENT_CONFIRMED.toPublicSessionStatus()
        )
        assertEquals(
            SessionStatus.WAITING_ENRICHMENT,
            CheckoutJourneyState.NEEDS_CONFIRMATION.toPublicSessionStatus()
        )
    }
}
