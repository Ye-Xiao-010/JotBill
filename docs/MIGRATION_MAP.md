> **状态头**：正典·参考 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# 改造映射表 (MIGRATION_MAP.md)
> **状态**：[参考] —— 稳定
> 入口：`00-现状正本.md`

> 基于 LiteNote (https://github.com/GystuG/LiteNote) 实际代码结构,定义每个文件的迁移策略。
>
> 调研日期:2026-07-31
>
> 关联文档:NEW_PROJECT_KICKOFF_V2.md 第15节、DECISIONS.md

---

## 迁移标记说明

| 标记 | 含义 |
|---|---|
| [保留] | 直接复用,无需修改 |
| [修改数据源] | 保留 UI,把 HTTP API 调用改为 Native Module 调用 |
| [修改逻辑] | 保留文件,但业务逻辑需调整 |
| [扩展] | 保留原有功能,新增字段或功能 |
| [删除] | 不迁移,从项目中移除 |
| [新增] | LiteNote 没有,JotBill 需要新建 |

---

## 一、屏幕文件迁移清单

LiteNote 实际有 **24 个 .tsx 文件**(含 reports 下 8 个 Tab/视图),路径前缀 `jotbill-app/src/screens/`。

### 1.1 顶层页面(4 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `BudgetScreen.tsx` | [修改数据源] | 预算管理页,把 budgetsService HTTP 调用改为 Data.getBudgets/addBudget 等;新增预算超支提醒逻辑(80%/100%) |
| `FinancialGoalScreen.tsx` | [修改数据源] | 财务目标页,把 financialGoalsService HTTP 调用改为 Data.getFinancialGoals 等 |
| `GeneralSettingsScreen.tsx` | [修改逻辑] | 通用设置页,移除 ASR/支付通知等依赖后端的配置项,新增悬浮按钮模式、AI 月度预算配置 |
| `SettingsScreen.tsx` | [修改逻辑] | 设置主入口,移除"账号"相关入口,新增"AI 服务商""运行状态""数据管理""账户管理""净资产"入口 |

### 1.2 AI 子目录(2 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `ai/AIChatScreen.tsx` | [修改逻辑] | AI 对话屏,把 aiService.sendMessage/streamMessage 改为原生直连 AI API;Tool Calling 范围限制为只查询(MVP);移除依赖后端的 SSE 转发,改为原生层直连服务商 SSE |
| `ai/AIChatSessionsScreen.tsx` | [修改数据源] | AI 会话历史列表,改为本地 Room 存储查询;移除 userId 过滤(单账本) |

### 1.3 Auth 子目录(4 个) — 全部删除

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `auth/LoginScreen.tsx` | [删除] | 无账号体系,不迁移 |
| `auth/EditProfileScreen.tsx` | [删除] | 无账号体系 |
| `auth/PrivacyPolicyScreen.tsx` | [保留] | 保留页面,更新隐私政策文本(新增 AI 截图上传说明) |
| `auth/UserAgreementScreen.tsx` | [保留] | 保留页面,更新用户协议文本 |

> 注:PrivacyPolicyScreen 和 UserAgreementScreen 虽在 auth/ 目录,但内容独立于账号体系,保留并迁移到顶层 settings/ 下。

### 1.4 Dashboard 子目录(1 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `dashboard/DashboardScreen.tsx` | [修改数据源] | 主页/仪表盘,把 billsService.getTodayBills/getBillStatistics 改为 Data.getBills/getBillStatistics;移除 userId;新增"待确认"角标提示 |

### 1.5 Finance 子目录(5 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `finance/AllBillsScreen.tsx` | [修改数据源] | 全部账单列表,把 billsService.getBills 改为 Data.getBills;Bill 类型扩展七种交易类型;新增筛选:平台/商户/来源 |
| `finance/BillDetailScreen.tsx` | [扩展] | 账单详情页,新增展示字段:platform/merchant/productDescription/sourceType/originalTransactionId(退款关联);新增"关联原交易"操作 |
| `finance/CreateBillScreen.tsx` | [扩展] | 新建账单页,新增字段选择:交易类型(七种)/平台/商户/商品说明/资金账户/转账目标账户;金额输入改为分(Long) |
| `finance/CategoryManageScreen.tsx` | [修改数据源] | 分类管理页,把 categoriesService 改为 Data.getCategories 等;移除 userId |
| `finance/StatisticsScreen.tsx` | [修改数据源] | 统计页,把 billsService.getBillStatistics 改为 Data.getBillStatistics;新增平台维度统计 |

### 1.6 Reports 子目录(8 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `reports/ReportsScreen.tsx` | [修改数据源] | 报表 Tab 容器,调整数据获取来源 |
| `reports/AssetsTab.tsx` | [扩展] | 资产 Tab,重写为净资产视图:总资产/总负债/净资产/各账户余额/净资产趋势;修复 LiteNote 中后端按账户余额算、前端按收支差算的不一致问题 |
| `reports/IncomeExpenseTab.tsx` | [修改数据源] | 收支 Tab,改为本地统计 |
| `reports/DailyView.tsx` | [修改数据源] | 每日视图,改为本地查询 |
| `reports/MonthlyView.tsx` | [修改数据源] | 每月视图,改为本地查询;跨午夜按 occurredAt 归类 |
| `reports/YearlyView.tsx` | [修改数据源] | 每年视图,改为本地查询 |
| `reports/CategoryAnalysisScreen.tsx` | [修改数据源] | 分类分析页,改为本地统计 |
| `reports/TrendAnalysisScreen.tsx` | [修改数据源] | 趋势分析页,改为本地统计 |

### 1.7 新增屏幕(JotBill 独有,8 个)

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `review/ReviewScreen.tsx` | [新增] | 待确认中心,展示所有 PendingTransaction,支持确认/忽略/合并/拆分 |
| `review/ReviewDetailScreen.tsx` | [新增] | 待确认详情页,支持修改字段后确认入账;提供"不计收支"选项(押金场景) |
| `settings/AiProviderScreen.tsx` | [新增] | AI 服务商配置页:地址/模型/API Key/连通性测试/supportsVision 校验 |
| `settings/RuntimeStatusScreen.tsx` | [新增] | 运行状态页:无障碍/通知/悬浮窗/电池优化/AI 预算状态;提供跳转系统设置入口 |
| `settings/DataManagementScreen.tsx` | [新增] | 数据管理页:备份/恢复/导出/清除;含迁移失败回滚说明 |
| `settings/AccountsScreen.tsx` | [新增] | 账户管理页:账户列表/余额展示/新增/编辑/停用/余额校准 |
| `settings/NetWorthScreen.tsx` | [新增] | 净资产视图页:总资产/总负债/净资产/趋势图(从 AssetsTab 扩展独立) |
| `settings/RulesScreen.tsx` | [新增] | 规则管理页:规则列表/新增/编辑/删除/应用到历史 |

---

## 二、Services 迁移清单

LiteNote 路径前缀 `jotbill-app/src/services/`。

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `http.ts` | [删除] | axios HTTP 封装,不再需要(无后端) |
| `api.ts` | [删除] | API 入口,不再需要 |
| `index.ts` | [修改逻辑] | 重写导出,改为导出 Native Module 包装器 |
| `api/bills.ts` | [删除] | billsService HTTP 实现,改为 Data.getBills 等 Native 调用 |
| `api/accounts.ts` | [删除] | accountsService HTTP 实现,改为 Data.getAccounts 等 |
| `api/budgets.ts` | [删除] | budgetsService HTTP 实现,改为 Data.getBudgets 等 |
| `api/categories.ts` | [删除] | categoriesService HTTP 实现,改为 Data.getCategories 等 |
| `api/ai.ts` | [修改逻辑] | aiService 重写:配置管理改为 Data.getAiProviders 等;SSE 流式改为原生层直连;账单解析改为原生 AiVisionClient |
| `api/auth.ts` | [删除] | 认证服务,无账号体系 |
| `api/appVersion.ts` | [删除] | 应用版本服务,改为自发布 APK 检查更新 |
| `api/financial-goals.ts` | [删除] | 改为 Data.getFinancialGoals 等 |
| `api/hotUpdate.ts` | [删除] | 热更新 API,不使用热更新 |
| `paymentNotification/` | [修改逻辑] | 支付通知权限管理,保留权限引导,移除依赖后端的逻辑 |
| `audio/` | [保留] | 录音服务,语音记账用,保留 |
| `hotUpdate/` | [删除] | 热更新实现,不使用 |

---

## 三、Types 迁移清单

LiteNote 路径前缀 `jotbill-app/src/types/`。

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `bill.ts` | [扩展] | Bill 扩展为七种交易类型,新增 platform/merchant/productDescription/sourceType 等字段;金额改为 amountMinor(Long);详见 Native.d.ts |
| `account.ts` | [扩展] | Account 拆分 initialBalance/currentBalance;新增 subtype/kind/isActive;详见 Native.d.ts |
| `category.ts` | [保留] | 基本不变,id 改为 string |
| `budget.ts` | [保留] | 基本不变,金额改为 Minor(Long) |
| `ai.ts` | [扩展] | AIProvider 改为 qwen/glm/siliconflow;新增 AiCostRecord/AiBudgetSummary;移除依赖后端的 ChatSession 等(改为本地) |
| `financial-goal.ts` | [保留] | 基本不变 |
| `api.ts` | [修改逻辑] | ApiResponse 改为 NativeResponse;移除 PaginatedResponse 的 HTTP 特有字段 |
| `models.ts` | [修改逻辑] | 数据模型调整,移除 userId |
| `navigation.ts` | [修改逻辑] | 导航路由调整:移除 auth 路由,新增 review/settings 子路由 |
| `user.ts` | [删除] | 无账号体系 |
| `index.ts` | [修改逻辑] | 重新导出 |

---

## 四、原生模块迁移清单

LiteNote 路径前缀 `jotbill-app/android/app/src/main/java/com/litenote/`,包名改为 `com.jotbill.app`。

### 4.1 已有原生模块(4 个)

| 模块 | 标记 | 迁移说明 |
|---|---|---|
| `auth/AuthTokenModule.kt` | [删除] | JWT token 读取,无账号体系 |
| `auth/AuthTokenPackage.kt` | [删除] | 同上 |
| `hotupdate/HotUpdateModule.kt` | [删除] | 热更新,不使用 |
| `hotupdate/HotUpdatePackage.kt` | [删除] | 同上 |
| `notification/PaymentNotificationModule.kt` | [修改逻辑] | 改造为识别链路入口:通知触发 PaymentSession 创建,而非直接调后端 API |
| `notification/PaymentNotificationService.kt` | [修改逻辑] | 核心通知监听服务保留,移除 BillApiService 调用,改为写入 Room + 通知 RecognitionEngine |
| `notification/PaymentOverlayManager.kt` | [扩展] | 从全屏悬浮窗改为小型手动截图按钮;新增按钮状态管理(截图后变灰/过期恢复/敏感页隐藏) |
| `notification/BillApiService.kt` | [删除] | 调后端 API 创建账单,改为本地 Room 写入 |
| `notification/BootCompletedReceiver.kt` | [保留] | 开机自启通知监听服务 |
| `notification/PaymentNotificationHeadlessService.kt` | [删除] | Headless JS 任务,改为纯 Kotlin 处理 |
| `notification/CategoryData.kt` | [删除] | 分类数据类,使用 Room Entity 替代 |
| `utils/InstallApkModule.kt` | [保留] | APK 安装,用于自发布升级 |
| `utils/InstallApkPackage.kt` | [保留] | 同上 |
| `utils/SentryLogger.kt` | [修改逻辑] | Sentry 监控保留,但需确保不上传截图/敏感数据;或改为本地日志 |
| `MainActivity.kt` | [修改逻辑] | 包名改为 com.jotbill.app;移除热更新相关初始化 |
| `MainApplication.kt` | [修改逻辑] | 包名改;移除 AuthToken/HotUpdate Package 注册;新增 DataPackage/RecognitionPackage/AiPackage/RuntimePackage/OverlayPackage/BackupPackage |

### 4.2 新增原生模块(JotBill 独有)

| 模块 | 标记 | 说明 |
|---|---|---|
| `data/DataModule.kt` + `DataPackage.kt` | [新增] | 数据访问层,实现 DataModule 接口,调用 Room DAO |
| `data/Database.kt` | [新增] | Room 数据库,SQLCipher 加密 |
| `data/dao/BillDao.kt` | [新增] | 账单 DAO |
| `data/dao/AccountDao.kt` | [新增] | 账户 DAO |
| `data/dao/CategoryDao.kt` | [新增] | 分类 DAO |
| `data/dao/BudgetDao.kt` | [新增] | 预算 DAO |
| `data/dao/RuleDao.kt` | [新增] | 规则 DAO |
| `data/dao/PendingDao.kt` | [新增] | 待确认 DAO |
| `data/entity/*.kt` | [新增] | Room Entity 类(BillEntity/AccountEntity 等) |
| `recognition/RecognitionModule.kt` + `Package.kt` | [新增] | 识别状态管理,实现 RecognitionModule 接口 |
| `recognition/AccessibilityService.kt` | [新增] | 无障碍服务:结算页检测/截图/支付结果页识别 |
| `recognition/CheckoutPageDetector.kt` | [新增] | 结算页检测器 |
| `recognition/PaymentResultReader.kt` | [新增] | 支付结果页读取器 |
| `recognition/ScreenshotCapture.kt` | [新增] | 截图工具(takeScreenshot 封装) |
| `recognition/PageRoleClassifier.kt` | [新增] | 页面角色分类器 |
| `recognition/StabilityBuffer.kt` | [新增] | 稳定窗口缓冲 |
| `recognition/FingerprintBuilder.kt` | [新增] | 页面指纹构建 |
| `recognition/RequestCoordinator.kt` | [新增] | 请求协调器(幂等/去重) |
| `recognition/PaymentSessionEngine.kt` | [新增] | 支付会话引擎 |
| `recognition/ReconciliationEngine.kt` | [新增] | 核对引擎(AI 候选 vs 支付证据匹配) |
| `recognition/RuleEngine.kt` | [新增] | 规则引擎 |
| `ai/AiModule.kt` + `Package.kt` | [新增] | AI 服务商管理,实现 AiModule 接口 |
| `ai/AiVisionClient.kt` | [新增] | AI 视觉客户端:截图压缩/上传/JSON 校验/内存管理 |
| `ai/AiProviderAdapter.kt` | [新增] | 服务商适配器接口 |
| `ai/QwenAdapter.kt` | [新增] | 通义千问适配器(主用) |
| `ai/GlmAdapter.kt` | [新增] | 智谱 GLM 适配器(备用) |
| `ai/SiliconFlowAdapter.kt` | [新增] | 硅基流动适配器(调试) |
| `ai/PromptManager.kt` | [新增] | Prompt 模板版本管理 |
| `ai/CostController.kt` | [新增] | 成本控制:频率限制/月度预算/调用记录 |
| `runtime/RuntimeModule.kt` + `Package.kt` | [新增] | 运行健康状态,实现 RuntimeModule 接口 |
| `runtime/HealthMonitor.kt` | [新增] | 健康监控:WorkManager 周期检查/异常通知 |
| `overlay/OverlayModule.kt` + `Package.kt` | [新增] | 悬浮按钮控制,实现 OverlayModule 接口 |
| `backup/BackupModule.kt` + `Package.kt` | [新增] | 备份恢复,实现 BackupModule 接口 |
| `backup/BackupManager.kt` | [新增] | 备份管理:创建/恢复/加密/迁移 |
| `backup/ImportParser.kt` | [新增] | 导入解析:微信/支付宝账单 CSV/Excel |
| `backup/ExportManager.kt` | [新增] | 导出管理:CSV/Excel/JSON |

---

## 五、其他文件迁移清单

### 5.1 配置文件

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `package.json` | [修改逻辑] | 移除 axios/apisauce/react-native-sse(改为原生 SSE);移除热更新依赖;新增原生模块依赖 |
| `android/app/build.gradle` | [修改逻辑] | applicationId 改为 com.jotbill.app;minSdk 改为 33;新增 SQLCipher/Room 依赖 |
| `android/app/src/main/AndroidManifest.xml` | [修改逻辑] | 包名改;新增 SYSTEM_ALERT_WINDOW 权限;声明 AccessibilityService;移除 INTERNET(或保留用于 AI);新增 SQLCipher 相关 |
| `android/app/src/main/res/xml/` | [新增] | 新增 accessibility_service_config.xml |

### 5.2 后端

| 目录 | 标记 | 迁移说明 |
|---|---|---|
| `litenote-backend/` | [删除] | 整个后端不迁移,功能转移到原生层 |

### 5.3 脚本

| 文件 | 标记 | 迁移说明 |
|---|---|---|
| `scripts/release-bundle.js` | [删除] | 热更新打包脚本,不使用 |
| 其他 scripts | [评估] | 按需保留 |

---

## 六、迁移工作量评估

### 按标记统计

| 标记 | 屏幕文件 | Services | Types | 原生模块 | 合计 |
|---|---|---|---|---|---|
| [保留] | 2 | 1 | 2 | 3 | 8 |
| [修改数据源] | 10 | 0 | 0 | 0 | 10 |
| [修改逻辑] | 4 | 3 | 4 | 5 | 16 |
| [扩展] | 3 | 0 | 3 | 1 | 7 |
| [删除] | 2 | 9 | 1 | 5 | 17 |
| [新增] | 8 | 0 | 0 | 35+ | 43+ |
| **合计** | **29** | **13** | **10** | **44+** | **96+** |

### 按阶段分配

| 阶段 | 主要工作 | 涉及文件数 |
|---|---|---|
| 阶段一 | AI 能力验证 | 5(原生 AI 模块) |
| 阶段二 | 数据层本地化 | 20+(data 模块 + 10 个修改数据源屏幕) |
| 阶段三 | 识别链路 | 15+(recognition 模块 + 悬浮按钮改造) |
| 阶段四 | 支付结果页+通知 | 8(通知服务改造) |
| 阶段五 | 规则引擎+待确认 | 10+(review 屏幕 + rules) |
| 阶段六 | 资产管理+统计 | 8(账户管理 + 净资产 + 统计扩展) |
| 阶段七 | 稳定性+发布 | 5(备份 + 运行状态 + 升级) |

---

## 七、关键迁移风险

1. **MobX + React Query 双状态管理**:10 个"修改数据源"屏幕都用了 React Query 调 HTTP,改为 Native Module 后需要保留 React Query 的缓存/失效机制,只把 fetcher 从 HTTP 换成 Native 调用。

2. **AI 聊天 SSE 流式**:LiteNote 用 react-native-sse 库消费后端转发的 SSE,去后端后需改为原生层直连服务商 SSE,再通过 NativeEventEmitter 传给 RN。这是最复杂的改造点。

3. **PaymentNotificationService 改造**:LiteNote 的通知服务(22KB)直接调后端 API 创建账单,需改造为先写 Room 再通知 RecognitionEngine。注意保持通知幂等性。

4. **PaymentOverlayManager 改造**:从全屏悬浮窗改为小型手动截图按钮,交互逻辑完全不同,需重写大部分代码。

5. **AssetsTab 不一致修复**:LiteNote 后端按账户余额算净资产、前端按收支差算,两者矛盾。JotBill 需统一为流派B的余额计算规则,重写 AssetsTab 和新增 NetWorthScreen。
