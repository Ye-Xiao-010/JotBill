package com.jotbill.app.recognition

/**
 * 支付环境主动扫描的停止判定（纯 Kotlin，可 JVM 单测）。
 *
 * ## 为什么抽出（2026-10-27 v5）
 *
 * HyperOS 2 支付期事件死区（75~155s）使事件驱动管线漏弹（见
 * `docs/qa/device/2026-10-27-remote-user1/ANALYSIS.md` v5）。主动扫描时钟
 * 在死区内周期扫 `rootInActiveWindow`。停止条件是三条防线的交叉：
 * 总时长上限 / 离开支付环境宽限 / 证据产出（产出点在扫描管线内直接停钟，
 * 不经过本对象）。停止判据写宽（该停不停）会让时钟空转耗电；写窄（不该停
 * 而停）会重新漏弹 —— 两个方向都必须由用例钉死，必须可测。
 *
 * 判定与 [CheckoutAccessibilityService.runProactivePaymentScan] 的语义
 * 一一对应：字段状态由服务持有，本对象只做无状态裁决。
 */
internal object ProactiveScanPolicy {

    /** 总时长上限（3 分钟）：密码输入最坏场景 + 成功页停留。 */
    const val MAX_DURATION_MILLIS = 180_000L

    /** 离开支付环境后的宽限窗：确认键→成功页切换瞬间活动根短暂为空。 */
    const val LINGER_MILLIS = 10_000L

    /**
     * 本轮扫描的裁决。
     *
     * @param startedAt 时钟启动时刻
     * @param lastSeenAt 最近一次确认仍在支付环境的时刻
     * @param activeRootPackage 本轮读到的活动根包名（空 = 无活动根）
     * @param now 当前时刻
     * @param supportsPayment 支付包白名单判定
     */
    fun verdict(
        startedAt: Long,
        lastSeenAt: Long,
        activeRootPackage: String,
        now: Long,
        supportsPayment: (String) -> Boolean
    ): Verdict {
        if (now - startedAt > MAX_DURATION_MILLIS) {
            return Verdict.STOP_TIMEOUT
        }
        val inPayment = activeRootPackage.isNotBlank() && supportsPayment(activeRootPackage)
        if (inPayment) {
            return Verdict.SCAN
        }
        if (now - lastSeenAt > LINGER_MILLIS) {
            return Verdict.STOP_LEFT_PAYMENT
        }
        // 窗口切换瞬间（活动根为空/非支付包）尚未过宽限窗：继续等。
        return Verdict.WAIT
    }

    enum class Verdict {
        /** 扫活动根（rootInActiveWindow）。 */
        SCAN,
        /** 活动根空/非支付包但宽限窗未过：本轮跳过，下一轮再看。 */
        WAIT,
        /** 超总时长上限。 */
        STOP_TIMEOUT,
        /** 离开支付环境超宽限窗。 */
        STOP_LEFT_PAYMENT
    }
}
