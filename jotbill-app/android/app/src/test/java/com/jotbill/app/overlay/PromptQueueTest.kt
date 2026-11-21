package com.jotbill.app.overlay

import com.jotbill.app.pending.PendingChangeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PromptQueue] 单测：钉死两条方向相反的不变式 ——
 * ① 槽位忙时到达的候选必须能补播（P1-1 的病灶就是静默丢弃）；
 * ② 有界 + TTL + 幂等，保证补播不会变成无界堆积或过时打扰。
 */
class PromptQueueTest {

    private companion object {
        const val START = 1_700_000_000_000L
        const val CAPACITY = 3
        const val TTL = 30_000L

        fun info(pendingId: String, amountMinor: Long = 1990L) = PendingChangeInfo(
            pendingId = pendingId,
            type = "expense",
            amountMinor = amountMinor,
            sourceType = "accessibility",
            conflictReason = null,
            occurredAt = START,
            isNew = true
        )
    }

    /** FIFO：先入队的先补播。 */
    @Test
    fun pollsInOfferOrder() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-1")))
        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-2")))

        assertEquals(
            "先入队的 pending-1 必须先出队",
            "pending-1",
            (queue.poll() as PromptQueue.PollResult.Ready).info.pendingId
        )
        assertEquals(
            "随后是 pending-2",
            "pending-2",
            (queue.poll() as PromptQueue.PollResult.Ready).info.pendingId
        )
        assertEquals(PromptQueue.PollResult.Empty, queue.poll())
    }

    /** 幂等：同一笔的重复 emit（更新事件）不重复占位。 */
    @Test
    fun duplicatePendingIdIsRejected() {
        val queue = PromptQueue(CAPACITY, TTL) { START }
        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-1", 1990L)))
        assertEquals(
            "同 pendingId 不同金额的更新事件必须判重",
            PromptQueue.OfferResult.Duplicate,
            queue.offer(info("pending-1", 2990L))
        )
        assertEquals(1, queue.size)
    }

    /** 有界：容量满时拒绝并让调用方留痕；出队后腾出位置。 */
    @Test
    fun fullQueueRejectsAndRecoversAfterPoll() {
        val queue = PromptQueue(2, TTL) { START }
        queue.offer(info("pending-1"))
        queue.offer(info("pending-2"))
        assertEquals(PromptQueue.OfferResult.Full, queue.offer(info("pending-3")))

        queue.poll()
        assertEquals(
            "出队一笔后必须能再入队",
            PromptQueue.OfferResult.Enqueued,
            queue.offer(info("pending-3"))
        )
    }

    /** 补播延迟可观测：waitedMs 等于出队时刻与入队时刻之差。 */
    @Test
    fun readyCarriesWaitedMs() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        queue.offer(info("pending-1"))
        now += 4_000L

        val result = queue.poll()
        assertTrue(result is PromptQueue.PollResult.Ready)
        assertEquals(4_000L, (result as PromptQueue.PollResult.Ready).waitedMs)
    }

    /** TTL 边界（含端点）：排队恰好在 TTL 整点仍补播 —— 与同额窗口的闭区间语义一致。 */
    @Test
    fun pollsExactlyAtTtlBoundary() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        queue.offer(info("pending-1"))
        now += TTL

        assertTrue(queue.poll() is PromptQueue.PollResult.Ready)
    }

    /** TTL 边界另一侧：超过 TTL 的首条出队即弃，且必须能继续出下一笔。 */
    @Test
    fun expiredHeadIsDroppedAndNextIsStillServed() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        queue.offer(info("pending-1"))
        now += TTL + 1L
        // pending-2 在 pending-1 已超时之后才入队：自身是新鲜的。
        // （两笔同时入队再一起超时的场景由 pollReturnsEmptyAfterAllExpired 覆盖。）
        queue.offer(info("pending-2"))

        val expired = queue.poll()
        assertTrue(expired is PromptQueue.PollResult.Expired)
        assertEquals("pending-1", (expired as PromptQueue.PollResult.Expired).pendingId)
        assertEquals(TTL + 1L, expired.ageMs)

        assertEquals(
            "首条超时不得拖累后入队的一笔",
            "pending-2",
            (queue.poll() as PromptQueue.PollResult.Ready).info.pendingId
        )
    }

    /** 全部超时后队列归零，poll 稳定返回 Empty（调用方循环补播的退出条件）。 */
    @Test
    fun pollReturnsEmptyAfterAllExpired() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        queue.offer(info("pending-1"))
        now += TTL + 1L

        assertTrue(queue.poll() is PromptQueue.PollResult.Expired)
        assertEquals(PromptQueue.PollResult.Empty, queue.poll())
        assertEquals(PromptQueue.PollResult.Empty, queue.poll())
    }

    /** 重入队保留首次入队时刻：出队→重入队不得把 TTL 清零（复审 🟡-6）。 */
    @Test
    fun reofferAfterPollKeepsOriginalAge() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-1")))
        now += 10_000L
        assertTrue(queue.poll() is PromptQueue.PollResult.Ready)
        now += 25_000L

        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-1")))
        assertTrue(
            "距首次入队已 35s > TTL，重入队后必须判过期而不是再等 30s",
            queue.poll() is PromptQueue.PollResult.Expired
        )
    }

    /** forget（终态）后重新入队：TTL 从零起算。 */
    @Test
    fun forgetResetsAge() {
        var now = START
        val queue = PromptQueue(CAPACITY, TTL) { now }
        queue.offer(info("pending-1"))
        now += 20_000L
        queue.poll()
        queue.forget("pending-1")
        now += 20_000L

        assertEquals(PromptQueue.OfferResult.Enqueued, queue.offer(info("pending-1")))
        assertTrue(
            "终态后重新入队是新的队列生命周期",
            queue.poll() is PromptQueue.PollResult.Ready
        )
    }

    /** 清空：功能停用时不得再补播任何历史候选。 */
    @Test
    fun clearDropsEverything() {
        val queue = PromptQueue(CAPACITY, TTL) { START }
        queue.offer(info("pending-1"))
        queue.offer(info("pending-2"))

        assertEquals(listOf("pending-1", "pending-2"), queue.clear().map { it.pendingId })
        assertEquals(0, queue.size)
        assertEquals(PromptQueue.PollResult.Empty, queue.poll())
    }
}
