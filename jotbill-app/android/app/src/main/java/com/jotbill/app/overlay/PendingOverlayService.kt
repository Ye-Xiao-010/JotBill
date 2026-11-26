package com.jotbill.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jotbill.app.utils.JotBillLog

/**
 * 弹窗期间的短时 specialUse 前台服务（2026-10-29）。
 *
 * ## 为什么需要
 *
 * HyperOS 3 在**支付宝前台**会把本应用的 `SYSTEM_ALERT_WINDOW` AppOp 临时置为
 * `IGNORED`，并在恢复瞬间出现"AppOps 已 ALLOWED、窗口层尚未恢复"的空窗。
 * 远程用户 3 的 15.50 支付实测（`docs/qa/device/2026-10-29-remote-user3/`）：
 * 第一张弹窗 10:31:46.126 `addView` 成功、`attached=true`，但 30 秒内无障碍
 * 活动窗口一直是支付宝 —— 用户侧"什么都没弹"；第二张 30 秒后同样的窗口却正常。
 *
 * 对照钱迹（同机 2026-10-28 23:26:47 支付宝 2.60）：它在 `addView` **之前 70ms**
 * 拉起 `AutoBillFloatingService`（specialUse 前台服务），系统日志明确
 * `Background started FGS: Allowed ... code:UID_VISIBLE`，随后窗口立即拿到输入焦点。
 *
 * 本服务即该机制的最小实现：弹窗 `addView` 前启动、`dismissInternal` 后停止，
 * 让本应用在支付应用前台时保持 `UID_VISIBLE`，避免窗口被 ROM 压到支付应用之下。
 *
 * ## 边界
 *
 * - 只在弹窗生命周期内存在（秒级），不做常驻；常驻保活由无障碍服务自己的
 *   1×1 悬浮窗负责（见 `CheckoutAccessibilityService.installKeepAliveWindow`）。
 * - 启动失败（Android 12+ 后台启动限制、厂商裁剪）只记日志，**不阻断弹窗** ——
 *   退回无 FGS 路径，行为与 0.0.104 一致。
 */
class PendingOverlayService : Service() {

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val started = startForegroundCompat()
        val shouldStopImmediately = synchronized(stateLock) {
            runningInstance = this
            !started || stopRequested
        }
        if (shouldStopImmediately) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted && !startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val shouldStop = synchronized(stateLock) { stopRequested }
        if (shouldStop) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (foregroundStarted) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            foregroundStarted = false
        }
        synchronized(stateLock) {
            if (runningInstance === this) {
                runningInstance = null
                if (stopRequested) {
                    startDispatched = false
                }
            }
        }
        JotBillLog.i(TAG, "弹窗前台服务已停止")
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            JotBillLog.i(TAG, "弹窗前台服务已启动: type=specialUse")
            true
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗前台服务启动失败(startForeground)", error)
            false
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "付款确认弹窗保活",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "仅在付款确认弹窗显示期间短暂运行"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("正在准备记账确认")
            .setContentText("付款确认弹窗显示期间短暂运行")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "PendingOverlayService"
        private const val CHANNEL_ID = "pending_overlay_keepalive"
        private const val NOTIFICATION_ID = 0x4A11

        private val stateLock = Any()
        private var runningInstance: PendingOverlayService? = null
        private var startDispatched = false
        private var stopRequested = false

        /** 启动前台服务；失败只记日志，调用方继续走无 FGS 路径。 */
        fun start(context: Context) {
            synchronized(stateLock) {
                stopRequested = false
            }
            try {
                val intent = Intent(context, PendingOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                synchronized(stateLock) {
                    startDispatched = true
                }
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "弹窗前台服务启动失败", error)
            }
        }

        /**
         * 停止前台服务；幂等，且避免在 `startForegroundService()` 刚派发、
         * `onCreate()` 尚未执行 `startForeground()` 的空窗期强行 `stopService()`
         * 触发 Android 12+ `ForegroundServiceDidNotStartInTimeException`。
         */
        fun stop(context: Context) {
            val instanceToStop = synchronized(stateLock) {
                stopRequested = true
                val current = runningInstance
                when {
                    current != null && current.foregroundStarted -> current
                    current == null && !startDispatched -> null
                    else -> null
                }
            }
            if (instanceToStop != null) {
                try {
                    instanceToStop.stopSelf()
                } catch (error: RuntimeException) {
                    JotBillLog.w(TAG, "弹窗前台服务停止失败", error)
                }
            }
        }
    }
}
