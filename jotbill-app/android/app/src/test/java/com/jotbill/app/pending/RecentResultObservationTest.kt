package com.jotbill.app.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [RecentResultObservation] 单测：钉死"同一笔"判据的两条方向相反的不变式 ——
 * ① 同包同额同文本哈希 ⇒ 判为同一笔（不管隔多久）；
 * ② 哈希不同、或该笔已终态 ⇒ 必须放行，不得吞掉真实的新支付。
 *
 * 只测①会得到一个"把所有同额支付都当成重复观测"的实现，而误杀是静默丢账；
 * 只测②则等于没有判据。两条必须同时成立。
 */
class RecentResultObservationTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PKG = "com.tencent.mm"
        const val HASH = "hash-1990-same-page"
    }

    @Before
    fun setUp() {
        RecentResultObservation.reset()
    }

    /** 核心不变式：同一页面被重复观测，在 15 秒窗口内必须命中。 */
    @Test
    fun hitsOnSamePackageAmountAndHash() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertEquals(
            "同包同额同哈希、14 秒内必须命中",
            "pending-1",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 14_000L)
        )
    }

    /**
     * 边界（闭区间）：正好 15.000 秒仍算合并。
     *
     * 与 SQL `BETWEEN` 的闭区间语义对齐；改成一端开一端闭会让同一笔判定
     * 在两套机制间出现 1 毫秒的缝隙。
     */
    @Test
    fun matchesExactlyAtFifteenSeconds() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertEquals(
            "15.000 秒整必须命中",
            "pending-1",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 15_000L)
        )
    }

    /**
     * 反向不变式（用户 2026-10-24 裁定）：超过 15 秒即视为新交易。
     *
     * ⚠️ 判别性边界：`pruneLocked` 用同一常量清理超窗条目，所以即使去掉
     * `findSameObservation` 里的行内时间条件，本例仍会通过。行内条件是**防御性**的
     * （防止将来放宽清理逻辑时匹配窗悄悄变宽），不是本用例的判别来源。
     */
    @Test
    fun doesNotMatchBeyondFifteenSeconds() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertNull(
            "15.001 秒必须放行 —— 超过 15 秒视为新交易，宁可误报不可误杀",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 15_001L)
        )
    }

    /** 反向不变式：哈希不同 ⇒ 真实第二笔，必须放行。 */
    @Test
    fun differentHashIsTreatedAsNewPayment() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertNull(
            "文本哈希不同说明是另一笔，不得合并",
            RecentResultObservation.findSameObservation(PKG, 1990L, "hash-order-2", NOW + 20_000L)
        )
    }

    /** 包名或金额任一不同都不命中 —— 判据是三元组，不是单列。 */
    @Test
    fun differentAmountOrPackageDoesNotMatch() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertNull(
            "不同金额不得命中",
            RecentResultObservation.findSameObservation(PKG, 1991L, HASH, NOW)
        )
        assertNull(
            "不同支付包不得命中",
            RecentResultObservation.findSameObservation("com.eg.android.AlipayGphone", 1990L, HASH, NOW)
        )
    }

    /** 哈希缺失（通知回退来源）⇒ 不参与本判据，退回时间窗兜底。 */
    @Test
    fun nullOrBlankHashNeverMatches() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertNull(RecentResultObservation.findSameObservation(PKG, 1990L, null, NOW))
        assertNull(RecentResultObservation.findSameObservation(PKG, 1990L, "   ", NOW))
    }

    /** 哈希为空时 `remember` 不登记 —— 避免用空值把后续所有同额支付都判成重复。 */
    @Test
    fun rememberIgnoresBlankHash() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, null, NOW)
        RecentResultObservation.remember("pending-2", PKG, 1990L, "  ", NOW)

        assertNull(
            "空哈希不得登记",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW)
        )
    }

    /** 该笔已终态（确认/忽略）⇒ 登记解除，之后同额同文本的新支付必须重新建卡。 */
    @Test
    fun forgetOnConsumptionAllowsNextSamePayment() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)
        assertNotNull(RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW))

        RecentResultObservation.forget("pending-1")

        assertNull(
            "确认入账后同额同文本的新支付不得被旧登记吞掉",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 20_000L)
        )
    }

    /**
     * 超窗条目必须被 [RecentResultObservation] 真正清理，而不是只靠匹配条件过滤。
     *
     * 判别性：若把 `pruneLocked` 去掉、只留匹配时的 15 秒判断，
     * 第二次以窗口内的 now 查询会命中残留条目 ⇒ 本例必红。
     */
    @Test
    fun expiredEntryIsPrunedFromMemory() {
        RecentResultObservation.remember("pending-1", PKG, 1990L, HASH, NOW)

        assertNull(
            "超窗查询不得命中",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 15_001L)
        )
        assertNull(
            "超窗条目必须已被清理，不得在后续窗口内复活",
            RecentResultObservation.findSameObservation(PKG, 1990L, HASH, NOW + 14_000L)
        )
    }

    /**
     * 上限按**插入序**淘汰，不按 pendingId 排序。
     *
     * 判别性来自**反序构造**：插入序是 `pending-64, pending-63, … pending-0`，
     * 而字典序最小的是 `pending-0`（最后插入的那条）。
     * - 按插入序淘汰 ⇒ 最先插入的 `pending-64`（金额 1000 / hash-0）被删；
     * - 按字典序淘汰 ⇒ 会删掉最后插入的 `pending-0`，`pending-64` 反而存活。
     *
     * 若按正序 `pending-0…64` 构造，字典序最小恰好是 `pending-0`，两种实现都通过，
     * 用例就失去判别力（这是 2026-10-24 复审发现的假绿写法）。
     */
    @Test
    fun prunesByInsertionOrderNotByIdOrdering() {
        repeat(65) { index ->
            RecentResultObservation.remember(
                pendingId = "pending-${64 - index}",
                paymentPackage = PKG,
                amountMinor = 1_000L + index,
                rawTextHash = "hash-$index",
                now = NOW
            )
        }

        assertNull(
            "最先插入的一条必须被淘汰（pending-64，金额 1000）",
            RecentResultObservation.findSameObservation(PKG, 1_000L, "hash-0", NOW)
        )
        assertNotNull(
            "最后插入的一条必须存活（pending-0，金额 1064）",
            RecentResultObservation.findSameObservation(PKG, 1_064L, "hash-64", NOW)
        )
    }
}
