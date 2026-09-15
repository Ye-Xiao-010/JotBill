package com.jotbill.app.budget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import com.jotbill.app.data.JotBillDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 预算超支系统通知提醒。
 *
 * 与 RN 完全解耦:在 [com.jotbill.app.MainApplication] 启动时初始化,
 * 账单增删改后由 DataModule 触发 [checkAndNotify] 检查所有活跃预算,
 * 跨过预警档位时发送系统通知。不依赖 RN 是否启动。
 *
 * 提醒范围决策(对齐 DECISIONS「预算超支提醒 80% 和 100% 各提醒一次,本地通知,可关闭」):
 * - 一次性:每个周期(月度/年度)内,预警档位(alertAt,默认 80)与 100% 各提醒一次,
 *   以 SharedPreferences 记录已提醒档位去重;新周期档位键随周期号变化,自动重置;
 * - 预算金额为 0 时进度按 0/100 处理(花过即 100,未花为 0);
 * - 隐私:通知文案只含预算名与金额,不含商户/订单号等敏感字段。
 */
internal object BudgetReminderNotifier {
    private const val TAG = "BudgetReminder"
    private const val CHANNEL_ID = "budget_reminder"
    private const val PREFS_NAME = "budget_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val ALERTED_PREFIX = "alerted_"
    private const val ALERTED_AT_PREFIX = "alerted_at_"

    @Volatile
    private var appContext: Context? = null

    /** DataModule 每次账单写入都会各起协程调用 checkAndNotify,串行化避免并发重复通知 */
    private val checkMutex = Mutex()

    /** 命中预算:预算实体 + 当前进度百分比(0-100)。 */
    data class BudgetAlertHit(val budget: com.jotbill.app.data.entity.BudgetEntity, val progressPct: Int)

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

    /**
     * 检查所有活跃预算,对跨过未提醒档位的预算发系统通知并记录档位。
     *
     * @return 本次新触发的提醒命中(供 DataModule 发射 onBudgetAlert 事件给 JS)
     */
    suspend fun checkAndNotify(database: JotBillDatabase): List<BudgetAlertHit> = checkMutex.withLock {
        val context = appContext ?: return@withLock emptyList()
        if (!isEnabled(context)) return@withLock emptyList()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null || !manager.areNotificationsEnabled()) return@withLock emptyList()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hits = mutableListOf<BudgetAlertHit>()
        database.budgetDao().getActive().forEach { budget ->
            val (start, end) = budgetPeriodRange(budget.period, now)
            val spent = database.billDao().sumExpenses(budget.categoryId, start, end)
            val progressPct = if (budget.amountMinor == 0L) {
                if (spent > 0L) 100 else 0
            } else {
                (spent.toDouble() / budget.amountMinor.toDouble() * 100.0).roundToInt()
            }
            // 一次性档位:alertAt(默认 80)与 100 各提醒一次;同周期内已提醒的档位不再提醒。
            // 另存触发该提醒时的 alertAt:用户同周期内调低阈值后,新阈值档位允许再提醒一次
            // (此前只存最高已提醒档位,调低后永远不会再提醒)。
            val periodKey = periodKey(budget.period, now)
            val dedupKey = ALERTED_PREFIX + budget.id + "_" + periodKey
            val alertAtKey = ALERTED_AT_PREFIX + budget.id + "_" + periodKey
            val already = prefs.getInt(dedupKey, 0)
            val storedAlertAt = prefs.getInt(alertAtKey, Int.MIN_VALUE)
            var level = 0
            when {
                progressPct >= 100 && already < 100 -> level = 100
                progressPct >= budget.alertAt &&
                    (already < budget.alertAt || storedAlertAt != budget.alertAt) ->
                    level = budget.alertAt
            }
            if (level > 0) {
                prefs.edit()
                    .putInt(dedupKey, level)
                    .putInt(alertAtKey, budget.alertAt)
                    .apply()
                hits.add(BudgetAlertHit(budget, progressPct))
                notify(context, manager, budget, spent, progressPct, level)
            }
        }
        hits
    }

    private fun notify(
        context: Context,
        manager: NotificationManager,
        budget: com.jotbill.app.data.entity.BudgetEntity,
        spentMinor: Long,
        progressPct: Int,
        levelPct: Int
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
        val text = "「${budget.name}」已用 ¥${formatYuan(spentMinor)} (${progressPct}%)"
        val title = if (levelPct >= 100) "预算已超支" else "预算接近上限"
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
            "预算提醒已发送: id=${budget.id.take(16)}, level=$levelPct%, spent=$spentMinor"
        )
    }

    /** 与 DataModule.budgetPeriodRange 同口径:月度=当月 1 日~月末,年度=当年 1 月 1 日~年末。 */
    private fun budgetPeriodRange(period: String, now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDate = when (period) {
            "monthly" -> today.withDayOfMonth(1)
            "yearly" -> today.withDayOfYear(1)
            else -> today
        }
        val nextDate = when (period) {
            "monthly" -> startDate.plusMonths(1)
            "yearly" -> startDate.plusYears(1)
            else -> startDate.plusDays(1)
        }
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = nextDate.atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        return start to end
    }

    /** 档位键的周期号:月度=yyyy-MM,年度=yyyy;新周期键变化即自动重置提醒。 */
    private fun periodKey(period: String, now: Long): String {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (period == "yearly") {
            today.year.toString()
        } else {
            today.year.toString() + "-" + today.monthValue.toString().padStart(2, '0')
        }
    }

    private fun formatYuan(amountMinor: Long): String {
        val yuan = amountMinor / 100
        val fen = amountMinor % 100
        return if (fen == 0L) "$yuan" else "$yuan.${fen.toString().padStart(2, '0')}"
    }
}
