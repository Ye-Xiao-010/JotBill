package com.jotbill.app.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.ChatMessageEntity
import com.jotbill.app.data.entity.ChatSessionEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiChatToolsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedNow: Long = LocalDate.of(2026, 11, 25)
        .atTime(14, 30)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private lateinit var database: JotBillDatabase
    private lateinit var tools: AiChatTools

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tools = AiChatTools(database, nowMillis = { fixedNow }, zoneId = zone)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `buildSystemPrompt 注入本地当前日期星期与启用账户清单`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 50_000L, sortOrder = 0))
        database.accountDao().insert(
            account("acc-cmb", "招商银行", "bank_debit", "asset", 200_000L, sortOrder = 1, cardTail = "8848")
        )
        database.accountDao().insert(
            account("acc-disabled", "停用账户", "cash", "asset", 10_000L, isActive = false, sortOrder = 2)
        )

        val prompt = tools.buildSystemPrompt("记一笔午饭")
        assertTrue(prompt.contains("2026-11-25（星期三）"))
        assertTrue(prompt.contains("餐饮(expense)"))
        assertTrue(prompt.contains("微信(电子钱包)"))
        assertTrue(prompt.contains("招商银行(银行卡,尾号8848)"))
        assertFalse(prompt.contains("停用账户"))
        assertTrue(prompt.contains("get_accounts"))
        assertTrue(prompt.contains("get_budgets"))
    }

    @Test
    fun `toolDefinitions 注册全部六个工具并包含扩展参数`() {
        val defs = tools.toolDefinitions()
        val names = (0 until defs.length()).map {
            defs.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertEquals(
            setOf("query_bills", "get_statistics", "get_accounts", "get_budgets", "get_goals", "create_bills"),
            names
        )

        val funcs = (0 until defs.length()).map { defs.getJSONObject(it).getJSONObject("function") }
        val createProps = funcs.first { it.getString("name") == "create_bills" }
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("bills")
            .getJSONObject("items")
            .getJSONObject("properties")
        assertTrue(createProps.has("accountName"))

        val queryProps = funcs.first { it.getString("name") == "query_bills" }
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertTrue(queryProps.has("platform"))
        assertTrue(queryProps.has("sortBy"))
        assertTrue(queryProps.has("sortDirection"))
    }

    @Test
    fun `createBillsResult 透传 accountName、拒绝非法历法日期并附带防重复调用提示`() {
        val result = tools.createBillsResult(
            """{"bills":[{"amount":28.5,"type":"expense","description":"午饭","categoryName":"餐饮","accountName":"微信","date":""}]}"""
        )
        assertTrue(result.getBoolean("success"))
        assertTrue(result.getString("nextStep").contains("不要重复调用"))
        val item = result.getJSONObject("data").getJSONArray("bills").getJSONObject(0)
        assertEquals(28.5, item.getDouble("amount"), 0.001)
        assertEquals("微信", item.getString("accountName"))
        assertEquals("2026-11-25", item.getString("date"))

        val badDate = tools.createBillsResult(
            """{"bills":[{"amount":28.5,"type":"expense","description":"午饭","categoryName":"餐饮","date":"2026-02-30"}]}"""
        )
        assertFalse(badDate.getBoolean("success"))
        assertTrue(badDate.getString("message").contains("日期无效"))
    }

    @Test
    fun `queryBillsResult 与 statisticsResult 兼容空字符串日期并拒绝非法日期`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 50_000L))
        database.billDao().insert(
            bill("b-1", 2500L, "expense", dayMillis(2026, 11, 25), "cat-food", "acc-wx", "晚餐")
        )

        val queryRes = tools.queryBillsResult("""{"startDate":"","endDate":"","categoryName":"餐饮"}""")
        assertTrue(queryRes.getBoolean("success"))
        assertEquals(1, queryRes.getJSONObject("data").getJSONArray("data").length())

        val statsRes = tools.statisticsResult("""{"startDate":"","endDate":""}""")
        assertTrue(statsRes.getBoolean("success"))
        assertEquals(25.0, statsRes.getJSONObject("data").getDouble("totalExpense"), 0.001)

        val badQuery = tools.queryBillsResult("""{"startDate":"2026-02-30"}""")
        assertFalse(badQuery.getBoolean("success"))
    }

    @Test
    fun `queryBillsResult 支持按父分类与账户尾号筛选并返回 accountName`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.categoryDao().insert(category("cat-breakfast", "早餐", "expense", parentId = "cat-food"))
        database.categoryDao().insert(category("cat-traffic", "交通", "expense"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 50_000L))
        database.accountDao().insert(
            account("acc-cmb", "招商银行", "bank_debit", "asset", 200_000L, cardTail = "8848")
        )
        database.billDao().insert(
            bill("b-1", 1800L, "expense", dayMillis(2026, 11, 24), "cat-breakfast", "acc-wx", "包子豆浆")
        )
        database.billDao().insert(
            bill("b-2", 3500L, "expense", dayMillis(2026, 11, 25), "cat-traffic", "acc-cmb", "打车")
        )

        val foodRes = tools.queryBillsResult("""{"categoryName":"餐饮"}""")
        assertTrue(foodRes.getBoolean("success"))
        val foodRows = foodRes.getJSONObject("data").getJSONArray("data")
        assertEquals(1, foodRows.length())
        assertEquals("b-1", foodRows.getJSONObject(0).getString("id"))
        assertEquals("微信", foodRows.getJSONObject(0).getString("accountName"))

        val tailRes = tools.queryBillsResult("""{"accountName":"尾号8848"}""")
        assertTrue(tailRes.getBoolean("success"))
        val tailRows = tailRes.getJSONObject("data").getJSONArray("data")
        assertEquals(1, tailRows.length())
        assertEquals("b-2", tailRows.getJSONObject(0).getString("id"))
        assertEquals("招商银行", tailRows.getJSONObject(0).getString("accountName"))
    }

    @Test
    fun `statisticsResult 返回收支汇总与按父分类聚合占比明细`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.categoryDao().insert(category("cat-lunch", "午餐", "expense", parentId = "cat-food"))
        database.categoryDao().insert(category("cat-traffic", "交通", "expense"))
        database.categoryDao().insert(category("cat-salary", "工资", "income"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 50_000L))

        database.billDao().insert(
            bill("b-1", 6000L, "expense", dayMillis(2026, 11, 10), "cat-lunch", "acc-wx", "工作餐")
        )
        database.billDao().insert(
            bill("b-2", 4000L, "expense", dayMillis(2026, 11, 12), "cat-traffic", "acc-wx", "地铁")
        )
        database.billDao().insert(
            bill("b-3", 500_000L, "income", dayMillis(2026, 11, 15), "cat-salary", "acc-wx", "11月工资")
        )

        val res = tools.statisticsResult("""{"startDate":"2026-11-01","endDate":"2026-11-30"}""")
        assertTrue(res.getBoolean("success"))
        val data = res.getJSONObject("data")
        assertEquals(5000.0, data.getDouble("totalIncome"), 0.001)
        assertEquals(100.0, data.getDouble("totalExpense"), 0.001)
        assertEquals(4900.0, data.getDouble("balance"), 0.001)

        val expCats = data.getJSONArray("expenseByCategory")
        assertEquals(2, expCats.length())
        assertEquals("餐饮", expCats.getJSONObject(0).getString("categoryName"))
        assertEquals(60.0, expCats.getJSONObject(0).getDouble("amount"), 0.001)
        assertEquals(60.0, expCats.getJSONObject(0).getDouble("percentage"), 0.001)
    }

    @Test
    fun `accountsResult 与 budgetsResult 准确聚合启用账户净资产及预算进度`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 100_000L, sortOrder = 0))
        database.accountDao().insert(
            account("acc-cc", "招行信用卡", "credit_card", "liability", -30_000L, sortOrder = 1, cardTail = "6688")
        )
        database.accountDao().insert(
            account("acc-off", "旧卡", "bank_debit", "asset", 99_900L, isActive = false, sortOrder = 2)
        )
        database.budgetDao().insert(
            BudgetEntity(
                id = "bud-1",
                name = "本月总预算",
                amountMinor = 100_000L,
                period = "monthly",
                startDay = 1,
                categoryId = null,
                alertAt = 80,
                isActive = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        database.billDao().insert(
            bill("b-1", 40_000L, "expense", dayMillis(2026, 11, 10), "cat-food", "acc-wx", "聚餐")
        )
        database.billDao().insert(
            bill("b-2", 5_000L, "refund", dayMillis(2026, 11, 12), "cat-food", "acc-wx", "部分退款")
        )

        val accRes = tools.accountsResult()
        assertTrue(accRes.getBoolean("success"))
        val accData = accRes.getJSONObject("data")
        assertEquals(2, accData.getInt("accountCount"))
        assertEquals(1000.0, accData.getDouble("totalAssets"), 0.001)
        assertEquals(300.0, accData.getDouble("totalLiabilities"), 0.001)
        assertEquals(700.0, accData.getDouble("netWorth"), 0.001)

        val budRes = tools.budgetsResult()
        assertTrue(budRes.getBoolean("success"))
        val budArr = budRes.getJSONObject("data").getJSONArray("budgets")
        assertEquals(1, budArr.length())
        val bud = budArr.getJSONObject(0)
        assertEquals("本月总预算", bud.getString("name"))
        assertEquals(1000.0, bud.getDouble("limit"), 0.001)
        assertEquals(350.0, bud.getDouble("spent"), 0.001)
        assertEquals(650.0, bud.getDouble("remaining"), 0.001)
        assertEquals(35, bud.getInt("progressPct"))
        assertFalse(bud.getBoolean("isOverBudget"))
    }

    @Test
    fun `queryBillsResult 支持按金额降序与平台筛选，statisticsResult 返回平台分布与月度趋势，goalsResult 返回目标进度`() = runBlocking {
        database.categoryDao().insert(category("cat-food", "餐饮", "expense"))
        database.accountDao().insert(account("acc-wx", "微信", "e_wallet", "asset", 50_000L))
        database.billDao().insert(
            bill("b-oct", 12_000L, "expense", dayMillis(2026, 10, 15), "cat-food", "acc-wx", "十月聚餐", platform = "美团")
        )
        database.billDao().insert(
            bill("b-nov-small", 1_500L, "expense", dayMillis(2026, 11, 20), "cat-food", "acc-wx", "早餐", platform = "美团")
        )
        database.billDao().insert(
            bill("b-nov-big", 50_000L, "expense", dayMillis(2026, 11, 18), "cat-food", "acc-wx", "买耳机", platform = "京东")
        )
        database.financialGoalDao().insert(
            FinancialGoalEntity(
                id = "goal-1",
                name = "应急备用金",
                targetAmountMinor = 1_000_000L,
                currentAmountMinor = 400_000L,
                targetDate = dayMillis(2026, 12, 25),
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        // 按金额降序查大额账单
        val topAmount = tools.queryBillsResult("""{"sortBy":"amount","sortDirection":"desc","limit":2}""")
        assertTrue(topAmount.getBoolean("success"))
        val topRows = topAmount.getJSONObject("data").getJSONArray("data")
        assertEquals("b-nov-big", topRows.getJSONObject(0).getString("id"))
        assertEquals("京东", topRows.getJSONObject(0).getString("platform"))

        // 按平台筛选美团
        val meituan = tools.queryBillsResult("""{"platform":"美团"}""")
        assertTrue(meituan.getBoolean("success"))
        assertEquals(2, meituan.getJSONObject("data").getJSONArray("data").length())

        // 统计返回平台分布和跨月趋势
        val stats = tools.statisticsResult("""{"startDate":"2026-10-01","endDate":"2026-11-30"}""")
        assertTrue(stats.getBoolean("success"))
        val statsData = stats.getJSONObject("data")
        val platforms = statsData.getJSONArray("platformBreakdown")
        assertEquals(2, platforms.length())
        assertEquals("京东", platforms.getJSONObject(0).getString("platform"))
        val trends = statsData.getJSONArray("monthlyTrends")
        assertEquals(2, trends.length())
        assertEquals("2026-10", trends.getJSONObject(0).getString("month"))
        assertEquals(120.0, trends.getJSONObject(0).getDouble("expense"), 0.001)
        assertEquals("2026-11", trends.getJSONObject(1).getString("month"))
        assertEquals(515.0, trends.getJSONObject(1).getDouble("expense"), 0.001)

        // 财务目标进度
        val goalsRes = tools.goalsResult()
        assertTrue(goalsRes.getBoolean("success"))
        val goalJson = goalsRes.getJSONObject("data").getJSONArray("goals").getJSONObject(0)
        assertEquals("应急备用金", goalJson.getString("name"))
        assertEquals(40, goalJson.getInt("progressPct"))
        assertEquals(6000.0, goalJson.getDouble("remaining"), 0.001)
        assertEquals("2026-12-25", goalJson.getString("targetDate"))
        assertEquals(30, goalJson.getInt("daysLeft"))
        assertFalse(goalJson.getBoolean("isCompleted"))
    }

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun category(
        id: String,
        name: String,
        type: String,
        parentId: String? = null
    ) = CategoryEntity(
        id = id,
        name = name,
        type = type,
        icon = "utensils",
        color = null,
        isDefault = false,
        sortOrder = 0,
        createdAt = 1L,
        updatedAt = 1L,
        parentId = parentId
    )

    private fun account(
        id: String,
        name: String,
        subtype: String,
        kind: String,
        balanceMinor: Long,
        isActive: Boolean = true,
        sortOrder: Int = 0,
        cardTail: String? = null
    ) = AccountEntity(
        id = id,
        name = name,
        subtype = subtype,
        kind = kind,
        initialBalanceMinor = balanceMinor,
        currentBalanceMinor = balanceMinor,
        currency = "CNY",
        icon = null,
        color = null,
        sortOrder = sortOrder,
        isActive = isActive,
        createdAt = 1L,
        updatedAt = 1L,
        cardTail = cardTail
    )

    @Test
    fun `chat_messages 持久化工具结果、还原 JSON 并从 LLM 历史上下文中安全过滤 role=tool`() = runBlocking {
        val sessionId = "sess-tool-history"
        database.chatDao().insertSession(
            ChatSessionEntity(
                id = sessionId,
                title = "工具卡片恢复测试",
                aiProviderId = null,
                isActive = true,
                isPinned = false,
                createdAt = fixedNow,
                updatedAt = fixedNow
            )
        )
        val statsPayload = JSONObject()
            .put("success", true)
            .put("message", "本月共支出 128 元")
            .put("data", JSONObject().put("totalExpense", 128).put("totalIncome", 0))

        database.chatDao().appendMessage(
            ChatMessageEntity(
                id = "m-user",
                sessionId = sessionId,
                seqNum = 1,
                role = "user",
                content = "本月花了多少",
                imageDataJson = null,
                createdAt = fixedNow
            ),
            fixedNow
        )
        database.chatDao().appendMessage(
            ChatMessageEntity(
                id = "m-tool",
                sessionId = sessionId,
                seqNum = 2,
                role = "tool",
                content = "本月共支出 128 元",
                imageDataJson = null,
                createdAt = fixedNow + 10,
                toolCallId = "call_stats_1",
                toolName = "get_statistics",
                toolResultJson = statsPayload.toString()
            ),
            fixedNow + 10
        )
        database.chatDao().appendMessage(
            ChatMessageEntity(
                id = "m-assistant",
                sessionId = sessionId,
                seqNum = 3,
                role = "assistant",
                content = "你本月总支出 128 元。",
                imageDataJson = null,
                createdAt = fixedNow + 20
            ),
            fixedNow + 20
        )

        val stored = database.chatDao().getMessages(sessionId)
        assertEquals(3, stored.size)

        // 1. 传给 LLM 的上下文严格过滤掉 role=tool，避免孤立 tool 消息触发 400
        val llmHistory = AiChatModule.filterHistoryForLlm(stored)
        assertEquals(listOf("user", "assistant"), llmHistory.map { it.role })

        // 2. messageToJson 完整还原 toolCallId / toolName / 结构化 toolResult
        val toolJson = AiChatModule.messageToJson(stored[1])
        assertEquals("tool", toolJson.getString("role"))
        assertEquals("call_stats_1", toolJson.getString("toolCallId"))
        assertEquals("get_statistics", toolJson.getString("toolName"))
        val restoredResult = toolJson.getJSONObject("toolResult")
        assertTrue(restoredResult.getBoolean("success"))
        assertEquals(128, restoredResult.getJSONObject("data").getInt("totalExpense"))

        // 普通文本消息不携带 toolResult
        val userJson = AiChatModule.messageToJson(stored[0])
        assertTrue(userJson.isNull("toolResult"))
        assertTrue(userJson.isNull("toolName"))
    }

    private fun bill(
        id: String,
        amountMinor: Long,
        type: String,
        occurredAt: Long,
        categoryId: String?,
        accountId: String?,
        description: String?,
        platform: String? = null
    ) = BillEntity(
        id = id,
        amountMinor = amountMinor,
        type = type,
        occurredAt = occurredAt,
        description = description,
        categoryId = categoryId,
        accountId = accountId,
        targetAccountId = null,
        platform = platform,
        merchant = null,
        productDescription = null,
        sourceType = "manual",
        originalTransactionId = null,
        orderId = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        version = 1
    )
}
