package com.jotbill.app.bookkeeping

import android.content.Context
import com.jotbill.app.utils.JotBillLog

/**
 * 自动记账通道开关体系（对齐小米 `ce.o0` book_keeping SP 命名空间）。
 *
 * 两层：总开关（合规红线 §7.3 的「暂停自动记账」，默认开启）+ 按支付渠道
 * 白名单（对齐设计方案 §2.1：白名单是第一道闸门）。
 *
 * 渠道默认值：支付宝 / 微信 / 云闪付开启（拍板项③），短信通知通道 M6 再放开。
 * mipay 的 com.android.systemui 排除在引擎侧做，不进通道表。
 */
object BookkeepingChannels {
    private const val TAG = "BookkeepingChannels"

    private const val PREFS_NAME = "auto_bookkeeping"

    private const val KEY_MASTER_ENABLED = "master_enabled"

    private const val KEY_CHANNEL_PREFIX = "channel_"

    /** 引擎需要监听的渠道包名 → 通道键。新增渠道只改这里 + 默认值。 */
    private val PACKAGES = mapOf(
        "com.eg.android.AlipayGphone" to "alipay",
        "com.tencent.mm" to "wechat",
        "com.unionpay" to "unionpay",
        "com.android.mms" to "mms"
    )

    /** 通道默认开关；false 的通道规则不删（M6 翻开关即启用）。 */
    private val DEFAULTS = mapOf(
        "alipay" to true,
        "wechat" to true,
        "unionpay" to true,
        "mms" to false
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ENABLED, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
        JotBillLog.i(TAG, "自动记账总开关切换: enabled=$enabled")
    }

    fun isChannelEnabled(context: Context, channel: String): Boolean =
        prefs(context).getBoolean(KEY_CHANNEL_PREFIX + channel, DEFAULTS[channel] ?: false)

    fun setChannelEnabled(context: Context, channel: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHANNEL_PREFIX + channel, enabled).apply()
        JotBillLog.i(TAG, "自动记账通道切换: channel=$channel, enabled=$enabled")
    }

    /** 引擎事件闸门：包不在白名单或通道关闭 → 不处理。 */
    fun shouldProcess(context: Context, packageName: String): Boolean {
        if (!isMasterEnabled(context)) return false
        val channel = PACKAGES[packageName] ?: return false
        return isChannelEnabled(context, channel)
    }
}
