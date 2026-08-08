package com.jotbill.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BridgeDtoMapperTest {
    @Test
    fun timestampFormattingUsesIso8601() {
        val timestamp = Instant.parse("2026-08-05T12:34:56Z").toEpochMilli()

        assertEquals("2026-08-05T12:34:56Z", BridgeDtoMapper.formatTimestamp(timestamp))
    }

    @Test
    fun timestampParserAcceptsDateOnly() {
        val timestamp = BridgeDtoMapper.parseTimestampValue("2026-08-05", "occurredAt")

        assertTrue(timestamp > 0L)
    }

    @Test
    fun unsupportedSourceIsRejectedBeforeRoomWrite() {
        try {
            BridgeDtoMapper.validateSourceType("remote_api")
        } catch (error: BridgeException) {
            assertEquals("INVALID_ARGUMENT", error.code)
            return
        }
        throw AssertionError("Unsupported source type was accepted")
    }

    @Test
    fun restrictedForeignKeyIsExposedAsDataConflict() {
        assertEquals("DATA_CONFLICT", dataErrorCode("FOREIGN KEY constraint failed"))
    }
}
