package com.jotbill.app.budget

import com.jotbill.app.data.entity.BudgetEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 预算周期唯一事实源：进度接口、系统提醒、前端展示标签都从这里取，
 * 避免自定义周期后出现"页面没超、通知超了"的双口径。
 *
 * 区间左闭右开，endAt 为下一周期起点前 1ms；一次性区间按用户选定的起止日（含当日）。
 */
internal object BudgetPeriods {
    const val MONTHLY = "monthly"
    const val WEEKLY = "weekly"
    const val BIWEEKLY = "biweekly"
    const val QUARTERLY = "quarterly"
    const val YEARLY = "yearly"
    const val ONCE = "once"

    val supported: Set<String> = linkedSetOf(
        MONTHLY, WEEKLY, BIWEEKLY, QUARTERLY, YEARLY, ONCE
    )

    data class Range(val startAt: Long, val endAt: Long) {
        fun contains(instant: Long): Boolean = instant in startAt..endAt
    }

    fun rangeFor(
        budget: BudgetEntity,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Range {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (budget.period) {
            MONTHLY -> monthlyRange(budget.startDay ?: 1, today, zone)
            WEEKLY -> weeklyRange(budget.weekStart ?: 1, today, zone)
            BIWEEKLY -> biweeklyRange(
                budget.anchorAt
                    ?: throw IllegalArgumentException("Biweekly budget missing anchorAt"),
                today,
                zone
            )
            QUARTERLY -> quarterRange(today, zone)
            YEARLY -> yearRange(today, zone)
            ONCE -> Range(
                startAt = budget.rangeStartAt ?: startOfDay(today, zone),
                endAt = budget.rangeEndAt ?: endOfDay(today, zone)
            )
            else -> throw IllegalArgumentException("Unsupported budget period: ${budget.period}")
        }
    }

    /** 前端徽标与通知共用：年度显示年份、季度显示 Q、其余显示日期区间。 */
    fun label(
        budget: BudgetEntity,
        range: Range,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val start = Instant.ofEpochMilli(range.startAt).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(range.endAt).atZone(zone).toLocalDate()
        return when (budget.period) {
            YEARLY -> "${start.year}年"
            QUARTERLY -> {
                val quarter = (start.monthValue - 1) / 3 + 1
                "${start.year} Q$quarter"
            }
            else -> {
                val monthDay = DateTimeFormatter.ofPattern("MM/dd")
                when {
                    start == end -> monthDay.format(start)
                    start.year == end.year ->
                        "${monthDay.format(start)}–${monthDay.format(end)}"
                    else ->
                        "${start.year}/${monthDay.format(start)}–${end.year}/${monthDay.format(end)}"
                }
            }
        }
    }

    fun startOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfDay(date.plusDays(1), zone) - 1L

    private fun range(
        start: LocalDate,
        nextStart: LocalDate,
        zone: ZoneId
    ): Range = Range(startOfDay(start, zone), startOfDay(nextStart, zone) - 1L)

    private fun monthlyRange(startDay: Int, today: LocalDate, zone: ZoneId): Range {
        val day = startDay.coerceIn(1, 31)
        fun cycleStart(month: YearMonth): LocalDate =
            month.atDay(minOf(day, month.lengthOfMonth()))

        var start = cycleStart(YearMonth.from(today))
        if (start.isAfter(today)) {
            start = cycleStart(YearMonth.from(today).minusMonths(1))
        }
        val next = cycleStart(YearMonth.from(start).plusMonths(1))
        return range(start, next, zone)
    }

    private fun weeklyRange(weekStart: Int, today: LocalDate, zone: ZoneId): Range {
        val anchorDay = DayOfWeek.of(weekStart.coerceIn(1, 7))
        val start = today.with(TemporalAdjusters.previousOrSame(anchorDay))
        return range(start, start.plusWeeks(1), zone)
    }

    private fun biweeklyRange(anchorAt: Long, today: LocalDate, zone: ZoneId): Range {
        val anchor = Instant.ofEpochMilli(anchorAt).atZone(zone).toLocalDate()
        val start = if (today.isBefore(anchor)) {
            anchor
        } else {
            val days = ChronoUnit.DAYS.between(anchor, today)
            anchor.plusDays((days / 14) * 14)
        }
        return range(start, start.plusDays(14), zone)
    }

    private fun quarterRange(today: LocalDate, zone: ZoneId): Range {
        val firstMonth = ((today.monthValue - 1) / 3) * 3 + 1
        val start = LocalDate.of(today.year, firstMonth, 1)
        return range(start, start.plusMonths(3), zone)
    }

    private fun yearRange(today: LocalDate, zone: ZoneId): Range =
        range(LocalDate.of(today.year, 1, 1), LocalDate.of(today.year + 1, 1, 1), zone)
}
