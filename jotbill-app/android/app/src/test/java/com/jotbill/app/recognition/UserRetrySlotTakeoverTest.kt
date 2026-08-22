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

    /**
     * 交接栅栏已设置但截图尚未落盘时，禁止手动重试接管。
     *
     * 这是京东弹窗结算页的真机回归：结算页稳定后数百毫秒即跳中间页，
     * 栅栏已设而候选仍为空。若只看 handoffHasSnapshot 就会误判为空壳并放行，
     * 栅栏随即被 discard（真机日志 reason=USER_RETRY_NEW_PAGE, consumed=false），
     * 而 handoffCheckout 仍指向该会话形成永久残留，
     * 此后"交接栅栏设置失败(槽位不存在)"反复出现、自动截图长期不触发。
     */
    @Test
    fun 交接栅栏已设置但截图未落盘时禁止接管() {
        assertFalse(
            "栅栏存在即代表会话正等待支付交接，即使候选为空也不得被抢占",
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = null,
                handoffHasFence = true
            )
        )
    }

    /**
     * 栅栏保护优先于任何活跃目标判定。
     */
    @Test
    fun 栅栏保护优先于活跃目标判定() {
        assertFalse(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY,
                handoffHasFence = true
            )
        )
    }

    /**
     * 既无快照也无栅栏才是真空壳，此时必须允许接管。
     *
     * 否则空壳交接会长期霸占槽位，用户手动截图全部被 NO_ACTIVE_CHECKOUT 拒绝。
     */
    @Test
    fun 无快照且无栅栏的真空壳允许接管() {
        assertTrue(
            canUserRetryTakeOverSlot(
                handoffHasSnapshot = false,
                activeCaptureTarget = null,
                handoffHasFence = false
            )
        )
    }
}
