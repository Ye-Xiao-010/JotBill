package com.jotbill.app.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingChangeHub 事件分发契约:
 * - 无消费者时不抛异常(落库不依赖提醒);
 * - 消费者按注册顺序收到完整负载;
 * - 单个消费者异常不影响其余消费者(系统通知与 JS 推送互相独立)。
 */
class PendingChangeHubTest {

    private fun info(isNew: Boolean = true, amountMinor: Long = 2745L) = PendingChangeInfo(
        pendingId = "pending-orphan:test-key",
        type = "expense",
        amountMinor = amountMinor,
        sourceType = "notification",
        conflictReason = "NO_ACTIVE_SESSION",
        occurredAt = 42L,
        isNew = isNew
    )

    @Test
    fun emitWithoutListenerDoesNotThrow() {
        PendingChangeHub.emit(info())
    }

    @Test
    fun listenersReceiveFullPayloadInOrder() {
        val received = mutableListOf<PendingChangeInfo>()
        val listener: (PendingChangeInfo) -> Unit = { received.add(it) }
        PendingChangeHub.addListener(listener)
        try {
            PendingChangeHub.emit(info())
            PendingChangeHub.emit(info(isNew = false, amountMinor = 0L))
        } finally {
            PendingChangeHub.removeListener(listener)
        }
        assertEquals(2, received.size)
        assertEquals("expense", received[0].type)
        assertEquals(2745L, received[0].amountMinor)
        assertEquals("notification", received[0].sourceType)
        assertEquals("NO_ACTIVE_SESSION", received[0].conflictReason)
        assertTrue(received[0].isNew)
        assertTrue(!received[1].isNew)
        assertEquals(0L, received[1].amountMinor)
    }

    @Test
    fun throwingListenerDoesNotBlockOtherListeners() {
        val received = mutableListOf<String>()
        val throwing: (PendingChangeInfo) -> Unit = { throw IllegalStateException("boom") }
        val healthy: (PendingChangeInfo) -> Unit = { received.add(it.pendingId) }
        PendingChangeHub.addListener(throwing)
        PendingChangeHub.addListener(healthy)
        try {
            PendingChangeHub.emit(info())
        } finally {
            PendingChangeHub.removeListener(throwing)
            PendingChangeHub.removeListener(healthy)
        }
        assertEquals(listOf("pending-orphan:test-key"), received)
    }

    @Test
    fun removedListenerStopsReceiving() {
        val received = mutableListOf<PendingChangeInfo>()
        val listener: (PendingChangeInfo) -> Unit = { received.add(it) }
        PendingChangeHub.addListener(listener)
        PendingChangeHub.removeListener(listener)
        PendingChangeHub.emit(info())
        assertTrue(received.isEmpty())
    }
}
