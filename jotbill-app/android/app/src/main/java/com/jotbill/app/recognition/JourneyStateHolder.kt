package com.jotbill.app.recognition

/**
 * 旅程状态的唯一持有者(批 4 S4)。
 *
 * 现状的三套真相源——service 的 stable/handoff 易失字段、协调器的上下文视图、
 * buffer 的槽位与栅栏——中,service 层的三个易失字段自本类起集中存储:
 * service 通过自定义访问器委托,62 个使用点零改动。协调器上下文是事件派生的
 * 视图(批 0 门禁测试已钉死),保持其内部私有,不进入本持有者。
 *
 * 线程约定:与原字段一致,@Volatile 保证跨线程可见;复杂状态迁移(如
 * "交接保留"判定)仍由 service 主线程逻辑完成,本类只做存储,不做策略。
 *
 * S5 计划:在此增加旅程身份(journeyId/锚点/世代),handleStable 的
 * "指纹变化=新会话"判定改为锚点判定。
 */
internal object JourneyStateHolder {
    @Volatile
    var activeStableCheckout: StableCheckoutPage? = null

    @Volatile
    var handoffStableCheckout: StableCheckoutPage? = null

    @Volatile
    var handoffForegroundPackage: String? = null

    /** 服务销毁/断连时的整体清理。 */
    fun clearAll() {
        activeStableCheckout = null
        handoffStableCheckout = null
        handoffForegroundPackage = null
    }
}
