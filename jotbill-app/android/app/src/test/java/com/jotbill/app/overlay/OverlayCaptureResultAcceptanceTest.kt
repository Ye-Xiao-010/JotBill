package com.jotbill.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮按钮采纳截图结果的回归测试。
 *
 * 用户反馈"明明已捕获但显示没有变化"。真机
 * .tmp-device-capture-20260823-154942 中自动截图 6 次 STORED
 * （15:49:57 / 15:50:28 / 15:50:34 / 15:50:53 / 15:50:58 / 15:51:17），
 * 却无一次把按钮更新为 CAPTURED_WAITING_PAYMENT。
 *
 * 根因是 onCaptureResult 要求 currentFingerprint 与结果指纹相等：
 * 自动截图用的是稳定确认时刻的指纹，而结算页指纹在持续漂移，
 * 悬浮层记录的早已是新值，相等过滤必然失败并直接 return。
 *
 * 本测试锁定"成功类结果放宽指纹匹配、失败类结果保持严格"这一判据。
 */
class OverlayCaptureResultAcceptanceTest {

    private val jd = "com.jingdong.app.mall"
    private val pdd = "com.xunmeng.pinduoduo"

    /**
     * 成功结果即便指纹已漂移也必须采纳。
     *
     * 这是本次修复的核心：快照确已写入缓冲，按钮必须显示为已捕获。
     */
    @Test
    fun 成功结果在指纹漂移后仍被采纳() {
        assertTrue(
            "STORED 证明快照已存在，不能因指纹推进而丢弃",
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = true,
                currentFingerprint = "新指纹",
                resultFingerprint = "稳定确认时的旧指纹",
                currentPackage = jd,
                resultPackage = jd,
                currentSessionId = "会话B",
                resultSessionId = "会话A"
            )
        )
    }

    /**
     * 成功结果在悬浮层尚无指纹时也应采纳。
     */
    @Test
    fun 成功结果在无当前指纹时被采纳() {
        assertTrue(
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = true,
                currentFingerprint = null,
                resultFingerprint = "某指纹",
                currentPackage = null,
                resultPackage = jd,
                currentSessionId = null,
                resultSessionId = "会话A"
            )
        )
    }

    /**
     * 跨购物包的成功结果不得采纳。
     *
     * 槽位按购物包划分，京东的截图结果不能更新拼多多页面的按钮状态。
     */
    @Test
    fun 跨购物包结果不被采纳() {
        assertFalse(
            "跨包结果会把别的应用的状态显示到当前页面",
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = true,
                currentFingerprint = "指纹A",
                resultFingerprint = "指纹A",
                currentPackage = pdd,
                resultPackage = jd,
                currentSessionId = null,
                resultSessionId = null
            )
        )
    }

    /**
     * 失败结果在指纹不一致时不得采纳。
     *
     * 否则旧页面的拒绝原因会覆盖当前页面状态，
     * 把本可点击的按钮显示成不可用。
     */
    @Test
    fun 失败结果在指纹不一致时不被采纳() {
        assertFalse(
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = false,
                currentFingerprint = "新指纹",
                resultFingerprint = "旧指纹",
                currentPackage = jd,
                resultPackage = jd,
                currentSessionId = null,
                resultSessionId = null
            )
        )
    }

    /**
     * 失败结果在会话不一致时不得采纳。
     */
    @Test
    fun 失败结果在会话不一致时不被采纳() {
        assertFalse(
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = false,
                currentFingerprint = "指纹A",
                resultFingerprint = "指纹A",
                currentPackage = jd,
                resultPackage = jd,
                currentSessionId = "会话B",
                resultSessionId = "会话A"
            )
        )
    }

    /**
     * 失败结果在指纹与会话都一致时应被采纳。
     *
     * 这是失败反馈的正常路径，不能因收紧而一并丢掉，
     * 否则用户点击后永远得不到失败提示。
     */
    @Test
    fun 失败结果在上下文一致时被采纳() {
        assertTrue(
            shouldAcceptOverlayCaptureResult(
                isSuccessResult = false,
                currentFingerprint = "指纹A",
                resultFingerprint = "指纹A",
                currentPackage = jd,
                resultPackage = jd,
                currentSessionId = "会话A",
                resultSessionId = "会话A"
            )
        )
    }

    /**
     * 保持窗口内必须维持"已捕获"，不被同包非结算页覆盖。
     *
     * 真机 .tmp-device-capture-20260823-163623 中 5 次 STORED 之后
     * 数十毫秒即抛出 NON_CHECKOUT_PAGE，若立刻回退用户看不到反馈。
     */
    @Test
    fun 保持窗口内维持已捕获状态() {
        assertTrue(
            "STORED 后 100ms 内不应回退状态",
            shouldHoldCapturedState(
                isCapturedState = true,
                capturedAt = 1_000L,
                now = 1_100L,
                holdMillis = 3_000L
            )
        )
    }

    /**
     * 保持窗口结束后应允许状态回退。
     *
     * 否则按钮会永久停在"已捕获"，无法回到可再次截图的 READY。
     */
    @Test
    fun 保持窗口结束后允许状态回退() {
        assertFalse(
            shouldHoldCapturedState(
                isCapturedState = true,
                capturedAt = 1_000L,
                now = 4_000L,
                holdMillis = 3_000L
            )
        )
    }

    /**
     * 非"已捕获"状态不受保持窗口影响。
     */
    @Test
    fun 非已捕获状态不受保持窗口影响() {
        assertFalse(
            shouldHoldCapturedState(
                isCapturedState = false,
                capturedAt = 1_000L,
                now = 1_100L,
                holdMillis = 3_000L
            )
        )
    }

    /**
     * 从未捕获过时不得进入保持窗口。
     *
     * capturedAt=0 与当前时间的差值必然远超窗口，需显式排除以免误判。
     */
    @Test
    fun 从未捕获时不进入保持窗口() {
        assertFalse(
            shouldHoldCapturedState(
                isCapturedState = true,
                capturedAt = 0L,
                now = 1_100L,
                holdMillis = 3_000L
            )
        )
    }
}
