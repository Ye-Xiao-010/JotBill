package com.jotbill.app.rules

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.bridge.BridgeDtoMapper
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.RuleEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 改分类一键生成规则自学习服务单元测试（Robolectric + Room 内存库）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CategoryRuleLearningServiceTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ACC_MAIN = "acc-main"
        const val CAT_FOOD = "cat-food"
        const val CAT_COFFEE = "cat-coffee"
        const val CAT_TRANSPORT = "cat-transport"
        const val CAT_INCOME = "cat-income"
    }

    private lateinit var database: JotBillDatabase
    private lateinit var service: CategoryRuleLearningService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = CategoryRuleLearningService(database)

        runBlocking {
            database.accountDao().insert(
                AccountEntity(
                    id = ACC_MAIN,
                    name = "微信零钱",
                    subtype = "e_wallet",
                    kind = "asset",
                    initialBalanceMinor = 0L,
                    currentBalanceMinor = 0L,
                    currency = "CNY",
                    icon = null,
                    color = null,
                    sortOrder = 0,
                    isActive = true,
                    createdAt = NOW,
                    updatedAt = NOW
                )
            )
            listOf(
                CategoryEntity(CAT_FOOD, "餐饮", "expense", "utensils", null, true, 0, NOW, NOW),
                CategoryEntity(CAT_COFFEE, "咖啡", "expense", "coffee", null, false, 1, NOW, NOW),
                CategoryEntity(CAT_TRANSPORT, "交通", "expense", "car", null, true, 2, NOW, NOW),
                CategoryEntity(CAT_INCOME, "其他收入", "income", "wallet", null, true, 3, NOW, NOW)
            ).forEach { database.categoryDao().insertIgnore(it) }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun bill(
        id: String,
        merchant: String?,
        type: String = "expense",
        categoryId: String? = CAT_FOOD,
        fieldLocks: String = "[]"
    ) = BillEntity(
        id = id,
        amountMinor = 1800L,
        type = type,
        occurredAt = NOW,
        description = merchant,
        categoryId = categoryId,
        accountId = ACC_MAIN,
        targetAccountId = null,
        platform = "com.tencent.mm",
        merchant = merchant,
        productDescription = null,
        sourceType = "notification",
        originalTransactionId = null,
        orderId = null,
        createdAt = NOW,
        updatedAt = NOW,
        version = 1,
        fieldLocks = fieldLocks
    )

    @Test
    fun previewSuggestsCreateWithExactHistoricalCountAndSkipsLockedAndIncompatibleBills() = runBlocking {
        listOf(
            // 当前刚修改的账单：通过 excludeBillId 排除
            bill(id = "bill-current", merchant = "瑞幸咖啡", categoryId = CAT_COFFEE),
            // 历史可更新 1：同商户、支出、未锁定、不同分类
            bill(id = "bill-hist-1", merchant = "瑞幸咖啡", type = "expense", categoryId = CAT_FOOD),
            // 历史可更新 2：大小写/空格容忍、手续费同属支出侧、未分类
            bill(id = "bill-hist-2", merchant = "  瑞幸咖啡  ", type = "fee", categoryId = null),
            // 已是目标分类：跳过
            bill(id = "bill-hist-same", merchant = "瑞幸咖啡", type = "expense", categoryId = CAT_COFFEE),
            // 用户手动锁过 categoryId：跳过并计入 lockedSkippedCount
            bill(
                id = "bill-hist-locked",
                merchant = "瑞幸咖啡",
                type = "expense",
                categoryId = CAT_FOOD,
                fieldLocks = """["categoryId"]"""
            ),
            // 不兼容类型（收入/转账/退款）：不计入
            bill(id = "bill-hist-income", merchant = "瑞幸咖啡", type = "income", categoryId = CAT_INCOME),
            bill(id = "bill-hist-transfer", merchant = "瑞幸咖啡", type = "transfer", categoryId = null),
            bill(id = "bill-hist-refund", merchant = "瑞幸咖啡", type = "refund", categoryId = CAT_INCOME),
            // 其他商户：不计入
            bill(id = "bill-other", merchant = "星巴克", type = "expense", categoryId = CAT_FOOD)
        ).forEach { database.billDao().insert(it) }

        val suggestion = service.preview(
            rawMerchant = " 瑞幸咖啡 ",
            categoryId = CAT_COFFEE,
            billType = "expense",
            excludeBillId = "bill-current"
        )

        assertEquals(CategoryRuleLearningService.STATUS_CREATE, suggestion.status)
        assertEquals("瑞幸咖啡", suggestion.merchant)
        assertEquals(CAT_COFFEE, suggestion.categoryId)
        assertEquals("咖啡", suggestion.categoryName)
        assertEquals(2, suggestion.matchingHistoricalCount)
        assertEquals(1, suggestion.lockedSkippedCount)
        assertEquals(CategoryRuleLearningService.DEFAULT_LEARNED_PRIORITY, suggestion.suggestedPriority)
        assertNull(suggestion.existingRuleId)
    }

    @Test
    fun previewRejectsGenericStopwordsBlankAndIncompatibleTypes() = runBlocking {
        val invalidMerchants = listOf(null, "", "   ", "店", "123456", "微信支付", "支付宝", "未知", "WeChat")
        for (merchant in invalidMerchants) {
            val result = service.preview(merchant, CAT_COFFEE, "expense")
            assertEquals("商户 '$merchant' 应被过滤", CategoryRuleLearningService.STATUS_INELIGIBLE, result.status)
        }

        val incompatibleTypes = listOf("transfer", "repayment", "adjustment", "refund", "income")
        for (type in incompatibleTypes) {
            val result = service.preview("瑞幸咖啡", CAT_COFFEE, type)
            assertEquals("支出分类对账单类型 '$type' 应不兼容", CategoryRuleLearningService.STATUS_INELIGIBLE, result.status)
        }

        assertFalse(RuleEngine.isCategoryCompatibleWithBillType("expense", "income"))
        assertFalse(RuleEngine.isCategoryCompatibleWithBillType("expense", "refund"))
        assertFalse(RuleEngine.isCategoryCompatibleWithBillType("expense", "transfer"))
        assertTrue(RuleEngine.isCategoryCompatibleWithBillType("expense", "expense"))
        assertTrue(RuleEngine.isCategoryCompatibleWithBillType("expense", "fee"))
        assertTrue(RuleEngine.isCategoryCompatibleWithBillType("income", "income"))
    }

    @Test
    fun previewReturnsAlreadyCoveredWhenActiveRuleResolvesToSameCategory() = runBlocking {
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-contains-luckin",
                name = "瑞幸自动咖啡",
                condition = RuleEngine.CONDITION_MERCHANT_CONTAINS,
                conditionValue = "瑞幸",
                action = RuleEngine.ACTION_SET_CATEGORY,
                actionValue = CAT_COFFEE,
                priority = 100,
                isActive = true,
                hitCount = 3,
                source = RuleEntity.SOURCE_MANUAL,
                createdAt = NOW,
                updatedAt = NOW
            )
        )

        val suggestion = service.preview("瑞幸咖啡(中关村店)", CAT_COFFEE, "expense")

        assertEquals(CategoryRuleLearningService.STATUS_ALREADY_COVERED, suggestion.status)
        assertEquals("rule-contains-luckin", suggestion.existingRuleId)
        assertEquals(0, suggestion.matchingHistoricalCount)
    }

    @Test
    fun previewSuggestsUpdateWhenExactMerchantRuleHasDifferentCategory() = runBlocking {
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-exact-luckin",
                name = "瑞幸咖啡 → 餐饮",
                condition = RuleEngine.CONDITION_MERCHANT_EQUALS,
                conditionValue = "瑞幸咖啡",
                action = RuleEngine.ACTION_SET_CATEGORY,
                actionValue = CAT_FOOD,
                priority = 80,
                isActive = true,
                hitCount = 2,
                source = RuleEntity.SOURCE_FROM_CORRECTION,
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        database.billDao().insert(bill(id = "bill-old", merchant = "瑞幸咖啡", categoryId = CAT_FOOD))

        val suggestion = service.preview("瑞幸咖啡", CAT_COFFEE, "expense")

        assertEquals(CategoryRuleLearningService.STATUS_UPDATE, suggestion.status)
        assertEquals("rule-exact-luckin", suggestion.existingRuleId)
        assertEquals(CAT_FOOD, suggestion.existingCategoryId)
        assertEquals("餐饮", suggestion.existingCategoryName)
        assertEquals(1, suggestion.matchingHistoricalCount)
        assertEquals(80, suggestion.suggestedPriority)
    }

    @Test
    fun previewComputesHigherPriorityToOverrideBroaderContainsRule() = runBlocking {
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-meituan-broad",
                name = "美团统一餐饮",
                condition = RuleEngine.CONDITION_MERCHANT_CONTAINS,
                conditionValue = "美团",
                action = RuleEngine.ACTION_SET_CATEGORY,
                actionValue = CAT_FOOD,
                priority = 100,
                isActive = true,
                hitCount = 5,
                source = RuleEntity.SOURCE_MANUAL,
                createdAt = NOW,
                updatedAt = NOW
            )
        )

        val suggestion = service.preview("美团打车", CAT_TRANSPORT, "expense")

        assertEquals(CategoryRuleLearningService.STATUS_CREATE, suggestion.status)
        assertTrue(
            "新建精确商户规则优先级应高于宽泛包含规则 (100)，实际=${suggestion.suggestedPriority}",
            suggestion.suggestedPriority < 100
        )

        // 按建议优先级落库后，验证 RuleEngine 确实将"美团打车"归入交通，而"美团外卖"仍归入餐饮
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-meituan-dache",
                name = "美团打车 → 交通",
                condition = RuleEngine.CONDITION_MERCHANT_EQUALS,
                conditionValue = suggestion.merchant,
                action = RuleEngine.ACTION_SET_CATEGORY,
                actionValue = suggestion.categoryId,
                priority = suggestion.suggestedPriority,
                isActive = true,
                hitCount = 0,
                source = RuleEntity.SOURCE_FROM_CORRECTION,
                createdAt = NOW + 1000L,
                updatedAt = NOW + 1000L
            )
        )

        val engine = RuleEngine(database)
        val activeRules = database.ruleDao().getActive()
        assertEquals(
            CAT_TRANSPORT,
            engine.evaluate(RuleEngine.Subject(merchant = "美团打车"), activeRules).patch.categoryId
        )
        assertEquals(
            CAT_FOOD,
            engine.evaluate(RuleEngine.Subject(merchant = "美团外卖"), activeRules).patch.categoryId
        )
    }

    @Test
    fun bridgeDtoMapperRuleEmitsSourceAndHitCount() {
        val entity = RuleEntity(
            id = "rule-1",
            name = "瑞幸咖啡 → 咖啡",
            condition = RuleEngine.CONDITION_MERCHANT_EQUALS,
            conditionValue = "瑞幸咖啡",
            action = RuleEngine.ACTION_SET_CATEGORY,
            actionValue = CAT_COFFEE,
            priority = 90,
            isActive = true,
            hitCount = 4,
            source = RuleEntity.SOURCE_FROM_CORRECTION,
            createdAt = NOW,
            updatedAt = NOW
        )

        val json = BridgeDtoMapper.rule(entity)

        assertEquals(4, json.getInt("hitCount"))
        assertEquals(RuleEntity.SOURCE_FROM_CORRECTION, json.getString("source"))
    }
}
