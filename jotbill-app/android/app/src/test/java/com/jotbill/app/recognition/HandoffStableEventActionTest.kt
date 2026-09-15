package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 挂起交接期间稳定事件处置判据的回归测试。
 *
 * X1 真机故障时序（2026-09-10 17:09，第三次复现，与 09-09 20:55 / 22:10 同构）：
 * - 17:09:17.149 交接栅栏已设置（指纹已按候选校正），购买意图 Accepted；
 * - 17:09:17.165 支付交接会话预写入，结算截图 AI 已派发；
 * - 17:09:17.345 同包迟到稳定事件（转场 UI 指纹漂移，距结算页稳定 6.4s）
 *   超出旧漂移尾波窗口（锚定页面 stableAt + 2.5s）被判"返回其他结算页"丢弃；
 * - 17:09:18.105 支付上下文失去引用 fallback 新会话 23eafff1；
 * - 17:09:21.837 ¥55 支付证据落错会话 matched=false → 待确认而非自动入账。
 *
 * 根因是过渡窗锚点错误：页面 stableAt 到用户提交订单天然间隔数秒（本次 6.4s），
 * 页面锚点 + 2.5s 窗口把挂起后 196ms 的转场事件放进了"新订单"判定。
 * 修复后锚定挂起时刻。本测试锁定判据边界：
 * 过渡期事件保留挂起会话，跨包/窗口外的新结算页仍按新订单丢弃。
 */
class HandoffStableEventActionTest {

    private val pdd = "com.xunmeng.pinduoduo"
    private val jd = "com.jingdong.app.mall"
    private val retainedFp = "a5bd22759f89"
    private val driftedFp = "baa740889d74"

    /**
     * 挂起后 196ms 的同包转场事件必须保留挂起会话。
     *
     * 这是本次修复的核心场景，对应 17:09:17.345 被误丢弃的那次事件。
     */
    @Test
    fun `挂起后196ms同包漂移事件保留挂起会话`() {
        assertEquals(
            "转场 UI 重发不代表新订单，丢弃会让支付证据落错会话",
            HandoffStableEventAction.KEEP_RETAINED,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = driftedFp,
                retainedPackage = pdd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 17_000L,
                observedAtMillis = 17_196L
            )
        )
    }

    /**
     * 页面稳定后 6.4s 才提交订单的真实节奏下，事件距挂起仍在窗口内。
     *
     * 旧锚点（页面 stableAt + 2.5s）恰好漏掉这条；新锚点（挂起时刻）覆盖。
     */
    @Test
    fun `挂起后数秒的转场事件仍在过渡窗内`() {
        assertEquals(
            "挂起时刻锚点下，6.5s 的迟到事件仍在 10s 过渡窗内",
            HandoffStableEventAction.KEEP_RETAINED,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = driftedFp,
                retainedPackage = pdd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 0L,
                observedAtMillis = 6_500L
            )
        )
    }

    /**
     * 过渡窗外（>10s）的同包不同指纹结算页按新订单丢弃。
     *
     * 用户真正回到购物应用开始逛新订单不能被挂起会话永久拦截。
     */
    @Test
    fun `过渡窗外的同包不同指纹按新订单丢弃`() {
        assertEquals(
            "窗口外的新结算页必须能建立新会话，否则新订单永远截不到图",
            HandoffStableEventAction.DISCARD_RETAINED,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = driftedFp,
                retainedPackage = pdd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 0L,
                observedAtMillis = 15_000L
            )
        )
    }

    /**
     * 返回原结算页（同指纹）任何时刻都恢复挂起会话。
     *
     * 与过渡窗无关：同指纹就是同一个订单，恢复不消耗截图也不覆盖候选。
     */
    @Test
    fun `同指纹返回原结算页永远恢复`() {
        assertEquals(
            "即使远超过渡窗，返回原结算页仍是恢复而非丢弃",
            HandoffStableEventAction.RESTORE,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = retainedFp,
                retainedPackage = pdd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 0L,
                observedAtMillis = 60_000L
            )
        )
    }

    /**
     * 跨购物包的稳定事件即使在过渡窗内也立即丢弃。
     *
     * 转场重发只可能来自同一购物应用；跨包事件是真正换了战场，
     * 保留旧挂起会话只会让槽位被跨包残留霸占。
     */
    @Test
    fun `跨包稳定事件窗口内也丢弃`() {
        assertEquals(
            "京东挂起会话不得拦截拼多多页面事件",
            HandoffStableEventAction.DISCARD_RETAINED,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = driftedFp,
                retainedPackage = jd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 0L,
                observedAtMillis = 500L
            )
        )
    }

    /**
     * 事件时间早于挂起时刻（排队中的旧事件）按过渡期处理。
     *
     * 负差值不构成"窗口外"，不得把挂起前一瞬的旧事件当成新订单依据。
     */
    @Test
    fun `挂起前排队的旧事件按过渡期处理`() {
        assertEquals(
            "负时间差必须落入过渡窗，否则挂起瞬间的在途事件会被误判",
            HandoffStableEventAction.KEEP_RETAINED,
            handoffStableEventAction(
                observedPackage = pdd,
                observedFingerprint = driftedFp,
                retainedPackage = pdd,
                retainedFingerprint = retainedFp,
                handoffRetainedAtMillis = 10_000L,
                observedAtMillis = 9_900L
            )
        )
    }
}
