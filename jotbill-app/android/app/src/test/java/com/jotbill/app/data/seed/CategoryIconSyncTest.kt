package com.jotbill.app.data.seed

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AppSettingEntity
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
 * 默认分类图标一次性迁移（emoji → lucide 名）：
 * - 老库（emoji 图标 + 默认名）全部升级为种子 lucide 名；
 * - 用户改过名的默认分类不动；
 * - 用户自选过图标（≠旧种子 emoji）的默认分类不动；
 * - 自定义分类（不在种子清单内）永不触碰；
 * - 设置键短路：二次执行不重复写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CategoryIconSyncTest {

    private lateinit var database: JotBillDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun category(
        id: String,
        name: String,
        icon: String?,
        type: String = "expense"
    ) = CategoryEntity(
        id = id, name = name, type = type, icon = icon, color = null,
        isDefault = true, sortOrder = 0, createdAt = 1L, updatedAt = 1L
    )

    @Test
    fun migratesLegacyEmojiToLucideName() = runBlocking {
        database.categoryDao().insertIgnore(
            category("default-category-expense-food", "餐饮", "🍜")
        )
        val initializer = DefaultDataInitializer(database)
        initializer.syncDefaultCategoryIcons()
        assertEquals("utensils", database.categoryDao().getById("default-category-expense-food")?.icon)
    }

    @Test
    fun skipsRenamedCategory() = runBlocking {
        database.categoryDao().insertIgnore(
            category("default-category-expense-food", "我的伙食", "🍜")
        )
        val initializer = DefaultDataInitializer(database)
        initializer.syncDefaultCategoryIcons()
        // 名字被用户改过 ⇒ 不动（判据要求 name 与默认值一致）
        assertEquals("🍜", database.categoryDao().getById("default-category-expense-food")?.icon)
    }

    @Test
    fun skipsUserChosenIcon() = runBlocking {
        database.categoryDao().insertIgnore(
            category("default-category-expense-food", "餐饮", "🥘")
        )
        val initializer = DefaultDataInitializer(database)
        initializer.syncDefaultCategoryIcons()
        // icon 与旧种子 emoji 不等 ⇒ 用户已自选，不动
        assertEquals("🥘", database.categoryDao().getById("default-category-expense-food")?.icon)
    }

    @Test
    fun neverTouchesCustomCategories() = runBlocking {
        database.categoryDao().insertIgnore(
            CategoryEntity(
                id = "custom-1", name = "餐饮", type = "expense", icon = "🍜", color = null,
                isDefault = false, sortOrder = 99, createdAt = 1L, updatedAt = 1L
            )
        )
        val initializer = DefaultDataInitializer(database)
        initializer.syncDefaultCategoryIcons()
        assertEquals("🍜", database.categoryDao().getById("custom-1")?.icon)
    }

    @Test
    fun runsOnlyOnce() = runBlocking {
        database.categoryDao().insertIgnore(
            category("default-category-expense-food", "餐饮", "🍜")
        )
        val initializer = DefaultDataInitializer(database)
        initializer.syncDefaultCategoryIcons()
        // 模拟"迁移后用户改回 emoji"：第二次执行不得再次改写
        val dao = database.categoryDao()
        dao.update(dao.getById("default-category-expense-food")!!.copy(icon = "🍜"))
        initializer.syncDefaultCategoryIcons()
        assertEquals("🍜", dao.getById("default-category-expense-food")?.icon)
        assertEquals(
            "true",
            database.appSettingDao().get("category_icons_synced_to_lucide")
        )
    }

    @Test
    fun freshSeedProducesLucideNames() = runBlocking {
        // 全新库：initializeIfFirstRun 直接种出 lucide 名（不经过迁移）
        val initializer = DefaultDataInitializer(database)
        initializer.initializeIfFirstRun()
        val all = database.categoryDao().getAll()
        assertTrue(all.isNotEmpty())
        // emoji 起码落在增补平面（首个 Char 是高位代理项，code ≥ 0xD800 且非 BMP 常规字）；
        // lucide 名是 ASCII，首字符 code < 0x80 —— 用「首字符非 ASCII」判 emoji 足够。
        assertTrue(
            "首启种子不应再产 emoji，实际含: ${all.filter { (it.icon?.firstOrNull()?.code ?: 0) > 0x7F }.map { "${it.name}:${it.icon}" }}",
            all.none { (it.icon?.firstOrNull()?.code ?: 0) > 0x7F }
        )
    }
}
