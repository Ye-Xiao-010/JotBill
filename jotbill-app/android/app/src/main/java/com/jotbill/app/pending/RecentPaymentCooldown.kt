package com.jotbill.app.pending

import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.utils.PaymentTiming

/**
 * 同额支付被处理后的短暂冷却（2026-10-13）。
 *
 * ## 要解决的失效
 *
 * 用户在支付结果页弹窗里确认入账后，**若仍停留在该结果页**，同一笔支付会被
 * 再观测一次并**再弹一次窗**。原因有三道防线同时失效：
 *
 * | 防线 | 位置 | 失效原因 |
 * |---|---|---|
 * | 事件键幂等 `stableId("pending-orphan", sourceEventKey)` | `CheckoutPaymentMatcher` | `sourceEventKey` 含 **时间桶**（`PaymentResultParser` 的 `occurredAt / 15_000`，2026-10-24 前为 30 秒）。用户填个分类再点确认通常已跨桶 → 换键 → 换 pendingId |
 * | 内存事件记忆 `markPaymentResultEvent` | `CheckoutAccessibilityService:986` | 同上，按 `sourceEventKey` 记，跨桶即失效 |
 * | 同包同额去重 `findRecentPendingByPlatformAndAmount` | `CheckoutPaymentMatcher` | 查的是 **pending 表**，而确认入账后该行已被 `deleteByIdIfVersion` **物理删除**（`LedgerRepository`）→ 查不到即视为"未处理过" |
 *
 * 真机实测（2026-10-12，微信 ¥39.06）确认重新观测紧跟在消费之后 147ms：
 * ```
 * 16:32:55.793  付款确认弹窗已关闭
 * 16:32:55.940  支付结果页解析到证据: 金额=3906分   ← 147ms 后又解析了一次
 * ```
 * 那一笔因用户未确认（pending 仍在库里）而被第 3 道防线挡住；**确认入账的行
 * 已被删除，第 3 道防线失效，重复卡与重复弹窗都会发生。**
 *
 * ## 为什么是"冷却"而不是"墓碑查询"
 *
 * 曾考虑查 `LedgerRepository:490` 已有的 `pending_confirm` 审计墓碑，**已否决**：
 * 墓碑只能按 `pendingId` 查，而跨桶后 pendingId 已变，救不了本问题；退而按
 * `(支付包, 金额, 时间窗)` 查，则窗口必须放大到分钟级才能覆盖"用户慢慢挑分类"，
 * 而分钟级窗口会**静默吞掉真实的第二笔同额支付** —— 那比重复弹窗严重得多
 * （误杀不可恢复，误报用户能在待确认中心看见并删除）。
 *
 * 本类用 15 秒窗口（2026-10-24 由 10 秒统一）：物理上不可能在 15 秒内完成第二笔支付
 * （须离开支付 App、回商户应用、重新下单、拉起支付、输密码），所以误杀概率趋近于零。
 *
 * ## 为什么拦在**建卡**而非**弹窗**
 *
 * 弹窗层（`PendingPromptPolicy`）在 `PendingChangeHub` 的下游，而通知器
 * （`PendingReminderNotifier`）是**另一个独立监听器**。只在弹窗层加冷却会得到：
 * 重复的待确认卡 ✅ 照建、系统通知 ✅ 照发、只有弹窗被挡 —— 用户仍会收到第二条
 * 「有一笔交易待确认」并在待确认中心看到重复条目。本类拦在建卡之前，
 * 三者一起消失。
 *
 * ## 生命周期
 *
 * 全部状态在内存，进程死亡即清空 —— 这是**有意**选择：宁可漏放（多一张可删除的
 * 重复卡）不可误杀（静默丢账）。且无障碍服务与本进程同生共死，进程死时观察流
 * 也断了，不存在"锁存残留"的错配。
 */
internal object RecentPaymentCooldown {

    private const val TAG = "RecentPaymentCooldown"

    /**
     * 冷却时长：从该笔被处理（确认入账/忽略）起算，期间同 `(支付包, 金额)` 的
     * 新证据不建卡。
     *
     * **2026-10-24 由 10 秒统一为 15 秒**，与 [PaymentTiming.SAME_TXN_WINDOW_MILLIS]
     * 同值 —— 用户裁定"同额第二笔 15 秒内接受合并"，两套阈值不一致时先失效的那道
     * 形同虚设。重新观测的实测延迟只有 147ms，15 秒远大于它；多抑制的 5 秒内
     * 物理上不可能完成第二笔支付，不会丢账。
     */
    const val COOLDOWN_MILLIS = PaymentTiming.CONSUMED_COOLDOWN_MILLIS

    /** 冷却键 + 该卡的订单号（orderId 护栏用，见 [cooldownRemaining]）。 */
    private data class CardEntry(val key: String, val orderId: String?)

    /** 被处理记录：处理时刻 + 该卡的订单号。 */
    private data class ConsumedEntry(val at: Long, val orderId: String?)

    /**
     * pendingId → 冷却键 `(支付包|金额)` + 订单号。建卡时登记，消费时反查。
     *
     * 必须是 [LinkedHashMap]：超上限时按**插入序**淘汰（见 [rememberCard]）。
     */
    private val cardKeys = LinkedHashMap<String, CardEntry>()

    /** 冷却键 → 被处理记录（时刻 + 订单号）。 */
    private val consumedAt = HashMap<String, ConsumedEntry>()

    private val lock = Any()

    private fun cooldownKey(paymentPackage: String, amountMinor: Long) =
        "$paymentPackage|$amountMinor"

    /**
     * 注册消费监听。必须在建卡方（[com.jotbill.app.MainApplication]）启动时调用，
     * 否则收不到 `removed=true` 事件，冷却永不启动。
     */
    fun init() {
        PendingChangeHub.addListener(::onPendingChange)
    }

    /**
     * 订阅 pending 变更，**只关心 `removed=true`**（确认入账 / 忽略 / 自动入账清理）。
     *
     * 弹窗 30 秒自动关闭（`AUTO_DISMISS`）**不会**产生该事件 —— 那一笔的 pending
     * 仍在库里，由既有的 15 秒同包同额去重负责，与本类职责不重叠。
     */
    private fun onPendingChange(info: PendingChangeInfo) {
        if (!info.removed) return
        val key = markConsumed(info.pendingId, System.currentTimeMillis()) ?: return
        // 此处不回读 consumedAt：它由 [lock] 守护，锁外读是数据竞争；且刚写入即读，
        // 剩余量恒等于 COOLDOWN_MILLIS，回读换不到任何信息。
        JotBillLog.i(
            TAG,
            "支付结果页冷却启动: id=${info.pendingId.take(16)}, key=$key, " +
                "时长=${COOLDOWN_MILLIS / 1000}s"
        )
    }

    /**
     * 登记一条新建的待确认，并把它的 pendingId 与 `(支付包, 金额)` 关联起来。
     *
     * 必须在 `insertIfAbsent` 成功后、且在 `PendingChangeHub.emit` **之前**调用：
     * emit 是同步的，消费者可能在同一线程栈上立刻走到"确认入账"，那时若还没登记
     * 就查不到冷却键。
     *
     * @param orderId 该卡的订单号（可空）—— 供消费后的冷却护栏区分"同额的另一笔"
     *   （见 [cooldownRemaining]），不参与冷却键。
     */
    fun rememberCard(
        pendingId: String,
        paymentPackage: String,
        amountMinor: Long,
        orderId: String?
    ) {
        val key = cooldownKey(paymentPackage, amountMinor)
        synchronized(lock) {
            cardKeys[pendingId] = CardEntry(key, orderId)
            // 换 key 时清理上一条映射，避免 pendingId 与冷却键长期堆积
            val stale = cardKeys.entries.firstOrNull {
                it.key != pendingId && it.value.key == key
            }
            if (stale != null) cardKeys.remove(stale.key)
            // 只裁剪 pendingId 映射（无时间语义）；consumedAt 的时间裁剪放在
            // [markConsumed] 里，那里才有与写入同一时间源的 now 可用 ——
            // 若此处另取真实时钟，会把单测注入的历史时间戳当场判为过期。
            //
            // ⚠️ 必须按**插入序**（FIFO）淘汰，不可按 pendingId 排序：生产 pendingId 是
            // `stableId()` 的 SHA-256 摘要（`CheckoutPaymentMatcher.stableId`），
            // 字典序与登记时间无关 ⇒ 按字典序裁是随机淘汰，且有约 1/(N+1) 的概率
            // **当场裁掉刚插入的这一条** ⇒ 该笔 [markConsumed] 返回 null ⇒ 冷却静默
            // 不启动 ⇒ 本类要修的"记账后再弹一次窗"原样复现。
            // 本表只在被消费时才缩小，未处理的孤儿卡会堆积，故上限确实会被触到。
            if (cardKeys.size > MAX_TRACKED_CARDS) {
                val iterator = cardKeys.keys.iterator()
                repeat(cardKeys.size - MAX_TRACKED_CARDS) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 标记该笔已被处理，返回其冷却键；未登记过则返回 null。
     *
     * @param now 时间源，测试注入用。**时间裁剪必须用这个 now**，不可另取真实时钟 ——
     *   否则注入的历史时间戳会被判为过期，在同一次调用里自毁。
     */
    fun markConsumed(pendingId: String, now: Long): String? = synchronized(lock) {
        val entry = cardKeys.remove(pendingId) ?: return null
        consumedAt[entry.key] = ConsumedEntry(at = now, orderId = entry.orderId)
        consumedAt.entries.removeAll { now - it.value.at > COOLDOWN_MILLIS * 2 }
        entry.key
    }

    /**
     * 该 `(支付包, 金额)` 是否处于冷却中。
     *
     * **orderId 护栏（拍板 A，2026-11-04）**：冷却记录的订单号与新信号订单号
     * **都非空且不同** ⇒ 不抑制 —— 单号已客观证明是两笔，不得以冷却抑制吞并
     * 第二笔真单（放行后由 `CheckoutPaymentMatcher` 的 orderId 冲突护栏建第二
     * 张卡）。任一侧为空或两者相等时，维持原 15s 抑制语义一字不变。
     *
     * @param orderId 新信号的订单号（可空）。
     * @return 剩余冷却毫秒；不在冷却（或命中 orderId 护栏）返回 null。
     */
    fun cooldownRemaining(
        paymentPackage: String,
        amountMinor: Long,
        orderId: String?,
        now: Long
    ): Long? =
        synchronized(lock) {
            val key = cooldownKey(paymentPackage, amountMinor)
            val entry = consumedAt[key] ?: return null
            if (
                !entry.orderId.isNullOrBlank() &&
                !orderId.isNullOrBlank() &&
                entry.orderId != orderId
            ) {
                return null
            }
            val elapsed = now - entry.at

            // 边界口径（2026-10-24 场景审查推演后保留 >=）：恰在 15.000s 时即使
            // 冷却放行，下游去重链（哈希 / D-25 均为闭区间）也会判同一笔合并，
            // 与"冷却仍在生效"的卡结果等价 —— 这 1ms 差异不可观测，不值得为
            // 口径整齐改语义（allowsSameAmountPaymentAfterCooldownExpires 钉死此行为）。
            if (elapsed >= COOLDOWN_MILLIS) {
                consumedAt.remove(key)
                null
            } else {
                COOLDOWN_MILLIS - elapsed
            }
        }

    private const val MAX_TRACKED_CARDS = 64

    /** 仅供单测复位。 */
    fun reset() = synchronized(lock) {
        cardKeys.clear()
        consumedAt.clear()
    }

    /**
     * 仅供单测：以固定时间戳驱动监听器，不经过真实时钟。
     *
     * 生产路径是 [onPendingChange]（内部取 `System.currentTimeMillis()`），
     * 不可注入；本包装让"removed 与非 removed 两条分支"都能被确定性验证。
     */
    fun onPendingChangeForTest(info: PendingChangeInfo, now: Long = System.currentTimeMillis()) {
        if (!info.removed) return
        markConsumed(info.pendingId, now)
    }
}