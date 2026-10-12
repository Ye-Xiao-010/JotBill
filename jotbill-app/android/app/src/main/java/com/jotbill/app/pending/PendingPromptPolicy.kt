package com.jotbill.app.pending

/**
 * 付款确认弹窗的触发策略（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 决定一个待确认创建事件是否值得弹窗。全部拒绝都返回原因便于日志归因：
 * - 仅支出型：退款/转账等复杂类型在待确认中心处理；
 * - 0 金额占位（AI 失败/金额缺失）**允许弹窗**（D-21）：用户不必再为了补金额
 *   专门进 App 待确认中心；代价是确认前强制补金额 —— UI 侧确认按钮在
 *   金额为空/为 0 时置灰（[PendingConfirmReadiness]），服务端侧
 *   [PendingConfirmService] 校验金额必须为正，两层同向、同一出处。
 *
 * ⚠️ **2026-10-12 用户裁定：去重与频控两条规则已废弃。**
 * 原先的 `DEDUP_WINDOW_MILLIS`（同商户同金额 5 分钟只弹一次）与
 * `HOURLY_PROMPT_LIMIT`（1 小时 10 次上限）会直接违反本版本的产品目标 ——
 * "只要出现微信/支付宝支付成功页就弹窗"。两笔同额连付是**两笔真实交易**，
 * 本就该弹两次；高频支付用户被限制弹窗次数则是漏记的来源。
 *
 * 副作用（本是有意为之，不是回归）：
 * - 同一笔支付若被重复观测成两条 pending（跨 [CheckoutPaymentMatcher] 的
 *   `ORPHAN_DEDUP_WINDOW_MILLIS = 90s` 窗口），现在会弹两次而非一次。
 *   落库去重与弹窗去重是两套机制，废弃的是后者。
 * - 高频支付用户一天可能被打扰数十次。缓解手段是弹窗 30s 自动关闭
 *   （见 `PendingConfirmOverlayManager.AUTO_DISMISS_DELAY`），
 *   自动关闭时发系统通知提示已转入待确认中心，不静默吞掉。
 *
 * 副作用之二：[evaluate] 由此变成**纯函数**，不再接收任何历史状态。
 * 这顺带消灭了 2026-10-12 修掉的 ConcurrentModificationException 崩进程的
 * 根因（并发遍历 `recentDedupKeys` / `recentPromptTimes`）——
 * 那两个集合连同 [PendingConfirmOverlayManager.snapshotPolicyState] 已整体删除。
 *
 * 勿扰时段后置：个人记账场景支付即事件，暂无静音需求。
 */
internal object PendingPromptPolicy {

    /**
     * 弹窗候选等待非敏感帧的最长时间；超时放弃（系统通知兜底仍在）。
     *
     * ⚠️ 2026-10-12 由 15s 调整为 30s，与
     * [PendingConfirmOverlayManager.AUTO_DISMISS_DELAY] 对齐 ——
     * 用户注意力窗口定为 30s，投递就不该在 15s 时放弃。
     * 真正的动因是 MIUI 会在应用进后台后短暂收回 `SYSTEM_ALERT_WINDOW`
     * （真机实测 duration=3s / 57s），15s TTL 短于 57s 时那笔支付**永久无弹窗**。
     */
    const val CANDIDATE_TTL_MILLIS = 30_000L

    enum class SkipReason(val wireValue: String) {
        DISABLED("DISABLED"),
        NOT_EXPENSE("NOT_EXPENSE"),
        REMOVED("REMOVED"),
        UPDATED_NOT_NEW("UPDATED_NOT_NEW"),
        /**
         * 用户断言通道产生的待确认(无支付证据,仅用户点击悬浮按钮)。
         *
         * 设计原则:AI 识别只产信息,支付结果页(无障碍读取)或支付通知才是
         * 入账/弹窗的合法触发器。断言条目静默进待确认中心(保落袋:真付钱
         * 且证据全挂的用户仍能在中心找到记录),但不得弹窗打扰,也不得被
         * 顺手确认入账(商品页误触会把商品价格记成支出)。
         */
        USER_ASSERTED("USER_ASSERTED")
    }

    sealed class Decision {
        /**
         * 允许弹窗。
         *
         * @param dedupKey "商户|金额"标识。**已无任何判定用途**（去重规则已废弃，
         *   2026-10-12），保留是因为它会进日志，是回溯"这次弹窗对应哪一笔"的线索。
         *   物理删除前请确认 [PendingConfirmOverlayManager] 的日志不再引用它。
         */
        data class Prompt(val dedupKey: String) : Decision()

        data class Skip(val reason: SkipReason) : Decision()
    }

    /**
     * @param info 待确认变更事件。
     * @param merchant 待确认行上的商户名（事件负载不含商户，由调用方补充）。
     * @param enabled 设置开关（关闭 = 仅系统通知）。
     *
     * **纯函数** —— 2026-10-12 起不再依赖任何历史状态入参。
     */
    fun evaluate(
        info: PendingChangeInfo,
        merchant: String?,
        enabled: Boolean
    ): Decision {
        if (info.removed) return Decision.Skip(SkipReason.REMOVED)
        if (!info.isNew) return Decision.Skip(SkipReason.UPDATED_NOT_NEW)
        if (!enabled) return Decision.Skip(SkipReason.DISABLED)
        // 用户断言通道(点击悬浮按钮直出的待确认)不弹窗:见 SkipReason.USER_ASSERTED
        // 注释。位置在 REMOVED/isNew/DISABLED 之后,保证"断言条目被删除/开关关闭"等
        // 既有语义优先级不变,其余类型判定不受影响。
        // 判据必须走共享函数:断言通道的变体不止一个(AI 未识别到金额时落
        // USER_ASSERTED_AMOUNT_MISSING),精确相等会漏掉该变体 ⇒ 断言条目弹窗,
        // 违反 Z2。见 PendingChangeHub.isUserAssertedConflictReason。
        if (
            info.sourceType == PendingSourceTypes.AI_VISION &&
            isUserAssertedConflictReason(info.conflictReason)
        ) {
            return Decision.Skip(SkipReason.USER_ASSERTED)
        }
        if (info.type != "expense") return Decision.Skip(SkipReason.NOT_EXPENSE)
        return Decision.Prompt("${merchant?.trim().orEmpty()}|${info.amountMinor}")
    }
}
