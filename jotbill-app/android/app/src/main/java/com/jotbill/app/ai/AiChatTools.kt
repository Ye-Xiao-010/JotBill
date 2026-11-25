package com.jotbill.app.ai

import com.jotbill.app.budget.BudgetPeriods
import com.jotbill.app.budget.BudgetProgressCalculator
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.dao.escapeLikeKeyword
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.model.CategoryStatRow
import com.jotbill.app.data.model.PlatformStatRow
import com.jotbill.app.data.model.TrendRow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.round

/**
 * AI 对话系统提示词组装与只读/待确认工具执行器。
 * 独立于 ReactApplicationContext，便于在 JVM + Room 内存库下直接做完整单测。
 */
internal class AiChatTools(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    fun toolDefinitions(): JSONArray = JSONArray()
        .put(queryBillsTool())
        .put(getStatisticsTool())
        .put(getAccountsTool())
        .put(getBudgetsTool())
        .put(getGoalsTool())
        .put(createBillsTool())

    suspend fun buildSystemPrompt(latestContent: String): String {
        val today = currentLocalDate()
        val prompt = StringBuilder(
            "你是招财猫猫的本地记账助手。使用简体中文回答。" +
                "当前本地日期：${today}（${chineseWeekday(today.dayOfWeek)}）。" +
                "所有“今天、昨天、前天、本周、本月、上个月”等相对日期必须严格基于当前本地日期推算。" +
                "你只能读取用户提供或系统工具返回的本地数据，不得声称已经删除、修改或保存账单。" +
                "查询明细账单时调用 query_bills（支持按日期、类型、关键词、分类、账户、平台筛选，以及按日期或金额排序）；" +
                "统计收支总额、分类占比、消费平台分布与月度趋势时调用 get_statistics；" +
                "查询账户余额、总资产与净资产时调用 get_accounts；" +
                "查询预算限额与使用进度时调用 get_budgets；" +
                "查询财务/攒钱目标进度时调用 get_goals；" +
                "需要记账时必须调用 create_bills 工具生成待确认账单；create_bills 返回成功后待确认卡片已展示给用户，请直接用文字提示用户核对并保存，切勿重复调用 create_bills。" +
                "收到图片时提取金额、平台、商户、商品说明、支付账户和日期，并调用 create_bills；不确定的字段明确说明。"
        )

        // 注入本地真实分类清单，避免模型凭空臆造近义分类名导致 categoryId 丢失
        runCatching {
            val names = database.categoryDao().getAll()
                .filter { it.type == "income" || it.type == "expense" }
                .sortedWith(compareBy({ it.type }, { it.sortOrder }))
                .joinToString("、") { "${it.name}(${it.type})" }
            if (names.isNotBlank()) {
                prompt.append("\n可用的账单分类（categoryName 必须原样使用其中一项）：")
                    .append(names)
            }
        }

        // 注入本地启用中的资金账户，便于记账时识别账户、查询时按账户筛选
        runCatching {
            val accounts = database.accountDao().getAll()
                .filter { it.isActive }
                .sortedBy { it.sortOrder }
            if (accounts.isNotEmpty()) {
                val formatted = accounts.joinToString("、") { account ->
                    val subtypeText = subtypeLabel(account.subtype)
                    val tail = account.cardTail?.takeIf { it.isNotBlank() }?.let { ",尾号$it" }.orEmpty()
                    "${account.name}($subtypeText$tail)"
                }
                prompt.append("\n可用的资金账户（用户提及支付方式或账户时，accountName 优先原样使用其中一项）：")
                    .append(formatted)
            }
        }

        if (QUERY_KEYWORDS.any(latestContent::contains)) {
            val recentBills = database.billDao().query(
                type = null,
                categoryId = null,
                accountId = null,
                platform = null,
                merchant = null,
                sourceType = null,
                startDate = null,
                endDate = null,
                keyword = null,
                orderBy = "date",
                orderDirection = "desc",
                limit = 20,
                offset = 0
            )
            prompt.append("\n以下是最多 20 条本地账单摘要，仅用于回答本次查询：")
            if (recentBills.isEmpty()) {
                prompt.append("\n暂无账单。")
            } else {
                recentBills.forEach { bill ->
                    val date = Instant.ofEpochMilli(bill.occurredAt)
                        .atZone(zoneId)
                        .toLocalDate()
                    prompt.append("\n- ")
                        .append(date)
                        .append(' ')
                        .append(bill.type)
                        .append(' ')
                        // 三处口径统一为「元」，避免与 create_bills.amount 单位混淆导致 100 倍金额错误
                        .append(formatYuan(bill.amountMinor))
                        .append("元")
                    bill.merchant?.let { prompt.append(" 商户:").append(it.take(80)) }
                    bill.description?.let { prompt.append(" 说明:").append(it.take(120)) }
                }
            }
        }
        return prompt.toString()
    }

    suspend fun executeTool(name: String?, arguments: String): JSONObject = when (name) {
        "query_bills" -> queryBillsResult(arguments)
        "get_statistics" -> statisticsResult(arguments)
        "get_accounts" -> accountsResult()
        "get_budgets" -> budgetsResult()
        "get_goals" -> goalsResult()
        "create_bills" -> createBillsResult(arguments)
        else -> JSONObject()
            .put("success", false)
            .put("message", "不支持的工具调用: ${name ?: "unknown"}")
    }

    suspend fun queryBillsResult(arguments: String): JSONObject {
        return try {
            val input = JSONObject(arguments.ifBlank { "{}" })
            val type = input.optionalString("type")
            require(type == null || type in BILL_TYPES)
            val sortBy = input.optionalString("sortBy") ?: "date"
            val sortDirection = input.optionalString("sortDirection") ?: "desc"
            require(sortBy in setOf("date", "amount"))
            require(sortDirection in setOf("asc", "desc"))
            val platform = input.optionalString("platform")?.take(50)
            val limit = input.optInt("limit", 5).coerceIn(1, MAX_QUERY_BILLS)
            val startDateText = input.optionalString("startDate")
            val endDateText = input.optionalString("endDate")
            val startDate = dateBoundary(startDateText, endOfDay = false)
            val endDate = dateBoundary(endDateText, endOfDay = true)
            require(startDateText == null || startDate != null)
            require(endDateText == null || endDate != null)
            require(startDate == null || endDate == null || startDate <= endDate)

            val allCategories = database.categoryDao().getAll()
            val allAccounts = database.accountDao().getAll()
            val categoryNames = allCategories.associate { it.id to it.name }
            val accountNames = allAccounts.associate { it.id to it.name }

            val categoryNameFilter = input.optionalString("categoryName")
            val matchedCategoryIds = categoryNameFilter?.let { resolveCategoryIds(it, allCategories) }
            if (categoryNameFilter != null && matchedCategoryIds.isNullOrEmpty()) {
                return JSONObject()
                    .put("success", true)
                    .put("data", JSONObject().put("data", JSONArray()))
                    .put("message", "未找到名为「$categoryNameFilter」的分类")
            }

            val accountNameFilter = input.optionalString("accountName")
            val matchedAccountIds = accountNameFilter?.let { resolveAccountIds(it, allAccounts) }
            if (accountNameFilter != null && matchedAccountIds.isNullOrEmpty()) {
                return JSONObject()
                    .put("success", true)
                    .put("data", JSONObject().put("data", JSONArray()))
                    .put("message", "未找到名为「$accountNameFilter」的账户")
            }

            val singleCategoryId = matchedCategoryIds?.takeIf { it.size == 1 }?.first()
            val singleAccountId = matchedAccountIds?.takeIf { it.size == 1 }?.first()
            val needsMemoryFilter = (matchedCategoryIds != null && matchedCategoryIds.size > 1) ||
                (matchedAccountIds != null && matchedAccountIds.size > 1)

            val queried = database.billDao().query(
                type = type,
                categoryId = singleCategoryId,
                accountId = singleAccountId,
                platform = platform,
                merchant = null,
                sourceType = null,
                startDate = startDate,
                endDate = endDate,
                keyword = escapeLikeKeyword(input.optionalString("keyword")?.take(100)),
                orderBy = sortBy,
                orderDirection = sortDirection,
                limit = if (needsMemoryFilter) MAX_MULTI_FILTER_SCAN else limit,
                offset = 0
            )

            val bills = queried
                .asSequence()
                .filter { bill ->
                    matchedCategoryIds == null || (bill.categoryId != null && bill.categoryId in matchedCategoryIds)
                }
                .filter { bill ->
                    matchedAccountIds == null || (bill.accountId != null && bill.accountId in matchedAccountIds)
                }
                .take(limit)
                .toList()

            val rows = JSONArray().also { array ->
                bills.forEach { bill ->
                    val desc = bill.description?.takeIf { it.isNotBlank() }
                        ?: bill.productDescription?.takeIf { it.isNotBlank() }
                        ?: bill.merchant?.takeIf { it.isNotBlank() }
                        ?: ""
                    array.put(
                        JSONObject()
                            .put("id", bill.id)
                            .put("amount", bill.amountMinor / 100.0)
                            .put("type", bill.type)
                            .put(
                                "date",
                                Instant.ofEpochMilli(bill.occurredAt)
                                    .atZone(zoneId)
                                    .toLocalDate()
                                    .toString()
                            )
                            .put("description", desc)
                            .put("categoryName", bill.categoryId?.let(categoryNames::get) ?: "")
                            .put("accountName", bill.accountId?.let(accountNames::get) ?: "")
                            .put("platform", bill.platform ?: "")
                    )
                }
            }
            JSONObject()
                .put("success", true)
                .put("data", JSONObject().put("data", rows))
                .put("message", if (bills.isEmpty()) "未查询到账单" else "查询到 ${bills.size} 条账单")
        } catch (_: Exception) {
            JSONObject().put("success", false).put("message", "账单查询参数无效")
        }
    }

    suspend fun statisticsResult(arguments: String): JSONObject = try {
        val input = JSONObject(arguments.ifBlank { "{}" })
        val startDateText = input.optionalString("startDate")
        val endDateText = input.optionalString("endDate")
        val parsedStart = dateBoundary(startDateText, endOfDay = false)
        val parsedEnd = dateBoundary(endDateText, endOfDay = true)
        require(startDateText == null || parsedStart != null)
        require(endDateText == null || parsedEnd != null)
        val startDate = parsedStart ?: 0L
        val endDate = parsedEnd ?: nowMillis()
        require(startDate <= endDate)

        val summary = database.billDao().getSummary(startDate, endDate)
        val expenseCategories = database.billDao().getCategoryStats("expense", startDate, endDate)
        val incomeCategories = database.billDao().getCategoryStats("income", startDate, endDate)
        val platformStats = database.billDao().getPlatformStats(startDate, endDate)
        val monthlyTrendRows = database.billDao().getMonthlyTrends(startDate, endDate)

        val data = JSONObject()
            .put("totalIncome", summary.totalIncome / 100.0)
            .put("totalExpense", summary.totalExpense / 100.0)
            .put("balance", (summary.totalIncome - summary.totalExpense) / 100.0)
            .put("incomeCount", summary.incomeCount)
            .put("expenseCount", summary.expenseCount)
            .put("expenseByCategory", buildCategoryBreakdown(expenseCategories, summary.totalExpense))
            .put("incomeByCategory", buildCategoryBreakdown(incomeCategories, summary.totalIncome))
            .put("platformBreakdown", buildPlatformBreakdown(platformStats, summary.totalExpense))
            .put("monthlyTrends", buildMonthlyTrends(monthlyTrendRows))
        startDateText?.let { data.put("startDate", it.take(10)) }
        endDateText?.let { data.put("endDate", it.take(10)) }

        JSONObject()
            .put("success", true)
            .put("data", data)
            .put("message", "统计完成")
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "统计参数无效")
    }

    suspend fun accountsResult(): JSONObject = try {
        val activeAccounts = database.accountDao().getAll()
            .filter { it.isActive }
            .sortedBy { it.sortOrder }
        val netWorthRow = database.accountDao().getNetWorth()
        val totalAssets = netWorthRow.totalAssets
        val totalLiabilities = netWorthRow.totalLiabilities
        val netWorth = totalAssets - totalLiabilities

        val accountRows = JSONArray().also { array ->
            activeAccounts.forEach { account ->
                array.put(
                    JSONObject()
                        .put("id", account.id)
                        .put("name", account.name)
                        .put("subtype", account.subtype)
                        .put("subtypeLabel", subtypeLabel(account.subtype))
                        .put("kind", account.kind)
                        .put("balance", account.currentBalanceMinor / 100.0)
                        .put("currency", account.currency)
                        .apply {
                            account.cardTail?.takeIf { it.isNotBlank() }?.let { put("cardTail", it) }
                        }
                )
            }
        }

        val data = JSONObject()
            .put("accounts", accountRows)
            .put("totalAssets", totalAssets / 100.0)
            .put("totalLiabilities", totalLiabilities / 100.0)
            .put("netWorth", netWorth / 100.0)
            .put("accountCount", activeAccounts.size)

        val message = if (activeAccounts.isEmpty()) {
            "暂无启用中的资金账户"
        } else {
            "共 ${activeAccounts.size} 个启用账户，净资产 ${formatYuan(netWorth)} 元"
        }

        JSONObject()
            .put("success", true)
            .put("data", data)
            .put("message", message)
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "查询账户信息失败")
    }

    suspend fun budgetsResult(): JSONObject = try {
        val now = nowMillis()
        val activeBudgets = database.budgetDao().getActive()
        val categoryNames = database.categoryDao().getAll().associate { it.id to it.name }

        val rows = JSONArray().also { array ->
            activeBudgets.forEach { budget ->
                val snapshot = BudgetProgressCalculator.compute(database, budget, now)
                val periodLabel = BudgetPeriods.label(budget, snapshot.range, zoneId)
                array.put(
                    JSONObject()
                        .put("id", budget.id)
                        .put("name", budget.name)
                        .put("categoryName", budget.categoryId?.let(categoryNames::get) ?: "总预算")
                        .put("period", budget.period)
                        .put("periodLabel", periodLabel)
                        .put("limit", budget.amountMinor / 100.0)
                        .put("spent", snapshot.spentMinor / 100.0)
                        .put("remaining", snapshot.remainingMinor / 100.0)
                        .put("progressPct", snapshot.progressPct)
                        .put("isOverBudget", snapshot.isOverBudget)
                        .put("alertAt", budget.alertAt)
                )
            }
        }

        val data = JSONObject()
            .put("budgets", rows)
            .put("count", activeBudgets.size)

        val message = if (activeBudgets.isEmpty()) {
            "当前未设置启用中的预算"
        } else {
            "共 ${activeBudgets.size} 项启用预算"
        }

        JSONObject()
            .put("success", true)
            .put("data", data)
            .put("message", message)
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "查询预算进度失败")
    }

    suspend fun goalsResult(): JSONObject = try {
        val today = currentLocalDate()
        val goals = database.financialGoalDao().getAll()
        val rows = JSONArray().also { array ->
            goals.forEach { goal ->
                val remainingMinor = (goal.targetAmountMinor - goal.currentAmountMinor).coerceAtLeast(0L)
                val progressPct = if (goal.targetAmountMinor > 0L) {
                    ((goal.currentAmountMinor * 100L) / goal.targetAmountMinor)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                } else if (goal.currentAmountMinor > 0L) {
                    100
                } else {
                    0
                }
                val isCompleted = goal.targetAmountMinor > 0L && goal.currentAmountMinor >= goal.targetAmountMinor
                val targetLocalDate = goal.targetDate?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
                }
                val item = JSONObject()
                    .put("id", goal.id)
                    .put("name", goal.name)
                    .put("target", goal.targetAmountMinor / 100.0)
                    .put("current", goal.currentAmountMinor / 100.0)
                    .put("remaining", remainingMinor / 100.0)
                    .put("progressPct", progressPct)
                    .put("isCompleted", isCompleted)
                targetLocalDate?.let { date ->
                    item.put("targetDate", date.toString())
                    item.put("daysLeft", ChronoUnit.DAYS.between(today, date).toInt())
                }
                array.put(item)
            }
        }
        val data = JSONObject()
            .put("goals", rows)
            .put("count", goals.size)
        val message = if (goals.isEmpty()) "当前未设置财务目标" else "共 ${goals.size} 项财务目标"
        JSONObject()
            .put("success", true)
            .put("data", data)
            .put("message", message)
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "查询财务目标失败")
    }

    fun createBillsResult(arguments: String): JSONObject {
        return try {
            val source = JSONObject(arguments).optJSONArray("bills") ?: JSONArray()
            val bills = JSONArray()
            for (index in 0 until minOf(source.length(), MAX_PROPOSED_BILLS)) {
                val bill = source.optJSONObject(index) ?: continue
                val amount = bill.optDouble("amount", 0.0)
                if (!amount.isFinite() || amount <= 0) continue
                // 非法 type/日期不静默兜底：静默改写会让模型与用户对入账内容产生错误预期，
                // 返回明确失败让模型修正后重试
                val type = bill.optString("type")
                if (type != "income" && type != "expense") {
                    return JSONObject()
                        .put("success", false)
                        .put("message", "第 ${index + 1} 条账单类型无效（仅支持 income 或 expense），请修正后重新调用")
                }
                val dateText = bill.optionalString("date").orEmpty()
                val resolvedDate = if (dateText.isEmpty()) {
                    currentLocalDate().toString()
                } else {
                    parseValidCalendarDate(dateText)
                        ?: return JSONObject()
                            .put("success", false)
                            .put("message", "第 ${index + 1} 条账单日期无效（应为有效 YYYY-MM-DD 日期），请修正后重新调用")
                }
                val item = JSONObject()
                    .put("amount", amount)
                    .put("type", type)
                    .put("description", bill.optString("description").take(200))
                    .put("categoryName", bill.optString("categoryName", "其他").take(50))
                    .put("date", resolvedDate)
                bill.optionalString("accountName")?.take(50)?.let {
                    item.put("accountName", it)
                }
                bills.put(item)
            }
            if (bills.length() == 0) {
                JSONObject().put("success", false).put("message", "未识别到有效账单")
            } else {
                JSONObject()
                    .put("success", true)
                    .put("data", JSONObject().put("bills", bills))
                    .put("message", "已整理 ${bills.length()} 条待确认账单")
                    .put("nextStep", "待确认账单卡片已展示给用户，请直接用简洁中文提醒用户核对并保存，不要重复调用 create_bills。")
            }
        } catch (_: Exception) {
            JSONObject().put("success", false).put("message", "AI 返回的账单格式无效")
        }
    }

    private fun buildCategoryBreakdown(rows: List<CategoryStatRow>, totalMinor: Long): JSONArray =
        JSONArray().also { array ->
            rows.take(MAX_CATEGORY_BREAKDOWN).forEach { row ->
                val pct = if (totalMinor > 0L) {
                    round(row.amountMinor * 1000.0 / totalMinor) / 10.0
                } else {
                    0.0
                }
                array.put(
                    JSONObject()
                        .put("categoryName", row.categoryName?.takeIf { it.isNotBlank() } ?: "未分类")
                        .put("categoryIcon", row.categoryIcon ?: "")
                        .put("amount", row.amountMinor / 100.0)
                        .put("count", row.count)
                        .put("percentage", pct)
                )
            }
        }

    private fun buildPlatformBreakdown(rows: List<PlatformStatRow>, totalExpenseMinor: Long): JSONArray =
        JSONArray().also { array ->
            rows.take(MAX_PLATFORM_BREAKDOWN).forEach { row ->
                val name = row.platform?.takeIf { it.isNotBlank() } ?: return@forEach
                val pct = if (totalExpenseMinor > 0L) {
                    round(row.amountMinor * 1000.0 / totalExpenseMinor) / 10.0
                } else {
                    0.0
                }
                array.put(
                    JSONObject()
                        .put("platform", name)
                        .put("amount", row.amountMinor / 100.0)
                        .put("count", row.count)
                        .put("percentage", pct)
                )
            }
        }

    private fun buildMonthlyTrends(rows: List<TrendRow>): JSONArray {
        val byMonth = linkedMapOf<String, Pair<Long, Long>>()
        rows.forEach { row ->
            val current = byMonth[row.period] ?: (0L to 0L)
            byMonth[row.period] = when (row.type) {
                "income" -> (current.first + row.amountMinor) to current.second
                "expense" -> current.first to (current.second + row.amountMinor)
                else -> current
            }
        }
        return JSONArray().also { array ->
            byMonth.entries.toList().takeLast(MAX_MONTHLY_TRENDS).forEach { (month, pair) ->
                val (incomeMinor, expenseMinor) = pair
                array.put(
                    JSONObject()
                        .put("month", month)
                        .put("income", incomeMinor / 100.0)
                        .put("expense", expenseMinor / 100.0)
                        .put("balance", (incomeMinor - expenseMinor) / 100.0)
                )
            }
        }
    }

    private fun resolveCategoryIds(nameQuery: String, categories: List<CategoryEntity>): Set<String> {
        val needle = nameQuery.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptySet()
        val exact = categories.filter { it.name.trim().lowercase(Locale.ROOT) == needle }
        val base = exact.ifEmpty {
            categories.filter { it.name.trim().lowercase(Locale.ROOT).contains(needle) }
        }
        if (base.isEmpty()) return emptySet()
        val matchedIds = base.mapTo(linkedSetOf()) { it.id }
        // 选中父分类时自动包含其子分类，与统计口径保持一致
        categories.filter { it.parentId != null && it.parentId in matchedIds }
            .forEach { matchedIds.add(it.id) }
        return matchedIds
    }

    private fun resolveAccountIds(nameQuery: String, accounts: List<AccountEntity>): Set<String> {
        val needle = nameQuery.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptySet()
        val exact = accounts.filter { it.name.trim().lowercase(Locale.ROOT) == needle }
        if (exact.isNotEmpty()) return exact.mapTo(linkedSetOf()) { it.id }
        val tailMatches = accounts.filter {
            !it.cardTail.isNullOrBlank() && needle.contains(it.cardTail.lowercase(Locale.ROOT))
        }
        if (tailMatches.isNotEmpty()) return tailMatches.mapTo(linkedSetOf()) { it.id }
        return accounts
            .filter {
                val accName = it.name.trim().lowercase(Locale.ROOT)
                accName.contains(needle) || needle.contains(accName)
            }
            .mapTo(linkedSetOf()) { it.id }
    }

    private fun createBillsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "create_bills")
                .put("description", "整理一到多条待用户确认的账单，不直接写入账本")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "bills",
                                JSONObject()
                                    .put("type", "array")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "amount",
                                                        JSONObject()
                                                            .put("type", "number")
                                                            .put(
                                                                "description",
                                                                "金额，单位：元（不是分）。必须大于 0，最多 7 位整数位"
                                                            )
                                                            .put("minimum", 0.01)
                                                            .put("maximum", 9999999)
                                                    )
                                                    .put(
                                                        "type",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("enum", JSONArray(listOf("income", "expense")))
                                                    )
                                                    .put("description", JSONObject().put("type", "string"))
                                                    .put("categoryName", JSONObject().put("type", "string"))
                                                    .put(
                                                        "accountName",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put(
                                                                "description",
                                                                "支付或入账账户名称（可选，优先原样使用可用资金账户列表中的名称）"
                                                            )
                                                    )
                                                    .put(
                                                        "date",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("description", "交易日期，格式 YYYY-MM-DD")
                                                    )
                                            )
                                            .put(
                                                "required",
                                                JSONArray(listOf("amount", "type", "description", "categoryName", "date"))
                                            )
                                    )
                            )
                        )
                        .put("required", JSONArray(listOf("bills")))
                )
        )

    private fun queryBillsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "query_bills")
                .put("description", "只读查询本地账单，支持按日期、类型、关键词、分类、账户或消费平台筛选，并支持按日期或金额排序，最多返回 20 条")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "type",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(BILL_TYPES.toList()))
                                )
                                .put("keyword", JSONObject().put("type", "string"))
                                .put(
                                    "categoryName",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "按分类名称筛选，如餐饮、交通")
                                )
                                .put(
                                    "accountName",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "按账户名称筛选，如微信、支付宝、招商银行")
                                )
                                .put(
                                    "platform",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "按消费平台筛选，如美团、京东、淘宝、拼多多")
                                )
                                .put(
                                    "sortBy",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("date", "amount")))
                                        .put("description", "排序字段：date 按日期，amount 按金额，默认 date")
                                )
                                .put(
                                    "sortDirection",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("desc", "asc")))
                                        .put("description", "排序方向：desc 降序（最新或最大），asc 升序，默认 desc")
                                )
                                .put(
                                    "startDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "endDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", MAX_QUERY_BILLS)
                                )
                        )
                )
        )

    private fun getStatisticsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "get_statistics")
                .put("description", "只读统计指定日期范围内的本地收入、支出、结余、各分类占比、消费平台分布及按月收支趋势")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "startDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "endDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                        )
                )
        )

    private fun getAccountsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "get_accounts")
                .put("description", "只读查询本地启用中的资金账户余额列表、总资产、总负债和净资产")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
        )

    private fun getBudgetsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "get_budgets")
                .put("description", "只读查询本地启用中的预算限额、当前周期已用金额、剩余金额和使用进度")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
        )

    private fun getGoalsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "get_goals")
                .put("description", "只读查询本地财务/攒钱目标的目标金额、已存金额、剩余差额、完成进度和截止日期")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
        )

    private fun dateBoundary(value: String?, endOfDay: Boolean): Long? {
        if (value == null) return null
        return runCatching {
            val date = LocalDate.parse(value.take(10))
            val boundary = if (endOfDay) date.plusDays(1).atStartOfDay() else date.atStartOfDay()
            boundary.atZone(zoneId).toInstant().toEpochMilli() - if (endOfDay) 1 else 0
        }.getOrNull()
    }

    private fun parseValidCalendarDate(value: String): String? {
        val normalized = value.trim().take(10)
        if (!normalized.matches(DATE_PATTERN)) return null
        return runCatching { LocalDate.parse(normalized).toString() }.getOrNull()
    }

    private fun currentLocalDate(): LocalDate =
        Instant.ofEpochMilli(nowMillis()).atZone(zoneId).toLocalDate()

    private fun formatYuan(minor: Long): String =
        String.format(Locale.ROOT, "%.2f", minor / 100.0)

    private fun chineseWeekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }

    private fun subtypeLabel(subtype: String): String = when (subtype) {
        "cash" -> "现金"
        "e_wallet" -> "电子钱包"
        "bank_debit" -> "银行卡"
        "credit_card" -> "信用卡"
        "consumer_loan" -> "消费信贷"
        else -> "其他"
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_PROPOSED_BILLS = 10
        const val MAX_QUERY_BILLS = 20
        const val MAX_MULTI_FILTER_SCAN = 100
        const val MAX_CATEGORY_BREAKDOWN = 10
        const val MAX_PLATFORM_BREAKDOWN = 8
        const val MAX_MONTHLY_TRENDS = 12
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        val BILL_TYPES = setOf("income", "expense", "transfer", "refund", "repayment", "fee", "adjustment")
        val QUERY_KEYWORDS = listOf("查询", "查账", "账单", "统计", "最近", "今天", "昨天", "本月", "这个月", "多少", "支出", "收入")
    }
}
