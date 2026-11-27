package com.jotbill.app.bridge

import android.util.Log
import androidx.room.withTransaction
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.LedgerValidationException
import com.jotbill.app.data.dao.escapeLikeKeyword
import com.jotbill.app.data.seed.DefaultDataInitializer
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.rules.CategoryRuleLearningService
import com.jotbill.app.rules.FieldPriorityResolver
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.data.model.CategoryStatRow
import com.jotbill.app.data.model.PlatformStatRow
import com.jotbill.app.data.model.TrendRow
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.pending.PendingConfirmService
import com.jotbill.app.pending.PendingReminderNotifier
import com.jotbill.app.budget.BudgetPeriods
import com.jotbill.app.budget.BudgetProgressCalculator
import com.jotbill.app.budget.BudgetReminderNotifier
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
import java.util.UUID

/**
 * 本地优先的数据桥接层。方法使用 JSON 字符串作为边界，保持 JS 封装层的稳定契约。
 */

/** JS 事件名:待确认创建/更新(DataModule → RN)。 */
internal const val PENDING_CREATED_EVENT = "onPendingCreated"

/**
 * 进程内共享的 JS 事件出口。
 *
 * 付款确认弹窗([com.jotbill.app.overlay.PendingConfirmOverlayManager])在
 * 无 RN 桥实例的上下文里确认入账后，借此广播 onBillCreated 让前端刷新
 * 账单缓存；RN 宿主未启动或已销毁时为 null,弹窗侧跳过推送即可。
 */
@Volatile
internal var sharedJsEventEmitter: NativeEventEmitter? = null

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

    init {
        // 待确认创建/更新事件 → JS 推送。数据落库在各自创建点完成,本监听只是提醒旁路;
        // 落库与提醒解耦的设计见 pending/PendingChangeHub。
        PendingChangeHub.addListener(::onPendingChangeFromHub)
        sharedJsEventEmitter = events
    }

    override fun onCatalystInstanceDestroy() {
        PendingChangeHub.removeListener(::onPendingChangeFromHub)
        if (sharedJsEventEmitter === events) {
            sharedJsEventEmitter = null
        }
    }

    private fun onPendingChangeFromHub(info: PendingChangeInfo) {
        try {
            events.emit(
                PENDING_CREATED_EVENT,
                JSONObject()
                    .put("pendingId", info.pendingId)
                    .put("type", info.type)
                    .put("amountMinor", info.amountMinor)
                    .put("sourceType", info.sourceType)
                    .put("conflictReason", info.conflictReason ?: JSONObject.NULL)
                    .put("isNew", info.isNew)
            )
        } catch (error: RuntimeException) {
            Log.w("DataModule", "pending 事件推送 JS 失败(RN 可能未就绪)", error)
        }
    }

    /**
     * 账单增删改成功后触发预算超支提醒检查(异步,不阻塞主流程)。
     * 命中预警档位时发系统通知,并向 JS 推送 onBudgetAlert 事件。
     */
    private fun maybeNotifyBudgetAlerts() {
        scope.launch {
            try {
                val hits = BudgetReminderNotifier.checkAndNotify(database)
                hits.forEach { hit ->
                    try {
                        events.emit(
                            "onBudgetAlert",
                            JSONObject()
                                .put("budgetId", hit.budget.id)
                                .put("budgetName", hit.budget.name)
                                .put("progress", hit.progressPct / 100.0)
                                .put("isOverBudget", hit.isOverBudget)
                        )
                    } catch (error: RuntimeException) {
                        Log.w("DataModule", "onBudgetAlert 推送 JS 失败(RN 可能未就绪)", error)
                    }
                }
            } catch (error: Exception) {
                Log.w("DataModule", "预算提醒检查失败(不影响记账)", error)
            }
        }
    }

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
        val keyword = escapeLikeKeyword(filter.optionalString("keyword"))
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
        // ---- P15：可选客户端幂等键 ----
        // RN 在每个表单会话/AI 提案内生成 clientRequestId 并随重试原样重传，
        // 账单 id 由它确定性生成：重试/双击/部分失败重传命中既有账单即幂等返回，
        // 不再产生第二条。缺省（旧客户端）保持原随机 UUID 语义，契约向后兼容。
        val clientRequestId = input.optionalString("clientRequestId")
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (clientRequestId != null &&
            (clientRequestId.length > 128 || !clientRequestId.matches(CLIENT_REQUEST_ID_PATTERN))
        ) {
            throw BridgeException(
                "INVALID_ARGUMENT",
                "clientRequestId must match [A-Za-z0-9_.:-]{1,128}"
            )
        }
        val billId = clientRequestId?.let { "manual:$it" } ?: UUID.randomUUID().toString()
        if (clientRequestId != null) {
            database.billDao().getById(billId)?.let { existing ->
                // 幂等重放：不重复 insert、不动余额、不再广播 onBillCreated，
                // 直接把首次入账的结果返回给 RN（与首响应语义一致）。
                return@launch BridgeDtoMapper.bill(existing).toString()
            }
        }
        val bill = BridgeDtoMapper.parseBillInput(input, now, billId)
        // 必须按 outcome 分支：命中去重第 1 级时并未 insert 新账单，也未动余额，
        // 实际是把新证据字段合并进了既有账单。此时若仍把 `bill`（一个从未落库的
        // UUID）返回给 JS 并广播 onBillCreated，前端跳详情页会得到 NOT_FOUND，
        // 而余额分毫未动、UI 却显示"保存成功"。addBill 的 id 是随机 UUID、
        // 无幂等键，用户重试就会真的产生重复账单。
        val outcome = try {
            repository.createBillWithDedup(bill)
        } catch (error: com.jotbill.app.data.OrderConflictException) {
            // M1：同单号撞号 —— 明确错误码返回 RN，新账单不落库、不动余额，
            // 用户看到冲突提示而不是"保存成功"的假象。
            throw BridgeException(
                "ORDER_CONFLICT",
                "订单号与既有账单（金额或类型）冲突，请到账单列表核对: ${bill.orderId}"
            )
        }
        val settledBill = when (outcome) {
            is CreateBillOutcome.Committed -> outcome.bill
            is CreateBillOutcome.MergedInto -> outcome.bill
        }
        events.emit("onBillCreated", BridgeDtoMapper.bill(settledBill))
        maybeNotifyBudgetAlerts()
        BridgeDtoMapper.bill(settledBill).toString()
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
        // A-1（2026-11-05 拍板）：用户手改字段自动并入 fieldLocks —— 只锁**实际发生变更**的
        // 字段（判据 = updated 与 existing 逐字段值比较；RN 全量表单提交时未改字段值相等
        // ⇒ 不会误锁一批字段）。用户再次编辑同一字段 = 写入新值并保持锁定（更新锁值、
        // 保持锁）；自动链路（EVIDENCE/IMPORT）不经过本入口，不会触发加锁。
        // 锁由 Kotlin 侧生成，不依赖 RN 传参（RN 契约仍不声明 fieldLocks）。
        val autoLockedFields = FieldPriorityResolver.LOCKABLE_FIELDS.filter { field ->
            FieldPriorityResolver.billFieldValue(updated, field) !=
                FieldPriorityResolver.billFieldValue(existing, field)
        }
        val updatedWithLocks = if (autoLockedFields.isEmpty()) {
            updated
        } else {
            updated.copy(
                fieldLocks = FieldPriorityResolver.serializeLocks(
                    FieldPriorityResolver.parseLocks(updated.fieldLocks) + autoLockedFields
                )
            )
        }
        if (updatedWithLocks.amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }
        if (!repository.updateBillWithBalanceUpdate(existing, updatedWithLocks)) {
            if (database.billDao().getById(existing.id) == null) {
                throw BridgeException("NOT_FOUND", "Bill $id was not found")
            }
            throw BridgeException("VERSION_CONFLICT", "Bill $id has changed; refresh before updating")
        }
        events.emit("onBillUpdated", BridgeDtoMapper.bill(updatedWithLocks))
        maybeNotifyBudgetAlerts()
        BridgeDtoMapper.bill(updatedWithLocks).toString()
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
        maybeNotifyBudgetAlerts()
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
            updatedAt = now,
            cardTail = if (subtype in BANK_CARD_SUBTYPES) {
                input.optionalString("cardTail")?.let(::normalizeCardTail)
            } else {
                null
            }
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
                cardTail = if (subtype in BANK_CARD_SUBTYPES) {
                    if (input.has("cardTail")) {
                        input.updatedNullableString("cardTail", null)?.let(::normalizeCardTail)
                    } else {
                        // 老备份可能带入非法值：静默丢弃，避免每次编辑都抛校验错。
                        existing.cardTail?.takeIf { Regex("\\d{4}").matches(it) }
                    }
                } else {
                    null
                },
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

    /**
     * B4：余额一致性实时重算（桥）。RN 账户页进入时调用；
     * 不一致清单逐条含 expected/actual/diff，UI 指向「校准余额」入口。
     * 口径见 [com.jotbill.app.data.BalanceReconciler]（含校准差额，校准过的账户不误报）。
     */
    @ReactMethod
    fun getBalanceReconcileWarnings(promise: Promise) = launch(promise) {
        val mismatches = com.jotbill.app.data.BalanceReconciler(database).check()
        JSONObject().apply {
            put("checkedAt", System.currentTimeMillis())
            put(
                "mismatches",
                JSONArray().also { array ->
                    mismatches.forEach { mismatch ->
                        array.put(
                            JSONObject().apply {
                                put("accountId", mismatch.accountId)
                                put("expectedMinor", mismatch.expectedMinor)
                                put("actualMinor", mismatch.actualMinor)
                                put("diffMinor", mismatch.diffMinor)
                            }
                        )
                    }
                }
            )
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

    private suspend fun nextChildSortOrder(parentId: String): Int {
        val maxChildSortOrder = database.categoryDao().getChildren(parentId)
            .maxOfOrNull { it.sortOrder } ?: 99
        return maxOf(100, maxChildSortOrder + 1)
    }

    private suspend fun nextTopLevelSortOrder(type: String, requestedSortOrder: Int): Int {
        val maxTopLevelSortOrder = database.categoryDao().getByType(type)
            .filter { it.parentId == null }
            .maxOfOrNull { it.sortOrder } ?: -1
        return maxOf(requestedSortOrder, maxTopLevelSortOrder + 1)
    }

    @ReactMethod
    fun addCategory(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val type = BridgeDtoMapper.requiredString(input, "type")
        validateCategoryType(type)
        // 批 6 子分类:parentId 可选;父须存在、同类型,且父本身是顶级(体系两级)
        val parentId = input.optionalString("parentId")
        if (parentId != null) {
            val parent = database.categoryDao().getById(parentId)
                ?: throw BridgeException("NOT_FOUND", "Parent category $parentId was not found")
            if (parent.type != type) {
                throw BridgeException("INVALID_ARGUMENT", "子分类类型必须与父分类一致")
            }
            if (parent.parentId != null) {
                throw BridgeException("INVALID_ARGUMENT", "分类体系最多两级,子分类下不能再建子分类")
            }
        }
        val now = System.currentTimeMillis()
        // 排序计算与插入包进事务：nextChildSortOrder/nextTopLevelSortOrder 是
        // 读-算-写,并发新增两个分类会取到同一个 max 撞 sortOrder
        val category = database.withTransaction {
            // 子分类排序:预设子分类 sortOrder 从 100 起,新增子分类排到既有子分类之后。
            // 顶级分类排序:新增自定义顶级分类排到既有顶级分类之后,避免 sortOrder=0
            // 与默认分类混排,保证"默认在前、自定义在后"。
            val sortOrder = if (parentId != null) {
                nextChildSortOrder(parentId)
            } else {
                nextTopLevelSortOrder(type, input.optInt("sortOrder", 0))
            }
            CategoryEntity(
                id = UUID.randomUUID().toString(),
                name = BridgeDtoMapper.requiredString(input, "name"),
                type = type,
                icon = input.optionalString("icon"),
                color = input.optionalString("color"),
                isDefault = false,
                sortOrder = sortOrder,
                createdAt = now,
                updatedAt = now,
                parentId = parentId
            ).also { database.categoryDao().insert(it) }
        }
        events.emit("onCategoryChanged", BridgeDtoMapper.category(category))
        BridgeDtoMapper.category(category).toString()
    }

    @ReactMethod
    fun updateCategory(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val categoryId = requireId(id, "category")
        val input = parseJson(inputJson)
        val updated = database.withTransaction {
            val existing = database.categoryDao().getById(categoryId)
                ?: throw BridgeException("NOT_FOUND", "Category $id was not found")
            // 批 6:默认分类允许改名/图标/颜色/排序(类型锁定、不可删除)
            val type = if (existing.isDefault) {
                existing.type
            } else {
                input.optionalString("type") ?: existing.type
            }
            validateCategoryType(type)
            // 类型变更保护：有子分类或已被账单引用时拒绝。否则会出现两级类型不一致、
            // 或支出账单挂在 income 分类上（账单在选择器中不可见）。
            if (type != existing.type) {
                if (database.categoryDao().countChildren(existing.id) > 0) {
                    throw BridgeException("DATA_CONFLICT", "该分类含子分类，不可变更类型，请先处理子分类")
                }
                if (database.billDao().countByCategoryId(existing.id) > 0) {
                    throw BridgeException("DATA_CONFLICT", "该分类已被账单使用，不可变更类型")
                }
            }
            // 父级变更校验:父须存在、同类型、顶级;自引用禁止;
            // 使用 updatedNullableString 允许显式传 null/"" 将子分类提升为一级分类。
            val requestedParentId = input.updatedNullableString("parentId", existing.parentId)
            if (requestedParentId != existing.parentId) {
                if (requestedParentId == null) {
                    if (database.categoryDao().countChildren(existing.id) > 0) {
                        throw BridgeException("INVALID_ARGUMENT", "已含子分类的分类不可改为顶级,请先处理其子分类")
                    }
                } else {
                    if (requestedParentId == existing.id) {
                        throw BridgeException("INVALID_ARGUMENT", "分类不能成为自己的父分类")
                    }
                    if (database.categoryDao().countChildren(existing.id) > 0) {
                        throw BridgeException("DATA_CONFLICT", "已含子分类的分类不可移动,请先处理其子分类")
                    }
                    val parent = database.categoryDao().getById(requestedParentId)
                        ?: throw BridgeException("NOT_FOUND", "Parent category $requestedParentId was not found")
                    if (parent.type != type) {
                        throw BridgeException("INVALID_ARGUMENT", "子分类类型必须与父分类一致")
                    }
                    if (parent.parentId != null) {
                        throw BridgeException("INVALID_ARGUMENT", "分类体系最多两级,子分类下不能再建子分类")
                    }
                }
            }
            // 排序:移动到新父分类下重算子分类排序;子分类升为顶级时按顶级重算;
            // 仅编辑名称/图标时保持原 sortOrder
            val sortOrder = when {
                requestedParentId != null && requestedParentId != existing.parentId ->
                    nextChildSortOrder(requestedParentId)
                requestedParentId == null && existing.parentId != null ->
                    nextTopLevelSortOrder(type, input.optionalInt("sortOrder") ?: existing.sortOrder)
                else -> input.optionalInt("sortOrder") ?: existing.sortOrder
            }
            val next = existing.copy(
                name = input.optionalString("name") ?: existing.name,
                type = type,
                icon = input.updatedNullableString("icon", existing.icon),
                color = input.updatedNullableString("color", existing.color),
                sortOrder = sortOrder,
                updatedAt = System.currentTimeMillis(),
                parentId = requestedParentId
            )
            database.categoryDao().update(next)
            next
        }
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
            if (database.categoryDao().countChildren(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "请先删除或移动该分类下的子分类")
            }
            if (database.billDao().countByCategoryId(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "Category $id is referenced by bills")
            }
            if (database.budgetDao().countByCategoryId(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "该分类已被预算使用，请先删除或修改对应预算")
            }
            if (database.pendingTransactionDao().countPendingByCategoryId(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "该分类仍被待确认账单引用，请先处理待确认记录")
            }
            if (database.ruleDao().countByCategoryAction(existing.id) > 0) {
                throw BridgeException("DATA_CONFLICT", "该分类已被规则引用，请先修改或删除对应规则")
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
        val period = input.optionalString("period") ?: BudgetPeriods.MONTHLY
        validateBudgetPeriod(period)
        val periodConfig = parseBudgetPeriodConfig(input, period, null)
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
            startDay = periodConfig.startDay,
            weekStart = periodConfig.weekStart,
            anchorAt = periodConfig.anchorAt,
            rangeStartAt = periodConfig.rangeStartAt,
            rangeEndAt = periodConfig.rangeEndAt,
            categoryId = input.optionalString("categoryId"),
            alertAt = alertAt,
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
        database.budgetDao().insert(budget)
        events.emit("onBudgetChanged", BridgeDtoMapper.budget(budget))
        maybeNotifyBudgetAlerts()
        BridgeDtoMapper.budget(budget).toString()
    }

    @ReactMethod
    fun updateBudget(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val existing = database.budgetDao().getById(requireId(id, "budget"))
            ?: throw BridgeException("NOT_FOUND", "Budget $id was not found")
        val input = parseJson(inputJson)
        val period = input.optionalString("period") ?: existing.period
        validateBudgetPeriod(period)
        val periodConfig = parseBudgetPeriodConfig(input, period, existing)
        val amountMinor = input.optionalLong("amountMinor") ?: existing.amountMinor
        if (amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            amountMinor = amountMinor,
            period = period,
            startDay = periodConfig.startDay,
            weekStart = periodConfig.weekStart,
            anchorAt = periodConfig.anchorAt,
            rangeStartAt = periodConfig.rangeStartAt,
            rangeEndAt = periodConfig.rangeEndAt,
            categoryId = input.updatedNullableString("categoryId", existing.categoryId),
            alertAt = input.optionalInt("alertAt")?.coerceIn(0, 100) ?: existing.alertAt,
            isActive = input.optionalBoolean("isActive") ?: existing.isActive,
            updatedAt = System.currentTimeMillis()
        )
        database.budgetDao().update(updated)
        events.emit("onBudgetChanged", BridgeDtoMapper.budget(updated))
        maybeNotifyBudgetAlerts()
        BridgeDtoMapper.budget(updated).toString()
    }

    @ReactMethod
    fun deleteBudget(id: String, promise: Promise) = launch(promise) {
        val budget = database.budgetDao().getById(requireId(id, "budget"))
            ?: throw BridgeException("NOT_FOUND", "Budget $id was not found")
        database.budgetDao().delete(budget)
        BudgetReminderNotifier.clearBudgetState(reactContext, budget.id)
        events.emit("onBudgetChanged", JSONObject().put("id", budget.id).put("deleted", true))
        null
    }

    @ReactMethod
    fun getBudgetProgress(promise: Promise) = launch(promise) {
        val now = System.currentTimeMillis()
        JSONArray().also { array ->
            database.budgetDao().getActive().forEach { budget ->
                val snapshot = try {
                    BudgetProgressCalculator.compute(database, budget, now)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w("DataModule", "预算进度计算失败，跳过: id=${budget.id.take(16)}", error)
                    return@forEach
                }
                array.put(BridgeDtoMapper.budget(budget).apply {
                    put("spentMinor", snapshot.spentMinor)
                    put("grossSpentMinor", snapshot.grossSpentMinor)
                    put("refundMinor", snapshot.refundMinor)
                    put("remainingMinor", snapshot.remainingMinor)
                    put("progress", snapshot.progress)
                    put("progressPct", snapshot.progressPct)
                    put("isOverBudget", snapshot.isOverBudget)
                    put("periodStartAt", BridgeDtoMapper.formatTimestamp(snapshot.range.startAt))
                    put("periodEndAt", BridgeDtoMapper.formatTimestamp(snapshot.range.endAt))
                    put("periodLabel", BudgetPeriods.label(budget, snapshot.range))
                    put("isPeriodActive", snapshot.isActivePeriod)
                    put(
                        "needsAlert",
                        snapshot.isActivePeriod &&
                            budget.alertAt in 1..100 &&
                            snapshot.progressPct >= budget.alertAt
                    )
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
        val condition = BridgeDtoMapper.requiredString(input, "condition")
        val conditionValue = BridgeDtoMapper.requiredString(input, "conditionValue")
        val action = BridgeDtoMapper.requiredString(input, "action")
        val actionValue = input.optionalString("actionValue")
        validateRuleDefinition(condition, conditionValue, action, actionValue)
        val rule = RuleEntity(
            id = UUID.randomUUID().toString(),
            name = BridgeDtoMapper.requiredString(input, "name"),
            condition = condition,
            conditionValue = conditionValue,
            action = action,
            actionValue = actionValue,
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
        val condition = input.optionalString("condition") ?: existing.condition
        val conditionValue = input.optionalString("conditionValue") ?: existing.conditionValue
        val action = input.optionalString("action") ?: existing.action
        val actionValue = input.updatedNullableString("actionValue", existing.actionValue)
        validateRuleDefinition(condition, conditionValue, action, actionValue)
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            condition = condition,
            conditionValue = conditionValue,
            action = action,
            actionValue = actionValue,
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
        val targetRules = if (!ruleId.isNullOrBlank()) {
            val single = database.ruleDao().getById(requireId(ruleId, "rule"))
                ?: throw BridgeException("NOT_FOUND", "Rule $ruleId was not found")
            if (!single.isActive) emptyList() else listOf(single)
        } else {
            database.ruleDao().getActive()
        }
        val patchRules = targetRules.filter {
            it.action == RuleEngine.ACTION_SET_PLATFORM ||
                it.action == RuleEngine.ACTION_SET_MERCHANT ||
                it.action == RuleEngine.ACTION_SET_CATEGORY ||
                it.action == RuleEngine.ACTION_SET_ACCOUNT
        }
        if (patchRules.isEmpty()) {
            return@launch JSONObject().put("affected", 0).toString()
        }
        val categoriesById = database.categoryDao().getAll().associateBy { it.id }
        val bills = database.billDao().getMutableBills()
        var affected = 0
        for (bill in bills) {
            val aliases = listOfNotNull(
                bill.platform?.takeIf { it.isNotBlank() }
                    ?.let { com.jotbill.app.bookkeeping.AutoBookkeeper.channelLabel(it) }
            ).distinct()
            val decision = ruleEngine.evaluate(
                RuleEngine.Subject(
                    amountMinor = bill.amountMinor,
                    platform = bill.platform,
                    platformAliases = aliases,
                    merchant = bill.merchant,
                    description = bill.description ?: bill.productDescription,
                    sourceType = bill.sourceType
                ),
                patchRules
            )
            if (decision.hits.isEmpty()) continue
            val patch = ruleEngine.sanitizePatch(decision.patch)
            val nextCategoryId = patch.categoryId?.takeIf { candidateId ->
                categoriesById[candidateId]?.let { category ->
                    RuleEngine.isCategoryCompatibleWithBillType(category.type, bill.type)
                } == true
            } ?: bill.categoryId
            val nextAccountId = patch.accountId?.takeIf { candidateId ->
                if (bill.type == "transfer" || bill.type == "repayment") {
                    candidateId != bill.targetAccountId
                } else {
                    true
                }
            } ?: bill.accountId
            val candidate = bill.copy(
                platform = patch.platform ?: bill.platform,
                merchant = patch.merchant ?: bill.merchant,
                categoryId = nextCategoryId,
                accountId = nextAccountId,
                updatedAt = System.currentTimeMillis(),
                version = bill.version + 1
            )
            val (protected, _) = FieldPriorityResolver.protectLockedUpdate(
                bill,
                candidate,
                FieldPriorityResolver.Source.RULE
            )
            val changed = protected.platform != bill.platform ||
                protected.merchant != bill.merchant ||
                protected.categoryId != bill.categoryId ||
                protected.accountId != bill.accountId
            if (!changed) continue
            val applied = runCatching {
                repository.updateBillWithBalanceUpdate(
                    bill,
                    candidate,
                    FieldPriorityResolver.Source.RULE
                )
            }.getOrDefault(false)
            if (applied) {
                affected += 1
                decision.hits.forEach { hit ->
                    database.ruleDao().incrementHitCount(hit.ruleId)
                }
            }
        }
        if (affected > 0) {
            events.emit("onBillChanged", JSONObject().put("batchUpdated", affected))
            maybeNotifyBudgetAlerts()
        }
        JSONObject().put("affected", affected).toString()
    }

    @ReactMethod
    fun previewCategoryRuleSuggestion(inputJson: String, promise: Promise) = launch(promise) {
        val input = parseJson(inputJson)
        val merchant = input.optionalString("merchant")
        val categoryId = BridgeDtoMapper.requiredString(input, "categoryId")
        val billType = BridgeDtoMapper.requiredString(input, "billType")
        BridgeDtoMapper.validateBillType(billType)
        val excludeBillId = input.optionalString("excludeBillId")
        val suggestion = CategoryRuleLearningService(database).preview(
            rawMerchant = merchant,
            categoryId = categoryId,
            billType = billType,
            excludeBillId = excludeBillId
        )
        JSONObject().apply {
            put("status", suggestion.status)
            put("merchant", suggestion.merchant)
            put("categoryId", suggestion.categoryId)
            suggestion.categoryName?.let { put("categoryName", it) }
            suggestion.existingRuleId?.let { put("existingRuleId", it) }
            suggestion.existingRuleName?.let { put("existingRuleName", it) }
            suggestion.existingCategoryId?.let { put("existingCategoryId", it) }
            suggestion.existingCategoryName?.let { put("existingCategoryName", it) }
            put("matchingHistoricalCount", suggestion.matchingHistoricalCount)
            put("lockedSkippedCount", suggestion.lockedSkippedCount)
            put("suggestedPriority", suggestion.suggestedPriority)
        }.toString()
    }

    private fun validateRuleDefinition(
        condition: String,
        conditionValue: String,
        action: String,
        actionValue: String?
    ) {
        if (condition !in RuleEngine.VALID_CONDITIONS) {
            throw BridgeException("INVALID_ARGUMENT", "不支持的规则条件: $condition")
        }
        if (action !in RuleEngine.VALID_ACTIONS) {
            throw BridgeException("INVALID_ARGUMENT", "不支持的规则动作: $action")
        }
        if (condition == RuleEngine.CONDITION_AMOUNT_RANGE &&
            RuleEngine.parseAmountRange(conditionValue) == null
        ) {
            throw BridgeException("INVALID_ARGUMENT", "金额区间格式不合法，应为整数分范围如 1000-5000")
        }
        val requiresActionValue = action == RuleEngine.ACTION_SET_PLATFORM ||
            action == RuleEngine.ACTION_SET_MERCHANT ||
            action == RuleEngine.ACTION_SET_CATEGORY ||
            action == RuleEngine.ACTION_SET_ACCOUNT
        if (requiresActionValue && actionValue.isNullOrBlank()) {
            throw BridgeException("INVALID_ARGUMENT", "该规则动作需要填写目标值")
        }
    }

    @ReactMethod
    fun getPendingTransactions(filterJson: String?, promise: Promise) = launch(promise) {
        val filter = parseJson(filterJson)
        val all = filter.optBoolean("all")
        val page = if (all) 1 else filter.optInt("page", 1).coerceAtLeast(1)
        val limit = if (all) Int.MAX_VALUE else filter.optInt("limit", 20).coerceIn(1, 100)
        val offset = ((page - 1).toLong() * limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pending = database.pendingTransactionDao().getPageExcludingEditCards(limit, offset)
        val total = if (all) pending.size else database.pendingTransactionDao().countExcludingEditCards()
        JSONObject().apply {
            put("items", JSONArray().also { array -> pending.forEach { array.put(BridgeDtoMapper.pending(it)) } })
            put("total", total)
            put("page", page)
            put("limit", if (all) total else limit)
            put("hasMore", !all && page.toLong() * limit < total.toLong())
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
    fun ignorePendingTransactions(selectionJson: String, promise: Promise) = launch(promise) {
        val items = JSONArray(selectionJson)
        val selection = buildMap {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = requireId(item.optString("pendingId"), "pending transaction")
                val version = item.getInt("version")
                if (version <= 0) throw BridgeException("INVALID_ARGUMENT", "Invalid pending transaction version")
                put(id, version)
            }
        }
        val ignoredCount = repository.ignorePendingTransactions(selection)
        val result = JSONObject().put("ignoredCount", ignoredCount)
        if (ignoredCount > 0) events.emit("onPendingChanged", result)
        result.toString()
    }

    /** React Native 新版事件发射器要求模块提供这两个订阅计数方法。 */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    /** 待确认提醒开关(仅控制系统通知;JS 事件与数据落库不受影响)。默认开启。 */
    @ReactMethod
    fun isPendingReminderEnabled(promise: Promise) = launch(promise) {
        PendingReminderNotifier.isEnabled(reactContext.applicationContext)
    }

    @ReactMethod
    fun setPendingReminderEnabled(enabled: Boolean, promise: Promise) = launch(promise) {
        PendingReminderNotifier.setEnabled(reactContext.applicationContext, enabled)
        null
    }

    /** 预算超支提醒开关(仅控制系统通知;预算进度计算与 UI 变色不受影响)。默认开启。 */
    @ReactMethod
    fun isBudgetReminderEnabled(promise: Promise) = launch(promise) {
        BudgetReminderNotifier.isEnabled(reactContext.applicationContext)
    }

    @ReactMethod
    fun setBudgetReminderEnabled(enabled: Boolean, promise: Promise) = launch(promise) {
        BudgetReminderNotifier.setEnabled(reactContext.applicationContext, enabled)
        if (enabled) {
            maybeNotifyBudgetAlerts()
        }
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
        // 确认入账统一走共享入口：与付款确认弹窗共用同一条
        // "覆写字段→规则引擎→组装 Bill→事务内乐观锁确认"路径，防止双实现漂移。
        // 字段优先级：用户显式输入 > 规则补全 > 待确认既有字段（证据/AI）。
        val outcome = PendingConfirmService(database).confirm(
            pendingId = pending.id,
            overrides = PendingConfirmService.Overrides(
                type = input.optionalString("type"),
                sourceType = input.optionalString("sourceType"),
                amountMinor = input.optionalMinor("amountMinor"),
                merchant = input.optionalString("merchant"),
                categoryId = input.optionalString("categoryId"),
                accountId = input.optionalString("accountId"),
                targetAccountId = input.optionalString("targetAccountId"),
                platform = input.optionalString("platform"),
                productDescription = input.optionalString("productDescription"),
                orderId = input.optionalString("orderId"),
                description = input.updatedNullableString("description", null),
                occurredAt = if (input.hasValue("occurredAt")) {
                    BridgeDtoMapper.requiredTimestamp(input, "occurredAt")
                } else {
                    null
                },
                originalTransactionId = input.optionalString("originalTransactionId")
            ),
            // M4：RN 在「疑似重复」二次确认弹窗点确定后回传 true 放行
            confirmedDuplicate = input.optBoolean("confirmedDuplicate", false)
        )
        val settledBill = when (outcome) {
            is PendingConfirmService.Outcome.Confirmed -> outcome.bill
            is PendingConfirmService.Outcome.AutoBooked ->
                // 卡片已被其它通道自动入账：返回既有账单，RN 按成功处理
                // （真机 2026-11-16：弹窗点确认与短信入账并发）。
                database.billDao().getById(outcome.billId)
                    ?: throw BridgeException("VERSION_CONFLICT", "Pending transaction $id has changed")
            is PendingConfirmService.Outcome.AlreadyHandled ->
                throw BridgeException("VERSION_CONFLICT", "Pending transaction $id has changed")
            is PendingConfirmService.Outcome.DuplicateConfirmRequired ->
                // M4：可识别错误码 —— RN 据此弹二次确认，而不是普通失败提示
                throw BridgeException(
                    "DUPLICATE_CONFIRM_REQUIRED",
                    "疑似重复交易，请核对后再确认"
                )
            is PendingConfirmService.Outcome.Invalid ->
                throw BridgeException("INVALID_ARGUMENT", outcome.reason)
        }
        events.emit("onPendingChanged", JSONObject().put("pendingId", pending.id).put("deleted", true))
        events.emit("onBillCreated", BridgeDtoMapper.bill(settledBill))
        maybeNotifyBudgetAlerts()
        val response = BridgeDtoMapper.bill(settledBill)
        // P1-5（2026-10-24 场景审查）：合并路径（同 sourceType+orderId 已有账单）
        // 会丢弃用户本次的修改，RN 侧必须知道以便如实提示，而不是让用户以为
        // "按我改的记上了"。
        if (outcome is PendingConfirmService.Outcome.Confirmed && outcome.merged) {
            response.put("mergedIntoExistingBill", true)
        }
        response.toString()
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

    /** 卡尾号只接受 4 位数字（银行短信按它精准匹配账户）。 */
    private fun normalizeCardTail(raw: String): String {
        val trimmed = raw.trim()
        if (!Regex("\\d{4}").matches(trimmed)) {
            throw BridgeException("INVALID_ARGUMENT", "cardTail must be exactly 4 digits")
        }
        return trimmed
    }

    /** 只有借记卡/信用卡账户才有卡尾号语义。 */
    private val BANK_CARD_SUBTYPES = setOf("bank_debit", "credit_card")

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

    private data class BudgetPeriodConfig(
        val startDay: Int? = null,
        val weekStart: Int? = null,
        val anchorAt: Long? = null,
        val rangeStartAt: Long? = null,
        val rangeEndAt: Long? = null
    )

    private fun validateBudgetPeriod(period: String) {
        if (period !in BudgetPeriods.supported) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported budget period: $period")
        }
    }

    private fun parseBudgetPeriodConfig(
        input: JSONObject,
        period: String,
        existing: BudgetEntity?
    ): BudgetPeriodConfig {
        fun dateField(key: String): LocalDate? {
            val raw = input.optionalString(key) ?: return null
            return try {
                LocalDate.parse(raw)
            } catch (error: Exception) {
                throw BridgeException("INVALID_ARGUMENT", "Field $key must be YYYY-MM-DD", error)
            }
        }

        return when (period) {
            BudgetPeriods.MONTHLY -> {
                val startDay = input.optionalInt("startDay")
                    ?: existing?.takeIf { it.period == period }?.startDay
                    ?: 1
                if (startDay !in 1..31) {
                    throw BridgeException("INVALID_ARGUMENT", "startDay must be between 1 and 31")
                }
                BudgetPeriodConfig(startDay = startDay)
            }
            BudgetPeriods.WEEKLY -> {
                val weekStart = input.optionalInt("weekStart")
                    ?: existing?.takeIf { it.period == period }?.weekStart
                    ?: 1
                if (weekStart !in 1..7) {
                    throw BridgeException("INVALID_ARGUMENT", "weekStart must be between 1 and 7")
                }
                BudgetPeriodConfig(weekStart = weekStart)
            }
            BudgetPeriods.BIWEEKLY -> {
                val anchorAt = dateField("anchorDate")?.let { BudgetPeriods.startOfDay(it) }
                    ?: existing?.takeIf { it.period == period }?.anchorAt
                    ?: throw BridgeException(
                        "INVALID_ARGUMENT",
                        "anchorDate is required for biweekly budgets"
                    )
                BudgetPeriodConfig(anchorAt = anchorAt)
            }
            BudgetPeriods.ONCE -> {
                val startAt = dateField("rangeStartDate")?.let { BudgetPeriods.startOfDay(it) }
                    ?: existing?.takeIf { it.period == period }?.rangeStartAt
                    ?: throw BridgeException(
                        "INVALID_ARGUMENT",
                        "rangeStartDate is required for one-off budgets"
                    )
                val endAt = dateField("rangeEndDate")?.let { BudgetPeriods.endOfDay(it) }
                    ?: existing?.takeIf { it.period == period }?.rangeEndAt
                    ?: throw BridgeException(
                        "INVALID_ARGUMENT",
                        "rangeEndDate is required for one-off budgets"
                    )
                if (endAt < startAt) {
                    throw BridgeException(
                        "INVALID_ARGUMENT",
                        "rangeEndDate must not be before rangeStartDate"
                    )
                }
                BudgetPeriodConfig(rangeStartAt = startAt, rangeEndAt = endAt)
            }
            BudgetPeriods.QUARTERLY, BudgetPeriods.YEARLY -> BudgetPeriodConfig()
            else -> throw BridgeException("INVALID_ARGUMENT", "Unsupported budget period: $period")
        }
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
                // 协程被取消（模块销毁/scope 取消）时也要 reject，否则 JS 侧 Promise 永久悬挂。
                promise.reject("CANCELLED", error.message ?: "Operation was cancelled", error)
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

/** P15：客户端幂等键的合法字符集（防注入、限长，键会直接进入账单主键）。 */
private val CLIENT_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_.:-]+")

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
