package com.jotbill.app.overlay

import com.jotbill.app.pending.PendingChangeInfo

/**
 * 弹窗候选等待队列：槽位忙时的有界 FIFO 补播（2026-10-24 审查 P1-1）。
 *
 * 此前弹窗候选只有一个槽位（在屏弹窗 / 挂起候选 / 在途上屏三者占其一），
 * 槽位忙时到达的新支付事件被直接 `return` 丢弃 —— pending 行照常落库、
 * 系统通知照常发送，但弹窗永久不出现，日志里只有一条门禁判定后便无下文，
 * 事后无法归因。MIUI 悬浮窗权限收权期间（真机实测 waitedMs=1113/1976/4031，
 * 最长 57s）槽位挂起是常态而非异常，连续两笔支付极易命中 —— 这是 L1
 * 连续 20 笔验收里最可能的漏弹来源。
 *
 * 设计：
 * - **有界**：超过 [capacity] 即拒绝并让调用方留痕（可归因），不无界堆积；
 * - **FIFO 补播**：槽位释放时由调用方 [poll]，按入队顺序逐笔补播；
 * - **TTL**：入队超过 [ttlMillis] 的条目出队即弃 —— 支付时刻早已过去，
 *   系统通知兜底已覆盖该笔，补弹只是过时打扰；
 * - **幂等**：同 pendingId 只保留最早一条（重复 emit 通常是同一笔的更新事件）。
 *
 * 纯逻辑类，时钟可注入以便单测；**无锁** —— 所有调用点都在弹窗管理器的
 * 主线程串行区（与 `overlayView` / `waitingCandidate` / `inFlightCandidate`
 * 同一套主线程约定）。
 */
internal class PromptQueue(
    private val capacity: Int,
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Entry(
        val info: PendingChangeInfo,
        val enqueuedAt: Long
    )

    private val entries = ArrayDeque<Entry>()

    val size: Int get() = entries.size

    /** 入队结果，调用方据此留痕（三种结果都必须可 grep）。 */
    sealed interface OfferResult {
        /** 已入队，等待槽位释放后补播。 */
        data object Enqueued : OfferResult

        /** 队列里已有同 pendingId 的条目，保留最早一条。 */
        data object Duplicate : OfferResult

        /** 队列已满，该笔被丢弃 —— 落库与通知不受影响，仅弹窗不补播。 */
        data object Full : OfferResult
    }

    fun offer(info: PendingChangeInfo): OfferResult {
        if (entries.any { it.info.pendingId == info.pendingId }) {
            return OfferResult.Duplicate
        }
        if (entries.size >= capacity) {
            return OfferResult.Full
        }
        entries.addLast(Entry(info, now()))
        return OfferResult.Enqueued
    }

    /** 出队结果。 */
    sealed interface PollResult {
        /** 队列为空。 */
        data object Empty : PollResult

        /** 首条已过 TTL，出队即弃；调用方留痕后应继续 [poll] 下一笔。 */
        data class Expired(val pendingId: String, val ageMs: Long) : PollResult

        /** 可补播的候选，[waitedMs] 为排队耗时（诊断用）。 */
        data class Ready(val info: PendingChangeInfo, val waitedMs: Long) : PollResult
    }

    fun poll(): PollResult {
        while (entries.isNotEmpty()) {
            val head = entries.first()
            val ageMs = now() - head.enqueuedAt
            entries.removeFirst()
            if (ageMs > ttlMillis) {
                return PollResult.Expired(head.info.pendingId, ageMs)
            }
            return PollResult.Ready(head.info, ageMs)
        }
        return PollResult.Empty
    }

    /** 清空队列（功能停用、进程状态重置时调用），返回清除的条数。 */
    fun clear(): Int {
        val removed = entries.size
        entries.clear()
        return removed
    }
}
