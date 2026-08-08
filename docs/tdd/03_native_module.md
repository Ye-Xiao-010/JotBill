# 03. Native Module 详细设计

> 技术实现文档(TDD)第三部分
>
> 关联文档:types/Native.d.ts、02_room_schema.md、MIGRATION_MAP.md
>
> 作用:将接口类型定义转化为 Kotlin 类签名、DTO、序列化方案和线程模型,作为 RN 与原生并行开发的契约

---

## 1. 模块总览

```
com.jotbill.app/
├── MainActivity.kt
├── MainApplication.kt          (注册 6 个 Native Package)
├── data/
│   ├── DataModule.kt           (实现 DataModule 接口)
│   ├── DataPackage.kt
│   ├── Database.kt             (Room + SQLCipher)
│   ├── DatabaseModule.kt       (DI: 提供 DAO)
│   └── dto/                    (DTO 类 +序列化)
├── recognition/
│   ├── RecognitionModule.kt
│   ├── RecognitionPackage.kt
│   ├── JotBillAccessibilityService.kt
│   ├── CheckoutPageDetector.kt
│   ├── PaymentResultReader.kt
│   ├── ScreenshotCapture.kt
│   ├── PageRoleClassifier.kt
│   ├── StabilityBuffer.kt
│   ├── FingerprintBuilder.kt
│   ├── RequestCoordinator.kt
│   ├── PaymentSessionEngine.kt
│   ├── ReconciliationEngine.kt
│   ├── RuleEngine.kt
│   └── SnapshotBuffer.kt       (内存中的快照缓冲)
├── ai/
│   ├── AiModule.kt
│   ├── AiPackage.kt
│   ├── AiVisionClient.kt
│   ├── AiProviderAdapter.kt    (接口)
│   ├── OpenAICompatibleAdapter.kt (基类)
│   ├── QwenAdapter.kt
│   ├── GlmAdapter.kt
│   ├── SiliconFlowAdapter.kt
│   ├── PromptManager.kt
│   ├── CostController.kt
│   ├── ImagePreprocessor.kt
│   └── AiResponseValidator.kt
├── runtime/
│   ├── RuntimeModule.kt
│   ├── RuntimePackage.kt
│   ├── HealthMonitor.kt
│   └── HealthCheckWorker.kt    (WorkManager)
├── overlay/
│   ├── OverlayModule.kt
│   ├── OverlayPackage.kt
│   └── ManualCaptureButton.kt  (悬浮按钮管理)
├── backup/
│   ├── BackupModule.kt
│   ├── BackupPackage.kt
│   ├── BackupManager.kt
│   ├── ImportParser.kt
│   └── ExportManager.kt
└── util/
    ├── KeystoreHelper.kt       (API Key 加密/数据库密钥)
    ├── EventBridge.kt          (原生→RN 事件发射)
    └── JsonHelper.kt           (DTO 序列化)
```

---

## 2. 线程模型

### 2.1 线程职责划分

| 线程 | 职责 | 实现 |
|---|---|---|
| **主线程 (Main)** | UI 操作、RN Bridge 回调 | 切回主线程: `withContext(Dispatchers.Main)` |
| **IO 线程** | 数据库读写、文件操作、网络请求 | `Dispatchers.IO` |
| **无障碍回调线程** | onAccessibilityEvent 事件处理 | 系统回调线程,需快速返回 |
| **通知监听线程** | onNotificationPosted 事件处理 | 系统回调线程,需快速返回 |
| **AI 网络线程** | HTTP 请求/响应 | OkHttp 内部线程池 |
| **WorkManager 线程** | 健康检查、审计清理、预算检查 | 系统调度 |

### 2.2 线程切换规则

```kotlin
/**
 * 所有 Native Module 方法都在 IO 线程执行数据库操作
 * 回调结果切回主线程给 RN
 */
class DataModule(...) : ReactContextBaseJavaModule(reactContext) {

    @ReactMethod
    fun getBills(filterJson: String, promise: Promise) {
        scope.launch {
            try {
                // IO 线程执行数据库查询
                val result = withContext(Dispatchers.IO) {
                    val filter = parseBillFilter(filterJson)
                    billDao.query(filter)
                }
                // 主线程回调 RN
                withContext(Dispatchers.Main) {
                    promise.resolve(result.toJson())
                }
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }
}
```

### 2.3 并发控制

```kotlin
/**
 * 支付会话引擎使用 Mutex 保证同一会话操作的原子性
 */
class PaymentSessionEngine(...) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun updateSession(sessionId: String, block: suspend () -> Unit) {
        val mutex = sessionLocks.computeIfAbsent(sessionId) { Mutex() }
        mutex.withLock {
            block()
        }
    }
}

/**
 * 快照缓冲使用原子引用
 */
class SnapshotBuffer {
    private val currentSnapshot = AtomicReference<CheckoutSnapshot?>(null)

    fun put(snapshot: CheckoutSnapshot) {
        currentSnapshot.set(snapshot)
    }

    fun take(): CheckoutSnapshot? = currentSnapshot.getAndSet(null)
}
```

---

## 3. DTO 与序列化

### 3.1 序列化方案

使用 `org.json.JSONObject` 手动序列化(避免引入 Gson/Moshi 的额外体积),所有 DTO 提供 `toJson()` 和 `fromJson()` 方法。

```kotlin
/**
 * DTO 序列化基类
 */
interface JsonSerializable {
    fun toJson(): JSONObject
}

/**
 * JSON 辅助工具
 */
object JsonHelper {

    fun String.toJSONObjectOrNull(): JSONObject? = try {
        JSONObject(this)
    } catch (e: Exception) { null }

    fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    // Room stores epoch milliseconds; the RN contract uses ISO-8601 strings.
    fun parseIsoTimestamp(value: String): Long =
        java.time.Instant.parse(value).toEpochMilli()

    fun formatIsoTimestamp(value: Long): String =
        java.time.Instant.ofEpochMilli(value).toString()
}
```

### 3.2 Bill DTO

```kotlin
/**
 * 账单 DTO(RN ↔ 原生传输用)
 */
data class BillDTO(
    val id: String,
    val amountMinor: Long,
    val type: String,
    val occurredAt: String,          // RN 边界使用 ISO-8601; Room 内部保存毫秒 Long
    val description: String?,
    val categoryId: String?,
    val accountId: String?,
    val targetAccountId: String?,
    val platform: String?,
    val merchant: String?,
    val productDescription: String?,
    val sourceType: String,
    val originalTransactionId: String?,
    val orderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int
) : JsonSerializable {

    override fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("amountMinor", amountMinor)
        put("type", type)
        put("occurredAt", occurredAt)
        description?.let { put("description", it) }
        categoryId?.let { put("categoryId", it) }
        accountId?.let { put("accountId", it) }
        targetAccountId?.let { put("targetAccountId", it) }
        platform?.let { put("platform", it) }
        merchant?.let { put("merchant", it) }
        productDescription?.let { put("productDescription", it) }
        put("sourceType", sourceType)
        originalTransactionId?.let { put("originalTransactionId", it) }
        orderId?.let { put("orderId", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("version", version)
    }

    companion object {
        fun fromJson(json: JSONObject): BillDTO = BillDTO(
            id = json.getString("id"),
            amountMinor = json.getLong("amountMinor"),
            type = json.getString("type"),
            occurredAt = json.getString("occurredAt"),
            description = json.optStringOrNull("description"),
            categoryId = json.optStringOrNull("categoryId"),
            accountId = json.optStringOrNull("accountId"),
            targetAccountId = json.optStringOrNull("targetAccountId"),
            platform = json.optStringOrNull("platform"),
            merchant = json.optStringOrNull("merchant"),
            productDescription = json.optStringOrNull("productDescription"),
            sourceType = json.getString("sourceType"),
            originalTransactionId = json.optStringOrNull("originalTransactionId"),
            orderId = json.optStringOrNull("orderId"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            version = json.getInt("version")
        )
    }
}

/**
 * Entity → DTO 转换
 */
fun BillEntity.toDTO(): BillDTO = BillDTO(
    id = id,
    amountMinor = amountMinor,
    type = type,
    occurredAt = formatIsoTimestamp(occurredAt),
    description = description,
    categoryId = categoryId,
    accountId = accountId,
    targetAccountId = targetAccountId,
    platform = platform,
    merchant = merchant,
    productDescription = productDescription,
    sourceType = sourceType,
    originalTransactionId = originalTransactionId,
    orderId = orderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version
)
```

### 3.3 分页响应 DTO

```kotlin
/**
 * 分页响应 DTO
 */
data class PaginatedDTO<T : JsonSerializable>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean
) : JsonSerializable {

    override fun toJson(): JSONObject = JSONObject().apply {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        put("items", array)
        put("total", total)
        put("page", page)
        put("limit", limit)
        put("hasMore", hasMore)
    }
}
```

### 3.4 其他关键 DTO(概要)

AccountDTO、CategoryDTO、BudgetDTO、PendingTransactionDTO、CheckoutSessionDTO、AiProviderConfigDTO、RuntimeHealthDTO、NetWorthDTO 等遵循相同模式,字段一一对应 Entity,不再逐个展开。

---

## 4. DataModule 实现

### 4.1 模块注册

```kotlin
/**
 * 数据访问模块
 * 实现 Native.d.ts 中 DataModule 接口
 */
class DataModule(
    reactContext: ReactApplicationContext,
    private val database: JotBillDatabase
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Data"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ========== 账单 ==========

    @ReactMethod
    fun getBills(filterJson: String, promise: Promise) {
        scope.launch {
            try {
                val filter = JSONObject(filterJson)
                val bills = billDao.query(
                    type = filter.optStringOrNull("type"),
                    categoryId = filter.optStringOrNull("categoryId"),
                    accountId = filter.optStringOrNull("accountId"),
                    platform = filter.optStringOrNull("platform"),
                    sourceType = filter.optStringOrNull("sourceType"),
                    startDate = filter.optLongOrNull("startDate"),
                    endDate = filter.optLongOrNull("endDate"),
                    keyword = filter.optStringOrNull("keyword"),
                    orderBy = filter.optString("orderBy", "date"),
                    limit = filter.optInt("limit", 20),
                    offset = (filter.optInt("page", 1) - 1) * filter.optInt("limit", 20)
                )
                val dtoList = bills.map { it.toDTO() }
                val response = PaginatedDTO(
                    items = dtoList,
                    total = billDao.count(),
                    page = filter.optInt("page", 1),
                    limit = filter.optInt("limit", 20),
                    hasMore = false
                )
                promise.resolve(response.toJson().toString())
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun addBill(inputJson: String, promise: Promise) {
        scope.launch {
            try {
                val input = JSONObject(inputJson)
                val now = System.currentTimeMillis()
                val entity = BillEntity(
                    id = UUID.randomUUID().toString(),
                    amountMinor = input.getLong("amountMinor"),
                    type = input.getString("type"),
                    occurredAt = parseIsoTimestamp(input.getString("occurredAt")),
                    description = input.optStringOrNull("description"),
                    categoryId = input.optStringOrNull("categoryId"),
                    accountId = input.optStringOrNull("accountId"),
                    targetAccountId = input.optStringOrNull("targetAccountId"),
                    platform = input.optStringOrNull("platform"),
                    merchant = input.optStringOrNull("merchant"),
                    productDescription = input.optStringOrNull("productDescription"),
                    sourceType = input.getString("sourceType"),
                    originalTransactionId = input.optStringOrNull("originalTransactionId"),
                    orderId = input.optStringOrNull("orderId"),
                    createdAt = now,
                    updatedAt = now,
                    version = 1
                )
                // 事务:写入账单 + 更新余额
                createBillWithBalanceUpdate(entity, accountDao, auditDao)
                promise.resolve(entity.toDTO().toJson().toString())
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun updateBill(id: String, inputJson: String, promise: Promise) {
        scope.launch {
            try {
                val existing = billDao.getById(id) ?: throw Exception("Bill not found")
                val input = JSONObject(inputJson)
                val version = input.getInt("version")
                if (existing.version != version) {
                    promise.reject("VERSION_CONFLICT", "数据已被修改,请刷新后重试")
                    return@launch
                }
                val updated = existing.copy(
                    amountMinor = input.optLong("amountMinor", existing.amountMinor),
                    type = input.optString("type", existing.type),
                    occurredAt = if (input.has("occurredAt")) {
                        parseIsoTimestamp(input.getString("occurredAt"))
                    } else {
                        existing.occurredAt
                    },
                    description = input.optStringOrNull("description") ?: existing.description,
                    categoryId = input.optStringOrNull("categoryId") ?: existing.categoryId,
                    accountId = input.optStringOrNull("accountId") ?: existing.accountId,
                    platform = input.optStringOrNull("platform") ?: existing.platform,
                    merchant = input.optStringOrNull("merchant") ?: existing.merchant,
                    updatedAt = System.currentTimeMillis(),
                    version = version + 1
                )
                billDao.update(updated)
                promise.resolve(updated.toDTO().toJson().toString())
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun deleteBill(id: String, promise: Promise) {
        scope.launch {
            try {
                val bill = billDao.getById(id) ?: throw Exception("Bill not found")
                billDao.delete(bill)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getBillStatistics(filterJson: String, promise: Promise) {
        scope.launch {
            try {
                val filter = JSONObject(filterJson)
                val startDate = filter.optLong("startDate", 0)
                val endDate = filter.optLong("endDate", System.currentTimeMillis())
                val summary = billDao.getSummary(startDate, endDate)
                val categoryStats = billDao.getCategoryStats("expense", startDate, endDate)
                val platformStats = billDao.getPlatformStats(startDate, endDate)
                // 组装 BillStatistics JSON
                promise.resolve(/* statistics JSON */)
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    // ========== 待确认 ==========

    @ReactMethod
    fun getPendingTransactions(filterJson: String, promise: Promise) {
        scope.launch {
            val filter = JSONObject(filterJson)
            val page = filter.optInt("page", 1)
            val limit = filter.optInt("limit", 20)
            val items = pendingDao.getPage(limit, (page - 1) * limit)
            // 返回分页 JSON
            promise.resolve(/* paginated JSON */)
        }
    }

    @ReactMethod
    fun confirmPendingTransaction(id: String, inputJson: String, version: Int, promise: Promise) {
        scope.launch {
            try {
                val pending = pendingDao.getById(id) ?: throw Exception("Pending not found")
                if (pending.version != version) {
                    promise.reject("VERSION_CONFLICT", "数据已被修改")
                    return@launch
                }
                val input = JSONObject(inputJson)
                // 1. 创建正式账单
                val bill = createBillFromPending(pending, input)
                createBillWithBalanceUpdate(bill, accountDao, auditDao)
                // 2. 删除待确认记录
                pendingDao.delete(pending)
                promise.resolve(bill.toDTO().toJson().toString())
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun ignorePendingTransaction(id: String, version: Int, promise: Promise) {
        scope.launch {
            val pending = pendingDao.getById(id) ?: throw Exception("Pending not found")
            if (pending.version != version) {
                promise.reject("VERSION_CONFLICT", "数据已被修改")
                return@launch
            }
            pendingDao.delete(pending)
            promise.resolve(null)
        }
    }

    // ========== 账户 ==========

    @ReactMethod
    fun getAccounts(promise: Promise) {
        scope.launch {
            val accounts = accountDao.getAll()
            val array = JSONArray()
            accounts.forEach { array.put(it.toDTO().toJson()) }
            promise.resolve(array.toString())
        }
    }

    @ReactMethod
    fun addAccount(inputJson: String, promise: Promise) { /* ... */ }

    @ReactMethod
    fun updateAccount(id: String, inputJson: String, promise: Promise) { /* ... */ }

    @ReactMethod
    fun deleteAccount(id: String, promise: Promise) { /* ... */ }

    @ReactMethod
    fun calibrateAccountBalance(id: String, actualBalanceMinor: Long, note: String?, promise: Promise) {
        scope.launch {
            try {
                val account = accountDao.getById(id) ?: throw Exception("Account not found")
                val before = account.currentBalanceMinor
                val diff = actualBalanceMinor - before
                // 1. 更新余额
                accountDao.updateBalance(id, actualBalanceMinor, System.currentTimeMillis())
                // 2. 创建校准记录
                val calibration = BalanceCalibrationEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = id,
                    beforeBalanceMinor = before,
                    afterBalanceMinor = actualBalanceMinor,
                    diffMinor = diff,
                    note = note,
                    createdAt = System.currentTimeMillis()
                )
                calibrationDao.insert(calibration)
                promise.resolve(calibration.toDTO().toJson().toString())
            } catch (e: Exception) {
                promise.reject("DB_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun getNetWorth(promise: Promise) {
        scope.launch {
            val netWorth = accountDao.getNetWorth()
            val response = JSONObject().apply {
                put("totalAssetsMinor", netWorth.totalAssets)
                put("totalLiabilitiesMinor", netWorth.totalLiabilities)
                put("netWorthMinor", netWorth.totalAssets - netWorth.totalLiabilities)
                put("accountCount", accountDao.activeCount())
                put("updatedAt", System.currentTimeMillis())
            }
            promise.resolve(response.toString())
        }
    }

    // ========== 分类/预算/财务目标/规则 ==========
    // 模式与上述一致,省略

    companion object {
        const val NAME = "Data"
    }
}

/**
 * Package 注册
 */
class DataPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        val database = JotBillDatabaseProvider.get(reactContext)
        return listOf(DataModule(reactContext, database))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager> = emptyList()
}
```

---

## 5. RecognitionModule 实现

### 5.1 模块实现

```kotlin
/**
 * 识别状态管理模块
 * 实现 Native.d.ts 中 RecognitionModule 接口
 * 注意:无障碍事件不通过此模块传递,此模块只提供状态查询和操作
 */
class RecognitionModule(
    reactContext: ReactApplicationContext,
    private val database: JotBillDatabase,
    private val eventBridge: EventBridge
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Recognition"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @ReactMethod
    fun getCheckoutSessions(filterJson: String, promise: Promise) {
        scope.launch {
            val filter = JSONObject(filterJson)
            val statuses = filter.optJSONArray("status")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            val sessions = if (statuses != null) {
                sessionDao.getByStatuses(statuses)
            } else {
                sessionDao.getAll()
            }
            // 返回 JSON 数组
            promise.resolve(/* sessions JSON */)
        }
    }

    @ReactMethod
    fun retryAiRecognition(candidateId: String, promise: Promise) {
        scope.launch {
            try {
                val candidate = candidateDao.getById(candidateId)
                    ?: throw Exception("Candidate not found")
                val session = sessionDao.getById(candidate.sessionId)
                    ?: throw Exception("Session not found")
                // 重新触发 AI 识别
                aiVisionClient.recognize(session, candidate)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("RETRY_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun getRecognitionStatus(promise: Promise) {
        scope.launch {
            val activeSessions = sessionDao.countByStatuses(
                listOf("OBSERVING", "SNAPSHOT_BUFFERED", "AI_REQUESTING", "WAITING_ENRICHMENT")
            )
            val pendingCount = pendingDao.count()
            val aiRequesting = sessionDao.countByStatuses(listOf("AI_REQUESTING")) > 0
            val response = JSONObject().apply {
                put("activeSessions", activeSessions)
                put("pendingCount", pendingCount)
                put("aiRequesting", aiRequesting)
            }
            promise.resolve(response.toString())
        }
    }

    companion object {
        const val NAME = "Recognition"
    }
}
```

### 5.2 EventBridge(原生→RN 事件)

```kotlin
/**
 * 事件桥接器
 * 原生层通过此工具向 RN 发送语义事件
 * 注意:只发送低频语义事件,不发送原始无障碍/通知事件
 */
class EventBridge(private val reactContext: ReactApplicationContext) {

    fun emitCheckoutDetected(sessionId: String, shoppingPackage: String) {
        emit("onCheckoutDetected", JSONObject().apply {
            put("sessionId", sessionId)
            put("shoppingPackage", shoppingPackage)
        })
    }

    fun emitSnapshotCaptured(
        sessionId: String,
        snapshotId: String,
        sourceReason: String
    ) {
        emit("onSnapshotCaptured", JSONObject().apply {
            put("sessionId", sessionId)
            put("snapshotId", snapshotId)
            put("sourceReason", sourceReason)
        })
    }

    fun emitAiRecognitionCompleted(
        sessionId: String,
        candidateId: String,
        success: Boolean
    ) {
        emit("onAiRecognitionCompleted", JSONObject().apply {
            put("sessionId", sessionId)
            put("candidateId", candidateId)
            put("success", success)
        })
    }

    fun emitReviewRequired(pendingId: String, reason: String) {
        emit("onReviewRequired", JSONObject().apply {
            put("pendingId", pendingId)
            put("reason", reason)
        })
    }

    fun emitRuntimeHealthChanged(health: RuntimeHealth) {
        emit("onRuntimeHealthChanged", health.toJson())
    }

    fun emitBudgetAlert(budgetId: String, budgetName: String, progress: Double, isOverBudget: Boolean) {
        emit("onBudgetAlert", JSONObject().apply {
            put("budgetId", budgetId)
            put("budgetName", budgetName)
            put("progress", progress)
            put("isOverBudget", isOverBudget)
        })
    }

    private fun emit(eventName: String, data: JSONObject) {
        val params = Arguments.createMap().apply {
            // 将 JSONObject 转为 React Native ReadableMap
            putString("data", data.toString())
        }
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
}
```

---

## 6. AiModule 实现

```kotlin
/**
 * AI 服务商管理模块
 * 实现 Native.d.ts 中 AiModule 接口
 */
class AiModule(
    reactContext: ReactApplicationContext,
    private val database: JotBillDatabase,
    private val keystoreHelper: KeystoreHelper
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Ai"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @ReactMethod
    fun getAiProviders(promise: Promise) {
        scope.launch {
            val configs = aiProviderDao.getAll()
            val array = JSONArray()
            configs.forEach { config ->
                val dto = config.toDTO().copy(
                    apiKey = maskApiKey(config.apiKeyEncrypted)  // 返回掩码
                )
                array.put(dto.toJson())
            }
            promise.resolve(array.toString())
        }
    }

    @ReactMethod
    fun addAiProvider(inputJson: String, promise: Promise) {
        scope.launch {
            try {
                val input = JSONObject(inputJson)
                val apiKey = input.getString("apiKey")
                val encryptedKey = keystoreHelper.encrypt(apiKey)
                val config = AiProviderConfigEntity(
                    id = UUID.randomUUID().toString(),
                    name = input.getString("name"),
                    provider = input.getString("provider"),
                    model = input.getString("model"),
                    apiKeyEncrypted = encryptedKey,
                    apiBaseUrl = input.optStringOrNull("apiBaseUrl"),
                    isDefault = input.optBoolean("isDefault", false),
                    supportsVision = input.getBoolean("supportsVision"),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                aiProviderDao.insert(config)
                promise.resolve(config.toDTO().toJson().toString())
            } catch (e: Exception) {
                promise.reject("AI_CONFIG_ERROR", e.message)
            }
        }
    }

    @ReactMethod
    fun testAiProvider(id: String, promise: Promise) {
        scope.launch {
            try {
                val config = aiProviderDao.getById(id) ?: throw Exception("Config not found")
                val apiKey = keystoreHelper.decrypt(config.apiKeyEncrypted)
                val adapter = AdapterFactory.create(config.provider)
                val result = adapter.testConnection(config.copy(apiKeyEncrypted = apiKey))
                val response = JSONObject().apply {
                    put("success", result.success)
                    put("message", result.message)
                    result.latencyMs?.let { put("latencyMs", it) }
                }
                promise.resolve(response.toString())
            } catch (e: Exception) {
                promise.reject("AI_TEST_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun getAiBudget(promise: Promise) {
        scope.launch {
            val month = SimpleDateFormat("yyyy-MM", Locale.getDefault())
                .format(Date())
            val spent = aiCostDao.getMonthlyCost(month)
            val requestCount = aiCostDao.getMonthlySuccessCount(month)
            val budget = settingDao.get("ai_monthly_budget_minor")?.toLong() ?: 1000
            val response = JSONObject().apply {
                put("monthlyBudgetMinor", budget)
                put("monthlySpentMinor", spent)
                put("remainingMinor", budget - spent)
                put("requestCount", requestCount)
                put("month", month)
            }
            promise.resolve(response.toString())
        }
    }

    @ReactMethod
    fun setAiBudget(monthlyBudgetMinor: Long, promise: Promise) {
        scope.launch {
            settingDao.set(AppSettingEntity(
                key = "ai_monthly_budget_minor",
                value = monthlyBudgetMinor.toString(),
                updatedAt = System.currentTimeMillis()
            ))
            promise.resolve(null)
        }
    }

    private fun maskApiKey(encrypted: String?): String? {
        if (encrypted == null) return null
        val decrypted = keystoreHelper.decrypt(encrypted)
        return if (decrypted.length > 8) {
            decrypted.substring(0, 4) + "****" + decrypted.substring(decrypted.length - 4)
        } else {
            "****"
        }
    }

    companion object {
        const val NAME = "Ai"
    }
}
```

### 6.1 适配器工厂

```kotlin
/**
 * 适配器工厂
 * 根据服务商标识创建对应适配器
 */
object AdapterFactory {
    fun create(provider: AiProvider): AiProviderAdapter {
        return when (provider) {
            AiProvider.QWEN -> QwenAdapter()
            AiProvider.GLM -> GlmAdapter()
            AiProvider.SILICONFLOW -> SiliconFlowAdapter()
            AiProvider.CUSTOM -> throw IllegalArgumentException("Custom provider requires manual adapter")
        }
    }
}
```

---

## 7. RuntimeModule 实现

> 阶段二只承诺 `getRuntimeHealth()` 返回数据库版本、账单/分类/账户数量和启动时间等基础摘要。系统权限跳转和完整健康检查属于后续阶段，当前公共契约以 `docs/types/Native.d.ts` 为准。

```kotlin
/**
 * 运行健康状态模块
 */
class RuntimeModule(
    reactContext: ReactApplicationContext,
    private val database: JotBillDatabase,
    private val eventBridge: EventBridge
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Runtime"

    @ReactMethod
    fun getRuntimeHealth(promise: Promise) {
        scope.launch {
            val health = checkHealth()
            promise.resolve(health.toJson().toString())
        }
    }

    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun openNotificationSettings(promise: Promise) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun openOverlaySettings(promise: Promise) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + reactContext.packageName)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun openBatteryOptimizationSettings(promise: Promise) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    @ReactMethod
    fun openUsageStatsSettings(promise: Promise) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        promise.resolve(null)
    }

    private suspend fun checkHealth(): RuntimeHealth {
        val ctx = reactContext
        return RuntimeHealth(
            accessibilityEnabled = isAccessibilityEnabled(),
            notificationEnabled = isNotificationEnabled(),
            overlayPermissionGranted = Settings.canDrawOverlays(ctx),
            usageStatsEnabled = hasUsageStatsPermission(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            aiProviderConfigured = aiProviderDao.getDefault() != null,
            accessibilityServiceRunning = isAccessibilityServiceRunning(),
            notificationServiceRunning = isNotificationServiceRunning(),
            backgroundRestrictions = false,  // 检查后台限制
            aiAvailable = true,  // 检查网络
            aiBudgetRemaining = checkAiBudgetRemaining(),
            lastAiError = null,
            checkedAt = System.currentTimeMillis()
        )
    }

    companion object {
        const val NAME = "Runtime"
    }
}
```

---

## 8. OverlayModule 实现

```kotlin
/**
 * 悬浮按钮控制模块
 */
class OverlayModule(
    reactContext: ReactApplicationContext,
    private val buttonManager: ManualCaptureButtonManager
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Overlay"

    @ReactMethod
    fun setManualCaptureButtonEnabled(enabled: Boolean, promise: Promise) {
        scope.launch {
            if (enabled) {
                buttonManager.show()
            } else {
                buttonManager.hide()
            }
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun setManualCaptureButtonMode(mode: String, promise: Promise) {
        scope.launch {
            buttonManager.setMode(OverlayMode.valueOf(mode.uppercase()))
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getManualCaptureButtonMode(promise: Promise) {
        promise.resolve(buttonManager.getMode().name.lowercase())
    }

    companion object {
        const val NAME = "Overlay"
    }
}
```

---

## 9. BackupModule 实现

> 阶段二只落地 CSV 导出和清除本地数据；完整备份、恢复预览、恢复执行和文件导入在后续阶段实现，当前公共契约以 `docs/types/Native.d.ts` 为准。

```kotlin
/**
 * 备份恢复模块
 */
class BackupModule(
    reactContext: ReactApplicationContext,
    private val database: JotBillDatabase,
    private val backupManager: BackupManager
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "Backup"

    @ReactMethod
    fun createBackup(optionsJson: String, promise: Promise) {
        scope.launch {
            try {
                val options = JSONObject(optionsJson)
                val encrypt = options.optBoolean("encrypt", false)
                val summary = backupManager.createBackup(encrypt)
                promise.resolve(summary.toJson().toString())
            } catch (e: Exception) {
                promise.reject("BACKUP_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun restoreBackup(filePath: String, promise: Promise) {
        scope.launch {
            try {
                val preview = backupManager.previewRestore(filePath)
                promise.resolve(preview.toJson().toString())
            } catch (e: Exception) {
                promise.reject("RESTORE_PREVIEW_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun executeRestore(filePath: String, strategy: String, promise: Promise) {
        scope.launch {
            try {
                val result = backupManager.executeRestore(filePath, strategy)
                promise.resolve(result.toJson().toString())
            } catch (e: Exception) {
                promise.reject("RESTORE_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun exportData(format: String, promise: Promise) {
        scope.launch {
            try {
                val filePath = backupManager.export(format)
                promise.resolve(JSONObject().put("filePath", filePath).toString())
            } catch (e: Exception) {
                promise.reject("EXPORT_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun importData(filePath: String, promise: Promise) {
        scope.launch {
            try {
                val result = backupManager.import(filePath)
                promise.resolve(result.toJson().toString())
            } catch (e: Exception) {
                promise.reject("IMPORT_FAILED", e.message)
            }
        }
    }

    companion object {
        const val NAME = "Backup"
    }
}
```

---

## 10. MainApplication 注册

```kotlin
/**
 * 应用入口
 * 注册 6 个 Native Package
 */
class MainApplication : Application(), ReactApplication {

    override val reactNativeHost: ReactNativeHost = object : ReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> {
            val database = JotBillDatabaseProvider.get(this@MainApplication)
            val keystoreHelper = KeystoreHelper(this@MainApplication)
            val eventBridge = EventBridge(reactApplicationContext)
            val buttonManager = ManualCaptureButtonManager(this@MainApplication, eventBridge)

            return listOf(
                PackageList().apply { /* 自动添加第三方包 */ },
                DataPackage(database),
                RecognitionPackage(database, eventBridge),
                AiPackage(database, keystoreHelper),
                RuntimePackage(database, eventBridge),
                OverlayPackage(buttonManager),
                BackupPackage(database)
            ).flatten()
        }
    }
}
```

---

## 11. RN 侧封装

### 11.1 NativeModule 包装器

```typescript
// src/services/native/index.ts

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { Data, Recognition, Ai, Runtime, Overlay, Backup } = NativeModules;

/**
 * 数据访问服务
 * 替代 LiteNote 的 billsService/accountsService 等 HTTP 调用
 */
export const dataService = {
  // 账单
  getBills: (filter?: BillFilter): Promise<PaginatedResponse<Bill>> =>
    Data.getBills(JSON.stringify(filter ?? {})).then(parsePaginated),

  getBillById: (id: string): Promise<Bill> =>
    Data.getBillById(id).then(parseJson),

  addBill: (input: BillInput): Promise<Bill> =>
    Data.addBill(JSON.stringify(input)).then(parseJson),

  updateBill: (id: string, input: BillUpdateInput): Promise<Bill> =>
    Data.updateBill(id, JSON.stringify(input)).then(parseJson),

  deleteBill: (id: string): Promise<void> => Data.deleteBill(id),

  // 账户
  getAccounts: (): Promise<Account[]> =>
    Data.getAccounts().then(parseJsonArray),

  getNetWorth: (): Promise<NetWorthSummary> =>
    Data.getNetWorth().then(parseJson),

  calibrateAccountBalance: (id: string, balance: number, note?: string): Promise<void> =>
    Data.calibrateAccountBalance(id, balance, note ?? null),

  // 分类/预算/规则等同理
};

/**
 * 识别状态服务
 */
export const recognitionService = {
  getRecognitionStatus: () => Recognition.getRecognitionStatus().then(parseJson),
  getCheckoutSessions: (filter?) => Recognition.getCheckoutSessions(JSON.stringify(filter ?? {})).then(parseJsonArray),
  retryAiRecognition: (candidateId: string) => Recognition.retryAiRecognition(candidateId),
};

/**
 * 事件订阅
 */
export const recognitionEvents = new NativeEventEmitter(Recognition);

export function subscribeRecognitionEvent(
  eventName: keyof RecognitionEvents,
  handler: Function
) {
  return recognitionEvents.addListener(eventName, (params: { data: string }) => {
    handler(JSON.parse(params.data));
  });
}
```

### 11.2 React Query 集成

```typescript
// src/hooks/useBills.ts

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { dataService } from '@/services/native';

/**
 * 账单列表 Hook
 * 替代 LiteNote 的 useQuery(() => billsService.getBills(filter))
 * 唯一变化:fetcher 从 HTTP 改为 Native Module 调用
 */
export function useBills(filter?: BillFilter) {
  return useQuery({
    queryKey: ['bills', filter],
    queryFn: () => dataService.getBills(filter),
  });
}

export function useAddBill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: BillInput) => dataService.addBill(input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['bills'] });
      queryClient.invalidateQueries({ queryKey: ['billStatistics'] });
      queryClient.invalidateQueries({ queryKey: ['netWorth'] });
    },
  });
}
```

---

## 12. 与 LiteNote 的对接差异总结

| LiteNote 方式 | JotBill 方式 | 改造点 |
|---|---|---|
| `axios.get('/bills')` | `Data.getBills(filter)` | HTTP → Native Module |
| `billsService.getBills()` | `dataService.getBills()` | Service 层重写 |
| React Query fetcher 调 HTTP | React Query fetcher 调 Native | 只改 fetcher,保留缓存机制 |
| MobX store 存 userId | MobX store 去掉 userId | 去账号体系 |
| `react-native-sse` 消费后端 SSE | 原生直连服务商 + EventBridge | SSE 改造(阶段二) |
| JWT token 存 AsyncStorage | 无 token,API Key 存 Keystore | 认证改造 |
| `PaymentNotificationService` 调后端 API | 写 Room + 通知 RecognitionEngine | 通知服务改造 |
