package com.jotbill.app.pending

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.jotbill.app.utils.JotBillLog

/**
 * 待确认交易的系统通知提醒。
 *
 * 与 RN 完全解耦:在 [com.jotbill.app.MainApplication] 启动时初始化,
 * 进程内任何 pending 创建(无障碍/通知监听/导入)都会触发,不依赖 RN 是否启动。
 *
 * 提醒范围决策:
 * - 只提醒"新建"事件(isNew=true)——内容增强类更新(AI 证据回填)不重复打扰;
 * - 只提醒 amountMinor>0 的条目——0 金额占位(AI 失败重试位)只进待确认中心与 JS 事件;
 * - 隐私:通知文案只含金额/类型/来源,不含商户名与订单号,锁屏可见内容最小化。
 */
internal object PendingReminderNotifier {
    private const val TAG = "PendingReminder"
    private const val CHANNEL_ID = "pending_reminder"
    private const val PREFS_NAME = "pending_reminder"
    private const val KEY_ENABLED = "enabled"

    /** 连续多笔支付时的最小通知间隔,防止通知刷屏;被抑制次数计入日志。 */
    private const val MIN_NOTIFY_INTERVAL_MILLIS = 2_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastNotifiedAt = 0L

    @Volatile
    private var suppressedCount = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "待确认提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "有新的待确认交易时提醒"
            }
            manager.createNotificationChannel(channel)
        }
        PendingChangeHub.addListener(::onPendingChange)
        JotBillLog.i(TAG, "待确认提醒已初始化: channel=$CHANNEL_ID")
    }

    /** 用户开关,默认开启。提醒只影响通知,不影响落库与 JS 事件。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        JotBillLog.i(TAG, "待确认提醒开关已切换: enabled=$enabled")
    }

    private fun onPendingChange(info: PendingChangeInfo) {
        val context = appContext ?: return
        if (info.removed) {
            // 待确认已确认/忽略/自动入账清理：消除状态栏上"有一笔交易待确认"，
            // 否则通知挂到用户手动划掉为止
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(info.pendingId.hashCode())
            JotBillLog.i(TAG, "已消除待确认通知: id=${info.pendingId.take(16)}")
            return
        }
        if (!info.isNew) {
            JotBillLog.i(TAG, "跳过提醒: 内容更新事件 id=${info.pendingId.take(16)}")
            return
        }
        // 用户断言通道产生的待确认不发送系统通知:断言是用户主动点击,
        // 按钮状态变化已构成操作反馈;弹窗/通知的触发权属于支付证据链路
        // (支付结果页无障碍读取/支付通知)。条目仍静默进入待确认中心。
        // 判据走共享函数(与弹窗策略同一出处):断言通道的变体不止一个,
        // 精确相等会漏掉 USER_ASSERTED_AMOUNT_MISSING(AI 未识别到金额)。
        // 该变体当前恒被下方 0 金额闸拦下 ⇒ 本处改动为零行为变化,仅日志
        // 归因更准确;但两侧判据必须同源,否则将来任一侧闸门放开都会让
        // Z2 在单侧静默失效。
        if (
            info.sourceType == PendingSourceTypes.AI_VISION &&
            isUserAssertedConflictReason(info.conflictReason)
        ) {
            JotBillLog.i(TAG, "跳过提醒: 用户断言条目待支付证据 id=${info.pendingId.take(16)}")
            return
        }
        if (info.amountMinor <= 0L) {
            JotBillLog.i(TAG, "跳过提醒: 0 金额占位条目 id=${info.pendingId.take(16)}")
            return
        }
        if (!isEnabled(context)) {
            JotBillLog.i(TAG, "跳过提醒: 用户已关闭开关")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null || !manager.areNotificationsEnabled()) {
            JotBillLog.w(TAG, "跳过提醒: 应用通知权限未授予(API 33+ 需运行时授权)")
            return
        }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastNotifiedAt < MIN_NOTIFY_INTERVAL_MILLIS) {
                suppressedCount++
                JotBillLog.i(
                    TAG,
                    "节流抑制提醒: suppressed=$suppressedCount, id=${info.pendingId.take(16)}"
                )
                return
            }
            lastNotifiedAt = now
            suppressedCount = 0
        }
        try {
            manager.notify(info.pendingId.hashCode(), buildNotification(context, info))
            JotBillLog.i(
                TAG,
                "待确认提醒已发送: id=${info.pendingId.take(16)}, type=${info.type}, " +
                    "amount=${info.amountMinor}, source=${info.sourceType}"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "待确认提醒发送失败", error)
        }
    }

    private fun buildNotification(context: Context, info: PendingChangeInfo): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = "¥${formatYuan(info.amountMinor)} · ${typeLabel(info.type)} · ${sourceLabel(info.sourceType)}"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("有一笔交易待确认")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun formatYuan(amountMinor: Long): String {
        val yuan = amountMinor / 100
        val fen = amountMinor % 100
        return if (fen == 0L) "$yuan" else "$yuan.${fen.toString().padStart(2, '0')}"
    }

    private fun typeLabel(type: String): String = when (type) {
        "expense" -> "支出"
        "income" -> "收入"
        "transfer" -> "转账"
        "repayment" -> "还款"
        "refund" -> "退款"
        "fee" -> "手续费"
        else -> type
    }

    private fun sourceLabel(sourceType: String): String = when (sourceType) {
        "notification" -> "支付通知"
        "accessibility" -> "支付结果页"
        "accessibility_transfer" -> "转账结果页"
        "ai_vision" -> "截图识别"
        "import" -> "导入"
        "manual" -> "手动"
        else -> sourceType
    }
}
