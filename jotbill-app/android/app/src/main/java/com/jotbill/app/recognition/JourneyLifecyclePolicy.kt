package com.jotbill.app.recognition

/**
 * 旅程锚点:判定"同一笔订单"的最小稳定语义(批 4 规格 §2)。
 *
 * - shoppingPackage:购物包名,必选强锚;
 * - windowClass:归一化窗口类名。同一订单的结算 Activity 不会变,适合做锚;
 * - 金额锚点为规划项:CheckoutPageObservation 目前只携带 hasFinalAmountSignal
 *   布尔量,不携带金额值;待观察摘要增加金额字段后在 S5b 引入第三锚。
 */
data class JourneyAnchor(
    val shoppingPackage: String,
    val windowClass: String
)

/**
 * 旅程生命周期决策(批 4 规格 §3,纯函数,可全矩阵单测)。
 *
 * 死亡规则(2026-08-29 用户定稿):
 * - D1 交接后等支付超时:8 分钟;
 * - D2 结算页停滞:10 分钟;
 * - D3 普通浏览离开:3 分钟;
 * - D4 被锚点不同的新旅程取代:由 [isSameJourney] 的否定隐含,调用方负责终结旧旅程;
 * - D5 显式关闭(服务断连/销毁):调用方直接清理,不经本策略。
 *
 * 敏感页与中间页不是死亡事件:它们是旅程内部状态,由调用方处理。
 */
internal class JourneyLifecyclePolicy(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val waitingPaymentTimeoutMillis: Long = DEFAULT_WAITING_PAYMENT_TIMEOUT_MILLIS,
    private val checkoutStallTimeoutMillis: Long = DEFAULT_CHECKOUT_STALL_TIMEOUT_MILLIS,
    private val leftShoppingTimeoutMillis: Long = DEFAULT_LEFT_SHOPPING_TIMEOUT_MILLIS
) {
    /** 旅程的推进阶段,决定适用哪条死亡超时。 */
    enum class Stage {
        /** 已确认稳定结算页,尚未提交/交接。 */
        CHECKOUT_STABLE,

        /** 已交接支付应用,等待支付证据或返回结算页。 */
        WAITING_PAYMENT,

        /** 离开购物与支付语境(前台是无关应用),可能随时回来。 */
        LEFT_SHOPPING
    }

    /** 旅程的运行期可变部分;身份(journeyId)由调用方生成并持有。 */
    data class JourneyRuntime(
        val journeyId: String,
        val anchor: JourneyAnchor,
        val bornAt: Long,
        val stage: Stage,
        /** 最近一次推进(稳定页刷新/交接/回结算/进入本阶段)的时间。 */
        val lastProgressAt: Long
    )

    init {
        require(waitingPaymentTimeoutMillis > 0 && checkoutStallTimeoutMillis > 0 && leftShoppingTimeoutMillis > 0) {
            "死亡超时参数必须全部大于 0"
        }
    }

    /** 锚点相同即同一旅程:同包同金额连单按用户决定归并(第二笔走孤儿待确认)。 */
    fun isSameJourney(existing: JourneyRuntime, incoming: JourneyAnchor): Boolean =
        existing.anchor == incoming

    /** 旅程在 at 时刻是否已满足死亡规则(按当前阶段套用对应超时)。 */
    fun shouldDie(journey: JourneyRuntime, at: Long = nowMillis()): Boolean {
        val timeout = when (journey.stage) {
            Stage.WAITING_PAYMENT -> waitingPaymentTimeoutMillis
            Stage.CHECKOUT_STABLE -> checkoutStallTimeoutMillis
            Stage.LEFT_SHOPPING -> leftShoppingTimeoutMillis
        }
        return at - journey.lastProgressAt >= timeout
    }

    /** 阶段推进:返回带新阶段与刷新推进时间的新运行态。 */
    fun advance(journey: JourneyRuntime, stage: Stage, at: Long = nowMillis()): JourneyRuntime =
        journey.copy(stage = stage, lastProgressAt = at)

    companion object {
        const val DEFAULT_WAITING_PAYMENT_TIMEOUT_MILLIS = 8 * 60_000L
        const val DEFAULT_CHECKOUT_STALL_TIMEOUT_MILLIS = 10 * 60_000L
        const val DEFAULT_LEFT_SHOPPING_TIMEOUT_MILLIS = 3 * 60_000L
    }
}
