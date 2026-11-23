package com.jotbill.app.bookkeeping

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jotbill.app.utils.JotBillLog

/**
 * 自动记账成功通知（对齐设计方案 §2.7 与小米反面教材 §7.5：通知权限被关
 * 造成的「不可感知失效」必须显式留日志）。带「撤销」动作；点击正文拉起应用。
 */
class AutoBookkeepingNotifier(private val context: Context) {

    fun notifyAutoBooked(
        billId: String,
        amountMinor: Long,
        type: String,
        channelLabel: String,
        merchant: String?
    ) {
        if (!BookkeepingChannels.isAutoBookedNotificationEnabled(context)) {
            JotBillLog.i(TAG, "自动记账通知已关闭(设置), 跳过: billId=${billId.take(8)}")
            return
        }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null || !manager.areNotificationsEnabled()) {
            JotBillLog.w(TAG, "自动记账通知未发: 应用通知权限未授予")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "自动记账",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "自动记账成功时提醒，可撤销" }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val text = buildString {
            append("¥").append(formatYuan(amountMinor))
            append(" · ").append(if (type == "income") "收入" else "支出")
            append(" · ").append(channelLabel)
            merchant?.let { append(" · ").append(it) }
        }
        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        val undoIntent = Intent(context, AutoBookkeepingUndoReceiver::class.java).apply {
            action = AutoBookkeepingUndoReceiver.ACTION_UNDO
            putExtra(AutoBookkeepingUndoReceiver.EXTRA_BILL_ID, billId)
        }
        val undoPendingIntent = PendingIntent.getBroadcast(
            context,
            billId.hashCode(),
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = builder
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("已自动记一笔")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .addAction(0, "撤销", undoPendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(billId.hashCode(), notification)
        JotBillLog.i(TAG, "自动记账通知已发: billId=${billId.take(8)}, $text")
    }

    companion object {
        private const val TAG = "AutoBookkeepingNotifier"

        const val CHANNEL_ID = "auto_bookkeeping"

        fun formatYuan(amountMinor: Long): String {
            val sign = if (amountMinor < 0) "-" else ""
            val abs = Math.abs(amountMinor)
            return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        }
    }
}
