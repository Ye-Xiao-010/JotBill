package com.jotbill.app.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [RecentPaymentCooldown] 单测：钉死两条方向相反的不变式 ——
 * ① 确认入账后仍停结果页，重复观测必须被抑制；
 * ② 冷却到期后同额的新支付必须放行。
 *
 * 两者缺一不可。只测①会得到一个"把所有同额后续支付都吞掉"的实现，而误杀比误报
 * 严重得多：重复卡用户能在待确认中心看见并删除，漏掉的交易则是**静默丢账**。
 */
class RecentPaymentCooldownTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PKG = "com.tencent.mm"
    }

    @Before
    fun setUp() {
        RecentPaymentCooldown.reset()
    }

    /**
     * 判别性自检：在"永不冷却"的哨兵实现下断言**不再拦截**。
     *
     * 方向与其它用例相反，因此每次跑全量都等于在验证"那条不变式确实被实现支撑、
     * 而不是恒真断言"——真正的判别由哨兵关掉后其余用例转红来证明。
     */
    /** 核心不变式：消费后 147ms（真机实测值）的重复观测必须被抑制。 */
    @Test
    fun suppressesReobservationShortlyAfterConsumption() {
        RecentPaymentCooldown.rememberCard("pending-orphan-4", PKG, 3906L, null)
        assertEquals(PKG + "|3906", RecentPaymentCooldown.markConsumed("pending-orphan-4", NOW))

        val remaining = RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, NOW + 147L)
        assertNotNull("消费后 147ms 的重复观测必须被抑制", remaining)
        assertEquals(RecentPaymentCooldown.COOLDOWN_MILLIS - 147L, remaining!!)
    }

    /**
     * 反向不变式：冷却到期后同额的新支付必须放行。
     *
     * 判别性：把 `cooldownRemaining` 改成永不返回非 null（等价于去掉整个冷却），
     * `suppressesReobservationShortlyAfterConsumption` 必红。
     */
    @Test
    fun allowsSameAmountPaymentAfterCooldownExpires() {
        RecentPaymentCooldown.rememberCard("pending-orphan-4", PKG, 3906L, null)
        RecentPaymentCooldown.markConsumed("pending-orphan-4", NOW)

        assertNotNull(
            "窗口内最后一毫秒仍须拦截",
            RecentPaymentCooldown.cooldownRemaining(
                PKG, 3906L, null, NOW + RecentPaymentCooldown.COOLDOWN_MILLIS - 1
            )
        )
        assertNull(
            "到点必须放行",
            RecentPaymentCooldown.cooldownRemaining(
                PKG, 3906L, null, NOW + RecentPaymentCooldown.COOLDOWN_MILLIS
            )
        )
    }

    /** 未登记过的 pendingId 不应凭空启动冷却，否则会永久误杀该金额。 */
    @Test
    fun unknownPendingIdStartsNoCooldown() {
        assertNull(RecentPaymentCooldown.markConsumed("pending-does-not-exist", NOW))
        assertNull(RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, NOW))
    }

    /** 冷却按 (支付包, 金额) 隔离：同金额换支付包、或同包换金额，都必须放行。 */
    @Test
    fun cooldownIsScopedToPaymentPackageAndAmount() {
        RecentPaymentCooldown.rememberCard("pending-a", PKG, 3906L, null)
        RecentPaymentCooldown.markConsumed("pending-a", NOW)

        assertNotNull(RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, NOW + 100L))
        assertNull(
            "支付宝的同额支付不应被微信的冷却牵连",
            RecentPaymentCooldown.cooldownRemaining(
                "com.eg.android.AlipayGphone", 3906L, null, NOW + 100L
            )
        )
        assertNull(
            "不同金额不应被牵连",
            RecentPaymentCooldown.cooldownRemaining(PKG, 9900L, null, NOW + 100L)
        )
    }

    /**
     * 连续两笔真实同额支付（各走完整生命周期）必须两笔都放行。
     *
     * 最易错的组合：第二笔的冷却键来自**第一笔的 pendingId**，`rememberCard`
     * 的换 key 清理若写错，第二笔会被自己上一轮的残留挡下。
     */
    @Test
    fun twoSequentialSameAmountPaymentsBothLand() {
        RecentPaymentCooldown.rememberCard("pending-1", PKG, 3906L, null)
        RecentPaymentCooldown.markConsumed("pending-1", NOW)
        assertNotNull(RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, NOW + 147L))

        // 用户已离开结果页，30 秒后完成了一笔新的同额支付
        val second = NOW + RecentPaymentCooldown.COOLDOWN_MILLIS + 30_000L
        RecentPaymentCooldown.rememberCard("pending-2", PKG, 3906L, null)
        assertNull(
            "第二笔同额支付必须放行",
            RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, second)
        )
    }

    /** 弹窗 30s 自动关闭不产生 removed 事件，因此不应启动冷却（职责归 90s 去重）。 */
    @Test
    fun plainNewPendingDoesNotStartCooldown() {
        RecentPaymentCooldown.rememberCard("pending-x", PKG, 3580L, null)
        RecentPaymentCooldown.onPendingChangeForTest(
            PendingChangeInfo(
                pendingId = "pending-x",
                type = "expense",
                amountMinor = 3580L,
                sourceType = PendingSourceTypes.ACCESSIBILITY,
                conflictReason = null,
                occurredAt = NOW,
                isNew = true,
                removed = false
            )
        )
        assertNull(RecentPaymentCooldown.cooldownRemaining(PKG, 3580L, null, NOW))
    }

    /** `removed=true` 事件确实会启动冷却（监听器接线正确性）。 */
    @Test
    fun removedEventStartsCooldown() {
        RecentPaymentCooldown.rememberCard("pending-r", PKG, 3580L, null)
        RecentPaymentCooldown.onPendingChangeForTest(
            PendingChangeInfo(
                pendingId = "pending-r",
                type = "expense",
                amountMinor = 3580L,
                sourceType = PendingSourceTypes.ACCESSIBILITY,
                conflictReason = null,
                occurredAt = NOW,
                isNew = false,
                removed = true
            ),
            now = NOW
        )
        assertNotNull(RecentPaymentCooldown.cooldownRemaining(PKG, 3580L, null, NOW + 1L))
    }

    /**
     * 规模上限：长期运行不得无限增长，且必须按**插入序**（FIFO）淘汰。
     *
     * 判别性：本例让**最后登记**的卡持有**字典序最小**的 id（登记序与字典序完全相反）。
     * 按字典序裁剪的实现会在插入它的同一次调用里把它当成"最小"裁掉 ⇒ `markConsumed`
     * 返回 null ⇒ 本例红。生产 pendingId 是 `stableId()` 的 SHA-256 摘要，字典序与
     * 登记时间无关，正是本例模拟的形态。
     *
     * 旧版本用递增 id（`pending-0`…`pending-499`）断言同一件事，但 `pending-499`
     * 恰好排在字典序第 56 位（只有 55 个串比它大）⇒ 落在 64 条上限内侥幸存活
     * ⇒ 按字典序裁剪也能通过，并不判别。
     */
    @Test
    fun prunesByInsertionOrderNotByIdOrdering() {
        val total = 200
        repeat(total) { i ->
            // 0199, 0198, … 0000：零填充使字典序等于数值序，故登记序与字典序相反
            val id = "pending-%04d".format(total - 1 - i)
            RecentPaymentCooldown.rememberCard(id, PKG, (i + 1).toLong(), null)
        }

        assertEquals(
            "最后登记的卡必须仍在跟踪表内(FIFO 裁剪不得淘汰最新)",
            "$PKG|$total",
            RecentPaymentCooldown.markConsumed("pending-0000", NOW)
        )
        assertNotNull(RecentPaymentCooldown.cooldownRemaining(PKG, total.toLong(), null, NOW + 1L))
    }

    /**
     * orderId 护栏（拍板 A，2026-11-04）：冷却记录与新信号的订单号**都非空且
     * 不同** ⇒ 不抑制（单号已证明是两笔，不得以冷却吞并第二笔真单）；任一为空
     * 或相等时维持原 15s 抑制语义。
     */
    @Test
    fun orderIdConflictBypassesCooldownGuard() {
        RecentPaymentCooldown.rememberCard("pending-order-a", PKG, 3906L, "ORDER-A")
        RecentPaymentCooldown.markConsumed("pending-order-a", NOW)

        assertNull(
            "订单号冲突必须放行（第二笔真单不得被冷却吞并）",
            RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, "ORDER-B", NOW + 147L)
        )
        assertNotNull(
            "同一订单号仍须抑制（原语义不变）",
            RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, "ORDER-A", NOW + 147L)
        )
        assertNotNull(
            "新信号缺订单号仍须抑制（原语义不变）",
            RecentPaymentCooldown.cooldownRemaining(PKG, 3906L, null, NOW + 147L)
        )
    }
}