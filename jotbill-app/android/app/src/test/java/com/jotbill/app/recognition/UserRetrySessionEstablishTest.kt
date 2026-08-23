package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结算会话不得被手动重试上下文降级的回归测试。
 *
 * 59a41b9 为支持"结算页停留期间手动补截"而让 Accepted 分支也建立手动上下文，
 * 但未排除 CHECKOUT 角色，导致结算会话被覆盖为 USER_RETRY 目标。
 * 真机 .tmp-device-capture-20260823-131138 的连锁故障：
 *   13:13:17.164 候选已写入 source=MANUAL_CAPTURE
 *   13:13:17.165 槽位丢弃 USER_RETRY_CONTEXT_CHANGED, consumed=false（1ms 后自毁）
 *   13:13:17.797 截图状态 REJECTED, 来源=CHECKOUT_STABLE, 原因=NOT_CHECKOUT_PAGE
 * 拼多多时段门禁 7 次 ACCEPTED（13:13:15/20/23/28/32/36/40），
 * 自动截图候选却为 0，最终 CHECKOUT_REFERENCE_INVALID 落孤儿待确认。
 *
 * 本测试锁定"结算页上下文只作兜底、不建立手动会话"这一不变量。
 */
class UserRetrySessionEstablishTest {

    /**
     * 结算页角色不得建立手动会话。
     *
     * 这是本轮修复的核心：一旦建立，结算会话被降级为 USER_RETRY，
     * 自动截图再也找不到 CHECKOUT 会话。
     */
    @Test
    fun 结算页角色不得建立手动会话() {
        assertFalse(
            "结算页上下文只作兜底，建立会话会把结算态降级并使自动截图失效",
            canEstablishUserRetrySession(
                contextRole = CheckoutPageRole.CHECKOUT,
                contextUsable = true,
                activeCaptureTarget = null
            )
        )
    }

    /**
     * 结算页角色即便已是手动会话也不得再建立。
     */
    @Test
    fun 结算页角色在手动会话下仍不得建立() {
        assertFalse(
            canEstablishUserRetrySession(
                contextRole = CheckoutPageRole.CHECKOUT,
                contextUsable = true,
                activeCaptureTarget = SnapshotCaptureTarget.USER_RETRY
            )
        )
    }

    /**
     * 普通购物页在无活跃会话时允许建立手动会话。
     *
     * 这是手动补截的正常路径，不能因修复回归而被一并禁掉。
     */
    @Test
    fun 普通页无活跃会话时允许建立() {
        listOf(
            CheckoutPageRole.SHOPPING_HOME,
            CheckoutPageRole.PRODUCT_DETAIL,
            CheckoutPageRole.CART,
            CheckoutPageRole.UNKNOWN
        ).forEach { role ->
            assertTrue(
                "$role 应允许建立手动会话",
                canEstablishUserRetrySession(
                    contextRole = role,
                    contextUsable = true,
                    activeCaptureTarget = null
                )
            )
        }
    }

    /**
     * 存在活跃结算会话时不得建立手动会话。
     *
     * 否则会 discard 掉正在进行的自动截图。
     */
    @Test
    fun 存在活跃结算会话时不得建立() {
        assertFalse(
            canEstablishUserRetrySession(
                contextRole = CheckoutPageRole.UNKNOWN,
                contextUsable = true,
                activeCaptureTarget = SnapshotCaptureTarget.CHECKOUT
            )
        )
    }

    /**
     * 上下文不可用时不得建立手动会话。
     */
    @Test
    fun 上下文不可用时不得建立() {
        assertFalse(
            canEstablishUserRetrySession(
                contextRole = CheckoutPageRole.UNKNOWN,
                contextUsable = false,
                activeCaptureTarget = null
            )
        )
    }

    /**
     * 无上下文角色时不得建立手动会话。
     */
    @Test
    fun 无上下文角色时不得建立() {
        assertFalse(
            canEstablishUserRetrySession(
                contextRole = null,
                contextUsable = true,
                activeCaptureTarget = null
            )
        )
    }
}
