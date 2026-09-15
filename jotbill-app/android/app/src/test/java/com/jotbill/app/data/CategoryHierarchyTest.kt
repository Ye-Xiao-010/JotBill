package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.dao.BillDao
import com.jotbill.app.data.dao.CategoryDao
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CategoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批 6 两级分类体系(Robolectric + Room 内存库):
 * - 父/子分类 CRUD 与 getChildren/countChildren;
 * - 分类统计按父上卷(COALESCE(parent_id, id));
 * - 预算求和包含子分类支出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CategoryHierarchyTest {

    private lateinit var database: JotBillDatabase
    private lateinit var categoryDao: CategoryDao
    private lateinit var billDao: BillDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        categoryDao = database.categoryDao()
        billDao = database.billDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun category(id: String, name: String, parentId: String? = null) =
        CategoryEntity(
            id = id, name = name, type = "expense", icon = null, color = null,
            isDefault = false, sortOrder = 0, createdAt = 1L, updatedAt = 1L,
            parentId = parentId
        )

    private fun account(id: String = "acc-1") = AccountEntity(
        id = id, name = "测试账户", subtype = "cash", kind = "asset",
        initialBalanceMinor = 0L, currentBalanceMinor = 0L, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    private fun bill(id: String, categoryId: String, amountMinor: Long) = BillEntity(
        id = id, amountMinor = amountMinor, type = "expense",
        occurredAt = 1_000_000L, description = null, categoryId = categoryId,
        accountId = "acc-1", targetAccountId = null, platform = null,
        merchant = null, productDescription = null, sourceType = "manual",
        originalTransactionId = null, orderId = null,
        createdAt = 1L, updatedAt = 1L, version = 1
    )

    @Test
    fun childrenQueriesWork() = runBlocking {
        categoryDao.insert(category("parent-1", "餐饮"))
        categoryDao.insert(category("child-1", "早餐", parentId = "parent-1"))
        categoryDao.insert(category("child-2", "正餐", parentId = "parent-1"))
        categoryDao.insert(category("other", "交通"))
        assertEquals(2, categoryDao.getChildren("parent-1").size)
        assertEquals(2, categoryDao.countChildren("parent-1"))
        assertEquals(0, categoryDao.countChildren("child-1"))
    }

    @Test
    fun categoryStatsRollUpToParent() = runBlocking {
        database.accountDao().insert(account())
        categoryDao.insert(category("parent-1", "餐饮"))
        categoryDao.insert(category("child-1", "早餐", parentId = "parent-1"))
        categoryDao.insert(category("parent-2", "交通"))
        billDao.insert(bill("b-1", "child-1", 500L))
        billDao.insert(bill("b-2", "child-1", 300L))
        billDao.insert(bill("b-3", "parent-1", 200L))
        val stats = billDao.getCategoryStats("expense", 0L, 10_000_000L)
        // 子分类与父分类的直接账单都上卷到父分类统计
        assertEquals(1, stats.size)
        assertEquals("parent-1", stats.single().categoryId)
        assertEquals(1000L, stats.single().amountMinor)
        assertEquals(3, stats.single().count)
    }

    @Test
    fun presetSubCategoriesSeedOncePerUpgrade() = runBlocking {
        val initializer = com.jotbill.app.data.seed.DefaultDataInitializer(database)
        initializer.initializeIfFirstRun()
        initializer.ensurePresetSubCategories()
        val foodChildren = categoryDao.getChildren("default-category-expense-food")
        assertEquals(4, foodChildren.size)
        assertTrue(foodChildren.all { it.parentId == "default-category-expense-food" })
        // 重复执行不复活(播种标记)
        initializer.ensurePresetSubCategories()
        assertEquals(4, categoryDao.getChildren("default-category-expense-food").size)
    }

    @Test
    fun budgetSumIncludesChildCategoryExpenses() = runBlocking {
        database.accountDao().insert(account())
        categoryDao.insert(category("parent-1", "餐饮"))
        categoryDao.insert(category("child-1", "早餐", parentId = "parent-1"))
        categoryDao.insert(category("other", "交通"))
        billDao.insert(bill("b-1", "child-1", 500L))
        billDao.insert(bill("b-2", "other", 900L))
        assertEquals(500L, billDao.sumExpenses("parent-1", 0L, 10_000_000L))
        assertTrue(billDao.sumExpenses("other", 0L, 10_000_000L) == 900L)
    }
}
