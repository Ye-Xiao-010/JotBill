package com.jotbill.app.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAlivePolicyTest {
    @Test
    fun requiresBothMasterAndKeepAliveEnabled() {
        assertTrue(KeepAlivePolicy.shouldRun(masterEnabled = true, keepAliveEnabled = true))
        assertFalse(KeepAlivePolicy.shouldRun(masterEnabled = false, keepAliveEnabled = true))
        assertFalse(KeepAlivePolicy.shouldRun(masterEnabled = true, keepAliveEnabled = false))
        assertFalse(KeepAlivePolicy.shouldRun(masterEnabled = false, keepAliveEnabled = false))
    }
}
