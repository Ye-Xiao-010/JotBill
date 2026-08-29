package com.jotbill.app.pending

import android.util.Log

/**
 * 待确认交易的变更事件(进程内)。
 *
 * @param pendingId 待确认行 ID(幂等键)。
 * @param type 交易类型:expense/income/transfer/repayment/refund/fee。
 * @param amountMinor 金额(分);0 表示占位条目(如 AI 识别失败重试位)。
 * @param sourceType 来源:notification/accessibility/ai_vision/import/manual。
 * @param conflictReason 冲突/未关联原因;无冲突的更新事件为 null。
 * @param occurredAt 交易时间(毫秒)。
 * @param isNew true=新建(首次落库),false=已有条目内容更新(如 AI 证据增强)。
 */
data class PendingChangeInfo(
    val pendingId: String,
    val type: String,
    val amountMinor: Long,
    val sourceType: String,
    val conflictReason: String?,
    val occurredAt: Long,
    val isNew: Boolean
)

/**
 * 待确认交易创建/更新的进程内分发中心。
 *
 * 创建点(recognition 各分类器、matcher、importing)只依赖本单例,不持有
 * React 上下文;消费方各自注册:系统通知提醒([PendingReminderNotifier],
 * Application 启动时注册,不依赖 RN 是否启动)与 JS 推送(DataModule,RN 桥接层)。
 *
 * 事件丢弃策略:无消费者或消费者抛异常时,事件不影响落库结果——数据本身
 * 已持久化,提醒是尽力而为的旁路。
 */
internal object PendingChangeHub {
    private const val TAG = "PendingChangeHub"

    @Volatile
    private var listeners: List<(PendingChangeInfo) -> Unit> = emptyList()

    @Synchronized
    fun addListener(listener: (PendingChangeInfo) -> Unit) {
        listeners = listeners + listener
    }

    @Synchronized
    fun removeListener(listener: (PendingChangeInfo) -> Unit) {
        listeners = listeners.filterNot { it === listener }
    }

    fun emit(info: PendingChangeInfo) {
        Log.i(
            TAG,
            "pending变更事件: id=${info.pendingId.take(16)}, type=${info.type}, " +
                "amount=${info.amountMinor}, source=${info.sourceType}, " +
                "reason=${info.conflictReason ?: "null"}, isNew=${info.isNew}"
        )
        val current = listeners
        if (current.isEmpty()) {
            Log.i(TAG, "无消费者,事件丢弃(数据已持久化,提醒尽力而为)")
            return
        }
        current.forEach { listener ->
            try {
                listener(info)
            } catch (error: RuntimeException) {
                Log.w(TAG, "pending事件消费者异常(不影响其余消费者)", error)
            }
        }
    }
}
