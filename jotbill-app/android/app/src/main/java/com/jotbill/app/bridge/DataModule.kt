package com.jotbill.app.bridge

import androidx.room.withTransaction
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.LedgerValidationException
import com.jotbill.app.data.seed.DefaultDataInitializer
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.rules.FieldPriorityResolver
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.data.model.CategoryStatRow
import com.jotbill.app.data.model.PlatformStatRow
import com.jotbill.app.data.model.TrendRow
import com.jotbill.app.rules.RuleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 本地优先的数据桥接层。方法使用 JSON 字符串作为边界，保持 JS 封装层的稳定契约。
 */
class DataModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = NativeEventEmitter(reactContext)
    private val database: JotBillDatabase by lazy {
        JotBillDatabaseProvider.get(reactContext)
    }
    private val repository: LedgerRepository by lazy {
        LedgerRepository(database)
    }
    private val ruleEngine: RuleEngine by lazy {
        RuleEngine(database)
    }

    override fun getName(): String = "Data"

    @ReactMethod
    fun getBills(filterJson: String?, promise: Promise) = launch(promise) {
        val filter = parseJson(filterJson)
        val page = filter.optInt("page", 1).coerceAtLeast(1)
        val limit = filter.optInt("limit", 20).coerceIn(1, 100)
        val startDate = optionalFilterTimestamp(filter, "startDate", "startDateMinor", endOfDate = false)
        val endDate = optionalFilterTimestamp(filter, "endDate", "endDateMinor", endOfDate = true)
        if (startDate != null && endDate != null && startDate > endDate) {
            throw BridgeException("INVALID_ARGUMENT", "startDate must be before endDate")
        }

        val type = filter.optionalString("type")
        val categoryId = filter.optionalString("categoryId")
        val accountId = filter.optionalString("accountId")
        val platform = filter.optionalString("platform")
        val merchant = filter.optionalString("merchant")
        val sourceType = filter.optionalString("sourceType")
        val keyword = filter.optionalString("keyword")
        val orderBy = filter.optString("orderBy", "date").takeIf {
            it in setOf("date", "amount", "createdAt")
        } ?: "date"
        val orderDirection = filter.optString("orderDirection", "desc").takeIf {
            it in setOf("asc", "desc")
        } ?: "desc"
        val offset = ((page - 1).toLong() * limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val bills = database.billDao().query(
            type = type,
            categoryId = categoryId,
            accountId = accountId,
            platform = platform,
            merchant = merchant,
            sourceType = sourceType,
            startDate = startDate,
            endDate = endDate,
            keyword = keyword,
            orderBy = orderBy,
            orderDirection = orderDirection,
            limit = limit,
            offset = offset
        )
        val total = database.billDao().countFiltered(
            type = type,
            categoryId = categoryId,
            accountId = accountId,
            platform = platform,
            merchant = merchant,
            sourceType = sourceType,
            startDate = startDate,
            endDate = endDate,
            keyword = keyword
        )

        JSONObject().apply {
            put("items", JSONArray().also { array -> bills.forEach { array.put(BridgeDtoMapper.bill(it)) } })
            put("total", total)
            put("page", page)
            put("limit", limit)
            put("hasMore", page * limit < total)
        }.toString()
    }

    @ReactMethod
    fun getBillById(id: String, promise: Promise) = launch(promise) {
        val bill = database.billDao().getById(requireId(id, "bill"))
            ?: throw BridgeException("NOT_FOUND", "Bill $id was not found")
        BridgeDtoMapper.bill(bill).toString()
    }

    @ReactMethod
    fun addBill(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val now = System.currentTimeMillis()
        input.optionalMinor("amountMinor")
        val bill = BridgeDtoMapper.parseBillInput(input, now, UUID.randomUUID().toString())
        repository.createBillWithBalanceUpdate(bill)
        events.emit("onBillCreated", BridgeDtoMapper.bill(bill))
        BridgeDtoMapper.bill(bill).toString()
    }

    @ReactMethod
    fun updateBill(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.billDao().getById(requireId(id, "bill"))
            ?: throw BridgeException("NOT_FOUND", "Bill $id was not found")
        if (existing.type == "adjustment") {
            throw BridgeException("ADJUSTMENT_IMMUTABLE", "Adjustment bills cannot be edited")
        }
        val input = parseJson(inputJson)
        val requestedVersion = input.optionalLong("version")?.toInt()
            ?: throw BridgeException("INVALID_ARGUMENT", "Missing required field: version")
        if (existing.version != requestedVersion) {
            throw BridgeException("VERSION_CONFLICT", "Bill $id has changed; refresh before updating")
        }

        val type = input.optionalString("type") ?: existing.type
        BridgeDtoMapper.validateBillType(type)
        if (type == "adjustment") {
            throw BridgeException("ADJUSTMENT_IMMUTABLE", "Adjustment bills cannot be edited")
        }
        val sourceType = input.optionalString("sourceType") ?: existing.sourceType
        BridgeDtoMapper.validateSourceType(sourceType)
        // 阶段五 T5：可选 fieldLocks 数组输入（经现有 JSON 输入通道，RN 契约暂不声明，
        // 字段级锁定：用户编辑可增删锁定集合；校验字段名必须在可锁清单内）
        val fieldLocks = if (input.has("fieldLocks")) {
            val locksArray = input.optJSONArray("fieldLocks")
                ?: throw BridgeException("INVALID_ARGUMENT", "fieldLocks must be an array of field names")
            val requestedLocks = buildSet {
                for (index in 0 until locksArray.length()) {
                    locksArray.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            val invalid = FieldPriorityResolver.invalidLockFields(requestedLocks)
            if (invalid.isNotEmpty()) {
                throw BridgeException(
                    "INVALID_ARGUMENT",
                    "Unknown lockable fields: ${invalid.joinToString()}"
                )
            }
            FieldPriorityResolver.serializeLocks(requestedLocks)
        } else {
            existing.fieldLocks
        }
        val updated = existing.copy(
            amountMinor = input.optionalMinor("amountMinor") ?: existing.amountMinor,
            type = type,
            occurredAt = if (input.hasValue("occurredAt")) {
                BridgeDtoMapper.requiredTimestamp(input, "occurredAt")
            } else {
                existing.occurredAt
            },
            description = input.updatedNullableString("description", existing.description),
            categoryId = input.updatedNullableString("categoryId", existing.categoryId),
            accountId = input.updatedNullableString("accountId", existing.accountId),
            targetAccountId = input.updatedNullableString("targetAccountId", existing.targetAccountId),
            platform = input.updatedNullableString("platform", existing.platform),
            merchant = input.updatedNullableString("merchant", existing.merchant),
            productDescription = input.updatedNullableString("productDescription", existing.productDescription),
            sourceType = sourceType,
            originalTransactionId = input.updatedNullableString(
                "originalTransactionId",
                existing.originalTransactionId
            ),
            orderId = input.updatedNullableString("orderId", existing.orderId),
            fieldLocks = fieldLocks,
            updatedAt = System.currentTimeMillis(),
            version = existing.version + 1
        )
        if (updated.amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }
        if (!repository.updateBillWithBalanceUpdate(existing, updated)) {
            if (database.billDao().getById(existing.id) == null) {
                throw BridgeException("NOT_FOUND", "Bill $id was not found")
            }
            throw BridgeException("VERSION_CONFLICT", "Bill $id has changed; refresh before updating")
        }
        events.emit("onBillUpdated", BridgeDtoMapper.bill(updated))
        BridgeDtoMapper.bill(updated).toString()
    }

    @ReactMethod
    fun deleteBill(id: String, promise: Promise) = launch(promise) {
        val bill = database.billDao().getById(requireId(id, "bill"))
            ?: throw BridgeException("NOT_FOUND", "Bill $id was not found")
        if (bill.type == "adjustment") {
            throw BridgeException("ADJUSTMENT_IMMUTABLE", "Adjustment bills cannot be deleted")
        }
        if (!repository.deleteBillWithBalanceUpdate(bill)) {
            throw BridgeException("NOT_FOUND", "Bill $id was not found")
        }
        events.emit("onBillDeleted", JSONObject().put("id", bill.id))
        null
    }

    @ReactMethod
    fun getBillStatistics(filterJson: String?, promise: Promise) = launch(promise) {
        val filter = parseJson(filterJson)
        val startDate = optionalFilterTimestamp(filter, "startDate", "startDateMinor", endOfDate = false) ?: 0L
        val endDate = optionalFilterTimestamp(
            filter,
            "endDate",
            "endDateMinor",
            endOfDate = true
        ) ?: System.currentTimeMillis()
        if (startDate > endDate) {
            throw BridgeException("INVALID_ARGUMENT", "startDate must be before endDate")
        }

        val billDao = database.billDao()
        val summary = billDao.getSummary(startDate, endDate)
        val expenseRows = billDao.getCategoryStats("expense", startDate, endDate)
        val incomeRows = billDao.getCategoryStats("income", startDate, endDate)
        val platformRows = billDao.getPlatformStats(startDate, endDate)
        val dailyRows = billDao.getDailyTrends(startDate, endDate)
        val monthlyRows = billDao.getMonthlyTrends(startDate, endDate)

        JSONObject().apply {
            put("totalIncome", summary.totalIncome)
            put("totalExpense", summary.totalExpense)
            put("balance", summary.totalIncome - summary.totalExpense)
            put("incomeCount", summary.incomeCount)
            put("expenseCount", summary.expenseCount)
            put("expenseCategoryStats", categoryStats(expenseRows, summary.totalExpense))
            put("incomeCategoryStats", categoryStats(incomeRows, summary.totalIncome))
            put("platformStats", platformStats(platformRows, summary.totalExpense))
            put("dailyTrends", trendStats(dailyRows, monthly = false))
            put("monthlyTrends", trendStats(monthlyRows, monthly = true))
        }.toString()
    }

    @ReactMethod
    fun getAccounts(promise: Promise) = launch(promise) {
        JSONArray().also { array ->
            database.accountDao().getAll().forEach { array.put(BridgeDtoMapper.account(it)) }
        }.toString()
    }

    @ReactMethod
    fun getAccountById(id: String, promise: Promise) = launch(promise) {
        val account = database.accountDao().getById(requireId(id, "account"))
            ?: throw BridgeException("NOT_FOUND", "Account $id was not found")
        BridgeDtoMapper.account(account).toString()
    }

    @ReactMethod
    fun addAccount(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val now = System.currentTimeMillis()
        val subtype = BridgeDtoMapper.requiredString(input, "subtype")
        validateAccountSubtype(subtype)
        val kind = resolveAccountKind(input, subtype)
        val initialBalance = normalizeInitialBalance(
            kind,
            input.optionalMinor("initialBalanceMinor") ?: 0L
        )
        val account = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            subtype = subtype,
            kind = kind,
            initialBalanceMinor = initialBalance,
            currentBalanceMinor = initialBalance,
            currency = input.optionalString("currency") ?: "CNY",
            icon = input.optionalString("icon"),
            color = input.optionalString("color"),
            sortOrder = input.optInt("sortOrder", 0),
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
        database.accountDao().insert(account)
        events.emit("onAccountChanged", BridgeDtoMapper.account(account))
        BridgeDtoMapper.account(account).toString()
    }

    @ReactMethod
    fun updateAccount(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val accountId = requireId(id, "account")
        val input = parseJson(inputJson)
        val updated = database.withTransaction {
            val existing = database.accountDao().getById(accountId)
                ?: throw BridgeException("NOT_FOUND", "Account $id was not found")
            val subtype = input.optionalString("subtype") ?: existing.subtype
            validateAccountSubtype(subtype)
            val kind = resolveAccountKind(input, subtype)
            if (kind != existing.kind) {
                throw BridgeException(
                    "INVALID_ARGUMENT",
                    "Account kind cannot be changed after creation"
                )
            }
            val updated = existing.copy(
                name = input.optionalString("name") ?: existing.name,
                subtype = subtype,
                kind = kind,
                currency = input.optionalString("currency") ?: existing.currency,
                icon = input.updatedNullableString("icon", existing.icon),
                color = input.updatedNullableString("color", existing.color),
                sortOrder = input.optionalInt("sortOrder") ?: existing.sortOrder,
                isActive = input.optionalBoolean("isActive") ?: existing.isActive,
                updatedAt = System.currentTimeMillis()
            )
            database.accountDao().update(updated)
            updated
        }
        events.emit("onAccountChanged", BridgeDtoMapper.account(updated))
        BridgeDtoMapper.account(updated).toString()
    }

    @ReactMethod
    fun deleteAccount(id: String, promise: Promise) = launch(promise) {
        val accountId = requireId(id, "account")
        val account = database.withTransaction {
            val existing = database.accountDao().getById(accountId)
                ?: throw BridgeException("NOT_FOUND", "Account $id was not found")
            val deactivated = existing.copy(
                isActive = false,
                updatedAt = System.currentTimeMillis()
            )
            database.accountDao().update(deactivated)
            deactivated
        }
        events.emit("onAccountChanged", BridgeDtoMapper.account(account).apply {
            put("deactivated", true)
        })
        null
    }

    @ReactMethod
    fun calibrateAccountBalance(
        id: String,
        actualBalanceMinor: Double,
        note: String?,
        promise: Promise
    ) = launch(promise) {
        val accountId = requireId(id, "account")
        val existingAccount = database.accountDao().getById(accountId)
            ?: throw BridgeException("NOT_FOUND", "Account $id was not found")
        val actual = normalizeBalance(
            existingAccount.kind,
            requireMinorUnit(actualBalanceMinor, "actualBalanceMinor")
        )
        val calibration = repository.calibrateAccountBalance(accountId, actual, note)
        val updatedAccount = database.accountDao().getById(accountId)
            ?: throw BridgeException("NOT_FOUND", "Account $id was not found")
        events.emit("onAccountChanged", BridgeDtoMapper.account(updatedAccount))
        BridgeDtoMapper.calibration(calibration).toString()
    }

    @ReactMethod
    fun getNetWorth(promise: Promise) = launch(promise) {
        val row = database.accountDao().getNetWorth()
        JSONObject().apply {
            put("totalAssetsMinor", row.totalAssets)
            put("totalLiabilitiesMinor", row.totalLiabilities)
            put("netWorthMinor", row.totalAssets - row.totalLiabilities)
            put("accountCount", database.accountDao().activeCount())
            put("updatedAt", BridgeDtoMapper.formatTimestamp(System.currentTimeMillis()))
        }.toString()
    }

    @ReactMethod
    fun getBalanceCalibrations(accountId: String, promise: Promise) = launch(promise) {
        val id = requireId(accountId, "account")
        JSONArray().also { array ->
            database.balanceCalibrationDao().getByAccount(id)
                .forEach { array.put(BridgeDtoMapper.calibration(it)) }
        }.toString()
    }

    @ReactMethod
    fun getCategories(type: String?, promise: Promise) = launch(promise) {
        val categories = if (type.isNullOrBlank()) {
            database.categoryDao().getAll()
        } else {
            if (type !in setOf("income", "expense")) {
                throw BridgeException("INVALID_ARGUMENT", "Unsupported category type: $type")
            }
            database.categoryDao().getByType(type)
        }
        JSONArray().also { array -> categories.forEach { array.put(BridgeDtoMapper.category(it)) } }.toString()
    }

    @ReactMethod
    fun addCategory(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val type = BridgeDtoMapper.requiredString(input, "type")
        validateCategoryType(type)
        val now = System.currentTimeMillis()
        val category = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            type = type,
            icon = input.optionalString("icon"),
            color = input.optionalString("color"),
            isDefault = false,
            sortOrder = input.optInt("sortOrder", 0),
            createdAt = now,
            updatedAt = now
        )
        database.categoryDao().insert(category)
        events.emit("onCategoryChanged", BridgeDtoMapper.category(category))
        BridgeDtoMapper.category(category).toString()
    }

    @ReactMethod
    fun updateCategory(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.categoryDao().getById(requireId(id, "category"))
            ?: throw BridgeException("NOT_FOUND", "Category $id was not found")
        if (existing.isDefault) {
            throw BridgeException("INVALID_ARGUMENT", "Default categories cannot be edited")
        }
        val input = parseJson(inputJson)
        val type = input.optionalString("type") ?: existing.type
        validateCategoryType(type)
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            type = type,
            icon = input.updatedNullableString("icon", existing.icon),
            color = input.updatedNullableString("color", existing.color),
            sortOrder = input.optionalInt("sortOrder") ?: existing.sortOrder,
            updatedAt = System.currentTimeMillis()
        )
        database.categoryDao().update(updated)
        events.emit("onCategoryChanged", BridgeDtoMapper.category(updated))
        BridgeDtoMapper.category(updated).toString()
    }

    @ReactMethod
    fun deleteCategory(id: String, promise: Promise) = launch(promise) {
        val category = database.withTransaction {
            val existing = database.categoryDao().getById(requireId(id, "category"))
                ?: throw BridgeException("NOT_FOUND", "Category $id was not found")
            if (existing.isDefault) {
                throw BridgeException("INVALID_ARGUMENT", "Default categories cannot be deleted")
            }
            if (database.billDao().countByCategoryId(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "Category $id is referenced by bills")
            }
            database.categoryDao().delete(existing)
            existing
        }
        events.emit("onCategoryChanged", JSONObject().put("id", category.id).put("deleted", true))
        null
    }

    @ReactMethod
    fun initDefaultCategories(promise: Promise) = launch(promise) {
        DefaultDataInitializer(database).initializeIfFirstRun()
        null
    }

    @ReactMethod
    fun getBudgets(promise: Promise) = launch(promise) {
        JSONArray().also { array ->
            database.budgetDao().getActive().forEach { array.put(BridgeDtoMapper.budget(it)) }
        }.toString()
    }

    @ReactMethod
    fun addBudget(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val period = input.optionalString("period") ?: "monthly"
        validateBudgetPeriod(period)
        val amountMinor = input.optionalLong("amountMinor")
            ?: throw BridgeException("INVALID_ARGUMENT", "Missing required field: amountMinor")
        if (amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }
        val alertAt = input.optInt("alertAt", 80).coerceIn(0, 100)
        val now = System.currentTimeMillis()
        val budget = BudgetEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            amountMinor = amountMinor,
            period = period,
            categoryId = input.optionalString("categoryId"),
            alertAt = alertAt,
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
        database.budgetDao().insert(budget)
        events.emit("onBudgetChanged", BridgeDtoMapper.budget(budget))
        BridgeDtoMapper.budget(budget).toString()
    }

    @ReactMethod
    fun updateBudget(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.budgetDao().getById(requireId(id, "budget"))
            ?: throw BridgeException("NOT_FOUND", "Budget $id was not found")
        val input = parseJson(inputJson)
        val period = input.optionalString("period") ?: existing.period
        validateBudgetPeriod(period)
        val amountMinor = input.optionalLong("amountMinor") ?: existing.amountMinor
        if (amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            amountMinor = amountMinor,
            period = period,
            categoryId = input.updatedNullableString("categoryId", existing.categoryId),
            alertAt = input.optionalInt("alertAt")?.coerceIn(0, 100) ?: existing.alertAt,
            isActive = input.optionalBoolean("isActive") ?: existing.isActive,
            updatedAt = System.currentTimeMillis()
        )
        database.budgetDao().update(updated)
        events.emit("onBudgetChanged", BridgeDtoMapper.budget(updated))
        BridgeDtoMapper.budget(updated).toString()
    }

    @ReactMethod
    fun deleteBudget(id: String, promise: Promise) = launch(promise) {
        val budget = database.budgetDao().getById(requireId(id, "budget"))
            ?: throw BridgeException("NOT_FOUND", "Budget $id was not found")
        database.budgetDao().delete(budget)
        events.emit("onBudgetChanged", JSONObject().put("id", budget.id).put("deleted", true))
        null
    }

    @ReactMethod
    fun getBudgetProgress(promise: Promise) = launch(promise) {
        val now = System.currentTimeMillis()
        JSONArray().also { array ->
            database.budgetDao().getActive().forEach { budget ->
                val (startDate, endDate) = budgetPeriodRange(budget.period, now)
                val spentMinor = database.billDao().sumExpenses(budget.categoryId, startDate, endDate)
                val progress = if (budget.amountMinor == 0L) {
                    if (spentMinor > 0L) 1.0 else 0.0
                } else {
                    spentMinor.toDouble() / budget.amountMinor.toDouble()
                }
                array.put(BridgeDtoMapper.budget(budget).apply {
                    put("spentMinor", spentMinor)
                    put("remainingMinor", budget.amountMinor - spentMinor)
                    put("progress", progress)
                    put("isOverBudget", spentMinor > budget.amountMinor)
                    put("needsAlert", progress * 100.0 >= budget.alertAt)
                })
            }
        }.toString()
    }

    @ReactMethod
    fun getFinancialGoals(promise: Promise) = launch(promise) {
        JSONArray().also { array ->
            database.financialGoalDao().getAll().forEach { array.put(BridgeDtoMapper.financialGoal(it)) }
        }.toString()
    }

    @ReactMethod
    fun addFinancialGoal(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val now = System.currentTimeMillis()
        val goal = FinancialGoalEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            targetAmountMinor = input.optionalLong("targetAmountMinor")
                ?: throw BridgeException("INVALID_ARGUMENT", "Missing required field: targetAmountMinor"),
            currentAmountMinor = 0L,
            targetDate = if (input.hasValue("targetDate")) {
                BridgeDtoMapper.parseOptionalTimestamp(input, "targetDate")
            } else {
                null
            },
            createdAt = now,
            updatedAt = now
        )
        if (goal.targetAmountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "targetAmountMinor must not be negative")
        }
        database.financialGoalDao().insert(goal)
        events.emit("onFinancialGoalChanged", BridgeDtoMapper.financialGoal(goal))
        BridgeDtoMapper.financialGoal(goal).toString()
    }

    @ReactMethod
    fun updateFinancialGoal(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.financialGoalDao().getById(requireId(id, "financial goal"))
            ?: throw BridgeException("NOT_FOUND", "Financial goal $id was not found")
        val input = parseJson(inputJson)
        val targetAmountMinor = input.optionalLong("targetAmountMinor") ?: existing.targetAmountMinor
        if (targetAmountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "targetAmountMinor must not be negative")
        }
        val currentAmountMinor = input.optionalLong("currentAmountMinor") ?: existing.currentAmountMinor
        if (currentAmountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "currentAmountMinor must not be negative")
        }
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            targetAmountMinor = targetAmountMinor,
            currentAmountMinor = currentAmountMinor.coerceAtMost(targetAmountMinor),
            targetDate = if (input.has("targetDate")) {
                if (input.isNull("targetDate")) null else BridgeDtoMapper.parseOptionalTimestamp(input, "targetDate")
            } else {
                existing.targetDate
            },
            updatedAt = System.currentTimeMillis()
        )
        database.financialGoalDao().update(updated)
        events.emit("onFinancialGoalChanged", BridgeDtoMapper.financialGoal(updated))
        BridgeDtoMapper.financialGoal(updated).toString()
    }

    @ReactMethod
    fun deleteFinancialGoal(id: String, promise: Promise) = launch(promise) {
        val goal = database.financialGoalDao().getById(requireId(id, "financial goal"))
            ?: throw BridgeException("NOT_FOUND", "Financial goal $id was not found")
        database.financialGoalDao().delete(goal)
        events.emit("onFinancialGoalChanged", JSONObject().put("id", goal.id).put("deleted", true))
        null
    }

    @ReactMethod
    fun getRules(promise: Promise) = launch(promise) {
        JSONArray().also { array ->
            database.ruleDao().getAll().forEach { array.put(BridgeDtoMapper.rule(it)) }
        }.toString()
    }

    @ReactMethod
    fun addRule(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val now = System.currentTimeMillis()
        val rule = RuleEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            condition = BridgeDtoMapper.requiredString(input, "condition"),
            conditionValue = BridgeDtoMapper.requiredString(input, "conditionValue"),
            action = BridgeDtoMapper.requiredString(input, "action"),
            actionValue = input.optionalString("actionValue"),
            priority = input.optInt("priority", 0),
            isActive = true,
            // 阶段五 T1：记录创建来源；from_correction 表示用户修正记录后创建，其余按 manual
            source = if (input.optionalString("source") == RuleEntity.SOURCE_FROM_CORRECTION) {
                RuleEntity.SOURCE_FROM_CORRECTION
            } else {
                RuleEntity.SOURCE_MANUAL
            },
            createdAt = now,
            updatedAt = now
        )
        database.ruleDao().insert(rule)
        events.emit("onRuleChanged", BridgeDtoMapper.rule(rule))
        BridgeDtoMapper.rule(rule).toString()
    }

    @ReactMethod
    fun updateRule(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.ruleDao().getById(requireId(id, "rule"))
            ?: throw BridgeException("NOT_FOUND", "Rule $id was not found")
        val input = parseJson(inputJson)
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            condition = input.optionalString("condition") ?: existing.condition,
            conditionValue = input.optionalString("conditionValue") ?: existing.conditionValue,
            action = input.optionalString("action") ?: existing.action,
            actionValue = input.updatedNullableString("actionValue", existing.actionValue),
            priority = input.optionalInt("priority") ?: existing.priority,
            isActive = input.optionalBoolean("isActive") ?: existing.isActive,
            // 阶段五 T1：来源可随修正更新；非法值保持原值
            source = when (input.optionalString("source")) {
                RuleEntity.SOURCE_MANUAL -> RuleEntity.SOURCE_MANUAL
                RuleEntity.SOURCE_FROM_CORRECTION -> RuleEntity.SOURCE_FROM_CORRECTION
                else -> existing.source
            },
            updatedAt = System.currentTimeMillis()
        )
        database.ruleDao().update(updated)
        events.emit("onRuleChanged", BridgeDtoMapper.rule(updated))
        BridgeDtoMapper.rule(updated).toString()
    }

    @ReactMethod
    fun deleteRule(id: String, promise: Promise) = launch(promise) {
        val rule = database.ruleDao().getById(requireId(id, "rule"))
            ?: throw BridgeException("NOT_FOUND", "Rule $id was not found")
        database.ruleDao().delete(rule)
        events.emit("onRuleChanged", JSONObject().put("id", rule.id).put("deleted", true))
        null
    }

    @ReactMethod
    fun applyRulesToExisting(ruleId: String?, promise: Promise) = launch(promise) {
        throw BridgeException(
            "NOT_IMPLEMENTED",
            "Applying rules requires the reconciliation engine step"
        )
    }

    @ReactMethod
    fun getPendingTransactions(filterJson: String?, promise: Promise) = launch(promise) {
        val filter = parseJson(filterJson)
        val page = filter.optInt("page", 1).coerceAtLeast(1)
        val limit = filter.optInt("limit", 20).coerceIn(1, 100)
        val offset = ((page - 1).toLong() * limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pending = database.pendingTransactionDao().getPage(limit, offset)
        val total = database.pendingTransactionDao().count()
        JSONObject().apply {
            put("items", JSONArray().also { array -> pending.forEach { array.put(BridgeDtoMapper.pending(it)) } })
            put("total", total)
            put("page", page)
            put("limit", limit)
            put("hasMore", page.toLong() * limit < total.toLong())
        }.toString()
    }

    @ReactMethod
    fun getPendingTransaction(id: String, promise: Promise) = launch(promise) {
        val pending = database.pendingTransactionDao().getById(requireId(id, "pending transaction"))
            ?: throw BridgeException("NOT_FOUND", "Pending transaction $id was not found")
        BridgeDtoMapper.pending(pending).toString()
    }

    @ReactMethod
    fun ignorePendingTransaction(id: String, version: Int, promise: Promise) = launch(promise) {
        val pending = database.pendingTransactionDao().getById(requireId(id, "pending transaction"))
            ?: throw BridgeException("NOT_FOUND", "Pending transaction $id was not found")
        // 阶段四 T6：基于 version 的乐观锁忽略；标记为 ignored 保留记录供审计，不物理删除
        if (!repository.ignorePendingTransaction(pending, version)) {
            throw BridgeException("VERSION_CONFLICT", "Pending transaction $id has changed")
        }
        events.emit("onPendingChanged", JSONObject().put("pendingId", pending.id).put("ignored", true))
        null
    }

    @ReactMethod
    fun confirmPendingTransaction(
        id: String,
        inputJson: String,
        version: Int,
        promise: Promise
    ) = launch(promise) {
        val pending = database.pendingTransactionDao().getById(requireId(id, "pending transaction"))
            ?: throw BridgeException("NOT_FOUND", "Pending transaction $id was not found")
        if (pending.version != version) {
            throw BridgeException("VERSION_CONFLICT", "Pending transaction $id has changed")
        }
        val input = parseJson(inputJson)
        val now = System.currentTimeMillis()
        val type = input.optionalString("type") ?: pending.type
        val sourceType = input.optionalString("sourceType") ?: pending.sourceType
        BridgeDtoMapper.validateBillType(type)
        BridgeDtoMapper.validateSourceType(sourceType)
        val amountMinor = input.optionalMinor("amountMinor") ?: pending.amountMinor
        if (amountMinor !in 1L..MAX_SAFE_MINOR_UNIT) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must be a positive safe integer minor unit")
        }
        // 阶段五 T1 时机二：用户确认入账前最终执行规则。
        // 字段优先级：用户显式输入 > 规则补全 > 待确认既有字段（证据/AI）；
        // ignore/send_to_review 不阻断用户确认（用户明确操作优先级最高），仅记录命中。
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = amountMinor,
                platform = pending.platform,
                merchant = pending.merchant,
                description = pending.productDescription,
                sourceType = sourceType
            )
        )
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch
                ?: RuleEngine.FieldPatch()
        )
        // 确认入账必须使用正数安全整数金额，AI 失败占位记录只能忽略或重试。
        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amountMinor,
            type = type,
            occurredAt = if (input.hasValue("occurredAt")) {
                BridgeDtoMapper.requiredTimestamp(input, "occurredAt")
            } else {
                pending.occurredAt
            },
            description = input.updatedNullableString("description", null),
            categoryId = input.optionalString("categoryId")
                ?: rulePatch.categoryId
                ?: pending.categoryId,
            accountId = input.optionalString("accountId")
                ?: rulePatch.accountId
                ?: pending.accountId,
            targetAccountId = input.optionalString("targetAccountId"),
            platform = input.optionalString("platform")
                ?: rulePatch.platform
                ?: pending.platform,
            merchant = input.optionalString("merchant")
                ?: rulePatch.merchant
                ?: pending.merchant,
            productDescription = input.optionalString("productDescription") ?: pending.productDescription,
            sourceType = sourceType,
            // 来源会话 ID 固定为 pending 绑定值，避免请求改写幂等边界。
            originalTransactionId = pending.sessionId,
            orderId = input.optionalString("orderId") ?: pending.orderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )
        // 事务内乐观锁确认：版本不一致返回 false，Bill 不写入、余额不更新。
        // 阶段五 T6：确认入账前执行去重（第 1 级命中合并进既有账单，不产生第二笔），
        // settledBill 为实际落库账单（新建或合并目标），事件与返回值均以它为准
        val confirmResult = repository.confirmPendingTransaction(pending, bill)
        if (!confirmResult.success) {
            throw BridgeException("VERSION_CONFLICT", "Pending transaction $id has changed")
        }
        val settledBill = confirmResult.committed ?: confirmResult.mergedInto ?: bill
        events.emit("onPendingChanged", JSONObject().put("pendingId", pending.id).put("deleted", true))
        events.emit("onBillCreated", BridgeDtoMapper.bill(settledBill))
        BridgeDtoMapper.bill(settledBill).toString()
    }

    private fun categoryStats(rows: List<CategoryStatRow>, total: Long): JSONArray =
        JSONArray().also { array ->
            rows.forEach { row ->
                array.put(JSONObject().apply {
                    put("categoryId", row.categoryId ?: "")
                    put("categoryName", row.categoryName ?: "")
                    if (row.categoryIcon != null) put("categoryIcon", row.categoryIcon)
                    put("amountMinor", row.amountMinor)
                    put("percentage", percentage(row.amountMinor, total))
                    put("count", row.count)
                })
            }
        }

    private fun platformStats(rows: List<PlatformStatRow>, total: Long): JSONArray =
        JSONArray().also { array ->
            rows.forEach { row ->
                array.put(JSONObject().apply {
                    put("platform", row.platform ?: "")
                    put("amountMinor", row.amountMinor)
                    put("percentage", percentage(row.amountMinor, total))
                    put("count", row.count)
                })
            }
        }

    private fun trendStats(rows: List<TrendRow>, monthly: Boolean): JSONArray {
        val grouped = linkedMapOf<String, JSONObject>()
        rows.forEach { row ->
            val trend = grouped.getOrPut(row.period) {
                JSONObject().apply {
                    if (monthly) {
                        put("month", row.period)
                        put("year", row.period.substringBefore('-').toInt())
                    } else {
                        put("date", row.period)
                    }
                    put("income", 0L)
                    put("expense", 0L)
                }
            }
            trend.put(row.type, row.amountMinor)
        }
        return JSONArray().also { array -> grouped.values.forEach(array::put) }
    }

    private fun percentage(amount: Long, total: Long): Double =
        if (total == 0L) 0.0 else amount.toDouble() * 100.0 / total.toDouble()

    private fun optionalFilterTimestamp(
        filter: JSONObject,
        dateKey: String,
        millisKey: String,
        endOfDate: Boolean
    ): Long? {
        if (filter.hasValue(millisKey)) {
            return BridgeDtoMapper.parseOptionalTimestamp(filter, millisKey)
        }
        return if (filter.hasValue(dateKey)) {
            BridgeDtoMapper.parseDateBoundary(filter.optString(dateKey), endOfDate)
        } else {
            null
        }
    }

    private fun parseJson(json: String?): JSONObject {
        if (json.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(json)
        } catch (error: Exception) {
            throw BridgeException("INVALID_ARGUMENT", "Invalid JSON input", error)
        }
    }

    private fun requireId(id: String?, resource: String): String {
        val normalized = id?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw BridgeException("INVALID_ARGUMENT", "$resource id must not be empty")
        }
        return normalized
    }

    private fun validateAccountSubtype(subtype: String) {
        if (subtype !in setOf("cash", "e_wallet", "bank_debit", "credit_card", "consumer_loan", "other")) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported account subtype: $subtype")
        }
    }

    private fun resolveAccountKind(input: JSONObject, subtype: String): String {
        val derivedKind = accountKind(subtype)
        val requestedKind = input.optionalString("kind")
        if (requestedKind != null && requestedKind != derivedKind) {
            throw BridgeException("INVALID_ARGUMENT", "Account kind must match subtype")
        }
        return derivedKind
    }

    private fun normalizeInitialBalance(kind: String, balanceMinor: Long): Long {
        if (kind == "asset" && balanceMinor < 0L) {
            throw BridgeException("INVALID_ARGUMENT", "Asset initial balance must not be negative")
        }
        return normalizeBalance(kind, balanceMinor)
    }

    private fun normalizeBalance(kind: String, balanceMinor: Long): Long {
        return if (kind == "liability" && balanceMinor > 0L) -balanceMinor else balanceMinor
    }

    private fun requireMinorUnit(value: Double, field: String): Long {
        if (
            value.isNaN() ||
            value.isInfinite() ||
            value % 1.0 != 0.0 ||
            value > MAX_SAFE_MINOR_UNIT.toDouble() ||
            value < -MAX_SAFE_MINOR_UNIT.toDouble()
        ) {
            throw BridgeException("INVALID_ARGUMENT", "Field $field must be a safe integer minor unit")
        }
        return value.toLong()
    }

    private fun accountKind(subtype: String): String = when (subtype) {
        "credit_card", "consumer_loan" -> "liability"
        else -> "asset"
    }

    private fun validateBudgetPeriod(period: String) {
        if (period !in setOf("monthly", "yearly")) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported budget period: $period")
        }
    }

    private fun budgetPeriodRange(period: String, now: Long): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDate = when (period) {
            "monthly" -> today.withDayOfMonth(1)
            "yearly" -> today.withDayOfYear(1)
            else -> throw BridgeException("INVALID_ARGUMENT", "Unsupported budget period: $period")
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

    private fun validateCategoryType(type: String) {
        if (type !in setOf("income", "expense")) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported category type: $type")
        }
    }

    private fun launch(promise: Promise, action: suspend () -> Any?) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                promise.resolve(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BridgeException) {
                promise.reject(error.code, error.message, error)
            } catch (error: LedgerValidationException) {
                promise.reject("INVALID_ARGUMENT", error.message, error)
            } catch (error: Exception) {
                val code = dataErrorCode(error.message)
                promise.reject(code, error.message ?: "Local data operation failed", error)
            }
        }
    }
}

private const val MAX_SAFE_MINOR_UNIT = 9_007_199_254_740_991L

internal fun dataErrorCode(message: String?): String {
    return if (
        message?.contains("UNIQUE", ignoreCase = true) == true ||
        message?.contains("FOREIGN KEY", ignoreCase = true) == true
    ) {
        "DATA_CONFLICT"
    } else {
        "DB_ERROR"
    }
}

private fun JSONObject.hasValue(key: String): Boolean = has(key) && !isNull(key)

private fun JSONObject.optionalString(key: String): String? {
    if (!hasValue(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}

private fun JSONObject.optionalLong(key: String): Long? {
    if (!hasValue(key)) return null
    return try {
        getLong(key)
    } catch (error: Exception) {
        throw BridgeException("INVALID_ARGUMENT", "Field $key must be an integer", error)
    }
}

private fun JSONObject.optionalMinor(key: String): Long? {
    if (!hasValue(key)) return null
    val value = get(key)
    return when (value) {
        is Byte, is Short, is Int -> (value as Number).toLong()
        is Long -> {
            if (value !in -MAX_SAFE_MINOR_UNIT..MAX_SAFE_MINOR_UNIT) {
                throw BridgeException("INVALID_ARGUMENT", "Field $key must be a safe integer minor unit")
            }
            value
        }
        is Float -> requireMinorUnitValue(value.toDouble(), key)
        is Double -> requireMinorUnitValue(value, key)
        else -> throw BridgeException("INVALID_ARGUMENT", "Field $key must be an integer minor unit")
    }
}

private fun requireMinorUnitValue(value: Double, field: String): Long {
    if (
        value.isNaN() ||
        value.isInfinite() ||
        value % 1.0 != 0.0 ||
        value > MAX_SAFE_MINOR_UNIT.toDouble() ||
        value < -MAX_SAFE_MINOR_UNIT.toDouble()
    ) {
        throw BridgeException("INVALID_ARGUMENT", "Field $field must be a safe integer minor unit")
    }
    return value.toLong()
}

private fun JSONObject.optionalInt(key: String): Int? = optionalLong(key)?.toInt()

private fun JSONObject.optionalBoolean(key: String): Boolean? {
    if (!hasValue(key)) return null
    return try {
        getBoolean(key)
    } catch (error: Exception) {
        throw BridgeException("INVALID_ARGUMENT", "Field $key must be boolean", error)
    }
}

private fun JSONObject.updatedNullableString(key: String, current: String?): String? {
    if (!has(key)) return current
    if (isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}
