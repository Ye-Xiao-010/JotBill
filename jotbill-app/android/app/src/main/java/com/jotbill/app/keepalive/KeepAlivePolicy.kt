package com.jotbill.app.keepalive

internal object KeepAlivePolicy {
    fun shouldRun(masterEnabled: Boolean, keepAliveEnabled: Boolean): Boolean =
        masterEnabled && keepAliveEnabled
}
