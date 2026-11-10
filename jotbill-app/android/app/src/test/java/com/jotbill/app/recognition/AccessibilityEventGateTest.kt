package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityEventGateTest {
    private val relevantPackages = setOf("com.taobao.taobao", "com.tencent.mm")

    @Test
    fun knownRelevantPackageIsProcessed() {
        val gate = AccessibilityEventGate(relevantPackages)

        assertEquals(
            PopupEventDecision.Process,
            gate.decide("com.taobao.taobao", lastRelevantPackage = null)
        )
    }

    @Test
    fun blankPackageIsProcessedForRootFallback() {
        val gate = AccessibilityEventGate(relevantPackages)

        assertEquals(
            PopupEventDecision.Process,
            gate.decide("", lastRelevantPackage = null)
        )
    }

    @Test
    fun unrelatedPackageIsDroppedWithoutRelevantWindow() {
        val gate = AccessibilityEventGate(relevantPackages)

        assertEquals(
            PopupEventDecision.Drop,
            gate.decide("com.example.other", lastRelevantPackage = null)
        )
    }

    @Test
    fun unrelatedPackageIsProcessedWhenLeavingRelevantWindow() {
        val gate = AccessibilityEventGate(relevantPackages)

        assertEquals(
            PopupEventDecision.Process,
            gate.decide("com.example.other", lastRelevantPackage = "com.taobao.taobao")
        )
    }

    @Test
    fun screenOffDropsEvenRelevantPackage() {
        val gate = AccessibilityEventGate(relevantPackages)
        gate.screenInteractive = false

        assertEquals(
            PopupEventDecision.Drop,
            gate.decide("com.taobao.taobao", lastRelevantPackage = "com.taobao.taobao")
        )
    }
}
