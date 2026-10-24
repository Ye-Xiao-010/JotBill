package com.jotbill.app.pending

import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.utils.PaymentTiming

/**
 * 支付结果页"同一笔"的重复观测识别（2026-10-24）。
 *
 * ## 要解决的失效
 *
 * 孤儿待确认的跨桶去重（`CheckoutPaymentMatcher.ORPHAN_DEDUP_WINDOW_MILLIS`）
 * 原先靠**时间窗**判"是不是同一笔的重复观测"。时间窗无法区分两件事：
 *
 * | 场景 | 页面文本 | 真实语义 |
 * |---|---|---|
 * | 用户确认后仍停在成功页 / 填分类填了 60 秒 | **完全一致** | 同一笔 |
 * | 用户又下了一单、金额恰好相同 | 时间/订单号至少一处不同 | 两笔 |
 *
 * 纯时间窗只能二选一：窗口宽 ⇒ 吞掉真实的第二笔（误杀，不可恢复）；
 * 窗口窄 ⇒ 同一笔停留超过窗口就多建一张卡（误报，可删）。
 *
 * ## 判据：页面文本哈希 + 15 秒上限
 *
 * `PaymentResultParser` 已为每条证据计算 `rawTextHash`：脱敏文本 + **订单号摘要**
 * （`sha256("result|包名|sha256(订单号)|脱敏文本")`）。同一笔的重复观测文本一致
 * ⇒ 哈希一致；真实第二笔的时间/订单号至少一处不同 ⇒ 哈希不同。
 *
 * ⚠️ **哈希必须配 15 秒上限**（用户 2026-10-24 裁定）：页面不显示时间时，
 * "同一笔停留 60 秒"与"同额同文本的第二笔"无法区分。裁定选择"超过 15 秒即视为
 * 新交易"——宁可多一张可删的卡，也不静默吞掉真实的第二笔。
 * 页面显示时间时不依赖本判据（`occurredAt` 恒定，D-25 窗口天然命中）。
 *
 * 时间窗仍然保留，作为**哈希缺失或漂移时的兜底**（见 `ORPHAN_DEDUP_WINDOW_MILLIS`）。
 *
 * ## 为什么是内存而不是数据库列
 *
 * 加 `pending_transactions.raw_text_hash` 需要 Room 迁移（库版本 +1），
 * 而验收期动 schema 的风险高于收益。重复观测必然发生在同一进程内
 * （无障碍服务与本进程同生共死），内存表足够；进程死亡后表清空，
 * 方向是"多一张可删的卡"，不是丢账。
 *
 * ## 生命周期（与 [RecentPaymentCooldown] 的分工）
 *
 * | 类 | 覆盖时段 | 触发 |
 * |---|---|---|
 * | [RecentPaymentCooldown] | **消费后** 15 秒 | `removed=true` 起算 |
 * | 本类 | **消费前**的重复观测 | 建卡时登记，`removed=true` 时清除 |
 *
 * 两者互补：消费后由冷却挡住，消费前由哈希挡住；一旦该笔被确认/忽略，
 * 本类立即清除，不会吞掉之后同额同文本的真实新支付。
 *
 * 保留期与匹配窗同为 15 秒：超窗即视为新交易（用户 2026-10-24 裁定），
 * 登记也没有继续保留的价值。正常路径仍由 `removed` 精确清除。
 */
internal object RecentResultObservation {

    private const val TAG = "RecentResultObservation"

    /**
     * 保留期 = 匹配窗：超过 [PaymentTiming.SAME_TXN_WINDOW_MILLIS] 的登记既不再参与
     * 判定，也没有保留价值（裁定：15 秒外即视为新交易）。
     */
    private const val RETENTION_MILLIS = PaymentTiming.SAME_TXN_WINDOW_MILLIS

    /**
     * 上限。待确认中心没有过期清理机制（`FIX_TASK_2026-09-29` 有记录），
     * 用户可能长期不处理，必须有界。
     */
    private const val MAX_TRACKED = 64

    private data class Entry(
        val pendingId: String,
        val paymentPackage: String,
        val amountMinor: Long,
        val rawTextHash: String,
        val seenAt: Long
    )

    /** pendingId → 登记项。**必须按插入序淘汰**（理由同 [RecentPaymentCooldown]）。 */
    private val entries = LinkedHashMap<String, Entry>()

    private val lock = Any()

    /**
     * 注册消费监听。必须在建卡方（[com.jotbill.app.MainApplication]）启动时调用，
     * 否则 `removed=true` 收不到，已确认的条目会一直留在表里吞掉后续同额支付。
     */
    fun init() {
        PendingChangeHub.addListener(::onPendingChange)
    }

    private fun onPendingChange(info: PendingChangeInfo) {
        if (!info.removed) return
        forget(info.pendingId)
    }

    /**
     * 建卡成功后登记；必须在 `PendingChangeHub.emit` **之前**调用 ——
     * emit 是同步的，监听方可能在同一栈上立刻确认入账并触发 `removed`。
     *
     * @param rawTextHash 证据的脱敏文本哈希；为空表示来源无法提供（通知回退），
     *   此时不登记，该笔退回纯时间窗判据。
     */
    fun remember(
        pendingId: String,
        paymentPackage: String,
        amountMinor: Long,
        rawTextHash: String?,
        now: Long
    ) {
        val hash = rawTextHash?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            pruneLocked(now)
            entries[pendingId] = Entry(
                pendingId = pendingId,
                paymentPackage = paymentPackage,
                amountMinor = amountMinor,
                rawTextHash = hash,
                seenAt = now
            )
            if (entries.size > MAX_TRACKED) {
                val iterator = entries.keys.iterator()
                repeat(entries.size - MAX_TRACKED) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * 查找"同支付包 + 同金额 + 同文本哈希"且在 15 秒窗口内的未处理登记。
     *
     * 时间条件显式写出（而非只依赖 [pruneLocked]）：两者条件必须一致，
     * 否则将来有人放宽清理逻辑，匹配窗会跟着悄悄变宽。
     *
     * @return 命中的 `pendingId`；未命中、哈希为空或超出窗口时返回 null。
     */
    fun findSameObservation(
        paymentPackage: String,
        amountMinor: Long,
        rawTextHash: String?,
        now: Long
    ): String? {
        val hash = rawTextHash?.takeIf { it.isNotBlank() } ?: return null
        synchronized(lock) {
            pruneLocked(now)
            return entries.values.firstOrNull {
                it.paymentPackage == paymentPackage &&
                    it.amountMinor == amountMinor &&
                    it.rawTextHash == hash &&
                    now - it.seenAt <= PaymentTiming.SAME_TXN_WINDOW_MILLIS
            }?.pendingId
        }
    }

    /** 该笔已终态（确认/忽略/自动入账清理）⇒ 立即解除登记。 */
    fun forget(pendingId: String) {
        synchronized(lock) {
            val removed = entries.remove(pendingId)
            if (removed != null) {
                JotBillLog.i(TAG, "同一笔登记已解除: id=${pendingId.take(16)}")
            }
        }
    }

    /** 仅供单测复位。 */
    fun reset() = synchronized(lock) {
        entries.clear()
    }

    private fun pruneLocked(now: Long) {
        entries.entries.removeAll { now - it.value.seenAt > RETENTION_MILLIS }
    }
}
