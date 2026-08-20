package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动重试上下文接管槽位的守卫测试。
 *
 * 拼多多回归：提交订单后进入同包收银台（预交接），协调器把 activeCheckout
 * 置空并保留快照等待消费；46 毫秒后普通页手动重试上下文因判定"无人占用"
 * 而 discard 掉该快照，导致支付交接 NO_BUFFERED_SNAPSHOT。
 *
 * 京东回归：结算页截图刚发起就被同包中间页取消，交接会话只剩元数据外壳；
 * 守卫若只看"是否存在交接会话"，空壳交接会霸占槽位 40 秒，用户手动截图
 * 全部被 NO_ACTIVE_CHECKOUT 拒绝。
 */
class UserRetrySlotTakeoverTest {

    @Test
    fun 交接会话仍有快照时禁止手动重试接管() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = true,
                activeCaptureTarget = null
            )
        )
    }

    @Test
    fun 有快照的交接优先于任何活跃目标判定() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = true,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = true,
                activeCaptureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        )
    }

    @Test
    fun 空壳交接会话不得阻止手动截图() {
        assertTrue(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = null
            )
        )
    }

    @Test
    fun 存在真实结算会话时禁止手动重试接管() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        )
    }

    @Test
    fun 已是手动重试会话时允许继续接管() {
        assertTrue(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY
            )
        )
    }
}
