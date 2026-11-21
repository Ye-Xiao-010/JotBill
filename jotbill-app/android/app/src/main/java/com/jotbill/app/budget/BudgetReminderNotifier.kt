package com.jotbill.app.budget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import com.jotbill.app.data.JotBillDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 预算超支系统通知提醒。
 *
 * 与 RN 完全解耦:在 [com.jotbill.app.MainApplication] 启动时初始化,
 * 账单增删改后由写入方触发 [checkAndNotify] 检查所有活跃预算,
 * 跨过预警档位时发送系统通知。不依赖 RN 是否启动。
 *
 * 提醒范围决策:
 * - 每个周期实例内,预警档位(alertAt,默认 80)与 100% 各提醒一次;
 *   周期实例键 = 本期开始时间戳,自定义周期(周/双周/一次性等)不再依赖自然年月;
 * - 退款/改账导致进度回落后,已跨过的档位自动解除,再次跨过时重新提醒;
 * - 一次性区间结束后不再提醒;
 * - 预算金额为 0 时进度按 0/100 处理(花过即 100,未花为 0);
 * - 隐私:通知文案只含预算名、周期与金额,不含商户/订单号等敏感字段。
 */
internal object BudgetReminderNotifier {
    private const val TAG = "BudgetReminder"
    private const val CHANNEL_ID = "budget_reminder"
    private const val PREFS_NAME = "budget_reminder"
    private const val KEY_ENABLED = "enabled"
    // v2 档位键：旧版本存的是"最高已提醒档位"，无法安全表达回落解除；
    // 新键存位掩码，升级后可能出现一次重复提醒，但不会漏掉新产品周期。
    private const val ALERTED_MASK_PREFIX = "alerted_mask_"
    private const val ALERTED_AT_PREFIX = "alerted_at_"

    @Volatile
    private var appContext: Context? = null

    /** 各写入路径都会触发检查,串行化避免并发重复通知 */
    private val checkMutex = Mutex()

    /** 命中预算:预算实体 + 当前进度百分比(0-100) + 是否实际超支。 */
    data class BudgetAlertHit(
        val budget: com.jotbill.app.data.entity.BudgetEntity,
        val progressPct: Int,
        val isOverBudget: Boolean
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "预算提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "预算使用达到预警阈值时提醒"
            }
            manager.createNotificationChannel(channel)
        }
        Log.i(TAG, "预算提醒已初始化: channel=$CHANNEL_ID")
    }

    /** 用户开关,默认开启。提醒只影响通知,不影响预算进度计算与 UI 变色。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "预算提醒开关已切换: enabled=$enabled")
    }

    /** 备份恢复会整体替换预算与账单，旧档位键必须清空，避免压制新数据的提醒。 */
    suspend fun resetAlertState(context: Context) = checkMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        prefs.edit().clear().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "预算提醒档位已重置")
    }

    /** 删除预算时清掉其全部周期档位键，避免 prefs 无限增长。 */
    suspend fun clearBudgetState(context: Context, budgetId: String) = checkMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val prefixes = listOf(
            ALERTED_MASK_PREFIX + budgetId + "_",
            ALERTED_AT_PREFIX + budgetId + "_",
            "alerted_" + budgetId + "_"
        )
        prefs.all.keys
            .filter { key -> prefixes.any { key.startsWith(it) } }
            .forEach { key -> editor.remove(key) }
        editor.apply()
    }

    /**
     * 检查所有活跃预算,对跨过未提醒档位的预算发系统通知并记录档位。
     *
     * @return 本次新触发的提醒命中(供 DataModule 发射 onBudgetAlert 事件给 JS)
     */
    suspend fun checkAndNotify(database: JotBillDatabase): List<BudgetAlertHit> = checkMutex.withLock {
        val context = appContext ?: return@withLock emptyList()
        val manager = if (isEnabled(context)) {
            activeNotificationManager(context)
        } else {
            null
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hits = mutableListOf<BudgetAlertHit>()
        database.budgetDao().getActive().forEach { budget ->
            val snapshot = try {
                BudgetProgressCalculator.compute(database, budget, now)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "预算进度计算失败，跳过: id=${budget.id.take(16)}", error)
                return@forEach
            }
            // 一次性区间/尚未开始的双周周期不参与提醒。
            if (!snapshot.isActivePeriod) return@forEach

            val periodKey = snapshot.range.startAt.toString()
            val maskKey = ALERTED_MASK_PREFIX + budget.id + "_" + periodKey
            val alertAtKey = ALERTED_AT_PREFIX + budget.id + "_" + periodKey
            val notifiedMask = prefs.getInt(maskKey, 0)
            val storedAlertAt = prefs.getInt(alertAtKey, Int.MIN_VALUE)
            val decision = BudgetAlertPolicy.evaluate(
                progressRatio = snapshot.progress,
                alertAt = budget.alertAt,
                notifiedMask = notifiedMask,
                storedAlertAt = storedAlertAt,
                markAsNotified = manager != null
            )
            // 通知关闭时仍维护回落档位，重新开启后才不会被旧掩码压制。
            if (decision.levelToNotify == 0 || manager == null) {
                if (decision.notifiedMask != notifiedMask) {
                    prefs.edit().putInt(maskKey, decision.notifiedMask).apply()
                }
                return@forEach
            }

            val progressPct = snapshot.progressPct
            val sent = runCatching {
                notify(
                    context = context,
                    manager = manager,
                    budget = budget,
                    spentMinor = snapshot.spentMinor,
                    progressPct = progressPct,
                    levelPct = decision.levelToNotify,
                    periodLabel = BudgetPeriods.label(budget, snapshot.range)
                )
            }.onFailure {
                Log.w(TAG, "预算提醒发送失败，保留档位待下次重试", it)
            }.isSuccess
            if (!sent) return@forEach

            prefs.edit()
                .putInt(maskKey, decision.notifiedMask)
                .putInt(alertAtKey, budget.alertAt)
                .apply()
            hits.add(BudgetAlertHit(budget, progressPct, snapshot.isOverBudget))
        }
        hits
    }

    private fun activeNotificationManager(context: Context): NotificationManager? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return null
        if (!manager.areNotificationsEnabled()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return null
        }
        return manager
    }

    private fun notify(
        context: Context,
        manager: NotificationManager,
        budget: com.jotbill.app.data.entity.BudgetEntity,
        spentMinor: Long,
        progressPct: Int,
        levelPct: Int,
        periodLabel: String
    ) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = "「${budget.name}」$periodLabel 已用 ¥${formatYuan(spentMinor)} (${progressPct}%)"
        val title = when {
            levelPct < 100 -> "预算接近上限"
            spentMinor > budget.amountMinor -> "预算已超支"
            else -> "预算已用完"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(
            ("budget_" + budget.id).hashCode(),
            builder
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
        )
        Log.i(
            TAG,
            "预算提醒已发送: id=${budget.id.take(16)}, level=$levelPct%, spent=$spentMinor, period=$periodLabel"
        )
    }

    private fun formatYuan(amountMinor: Long): String {
        val yuan = amountMinor / 100
        val fen = amountMinor % 100
        return if (fen == 0L) "$yuan" else "$yuan.${fen.toString().padStart(2, '0')}"
    }
}
