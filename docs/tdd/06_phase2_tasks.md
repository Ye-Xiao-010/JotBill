# 06. 阶段二任务拆分

> 技术实现文档(TDD)第六部分
>
> 关联文档:NEW_PROJECT_KICKOFF_V2.md 第19节阶段二、02_room_schema.md、03_native_module.md、MIGRATION_MAP.md
>
> 作用:将"数据层本地化"拆为可执行的具体任务,涵盖 LiteNote 源码 fork、Room 实现、Native Module 落地、各页面数据源迁移、账号体系移除和 AI 直连改造

---

## 1. 阶段二目标

**验证目标**:LiteNote 前端在 **无后端** 环境下,所有账单/分类/账户/预算/统计/财务目标页面由本地 Room 数据驱动,可正常展示、创建、编辑、删除。

**实现范围**:
- 实现完整的 Room 数据层(Entity/DAO/Repository/Migration)
- 实现 Native Module 桥接层,前端通过 Native Module 读写本地数据
- 按优先级迁移 10 个"修改数据源"屏幕
- 移除账号认证、NestJS、JWT、热更新等后端依赖
- AI 对话/语音/拍照改为手机直连外部 AI API
- **不包含**:无障碍服务、通知监听、AI 视觉识别、支付会话引擎(阶段三及以后)
- **不包含**:规则引擎、退款/转账/还款(阶段五)、账单导入(阶段六)

**前置条件**:
- 阶段一已通过验收(AI 适配器代码可复用)
- LiteNote 源码已取得,MIT 许可证确认
- 小米 17U 真机可用

---

## 2. 任务总览

| Step | 任务 | 涉及文件数 | 依赖 |
|---|---|---|---|
| 1 | LiteNote 源码 fork 与项目初始化 | 10+ 配置 | 无 |
| 2 | Room 数据层实现 | 15+ Entity/DAO | Step 1 |
| 3 | Native Module 桥接层实现 | 10+ Module | Step 2 |
| 4 | 账号体系移除 | 8 文件删除/改写 | Step 1 |
| 5 | React Query 数据源迁移(核心 5 屏) | 5 屏 | Step 3 |
| 6 | React Query 数据源迁移(报表 5 屏) | 5 屏 | Step 5 |
| 7 | 设置页与新增屏幕接入 | 4 屏 | Step 3 |
| 8 | AI 对话/语音/拍照直连改造 | 4 文件 | Step 3 |
| 9 | 默认数据初始化与种子数据 | 1 脚本 | Step 2 |
| 10 | 真机集成测试与验收 | — | 全部 |

---

## 3. 任务详情

### 任务 1:LiteNote 源码 fork 与项目初始化

**目标**:得到一个可编译运行的 JotBill RN 工程骨架,包名、依赖、配置全部更新。

**文件清单:**

| 文件 | 操作 | 说明 |
|---|---|---|
| `litenote-mobile-app/` → `jotbill-app/` | 重命名目录 | 整个移动端工程 |
| `package.json` | 修改 | 移除 axios/apisauce/react-native-sse;新增 @react-native-async-storage/async-storage(保留);调整 name 为 jotbill-app |
| `android/app/build.gradle` | 修改 | applicationId 改 `com.jotbill.app`;minSdk 改 33;新增 Room/SQLCipher/Hilt 依赖 |
| `android/app/src/main/AndroidManifest.xml` | 修改 | 包名改;移除 INTERNET 之外的后端相关权限;声明 SQLCipher Provider(若需要) |
| `android/app/src/main/java/com/jotbill/app/` | 重命名 | 从 `com/litenote/` 整体迁移 |
| `android/app/google-services.json` | 删除 | 不使用 Firebase |
| `src/services/http.ts` | 删除 | axios 封装 |
| `src/services/api.ts` | 删除 | API 入口 |
| `litenote-backend/` | 删除 | 整个 NestJS 后端不迁移 |
| `scripts/release-bundle.js` | 删除 | 热更新打包 |
| `.env`、`.env.production` | 删除/重写 | 后端 API 地址不再需要 |

**关键代码:`build.gradle` 依赖**

```gradle
dependencies {
    // Room
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    ksp "androidx.room:room-compiler:2.6.1"

    // SQLCipher
    implementation "net.zetetic:android-database-sqlcipher:4.5.4"
    implementation "androidx.sqlite:sqlite-ktx:2.4.0"

    // Hilt
    implementation "com.google.dagger:hilt-android:2.51.1"
    ksp "com.google.dagger:hilt-compiler:2.51.1"

    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"

    // DataStore
    implementation "androidx.datastore:datastore-preferences:1.1.1"

    // WorkManager(阶段三用,提前引入)
    implementation "androidx.work:work-runtime-ktx:2.9.0"

    // OkHttp(AI 直连用)
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
}
```

**验收:**
- `cd jotbill-app && npm install` 成功
- `cd android && ./gradlew assembleDebug` 成功生成 APK
- 在小米 17U 上能启动,显示 LiteNote 原始 UI(此时数据请求会失败,符合预期)

---

### 任务 2:Room 数据层实现

**目标**:按 [02_room_schema.md](02_room_schema.md) 实现 15 个 Entity + DAO + Database + Migration 框架。

**文件清单:**

| 文件 | 说明 |
|---|---|
| `data/Database.kt` | Room Database 主类,SQLCipher 加密配置 |
| `data/DatabaseMigration.kt` | Migration 列表(初始 version=1,预留扩展) |
| `data/entity/BillEntity.kt` | 账单实体 |
| `data/entity/AccountEntity.kt` | 账户实体(含 initialBalance/currentBalance) |
| `data/entity/CategoryEntity.kt` | 分类实体 |
| `data/entity/BudgetEntity.kt` | 预算实体 |
| `data/entity/FinancialGoalEntity.kt` | 财务目标实体 |
| `data/entity/RuleEntity.kt` | 规则实体(阶段五填充逻辑) |
| `data/entity/PendingTransactionEntity.kt` | 待确认交易(阶段四/五用,先建表) |
| `data/entity/CheckoutSessionEntity.kt` | 支付会话(阶段三用,先建表) |
| `data/entity/TransactionCandidateEntity.kt` | 交易候选(阶段三用,先建表) |
| `data/entity/PaymentEvidenceEntity.kt` | 支付证据(阶段四用,先建表) |
| `data/entity/AiProviderConfigEntity.kt` | AI 服务商配置 |
| `data/entity/AiCostRecordEntity.kt` | AI 成本记录 |
| `data/entity/BalanceCalibrationEntity.kt` | 余额校准记录 |
| `data/entity/AuditEntryEntity.kt` | 审计日志 |
| `data/entity/AppSettingEntity.kt` | 应用设置(KV) |
| `data/dao/*.kt` | 每个 Entity 对应一个 DAO(15 个) |
| `data/dao/typeconverters/Converters.kt` | Room TypeConverters(枚举/JSON 字段) |
| `security/KeyStoreSecretStore.kt` | Keystore 密钥派生(SQLCipher 主密钥) |

**实现要点:**

1. **SQLCipher 主密钥**:首次启动时通过 `KeyStoreSecretStore` 在 Keystore 生成 AES-256 密钥,派生为 SQLCipher passphrase,存于 EncryptedSharedPreferences
2. **Transaction 类型映射**:用 `TransactionType` 枚举(EXPENSE/INCOME/TRANSFER/REFUND/REPAYMENT/FEE/ADJUSTMENT),DAO 用 TypeConverter
3. **金额单位**:全部 `Long`(分),前端展示时除以 100
4. **索引**:按 02_room_schema.md 定义,`occurred_at`、`order_id`、`account_id`、`category_id` 必须建索引
5. **外键**:BillEntity → AccountEntity/CategoryEntity 外键约束 + `onDelete = RESTRICT`(防止误删有账单的分类)
6. **Migration**:初始 version=1,预留 Migration(2,3) 等空位,生产期禁用 `fallbackToDestructiveMigration`

**关键代码:Entity 示例**

```kotlin
/**
 * 账单实体
 * 对应 ledger_transactions,支持七种交易类型
 */
@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["order_id"]),
        Index(value = ["account_id"]),
        Index(value = ["category_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class BillEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: String,  // TransactionType.name
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val note: String?,
    @ColumnInfo(name = "platform") val platform: String?,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "product_description") val productDescription: String?,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "source_type") val sourceType: String,  // SourceType
    @ColumnInfo(name = "locked_fields") val lockedFields: List<String>,  // JSON
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "original_transaction_id") val originalTransactionId: String?
)
```

**验收:**
- 单元测试:每个 DAO 的 CRUD 操作通过(用 in-memory Room)
- 索引/外键约束生效(尝试插入非法外键应抛异常)
- SQLCipher 加密生效(用 `sqlite3` 直接打开 db 文件应失败)

---

### 任务 3:Native Module 桥接层实现

**目标**:按 [03_native_module.md](03_native_module.md) 实现 5 个 Native Module,前端可通过 `Native.Data.getBills()` 等调用读写本地数据。

**文件清单:**

| 文件 | 说明 |
|---|---|
| `bridge/DataModule.kt` | 数据访问 Native Module(getBills/addBill/updateBill/deleteBill/getCategories 等) |
| `bridge/DataPackage.kt` | ReactPackage 注册 |
| `bridge/AiModule.kt` | AI 服务商管理(getAiProviders/addAiProvider/testAiProvider 等) |
| `bridge/AiPackage.kt` | ReactPackage |
| `bridge/RuntimeModule.kt` | 运行状态(getRuntimeHealth,阶段二只返回基础信息) |
| `bridge/RuntimePackage.kt` | ReactPackage |
| `bridge/BackupModule.kt` | 备份恢复(阶段二实现导出/导入 CSV,加密备份阶段七) |
| `bridge/BackupPackage.kt` | ReactPackage |
| `bridge/NativeEventEmitter.kt` | 统一事件发射器(onBillCreated/onBillUpdated/onPendingChanged) |
| `bridge/BridgeDtoMapper.kt` | Entity ↔ DTO 转换(避免直接暴露 Entity) |
| `bridge/Dto.kt` | 统一 DTO 数据类(BillDTO/AccountDTO/CategoryDTO 等) |
| `MainApplication.kt` | 修改:注册新增的 Package |

**实现要点:**

1. **线程模型**:所有 `@ReactMethod` 用 `scope.launch(Dispatchers.IO)`,通过 `Promise.resolve` 回主线程返回
2. **错误处理**:统一 `NativeError` DTO(code/message/retryable),前端通过 `try/catch` 或 Promise reject 捕获
3. **分页**:长列表统一用 `PaginatedDTO<T>(items/total/page/limit/hasMore)`,前端按 page+limit 查询
4. **事件发射**:写操作完成后发射事件,前端通过 `EventEmitter.addListener('onBillCreated', ...)` 订阅
5. **DTO 隔离**:不直接返回 Entity,通过 `BridgeDtoMapper.toDTO()` 转换,字段名 camelCase

**关键代码:DataModule 示例**

```kotlin
/**
 * 数据访问 Native Module
 * 提供账单/分类/账户/预算/财务目标的 CRUD 接口给 RN
 */
class DataModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventEmitter = NativeEventEmitter(reactContext)

    override fun getName() = "Data"

    /**
     * 查询账单列表
 * @param filterJson 筛选条件 JSON(type/page/limit/dateFrom/dateTo/categoryId/accountId/platform/merchant)
     * @param promise 返回 PaginatedDTO JSON
     */
    @ReactMethod
    fun getBills(filterJson: String, promise: Promise) {
        scope.launch {
            try {
                val filter = JSONObject(filterJson)
                val bills = billDao.queryByFilter(filter)
                val total = billDao.countByFilter(filter)
                val dtoList = bills.map { BridgeDtoMapper.toBillDTO(it) }
                val response = PaginatedDTO(items = dtoList, total = total)
                promise.resolve(response.toJson().toString())
            } catch (e: Exception) {
                promise.reject("DATA_QUERY_ERROR", e.message, e)
            }
        }
    }

    /**
     * 创建账单
     * @param billJson BillInput JSON
     * @param promise 返回创建后的 BillDTO
     */
    @ReactMethod
    fun addBill(billJson: String, promise: Promise) {
        scope.launch {
            try {
                val input = JSONObject(billJson)
                val entity = BridgeDtoMapper.toBillEntity(input)
                billDao.insert(entity)
                // 同步更新账户余额
                if (entity.accountId != null) {
                    accountDao.updateBalanceOnBill(
                        entityId = entity.id,
                        accountId = entity.accountId,
                        amountMinor = entity.amountMinor,
                        type = TransactionType.valueOf(entity.type)
                    )
                }
                eventEmitter.emitBillCreated(entity.id)
                promise.resolve(BridgeDtoMapper.toBillDTO(entity).toJson().toString())
            } catch (e: Exception) {
                promise.reject("DATA_INSERT_ERROR", e.message, e)
            }
        }
    }

    // 其余方法:updateBill/deleteBill/getBillById/getAccounts/addAccount/...
}
```

**验收:**
 - 在 RN 中 `Native.Data.getBills(JSON.stringify({page:1, limit:20}))` 能返回 JSON 字符串
- 写操作完成后,前端 `EventEmitter.addListener('onBillCreated')` 能收到事件
- 错误时 Promise reject 包含 code 和 message

---

### 任务 4:账号体系移除

**目标**:删除登录/注册/JWT 相关代码,改为应用锁(可选,阶段二先不做,只移除)。

**文件清单:**

| 文件 | 操作 | 说明 |
|---|---|---|
| `src/screens/auth/LoginScreen.tsx` | 删除 | 登录页 |
| `src/screens/auth/EditProfileScreen.tsx` | 删除 | 资料编辑 |
| `src/screens/auth/PrivacyPolicyScreen.tsx` | 保留 | 移到 `src/screens/settings/` |
| `src/screens/auth/UserAgreementScreen.tsx` | 保留 | 移到 `src/screens/settings/` |
| `src/providers/AuthProvider.tsx` | 删除 | 认证上下文 |
| `src/utils/nativeAuth.ts` | 删除 | JWT token 读取 |
| `src/services/api/auth.ts` | 删除 | 认证 API |
| `android/.../auth/AuthTokenModule.kt` | 删除 | JWT 原生模块 |
| `android/.../auth/AuthTokenPackage.kt` | 删除 | 同上 |
| `src/navigation/AppNavigator.tsx` | 修改 | 移除 auth 路由,首次启动直接进入主页 |

**实现要点:**

1. **路由调整**:`AppNavigator` 不再判断 `isAuthenticated`,直接显示 `MainTabs`
2. **Provider 调整**:`App.tsx` 移除 `<AuthProvider>` 包裹,改为 `<QueryClientProvider>` + `<DataProvider>`
3. **用户 ID 处理**:LiteNote 后端查询带 `userId`,改为本地单账本后,所有 DAO 查询移除 `userId` 过滤
4. **AsyncStorage 清理**:删除 `auth_token`、`user_profile` 等键
5. **应用锁**(可选):阶段二先不做,阶段七再加生物识别锁

**验收:**
- 应用启动后直接进入主页,无登录页
- 无 Network 请求(用 Charles/抓包工具确认)
- 路由栈中不存在 `Login` 路由

---

### 任务 5:React Query 数据源迁移 — 核心 5 屏

**目标**:将 LiteNote 5 个核心屏幕的 React Query `queryFn` 从 HTTP API 改为 Native Module 调用。

**文件清单:**

| 文件 | LiteNote 原实现 | 迁移动作 |
|---|---|---|
| `src/screens/dashboard/DashboardScreen.tsx` | `billsService.getTodayBills/getBillStatistics` | 改为 `Native.Data.getBills({dateFrom:今日0点, dateTo:现在})` 和 `Native.Data.getBillStatistics(...)` |
| `src/screens/finance/AllBillsScreen.tsx` | `billsService.getBills` | 改为 `Native.Data.getBills`,新增筛选:平台/商户/来源 |
| `src/screens/finance/BillDetailScreen.tsx` | `billsService.getBillById` | 改为 `Native.Data.getBillById`,新增展示字段:platform/merchant/sourceType |
| `src/screens/finance/CreateBillScreen.tsx` | `billsService.createBill` | 改为 `Native.Data.addBill`,新增字段选择:交易类型/平台/商户/账户 |
| `src/screens/finance/CategoryManageScreen.tsx` | `categoriesService.getCategories` | 改为 `Native.Data.getCategories` |

**实现要点:**

1. **保留 React Query 缓存语义**:只换 `queryFn`,保留 `useQuery`/`useMutation`/`queryClient.invalidateQueries`
2. **类型扩展**:更新 `src/types/bill.ts`,按 [Native.d.ts](../types/Native.d.ts) 扩展七种交易类型和新字段
3. **金额单位**:前端展示统一用 `formatAmount(amountMinor)` 函数,除以 100 并保留两位小数
4. **错误处理**:`queryFn` 抛出 `Error` 时,React Query 自动进入 `error` 状态,UI 显示错误提示
5. **空状态**:首启无数据时,各页面显示空状态引导(如"点击 + 新建第一笔账单")

**关键代码:useDashboard 改造示例**

```typescript
// 迁移前(LiteNote)
export function useDashboard() {
  const todayBills = useQuery({
    queryKey: ['bills', 'today'],
    queryFn: () => billsService.getTodayBills(),
  })
  // ...
}

// 迁移后(JotBill)
export function useDashboard() {
  const todayBills = useQuery({
    queryKey: ['bills', 'today'],
    queryFn: async () => {
      const json = await Native.Data.getBills(JSON.stringify({
        dateFrom: startOfDay(new Date()).getTime(),
        dateTo: Date.now(),
        page: 1,
 limit: 100,
      }))
      return JSON.parse(json) as PaginatedDTO<BillDTO>
    },
  })
  // ...
}
```

**验收:**
- 5 个页面在无网络环境下正常展示(用飞行模式验证)
- 新建账单后,列表立即刷新(通过 `queryClient.invalidateQueries`)
- 删除账单后,Dashboard 统计同步更新
- 长商户名不溢出(用"测试商户名测试商户名测试商户名测试商户名"验证)

---

### 任务 6:React Query 数据源迁移 — 报表 5 屏

**目标**:迁移 LiteNote 8 个报表相关屏幕,改为本地 SQL 聚合查询。

**文件清单:**

| 文件 | 说明 |
|---|---|
| `src/screens/reports/ReportsScreen.tsx` | Tab 容器,数据来源调整 |
| `src/screens/reports/AssetsTab.tsx` | **重写为净资产视图**(总资产/总负债/净资产/各账户余额/趋势) |
| `src/screens/reports/IncomeExpenseTab.tsx` | 收支 Tab,改为 `Native.Data.getBillStatistics` |
| `src/screens/reports/DailyView.tsx` | 每日视图,改为本地按 `occurredAt` 聚合 |
| `src/screens/reports/MonthlyView.tsx` | 每月视图,跨午夜按 `occurredAt` 归类 |
| `src/screens/reports/YearlyView.tsx` | 每年视图 |
| `src/screens/reports/CategoryAnalysisScreen.tsx` | 分类分析,本地统计 |
| `src/screens/reports/TrendAnalysisScreen.tsx` | 趋势分析,本地统计 |

**实现要点:**

1. **AssetsTab 重写**:修复 LiteNote 后端按账户余额算、前端按收支差算的不一致问题,统一为流派 B:
   - 总资产 = 所有 `kind=ASSET` 账户的 `currentBalanceMinor` 之和
   - 总负债 = 所有 `kind=LIABILITY` 账户的 `currentBalanceMinor` 之和(负数)
   - 净资产 = 总资产 + 总负债
   - 净资产趋势 = 按月快照(本阶段先实时计算,快照表阶段七实现)
2. **Native Module 新增**:`getNetWorthSnapshot()`、`getStatisticsByRange(groupBy/dateFrom/dateTo)`
3. **SQL 聚合**:在 DAO 中用 `@Query` 写聚合 SQL,避免前端循环计算
4. **跨午夜归类**:`occurredAt` 按当日 0 点归类,不按"前一天 23:59 的账单算今天"

**关键代码:DAO 聚合查询**

```kotlin
@Query("""
    SELECT
        strftime('%Y-%m', occurred_at / 1000, 'unixepoch', 'localtime') AS month,
        SUM(CASE WHEN type = 'EXPENSE' THEN amount_minor ELSE 0 END) AS expense,
        SUM(CASE WHEN type = 'INCOME' THEN amount_minor ELSE 0 END) AS income
    FROM bills
    WHERE occurred_at BETWEEN :from AND :to
    GROUP BY month
    ORDER BY month
""")
suspend fun getMonthlyStatistics(from: Long, to: Long): List<MonthlyStatRow>
```

**验收:**
- 报表页在 1000 条账单下查询 ≤ 500ms
- 资产页净资产 = Σ 账户余额,与手动计算一致
- 月度报表跨年显示正常(2025-12 → 2026-01)

---

### 任务 7:设置页与新增屏幕接入

**目标**:设置主入口改造 + 接入 4 个 JotBill 新增屏幕(部分先做骨架,详细功能后续阶段填充)。

**文件清单:**

| 文件 | 操作 | 说明 |
|---|---|---|
| `src/screens/SettingsScreen.tsx` | 修改 | 移除"账号"入口,新增"AI 服务商""运行状态""数据管理""账户管理""净资产""规则"入口 |
| `src/screens/settings/AiProviderScreen.tsx` | 新增 | AI 服务商配置页(地址/模型/API Key/连通性测试) |
| `src/screens/settings/RuntimeStatusScreen.tsx` | 新增 | 运行状态页(阶段二只显示基础信息:数据库版本/账单数/最近备份时间) |
| `src/screens/settings/DataManagementScreen.tsx` | 新增 | 数据管理页:导出 CSV / 清除数据(备份恢复阶段七完善) |
| `src/screens/settings/AccountsScreen.tsx` | 新增 | 账户管理页:列表/新增/编辑/停用/余额校准 |
| `src/screens/finance/BudgetScreen.tsx` | 修改 | 预算管理,改为本地查询,新增超支提醒(80%/100%) |
| `src/screens/finance/FinancialGoalScreen.tsx` | 修改 | 财务目标,改为本地查询 |

**实现要点:**

1. **AiProviderScreen**:复用阶段一的 Adapter 代码,API Key 用 Keystore 加密存储,读取时掩码显示
2. **AccountsScreen**:
   - 账户类型:`kind`(ASSET/LIABILITY)、`subtype`(CASH/BANK_CARD/ALIPAY/WECHAT/CREDIT_CARD/LOAN)
   - 信用卡作为 `LIABILITY`,`currentBalanceMinor` 为负数表示欠款
   - 余额校准:记一条 `BalanceCalibrationEntity`,差额生成 `ADJUSTMENT` 类型账单
3. **DataManagementScreen**:导出 CSV 先做(用 SAF),备份恢复阶段七做
4. **RuntimeStatusScreen**:阶段二显示 `数据库版本/账单总数/分类数/账户数/最近启动时间`,健康监控阶段三/七完善

**验收:**
- 添加 AI 服务商配置,API Key 保存后重启应用仍存在
- 账户管理页新建账户后,资产页净资产立即更新
- 余额校准后,账户当前余额变更,并生成一条 `ADJUSTMENT` 账单

---

### 任务 8:AI 对话/语音/拍照直连改造

**目标**:移除 LiteNote AI 服务端转发,改为 Android 原生层直连外部 AI API,通过 NativeEventEmitter 传给 RN。

**文件清单:**

| 文件 | 操作 | 说明 |
|---|---|---|
| `src/services/api/ai.ts` | 重写 | 配置管理改为 `Native.Ai.getAiProviders` 等;SSE 改为订阅原生事件 |
| `src/screens/ai/AIChatScreen.tsx` | 修改 | `aiService.sendMessage/streamMessage` 改为 `Native.Ai.sendMessage`;SSE 事件改为 `EventEmitter.addListener('onAiStreamChunk')` |
| `src/screens/ai/AIChatSessionsScreen.tsx` | 修改 | 会话列表改为 `Native.Data.getChatSessions`,本地 Room 存储 |
| `android/.../ai/AiChatModule.kt` | 新增 | AI 对话 Native Module:sendMessage(发起 SSE)、cancelMessage(中止) |
| `android/.../ai/ChatSessionEntity.kt` | 新增 | 会话/消息 Entity(若 02_room_schema 未含则补) |
| `android/.../ai/StreamingSseClient.kt` | 新增 | OkHttp SSE 客户端,逐 chunk 通过事件发射器传给 RN |

**实现要点:**

1. **SSE 流式**:`AiChatModule.sendMessage()` 启动 OkHttp SSE 请求,每收到一个 chunk 通过 `NativeEventEmitter.emit('onAiStreamChunk', {sessionId, chunk})` 传给 RN;RN 端用 `useEffect` 订阅
2. **取消机制**:`Native.Ai.cancelMessage(sessionId)` 调用 OkHttp `Call.cancel()`,前端中止按钮可用
3. **会话存储**:`ChatSessionEntity` 和 `ChatMessageEntity` 存本地 Room,不再依赖后端
4. **Tool Calling**:MVP 限制为只查询(如"查最近 5 笔账单"),不允许 AI 删除/修改账单
5. **拍照 OCR**:阶段二先保留 LiteNote 的拍照 → 上传 AI 解析账单的流程,改为直连 AI API(复用阶段一 Adapter)

**验收:**
- AI 对话能流式输出(逐字显示)
- 中止按钮能立即停止响应
- 切换 AI 服务商后,对话使用新配置
- 飞行模式下 AI 对话失败,UI 提示网络错误

---

### 任务 9:默认数据初始化与种子数据

**目标**:首次启动时初始化默认分类、示例账户和示例账单,让用户开箱即用。

**文件清单:**

| 文件 | 说明 |
|---|---|
| `data/seed/DefaultDataInitializer.kt` | 首次启动初始化器 |
| `data/seed/DefaultCategories.json` | 默认分类数据(餐饮/交通/购物/娱乐等,含图标和颜色) |
| `data/seed/DefaultAccounts.json` | 默认账户(现金/支付宝/微信/信用卡) |

**实现要点:**

1. **触发时机**:`AppSettingEntity` 中查 `initialized` 标记,首次启动未初始化时执行
2. **默认分类**:参考 LiteNote 后端 `Category` 表初始数据,扩展到 20 个常用分类
3. **默认账户**:4 个账户(现金/支付宝/微信/信用卡),`initialBalanceMinor` 全部为 0
4. **示例账单**(可选):提供 5 条示例账单,用户首次进入 Dashboard 不为空,可一键清除
5. **事务原子性**:初始化用 Room `@Transaction`,失败回滚

**关键代码:**

```kotlin
/**
 * 默认数据初始化器
 * 首次启动时填充默认分类和账户
 */
class DefaultDataInitializer(
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val settingDao: AppSettingDao
) {
    suspend fun initializeIfFirstRun() {
        val initialized = settingDao.getBoolean("initialized", false)
        if (initialized) return

        categoryDao.insertAll(loadDefaultCategories())
        accountDao.insertAll(loadDefaultAccounts())
        settingDao.setBoolean("initialized", true)
    }
}
```

**验收:**
- 首次启动后,分类管理页有 20 个默认分类
- 账户管理页有 4 个默认账户
- 再次启动不重复初始化

---

### 任务 10:真机集成测试与验收

**目标**:在小米 17U 上完成端到端验证,确认所有页面本地数据驱动,无任何后端请求。

**测试用例:**

| # | 测试场景 | 操作 | 预期结果 |
|---|---|---|---|
| 1 | 首次启动 | 安装 APK 并启动 | 显示 LiteNote UI,默认分类和账户已初始化 |
| 2 | 新建账单 | Dashboard → + → 填写 → 保存 | 列表立即出现新账单,统计更新 |
| 3 | 编辑账单 | 账单详情 → 编辑 → 修改金额 → 保存 | 金额变更,账户余额同步调整 |
| 4 | 删除账单 | 账单详情 → 删除 | 列表移除,统计回退 |
| 5 | 分类管理 | 设置 → 分类 → 新增 → 保存 | 分类列表更新 |
| 6 | 账户管理 | 设置 → 账户 → 新增 → 余额校准 | 账户余额变更,生成 ADJUSTMENT 账单 |
| 7 | 预算 | 预算页 → 设置月度预算 → 超支 | 超支时显示红色警告 |
| 8 | 报表-资产 | 报表 → 资产 Tab | 显示总资产/总负债/净资产,与账户余额一致 |
| 9 | 报表-收支 | 报表 → 收支 Tab → 切换月/年 | 图表正确展示,无网络请求 |
| 10 | AI 对话 | AI → 输入消息 → 发送 | 流式输出正常,可中止 |
| 11 | 离线模式 | 飞行模式 → 重复 1-9 | 全部正常(除 AI 对话) |
| 12 | 抓包验证 | Charles 抓包 → 启动应用 → 操作 1-9 | 无任何账本相关 HTTP 请求(只有 AI 对话请求) |
| 13 | 数据库加密 | adb root → 查看 db 文件 | db 文件无法用 sqlite3 直接打开 |
| 14 | 重启保持 | 操作后重启应用 | 所有数据保持,无丢失 |

**验收标准:**

| # | 验收项 | 通过标准 |
|---|---|---|
| 1 | 编译运行 | APK 在小米 17U 上正常启动,无崩溃 |
| 2 | 无后端依赖 | 抓包确认无账本/分类/账户/统计相关 HTTP 请求 |
| 3 | 数据持久化 | 重启后所有数据保持 |
| 4 | SQLCipher 加密 | db 文件无法直接读取 |
| 5 | 核心功能 | 14 个测试用例全部通过 |
| 6 | 性能 | 1000 条账单下报表查询 ≤ 500ms |
| 7 | UI 一致性 | 视觉与 LiteNote 一致,长字段不溢出 |
| 8 | 离线可用 | 飞行模式下所有账本功能正常 |

**验收失败的处理:**

| 失败项 | 处理方案 |
|---|---|
| 1 失败 | 检查 SQLCipher/Room 依赖配置,查看崩溃日志 |
| 2 失败(仍有后端请求) | 检查是否漏改某个 service/api 文件,用 grep 搜索 `axios/http` |
| 4 失败(数据库未加密) | 检查 `KeyStoreSecretStore` 是否正确生成密钥 |
| 6 失败(性能差) | 检查 DAO 索引是否生效,用 `EXPLAIN QUERY PLAN` 分析 |
| 8 失败(离线崩溃) | 检查是否仍有网络依赖,定位崩溃点 |

---

## 4. 执行步骤

### Step 1:项目初始化(任务 1)
- Fork LiteNote 仓库,重命名为 jotbill-app
- 改包名、依赖、Manifest
- 确认能编译运行(LiteNote 原始 UI)

### Step 2:数据层与桥接层(任务 2 + 3)
- 实现 15 个 Entity + DAO
- 实现 SQLCipher 加密
- 实现 5 个 Native Module
- 单元测试通过

### Step 3:清理后端依赖(任务 4)
- 删除账号体系、http.ts、api/*.ts
- 调整路由和 Provider
- 确认 RN 能正常启动

### Step 4:数据源迁移(任务 5 + 6)
- 先迁移 Dashboard(验证端到端流程通)
- 再迁移其余 4 个核心屏
- 最后迁移 8 个报表屏
- 每屏迁移完做真机验证

### Step 5:设置与新增屏(任务 7)
- 设置主入口改造
- AI 服务商配置页(复用阶段一 Adapter)
- 账户管理页
- 资产 Tab 重写

### Step 6:AI 直连改造(任务 8)
- AiChatModule 实现
- SSE 流式
- 会话本地存储
- 真机验证流式输出

### Step 7:默认数据(任务 9)
- DefaultDataInitializer
- 默认分类和账户
- 真机验证首次启动

### Step 8:集成测试(任务 10)
- 14 个测试用例逐项执行
- 抓包验证
- 离线验证
- 输出验收报告

---

## 5. 项目结构(阶段二结束时的状态)

```
jotbill-app/
├── android/app/src/main/java/com/jotbill/app/
│   ├── data/
│   │   ├── Database.kt
│   │   ├── DatabaseMigration.kt
│   │   ├── entity/                  (15 个 Entity)
│   │   ├── dao/                     (15 个 DAO)
│   │   ├── dao/typeconverters/
│   │   ├── repository/              (LedgerRepository 等,可选)
│   │   └── seed/
│   │       ├── DefaultDataInitializer.kt
│   │       ├── DefaultCategories.json
│   │       └── DefaultAccounts.json
│   ├── bridge/
│   │   ├── DataModule.kt + DataPackage.kt
│   │   ├── AiModule.kt + AiPackage.kt
│   │   ├── RuntimeModule.kt + RuntimePackage.kt
│   │   ├── BackupModule.kt + BackupPackage.kt
│   │   ├── AiChatModule.kt          (AI 对话 SSE)
│   │   ├── NativeEventEmitter.kt
│   │   ├── BridgeDtoMapper.kt
│   │   └── Dto.kt
│   ├── ai/
│   │   ├── AiProviderAdapter.kt     (从阶段一复用)
│   │   ├── OpenAICompatibleAdapter.kt
│   │   ├── QwenAdapter.kt
│   │   ├── GlmAdapter.kt
│   │   ├── SiliconFlowAdapter.kt
│   │   ├── PromptManager.kt
│   │   ├── AiResponseValidator.kt
│   │   ├── ImagePreprocessor.kt
│   │   ├── StreamingSseClient.kt    (SSE 流式)
│   │   └── CostController.kt
│   ├── security/
│   │   └── KeyStoreSecretStore.kt
│   └── MainApplication.kt           (注册所有 Package)
├── src/
│   ├── screens/
│   │   ├── dashboard/DashboardScreen.tsx          [修改数据源]
│   │   ├── finance/
│   │   │   ├── AllBillsScreen.tsx                 [修改数据源]
│   │   │   ├── BillDetailScreen.tsx               [扩展]
│   │   │   ├── CreateBillScreen.tsx               [扩展]
│   │   │   ├── CategoryManageScreen.tsx           [修改数据源]
│   │   │   ├── StatisticsScreen.tsx               [修改数据源]
│   │   │   ├── BudgetScreen.tsx                   [修改逻辑]
│   │   │   └── FinancialGoalScreen.tsx            [修改数据源]
│   │   ├── reports/
│   │   │   ├── ReportsScreen.tsx                  [修改数据源]
│   │   │   ├── AssetsTab.tsx                      [重写]
│   │   │   ├── IncomeExpenseTab.tsx               [修改数据源]
│   │   │   ├── DailyView.tsx                      [修改数据源]
│   │   │   ├── MonthlyView.tsx                    [修改数据源]
│   │   │   ├── YearlyView.tsx                     [修改数据源]
│   │   │   ├── CategoryAnalysisScreen.tsx         [修改数据源]
│   │   │   └── TrendAnalysisScreen.tsx            [修改数据源]
│   │   ├── ai/
│   │   │   ├── AIChatScreen.tsx                   [修改逻辑]
│   │   │   └── AIChatSessionsScreen.tsx           [修改数据源]
│   │   └── settings/
│   │       ├── SettingsScreen.tsx                 [修改逻辑]
│   │       ├── GeneralSettingsScreen.tsx          [修改逻辑]
│   │       ├── AiProviderScreen.tsx               [新增]
│   │       ├── RuntimeStatusScreen.tsx            [新增]
│   │       ├── DataManagementScreen.tsx           [新增]
│   │       ├── AccountsScreen.tsx                 [新增]
│   │       ├── PrivacyPolicyScreen.tsx            [从 auth 移入]
│   │       └── UserAgreementScreen.tsx            [从 auth 移入]
│   ├── services/
│   │   ├── index.ts                               [重写导出]
│   │   ├── api/ai.ts                              [重写为 Native 包装]
│   │   ├── audio/                                 [保留]
│   │   └── paymentNotification/                   [保留权限引导,移除后端]
│   ├── providers/
│   │   └── DataProvider.tsx                       [新增,替代 AuthProvider]
│   ├── hooks/                                     [全部修改 queryFn]
│   └── types/                                     [按 Native.d.ts 扩展]
└── (litenote-backend/ 已删除)
```

---

## 6. 风险与应对

| 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|
| MobX + React Query 双状态管理冲突 | 中 | 数据不一致 | 统一用 React Query,移除 MobX 状态(若 LiteNote 用了) |
| AI 聊天 SSE 流式改造复杂 | 高 | 对话功能不可用 | 先实现非流式版本,流式作为任务 8 子任务单独验证 |
| SQLCipher 密钥丢失导致数据库无法打开 | 低 | 数据丢失 | 密钥备份在 EncryptedSharedPreferences,Keystore 不卸载即不丢失;卸载应用等于清除数据 |
| Room Migration 设计不当 | 中 | 后续升级困难 | 初始 version=1,严格按 02_room_schema.md 预留字段,不轻易改 schema |
| LiteNote 第三方依赖许可证冲突 | 低 | 法律风险 | 任务 1 中检查所有依赖许可证,替换不兼容的 |
| AssetsTab 重写计算错误 | 中 | 净资产显示错误 | 单元测试覆盖:总资产/总负债/净资产的计算逻辑 |
| 长商户名/长商品名 UI 溢出 | 中 | 视觉问题 | 用 `numberOfLines={1} ellipsizeMode="tail"` 截断 |
 | Native Module 调用频繁导致性能问题 | 低 | 卡顿 | 批量查询代替循环单条查询,分页限制 limit ≤ 100 |
| 默认分类不符合用户习惯 | 低 | 用户体验差 | 提供"恢复默认分类"按钮,支持用户自定义 |
| 跨午夜账单归类错误 | 中 | 报表数据错位 | 统一用 `occurredAt` 的本地时区 0 点归类,单元测试覆盖 |

---

## 7. 阶段二交付物清单

阶段二结束时,应交付:

| # | 交付物 | 形式 |
|---|---|---|
| 1 | JotBill Android APK | 可在小米 17U 上安装运行 |
| 2 | 源码仓库 | Git 仓库,含完整提交历史 |
| 3 | 数据库 Schema 文档 | 已有 [02_room_schema.md](02_room_schema.md) |
| 4 | Native Module 接口文档 | 已有 [03_native_module.md](03_native_module.md) |
| 5 | 阶段二验收报告 | 14 个测试用例结果 + 性能数据 + 抓包截图 |
| 6 | 已知问题列表 | 阶段三前需修复的问题 |

**通过阶段二验收后,可进入阶段三(自动结算页检测)。**
