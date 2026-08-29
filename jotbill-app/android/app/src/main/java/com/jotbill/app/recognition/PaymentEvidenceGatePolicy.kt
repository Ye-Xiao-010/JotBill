package com.jotbill.app.recognition

/**
 * 支付证据门禁策略:无障碍主路径与截图兜底共用的敏感页锁存判定。
 *
 * 背景(阶段七 P0):微信支付密码页常被分类为 UNKNOWN 页,且与空节点树帧
 * 交替出现——空树帧无法自行判定敏感。锁存窗口的语义是:只要窗口内见过
 * 敏感支付页,窗口过期前的一切证据截图/派发都被禁止,由显式的
 * [onSensitivePageObserved] 刷新窗口起点。
 *
 * 本类是纯 Kotlin 决策器,不持有 Android 依赖;时钟可注入以便单测钉死
 * 窗口语义。两条证据通道必须经由同一实例判定,禁止各自维护锁存字段。
 *
 * 可见性说明:public 而非 internal——kapt 为 internal 类型属性生成的 Java
 * stub 会产生非法的 final+volatile 修饰符组合(与 CheckoutBufferGateway 同因)。
 */
class PaymentEvidenceGatePolicy(
    private val sensitiveLatchMillis: Long = DEFAULT_SENSITIVE_LATCH_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    init {
        require(sensitiveLatchMillis > 0) { "sensitiveLatchMillis 必须大于 0" }
    }

    private var lastSensitivePageAt: Long? = null

    /** 观察到敏感支付页时刷新锁存窗口起点。 */
    fun onSensitivePageObserved(at: Long = nowMillis()) {
        lastSensitivePageAt = at
    }

    /** 锁存窗口是否活跃;从未见过敏感页时恒为 false。 */
    fun isSensitiveLatchActive(at: Long = nowMillis()): Boolean {
        val last = lastSensitivePageAt ?: return false
        return at - last < sensitiveLatchMillis
    }

    /** 距最近一次敏感页观察的毫秒数;从未观察过返回 null(供诊断日志)。 */
    fun sensitiveLatchElapsedMillis(at: Long = nowMillis()): Long? =
        lastSensitivePageAt?.let { at - it }

    companion object {
        /** 与阶段七 P0 修复的锁存窗口一致:覆盖密码页与空树帧的交替周期。 */
        const val DEFAULT_SENSITIVE_LATCH_MILLIS = 60_000L
    }
}
