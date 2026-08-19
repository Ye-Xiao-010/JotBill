package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动重试上下文接管槽位的守卫测试。
 *
 * 真机故障回归：拼多多提交订单后进入同包收银台（预交接），协调器把
 * activeCheckout 置空并保留快照等待消费；46 毫秒后普通页手动重试上下文
 * 因判定"无人占用"而 discard 掉该快照，导致支付交接 NO_BUFFERED_SNAPSHOT，
 * 结算页商品信息永久丢失。
 */
class UserRetrySlotTakeoverTest {

    @Test
    fun 交接挂起期间禁止手动重试接管槽位() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffSuspended = true,
                activeCaptureTarget = null
            )
        )
    }

    @Test
    fun 交接挂起优先于任何活跃目标判定() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffSuspended = true,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffSuspended = true,
                activeCaptureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        )
    }

    @Test
    fun 存在真实结算会话时禁止手动重试接管() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffSuspended = false,
                activeCaptureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        )
    }

    @Test
    fun 无交接且无活跃会话时允许接管() {
        assertTrue(
            canUserRetryTakeOverSlot(
                handoffSuspended = false,
                activeCaptureTarget = null
            )
        )
    }

    @Test
    fun 已是手动重试会话时允许继续接管() {
        assertTrue(
            canUserRetryTakeOverSlot(
                handoffSuspended = false,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY
            )
        )
    }
}
