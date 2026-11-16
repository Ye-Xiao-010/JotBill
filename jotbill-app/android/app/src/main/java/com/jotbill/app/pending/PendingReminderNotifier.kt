package com.jotbill.app.pending

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jotbill.app.rules.DuplicateGuard
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

    /**
     * 新建待确认提醒的投递宽限（2026-11-16 真机双通知修复）。
     *
     * 证据卡（支付宝结果页/通知）常比银行短信早到 2~5 秒：卡刚建就发提醒，
     * 短信入账后卡被吸收，用户会同时看到「有一笔交易待确认」和「已自动记一笔」
     * 两条同额通知。宽限期内卡被吸收（removed）则不再发提醒。
     */
    private const val NEW_REMINDER_GRACE_MILLIS = 8_000L

    private val graceHandler = Handler(Looper.getMainLooper())

    /** 宽限期内待投递的提醒（pendingId → Runnable）；removed 时取消。 */
    private val scheduledPosts = mutableMapOf<String, Runnable>()

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
            cancelScheduledReminder(info.pendingId)
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
        // 宽限期投递（2026-11-16）：给银行短信等另一通道先入账的机会；
        // 宽限期内卡被吸收（removed）则彻底不发，避免同额双通知。
        scheduleReminder(context, info)
    }

    private fun scheduleReminder(context: Context, info: PendingChangeInfo) {
        // 退款/导入不需要宽限期：
        // - 退款卡不会被「自动入账通知」吸收（退款链路命中同额时直接返回 Duplicate，
        //   既有卡与其提醒保持），8 秒延后只会让用户觉得识别慢；
        // - 导入批次没有竞争通道，保持即时。
        if (info.type == "refund" || info.sourceType == DuplicateGuard.IMPORT_SOURCE_TYPE) {
            postReminder(context, info)
            return
        }
        val runnable = Runnable {
            synchronized(scheduledPosts) { scheduledPosts.remove(info.pendingId) }
            postReminder(context, info)
        }
        synchronized(scheduledPosts) {
            scheduledPosts.remove(info.pendingId)?.let(graceHandler::removeCallbacks)
            scheduledPosts[info.pendingId] = runnable
        }
        graceHandler.postDelayed(runnable, NEW_REMINDER_GRACE_MILLIS)
    }

    /** 卡片在宽限期内被吸收/忽略时取消待投递的提醒。 */
    private fun cancelScheduledReminder(pendingId: String) {
        val runnable = synchronized(scheduledPosts) { scheduledPosts.remove(pendingId) }
        runnable?.let(graceHandler::removeCallbacks)
    }

    private fun postReminder(context: Context, info: PendingChangeInfo) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null || !manager.areNotificationsEnabled()) {
            JotBillLog.w(TAG, "跳过提醒: 应用通知权限未授予(API 33+ 需运行时授权)")
            return
        }
        val now = System.currentTimeMillis()
        // 批量导入的 emit 在同一执行循环内连发（本地库写入远快于 2s），若参与
        // 节流，一次导入 N 条待确认只有第 1 条有系统通知、其余只留日志 ——
        // 用户对导入产生的待确认量级失去感知。导入来源豁免节流
        // （2026-10-24 场景审查）；量级兜底在 ImportScreen 结果页计数与待确认中心。
        // 退款同样豁免（2026-11-16）：多条退款同时到达（如监听补扫）时，
        // 节流会让后一条只有卡片、没有提醒；退款低频，每条都值得单独提醒。
        if (info.sourceType != DuplicateGuard.IMPORT_SOURCE_TYPE && info.type != "refund") {
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

    /**
     * 付款确认弹窗因超时自动关闭后的补充提醒（2026-10-12）。
     *
     * **为什么需要它**：创建 pending 时已经发过一次"有一笔交易待确认"
     * （见 [onPendingChange]），但弹窗会在用户未操作时于 30s 后静默关闭 ——
     * 用户很可能把"弹窗没了"误读成"已经记好了"。本条把这件事显式说出来。
     *
     * 与首条通知**共用同一个通知 id**（`pendingId.hashCode()`）：是**原地更新**
     * 而非新增一条，避免通知栏出现两条内容相近、只有措辞不同的通知。
     * 共用 id 也与 [onPendingChange] 的 `removed` 分支的 `cancel(同 id)` 保持自洽 ——
     * 用户随后在待确认中心处理掉这笔时，更新后的通知同样会被消除。
     *
     * 不受 [MIN_NOTIFY_INTERVAL_MILLIS] 节流：连续多笔支付时每笔都会弹窗，
     * 节流是为"创建时提醒"那种密集场景设计的；自动关闭是低频事件，
     * 且节流会把"用户没注意到弹窗关了"这个关键信息再吞一次。
     */
    fun notifyAutoDismissed(
        context: Context,
        pendingId: String,
        amountMinor: Long,
        type: String,
        sourceType: String
    ) {
        if (amountMinor <= 0L) {
            // 0 金额占位本就不发首条通知（见 [onPendingChange]），此处保持一致。
            JotBillLog.i(TAG, "跳过自动关闭提醒: 0 金额占位条目 id=${pendingId.take(16)}")
            return
        }
        if (!isEnabled(context)) {
            JotBillLog.i(TAG, "跳过自动关闭提醒: 用户已关闭开关")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null || !manager.areNotificationsEnabled()) {
            JotBillLog.w(TAG, "跳过自动关闭提醒: 应用通知权限未授予(API 33+ 需运行时授权)")
            return
        }
        try {
            manager.notify(
                pendingId.hashCode(),
                buildAutoDismissedNotification(context, pendingId, amountMinor, type, sourceType)
            )
            JotBillLog.i(
                TAG,
                "弹窗超时已转入待确认中心, 提醒已更新: id=${pendingId.take(16)}, amount=$amountMinor"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗超时提醒发送失败", error)
        }
    }

    private fun buildAutoDismissedNotification(
        context: Context,
        pendingId: String,
        amountMinor: Long,
        type: String,
        sourceType: String
    ): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = "¥${formatYuan(amountMinor)} · ${typeLabel(type)} · ${sourceLabel(sourceType)} · 未确认，已存入待确认中心"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("这笔还没记上")
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
