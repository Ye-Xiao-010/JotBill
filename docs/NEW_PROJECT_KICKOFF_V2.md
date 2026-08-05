# 新项目启动文档（基于 LiteNote 改造的 AI 自动记账应用）

> 文档状态：项目启动基线（V2，经技术评估讨论后修订）
>
> 版本：0.2.0
>
> 更新日期：2026-07-31
>
> 基础项目：LiteNote（https://github.com/GystuG/LiteNote，MIT 许可证）
>
> 参考方案：LITENOTE_AI_PAYMENT_DESIGN.md（AI 支付识别改造方案）
>
> 用途：定义新项目的目标、架构、功能、数据模型、改造清单和验收标准
>
> V2 变更摘要：AI 服务商定为智谱/通义/硅基流动三选一；去除本地 OCR；新增手动悬浮按钮截图；商户字段降级为场景相关字段；资产管理采用流派 B（流水+余额+净资产）；补充 15 项用户场景；落地 P3 工程决策（Android 13、SQLCipher、备份默认不加密、AOSP+小米首发、单账本、自发布 APK 等）

---

## 1. 文档目的与背景

### 1.1 文档目的

本文档定义新项目的完整产品基线，作为开发、设计、测试和长期维护的共同依据。文档回答以下问题：

1. 新项目与 LiteNote 的关系，保留什么、替换什么、新增什么。
2. AI 支付识别主链路如何工作，如何避免误记和重复。
3. 业务语义如何区分支付入口、资金账户、消费平台、实际商户和消费分类。
4. 退款、转账、还款、手续费等特殊交易如何处理。
5. AI 能力如何与本地优先原则共存，成本和风险如何控制。
6. LiteNote 前端如何改造为本地数据驱动。
7. MVP 达到什么标准后才可以发布。

### 1.2 背景与决策

本项目不基于 ZeroTap 继续，原因：

- ZeroTap 当前主链路未打通，核心识别能力（普通消费、平台消费、退款、转账）均不可用。
- ZeroTap 的本地优先/无网络基线与 AI 视觉识别方案根本对立，改造成本高于重做。
- 用户明确看重 LiteNote 的前端界面与交互，而 Compose 复刻无法达到 100% 一致。

本项目基于 LiteNote 改造，原因：

- LiteNote 前端 18 屏已完整实现，视觉和交互满足预期。
- LiteNote 已有多模型 AI、SSE 流式、Tool Calling、Whisper 语音、拍照 OCR 等差异化能力。
- AI 支付识别改造方案（LITENOTE_AI_PAYMENT_DESIGN.md）本身就是为 LiteNote 设计。

### 1.3 与原方案文档的关系

LITENOTE_AI_PAYMENT_DESIGN.md 是识别链路的方案提案，聚焦"AI 截图识别 + 支付确认"一条链路。本文档在此基础上补齐：

- 完整业务语义（五维度、七种交易类型、账户体系）。
- 缺失功能模块（账单导入、规则引擎、字段锁定、多级去重、备份恢复）。
- AI 特有设计（成本控制、故障转移、离线降级、延迟处理、错误反馈）。
- 工程质量（异常场景、性能指标、测试策略、验收标准）。
- LiteNote 改造清单与 Native Module 接口。

---

## 2. 项目概述

### 2.1 产品定义

新项目是一款基于 LiteNote 前端的本地优先个人自动记账应用。它保留 LiteNote 的 React Native 界面、主题、导航和交互体验，将云端后端替换为本地原生数据层，并新增 AI 视觉支付识别能力。

核心能力：

1. **AI 支付识别**：在购物软件稳定结算页预捕获内存截图，只有提交订单或支付应用调起后才调用外部多模态 AI 识别平台、商户、金额和订单号。
2. **支付成功确认**：由微信/支付宝通知或支付结果页确认支付成功，AI 结果不能独立入账。
3. **本地账本**：账单、分类、规则、账户、会话全部保存在设备本地，使用 Room 数据库。
4. **AI 辅助记账**：保留 LiteNote 的 AI 对话、语音、拍照记账能力，作为用户主动记账的增强。
5. **账单导入**：支持微信、支付宝官方账单文件导入与对账。
6. **完整业务语义**：区分支付入口、资金账户、消费平台、实际商户和消费分类，正确处理退款、转账、还款和手续费。

### 2.2 核心价值

- 减少逐平台编写无障碍适配的工作量，用 AI 视觉识别覆盖更多购物平台。
- 保留 LiteNote 成熟的前端体验和 AI 辅助能力。
- 本地账本不依赖远程服务器，数据可控。
- AI 只负责补全字段，不独立决定入账，保证准确性。

### 2.3 产品原则

1. **准确优先于自动化率**：宁可进入待确认，也不把低置信度结果静默记入正式账本。
2. **AI 识别与支付确认职责分离**：AI 负责"买了什么"，通知/结果页负责"是否支付成功"。
3. **本地优先**：核心账本、规则、统计、备份均在设备本地完成。
4. **AI 可选**：AI 视觉识别是可选增强来源，用户可关闭后仅使用通知识别和手动记账。
5. **用户可控**：所有自动操作可解释、可修改、可撤销。
6. **不重复计算**：同一交易的多条来源合并为一笔账。
7. **隐私分级**：本地不持久化截图，AI 上传前做脱敏，用户知情并选择服务商。

---

## 3. 与 LiteNote 的关系

### 3.0 LiteNote 源码审计基线

本项目不是根据 LiteNote 的 README 或演示视频重做，而是以 LiteNote `main` 分支的实际源码行为作为迁移依据。以下结论是启动阶段必须遵守的边界；没有列在“已实现”中的能力，不能在开发计划中假设为现成能力。

LiteNote 移动端的技术栈和关键入口为：

```text
jotbill-app/package.json       React Native 0.81.4 / React 19.1.0 及依赖
jotbill-app/App.tsx            RN 根组件和原生事件监听
jotbill-app/src/navigation/   Native Stack 和底部导航
jotbill-app/src/screens/      仪表盘、账单、统计、预算、目标、AI 等页面
jotbill-app/src/components/   通用控件、卡片、弹窗、图表和分类选择器
jotbill-app/src/hooks/        React Query 数据查询和页面状态
jotbill-app/src/services/     Axios、API、认证和更新服务
jotbill-app/src/providers/    AuthProvider 等上下文
```

前端当前实际数据流是：

```text
AuthProvider
  -> AsyncStorage 保存 Token/用户资料
  -> Axios 添加 Bearer Token 和 X-Request-ID
  -> NestJS REST API
  -> React Query 缓存
  -> React Native 页面
```

这意味着 LiteNote 的账单页面不是“复制后即可离线工作”的页面。每个页面的数据查询、创建、编辑、分类和统计都必须切换到本地 Repository；只复制视觉组件会留下运行时的远程请求。

LiteNote 已有的前端页面和准确迁移动作如下：

| LiteNote 页面/能力 | 实际源码位置 | 新项目处理 | 不能直接复用的部分 |
|---|---|---|---|
| 首页仪表盘 | `src/screens/dashboard/DashboardScreen.tsx`、`src/hooks/useDashboard.ts` | 保留布局、卡片和导航；查询改为 Room 聚合 | 远程统计和最近账单 queryFn |
| 全部账单 | `src/screens/finance/AllBillsScreen.tsx` | 保留列表、筛选和空状态 | `/bills` API、分页/缓存假设 |
| 账单详情 | `src/screens/finance/BillDetailScreen.tsx` | 增加来源、证据、会话和自动入账状态 | 远程详情接口和云端 ID 假设 |
| 新建/编辑账单 | `src/screens/finance/CreateBillScreen.tsx` | 保留表单和校验；写入本地数据库 | `BillApiService.createBill()` |
| 分类管理 | `src/screens/finance/CategoryManageScreen.tsx` | 保留网格、图标和排序交互；改成本地分类 | 远程 `/categories` 加载 |
| 收支统计/报表 | `src/screens/finance/StatisticsScreen.tsx`、`src/screens/reports/` | 保留图表和切换交互；改为本地 SQL 聚合 | 后端 `/bills/statistics` |
| 预算 | `src/screens/BudgetScreen.tsx` | 可复用 UI；新增本地预算和周期计算 | 后端预算聚合 |
| 财务目标 | `src/screens/FinancialGoalScreen.tsx` | 可复用 UI；新增本地目标进度计算 | 后端目标聚合 |
| AI 对话/会话 | `src/screens/ai/` | 可选保留；改为手机直连并通过 Native Module 调用本地账本 | 不得和后台支付识别共用会话状态 |
| 登录、注册、协议、个人资料 | `src/screens/auth/` | MVP 默认移除，改成本地初始化和应用锁 | JWT、用户资料 API |
| 通知设置 | `PaymentNotificationSettings.tsx` | 保留视觉；增加通知、无障碍、截图、AI 和电池状态 | 仅通知开关的旧状态模型 |
| 更新/热更新 | `UpdateModal.tsx`、`useHotUpdate.ts` | MVP 暂缓或另行设计签名升级 | LiteNote 后端版本接口和下载依赖 |

LiteNote Android 原生层的实际支付链路是通知触发悬浮窗，而不是结算页识别：

关键实现文件位于：

```text
jotbill-app/android/app/src/main/java/com/litenote/notification/PaymentNotificationService.kt
jotbill-app/android/app/src/main/java/com/litenote/notification/PaymentOverlayManager.kt
jotbill-app/android/app/src/main/java/com/litenote/notification/BillApiService.kt
jotbill-app/android/app/src/main/java/com/litenote/notification/PaymentNotificationModule.kt
jotbill-app/android/app/src/main/java/com/litenote/notification/PaymentNotificationHeadlessService.kt
jotbill-app/android/app/src/main/java/com/litenote/notification/BootCompletedReceiver.kt
```

```text
PaymentNotificationService.onNotificationPosted
  -> 检查 monitored_apps 包名
  -> 合并 title/titleBig/text/subText/summaryText/bigText/infoText/tickerText
  -> 排除“收款/到账/收到/转入/红包”
  -> 命中任意 paymentKeywords
  -> 金额正则提取
  -> 立即 PaymentOverlayManager.showPaymentOverlay()
  -> GET /categories?type=expense
  -> 用户选分类
  -> POST /bills
```

默认关键词是“支付、付款、消费、扣款、支出、交易提醒、花呗、余额宝、已支付”；金额正则覆盖 `¥50.00`、`50.00元`、`CNY 50.00`、`人民币50.00`、`金额：50.00` 等形式。这些规则只能作为支付候选解析器的初始样本，不能直接搬来决定支付成功或自动入账。

LiteNote 当前支付实现的限制必须在新项目中明确标记为“需要重写”：

- 没有购物软件结算页状态，也没有从商品详情页进入结算页的页面角色判断；
- 任何命中关键词和金额的通知都会尝试弹窗，没有支付通知事件键、订单键或通知更新去重；
- `PaymentOverlayManager.kt` 内部直接调用 `BillApiService.createBill()`，`onConfirm` 并不是统一提交入口；
- 分类来自远程 `GET /categories`，离线时确认记账可能失败；
- 默认悬浮窗自动消失约 60 秒，没有“等待 AI 补全后再决定是否弹窗”；
- 没有 AI 识别候选、支付结果关联、金额冲突处理、同额并发支付保护或正式账单幂等约束；
- Manifest 中声明了 `PaymentNotificationHeadlessService.kt`，但当前通知服务没有实际启动它，因此它不能被当作已经打通的后台处理链路；
- `App.tsx` 只监听 `onBillCreated` 并刷新 React Query 缓存，没有支付会话、AI pending、待确认交易等事件模型。

LiteNote 后端是 NestJS + Prisma + PostgreSQL，包含 `AuthModule`、`BillsModule`、`CategoriesModule`、`AccountsModule`、`BudgetsModule`、`FinancialGoalsModule`、`AIModule`、`AppVersionModule` 和 `HotUpdateModule`，模型包括 `User`、`AIModelConfig`、`Category`、`Bill`、`Account`、`Budget`、`FinancialGoal`、`ChatSession`、`HotUpdate` 和 `ChatMessage`。`AIModelConfig` 支持 provider、API Key、Base URL、model 和 `supportsVision`；`ParseBillDto` 支持 `image` 或 `text`，OpenAI 兼容适配器使用 `image_url`，Claude 适配器使用图片内容块。

这些后端代码可移植的只有请求格式、提示词、图片 Base64 转换和响应校验思路；账本接口、JWT、云端分类、云端统计和后端 AI 路由不在新项目中保留。新项目的 Android 原生层必须直接调用用户配置的外部 AI API，并把结果写入本地 Room。

源码迁移前还必须核对 LiteNote 仓库当前许可证、第三方依赖许可证、图标和字体授权。未确认授权的图片、商标、服务端密钥、签名文件和生产配置不得复制进新项目；复用的是可合法使用的代码结构和视觉实现，不等于获得 LiteNote 品牌或第三方服务授权。

### 3.1 保留 LiteNote 的部分

- React Native 0.81 前端框架与 TypeScript 代码。
- 18 个屏幕的视觉、布局、主题（Light/Dark/System）。
- React Navigation 7 导航结构。
- 账单列表、详情、统计、分类管理、预算、财务目标等 UI。
- AI 对话记账的交互界面（需改造调用层）。
- 语音记账的录音与展示交互。
- 拍照记账的相机与图片选择交互。
- 组件库（UI/业务/图表组件）。
- 主题系统与图标资源。

### 3.2 替换 LiteNote 的部分

| LiteNote 原有能力 | 替换为 |
|---|---|
| NestJS REST API 后端 | 本地 Room 数据库 + Native Module 数据访问层 |
| PostgreSQL 云端存储 | 本地 Room（SQLite）存储 |
| JWT 账号认证 | 移除账号体系，改为应用锁/生物识别 |
| 服务端 AI 调用（Claude/OpenAI/DeepSeek/Qwen） | 手机直连外部 AI API + 本地 Keystore 密钥管理 |
| SSE 流式响应 | 保留（AI 对话场景），AI 视觉识别用一次性 JSON 响应 |
| React Query 远程数据获取 | 改为 Native Module 调用 + 本地状态管理 |
| 云端分类同步 | 本地分类管理 + 个人规则引擎 |
| 远程账单创建 | 本地账单创建 + 自动识别入账 |
| 账号相关页面 | 移除，替换为运行状态/数据管理页面 |
| 动态 JS 热更新 | 移除（原生 APK 不支持等效热更新） |

### 3.3 新增的能力

- AI 视觉支付识别（结算页截图 + 多模态 AI）。
- 购物软件结算页通用检测。
- 支付会话状态机（CheckoutSession）。
- 微信/支付宝支付成功确认（通知 + 结果页）。
- 无障碍服务（只读、结算页检测、支付结果页识别）。
- 本地 OCR 兜底（AI 视觉失败或离线时）。
- 账单导入（微信 CSV/支付宝 XLSX/加密压缩包）。
- 个人规则引擎（替代自动学习）。
- 字段锁定与数据优先级（防止自动覆盖用户确认值）。
- 多级去重与关联。
- 退款、转账、还款、手续费处理。
- 备份恢复与 CSV 导出。
- 运行状态页与审计日志。
- AI 成本控制与服务商故障转移。

### 3.4 代码迁移判定表

下表用于开发任务拆分。标记为“复用”的内容仍需编译和真机回归；“重写”表示不能通过改 URL 或改字段名完成迁移。

| 代码/资源 | 判定 | 迁移动作 | 完成条件 |
|---|---|---|---|
| `src/theme/`、`src/components/` | 复用 | 保留主题 token、卡片、图表、分类网格和确认弹窗 | 新数据状态能在原布局内完整展示，长商户名不溢出 |
| `App.tsx` 的 `onBillCreated` 监听 | 重写 | 增加原生事件订阅、生命周期清理和 Query/本地缓存失效 | RN 重启后仍能收到待确认和已入账状态 |
| `src/navigation/AppNavigator.tsx` | 复用后删改 | 保留导航骨架，新增待确认、识别状态、AI 配置、数据管理入口 | 未登录时可直接进入本地账本 |
| `src/hooks/` 中 React Query hooks | 重写 queryFn | 将远程请求替换为 Native Module/本地查询，保留 loading/error/cache 语义 | 无网络时账单列表、统计、分类仍可用 |
| `src/services/http.ts`、`src/services/api/*` | 移除账本调用 | 仅保留可选 AI 对话客户端，删除 Bearer Token 和 `/bills` 请求 | 抓包不会出现账本、分类或用户资料上传 |
| `AuthProvider.tsx`、`utils/nativeAuth.ts` | 移除 | 改成本地初始化、应用锁和 Keystore 配置 | 不存在登录过期导致账本不可读 |
| `PaymentNotificationService.kt` | 部分复用后重写 | 保留字段合并和生命周期，接入 `PaymentResultNormalizer`、事件键和 Room | 通知更新/重复回调只产生一个来源事件 |
| `PaymentOverlayManager.kt` | 复用视觉，重写业务 | 从“收到通知立即弹窗”改为由决策引擎发送待确认事件 | 信息完整时无弹窗，缺失或冲突时延迟弹窗 |
| `BillApiService.kt` | 移除 | 替换 `LedgerRepository` 和 `TransactionCommitter` | 自动入账不需要网络和 JWT |
| `PaymentNotificationHeadlessService.kt` | 不作为核心复用 | 可删除或保留占位，但后台核心放在 Kotlin 服务和 Room | 服务停止 RN 后，交易状态仍可恢复 |
| LiteNote 后端 AI 适配器 | 协议移植 | 移植图片内容格式、提示词和 JSON 校验；改为 Android `AiVisionClient` | API Key 不进日志，非法响应不写账 |
| LiteNote Prisma `Bill` | 语义参考 | 映射为本地 `ledger_transactions`，新增会话、证据、锁定字段 | 交易来源和用户修改可追溯 |

以下内容不得在 MVP 以“以后再接”的方式伪装成已完成：本地数据库幂等约束、结算页请求去重、支付结果与会话匹配、待确认状态恢复、敏感截图释放和 AI 失败回退。

---

## 4. 总体架构

### 4.1 架构分层

```text
┌─────────────────────────────────────────────┐
│  React Native 前端（保留 LiteNote UI）       │
│  - 屏幕、组件、导航、主题                    │
│  - 状态管理（ViewModel + Flow/StateFlow）    │
└───────────────┬─────────────────────────────┘
                │ Native Module / Event Bridge
┌───────────────┴─────────────────────────────┐
│  原生数据访问层（Kotlin）                    │
│  - 账单/分类/规则/账户 Repository            │
│  - 支付会话引擎                              │
│  - 核对/规则/去重引擎                        │
│  - Room 数据库                               │
└───────────────┬─────────────────────────────┘
                │
┌───────────────┴─────────────────────────────┐
│  来源适配器层（Kotlin）                      │
│  - AiVisionAdapter（AI 视觉识别）            │
│  - NotificationAdapter（微信/支付宝通知）    │
│  - AccessibilityAdapter（支付结果页）        │
│  - ImportAdapter（账单文件导入）             │
│  - SmsAdapter（银行短信，可选）              │
└───────────────┬─────────────────────────────┘
                │
┌───────────────┴─────────────────────────────┐
│  系统能力层（Android）                       │
│  - AccessibilityService（结算页检测+截图）   │
│  - NotificationListenerService              │
│  - UsageStatsManager（应用轨迹）             │
│  - Android Keystore（密钥加密）              │
│  - 外部 AI API（网络）                       │
└─────────────────────────────────────────────┘
```

### 4.1.1 建议模块目录

目录按职责隔离，避免把通知解析、AI 请求和账本提交继续写在同一个 Service 或 Overlay 类中：

```text
android/app/src/main/java/<app>/
  capture/
    accessibility/       CheckoutPageDetector、PaymentResultReader、ScreenshotCapture
    notification/        PaymentNotificationListener、NotificationParser
  payment/
    session/              CheckoutSessionManager、SessionStateMachine
    evidence/             PaymentResultNormalizer、EvidenceDeduplicator
    matching/             TransactionMatcher、MatchScore
    decision/             AutoBookkeepingDecision、ReviewPolicy
  ai/
    AiVisionClient、AiProviderAdapter、AiResponseValidator、AiCostTracker
  ledger/
    db/                   Room Database、Entity、Dao、Migration
    repository/           LedgerRepository、CategoryRepository、SettingsRepository
  bridge/
    ZeroTapNativeModule、NativeEventEmitter、BridgeDtoMapper
  security/
    KeyStoreSecretStore、SensitivePageGuard、RedactionPipeline
  runtime/
    ServiceHealthMonitor、BootRecovery、WorkScheduler
```

后台输入只能进入 `payment/session`，正式账单只能由 `decision` 调用 `ledger/repository` 写入；UI、通知回调和 AI 客户端都不能直接创建 `LedgerTransaction`。

### 4.2 技术选型

| 领域 | 方案 |
|---|---|
| 前端 | React Native 0.81 + TypeScript（保留 LiteNote） |
| 原生层 | Kotlin + Jetpack Compose（仅用于设置页等原生界面，可选） |
| 本地数据库 | Room + SQLCipher（默认加密，主密钥由 Keystore 派生） |
| 数据访问 | Native Module + React Native Bridge |
| 状态管理 | ViewModel + StateFlow，前端通过 Native Event 订阅 |
| 密钥保护 | Android Keystore |
| 配置存储 | DataStore |
| 后台任务 | WorkManager |
| 通知监听 | NotificationListenerService |
| 界面读取 | AccessibilityService（只读） |
| 截图 | AccessibilityService.takeScreenshot() |
| 前台轨迹 | UsageStatsManager + 无障碍窗口事件 |
| AI 视觉 | 手机直连外部多模态 AI API（通义千问 qwen-vl-plus 主用 / 智谱 GLM-4V-Flash 备用 / 硅基流动 Qwen3-VL-8B-Instruct 仅开发调试；阶段一真机验证通过 2026-08-04） |
| AI 对话 | 保留 LiteNote 多模型适配器（手机直连） |
| 语音 | OpenAI Whisper 兼容协议 ASR |
| 文件访问 | Storage Access Framework |
| 依赖注入 | Hilt（原生层） |
| 网络 | OkHttp + Retrofit（仅 AI API 调用） |
| 最低 Android 版本 | Android 13（API 33） |
| 厂商兼容性首发 | AOSP/原生 + 小米（华为/OPPO/vivo 暂不提供针对性引导） |

### 4.3 网络权限策略

应用声明 INTERNET 权限，但网络仅用于：

- AI 视觉识别 API 调用（用户显式启用时）。
- AI 对话记账 API 调用（用户使用对话/语音/拍照功能时）。
- 语音识别 ASR API 调用。

禁止：

- 上传账单内容、交易备注、账户名称到任何服务器（AI 请求除外，且用户知情）。
- 遥测、广告、行为分析。
- 远程账号同步（本项目无账号体系）。

---

## 5. 业务语义

### 5.1 五个核心维度

| 维度 | 定义 | 示例 |
|---|---|---|
| 支付入口 | 用户完成支付时使用的软件或通道 | 微信、支付宝、云闪付 |
| 资金账户 | 实际承担或接收资金的账户 | 微信零钱、余额宝、招商银行卡、花呗 |
| 消费平台 | 用户下单或获得服务的平台 | 淘宝、美团、滴滴、京东 |
| 实际商户 | 提供商品或服务的具体收款方 | 某餐厅、某便利店、某网店 |
| 消费分类 | 用于统计的业务类别 | 餐饮、交通、购物、住房 |

这五个维度不得复用同一个字段。例如，支付宝是支付入口，不应默认作为消费平台；花呗是负债账户，不应作为消费分类。AI 返回的 `platform` 字段对应"消费平台"，与支付入口是不同字段。

**商户字段的场景相关性**：商户（实际收款方）的价值因消费场景而异。外卖/到店/线下扫码场景下商户是高价值字段（用户关心"在哪家店消费"）；电商/出行/虚拟商品场景下商户是低价值字段（用户更关心商品说明或分类）。因此：

- `merchant` 字段降级为"场景相关字段"，非必填。
- AI 提示词弱化商户提取要求，专注平台和金额。
- 商户缺失时默认不阻塞入账（宽松模式），不进入待确认。
- 电商场景下 `productDescription`（商品说明）比商户更有意义，AI 可同时提取。
- 统计中的商户排行主要适用于餐饮/到店类消费，电商场景以分类和平台维度为主。

### 5.2 七种交易类型

| 类型 | 是否计入收入/支出 | 说明 |
|---|---:|---|
| 支出 | 是 | 商品或服务消费 |
| 收入 | 是 | 工资、报销、经营收入等 |
| 转账 | 否 | 用户自有账户之间移动资金 |
| 退款 | 冲减原支出 | 支持全额和部分退款 |
| 还款 | 否 | 偿还信用卡、花呗等负债 |
| 手续费 | 是 | 提现、转账或服务手续费 |
| 不计收支 | 否 | 余额变动但不应进入收支统计的记录 |

### 5.3 账户类型与余额机制

本项目采用"流水+余额"资产管理模式（流派 B）：账户有期初余额和当前余额，账单自动增减余额，支持手动校准，提供净资产视图。

#### 账户类型

- 现金账户（资产）。
- 电子钱包账户（资产，如微信零钱、支付宝余额）。
- 银行借记账户（资产）。
- 信用卡账户（负债）。
- 消费信贷账户（负债，如花呗、白条）。
- 其他资产或负债账户。

> 信用卡不配置账单日/还款日/额度等专属字段，仅作为负债类型标签。还款操作通过转账实现（从资产账户转入信用卡账户），不重复计入支出。

#### 账户字段

```text
accountId
name
type                // ASSET / LIABILITY
subtype             // CASH / E_WALLET / BANK_DEBIT / CREDIT_CARD / CONSUMER_LOAN / OTHER
initialBalanceMinor // 期初余额（分），用户创建时设定
currentBalanceMinor // 当前余额（分），由系统维护
currency
icon
color
sortOrder
isActive            // 启用/停用状态
createdAt
updatedAt
```

#### 余额计算规则

- `currentBalanceMinor` 由系统在每笔交易入账时自动更新，不由用户直接编辑。
- 更新公式：
  - 支出：`currentBalanceMinor -= amountMinor`（资产账户减少，负债账户增加欠款）。
  - 收入：`currentBalanceMinor += amountMinor`（资产账户增加，负债账户减少欠款）。
  - 转账：源账户扣减，目标账户增加。
  - 还款：从资产账户转出（扣减），负债账户转入（减少欠款），不计收支。
  - 退款：冲减原支出，金额加回原账户。
- 停用账户不参与余额计算和净资产统计，但保留历史交易。
- 余额更新与交易写入在同一事务中执行。

#### 余额校准

- 用户发现余额不准时可手动校准：输入实际余额，系统记录差额并生成一条"余额校准记录"（类型为"不计收支"）。
- 校准记录包含：账户、校准前余额、校准后余额、差额、时间、备注。
- 校准记录可撤销。

#### 净资产视图

- 净资产 = 所有资产账户 `currentBalanceMinor` 之和 - 所有负债账户 `currentBalanceMinor` 绝对值之和。
- 资产页展示：各账户余额列表、总资产、总负债、净资产、净资产趋势图。
- 账户余额精度说明：自动识别可能只识别到"微信支付"而非"微信零钱"具体账户，余额管理是辅助功能，不应作为财务对账依据。

### 5.4 字段优先级

字段更新优先级从高到低：

1. 用户明确编辑并锁定的值（fieldLocks）。
2. 用户确认的个人规则结果。
3. 带官方交易号的账单导入字段。
4. 支付成功通知/结果页的结构化字段。
5. AI 视觉识别的高置信度字段。
6. 支付会话推断的平台字段。
7. 默认规则和历史建议。

低优先级数据不得覆盖更高优先级数据，但可以作为冲突提示展示。

---

## 6. AI 支付识别主链路

### 6.0 购物页面到支付页面的转场

```text
BROWSING/PRODUCT_DETAIL
  -> 用户选择规格或点击“立即购买”
  -> CART（可选）
  -> CHECKOUT（出现最终应付金额和去支付动作）
  -> PAYMENT_HANDOFF（跳转微信/支付宝）
  -> PAYMENT_RESULT（成功/失败/取消）
```

`CheckoutSession` 在稳定的 `CHECKOUT` 首次成立时创建，状态先为 `CHECKOUT_DETECTED`，截图完成后进入 `SNAPSHOT_BUFFERED`/`AI_TRIGGER_ARMED`；只有购买意图信号出现后才进入 `AI_PENDING`。商品详情和购物车阶段只维护短时页面观察，不创建支付会话、不调用 AI。购物软件每次点击商品导致的详情页事件必须在 `PageRole` 层被拦截。

### 6.1 完整业务流程

```text
购物软件结算页
  -> 通用结算页信号检测
  -> 页面稳定 300~800ms
  -> 敏感页面检查（密码/验证码/二维码等禁止截图）
  -> 立即截图并放入 CheckoutSnapshotBuffer（只在内存，30 秒过期）
  -> 用户返回/查看其他商品：不调用 AI，过期后释放截图
  -> 用户提交订单或支付应用被调起
  -> 从当前内存快照创建一次 AI 请求
  -> AI 返回平台、商户、金额、订单号候选 + 置信度
  -> 跳转微信/支付宝并完成付款
  -> 通知或无障碍结果页确认成功
  -> 匹配候选金额、时间和支付应用
  -> 高置信度自动入账，否则进入待确认
  -> 同一支付不会因 AI、通知、无障碍多次写入
```

核心原则：

> AI 负责识别"这笔钱可能买了什么"，通知或支付结果页负责确认"这笔钱是否真的支付成功"。

### 6.2 截图主时机

截图主时机仍然是购物软件结算页，而不是进入微信/支付宝之后；但截图和 AI 上传是两个独立动作：

```text
结算页稳定
  -> 截图到内存（本地快照，不联网）
  -> 等待“提交订单/去支付/支付应用调起”等购买意图信号
  -> 只有此时才上传 AI
```

截图本身不花 AI token，也不应触发网络请求；进入微信/支付宝之后只负责支付成功确认和最终金额获取。

### 6.3 直接扫码支付（无结算页）

直接扫描线下收款码没有购物软件结算页，回退策略：

- 不强行猜测购物平台，`platform` 字段留空或标记为"线下扫码"。
- 从微信/支付宝扫码页或支付结果页识别商户和金额。
- 平台字段留空或进入待确认，不伪造平台。

### 6.4 AI 先返回

```text
CHECKOUT_DETECTED -> SNAPSHOT_BUFFERED -> AI_TRIGGER_ARMED
  -> 提交订单/支付应用调起 -> AI_PENDING -> AI_COMPLETED
  -> 用户跳转支付
  -> PAYMENT_CONFIRMED
  -> 匹配金额/时间/应用/订单号
  -> 自动入账或待确认
```

AI 返回的 `amount` 只是结算候选；即使置信度很高，也要等待独立支付成功证据。

### 6.5 支付先返回

```text
CHECKOUT_DETECTED -> SNAPSHOT_BUFFERED -> AI_TRIGGER_ARMED
  -> 用户完成支付，通知先到
  -> 支付通知回退触发 -> AI_PENDING
  -> PAYMENT_CONFIRMED -> WAITING_ENRICHMENT
  -> 等待 AI 结果 5~15 秒
  -> 完整且一致：自动入账
  -> 超时、缺字段或冲突：仅弹出一次待确认窗口
```

支付结果先到时先写 Room，不能因为 AI 尚未返回而丢弃支付，也不能立即用不完整字段静默入账。AI 晚到时只允许更新原会话和候选版本，不得创建第二笔账单。

### 6.6 AI/截图失败回退

- 结算页检测成功但截图被 `FLAG_SECURE` 拒绝：停止截图，继续等待通知/结果页；
- AI 超时、429、非法 JSON 或额度耗尽：保留支付金额和来源，缺失的平台/商户进入待确认；
- 支付失败、取消或用户退回购物软件：会话关闭为 `FAILED`/`CANCELLED`，不创建正式账单；
- 无网络：不补发旧截图，不在网络恢复后自动重传已离开的页面；用户可手动重试当前待确认记录。
- 快照过期后提交订单：若当前页面仍是结算页且可安全读取，立即补截一张新图上传 AI；否则直接走通知/结果页待确认流程。

### 6.7 四次结算页示例

```text
商品 A 结算页 -> 截图 A 入内存 -> 返回 -> 30 秒后释放
商品 B 结算页 -> 截图 B 覆盖 A -> 返回 -> 释放
商品 C 结算页 -> 截图 C 覆盖 B -> 返回 -> 释放
商品 D 结算页 -> 截图 D 入内存
提交订单/支付应用调起 -> 仅上传截图 D 一次
支付成功 -> 关联 D 的 AI 候选并入账
```

如果用户在截图过期后才提交订单，默认不上传过期图片；若当前仍能安全读取 `CHECKOUT` 页面，可以重新截取一张"触发时快照"并只允许一次补拍，否则直接走通知/结果页待确认流程。无论哪种情况，都不能复用 A/B/C 的旧快照或重复调用 AI。

**连续支付多笔示例**：

```text
结算页A -> 截图A -> 提交A -> 图片从buffer取出上传AI A（buffer已清空）-> 支付A成功
  -> 用户回到购物软件
结算页B -> 截图B（新session）-> 提交B -> 上传AI B -> 支付B成功
  -> AI A返回 -> 回填session A -> 入账A
  -> AI B返回 -> 回填session B -> 入账B
两笔独立入账，互不干扰
```

AI 上传时图片从 buffer 取出，后续覆盖不影响已上传的请求。每笔交易是独立 session，新订单新会话，支付通知按 session 匹配，不会串单。

### 6.8 方案收益和代价

| 项目 | 结果 |
|---|---|
| AI 请求量 | 从“每个结算页一次”降为“出现真实购买意图后一次”，典型 A/B/C/D 场景为 1 次 |
| 本地截图量 | 仍可能有 4 次，但每次只保留当前槽位，旧图片立即释放 |
| 网络和 token | 用户放弃 A/B/C 时不产生上传、token 和服务商费用 |
| 识别时效 | 提交/支付调起后才开始网络识别，AI 可能晚于支付结果，需要 `WAITING_ENRICHMENT` |
| 误关联风险 | 通过快照版本、订单/金额指纹、支付交接窗口和会话 ID 限制串单 |
| 主要代价 | 提交信号可能不可读、用户可能超过 30 秒才付款，因此必须保留通知/结果页和手动确认回退 |

这套方案的正确目标不是“零截图”，而是“只在有购买意图时产生 AI 网络请求”。

---

## 7. 购物软件结算页检测

### 7.1 通用检测条件

不为每个平台编写完整控件 ID 适配，只做通用候选判断：

```text
当前包名属于支持的购物软件白名单
  + PageRole = CHECKOUT（不是商品详情或购物车）
  + 页面包含最终金额语义（应付/实付/订单金额/优惠后合计）
  + 页面包含结算动作（提交订单/去支付/确认订单）
  + 敏感页面检查通过（非密码/验证码/二维码页面）
```

满足全部条件后才截图。仅有价格、单价、总计、立即购买或加入购物车中的任意一项，不足以满足条件。平台包名用于确定当前应用，具体平台、商户和页面语义由 AI 从截图中提取。

### 7.2 支持的购物平台包名

首发支持常见平台包名（示例）：

- 淘宝：`com.taobao.taobao`
- 天猫：`com.tmall.wireless`
- 京东：`com.jingdong.app.mall`
- 美团：`com.sankuai.meituan`
- 拼多多：`com.xunmeng.pinduoduo`
- 滴滴：`com.sdu.didi.psnger`
- 抖音：`com.ss.android.ugc.aweme`
- 小红书：`com.xingin.xhs`

未知包名交给待确认流程，不根据 Logo 或单个文本强行推断。

### 7.3 小程序与 WebView 处理

微信/支付宝小程序与原生页面包名相同，需特殊处理：

- 监听无障碍窗口标题，提取小程序名称作为平台候选。
- WebView 结算页节点不可读时，依赖截图 AI 识别。
- 无法可靠判断时保持平台为空，进入待确认。

### 7.4 页面稳定判断

不对每个无障碍事件截图，也不在截图完成后立即请求 AI。推荐流程：

```text
检测到疑似结算页
  -> 页面稳定 300~800ms（无新事件）
  -> 截图一次，写入 CheckoutSnapshotBuffer
  -> 启动 30 秒本地过期计时器
  -> 等待提交订单/支付应用调起信号
  -> 触发时才把当前快照交给 AI
```

同一页面的重复无障碍事件只更新观察时间；新结算页用新快照覆盖旧快照并重置 30 秒计时器。只有购买意图信号出现后，才按请求键执行 AI 并发去重和有限重试（默认最多 2 次 AI 尝试）。

手动截图（用户点击悬浮按钮）可放宽稳定窗口：用户主动点击视为页面已稳定，无需等待 300~800ms。手动截图与自动截图共用同一 buffer，按时间戳保留最新。手动截图后，同一页面指纹内的自动检测不再重复截图。

### 7.5 页面角色模型：商品详情不是结算页

购物软件的无障碍事件通常会在用户浏览商品、切换规格、展开优惠、进入购物车和确认订单时连续触发。不能把“看到了价格”或“页面有立即购买按钮”当作结算页，否则用户每点开一个商品详情就会产生一次 AI 上传。

检测器必须先给当前窗口分配页面角色，再决定是否允许截图：

| `PageRole` | 典型信号 | 是否允许预捕获 | 说明 |
|---|---|---:|---|
| `BROWSING` | 搜索、推荐、首页、列表 | 否 | 没有当前订单证据 |
| `PRODUCT_DETAIL` | 商品标题、规格、单价、加入购物车、立即购买 | 否 | “立即购买”只是动作，不表示订单已生成 |
| `CART` | 购物车、勾选商品、去结算 | 否，除非页面已出现最终订单字段 | 购物车金额可能仍会变化 |
| `CHECKOUT` | 应付/实付/合计、收货信息、优惠后金额、提交订单/去支付 | 是（仅内存） | 只有该角色可建立截图缓冲；AI 上传还需购买意图 |
| `PAYMENT_HANDOFF` | 跳转微信/支付宝、选择支付方式、支付授权 | 否 | 已离开购物平台；不再预捕获或重新截图，但可消费已有快照 |
| `PAYMENT_RESULT` | 支付成功/失败/已完成、交易金额 | 否 | 只读取支付结果证据，不截图 |
| `UNKNOWN` | 文本不足、页面转场或无法判断 | 否 | 等待下一组稳定事件 |

`PRODUCT_DETAIL` 的最低判定示例：出现“立即购买”+单价仍然只能归为商品详情；必须看到“应付/实付/订单金额/合计”等最终金额语义，并且同时出现“提交订单/去支付/确认订单”等结算动作，才可以升级为 `CHECKOUT`。单个关键词、应用包名、截图中出现一个价格，均不足以触发 AI。

页面角色判定应保守处理：

```text
无障碍窗口/包名变化
  -> 清空当前页面稳定计时
  -> 重新收集文本和节点摘要
  -> 先判定 PageRole，再进入截图候选
```

当购物软件把结算页放在 WebView、小程序或自绘 Canvas 中时，节点树可能只有包名和少量标题。此时仍要求有至少一个可靠的结算语义或由用户为该应用开启“实验性结算检测”；实验性模式也只能生成待确认候选，不能绕过支付成功证据。

### 7.6 截图缓冲与购买意图触发

结算页检测只创建一个短生命周期的 `CheckoutSnapshotBuffer`，不立即创建网络 AI 请求。缓冲区是单槽或按前台购物包名分槽的内存对象，至少包含：

```text
snapshotId
sessionId
shoppingPackage
pageRole = CHECKOUT
pageFingerprint
capturedAt
expiresAt = capturedAt + 30s
imageBytes                 // 仅内存
sourceReason = CHECKOUT_STABLE | MANUAL_CAPTURE
```

写入规则：

1. 第一次稳定的 `CHECKOUT` 页面捕获一张快照，启动 30 秒倒计时。
2. 同一指纹的无障碍事件、滚动、倒计时和广告刷新不重新截图。
3. 新订单号、金额或页面实质变化产生新快照；旧 `imageBytes` 先清零并释放，再覆盖槽位并重置倒计时。
4. 页面回到 `PRODUCT_DETAIL`、`CART` 或离开购物应用到非支付应用时不触发 AI；识别到 `PAYMENT_APP_HANDOFF` 时必须先消费一次有效快照，再停止购物页观察。
5. 快照过期后不得上传旧图片。用户随后提交订单时，若仍能安全读取结算页，可按“补拍一次”策略捕获当前页面；否则直接走支付通知/结果页待确认。

如果旧快照还没有被购买意图消费，进入新结算页时将旧的 `AI_TRIGGER_ARMED` 会话标记为 `CANCELLED` 后再覆盖；如果旧快照已经触发 AI，旧请求可以完成，但结果只能写回旧 `sessionId`，不能被新商品复用。

这里的“截图成本低”只表示不产生网络流量、AI token 或服务商费用，不承诺所有设备都能在 5~10ms 完成。`takeScreenshot()` 仍会占用短时 CPU、内存和系统截图资源，因此必须按稳定页面节流，不能对每个无障碍事件调用。

AI 单次费用不能在文档中固定写成某个金额；它取决于图片尺寸、模型、输入/输出 token 和服务商定价。实现必须使用实际响应中的 usage（或本地估算）记录成本，不能把“截图本地免费”误解为“最终识别免费”。

AI 触发信号按可信度和可观测性排序：

| 信号 | 触发条件 | 动作 |
|---|---|---|
| `SUBMIT_ACTION` | 无障碍节点出现“提交订单/去支付/确认订单”并伴随点击或页面转场 | 立即消费当前快照，发起一次 AI 请求 |
| `PAYMENT_APP_HANDOFF` | 购物包名切换到微信/支付宝，且在支付交接窗口内存在有效快照 | 消费当前快照，发起一次 AI 请求 |
| `PAYMENT_PAGE_VISIBLE` | 购物应用显示支付方式确认页，随后即将跳转支付 | 消费当前快照；不截支付密码页 |
| `PAYMENT_NOTIFICATION_FALLBACK` | 支付通知先到，仍有未过期快照但未观察到按钮/包名事件 | 仅允许一次补触发，用于补全平台/商户 |
| `USER_RETRY` | 用户在待确认中心明确点击重新识别 | 重新截图或使用当前安全页面，仍受次数上限约束 |

触发器必须满足“有有效快照 + 未触发过 + 请求预算未耗尽”。`SUBMIT_ACTION`、`PAYMENT_APP_HANDOFF` 和通知回退共用同一个 `checkoutRequestKey`；第一个到达的信号写入 `aiTriggerReason`，后续信号只做幂等观察。无快照、快照已过期、用户已取消或当前页面命中敏感保护时，不得因为普通通知或 RN 重渲染而上传图片。AI 触发后快照立即从内存释放；AI 请求只保留 `requestId` 和结果，不保留图片。

### 7.7 页面指纹和 `CheckoutRequestKey`

每个页面事件都必须经过规范化和哈希，不能使用事件次数作为请求依据。定义以下运行时字段：

```text
shoppingPackage       当前购物应用包名
windowClass           当前窗口/Activity/无障碍根节点类别（可为空）
pageRole              BROWSING/PRODUCT_DETAIL/CART/CHECKOUT/...
normalizedTextHash    去除动态文本后的页面语义哈希
amountMinor           当前解析到的应付/实付金额（分）
orderId               当前页面可见订单号（可为空）
platformHint          包名或窗口标题产生的平台候选
pageFingerprint       上述字段的稳定哈希
checkoutRequestKey    shoppingPackage + pageFingerprint + orderId/金额版本
snapshotId            当前内存截图 ID
snapshotRevision     当前快照版本
snapshotExpiresAt     内存快照过期时间
aiTriggerReason       SUBMIT_ACTION/HANDOFF/NOTIFICATION_FALLBACK/USER_RETRY
aiTriggeredAt         真正上传 AI 的时间；仅截图时为空
candidateRevision     页面实质变化时递增的版本号
lastObservedAt        最近一次观察时间
```

`normalizedTextHash` 生成前必须删除或归一化以下内容，避免倒计时和动画导致指纹变化：

- 当前时间、日期和相对时间；
- “还剩 09:59”一类倒计时；
- 推荐商品、广告位、轮播序号和随机排序文本；
- 加载占位符、动画状态和无关 accessibility 描述；
- 多余空格、大小写差异、货币符号格式差异。

指纹不得包含完整截图、支付密码、验证码或完整银行卡号。订单号若存在应保留脱敏后的哈希，用于区分同额不同订单。

### 7.8 请求状态和单次上传规则

每个 `checkoutRequestKey` 有且只有一个请求状态：

```text
NOT_ELIGIBLE       尚未满足结算页条件
SNAPSHOT_BUFFERED  已截图到内存，尚未产生网络请求
ARMED              有效快照等待购买意图触发
ELIGIBLE           已收到购买意图，等待 AI 调度
IN_FLIGHT          已上传，等待响应
SUCCEEDED          已得到并校验的 AI 结果
FAILED_RETRYABLE   网络/超时等可重试失败
FAILED_FINAL       非法响应、敏感页面或达到上限
CANCELLED          页面离开或会话取消
EXPIRED            会话过期
```

调度器必须同时满足以下约束：

1. **触发前不联网**：`SNAPSHOT_BUFFERED`/`ARMED` 只保存内存快照和过期时间，不创建 HTTP 请求，不消耗 AI 配额。
2. **并发去重**：同一个 `checkoutRequestKey` 已处于 `IN_FLIGHT` 时，后续事件只能更新 `lastObservedAt`，不能创建第二个 HTTP 请求。
3. **结果缓存**：`SUCCEEDED` 结果在本地缓存到会话结束；相同 `checkoutRequestKey` 的提交、交接和通知事件直接复用结果，不因触发来源不同而重新上传图片。
4. **会话上限**：一个支付会话默认最多 2 次 AI 请求（首次触发 + 一次实质变化/可重试失败）；本地快照次数不计入该上限，达到上限进入待确认。
5. **全局节流**：AI 网络请求之间设置最小间隔（默认 1.5 秒），防止多个购买意图事件同时触发请求；本地截图不受该网络间隔限制，但仍按页面稳定事件节流。
6. **页面离开取消**：页面角色变成 `BROWSING`、`PRODUCT_DETAIL`、`CART` 或包名离开购物应用到非支付应用时，尚未开始的 `ARMED`/`ELIGIBLE` 状态取消；`PAYMENT_HANDOFF` 必须先执行一次触发再停止购物页观察；已开始的请求允许完成，但结果只能写回仍有效的会话。
7. **新订单新会话**：订单号变化、金额变化或从详情/购物车升级为新的结算页时建立新的 `CheckoutSession` 和新的请求键。
8. **同额不复用**：没有订单号时，即使金额相同，也必须同时满足短时间窗口、页面指纹和应用转场关系；无法区分时建立并发候选，不能直接复用上一笔结果。

### 7.9 允许重新请求的条件

同一会话只有下列情况可以创建新的 `candidateRevision`：

| 情况 | 动作 |
|---|---|
| 结算金额发生变化 | 取消旧的未开始请求，创建新指纹；旧结果保留为历史候选 |
| 订单号出现或发生变化 | 以订单号哈希重新建立请求键 |
| 页面角色从 `PRODUCT_DETAIL`/`CART` 升级为 `CHECKOUT` | 只捕获一张新快照并进入 `ARMED`，不联网 |
| 观察到 `SUBMIT_ACTION` 或 `PAYMENT_APP_HANDOFF` | 消费当前未过期快照，创建第一次 AI 请求 |
| 页面语义发生实质变化 | 重新计算哈希；只有字段变化超过阈值才重试 |
| 首次请求超时/网络失败 | 在退避后最多重试一次，且仍有购买意图和安全快照 |
| 用户明确重新发起支付 | 新建支付会话，不复用旧结果 |

以下变化绝不允许重传或触发 AI：无障碍事件重复、页面滚动、倒计时变化、广告刷新、同一截图的回调重复、用户仅返回商品详情、进入微信/支付宝后的支付结果变化。

### 7.10 推荐实现伪代码

```text
onAccessibilityWindowChanged(event):
    page = collectWindowSnapshot(event)
    role = pageRoleClassifier.classify(page)
    if role == PAYMENT_HANDOFF:
        onPurchaseIntent(PAYMENT_APP_HANDOFF)
        return
    if role != CHECKOUT:
        snapshotBuffer.observeNonCheckout(page, role)
        return

    stablePage = stabilityBuffer.awaitStable(page, 300..800ms)
    fingerprint = fingerprintBuilder.build(stablePage)
    if snapshotBuffer.hasSameFingerprint(fingerprint):
        snapshotBuffer.touch()
        return

    image = screenshotter.captureInMemory(stablePage)
    snapshotBuffer.replace(
        image = image,
        fingerprint = fingerprint,
        expiresAt = now + 30s
    )
    sessionManager.armAiTrigger(snapshotBuffer.metadata)

onPurchaseIntent(signal):
    // signal = SUBMIT_ACTION | PAYMENT_APP_HANDOFF | PAYMENT_NOTIFICATION_FALLBACK
    buffered = snapshotBuffer.current()
    if buffered == null || buffered.isExpired() || !sensitiveGuard.isSafe(buffered):
        return fallbackToPaymentEvidence()
    key = CheckoutRequestKey.from(buffered.metadata) // 不把触发来源放进 key
    if requestStore.isTriggeredOrInFlight(key):
        return
    requestStore.markEligible(key, signal.reason)
    image = snapshotBuffer.takeAndClear() // 清空内存快照，避免再次上传
    aiClient.recognize(image, key)
        .onSuccess { result -> requestStore.markSucceeded(key, result) }
        .onFailure { error -> requestStore.markFailed(key, error) }
```

实现时必须把 `sessionId`（一段支付旅程）、`snapshotId`（一次本地快照）和 `checkoutRequestKey`（一次购买意图触发的 AI 请求）分开存储；前者用于和通知匹配，第二个用于内存生命周期，第三个用于防止网络重复上传。截图回调、RN 重渲染和支付结果变化都不能直接调用 `aiClient`。

---

## 8. AI 识别协议

### 8.1 请求格式

调用外部多模态 AI API，上传截图（Base64 或二进制）+ 文本提示。AI 请求只由购买意图触发器发起，不由“进入结算页”、通知监听器、支付结果页或 RN 页面直接发起；结算页阶段只产生内存快照。

请求至少包含以下逻辑字段（实际字段按服务商适配器转换）：

```json
{
  "requestId": "local-ai-request-uuid",
  "checkoutRequestKey": "sha256(...) ",
  "promptVersion": "checkout-v1",
  "image": { "mimeType": "image/jpeg", "base64": "..." },
  "expectedScreenType": "checkout",
  "responseFormat": "json_schema"
}
```

实现约束：

- `requestId`、`checkoutRequestKey` 和 `sessionId` 不得混为一个字段；`requestId` 只用于一次 HTTP 尝试，`checkoutRequestKey` 用于重放去重；
- 默认在内存中把图片缩放到最长边不超过 1600px，使用 JPEG/WebP 压缩；若压缩后仍超过配置大小上限，放弃本次请求并进入待确认；
- 不发送设备截图的 EXIF、地理位置、相册路径或原始文件名；
- HTTP 层设置连接、写入、读取和总超时；响应超过 1MB 或不是预期 JSON 时立即失败；
- 服务商返回后先校验 JSON，再将字段转换为分为单位，不能直接把模型浮点数写入账本；
- 网络重试不保存图片到磁盘，重试必须重新截图并重新计算页面指纹，且受第 7 节的次数上限约束。

### 8.2 响应 JSON Schema

```json
{
  "screenType": "checkout",
  "platform": "京东",
  "merchant": "某商户",
  "amount": "99.00",
  "currency": "CNY",
  "orderId": null,
  "paymentMethod": null,
  "confidence": 0.86,
  "fieldConfidence": {
    "platform": 0.9,
    "merchant": 0.7,
    "amount": 0.95,
    "orderId": 0.0
  }
}
```

### 8.3 本地保存的会话信息

- `sessionId`。
- `checkoutRequestKey`、`pageFingerprint`、`candidateRevision`。
- `snapshotId`、`snapshotCapturedAt`、`snapshotExpiresAt`、`aiTriggerReason`。
- `requestId`（仅保存不可逆哈希或短 ID，不保存完整网络请求内容）。
- 请求状态、尝试次数、首次/最近观察时间和取消原因。
- 截图时间。
- 当前购物软件包名。
- AI 服务商和模型名称。
- 字段级置信度或整体置信度。
- AI 原始结果是否通过 JSON Schema 校验。
- 请求耗时和 token 消耗（用于成本控制）。

### 8.4 安全约束

- AI 返回的 `success` 字段不能作为支付成功依据。
- AI 返回的 `paymentMethod` 仅作参考，实际资金账户以支付结果页/通知为准。
- AI 返回结果必须通过 JSON Schema 校验，校验失败进入待确认。
- AI 返回的金额只能作为结算候选；最终账单金额必须优先使用支付通知或支付结果页金额。
- AI 不得接收支付密码、短信验证码、完整银行卡号、二维码、条形码或登录凭证页面。
- 日志只记录服务商、模型、状态码、耗时和错误类别，不记录 Base64、原始响应、完整页面文本或 API Key。

### 8.5 AI 错误分类和可恢复性

| 错误类别 | 是否重试 | 会话动作 |
|---|---:|---|
| 网络断开、连接超时、服务商 5xx | 是，最多一次 | `FAILED_RETRYABLE`，仍在 `CHECKOUT` 才重试 |
| 429/额度不足 | 否（本会话） | `FAILED_FINAL`，等待支付通知并进入待确认 |
| 4xx 鉴权或模型配置错误 | 否 | 标记 AI 配置错误，不阻塞支付 |
| 响应不是 JSON、字段越界、`screenType` 非结算页 | 否 | 丢弃结果，保留支付金额，进入待确认 |
| 敏感页面/系统拒绝截图 | 否 | 不上传，记录本地原因码 |
| AI 返回金额与页面候选不一致 | 不因冲突重试 | 保存冲突证据，支付成功后禁止自动入账 |

---

## 9. 支付成功确认

### 9.1 可信来源优先级

支付成功的可信来源按优先级处理：

1. 微信/支付宝系统支付通知。
2. 微信/支付宝支付结果页的无障碍文本。
3. 用户手动确认。

“系统没有显示顶部通知横幅”不等于“没有通知事件”。通知监听器应直接读取 `Notification` 对象的标题、展开文本、`textLines`、`subText`、`summaryText`、`bigText` 和 `tickerText`，再交给统一解析器。横幅展示由系统通知渠道和厂商策略决定，不能作为识别成功的前置条件。

每条通知先生成 `notificationEventKey`：优先使用来源包名、通知 key、通知 ID、post time 和归一化金额的组合；通知更新时沿用同一 key 并更新候选，不再创建新的支付事件。解析器必须排除收款、到账、转入、红包、AA、提现等非消费语义，不能只看“支付/消费”关键词。

### 9.2 匹配条件

AI 结算页结果与支付结果需要满足以下匹配条件：

```text
金额相同或在允许误差内（默认 ±0.01 元）
  + 时间间隔在支付会话窗口内（默认 5 分钟）
  + 支付应用为微信或支付宝
  + 状态为支付成功
```

金额匹配使用整数分比较；优惠、运费、服务费和分期等导致金额不同的情况必须保留冲突原因。订单号一致时优先级最高；没有订单号时还要验证结算页在支付应用之前出现、应用切换序列合理且会话未被其他支付结果占用。仅凭“同金额 + 五分钟内”不能自动合并两笔连续支付。

**优惠后金额差异处理**：AI 候选金额 ≥ 支付金额（优惠后下降）视为正常行为，不进入待确认，以支付金额入账。只有 AI 候选金额 < 支付金额（实付高于结算页）才视为冲突，进入待确认。

**合并支付限制声明**：合并支付（购物车多商户一次付款）是已知限制，MVP 不自动拆分。AI 识别到1个商户和总金额，但实际是多笔消费时，进入待确认由用户手动拆分。

**跨午夜时间口径**：交易时间以支付发生时间（`occurredAt`）为准，不以通知到达时间为准。6/30 23:50 支付、7/1 00:05 通知到达的交易计入 6 月。

**多支付会话并发匹配**：订单号一致时优先级最高，直接匹配。没有订单号时，按金额+时间窗口+会话未被占用综合匹配。两个并发支付会话无法区分时建立并发候选，不强行合并，由用户在待确认中心选择。

支付结果标准化为：

```text
PaymentEvidence {
  evidenceId
  sourceType = NOTIFICATION | ACCESSIBILITY_RESULT | MANUAL
  paymentPackage
  status = SUCCESS | FAILED | CANCELLED | UNKNOWN
  amountMinor?
  merchantRaw?
  orderId?
  occurredAt
  notificationEventKey/accessibilityEventKey
  confidence
}
```

`PaymentResultNormalizer` 只负责把来源变成证据；`TransactionMatcher` 负责将证据和 `CheckoutSession` 关联；`AutoBookkeepingDecision` 负责入账或弹窗。三者不能由通知回调直接串成“收到通知就 POST 账单”。

### 9.3 进入待确认的情况

- 金额不一致。
- 状态不明确。
- AI 置信度过低（默认 < 0.7）。
- 会话已过期。
- 多个候选平台同时存在。
- 规则冲突。
- 疑似重复交易。

待确认不静默入账，由用户处理。

---

## 10. 数据模型

### 10.1 CheckoutSession（支付会话）

```text
sessionId
shoppingPackage         // 购物软件包名
shoppingAppLabel
pageRole                // CHECKOUT 等页面角色
windowClass
pageFingerprint         // 规范化页面指纹
checkoutRequestKey      // 单次页面版本请求键
requestState            // SNAPSHOT_BUFFERED/ARMED/IN_FLIGHT/SUCCEEDED/...
snapshotId              // 当前或最近一次内存快照
snapshotExpiresAt       // 30 秒缓冲过期时间
aiTriggerReason         // SUBMIT_ACTION/HANDOFF/...
aiTriggeredAt            // 真正上传 AI 的时间
attemptCount            // 本会话 AI 请求次数（不含本地截图）
aiRequestId             // 一次 HTTP 尝试的本地 ID
candidateRevision       // 页面实质变化版本
lastObservedAt
platform                // AI 识别的消费平台
merchant                // AI 识别的商户
amount                  // AI 识别的金额
orderId                 // AI 识别的订单号
currency
aiConfidence            // AI 整体置信度
fieldConfidence         // 字段级置信度
capturedAt              // 截图时间
aiProvider              // AI 服务商
aiModel                 // AI 模型
aiLatencyMillis         // AI 请求耗时
aiTokenConsumed         // token 消耗
paymentPackage          // 微信/支付宝包名
paymentAmount           // 实际支付金额
paymentStatus           // 支付状态
paymentConfirmedAt      // 支付确认时间
paymentChannelId        // 支付入口
accountId               // 资金账户
sourceIds               // 关联的来源事件
state                   // 会话状态
conflictReason          // 冲突原因
paymentEvidenceIds      // 通知/无障碍证据
candidateSourceIds      // AI 和页面来源
autoBookkeepingDecision // AUTO_COMMIT/REVIEW/WAIT
userDecision            // CONFIRM/EDIT/DEFER/IGNORE
lastErrorCode
expiresAt
createdAt
updatedAt
```

### 10.2 会话状态机

```text
CHECKOUT_DETECTED   // 检测到结算页
SNAPSHOT_BUFFERED   // 已截图到内存，等待购买意图
AI_TRIGGER_ARMED    // 30 秒内等待提交/支付应用调起
AI_PENDING          // 已触发并上传，等待 AI 返回
AI_COMPLETED        // AI 返回结果
AI_FAILED           // AI 请求失败或校验失败
WAITING_PAYMENT     // 等待支付确认
PAYMENT_CONFIRMED   // 支付已确认
WAITING_ENRICHMENT  // 支付先到，等待 AI 补全
REVIEW_REQUIRED     // 需要人工确认
COMPLETED           // 已完成入账
CANCELLED           // 用户返回/支付取消
EXPIRED             // 会话超时
```

状态转换规则：

- `CHECKOUT_DETECTED` → `SNAPSHOT_BUFFERED`（本地截图完成，不联网）
- `SNAPSHOT_BUFFERED` → `AI_TRIGGER_ARMED`（启动 30 秒过期计时）
- `AI_TRIGGER_ARMED` → `AI_PENDING`（提交订单/支付应用调起/允许的支付通知回退）
- `AI_TRIGGER_ARMED` → `EXPIRED`（30 秒内没有购买意图，释放快照）
- `AI_PENDING` → `AI_COMPLETED`（AI 返回有效结果）
- `AI_PENDING` → `AI_FAILED`（请求超时、网络错误、校验失败）
- `AI_COMPLETED` → `WAITING_PAYMENT`（等待用户支付）
- `AI_FAILED` → `WAITING_PAYMENT`（回退到通知识别）
- `WAITING_PAYMENT` → `PAYMENT_CONFIRMED`（收到支付成功信号）
- `WAITING_PAYMENT` → `EXPIRED`（超过会话窗口，默认 5 分钟）
- `PAYMENT_CONFIRMED` → `COMPLETED`（AI 已完成且匹配成功，自动入账）
- `PAYMENT_CONFIRMED` → `REVIEW_REQUIRED`（AI 已完成但金额冲突、置信度低等）
- `REVIEW_REQUIRED` → `COMPLETED`（用户确认）
- `PAYMENT_CONFIRMED` → `WAITING_ENRICHMENT`（支付先到且 AI 仍在途）
- `WAITING_ENRICHMENT` → `COMPLETED`（AI 补全后满足自动入账条件）
- `WAITING_ENRICHMENT` → `REVIEW_REQUIRED`（超时或字段仍缺失）
- 任何未完成状态 → `CANCELLED`/`EXPIRED`（用户取消、应用离开或会话超时）
- 支付失败/取消的会话立即标记为 `FAILED`/`CANCELLED`，即使未过期，以避免与新会话冲突

状态变更必须通过一个事务写入会话、证据关联和审计记录。进程被杀后以 Room 中的状态恢复，不以 React Native 内存状态为准。

### 10.3 Transaction（交易）

```text
id
bookId
type                // 支出/收入/转账/退款/还款/手续费/不计收支
amountMinor         // 最小货币单位金额（Long，非 Float/Double）
currency
occurredAt
timezoneId
accountId           // 资金账户
targetAccountId     // 转账/还款的目标账户
paymentChannelId    // 支付入口
platformId          // 消费平台
merchantId          // 标准商户
merchantRaw         // 原始商户文本
categoryId          // 消费分类
note
confidence
reviewStatus        // 已确认/自动确认/待确认/忽略
fieldLocks          // 用户锁定的字段，防止自动覆盖
statementType       // 原始交易类型
productDescription  // 商品说明
createdAt
updatedAt
```

### 10.4 TransactionSource（交易来源）

一笔正式交易可由多个来源合并：

- 来源类型：AI 视觉、通知、无障碍节点、银行短信、导入、手动。
- 来源应用包名。
- 来源事件标识。
- 支付会话标识。
- 来源订单号或交易号。
- 原始发生时间。
- 脱敏后的原始文本或结构化字段。
- 解析器名称和版本。
- 原始字段哈希。
- 合并依据。

### 10.5 其他核心实体

- `Book`：账本信息。
- `Account`：资产或负债账户，含期初余额、当前余额、启用状态（见 5.3 节）。
- `PaymentChannel`：支付入口。
- `Platform`：消费平台。
- `Merchant`：标准商户和别名。
- `Category`：分类层级、图标和颜色。
- `Rule`：个人自动化规则、优先级和命中信息。
- `ImportBatch`：导入文件哈希、版本、状态和汇总。
- `TransactionLink`：退款、转账、还款、拆分或合并关系。
- `AuditEntry`：重要自动操作和用户修改记录（保留 90 天，超期自动清理）。
- `AiProviderConfig`：AI 服务商配置（地址、模型、密钥引用、成本上限）。
- `AiCostRecord`：AI 调用成本记录（按月汇总）。

实现时至少需要以下独立表/实体，而不是把字段全部塞进 `CheckoutSession`：

| 实体 | 必要字段 | 约束 |
|---|---|---|
| `CheckoutRequest` | `id`、`sessionId`、`snapshotId`、`checkoutRequestKey`、`pageFingerprint`、`candidateRevision`、`requestState`、`triggerReason`、`attemptCount`、`aiRequestId`、`triggeredAt`、`startedAt`、`completedAt`、`errorCode` | 快照阶段可为 `ARMED` 且没有网络时间；`checkoutRequestKey` 在活动会话内唯一；同一请求键只能有一个 `IN_FLIGHT` |
| `PaymentEvidence` | `id`、`sourceType`、`sourceEventKey`、`paymentPackage`、`status`、`amountMinor`、`orderId`、`occurredAt`、`confidence` | `sourceEventKey` 全局幂等；同一证据不可重复挂账 |
| `TransactionCandidate` | 会话、证据、字段值、字段来源、冲突、评分、审核状态 | 候选可以被用户编辑，但不覆盖原始证据 |
| `LedgerTransaction` | 正式账单字段、`sourceCandidateId`、`commitType`、`fieldLocks` | 同一候选只能提交一次；订单号非空时建立辅助去重索引 |

金额字段全部以 `Long amountMinor` 保存；AI/通知的十进制文本只在解析边界转换一次。时间保存 UTC 时间戳并保留设备时区，不能使用本地格式化字符串作为去重键。

---

## 11. 功能需求

### 11.1 首次启动与权限引导

#### FR-001 权限分层

| 权限或能力 | 用途 | MVP 建议 | 约束 |
|---|---|---|---|
| 通知读取 | 捕获微信、支付宝扣款通知 | 必开 | 只持久化已启用来源 |
| 无障碍服务 | 结算页检测、截图、支付结果页识别 | 必开 | 只读，不点击、不输入 |
| 悬浮窗 | 手动截图悬浮按钮 | 建议开启 | `SYSTEM_ALERT_WINDOW`，用户可关闭 |
| 应用使用情况 | 获取支付前后短时前台应用序列 | 建议开启 | 仅保存命中支付会话的包名与时间 |
| 网络 | AI 视觉识别、AI 对话、语音 ASR | 必开 | 仅用于 AI API，不上传账单 |
| 忽略电池优化 | 提高监听服务稳定性 | 建议开启 | 显示实际状态和关闭路径 |
| 短信读取 | 识别银行扣款、退款、还款短信 | 可选 | 过滤验证码，默认不启用 |
| 识别结果通知 | 入账后发送本地通知 | 必开 | Android 13+ 运行时授权 |
| 应用锁 | 生物识别解锁 | 可选 | 本地验证 |

权限分为"基础模式"和"增强模式"：

- 基础模式：通知 + 手动记账 + 账单导入。
- 增强模式：增加无障碍、AI 视觉识别、悬浮窗手动截图、应用轨迹和可选短信。

用户可随时关闭任一来源，关闭后不影响已有账目。

#### FR-002 首次启动流程

- 展示本地处理和隐私原则。
- 创建默认账本和基础分类。
- 选择需要识别的支付应用。
- 选择基础模式或增强模式。
- 配置 AI 服务商（地址、模型、API Key）。
- 分步引导通知读取、无障碍、应用使用情况和电池优化设置。
- 每次从系统设置返回后立即检测真实权限状态。
- 提供模拟支付会话测试，验证 A/B/C 放弃、D 提交时可以产生多次内存快照但只发出一次 AI 请求。
- 允许跳过自动识别，仅使用手动记账和账单导入。

#### FR-003 运行健康状态

设置页应展示：

- 通知读取权限是否有效。
- 无障碍服务是否启用、服务连接是否正常。
- 应用使用情况访问是否有效。
- AI 视觉识别是否启用、AI 服务商配置是否有效。
- 最近一次收到支持来源通知的时间。
- 最近一次识别到结算页的时间。
- 最近一次 AI 识别成功/失败及原因。
- 最近一次成功解析的时间。
- 本月 AI 调用次数和预估成本。
- 设备是否存在可能影响运行的后台限制。

**状态异常提醒**：运行状态异常时不仅设置页显示，还应发送本地通知提醒用户。包括：无障碍服务被关闭、通知监听权限被撤销、AI 服务商持续失败等。

### 11.2 AI 视觉识别

#### FR-010 结算页检测

见第 7 节。检测到稳定结算页后只触发一次本地内存截图和 30 秒购买意图等待，不直接调用 AI。

#### FR-011 截图与上传

- 使用 `AccessibilityService.takeScreenshot()`，不使用 MediaProjection。
- Android 11+ 在无障碍服务已连接且 Manifest 声明 `android:canTakeScreenshot="true"` 时调用该 API；它不应触发 MediaProjection 的录屏授权弹窗，也不需要创建录屏通知。
- 截图回调返回 `HardwareBuffer` 后立即复制到受控的内存 `Bitmap`，复制成功后关闭 buffer；任何异常路径都必须释放 buffer、bitmap 和压缩字节数组。
- 截图仅存在于内存；结算页阶段先进入 `CheckoutSnapshotBuffer`，触发 AI 后立即取出并清空，或 30 秒到期自动释放。
- 截图来源包括自动检测和用户手动点击悬浮按钮两种，共用同一个 `CheckoutSnapshotBuffer`，按时间戳保留最新一张。
- 上传前裁剪结算区域并做本地脱敏。
- 禁止在密码、验证码、二维码、银行卡输入、人脸验证页面截图。
- 页面带 `FLAG_SECURE` 或系统拒绝截图时直接降级，不尝试绕过。
- 提交订单/支付应用调起前不上传；同一快照最多消费一次，AI 请求由购买意图触发器统一发起。

#### FR-011a 手动悬浮按钮截图

- 当支持的购物应用在前台时显示一个小型悬浮按钮，用户可拖动到不遮挡的位置。
- 显示条件：默认为购物应用前台就显示（方案 A，覆盖 WebView 盲区）；用户可在设置中切换为"仅结算页显示"或"关闭手动按钮"。
- 用户点击按钮时立即截图并写入 `CheckoutSnapshotBuffer`，与自动截图共用同一 buffer，按时间戳保留最新。
- 手动截图后，同一页面指纹内的自动检测不再重复截图，避免用户刚点完又自动截一张。
- 按钮状态：截图前可点击；截图后变灰提示"已捕获，等待支付"；30 秒过期未支付则恢复可点击并提示"已过期，可重新截图"；购买意图触发上传后隐藏；AI 完成/支付完成后隐藏；离开购物应用后隐藏；敏感页面时隐藏。
- 需要 `SYSTEM_ALERT_WINDOW` 权限，首次使用时引导用户授权。

#### FR-012 截图生命周期

```text
HardwareBuffer
  -> Bitmap
  -> 裁剪结算区域
  -> 本地脱敏（遮挡疑似敏感字段）
  -> 内存压缩（JPEG/WebP）
  -> CheckoutSnapshotBuffer（最多 30 秒）
  -> [提交/支付调起] 取出并上传 AI，或 [超时/返回] 丢弃
  -> 关闭 HardwareBuffer
  -> 回收 Bitmap、字节数组和快照引用
```

禁止：

- 写入相册、数据库、缓存目录。
- 写入日志和崩溃报告。
- 为网络重试长期保存图片。
- 把 Base64 图片写入调试信息。

网络失败时默认丢弃图片；若仍有有效购买意图且未达到重试上限，可以重新捕获当前安全页面，不得把旧图片写入磁盘等待重试。

#### FR-013 AI 调用

- API Key 由用户在设置中填写，使用 Android Keystore 加密保存。
- 支持用户配置 API 地址和模型。
- 首发支持三家服务商，均需验证是否兼容 OpenAI Vision 格式（`image_url` + Base64 data URL）：
  - **通义千问 qwen-vl-plus**（主用，质量优先，阶段一已验证）。
  - **智谱 GLM-4V-Flash**（备用，速度/成本优先，主用故障或预算紧张时切换；阶段一已验证）。
  - **硅基流动 Qwen3-VL-8B-Instruct**（仅开发调试，不作为生产识别模型；阶段一已验证）。
  - ⚠️ 注意：智谱 `glm-4.7-flash` 为纯文本模型，不支持图片输入；硅基流动 `Qwen3.5-4B` 同样为纯文本模型，均不可用于视觉识别。
- 服务商适配器需支持 `AiProviderAdapter` 接口，按服务商差异转换请求/响应格式。
- `AiProviderConfig` 必须包含 `supportsVision` 字段，防止用户配置不支持视觉的模型。
- 设置请求超时（默认 15 秒）和响应大小上限（默认 1 MB）。
- 禁止记录请求图片、完整响应和 API Key。
- 要求服务商返回固定 JSON Schema，本地校验。
- 设置页提供 AI 连通性测试按钮，用于验证模型名称和 API 地址是否有效。

#### FR-014 敏感页面检测

出现以下内容时禁止截图上传：

- 支付密码输入框。
- 短信验证码输入框。
- 银行卡完整卡号。
- 指纹或人脸验证提示。
- 二维码或条形码图片。
- 登录凭证输入。

脱敏算法（本地，非绝对安全保证）：

- 正则匹配疑似银行卡号（连续 13-19 位数字）并遮挡。
- 正则匹配疑似验证码（4-8 位数字，附近有"验证码"字样）并遮挡。
- 检测页面文本包含"密码""验证码""指纹""人脸"等关键词时放弃截图。
- 二维码检测使用纯文本关键词判断：页面文本包含"二维码""扫码""条形码"等关键词时放弃截图（保守策略，可能漏检视觉二维码但更安全）。

#### FR-015 页面角色和 AI 请求幂等

- 结算检测必须输出 `PageRole`，`PRODUCT_DETAIL`、`CART`、`BROWSING`、`PAYMENT_HANDOFF` 和 `PAYMENT_RESULT` 默认不得上传 AI。
- 商品详情页出现价格、“立即购买”或“加入购物车”时，不得创建 `CheckoutRequest`。
- 只有包名已启用、页面角色为 `CHECKOUT`、存在可解析的最终金额、存在结算语义且页面稳定 300~800ms 时，才允许捕获 `CheckoutSnapshot`；此时不联网。
- 只有 `SUBMIT_ACTION`、`PAYMENT_APP_HANDOFF`、允许的支付通知回退或用户明确重试出现时，才允许把未过期快照转换为 AI 请求。
- 同一 `checkoutRequestKey` 在 `IN_FLIGHT`、`SUCCEEDED` 或 `FAILED_FINAL` 时，重复无障碍事件不得发出新请求。
- 每个支付会话默认最多两次 AI 请求；本地截图次数不计入 AI 配额，但每个快照仍必须限时释放。
- AI 触发前不产生网络流量和 token 消耗；全局请求间隔、退避、超时和取消必须可配置并写入运行状态。
- 订单号/金额/结算页实质变化会创建新的 `candidateRevision`；倒计时、广告、滚动和重复事件不创建新版本。
- 购物平台跳转到微信/支付宝时，先在交接事件中消费一次有效快照，再结束购物页面观察；支付结果变化不得触发购物截图或新的 AI 请求。
- AI 返回晚于支付通知时，结果只能回填原 `sessionId`；如果会话已被用户拒绝或过期，不得新建孤立账单。

### 11.3 支付成功确认

#### FR-020 通知识别

- 监听微信、支付宝支付通知。
- 提取金额、商户、支付时间、订单号。
- 通知是支付成功的可信来源之一。
- 同一通知多次回调只产生一个来源事件（幂等）。

#### FR-021 支付结果页识别

- 无障碍服务识别微信/支付宝支付结果页文本节点。
- 提取金额、商户、付款方式、订单号、支付状态。
- 节点不可读时（如 WebView/Canvas 渲染），依赖支付通知获取金额和状态，商户字段可能缺失并进入待确认。
- 本项目不使用本地 OCR，支付结果页金额以无障碍节点或通知为准。

#### FR-022 会话匹配

见第 9 节。AI 结果与支付结果匹配后入账或进入待确认。

#### FR-023 自动入账和延迟弹窗

自动入账必须同时满足：

```text
支付状态 = SUCCESS
+ 支付最终金额明确且为正数
+ 支付来源包名已启用
+ 会话未过期且未被其他候选占用
+ 金额与结算候选一致，或没有结算候选
+ 必要字段达到用户选择的置信度策略
+ `LedgerTransaction` 幂等检查通过
```

处理顺序固定为：

```text
支付证据先到、AI 仍在 ARMED/IN_FLIGHT
  -> 若有未过期快照，允许一次 PAYMENT_NOTIFICATION_FALLBACK 触发
  -> 写入 PAYMENT_CONFIRMED，不弹窗
  -> 进入 WAITING_ENRICHMENT，等待 5~15 秒
  -> AI 返回且字段完整：自动入账
  -> AI 超时/字段缺失/金额冲突：发送 onReviewRequired
```

AI 已返回但支付证据尚未到时，只保存 `WAITING_PAYMENT` 候选，不显示“已支付”或创建正式账单。支付失败、取消、回退到购物软件或会话过期时，不得自动入账；用户可以在待确认中心手动建立账单，但该操作必须标记为 `MANUAL`。

弹窗只能由原生决策层发送一次。RN 重连、通知重复、AI 晚到或用户返回页面不得再次弹出同一 `candidateId`；用户选择“稍后处理”后，系统只在待确认中心保留记录。

### 11.4 AI 对话与语音记账（保留 LiteNote）

#### FR-030 AI 对话记账

- 保留 LiteNote 的 AI 对话界面和多模型适配器。
- 改为手机直连外部 AI API（去后端）。
- 支持 Claude/OpenAI/DeepSeek/Qwen 等模型。
- 保留 SSE 流式响应和 Tool Calling。
- MVP 阶段 AI 只可调用本地查询和统计工具,不允许创建、修改、删除账单或管理分类；写入操作由用户在界面中确认完成。
- 工具调用通过 Native Module 桥接到本地数据层。

#### FR-031 语音记账

- 保留 LiteNote 的语音录音交互。
- 录音上传 OpenAI Whisper 兼容协议 ASR。
- 转写文本后由 AI 解析为账单字段。
- 用户确认后入账。

#### FR-032 拍照记账

- 保留 LiteNote 的拍照/选图交互。
- 图片上传多模态 AI 识别小票/发票。
- 识别金额、日期、商户、分类。
- 用户确认后入账。

### 11.5 账单导入

#### FR-040 格式支持

- 微信官方账单 CSV 或官方压缩包。
- 支付宝官方账单 CSV、XLSX 或官方压缩包。
- UTF-8、UTF-8 BOM 和 GBK 编码。
- 表头名称、列顺序和说明行变化的不同版本。

#### FR-041 加密压缩包

- 密码只在当前导入过程中使用。
- 密码不得写入数据库、日志、剪贴板。
- 导入结束后从内存移除。
- 解压文件存放于应用私有临时目录，导入结束或取消后清理。

#### FR-042 导入预览

正式写入前展示：

- 来源和识别出的账单格式版本。
- 交易时间范围。
- 总记录数和有效记录数。
- 预计新增、合并、跳过、待确认和失败数量。
- 收入、支出、退款、转账等类型分布。

#### FR-043 导入执行与撤销

- 大文件采用流式或分批解析。
- 导入过程支持进度展示和安全取消。
- 导入以数据库事务执行。
- 保存文件内容哈希和导入器版本，避免重复导入。
- 用户可整批撤销最近导入。

### 11.6 个人规则引擎

#### FR-050 规则类型

规则可基于：

- 来源应用。
- 原始商户或标准商户。
- 文本关键词。
- 金额范围。
- 资金账户。
- 平台。
- 交易类型。

规则可输出：平台、标准商户、分类、账户、忽略、进入待确认。

**规则执行时机**：规则在两个阶段执行——候选生成时（为 AI 候选或通知识别补充字段）和入账前（最终校验用户确认前的字段）。两次执行都记录命中信息。

**规则作用于纯通知路径**：规则引擎不仅作用于 AI 候选，也作用于纯通知识别路径（如定期续费、扫码支付无结算页场景）。通知识别返回的商户文本可由规则匹配并补全平台和分类。

#### FR-051 规则创建

用户修正记录后可选择：

- 仅修改当前记录。
- 对未来相似交易生效。
- 同时预览并批量修改历史交易。

#### FR-052 规则冲突

规则具备优先级、启用状态、创建来源和命中次数。发生冲突时展示将要应用的规则和原因，不静默选择。

### 11.7 去重、合并与关联

#### FR-060 去重顺序

1. 来源订单号或交易号精确匹配。
2. 同一来源事件标识精确匹配。
3. 导入文件记录标识精确匹配。
4. 金额、币种、时间窗口、账户和商户相似度综合匹配。

#### FR-061 退款关联

- 优先按原交易号关联。
- 没有交易号时使用金额、商户、平台和时间范围生成候选。
- 支持一笔交易对应多次部分退款。
- 退款总额超过原交易实付金额时提示异常。
- 净支出统计正确扣减退款，保留原始支出和退款明细。
- 退款通知延迟到达且原交易已过期或已删除时，创建独立退款记录并提示用户手动关联。
- 退款接收账户可能与原支付账户不同（如微信支付原交易退款到支付宝），以退款通知为准，不影响原交易关联。

#### FR-062 转账与还款

- 自有账户之间的转账不计入收入和支出。
- 信用卡、花呗等负债还款不重复计入支出。
- 还款产生的手续费单独记录为支出。
- 无法判断对方账户是否属于用户时进入待确认。

### 11.8 待确认中心

#### FR-070 待确认操作

用户可以：

- 确认系统建议。
- 修改交易类型、金额、账户、平台、商户和分类。
- 将记录与已有账目合并。
- 将误合并记录拆开。
- 忽略当前记录。
- 建立适用于未来或历史记录的个人规则。
- 选择"不计收支"类型（适用于押金、余额校准等不应进入收支统计的记录）。

待确认列表支持单笔处理和批量处理，并明确显示需要确认的原因。

### 11.9 手动记账

#### FR-080 新增和编辑

至少支持：交易类型、金额和币种、时间、资金账户、支付入口、平台、商户、分类、备注和标签。常用字段根据历史选择提供建议，但不改变用户已输入内容。

### 11.10 账目浏览、搜索与统计

#### FR-090 账单列表

- 默认按交易发生时间分组。
- 区分正式账目、待确认、已退款和已合并状态。
- 支持按类型、分类、账户、支付入口、平台、商户、来源和时间筛选。
- 支持商户、平台、备注和金额搜索。

#### FR-091 统计

- 月度收入、支出和净结余。
- 分类支出占比和趋势。
- 消费平台排行。
- 支付入口分布。
- 资金账户支出分布。
- 净资产视图（总资产、总负债、净资产及趋势）。
- 退款前金额和退款后净支出的清晰区分。

统计只使用正式账目，待确认记录单独提示。

#### FR-091a 预算超支提醒

- 预算达到 80% 时发送本地通知提醒用户。
- 预算达到 100% 时再次发送通知提醒。
- 提醒频率：每个阈值每个预算周期最多提醒一次。
- 用户可在设置中关闭预算提醒。

### 11.11 备份、恢复与导出

#### FR-100 完整备份

至少包含：账本和交易、交易来源与关联关系、账户、平台、商户和分类、个人规则和商户别名、应用必要配置。

备份文件带版本号、校验信息和可选加密。

#### FR-101 恢复

- 恢复前验证文件完整性和版本兼容性。
- 展示将恢复的数据范围。
- 明确选择覆盖或合并策略。
- 恢复失败时保持原数据库不变。
- 备份文件默认不加密（设备内数据库已加密，导出后用户自行负责），用户可选加密备份。
- 备份包含账单和配置，但不包含 API Key（Keystore 安全限制，密钥不可跨设备迁移）；恢复后用户需重新输入 AI 服务商密钥。
- 通知格式变化风险声明：支付应用版本更新可能导致通知解析失败，用户可通过账单导入补全。

#### FR-102 导出

提供 CSV 导出供用户在电子表格或其他记账工具中使用。

---

## 12. AI 成本控制与故障转移

### 12.1 成本控制

#### FR-110 调用频率限制

- 每日 AI 视觉识别调用上限（默认 50 次，用户可配置）。
- 同一会话不重复调用。
- 页面稳定后才截图，避免频繁刷新导致多次调用。

#### FR-111 月度成本预算

- 用户设置月度 AI 成本预算（默认 10 元）。
- 实时累计 token 消耗，按服务商定价估算成本。
- 接近预算 80% 时提醒，达到预算时暂停 AI 视觉识别（不影响通知识别和手动记账）。
- 设置页展示本月调用次数、token 消耗和预估成本。

#### FR-112 成本记录

- `AiCostRecord` 记录每次 AI 调用的时间、服务商、模型、token 数、预估成本。
- 支持按月汇总查看历史成本趋势。

### 12.2 故障转移与降级

#### FR-113 服务商故障转移

- 支持配置多个 AI 服务商（主用 + 备用）。
- 默认主备策略：通义千问 qwen-vl-plus 主用 / 智谱 GLM-4V-Flash 备用 / 硅基流动 Qwen3-VL-8B-Instruct 仅开发调试。
- 主用服务商请求失败（超时、5xx、网络错误）时自动切换备用。
- 切换失败后进入待确认，不阻塞支付流程。
- 用户配置的模型被服务商下线或重命名时，返回 `AI_AUTH` 错误码，提示用户检查模型名称和 API 地址，并在设置页提供连通性测试按钮。

#### FR-114 离线降级

- 无网络时 AI 视觉识别完全失效，降级策略：
  - 仍检测结算页，但不截图，仅记录包名和页面文本作为来源。
  - 等待支付通知/结果页识别金额和商户。
  - 平台字段从前台应用轨迹推断或留空。
  - 进入待确认，由用户补充。
- 网络恢复后不自动补截图（避免延迟和重复）。

#### FR-115 AI 延迟处理

- AI 请求耗时超过阈值（默认 10 秒）时：
  - 不阻塞支付流程，用户可正常完成支付。
  - AI 结果延迟到达后回填会话，若会话已通过通知确认且金额匹配则合并。
  - 若会话已超时关闭，AI 结果丢弃，不创建孤立记录。

#### FR-116 AI 识别错误反馈

- 用户修正 AI 结果后，记录修正前后的字段差异。
- 修正数据仅保存在本地，不自动反馈给服务商（避免隐私泄露）。
- 用户可在设置中手动查看 AI 识别准确率统计。

### 12.3 MVP 默认时序和限额

所有时序集中定义为配置常量，页面和服务不得各自写一套：

| 配置项 | 默认值 | 作用 |
|---|---:|---|
| `checkoutStableWindowMs` | 500 ms（允许 300~800） | 结算页连续无实质变化后才截图 |
| `snapshotTtlMs` | 30000 ms | 本地快照等待提交/支付调起；到期释放 |
| `paymentHandoffWindowMs` | 8000 ms | 购物包名切换到微信/支付宝时允许消费快照 |
| `maxAiRequestsPerSession` | 2 | 首次购买意图触发 + 一次允许的变化/可恢复失败；不包含本地截图 |
| `minAiRequestIntervalMs` | 1500 ms | 全局 AI 网络请求节流 |
| `aiConnectTimeoutMs` | 5000 ms | 建立外部连接的最长时间 |
| `aiReadTimeoutMs` | 15000 ms | 等待响应的最长时间 |
| `paymentMatchWindowMs` | 5 分钟 | 结算页和支付证据的关联窗口 |
| `enrichmentWaitMs` | 15000 ms | 支付先到时等待 AI 补全 |
| `sessionIdleExpiryMs` | 5 分钟 | 页面离开或无观察后的会话过期时间 |
| `dailyVisionLimit` | 50 次 | 视觉识别每日上限 |
| `monthlyCostAlertPercent` | 80% | 达到预算时提醒 |

测试可以覆盖这些默认值，但业务代码不能在测试外偷偷放宽次数或时间窗口。

---

## 13. 失败回退

```text
购物页截图失败
  -> 继续监听支付通知，平台字段留空

AI 请求超时或返回非法 JSON
  -> 保留支付金额和支付来源
  -> 平台/商户为空或进入待确认

支付结果页不可读
  -> 等待通知

支付金额与结算金额不一致
  -> 不自动入账，进入待确认

截图遇到 FLAG_SECURE
  -> 放弃截图，不影响通知识别

直接扫码支付，没有购物软件结算页
  -> 识别微信/支付宝中的商户和金额
  -> 平台字段留空或标记"线下扫码"

无网络
  -> AI 视觉识别失效，降级到通知+轨迹+待确认

AI 服务商故障
  -> 自动切换备用服务商
  -> 全部失败则进入待确认

会话超时
  -> 关闭会话，不创建孤立记录
  -> 若后续收到匹配通知，创建新会话或直接入账
```

### 13.1 统一错误码

错误码写入 `CheckoutRequest.errorCode`、`AuditEntry.reasonCodes` 或运行健康状态；对用户显示可读文案，对日志只保留错误码和阶段。

| 错误码 | 含义 | 是否阻塞支付 | 默认处理 |
|---|---|---:|---|
| `PAGE_NOT_CHECKOUT` | 当前页面是浏览/详情/购物车 | 否 | 不创建请求 |
| `PAGE_UNSTABLE` | 页面在稳定窗口内持续变化 | 否 | 延迟观察，不上传 |
| `PURCHASE_INTENT_MISSING` | 结算页未出现提交/支付调起信号 | 否 | 只保留快照，30 秒后释放 |
| `SNAPSHOT_EXPIRED` | 内存快照超过 30 秒 | 否 | 丢弃图片，不上传旧快照 |
| `AI_TRIGGER_DUPLICATE` | 同一快照已被购买意图消费 | 否 | 幂等返回原请求 |
| `SCREENSHOT_PERMISSION` | 无障碍截图能力未授权 | 否 | 继续通知/结果页路径 |
| `SCREENSHOT_SECURE` | 页面设置 `FLAG_SECURE` | 否 | 放弃截图，保留会话观察 |
| `SCREENSHOT_EMPTY` | 返回空 buffer、黑屏或尺寸无效 | 否 | 本会话最多一次重试 |
| `SENSITIVE_PAGE` | 命中密码、验证码、二维码等 | 否 | 禁止上传并进入待确认 |
| `AI_RATE_LIMIT` | 服务商 429 或本地调用上限 | 否 | 本会话不再重试 |
| `AI_TIMEOUT` | 连接/读取/总超时 | 否 | 退避一次，仍失败则待确认 |
| `AI_AUTH` | API Key 或 Base URL 无效 | 否 | 提示配置错误 |
| `AI_SCHEMA` | 响应非法、字段缺失或越界 | 否 | 丢弃结果 |
| `PAYMENT_STATUS_UNKNOWN` | 成功状态不明确 | 否 | 不入账，待确认 |
| `AMOUNT_CONFLICT` | 结算候选和支付金额冲突 | 否 | 禁止自动入账 |
| `MATCH_AMBIGUOUS` | 多个会话可匹配 | 否 | 显示候选供用户选择 |
| `DUPLICATE_SOURCE` | 来源事件已处理 | 否 | 幂等返回原结果 |
| `SESSION_EXPIRED` | 等待窗口结束 | 否 | 关闭会话，保留最小审计 |
| `DATABASE_WRITE` | 本地事务写入失败 | 否 | 重试事务，不重复提交 |

---

## 14. 截图与隐私

### 14.1 本地不持久化截图

应用本地不保存截图文件。截图生命周期见 FR-012。

### 14.2 AI 上传前脱敏

见 FR-014。脱敏算法是本地预处理，不能当作绝对安全保证。

### 14.3 AI 服务商数据保留

应用只能保证本地不持久化，无法保证 AI 服务商不保留请求。应选择支持零数据保留或明确数据保留策略的服务商，并在设置页提示用户查阅服务商隐私条款。

### 14.4 隐私合规验证

- 自动化测试：截图脱敏后的图片不包含银行卡号、验证码、密码等敏感字段。
- 自动化测试：日志和崩溃报告不包含截图、完整 AI 响应、API Key。
- 自动化测试：本地数据库不包含截图文件或 Base64 图片。
- 用户可在设置页查看 AI 服务商的数据处理协议摘要。

### 14.5 明确禁止

- 使用无障碍服务点击支付确认、填写字段或模拟支付操作。
- 绕过 `FLAG_SECURE`、系统权限拒绝或支付应用安全限制。
- 记录密码键盘、验证码、人脸验证或银行卡输入页面。
- 保存或传输支付密码、验证码和登录凭证。
- 未经用户确认上传原始账单。
- 将用户财务数据用于广告画像。

---

## 15. LiteNote 前端改造清单

### 15.1 数据层改造

LiteNote 的页面可以渐进迁移，但“无后端”是目标约束，不能把 NestJS 当作手机本地进程或临时账本层。NestJS 依赖 Node 运行时、服务端路由和 Prisma 数据库连接，不应嵌入 APK；迁移期间可以保留源码作为字段对照，但新应用运行时直接使用 Kotlin + Room。

#### 第一阶段：建立本地数据契约和兼容适配层

- 依据 LiteNote `Bill`、`Category`、`Account`、`Budget` 和 `FinancialGoal` 字段建立本地 DTO 与 Room Entity。
- 在 Native Module 中先实现与页面需要一致的 `getBills`、`getCategories`、`getStatistics` 和写入接口。
- 为每个原 React Query hook 建立本地 queryFn，保留 loading、error、invalidate 语义。
- 运行时关闭所有账本、分类、用户资料和统计的远程 API 请求。

#### 第二阶段：按页面替换数据访问

按屏幕优先级迁移：

1. 账单列表（dashboard/finance）→ `getBills()` Native Module。
2. 账单详情 → `getBillById(id)`。
3. 统计报表 → `getStatistics(month)`。
4. 分类管理 → `getCategories()` / `addCategory()` / `updateCategory()`。
5. 预算 → `getBudgets()` / `setBudget()`。
6. 财务目标 → `getGoals()` / `updateGoal()`。
7. 设置 → `getSettings()` / `setSetting()`。
8. 待确认 → `getPendingTransactions(filter?)` / `confirmPendingTransaction(id, input, version)` / `ignorePendingTransaction(id, version)`。
9. AI 对话 → 改为手机直连 AI API + 本地工具调用桥接。
10. 语音 → 保留录音交互，ASR 改为手机直连。
11. 拍照 → 保留相机交互，AI 识别改为手机直连。

#### 第三阶段：清理云端依赖

- 所有数据访问通过 Native Module。
- 不把 `litenote-backend/` 打包或作为设备端服务运行；源码可以作为协议和迁移参考保留在开发仓库之外。
- 删除账号认证相关页面和逻辑。
- 删除动态 JS 热更新相关代码。
- 删除 `BillApiService` 的 `/bills`、`/categories` 调用和 Token 同步。
- 保留网络权限只用于用户主动启用的外部 AI/ASR 请求，并在设置中显示最近请求状态。

### 15.2 新增页面

- **运行状态页**：权限状态、AI 服务商状态、最近识别时间、本月 AI 成本。
- **AI 识别状态页**：当前会话列表、会话详情、AI 识别中/失败/待确认状态。
- **待确认中心**：低置信度、冲突和疑似重复记录。
- **规则管理页**：个人规则、商户别名、命中记录。
- **导入向导页**：文件选择、格式识别、预览、执行和结果。
- **审计日志页**：自动操作和用户修改记录。
- **数据管理页**：备份、恢复、导出和清除数据。
- **AI 服务商配置页**：地址、模型、API Key、成本预算、数据保留说明。
- **账户管理页**：账户列表、余额展示、新增/编辑/停用账户、余额校准。
- **净资产视图页**：总资产、总负债、净资产、各账户余额、净资产趋势图。

### 15.3 移除页面

- 登录/注册页。
- 账号设置页。
- 任何依赖远程账号的页面。

### 15.4 保留页面

- 账单列表与详情（视觉不变，数据源改为本地）。
- 统计报表（视觉不变，数据源改为本地）。
- 分类管理（视觉不变）。
- 预算与财务目标（视觉不变，数据源改为本地）。
- AI 对话界面（视觉不变，调用层改为手机直连）。
- 语音记账（视觉不变）。
- 拍照记账（视觉不变）。
- 主题与设置（部分保留，移除账号相关）。

---

## 16. Native Module 接口设计

### 16.1 数据访问接口

```text
// 账单
getBills(filter?: BillFilter): Promise<PaginatedResponse<Bill>>
getBillById(id: string): Promise<Bill>
addBill(input: BillInput): Promise<Bill>
updateBill(id: string, input: BillUpdateInput): Promise<Bill>
deleteBill(id: string): Promise<void>
getBillStatistics(filter?: BillFilter): Promise<BillStatistics>

// 待确认
getPendingTransactions(filter?: BillFilter): Promise<PaginatedResponse<PendingTransaction>>
getPendingTransaction(id: string): Promise<PendingTransaction>
confirmPendingTransaction(id: string, input: BillInput, version: number): Promise<Bill>
ignorePendingTransaction(id: string, version: number): Promise<void>
mergePendingTransactions(ids: string[], target: BillInput): Promise<Bill>
splitPendingTransaction(id: string, splits: BillInput[]): Promise<Bill[]>

// 分类
getCategories(type?: "income" | "expense"): Promise<Category[]>
addCategory(input: CategoryInput): Promise<Category>
updateCategory(id: string, input: CategoryUpdateInput): Promise<Category>
deleteCategory(id: string): Promise<void>
initDefaultCategories(): Promise<void>

// 账户
getAccounts(): Promise<Account[]>
getAccountById(id: string): Promise<Account>
addAccount(input: AccountInput): Promise<Account>
updateAccount(id: string, input: AccountUpdateInput): Promise<Account>
deleteAccount(id: string): Promise<void>
calibrateAccountBalance(id: string, actualBalanceMinor: number, note?: string): Promise<BalanceCalibration>
getNetWorth(): Promise<NetWorthSummary>
getBalanceCalibrations(accountId: string): Promise<BalanceCalibration[]>

// 规则
getRules(): Promise<Rule[]>
addRule(input: RuleInput): Promise<Rule>
updateRule(id: string, input: Partial<RuleInput>): Promise<Rule>
deleteRule(id: string): Promise<void>
applyRulesToExisting(ruleId?: string): Promise<{ affected: number }>

// 识别状态
getRecognitionStatus(): Promise<RecognitionStatus>
getCheckoutSessions(filter?: { status?: SessionStatus[] }): Promise<CheckoutSession[]>
getCheckoutSession(sessionId: string): Promise<CheckoutSession>
getTransactionCandidate(candidateId: string): Promise<TransactionCandidate>
getPaymentEvidence(sessionId: string): Promise<PaymentEvidence[]>
retryAiRecognition(candidateId: string): Promise<void>

// AI 配置
getAiProviders(): Promise<AiProviderConfig[]>
addAiProvider(input: AiProviderInput): Promise<AiProviderConfig>
updateAiProvider(id: string, input: AiProviderUpdateInput): Promise<AiProviderConfig>
deleteAiProvider(id: string): Promise<void>
setDefaultAiProvider(id: string): Promise<AiProviderConfig>
testAiProvider(id: string): Promise<TestConnectionResult>
getAiBudget(): Promise<AiBudgetSummary>
setAiBudget(monthlyBudgetMinor: number): Promise<void>
getAiCostRecords(month?: string): Promise<AiCostRecord[]>

// 导入
inspectImport(filePath: string, password?: string): Promise<ImportInspection>
executeImport(filePath: string, password?: string): Promise<ImportResult>
undoImport(batchId: string): Promise<void>

// 备份
createBackup(options?: { encrypt?: boolean }): Promise<BackupSummary>
restoreBackup(filePath: string): Promise<RestorePreview>
executeRestore(filePath: string, strategy: "overwrite" | "merge"): Promise<ImportResult>
exportData(format: "csv" | "xlsx" | "json"): Promise<{ filePath: string }>
importData(filePath: string): Promise<ImportResult>

// 运行状态
getRuntimeHealth(): Promise<RuntimeHealth>

// 悬浮按钮
setManualCaptureButtonEnabled(enabled: boolean): Promise<void>
setManualCaptureButtonMode(mode: "always_on" | "checkout_only" | "off"): Promise<void>
```

### 16.2 事件通知接口

原生服务通过 Event Bridge 通知前端：

```text
onCheckoutDetected          // 检测到结算页
onAiRecognitionStarted      // AI 识别开始
onAiRecognitionCompleted    // AI 识别完成
onAiRecognitionFailed       // AI 识别失败
onPaymentConfirmed          // 支付已确认
onTransactionCreated        // 交易已创建
onReviewRequired            // 需要人工确认
onAiCostAlert               // AI 成本预警
onRuntimeHealthChanged      // 运行状态变化
```

事件 payload 必须包含稳定 ID 和当前状态，不能只发送一段展示文案：

```json
{
  "eventName": "onReviewRequired",
  "sessionId": "session-uuid",
  "candidateId": "candidate-uuid",
  "state": "REVIEW_REQUIRED",
  "reasonCodes": ["MERCHANT_MISSING", "AI_TIMEOUT"],
  "amountMinor": 9900,
  "occurredAt": "2026-07-31T12:34:56Z"
}
```

前端收到事件后只做缓存失效和页面刷新，不能在 JS 层再次创建账单。所有确认、编辑、忽略操作必须带 `candidateId` 和版本号，由原生层在事务中校验状态后执行。

### 16.3 识别与会话控制接口

识别相关公开方法统一见本节 16.1 和 `docs/types/Native.d.ts`；它们不负责替代原生服务内部的自动调度。后台服务内部可以继续实现权限开关、购物包名配置和通知样本解析，但这些不是 MVP 的 RN Native Module 公共契约。

`retryAiRecognition` 只能由用户明确操作触发，不能因为 RN 重渲染或页面重新挂载自动调用。后台服务重启时从 Room 恢复 `IN_FLIGHT` 请求为 `FAILED_RETRYABLE`，再按策略决定是否重试，不能重复使用一张已经释放的图片。

### 16.4 AI 工具调用接口（对话场景）

AI 对话中的 Tool Calling 通过 Native Module 桥接。MVP 只开放只读工具：

```text
ai_tool_query_bills(filter: BillFilter): Promise<Bill[]>
ai_tool_get_statistics(filter: BillFilter): Promise<BillStatistics>
```

---

## 17. 异常边界场景

系统设计与测试必须覆盖：

- 通知标题存在但正文为空。
- 同一通知多次更新或回调。
- 通知延迟到达或时间与交易时间不同。
- 支付应用只显示"支付成功"而不显示商户。
- 微信、支付宝和银行同时通知同一交易。
- 金额相同且短时间内连续消费两次（支付会话并发）。
- 跨午夜、跨时区或系统时间被修改。
- 全额退款和多次部分退款。
- 退款金额超过原交易金额。
- 信用卡消费后再次收到还款通知。
- 花呗分期、手续费和提前还款。
- 用户替他人代付或收到亲属卡付款。
- 红包、AA 收款、提现和转账。
- 账单文件包含说明行、空行、合计行或不同编码。
- 相同账单文件更名后再次导入。
- 导入过程中应用被杀死、设备断电或存储空间不足。
- 用户在导入后修改记录，再执行整批撤销。
- 数据库升级失败或备份文件损坏。
- 用户关闭权限后应用仍展示旧的"运行正常"状态。
- 消费平台跳转到浏览器、微信小程序或支付宝小程序后再付款。
- 用户在支付过程中切换到其他应用，前台轨迹出现干扰包名。
- 支付应用页面频繁刷新，无障碍服务重复收到相同节点。
- 无障碍节点为空、被混淆或页面启用 `FLAG_SECURE`。
- 短信延迟到达、发送方名称变化或同一银行多种模板并存。
- 验证码短信与交易短信来自同一发送方。
- AI 请求超时、返回非法 JSON、返回空字段。
- AI 返回金额与实际支付金额不一致。
- AI 服务商全部故障。
- 无网络环境下用户完成支付。
- 用户快速连续支付两笔同金额交易。
- 无障碍服务被系统杀后未自愈。
- 支付到一半关闭无障碍权限。
- AI 月度成本预算耗尽。
- 跨境/外币支付（首发不支持，但不应崩溃）。

---

## 18. 性能指标

### 18.1 性能要求

- 支持至少 10 万笔账目而不影响基础搜索和月度列表使用。
- 通知从接收到生成候选记录的目标时间小于 2 秒。
- AI 视觉识别从截图到返回结果的目标时间小于 8 秒（含网络）。
- 无障碍事件处理必须节流，不能因页面持续刷新造成明显卡顿。
- 1 万条受支持账单的导入目标时间小于 30 秒。
- 列表滚动不因金额、状态或异步图标加载发生明显跳动。

### 18.2 可靠性

- 通知、无障碍、短信和 AI 事件处理具有幂等性。
- 支付会话在应用被终止或超时后能够确定关闭。
- 导入和恢复操作具有事务性或明确的回滚能力。
- 应用崩溃后能够恢复未完成批次状态。
- 数据库迁移必须有自动化测试。
- 数据库迁移失败时自动回滚：迁移前自动备份数据库，迁移失败回退到旧版本数据库，并提示用户导出数据。
- 审计日志保留 90 天，超期自动清理，清理不影响交易数据。
- 不因解析单条异常记录导致整个导入批次失败。
- 无障碍服务被系统杀后能够自愈（通过前台通知或定时检查）。

### 18.3 MVP 验收量化指标

| 指标 | MVP 目标 |
|---|---:|
| 高置信度自动入账准确率 | 不低于 99% |
| 受支持格式金额提取准确率 | 不低于 99.5% |
| 受支持官方账单有效记录导入完整率 | 不低于 99.5% |
| 自动入账后的重复记录率 | 低于 1% |
| 普通待确认任务完成操作数 | 不超过 2 次主要操作 |
| AI 视觉识别结算页字段提取准确率 | 不低于 90%（平台/商户/金额） |
| 个人稳定版本无崩溃会话率 | 不低于 99% |
| 备份恢复数据一致性 | 100% 通过基准测试 |
| 商品详情页 AI 上传触发率 | 0 |
| A/B/C 放弃、D 提交场景的 AI 请求数 | 恰好 1 次 |
| 同一 `checkoutRequestKey` 的并发/越限重复上传数 | 0（允许策略内重试） |
| 同一支付产生的正式账单数 | 最多 1 笔 |
| 同一候选产生的确认弹窗数 | 最多 1 次 |

准确率指标必须同时公布测试样本范围。未支持或信息不足的通知应进入待确认，不计为"自动识别成功"。

### 18.4 交付阻断条件

出现以下任一情况不得作为稳定版本使用：

- 存在已知的金额精度错误。
- 还款或转账被普遍重复计算为支出。
- 导入取消、失败或撤销会删除无关数据。
- 备份文件无法可靠恢复。
- 未获得授权的通知被保存。
- 无关页面节点或普通应用轨迹被持久化。
- 日志或崩溃报告包含原始通知、短信、支付凭证、截图或完整账单。
- 任一关键权限关闭后仍误导用户认为对应识别来源运行正常。
- 无障碍服务影响支付页面输入、按钮点击或正常跳转。
- AI 上传未脱敏的敏感字段（密码、验证码、完整银行卡号）。

### 18.5 测试矩阵

| 层级 | 必测场景 | 通过条件 |
|---|---|---|
| 单元测试 | 页面角色分类、商品详情/购物车误触发、金额转分、文本归一化 | 商品详情和滚动事件不产生 AI 请求；只有稳定结算页产生内存快照 |
| 单元测试 | 指纹、请求键、并发状态和重试退避 | 相同键最多一个 `IN_FLIGHT`；上限和间隔生效 |
| 单元测试 | 通知关键词排除、金额解析、通知更新 | 收款/红包/转账不作为支出；更新不重复 |
| 单元测试 | AI JSON Schema、金额冲突、错误码映射 | 非法响应不写账；冲突进入待确认 |
| 单元测试 | 会话状态机和决策策略 | AI 先到、支付先到、AI 晚到均只能产生一笔结果 |
| Room 集成测试 | 崩溃恢复、事务回滚、唯一索引和候选提交 | 重启后状态可恢复；同一候选不能提交两次 |
| Bridge 集成测试 | Native Event payload、RN 重连和缓存失效 | 事件不导致 JS 重复创建账单 |
| AI 客户端测试 | 200/4xx/429/5xx、超时、断网、响应超限 | 图片不落盘；错误分类与重试策略正确 |
| 真机测试 | 商品详情连续点击、规格切换、优惠弹窗、购物车到结算 | 只有稳定结算页请求一次 |
| 真机测试 | A/B/C 进入结算后返回，D 提交订单 | 可有 4 次内存快照，但 AI HTTP 请求恰好 1 次且上传 D |
| 真机测试 | 京东/淘宝/美团/拼多多等结算页 | 结算页截图不出现录屏授权弹窗 |
| 真机测试 | 微信/支付宝静默通知、通知更新、无横幅通知 | 能收到并去重通知事件 |
| 真机测试 | 支付成功、取消、失败、密码页、`FLAG_SECURE` | 不点击、不输入、不上传敏感页；取消不入账 |
| 真机测试 | 两笔同额连续支付、支付先到/AI 后到 | 不串单、不重复弹窗、不重复入账 |
| 真机测试 | 手动悬浮按钮截图覆盖自动截图、手动后自动不重复、敏感页按钮隐藏 | 手动与自动共用 buffer，按钮状态正确 |
| 隐私测试 | 临时内存、日志、Crash、数据库和网络包 | 无截图文件、Base64、API Key 或完整敏感文本 |

---

## 19. 实施阶段

### 阶段一：AI 能力验证（最高优先级）

状态：已由项目负责人完成。本次开发不重复执行阶段一，直接从阶段二开始。

目标：手动触发截图，验证真实购物结算页能否稳定返回平台、商户和金额。

交付物：

- AI 服务商配置页（地址、模型、API Key、Keystore 加密）。
- 手动截图按钮（在设置页或调试页）。
- AI 请求与 JSON Schema 校验。
- 识别结果展示页。
- 成本记录基础能力。

验证通过后再投入自动检测。

### 阶段二：数据层本地化

目标：LiteNote 前端完全由本地数据驱动。

交付物：

- 建立 Room Entity、DAO、Repository 和数据库迁移脚本，不在设备端运行 NestJS。
- 按优先级迁移 LiteNote 各页面的数据调用为 Native Module。
- 移除账号认证。
- 保留 AI 对话/语音/拍照（改为手机直连）。

### 阶段三：自动结算页检测

目标：自动检测结算页并截图识别。

交付物：

- AccessibilityService 与结算页检测逻辑。
- 敏感页面检查与脱敏。
- `CheckoutSnapshotBuffer`、30 秒过期和新结算页覆盖逻辑。
- 提交订单/支付应用调起/支付通知回退触发器；进入结算页不直接联网。
- 截图生命周期管理。
- CheckoutSession 状态机。
- 结算页检测事件桥接到前端。

### 阶段四：支付结果匹配

目标：接入微信/支付宝通知、结果页识别和会话匹配。

交付物：

- NotificationListenerService。
- 支付结果页无障碍识别。
- AI 结果与支付结果匹配。
- 自动入账与待确认。

### 阶段五：完整业务语义

目标：补齐退款、转账、还款、手续费和规则引擎。

交付物：

- 退款识别与原交易关联。
- 转账方向与账户归属判断。
- 还款不重复计入支出。
- 手续费单独记录。
- 个人规则引擎。
- 字段锁定与数据优先级。
- 多级去重。

### 阶段六：账单导入

目标：支持微信/支付宝官方账单导入与对账。

交付物：

- 导入适配器（CSV/XLSX/加密压缩包）。
- 导入预览、执行和撤销。
- 跨来源核对与重复文件检测。

### 阶段七：稳定性与验收

目标：达到 MVP 验收标准并在主力设备长期稳定运行。

交付物：

- 自动化测试与真机验证报告。
- 性能、备份恢复和数据库升级测试。
- 已签名 APK 和签名密钥备份说明。
- 自发布 APK 升级机制：版本号管理、签名一致性、应用内检查更新。
- 数据库迁移回滚策略验证。
- 异常场景全覆盖验证。

---

## 20. 已确认决策与待确认事项

### 20.1 已确认决策

以下决策已在 V2 修订中落地：

| # | 决策项 | 确认值 |
|---|---|---|
| 1 | AI 服务商主备策略 | 通义千问 qwen-vl-plus 主用 / 智谱 GLM-4V-Flash 备用 / 硅基流动 Qwen3-VL-8B-Instruct 仅开发调试（阶段一真机验证通过 2026-08-04） |
| 2 | 本地 OCR | 去掉，降级靠通知+待确认 |
| 3 | 手动悬浮按钮 | 购物APP前台就显示 + 用户可配置，按时间戳保留最新，与自动检测共存 |
| 4 | 资产管理流派 | B 流水+余额（账户有期初/当前余额，账单自动增减，支持校准，净资产视图） |
| 5 | 信用卡专属字段 | 不需要（信用卡仅作为负债类型标签） |
| 6 | 商户字段 | 降级为场景相关字段，AI 不强制提取，缺失默认不阻塞入账 |
| 7 | 最低 Android 版本 | Android 13（API 33） |
| 8 | 数据库加密 | 默认 SQLCipher 加密，主密钥由 Keystore 派生 |
| 9 | 备份默认加密 | 默认不加密（设备内已加密，导出后用户自行负责），用户可选加密 |
| 10 | 测试样本采集策略 | 真机截图脱敏 + 合成数据两者结合 |
| 11 | 厂商兼容性首发范围 | AOSP/原生 + 小米 |
| 12 | 多账本/多用户 | 单账本（不支持多账本） |
| 13 | LiteNote 许可证 | MIT，可自由使用代码，需保留版权声明，建议新项目改名 |
| 14 | 规则引擎执行时机 | 候选生成时 + 入账前都执行 |
| 15 | 审计日志保留策略 | 90 天 |
| 16 | 多支付会话并发匹配 | 订单号优先 + 金额时间窗口 |
| 17 | 数据库迁移失败回滚 | 迁移前自动备份 + 失败回退到旧版本数据库 + 提示用户导出 |
| 18 | 应用升级机制 | 自发布 APK |
| 19 | 预算与财务目标 | 保留 LiteNote 预算和财务目标功能，与账户余额配合 |

### 20.2 仍需确认的事项

以下事项不阻塞本文档成立，但应在阶段一结束前确认：

1. AI 服务商定价与月度成本预算默认值（需查阅三家服务商实际定价）。
2. 可合法用于开发测试的微信、支付宝通知与账单样本（真机脱敏采集）。
3. CSV/XLSX/ZIP 解析依赖的最终选择和许可证。
4. 个人常用银行列表，是否启用短信读取。
5. 隐私政策与 AI 服务商数据处理协议摘要的内容。

### 20.3 许可证核实结论

LiteNote 仓库（https://github.com/GystuG/LiteNote）采用 **MIT 许可证**：

- 可自由使用、复制、修改、合并、发布、分发、再授权和销售。
- 不要求衍生项目开源。
- 必须在新项目代码中保留原始版权声明和许可声明。
- 建议在项目根目录放置 `THIRD_PARTY_LICENSES.md` 记录 LiteNote 原始版权声明。
- "LiteNote" 名称属于商标范畴，建议新项目改名避免争议。

---

## 21. 项目完成定义

新项目 MVP 只有在以下条件同时满足时才视为完成：

- 本文档中所有 MVP 功能通过验收。
- 交付阻断问题全部关闭。
- LiteNote 前端 18 屏全部由本地数据驱动，无远程 API 依赖。
- AI 视觉识别在真实购物结算页能稳定返回平台、商户和金额。
- 支付成功确认链路（通知 + 结果页）可用。
- 微信和支付宝支持范围有明确说明，未知格式不会被错误自动入账。
- 退款、转账、还款和手续费正确处理。
- 个人规则引擎真实作用于候选交易。
- 账单导入支持微信和支付宝官方格式。
- 备份、清库、恢复演练成功且数据一致。
- AI 成本可控，月度预算预警生效。
- 无障碍服务不影响支付应用正常操作。
- 截图脱敏通过自动化测试，敏感字段不上传。
- 权限、隐私、APK 签名备份和第三方依赖审查完成。
- 用户能够随时查看运行状态、处理待确认、撤销自动操作并导出自己的数据。

---

## 22. 结论

本项目以 LiteNote（MIT 许可证）为前端基础，融合 AI 支付识别方案，构建一款本地优先的个人自动记账应用。核心设计要点：

1. **AI 识别与支付确认职责分离**：AI 补全字段，通知/结果页确认成功。
2. **本地优先**：账本、规则、统计、备份均在本地，AI 仅作为可选增强来源。
3. **完整业务语义**：五维度区分、七种交易类型、退款转账还款手续费处理。
4. **AI 成本可控**：结算页只做内存截图，提交/支付意图出现后才上传 AI；再叠加频率限制、月度预算、故障转移和离线降级。
5. **隐私分级**：本地不持久化截图，上传前脱敏，用户知情选择服务商。
6. **渐进迁移**：LiteNote 前端分阶段从云端迁移到本地，降低风险。
7. **手动+自动双截图来源**：自动检测结算页截图与用户手动悬浮按钮截图共用 buffer，覆盖 WebView 盲区和用户信任问题。
8. **资产管理**：流水+余额模式，账户自动增减余额，支持手动校准和净资产视图。
9. **商户场景化**：商户字段降级为场景相关字段，电商场景以商品说明和分类为主。

相比 ZeroTap 路径，本项目保留 LiteNote 成熟前端，避免 Compose 复刻的视觉差异；相比原 LITENOTE_AI_PAYMENT_DESIGN.md，本文档补齐了完整业务语义、功能模块、AI 成本控制、故障转移、异常场景和验收标准。V2 修订进一步落地了 AI 服务商选型、去除本地 OCR、手动悬浮按钮、商户字段降级、资产管理流派 B 和 15 项用户场景补充。
