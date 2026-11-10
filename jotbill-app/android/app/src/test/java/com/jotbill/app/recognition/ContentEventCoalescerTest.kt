package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentEventCoalescerTest {
    @Test
    fun onlyFirstEventSchedulesDispatch() {
        val coalescer = ContentEventCoalescer()

        assertTrue(coalescer.onContentEvent())
        assertFalse(coalescer.onContentEvent())
        assertFalse(coalescer.onContentEvent())
    }

    @Test
    fun dispatchReleasesNextSlot() {
        val coalescer = ContentEventCoalescer()

        assertTrue(coalescer.onContentEvent())
        coalescer.onDispatched()
        assertTrue(coalescer.onContentEvent())
    }

    @Test
    fun resetReleasesNextSlot() {
        val coalescer = ContentEventCoalescer()

        assertTrue(coalescer.onContentEvent())
        coalescer.reset()
        assertTrue(coalescer.onContentEvent())
    }
}
