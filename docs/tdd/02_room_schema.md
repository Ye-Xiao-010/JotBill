# 02. Room 数据库 Schema

> 技术实现文档(TDD)第二部分
>
> 关联文档:NEW_PROJECT_KICKOFF_V2.md 第10节、types/Native.d.ts
>
> 作用:将设计文档数据模型转化为 Room Entity/DAO/索引/外键/Migration,作为数据层实现基础

---

## 1. 数据库配置

### 1.1 基本配置

```kotlin
/**
 * JotBill 主数据库
 * 使用 SQLCipher 加密,主密钥由 Keystore 派生
 */
@Database(
    entities = [
        BillEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        FinancialGoalEntity::class,
        RuleEntity::class,
        PendingTransactionEntity::class,
        CheckoutSessionEntity::class,
        TransactionCandidateEntity::class,
        PaymentEvidenceEntity::class,
        AiProviderConfigEntity::class,
        AiCostRecordEntity::class,
        BalanceCalibrationEntity::class,
        AuditEntryEntity::class,
        AppSettingEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class JotBillDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun financialGoalDao(): FinancialGoalDao
    abstract fun ruleDao(): RuleDao
    abstract fun pendingDao(): PendingTransactionDao
    abstract fun sessionDao(): CheckoutSessionDao
    abstract fun candidateDao(): TransactionCandidateDao
    abstract fun evidenceDao(): PaymentEvidenceDao
    abstract fun aiProviderDao(): AiProviderConfigDao
    abstract fun aiCostDao(): AiCostRecordDao
    abstract fun calibrationDao(): BalanceCalibrationDao
    abstract fun auditDao(): AuditEntryDao
    abstract fun settingDao(): AppSettingDao
}
```

### 1.2 SQLCipher 加密

```kotlin
/**
 * 数据库加密配置
 * 使用 SQLCipher,主密钥由 Android Keystore 派生
 */
fun createDatabase(context: Context): JotBillDatabase {
    val passphrase = getOrCreateDatabaseKey(context)  // 从 Keystore 派生
    val factory = SupportFactorySQLiteDatabase(passphrase)

    return Room.databaseBuilder(
        context,
        JotBillDatabase::class.java,
        "jotbill.db"
    )
    .openHelperFactory(factory)
    .addMigrations(*ALL_MIGRATIONS)
    .fallbackToDestructiveMigration()  // 仅开发期,生产期用 fallbackToDestructiveMigrationOnDowngrade
    .build()
}

/**
 * 从 Android Keystore 派生数据库密钥
 * 密钥不可跨设备迁移,换机时需通过备份恢复数据
 */
fun getOrCreateDatabaseKey(context: Context): ByteArray {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // 若密钥不存在则生成 AES-256 密钥
    // 用该密钥加密一个随机生成的数据库密钥
    // 返回解密后的数据库密钥
}
```

### 1.3 类型转换器

```kotlin
/**
 * Room 类型转换器
 * 处理枚举和复杂类型与 SQLite 的转换
 */
class Converters {

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        TransactionType.valueOf(value)

    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionSource(value: String): TransactionSource =
        TransactionSource.valueOf(value)

    @TypeConverter
    fun fromTransactionSource(source: TransactionSource): String = source.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus =
        SessionStatus.valueOf(value)

    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String = status.name

    @TypeConverter
    fun toAccountSubtype(value: String): AccountSubtype =
        AccountSubtype.valueOf(value)

    @TypeConverter
    fun fromAccountSubtype(subtype: AccountSubtype): String = subtype.name

    @TypeConverter
    fun toAccountKind(value: String): AccountKind =
        AccountKind.valueOf(value)

    @TypeConverter
    fun fromAccountKind(kind: AccountKind): String = kind.name

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")

    @TypeConverter
    fun fromStringList(list: List<String>): String = list.joinToString(",")
}
```

---

## 2. Entity 定义

### 2.1 BillEntity(账单)

```kotlin
/**
 * 账单实体
 * 对应设计文档 10.5 节 Transaction,扩展自 LiteNote BillData
 */
@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["category_id"]),
        Index(value = ["account_id"]),
        Index(value = ["platform"]),
        Index(value = ["order_id"]),
        Index(value = ["source_type"]),
        Index(value = ["original_transaction_id"]),
        Index(value = ["occurred_at", "type"])  // 统计查询优化
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_account_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BillEntity(
    @PrimaryKey
    val id: String,                    // UUID

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,             // 金额(分)

    @ColumnInfo(name = "type")
    val type: String,                  // TransactionType 枚举名

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,              // 发生时间(时间戳毫秒)

    val description: String?,          // 描述

    @ColumnInfo(name = "category_id")
    val categoryId: String?,           // 分类ID

    @ColumnInfo(name = "account_id")
    val accountId: String?,            // 资金账户ID

    @ColumnInfo(name = "target_account_id")
    val targetAccountId: String?,      // 转账目标账户ID

    val platform: String?,             // 消费平台
    val merchant: String?,             // 商户(场景相关)

    @ColumnInfo(name = "product_description")
    val productDescription: String?,   // 商品说明

    @ColumnInfo(name = "source_type")
    val sourceType: String,            // TransactionSource 枚举名

    @ColumnInfo(name = "original_transaction_id")
    val originalTransactionId: String?,  // 退款关联的原交易ID

    @ColumnInfo(name = "order_id")
    val orderId: String?,              // 订单号(去重关键)

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    val version: Int                   // 乐观锁版本号
)
```

### 2.2 AccountEntity(账户)

```kotlin
/**
 * 账户实体
 * 对应设计文档 5.3 节,流派B:有期初余额和当前余额
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["sort_order"]),
        Index(value = ["is_active"])
    ]
)
data class AccountEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    val subtype: String,               // AccountSubtype 枚举名

    val kind: String,                  // AccountKind 枚举名(asset/liability)

    @ColumnInfo(name = "initial_balance_minor")
    val initialBalanceMinor: Long,     // 期初余额(分)

    @ColumnInfo(name = "current_balance_minor")
    val currentBalanceMinor: Long,     // 当前余额(分),系统维护

    val currency: String,              // 默认 CNY

    val icon: String?,
    val color: String?,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,             // 启用/停用

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.3 CategoryEntity(分类)

```kotlin
/**
 * 分类实体
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["sort_order"])
    ]
)
data class CategoryEntity(
    @PrimaryKey
    val id: String,

    val name: String,
    val type: String,                  // income/expense
    val icon: String?,
    val color: String?,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.4 BudgetEntity(预算)

```kotlin
/**
 * 预算实体
 */
@Entity(
    tableName = "budgets",
    indices = [
        Index(value = ["category_id"]),
        Index(value = ["is_active"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class BudgetEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    val period: String,                // monthly/yearly

    @ColumnInfo(name = "category_id")
    val categoryId: String?,

    @ColumnInfo(name = "alert_at")
    val alertAt: Int,                  // 预警阈值百分比(0-100)

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.5 FinancialGoalEntity(财务目标)

```kotlin
/**
 * 财务目标实体
 */
@Entity(tableName = "financial_goals")
data class FinancialGoalEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    @ColumnInfo(name = "target_amount_minor")
    val targetAmountMinor: Long,

    @ColumnInfo(name = "current_amount_minor")
    val currentAmountMinor: Long,

    @ColumnInfo(name = "target_date")
    val targetDate: Long?,             // 时间戳毫秒,null 表示无期限

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.6 RuleEntity(规则)

```kotlin
/**
 * 规则实体
 * 对应设计文档 FR-050
 */
@Entity(
    tableName = "rules",
    indices = [
        Index(value = ["is_active"]),
        Index(value = ["priority"])
    ]
)
data class RuleEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    val condition: String,             // RuleCondition 枚举名

    @ColumnInfo(name = "condition_value")
    val conditionValue: String,

    val action: String,                // RuleAction 枚举名

    @ColumnInfo(name = "action_value")
    val actionValue: String?,

    val priority: Int,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.7 PendingTransactionEntity(待确认交易)

```kotlin
/**
 * 待确认交易实体
 * 对应设计文档第9节
 */
@Entity(
    tableName = "pending_transactions",
    indices = [
        Index(value = ["candidate_id"]),
        Index(value = ["session_id"]),
        Index(value = ["created_at"])
    ]
)
data class PendingTransactionEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "candidate_id")
    val candidateId: String?,

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long,

    val type: String,                  // TransactionType 枚举名

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    val platform: String?,
    val merchant: String?,

    @ColumnInfo(name = "product_description")
    val productDescription: String?,

    @ColumnInfo(name = "order_id")
    val orderId: String?,

    @ColumnInfo(name = "account_id")
    val accountId: String?,

    @ColumnInfo(name = "category_id")
    val categoryId: String?,

    @ColumnInfo(name = "conflict_reason")
    val conflictReason: String?,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    val version: Int
)
```

### 2.8 CheckoutSessionEntity(支付会话)

```kotlin
/**
 * 支付会话实体
 * 对应设计文档 10.1 节
 * 注意:快照图片不持久化,仅存在于内存
 */
@Entity(
    tableName = "checkout_sessions",
    indices = [
        Index(value = ["status"]),
        Index(value = ["expires_at"]),
        Index(value = ["checkout_request_key"], unique = true)
    ]
)
data class CheckoutSessionEntity(
    @PrimaryKey
    val id: String,                    // sessionId

    val status: String,                // SessionStatus 枚举名

    @ColumnInfo(name = "shopping_package")
    val shoppingPackage: String?,

    @ColumnInfo(name = "page_role")
    val pageRole: String?,

    @ColumnInfo(name = "page_fingerprint")
    val pageFingerprint: String?,

    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String?,           // 引用内存中的快照

    @ColumnInfo(name = "checkout_request_key")
    val checkoutRequestKey: String?,   // 请求幂等键

    @ColumnInfo(name = "ai_request_id")
    val aiRequestId: String?,

    @ColumnInfo(name = "candidate_id")
    val candidateId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long
)
```

### 2.9 TransactionCandidateEntity(交易候选)

```kotlin
/**
 * 交易候选实体
 * 对应设计文档 10.3 节
 */
@Entity(
    tableName = "transaction_candidates",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["matched"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CheckoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TransactionCandidateEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long?,

    val platform: String?,
    val merchant: String?,

    @ColumnInfo(name = "product_description")
    val productDescription: String?,

    @ColumnInfo(name = "evidence_amount_minor")
    val evidenceAmountMinor: Long?,

    @ColumnInfo(name = "evidence_merchant")
    val evidenceMerchant: String?,

    @ColumnInfo(name = "evidence_order_id")
    val evidenceOrderId: String?,

    val matched: Boolean,

    @ColumnInfo(name = "conflict_reason")
    val conflictReason: String?,

    @ColumnInfo(name = "applied_rules")
    val appliedRules: String,          // 逗号分隔的规则ID

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    val version: Int
)
```

### 2.10 PaymentEvidenceEntity(支付证据)

```kotlin
/**
 * 支付证据实体
 * 对应设计文档 9.2 节
 */
@Entity(
    tableName = "payment_evidence",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["order_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CheckoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PaymentEvidenceEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String,            // EvidenceSourceType 枚举名

    @ColumnInfo(name = "amount_minor")
    val amountMinor: Long?,

    val merchant: String?,
    val platform: String?,

    @ColumnInfo(name = "order_id")
    val orderId: String?,

    @ColumnInfo(name = "payment_method")
    val paymentMethod: String?,

    @ColumnInfo(name = "payment_status")
    val paymentStatus: String?,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long?,

    @ColumnInfo(name = "raw_text_hash")
    val rawTextHash: String?,          // 原文哈希(不存原文)

    @ColumnInfo(name = "captured_at")
    val capturedAt: Long
)
```

### 2.11 AiProviderConfigEntity(AI 服务商配置)

```kotlin
/**
 * AI 服务商配置实体
 * API Key 使用 Keystore 加密存储,不存明文
 */
@Entity(
    tableName = "ai_provider_configs",
    indices = [
        Index(value = ["is_default"])
    ]
)
data class AiProviderConfigEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    val provider: String,              // AiProvider 枚举名

    val model: String,

    @ColumnInfo(name = "api_key_encrypted")
    val apiKeyEncrypted: String?,      // 加密后的 API Key

    @ColumnInfo(name = "api_base_url")
    val apiBaseUrl: String?,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,

    @ColumnInfo(name = "supports_vision")
    val supportsVision: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 2.12 AiCostRecordEntity(AI 成本记录)

```kotlin
/**
 * AI 成本记录实体
 * 用于月度预算控制和成本统计
 */
@Entity(
    tableName = "ai_cost_records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["provider"]),
        Index(value = ["month"])       // YYYY-MM 格式,用于月度查询
    ]
)
data class AiCostRecordEntity(
    @PrimaryKey
    val id: String,

    val provider: String,              // AiProvider 枚举名
    val model: String,

    @ColumnInfo(name = "request_type")
    val requestType: String,           // vision/chat/asr

    @ColumnInfo(name = "tokens_in")
    val tokensIn: Int?,

    @ColumnInfo(name = "tokens_out")
    val tokensOut: Int?,

    @ColumnInfo(name = "cost_minor")
    val costMinor: Long,               // 成本(分)

    val success: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    val month: String                  // YYYY-MM,用于月度查询
)
```

### 2.13 BalanceCalibrationEntity(余额校准记录)

```kotlin
/**
 * 余额校准记录实体
 * 对应设计文档 5.3 节余额校准
 */
@Entity(
    tableName = "balance_calibrations",
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["created_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BalanceCalibrationEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "account_id")
    val accountId: String,

    @ColumnInfo(name = "before_balance_minor")
    val beforeBalanceMinor: Long,

    @ColumnInfo(name = "after_balance_minor")
    val afterBalanceMinor: Long,

    @ColumnInfo(name = "diff_minor")
    val diffMinor: Long,

    val note: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
```

### 2.14 AuditEntryEntity(审计日志)

```kotlin
/**
 * 审计日志实体
 * 保留 90 天,超期自动清理
 */
@Entity(
    tableName = "audit_entries",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["entity_type"]),
        Index(value = ["entity_id"])
    ]
)
data class AuditEntryEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String,            // bill/account/category/rule 等

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    val action: String,                // create/update/delete/confirm/ignore 等

    @ColumnInfo(name = "field_name")
    val fieldName: String?,            // 修改的字段名(update 时)

    @ColumnInfo(name = "old_value")
    val oldValue: String?,             // 修改前的值

    @ColumnInfo(name = "new_value")
    val newValue: String?,             // 修改后的值

    val operator: String,              // system/user/ai

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
```

### 2.15 AppSettingEntity(应用设置)

```kotlin
/**
 * 应用设置实体
 * 键值对存储,用于全局配置
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey
    val key: String,

    val value: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

**预定义设置项:**

| key | 说明 | 默认值 |
|---|---|---|
| `ai_monthly_budget_minor` | AI 月度预算(分) | 1000(10元) |
| `ai_min_interval_ms` | AI 调用最小间隔(毫秒) | 5000 |
| `ai_daily_limit` | AI 日调用上限 | 100 |
| `overlay_mode` | 悬浮按钮模式 | always_on |
| `overlay_enabled` | 悬浮按钮是否启用 | true |
| `snapshot_timeout_ms` | 快照过期时间(毫秒) | 30000 |
| `session_timeout_ms` | 会话超时时间(毫秒) | 300000(5分钟) |
| `budget_alert_enabled` | 预算提醒是否启用 | true |
| `first_run_completed` | 首次启动向导是否完成 | false |
| `db_version` | 数据库版本号(用于迁移) | 1 |

---

## 3. DAO 接口

### 3.1 BillDao

```kotlin
@Dao
interface BillDao {

    @Insert
    suspend fun insert(bill: BillEntity): String

    @Update
    suspend fun update(bill: BillEntity)

    @Delete
    suspend fun delete(bill: BillEntity)

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: String): BillEntity?

    @Query("SELECT * FROM bills WHERE id = :id")
    fun observeById(id: String): Flow<BillEntity?>

    /**
     * 分页查询账单
     * 对应 Native.d.ts DataModule.getBills
     */
    @Query("""
        SELECT * FROM bills
        WHERE (:type IS NULL OR type = :type)
        AND (:categoryId IS NULL OR category_id = :categoryId)
        AND (:accountId IS NULL OR account_id = :accountId)
        AND (:platform IS NULL OR platform = :platform)
        AND (:sourceType IS NULL OR source_type = :sourceType)
        AND (:startDate IS NULL OR occurred_at >= :startDate)
        AND (:endDate IS NULL OR occurred_at <= :endDate)
        AND (:keyword IS NULL OR description LIKE '%' || :keyword || '%'
             OR merchant LIKE '%' || :keyword || '%'
             OR product_description LIKE '%' || :keyword || '%')
        ORDER BY
            CASE WHEN :orderBy = 'date' THEN occurred_at END DESC,
            CASE WHEN :orderBy = 'amount' THEN amount_minor END DESC,
            CASE WHEN :orderBy = 'createdAt' THEN created_at END DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun query(
        type: String?,
        categoryId: String?,
        accountId: String?,
        platform: String?,
        sourceType: String?,
        startDate: Long?,
        endDate: Long?,
        keyword: String?,
        orderBy: String,
        limit: Int,
        offset: Int
    ): List<BillEntity>

    @Query("SELECT COUNT(*) FROM bills")
    suspend fun count(): Int

    /**
     * 统计:按类型汇总
     */
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN type = 'income' THEN amount_minor ELSE 0 END), 0) as totalIncome,
            COALESCE(SUM(CASE WHEN type = 'expense' THEN amount_minor ELSE 0 END), 0) as totalExpense,
            COUNT(CASE WHEN type = 'income' THEN 1 END) as incomeCount,
            COUNT(CASE WHEN type = 'expense' THEN 1 END) as expenseCount
        FROM bills
        WHERE occurred_at >= :startDate AND occurred_at <= :endDate
    """)
    suspend fun getSummary(startDate: Long, endDate: Long): BillSummary

    /**
     * 统计:按分类汇总
     */
    @Query("""
        SELECT b.category_id as categoryId, c.name as categoryName,
               c.icon as categoryIcon,
               SUM(b.amount_minor) as amountMinor,
               COUNT(*) as count
        FROM bills b
        LEFT JOIN categories c ON b.category_id = c.id
        WHERE b.type = :type
        AND b.occurred_at >= :startDate AND b.occurred_at <= :endDate
        GROUP BY b.category_id
        ORDER BY amountMinor DESC
    """)
    suspend fun getCategoryStats(
        type: String,
        startDate: Long,
        endDate: Long
    ): List<CategoryStatRow>

    /**
     * 统计:按平台汇总
     */
    @Query("""
        SELECT platform, SUM(amount_minor) as amountMinor, COUNT(*) as count
        FROM bills
        WHERE type = 'expense'
        AND occurred_at >= :startDate AND occurred_at <= :endDate
        AND platform IS NOT NULL
        GROUP BY platform
        ORDER BY amountMinor DESC
    """)
    suspend fun getPlatformStats(
        startDate: Long,
        endDate: Long
    ): List<PlatformStatRow>

    /**
     * 去重检查:订单号+金额
     */
    @Query("SELECT * FROM bills WHERE order_id = :orderId AND amount_minor = :amountMinor LIMIT 1")
    suspend fun findByOrderAndAmount(orderId: String, amountMinor: Long): BillEntity?

    /**
     * 退款关联:查找原交易
     */
    @Query("""
        SELECT * FROM bills
        WHERE order_id = :orderId
        AND type = 'expense'
        ORDER BY occurred_at DESC
        LIMIT 1
    """)
    suspend fun findOriginalForRefund(orderId: String): BillEntity?
}
```

### 3.2 AccountDao

```kotlin
@Dao
interface AccountDao {

    @Insert
    suspend fun insert(account: AccountEntity): String

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT * FROM accounts ORDER BY sort_order ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE is_active = 1 ORDER BY sort_order ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    /**
     * 更新账户余额(入账时调用,在事务中执行)
     */
    @Query("UPDATE accounts SET current_balance_minor = :newBalance, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBalance(id: String, newBalance: Long, updatedAt: Long)

    /**
     * 净资产汇总
     */
    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN kind = 'asset' THEN current_balance_minor ELSE 0 END), 0) as totalAssets,
            COALESCE(SUM(CASE WHEN kind = 'liability' THEN ABS(current_balance_minor) ELSE 0 END), 0) as totalLiabilities
        FROM accounts
        WHERE is_active = 1
    """)
    suspend fun getNetWorth(): NetWorthRow

    @Query("SELECT COUNT(*) FROM accounts WHERE is_active = 1")
    suspend fun activeCount(): Int
}
```

### 3.3 其他 DAO(概要)

其他 DAO 遵循相同模式,这里列出关键方法:

```kotlin
@Dao
interface CategoryDao {
    @Insert suspend fun insert(category: CategoryEntity): String
    @Update suspend fun update(category: CategoryEntity)
    @Delete suspend fun delete(category: CategoryEntity)
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sort_order ASC")
    suspend fun getByType(type: String): List<CategoryEntity>
    @Query("SELECT * FROM categories ORDER BY sort_order ASC")
    suspend fun getAll(): List<CategoryEntity>
}

@Dao
interface BudgetDao {
    @Insert suspend fun insert(budget: BudgetEntity): String
    @Update suspend fun update(budget: BudgetEntity)
    @Delete suspend fun delete(budget: BudgetEntity)
    @Query("SELECT * FROM budgets WHERE is_active = 1")
    suspend fun getActive(): List<BudgetEntity>
}

@Dao
interface PendingTransactionDao {
    @Insert suspend fun insert(pending: PendingTransactionEntity): String
    @Update suspend fun update(pending: PendingTransactionEntity)
    @Delete suspend fun delete(pending: PendingTransactionEntity)
    @Query("SELECT * FROM pending_transactions ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<PendingTransactionEntity>
    @Query("SELECT COUNT(*) FROM pending_transactions")
    suspend fun count(): Int
    @Query("SELECT * FROM pending_transactions WHERE id = :id")
    suspend fun getById(id: String): PendingTransactionEntity?
}

@Dao
interface CheckoutSessionDao {
    @Insert suspend fun insert(session: CheckoutSessionEntity): String
    @Update suspend fun update(session: CheckoutSessionEntity)
    @Query("SELECT * FROM checkout_sessions WHERE id = :id")
    suspend fun getById(id: String): CheckoutSessionEntity?
    @Query("SELECT * FROM checkout_sessions WHERE status IN (:statuses) ORDER BY created_at DESC")
    suspend fun getByStatuses(statuses: List<String>): List<CheckoutSessionEntity>
    @Query("SELECT * FROM checkout_sessions WHERE checkout_request_key = :key LIMIT 1")
    suspend fun findByRequestKey(key: String): CheckoutSessionEntity?
    @Query("UPDATE checkout_sessions SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}

@Dao
interface AiProviderConfigDao {
    @Insert suspend fun insert(config: AiProviderConfigEntity): String
    @Update suspend fun update(config: AiProviderConfigEntity)
    @Delete suspend fun delete(config: AiProviderConfigEntity)
    @Query("SELECT * FROM ai_provider_configs ORDER BY is_default DESC")
    suspend fun getAll(): List<AiProviderConfigEntity>
    @Query("SELECT * FROM ai_provider_configs WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): AiProviderConfigEntity?
}

@Dao
interface AiCostRecordDao {
    @Insert suspend fun insert(record: AiCostRecordEntity): String
    @Query("SELECT * FROM ai_cost_records WHERE month = :month ORDER BY created_at DESC")
    suspend fun getByMonth(month: String): List<AiCostRecordEntity>
    @Query("SELECT COALESCE(SUM(cost_minor), 0) FROM ai_cost_records WHERE month = :month")
    suspend fun getMonthlyCost(month: String): Long
    @Query("SELECT COUNT(*) FROM ai_cost_records WHERE month = :month AND success = 1")
    suspend fun getMonthlySuccessCount(month: String): Int
    @Query("DELETE FROM ai_cost_records WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface AuditEntryDao {
    @Insert suspend fun insert(entry: AuditEntryEntity): String
    @Query("SELECT * FROM audit_entries WHERE entity_type = :type AND entity_id = :id ORDER BY created_at DESC")
    suspend fun getByEntity(type: String, id: String): List<AuditEntryEntity>
    @Query("DELETE FROM audit_entries WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)  // 90天清理
}

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: AppSettingEntity)
    @Query("SELECT value FROM app_settings WHERE key = :key")
    suspend fun get(key: String): String?
    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<AppSettingEntity>
}
```

---

## 4. 事务设计

### 4.1 账单入账事务(余额联动)

```kotlin
/**
 * 账单入账事务
 * 在同一事务中:写入账单 + 更新账户余额
 */
@Transaction
suspend fun createBillWithBalanceUpdate(
    bill: BillEntity,
    accountDao: AccountDao,
    auditDao: AuditEntryDao
) {
    // 1. 写入账单
    billDao.insert(bill)

    // 2. 更新账户余额
    if (bill.accountId != null) {
        val account = accountDao.getById(bill.accountId) ?: return
        val newBalance = calculateNewBalance(
            currentBalance = account.currentBalanceMinor,
            amount = bill.amountMinor,
            type = bill.type,
            accountKind = account.kind
        )
        accountDao.updateBalance(bill.accountId, newBalance, System.currentTimeMillis())
    }

    // 3. 转账:同时更新目标账户
    if (bill.targetAccountId != null) {
        val targetAccount = accountDao.getById(bill.targetAccountId) ?: return
        val newTargetBalance = targetAccount.currentBalanceMinor + bill.amountMinor
        accountDao.updateBalance(bill.targetAccountId, newTargetBalance, System.currentTimeMillis())
    }

    // 4. 写入审计日志
    auditDao.insert(AuditEntryEntity(
        id = UUID.randomUUID().toString(),
        entityType = "bill",
        entityId = bill.id,
        action = "create",
        fieldName = null,
        oldValue = null,
        newValue = bill.amountMinor.toString(),
        operator = "system",
        createdAt = System.currentTimeMillis()
    ))
}

/**
 * 余额计算规则(对应设计文档 5.3 节)
 */
fun calculateNewBalance(
    currentBalance: Long,
    amount: Long,
    type: TransactionType,
    accountKind: AccountKind
): Long {
    return when (type) {
        TransactionType.EXPENSE -> {
            // 资产账户减少,负债账户增加欠款(余额更负)
            if (accountKind == AccountKind.ASSET) currentBalance - amount
            else currentBalance - amount  // 负债账户余额为负,支出使其更负
        }
        TransactionType.INCOME -> {
            // 资产账户增加,负债账户减少欠款
            if (accountKind == AccountKind.ASSET) currentBalance + amount
            else currentBalance + amount
        }
        TransactionType.REFUND -> currentBalance + amount  // 退款加回
        TransactionType.FEE -> currentBalance - amount      // 手续费扣减
        TransactionType.TRANSFER,
        TransactionType.REPAYMENT,
        TransactionType.ADJUSTMENT -> currentBalance  // 转账/还款在目标账户单独处理,不计收支的不改
    }
}
```

### 4.2 预算检查事务

```kotlin
/**
 * 入账后检查预算并触发提醒
 */
@Transaction
suspend fun checkBudgetAfterBill(
    bill: BillEntity,
    budgetDao: BudgetDao,
    billDao: BillDao
): List<BudgetAlert> {
    if (bill.type != TransactionType.EXPENSE) return emptyList()

    val budgets = budgetDao.getActive()
    val alerts = mutableListOf<BudgetAlert>()

    for (budget in budgets) {
        // 计算当前周期已花
        val (periodStart, periodEnd) = getBudgetPeriod(budget.period)
        val spent = calculateBudgetSpent(budget, periodStart, periodEnd, billDao)

        val progress = spent.toDouble() / budget.amountMinor
        val needsAlert80 = progress >= 0.8 && progress < 1.0
        val needsAlert100 = progress >= 1.0

        if (needsAlert80 || needsAlert100) {
            alerts.add(BudgetAlert(
                budgetId = budget.id,
                budgetName = budget.name,
                progress = progress,
                isOverBudget = needsAlert100
            ))
        }
    }

    return alerts
}
```

---

## 5. Migration 策略

### 5.1 迁移前自动备份

```kotlin
/**
 * 数据库迁移管理器
 * 迁移前自动备份,失败回退
 */
class MigrationManager(private val context: Context) {

    /**
     * 执行迁移
     * 1. 备份当前数据库
     * 2. 执行迁移
     * 3. 失败时回退到备份
     */
    suspend fun migrate(db: JotBillDatabase, from: Int, to: Int): Boolean {
        // 备份
        val backupPath = backupDatabase(db)

        return try {
            // Room 自动执行已注册的 Migration
            // 迁移成功
            true
        } catch (e: Exception) {
            // 迁移失败,回退
            restoreDatabase(backupPath)
            false
        }
    }
}
```

### 5.2 未来版本迁移示例

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 示例:新增字段
        // database.execSQL("ALTER TABLE bills ADD COLUMN new_field TEXT")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
```

### 5.3 审计日志清理

```kotlin
/**
 * 定期清理审计日志(90天)
 * 通过 WorkManager 每天执行一次
 */
class AuditCleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        database.auditDao().deleteBefore(cutoff)
        return Result.success()
    }
}
```

---

## 6. 索引设计说明

| 表 | 索引 | 用途 |
|---|---|---|
| bills | occurred_at | 按日期查询/统计 |
| bills | occurred_at + type | 收支统计 |
| bills | order_id | 去重检查 |
| bills | original_transaction_id | 退款关联查找 |
| bills | platform | 平台统计 |
| bills | source_type | 按来源筛选 |
| accounts | sort_order | 账户排序 |
| checkout_sessions | checkout_request_key (unique) | 请求幂等 |
| checkout_sessions | expires_at | 过期清理 |
| ai_cost_records | month | 月度预算查询 |
| audit_entries | created_at | 过期清理 |
| pending_transactions | created_at | 按时间排序 |

---

## 7. 首次初始化数据

```kotlin
/**
 * 首次启动初始化
 * 创建默认分类和账户
 */
suspend fun initializeDefaultData(database: JotBillDatabase) {
    // 默认支出分类
    val defaultExpenseCategories = listOf(
        "餐饮" to "🍜", "交通" to "🚌", "购物" to "🛍️",
        "娱乐" to "🎮", "居住" to "🏠", "医疗" to "💊",
        "教育" to "📚", "通讯" to "📱", "其他" to "📦"
    )

    // 默认收入分类
    val defaultIncomeCategories = listOf(
        "工资" to "💰", "兼职" to "💼", "理财" to "📈",
        "红包" to "🧧", "退款" to "↩️", "其他" to "📦"
    )

    // 默认账户
    val defaultAccounts = listOf(
        Triple("微信零钱", AccountSubtype.E_WALLET, AccountKind.ASSET),
        Triple("支付宝", AccountSubtype.E_WALLET, AccountKind.ASSET),
        Triple("现金", AccountSubtype.CASH, AccountKind.ASSET)
    )

    // 写入数据库(带 sortOrder)
}
```
