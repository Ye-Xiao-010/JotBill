package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §8.3 sessionId 传递链路第③环的判据测试。
 *
 * [stableFrameSessionId] 决定"这一帧稳定观察属于哪个会话"。判错的后果是把订单2 的帧
 * 标成订单1 的 sessionId（改造 E 要消除的错误归属），或反过来把订单1 的帧丢掉；
 * 两者在真机上都只能靠事后日志追查，因此在纯函数层面逐条钉死判据。
 */
class StableFrameSessionIdTest {

    private companion object {
        const val PKG = "com.xunmeng.pinduoduo"
        const val OTHER_PKG = "com.taobao.taobao"
        const val SESSION = "session-1"
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
    }

    @Test
    fun 包名与指纹都一致时返回会话标识() {
        assertEquals(
            SESSION,
            stableFrameSessionId(
                activeSessionId = SESSION,
                activeShoppingPackage = PKG,
                activePageFingerprint = FP_A,
                observedShoppingPackage = PKG,
                observedPageFingerprint = FP_A
            )
        )
    }

    @Test
    fun 指纹漂移沿用会话时返回沿用后的会话标识() {
        // handleStable 的漂移沿用分支会把 page 换成新帧（沿用旧 sessionId），
        // 因此本帧指纹与 activeCheckout 记录一致，仍应返回旧会话。
        assertEquals(
            SESSION,
            stableFrameSessionId(
                activeSessionId = SESSION,
                activeShoppingPackage = PKG,
                activePageFingerprint = FP_B,
                observedShoppingPackage = PKG,
                observedPageFingerprint = FP_B
            )
        )
    }

    @Test
    fun 该帧未建立会话时返回空() {
        // 交接过渡窗内同包迟到稳定事件：截图协调器有意忽略，activeCheckout 保持为空。
        assertNull(
            stableFrameSessionId(
                activeSessionId = null,
                activeShoppingPackage = null,
                activePageFingerprint = null,
                observedShoppingPackage = PKG,
                observedPageFingerprint = FP_A
            )
        )
    }

    @Test
    fun 会话已建立但本帧指纹未被采用时返回空() {
        // 该帧既没有新建也没有沿用（attemptedAutomaticCaptures 命中等早退分支）：
        // 不得把上一帧的会话塞给本帧。
        assertNull(
            stableFrameSessionId(
                activeSessionId = SESSION,
                activeShoppingPackage = PKG,
                activePageFingerprint = FP_A,
                observedShoppingPackage = PKG,
                observedPageFingerprint = FP_B
            )
        )
    }

    @Test
    fun 购物包名不一致时返回空() {
        assertNull(
            stableFrameSessionId(
                activeSessionId = SESSION,
                activeShoppingPackage = PKG,
                activePageFingerprint = FP_A,
                observedShoppingPackage = OTHER_PKG,
                observedPageFingerprint = FP_A
            )
        )
    }

    @Test
    fun 空会话标识不视为命中() {
        assertNull(
            stableFrameSessionId(
                activeSessionId = "   ",
                activeShoppingPackage = PKG,
                activePageFingerprint = FP_A,
                observedShoppingPackage = PKG,
                observedPageFingerprint = FP_A
            )
        )
    }
}
