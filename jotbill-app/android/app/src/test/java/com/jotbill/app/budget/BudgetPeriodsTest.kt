package com.jotbill.app.budget

import com.jotbill.app.data.entity.BudgetEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPeriodsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun budget(
        period: String,
        startDay: Int? = null,
        weekStart: Int? = null,
        anchorAt: Long? = null,
        rangeStartAt: Long? = null,
        rangeEndAt: Long? = null
    ) = BudgetEntity(
        id = "budget",
        name = "test",
        amountMinor = 100_00L,
        period = period,
        startDay = startDay,
        weekStart = weekStart,
        anchorAt = anchorAt,
        rangeStartAt = rangeStartAt,
        rangeEndAt = rangeEndAt,
        categoryId = null,
        alertAt = 80,
        isActive = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun at(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun dateOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    @Test
    fun `monthly with custom start day covers pay cycle`() {
        val range = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.MONTHLY, startDay = 25),
            at("2026-11-21"),
            zone
        )
        assertEquals(LocalDate.of(2026, 10, 25), dateOf(range.startAt))
        assertEquals(LocalDate.of(2026, 11, 24), dateOf(range.endAt))
    }

    @Test
    fun `monthly start day 31 clamps to last day of short month`() {
        val beforeFebruaryEnd = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.MONTHLY, startDay = 31),
            at("2026-02-15"),
            zone
        )
        assertEquals(LocalDate.of(2026, 1, 31), dateOf(beforeFebruaryEnd.startAt))
        assertEquals(LocalDate.of(2026, 2, 27), dateOf(beforeFebruaryEnd.endAt))

        val onFebruaryEnd = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.MONTHLY, startDay = 31),
            at("2026-02-28"),
            zone
        )
        assertEquals(LocalDate.of(2026, 2, 28), dateOf(onFebruaryEnd.startAt))
        assertEquals(LocalDate.of(2026, 3, 30), dateOf(onFebruaryEnd.endAt))
    }

    @Test
    fun `weekly uses configured week start`() {
        val mondayBased = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.WEEKLY, weekStart = 1),
            at("2026-11-20"),
            zone
        )
        assertEquals(LocalDate.of(2026, 11, 16), dateOf(mondayBased.startAt))
        assertEquals(LocalDate.of(2026, 11, 22), dateOf(mondayBased.endAt))

        val sundayBased = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.WEEKLY, weekStart = 7),
            at("2026-11-21"),
            zone
        )
        assertEquals(LocalDate.of(2026, 11, 15), dateOf(sundayBased.startAt))
        assertEquals(LocalDate.of(2026, 11, 21), dateOf(sundayBased.endAt))
    }

    @Test
    fun `biweekly anchors on configured date`() {
        val anchor = at("2026-11-02")
        val secondCycle = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.BIWEEKLY, anchorAt = anchor),
            at("2026-11-21"),
            zone
        )
        assertEquals(LocalDate.of(2026, 11, 16), dateOf(secondCycle.startAt))
        assertEquals(LocalDate.of(2026, 11, 29), dateOf(secondCycle.endAt))

        val firstCycle = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.BIWEEKLY, anchorAt = anchor),
            at("2026-11-15"),
            zone
        )
        assertEquals(LocalDate.of(2026, 11, 2), dateOf(firstCycle.startAt))
        assertEquals(LocalDate.of(2026, 11, 15), dateOf(firstCycle.endAt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `biweekly without anchor fails instead of drifting daily`() {
        BudgetPeriods.rangeFor(
            budget(BudgetPeriods.BIWEEKLY),
            at("2026-11-21"),
            zone
        )
    }

    @Test
    fun `quarter and year use calendar boundaries`() {
        val quarter = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.QUARTERLY),
            at("2026-11-21"),
            zone
        )
        assertEquals(LocalDate.of(2026, 10, 1), dateOf(quarter.startAt))
        assertEquals(LocalDate.of(2026, 12, 31), dateOf(quarter.endAt))
        assertEquals("2026 Q4", BudgetPeriods.label(budget(BudgetPeriods.QUARTERLY), quarter, zone))

        val year = BudgetPeriods.rangeFor(
            budget(BudgetPeriods.YEARLY),
            at("2026-11-21"),
            zone
        )
        assertEquals(LocalDate.of(2026, 1, 1), dateOf(year.startAt))
        assertEquals(LocalDate.of(2026, 12, 31), dateOf(year.endAt))
        assertEquals("2026年", BudgetPeriods.label(budget(BudgetPeriods.YEARLY), year, zone))
    }

    @Test
    fun `one-off range is fixed and active only inside it`() {
        val item = budget(
            BudgetPeriods.ONCE,
            rangeStartAt = at("2026-11-01"),
            rangeEndAt = BudgetPeriods.endOfDay(LocalDate.parse("2026-11-07"), zone)
        )
        val range = BudgetPeriods.rangeFor(item, at("2026-11-21"), zone)
        assertEquals(LocalDate.of(2026, 11, 1), dateOf(range.startAt))
        assertEquals(LocalDate.of(2026, 11, 7), dateOf(range.endAt))
        assertFalse(range.contains(at("2026-11-21")))
        assertEquals("11/01–11/07", BudgetPeriods.label(item, range, zone))
    }

    @Test
    fun `label includes years when cycle crosses year boundary`() {
        val item = budget(BudgetPeriods.MONTHLY, startDay = 25)
        val range = BudgetPeriods.rangeFor(item, at("2026-01-10"), zone)
        assertEquals(LocalDate.of(2025, 12, 25), dateOf(range.startAt))
        assertEquals(LocalDate.of(2026, 1, 24), dateOf(range.endAt))
        assertEquals(
            "2025/12/25–2026/01/24",
            BudgetPeriods.label(item, range, zone)
        )
    }

    @Test
    fun `future one-off range does not count as active`() {
        val item = budget(
            BudgetPeriods.ONCE,
            rangeStartAt = at("2026-12-01"),
            rangeEndAt = BudgetPeriods.endOfDay(LocalDate.parse("2026-12-10"), zone)
        )
        val range = BudgetPeriods.rangeFor(item, at("2026-11-21"), zone)
        assertTrue(range.startAt > at("2026-11-21"))
        assertFalse(range.contains(at("2026-11-21")))
    }
}
