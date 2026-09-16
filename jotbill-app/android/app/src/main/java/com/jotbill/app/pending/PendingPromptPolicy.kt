package com.jotbill.app.pending

/**
 * 付款确认弹窗的触发策略（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 决定一个待确认创建事件是否值得弹窗。门禁按"先过滤明显不该弹的、
 * 再做去重与频控"排序，全部拒绝都返回原因便于日志归因：
 * - 仅支出型：退款/转账等复杂类型在待确认中心处理；
 * - 0 金额占位（AI 失败/金额缺失）**允许弹窗**（D-21）：用户不必再为了补金额
 *   专门进 App 待确认中心；代价是确认前强制补金额 —— UI 侧确认按钮在
 *   金额为空/为 0 时置灰（[PendingConfirmReadiness]），服务端侧
 *   [PendingConfirmService] 校验金额必须为正，两层同向、同一出处；
 * - 去重：同商户+同金额在窗口内只弹一次（连续扫码两笔同价商品时，
 *   第二笔仍会进待确认中心，只是不重复打扰）；
 * - 频控：滑动 1 小时窗口内的弹窗次数上限。
 *
 * 勿扰时段后置：个人记账场景支付即事件，暂无静音需求。
 */
internal object PendingPromptPolicy {

    /** 同商户+同金额的去重窗口。 */
    const val DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L

    /** 滑动 1 小时窗口内允许的最大弹窗次数。 */
    const val HOURLY_PROMPT_LIMIT = 10

    /** 弹窗候选等待非敏感帧的最长时间；超时放弃（系统通知兜底仍在）。 */
    const val CANDIDATE_TTL_MILLIS = 15_000L

    enum class SkipReason(val wireValue: String) {
        DISABLED("DISABLED"),
        NOT_EXPENSE("NOT_EXPENSE"),
        REMOVED("REMOVED"),
        UPDATED_NOT_NEW("UPDATED_NOT_NEW"),
        DEDUP_WINDOW("DEDUP_WINDOW"),
        HOURLY_LIMIT("HOURLY_LIMIT"),
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
        /** 允许弹窗；dedupKey 供调用方记录最近弹窗时间。 */
        data class Prompt(val dedupKey: String) : Decision()

        data class Skip(val reason: SkipReason) : Decision()
    }

    /**
     * @param info 待确认变更事件。
     * @param merchant 待确认行上的商户名（事件负载不含商户，由调用方补充）；
     *   null/blank 参与去重时按空串处理，同类同额无商户付款仍会被去重。
     * @param enabled 设置开关（关闭 = 仅系统通知）。
     * @param recentDedupKeys 去重键 → 最近一次弹窗时间。
     * @param recentPromptTimes 滑动窗口内的历史弹窗时间戳。
     */
    fun evaluate(
        info: PendingChangeInfo,
        merchant: String?,
        enabled: Boolean,
        now: Long,
        recentDedupKeys: Map<String, Long>,
        recentPromptTimes: List<Long>
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

        val dedupKey = "${merchant?.trim().orEmpty()}|${info.amountMinor}"
        recentDedupKeys[dedupKey]?.let { lastAt ->
            if (now - lastAt < DEDUP_WINDOW_MILLIS) {
                return Decision.Skip(SkipReason.DEDUP_WINDOW)
            }
        }
        val windowPrompts = recentPromptTimes.count { now - it < 60 * 60 * 1000L }
        if (windowPrompts >= HOURLY_PROMPT_LIMIT) {
            return Decision.Skip(SkipReason.HOURLY_LIMIT)
        }
        return Decision.Prompt(dedupKey)
    }
}
