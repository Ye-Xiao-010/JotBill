package com.jotbill.app.recognition

/** 弹窗采集管线的入口判定；引擎管线有自己的包名/通道闸门，不走这里。 */
internal enum class PopupEventDecision {
    Process,
    Drop,
}

/**
 * 在读取根节点之前做一次廉价过滤。
 *
 * 已知购物/支付包直接放行；空包名（WebView/系统窗口）保留给上层用活动窗口兜底；
 * 其他包只有在上一帧仍属于购物/支付窗口时才放行——用于识别第三方悬浮窗覆盖
 * 以及离开购物应用的第一帧，之后同一无关包的事件不再反复读根节点。
 */
internal class AccessibilityEventGate(
    private val relevantPackages: Set<String>,
) {
    @Volatile
    var screenInteractive: Boolean = true

    fun decide(eventPackage: String, lastRelevantPackage: String?): PopupEventDecision {
        if (!screenInteractive) return PopupEventDecision.Drop
        val pkg = eventPackage.trim()
        if (pkg.isEmpty()) return PopupEventDecision.Process
        if (pkg in relevantPackages) return PopupEventDecision.Process
        return if (lastRelevantPackage in relevantPackages) {
            PopupEventDecision.Process
        } else {
            PopupEventDecision.Drop
        }
    }
}
