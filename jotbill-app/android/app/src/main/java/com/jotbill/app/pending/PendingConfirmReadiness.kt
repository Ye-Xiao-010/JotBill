package com.jotbill.app.pending

import com.jotbill.app.recognition.MAX_SAFE_MINOR_UNIT

/**
 * 待确认"确认入账"的就绪判据（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * **为什么单独抽出来**：入账金额的下限校验原本只在
 * [PendingConfirmService.confirm] 里（服务端），而弹窗按钮的启用条件在 UI 里
 * （`PendingConfirmOverlayManager.updateConfirmEnabled`）。两处各自判定必然
 * 漂移 —— 出现"按钮亮着但服务端拒绝"或反之。本项目已被"同一条规则散落
 * 多处、改一处漏一处"坑过多次（去重键 2 生成点 / 转账桶 / 同指纹保护
 * 3 实现点 / 断言通道判据 2 实现点），故**金额口径只定义一次**。
 *
 * 分工（两层同向，不是互相替代）：
 * - [isValidAmount]：金额口径的**单一出处**，服务端与 UI 共用；
 * - [isReady]：UI 就绪判据 = 分类 + 账户 + 金额有效（D-21 由"分类 + 账户"
 *   扩展而来）；
 * - 服务端 [PendingConfirmService] 仍是**强制点**：UI 置灰只是提前反馈，
 *   绕过 UI 也写不进 0 金额账单（已有用例 confirmRejectsZeroAmountAsInvalid
 *   钉死）。
 */
internal object PendingConfirmReadiness {

    /**
     * 入账金额是否合法：正的安全整数（分）。
     *
     * null 表示输入框为空或格式非法（[PendingConfirmOverlayManager] 的
     * parseYuan 对空串/非法格式/0 一律返回 null），0 表示金额缺失占位。
     * 三者都不能入账。
     */
    fun isValidAmount(amountMinor: Long?): Boolean =
        amountMinor != null && amountMinor in 1L..MAX_SAFE_MINOR_UNIT

    /**
     * 确认按钮是否就绪。
     *
     * @param hasCategory 用户是否已选分类（弹窗是显式确认入口，不允许无分类写入）
     * @param hasAccount 用户是否已选账户（不造占位账户，未匹配到就要求手选）
     * @param amountMinor 金额框当前解析结果（null = 空/非法）
     */
    fun isReady(hasCategory: Boolean, hasAccount: Boolean, amountMinor: Long?): Boolean =
        hasCategory && hasAccount && isValidAmount(amountMinor)
}
