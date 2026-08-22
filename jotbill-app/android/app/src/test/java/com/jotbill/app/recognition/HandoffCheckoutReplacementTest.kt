package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂起交接会话替换判据的回归测试。
 *
 * 京东真机回归：某个会话在失去快照与栅栏后仍永久占据 retained 交接位。
 * 日志中 8941f58a 从 20:53:18 起反复出现在 retained 位置，
 * 期间 current 已换成 170c28f7、c221b97d、2b103eca 等有效新会话却全被丢弃，
 * "交接栅栏设置失败(槽位不存在)"随之重复出现，
 * 自动截图在两分钟内仅成功一次（恰好是 retained=null 的那一刻）。
 *
 * 根因是旧实现无条件沿用已挂起会话。本测试锁定替换判据的边界：
 * 既要清掉真正无价值的残留会话，也不能替换掉仍在等待支付交接消费的会话。
 */
class HandoffCheckoutReplacementTest {

    /**
     * 挂起会话已无快照与栅栏，且存在当前会话时必须替换。
     *
     * 这是本次修复的核心场景，对应真机上 8941f58a 残留的解除。
     */
    @Test
    fun 残留会话在有新会话时被替换() {
        assertTrue(
            "既无快照也无栅栏的挂起会话已无价值，必须让位给当前会话",
            shouldReplaceHandoffCheckout(
                hasCurrentCheckout = true,
                retainedHasSnapshot = false,
                retainedHasFence = false
            )
        )
    }

    /**
     * 挂起会话仍有快照时不得替换。
     *
     * 该快照正等待切支付应用后被消费，替换会让已截到的图与支付证据失去关联。
     */
    @Test
    fun 挂起会话仍有快照时不替换() {
        assertFalse(
            shouldReplaceHandoffCheckout(
                hasCurrentCheckout = true,
                retainedHasSnapshot = true,
                retainedHasFence = false
            )
        )
    }

    /**
     * 挂起会话仍有交接栅栏时不得替换。
     *
     * 栅栏已设但截图在飞行中的空窗期，替换会丢掉即将落盘的结算截图。
     */
    @Test
    fun 挂起会话仍有栅栏时不替换() {
        assertFalse(
            "栅栏代表交接仍在进行，截图可能只是尚未落盘",
            shouldReplaceHandoffCheckout(
                hasCurrentCheckout = true,
                retainedHasSnapshot = false,
                retainedHasFence = true
            )
        )
    }

    /**
     * 快照与栅栏同时存在时不得替换。
     */
    @Test
    fun 快照与栅栏同时存在时不替换() {
        assertFalse(
            shouldReplaceHandoffCheckout(
                hasCurrentCheckout = true,
                retainedHasSnapshot = true,
                retainedHasFence = true
            )
        )
    }

    /**
     * 没有当前会话可接替时不得替换。
     *
     * 此时替换只会把挂起位清成空值，反而丢失原有交接上下文。
     */
    @Test
    fun 无当前会话可接替时不替换() {
        assertFalse(
            shouldReplaceHandoffCheckout(
                hasCurrentCheckout = false,
                retainedHasSnapshot = false,
                retainedHasFence = false
            )
        )
    }
}
