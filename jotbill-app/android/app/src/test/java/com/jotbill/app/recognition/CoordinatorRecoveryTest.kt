package com.jotbill.app.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协调器熔断自愈与熔断敏感度的回归测试。
 *
 * 真机 .tmp-device-capture-20260823-123024 中协调器在采集开始前即已熔断
 * （closed=true, failClosedRequested=true, emergencyClosed=true），
 * 整个 100 秒窗口内 12 次手动截图全部 SERVICE_NOT_CONNECTED、
 * 自动截图 0 次；期间门禁已在 12:30:45 正确识别到结算页（ACCEPTED），
 * 但因协调器不可用而无法建立候选。日志中无任何服务生命周期记录，
 * 说明系统不会因熔断回调 onServiceConnected，旧实例永久失效。
 *
 * 该轮修复包含两部分：
 * 1. 事件入口检测到熔断即重建截图链路（自愈）；
 * 2. 暂时性故障（主线程卡顿、单事件业务异常）不再熔断，降低熔断触发率。
 *
 * 本测试锁定"何种故障应当熔断"这一判据，以及自愈所需的状态查询语义。
 */
class CoordinatorRecoveryTest {

    /**
     * 主线程卡顿导致的事件分发超时不得熔断。
     *
     * 卡顿是暂时性的：投递到主线程的任务仍会稍后执行，后续事件可继续处理。
     * 一次 5 秒卡顿换来截图能力永久失效，代价极不对称。
     */
    @Test
    fun 分发超时不应熔断协调器() {
        assertFalse(
            "主线程卡顿属暂时性故障，应放弃本次事件而非销毁协调器",
            shouldFailClosedForDispatchFailure(DispatchFailureKind.MAIN_THREAD_TIMEOUT)
        )
    }

    /**
     * 单个事件的业务异常不得熔断。
     *
     * 节点树读取失败、状态不一致等属于单事件问题，放弃该事件即可。
     */
    @Test
    fun 单事件业务异常不应熔断协调器() {
        assertFalse(
            shouldFailClosedForDispatchFailure(DispatchFailureKind.EVENT_RUNTIME_ERROR)
        )
    }

    /**
     * Handler 不可用必须熔断。
     *
     * 主线程 Handler 无法投递意味着协调器已无法维持状态机，
     * 继续持有截图字节与缓冲状态不安全。
     */
    @Test
    fun 主线程不可用必须熔断() {
        assertTrue(
            shouldFailClosedForDispatchFailure(DispatchFailureKind.MAIN_HANDLER_UNAVAILABLE)
        )
    }

    /**
     * 严重错误（OutOfMemoryError 等）必须熔断。
     */
    @Test
    fun 严重错误必须熔断() {
        assertTrue(
            shouldFailClosedForDispatchFailure(DispatchFailureKind.FATAL_ERROR)
        )
    }

    /**
     * 服务销毁必须熔断。
     */
    @Test
    fun 服务销毁必须熔断() {
        assertTrue(
            shouldFailClosedForDispatchFailure(DispatchFailureKind.SERVICE_DESTROYED)
        )
    }

    /**
     * 任一关闭标志为真都应判定为需要重建。
     *
     * 三个标志分别由不同路径设置：closed 由主线程收口、
     * failClosedRequested 由跨线程请求先行置真、emergencyClosed 由兜底路径设置。
     * 自愈判据必须覆盖全部三者，否则会漏掉"跨线程已请求但主线程尚未收口"的中间态。
     */
    @Test
    fun 任一关闭标志为真都需要重建() {
        assertTrue(shouldRecoverCoordinator(closed = true, failClosedRequested = false, emergencyClosed = false))
        assertTrue(shouldRecoverCoordinator(closed = false, failClosedRequested = true, emergencyClosed = false))
        assertTrue(shouldRecoverCoordinator(closed = false, failClosedRequested = false, emergencyClosed = true))
    }

    /**
     * 三个标志均为假时不得重建。
     *
     * 协调器健康时重建会白白丢掉已存快照与进行中的交接。
     */
    @Test
    fun 协调器健康时不得重建() {
        assertFalse(
            "健康协调器被重建会丢掉已存快照与进行中的支付交接",
            shouldRecoverCoordinator(
                closed = false,
                failClosedRequested = false,
                emergencyClosed = false
            )
        )
    }
}
