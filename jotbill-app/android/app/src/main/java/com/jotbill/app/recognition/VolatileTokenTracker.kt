package com.jotbill.app.recognition

import java.util.ArrayDeque

/**
 * 跨帧易变 token 追踪器（Z1-L3，2026-09-10）。
 *
 * 问题：轮播/翻页文案以普通 TextView 定时换字（真机取证：拼多多商品规格层
 * 8 秒内 9 条文本变化，父链全为普通容器），结构排除法（ViewSwitcher 家族）
 * 与内容归一化（时钟/倒计时词表）都覆盖不到，token 原样进入指纹导致
 * 指纹持续漂移——每个新指纹被下游判为新订单边界，反复触发自动截图。
 *
 * 机制：采集器本来就在每个无障碍事件上重建页面摘要（每秒多次），同一
 * scope（包名+窗口类名）内同一节点位置出现过的**不同文本**就是"该位置
 * 在轮播"的证明——这是任何 App、任何改版都伪造不了的时间维度特征。
 * 观察窗口内同一位置出现 ≥2 个不同文本，该位置的文本即被判定为"易变"，
 * 后续采集只把信号语义保留（金额/动作词表判定不受影响），不再进入指纹。
 *
 * 换商品重截护栏：命中 finalAmountTerms 语义（结算页应付金额等静态文本）
 * 的 token 永不进入易变池——结算页金额不轮播，轮播的是商品页的到手价/
 * 折扣，因此排除轮播不会让"换商品"逃过指纹变化。
 *
 * 该类为纯 Kotlin 无 Android 依赖，状态按 scope 隔离、带 TTL 与容量上限，
 * 可在 JVM 单测中完整钉死行为。
 *
 * @param windowMillis 观察窗口时长。
 * @param nowMillis 时间源，测试注入用。
 * @param maxTrackedPositions 每个.scope 内追踪的位置上限（防异常巨大节点树撑爆内存）。
 */
internal class VolatileTokenTracker(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maxTrackedPositions: Int = DEFAULT_MAX_TRACKED_POSITIONS
) {
    private data class Observation(
        val text: String,
        val observedAt: Long
    )

    private class ScopeState {
        /** 位置键 → 窗口内的观察记录（按时间升序）。 */
        val positions = LinkedHashMap<String, ArrayDeque<Observation>>()
    }

    private val scopes = HashMap<String, ScopeState>()

    /**
     * 记录一次"某位置此刻的文本"，并返回该位置是否已判定为易变。
     *
     * 调用方（采集器）对每个进入指纹的文本 token 调用本方法：先查该位置
     * 是否已易变（已易变的位置调用方应跳过写入指纹），再把本次文本记入
     * 观察窗口。记录与判定在同一次调用中完成，调用方无需两步。
     *
     * @param scopeKey 作用域键（包名+窗口类名），跨页面隔离观察。
     * @param positionKey 位置键（节点语义标记：类名/资源id）。
     * @param text 该位置本次的文本（已归一化）。
     * @return true 表示该位置在观察窗口内出现过 ≥2 个不同文本（易变）。
     */
    @Synchronized
    fun observeAndCheck(scopeKey: String, positionKey: String, text: String): Boolean {
        if (positionKey.isBlank()) {
            return false
        }
        val state = scopes.getOrPut(scopeKey) { ScopeState() }
        val queue = state.positions.getOrPut(positionKey) {
            if (state.positions.size >= maxTrackedPositions) {
                // 容量已满：新位置不追踪（返回非易变），避免内存被异常页面撑爆。
                return false
            }
            ArrayDeque()
        }

        val now = nowMillis()
        // 先淘汰窗口外记录，再判定：过期观察不得参与易变判定（真机语义：
        // 15 秒前的文本不能证明"该位置正在轮播"）。此前先判定后淘汰，
        // 窗口刚过期时的首个新文本会命中陈旧记录而误判易变。
        while (queue.isNotEmpty() && now - queue.first().observedAt > windowMillis) {
            queue.removeFirst()
        }
        var volatile = false
        for (observation in queue) {
            if (observation.text != text) {
                volatile = true
                break
            }
        }
        queue.addLast(Observation(text, now))
        return volatile
    }

    /** 服务中断/销毁时清空全部观察状态。 */
    @Synchronized
    fun clear() {
        scopes.clear()
    }

    private companion object {
        /** 观察窗口：覆盖"数秒一次"的轮播节奏，又不过长导致换页误伤。 */
        const val DEFAULT_WINDOW_MILLIS = 15_000L

        /** 每作用域位置上限：防御异常巨大节点树。 */
        const val DEFAULT_MAX_TRACKED_POSITIONS = 400
    }
}
