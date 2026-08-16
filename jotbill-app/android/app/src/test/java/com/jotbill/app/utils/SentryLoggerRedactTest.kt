package com.jotbill.app.utils

import org.junit.Assert.assertFalse
import org.junit.Test

class SentryLoggerRedactTest {
    @Test
    fun redactsPasswordsKeysTokensAndCardLikeNumbers() {
        val raw = "password=hunter2 apiKey=sk-abc123 Authorization=Bearer token123 " +
            "卡号 6222021234567890 data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
        val redacted = SentryLogger.redact(raw)
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("sk-abc123"))
        assertFalse(redacted.contains("token123"))
        assertFalse(redacted.contains("6222021234567890"))
        assertFalse(redacted.contains("iVBORw0KGgoAAAANSUhEUg=="))
    }

    @Test
    fun keepsOrdinaryMessagesUsable() {
        val redacted = SentryLogger.redact("支付通知服务启动 2024-01-01")
        assertFalse(redacted.contains("***"))
    }
}
