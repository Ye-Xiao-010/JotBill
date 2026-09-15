package com.jotbill.app.rules

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.RuleEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 规则引擎条件匹配的回归测试。
 *
 * 审查发现缺陷：matchesCondition 中 merchant_equals/merchant_contains/description_contains
 * 区分大小写，而 platform_equals/source_equals 已忽略大小写——英文商户名
 * （如 "KFC"、"JD.com"、"Apple Store"）大小写不符会导致规则静默不命中，与平台/来源
 * 条件行为不一致。本测试锁定修复后的统一忽略大小写行为。
 *
 * 说明：evaluate 是纯函数不访问数据库，仅用内存库满足构造参数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleEngineConditionMatchTest {

    private lateinit var database: JotBillDatabase
    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = RuleEngine(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun rule(
        id: String = "rule-1",
        condition: String,
        conditionValue: String,
        action: String = "set_platform",
        actionValue: String? = "微信支付",
        priority: Int = 100
    ) = RuleEntity(
        id = id, name = "测试规则", condition = condition,
        conditionValue = conditionValue, action = action,
        actionValue = actionValue, priority = priority,
        isActive = true, hitCount = 0, source = "manual",
        createdAt = 1L, updatedAt = 1L
    )

    @Test
    fun merchantEqualsIgnoresCase() {
        val decision = engine.evaluate(
            subject = RuleEngine.Subject(merchant = "KFC 肯德基"),
            rules = listOf(rule(condition = "merchant_equals", conditionValue = "kfc 肯德基"))
        )
        assertEquals("merchant_equals 应忽略大小写命中", 1, decision.hits.size)
    }

    @Test
    fun merchantContainsIgnoresCase() {
        val decision = engine.evaluate(
            subject = RuleEngine.Subject(merchant = "JD.COM 京东商城"),
            rules = listOf(rule(condition = "merchant_contains", conditionValue = "jd.com"))
        )
        assertEquals("merchant_contains 应忽略大小写命中", 1, decision.hits.size)
    }

    @Test
    fun descriptionContainsIgnoresCase() {
        val decision = engine.evaluate(
            subject = RuleEngine.Subject(description = "Apple Store 付款"),
            rules = listOf(rule(condition = "description_contains", conditionValue = "apple store"))
        )
        assertEquals("description_contains 应忽略大小写命中", 1, decision.hits.size)
    }

    @Test
    fun platformEqualsStillIgnoresCase() {
        val decision = engine.evaluate(
            subject = RuleEngine.Subject(platform = "com.tencent.mm"),
            rules = listOf(rule(condition = "platform_equals", conditionValue = "COM.TENCENT.MM"))
        )
        assertEquals("platform_equals 保持忽略大小写", 1, decision.hits.size)
    }

    @Test
    fun merchantMismatchStillMisses() {
        val decision = engine.evaluate(
            subject = RuleEngine.Subject(merchant = "星巴克"),
            rules = listOf(rule(condition = "merchant_contains", conditionValue = "KFC"))
        )
        assertTrue("不相关的商户不应命中", decision.hits.isEmpty())
    }
}
