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

    /** 通道元信息：**渠道的唯一事实源** —— native 开关、RN 设置页列表、
     * 桥接层校验全部从这里派生，新增渠道只改这一处（不足#5 的修复）。 */
    class ChannelInfo(
        val key: String,
        val label: String,
        val description: String,
        val defaultEnabled: Boolean,
        val packages: List<String>
    )

    /**
     * 对齐小米 26 包白名单中与「付款后自动记账」相关的全部 App。内置资产里
     * 京东/美团/拼多多/抖音 6 条规则曾因渠道缺失而是死规则（2026-10-25 差距
     * 复查修正）。淘宝走支付宝收银台（MspContainerActivity），无需单独渠道。
     */
    val CHANNELS: List<ChannelInfo> = listOf(
        ChannelInfo(
            "alipay", "支付宝", "收银台 / 支付结果页 / 账单详情 / 通知",
            defaultEnabled = true,
            packages = listOf("com.eg.android.AlipayGphone")
        ),
        ChannelInfo(
            // ⚠️ 微信通道的现实（2026-10-25 用户裁定）：小米的微信自动记账在真机上
            // 就是不工作的（基类 key 打不中子类页面 + 组合正则脆弱 + 平台侧对抗，
            // 见设计文档 §2.6/§7.4）。我们移植的是同一份带伤资产 —— 引擎大概率
            // 同样打不中；微信的实际保障是既有弹窗/待确认管线（真机验证过）。
            // M4 要让微信走自动入账，需真机 dump 补实际页面类名 + 拆字段正则。
            "wechat", "微信支付", "实验性：微信对抗页面自动化，规则可能打不中；打不中时走待确认弹窗兜底（已真机验证）",
            defaultEnabled = true,
            packages = listOf("com.tencent.mm")
        ),
        ChannelInfo(
            "unionpay", "云闪付", "支付结果页 / 银联收银台",
            defaultEnabled = true,
            packages = listOf("com.unionpay")
        ),
        ChannelInfo(
            "jd", "京东", "支付结果页 / 支付结果弹框",
            defaultEnabled = true,
            packages = listOf("com.jingdong.app.mall")
        ),
        ChannelInfo(
            "meituan", "美团", "支付结果页",
            defaultEnabled = true,
            packages = listOf("com.sankuai.meituan")
        ),
        ChannelInfo(
            "pdd", "拼多多", "多多支付收银台（确认后入账）",
            defaultEnabled = true,
            packages = listOf("com.xunmeng.pinduoduo")
        ),
        ChannelInfo(
            "douyin", "抖音", "商城 / 团购下单页（确认后入账）",
            defaultEnabled = true,
            packages = listOf("com.ss.android.ugc.aweme")
        ),
        ChannelInfo(
            "mms", "银行短信", "扣款 / 入账短信（经系统通知，无需短信权限）",
            defaultEnabled = false,
            packages = listOf("com.android.mms")
        )
    )

    private val PACKAGES: Map<String, ChannelInfo> =
        CHANNELS.flatMap { info -> info.packages.map { it to info } }.toMap()

    private val DEFAULTS: Map<String, Boolean> =
        CHANNELS.associate { it.key to it.defaultEnabled }

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
        return isChannelEnabled(context, channel.key)
    }

    /** 通道键是否合法（桥接层 setChannelEnabled 校验用）。 */
    fun isValidChannelKey(channel: String): Boolean = DEFAULTS.containsKey(channel)
}
