package com.jotbill.app.budget

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetProgressCalculatorTest {
    private lateinit var database: JotBillDatabase
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun at(date: String, time: LocalTime = LocalTime.NOON): Long =
        LocalDate.parse(date).atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun bill(
        id: String,
        amountMinor: Long,
        type: String,
        occurredAt: Long,
        categoryId: String? = null
    ) = BillEntity(
        id = id,
        amountMinor = amountMinor,
        type = type,
        occurredAt = occurredAt,
        description = null,
        categoryId = categoryId,
        accountId = null,
        targetAccountId = null,
        platform = null,
        merchant = null,
        productDescription = null,
        sourceType = "manual",
        originalTransactionId = null,
        orderId = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        version = 1
    )

    private fun budget(categoryId: String? = null) = BudgetEntity(
        id = "budget",
        name = "test",
        amountMinor = 300_00L,
        period = BudgetPeriods.MONTHLY,
        startDay = 25,
        categoryId = categoryId,
        alertAt = 80,
        isActive = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private suspend fun insertCategory(id: String) {
        database.categoryDao().insert(
            CategoryEntity(
                id = id,
                name = id,
                type = "expense",
                icon = null,
                color = null,
                isDefault = false,
                sortOrder = 0,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
    }

    @Test
    fun refundOffsetsPeriodSpendingWithoutCategoryOrOriginalLink() = runBlocking {
        val now = at("2026-11-21")
        database.billDao().insert(bill("expense", 241_50L, "expense", at("2026-11-13")))
        database.billDao().insert(bill("refund", 100_00L, "refund", at("2026-11-20")))
        database.billDao().insert(bill("income", 500_00L, "income", at("2026-11-10")))
        database.billDao().insert(bill("transfer", 500_00L, "transfer", at("2026-11-10")))

        val snapshot = BudgetProgressCalculator.compute(database, budget(), now)
        assertEquals(241_50L, snapshot.grossSpentMinor)
        assertEquals(100_00L, snapshot.refundMinor)
        assertEquals(141_50L, snapshot.spentMinor)
        assertEquals(158_50L, snapshot.remainingMinor)
        assertEquals(47, snapshot.progressPct)
        assertTrue(snapshot.isActivePeriod)
    }

    @Test
    fun categoryBudgetStillGetsRefundsFromOtherCategories() = runBlocking {
        insertCategory("food")
        insertCategory("travel")
        val now = at("2026-11-21")
        database.billDao().insert(
            bill("expense", 200_00L, "expense", at("2026-11-13"), categoryId = "food")
        )
        database.billDao().insert(
            bill("refund", 50_00L, "refund", at("2026-11-20"), categoryId = "travel")
        )

        val snapshot = BudgetProgressCalculator.compute(database, budget("food"), now)
        assertEquals(200_00L, snapshot.grossSpentMinor)
        assertEquals(50_00L, snapshot.refundMinor)
        assertEquals(150_00L, snapshot.spentMinor)
    }

    @Test
    fun refundsExceedingSpendingClampNetToZero() = runBlocking {
        val now = at("2026-11-21")
        database.billDao().insert(bill("expense", 20_00L, "expense", at("2026-11-13")))
        database.billDao().insert(bill("refund", 50_00L, "refund", at("2026-11-20")))

        val snapshot = BudgetProgressCalculator.compute(database, budget(), now)
        assertEquals(0L, snapshot.spentMinor)
        assertEquals(0.0, snapshot.progress, 0.0)
        assertEquals(300_00L, snapshot.remainingMinor)
    }
}
