package com.jotbill.app.recognition

/**
 * 内容类事件的单槽合并器。
 *
 * 第一次事件返回 true，由调用方安排一次延迟采集；窗口内的后续事件只替换待处理
 * 快照，不重复安排任务。延迟任务执行后调用 [onDispatched] 重新放行下一批。
 */
internal class ContentEventCoalescer {
    private var dispatchPending = false

    fun onContentEvent(): Boolean {
        if (dispatchPending) return false
        dispatchPending = true
        return true
    }

    fun onDispatched() {
        dispatchPending = false
    }

    fun reset() {
        dispatchPending = false
    }
}
