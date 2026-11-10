package com.jotbill.app.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jotbill.app.MainActivity
import com.jotbill.app.bookkeeping.BookkeepingChannels
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.utils.JotBillLog

/**
 * 低频后台守护：只保留一条低重要性常驻通知，不轮询、不定时唤醒。
 *
 * 自动记账仍然由无障碍/通知监听事件驱动；这个服务只提升进程存活优先级，
 * 降低最近任务被清理后漏采的概率。用户可在自动记账设置页随时关闭。
 */
class RecognitionKeepAliveService : Service() {
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startForegroundCompat()) {
            stopSelf()
            return
        }
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted && !startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification(this)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "前台守护启动失败", error)
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台守护",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持自动记账在后台运行；不响铃、不振动"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RecognitionKeepAlive"
        private const val CHANNEL_ID = "recognition_keep_alive"
        private const val NOTIFICATION_ID = 0x4A42

        @Volatile
        var isRunning: Boolean = false
            private set

        fun sync(context: Context) {
            if (shouldRun(context)) {
                start(context)
            } else {
                stop(context)
            }
        }

        private fun shouldRun(context: Context): Boolean =
            KeepAlivePolicy.shouldRun(
                BookkeepingChannels.isMasterEnabled(context),
                KeepAliveSettings.isEnabled(context),
            )

        private fun start(context: Context) {
            try {
                val intent = Intent(context, RecognitionKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "后台守护启动失败", error)
            }
        }

        private fun stop(context: Context) {
            context.stopService(Intent(context, RecognitionKeepAliveService::class.java))
        }

        /** 无障碍连接/断开时刷新常驻通知，让用户能直接看到失效状态。 */
        fun refreshNotification(context: Context) {
            if (!isRunning) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(context))
        }

        private fun buildNotification(context: Context): Notification {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle("JotBill 自动记账守护中")
                .setContentText(statusText())
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }

        private fun statusText(): String =
            if (CheckoutAccessibilityService.isServiceRunning()) {
                "支付结果页与通知信号监听中，点击查看状态"
            } else {
                "无障碍服务未运行，自动记账可能停止，请打开应用检查权限"
            }
    }
}
