# 08. 阶段四任务拆分与执行基线

> 技术实现文档（TDD）第八部分。
>
> 关联文档：[阶段三至阶段四总览](../NEW_PROJECT_KICKOFF_V2.md)、[阶段三任务基线](07_phase3_tasks.md)、[Native Module 设计](03_native_module.md)、[公开 RN 契约](../types/Native.d.ts)。发生冲突时，以 `docs/types/Native.d.ts` 为唯一 RN 契约。
>
> 本文是开发、审查、证据和验收的分工基线。任务完成必须以分支、提交和交接物为单位记录，开发对话不得自行宣称真机样本或真机验收通过。

---

## 1. 文档使用规则

### 1.1 本阶段的执行对象

阶段四的工作对象是支付成功证据采集、AI 候选与支付证据匹配、自动入账决策和待确认交易流转。阶段三已经在购买意图后产出 `TransactionCandidate` 并把会话置为 `WAITING_ENRICHMENT`；阶段四负责把「AI 说买了什么」和「支付确实成功了」关联起来，满足匹配条件时自动写 `Bill` 并更新账户余额，不满足时进入待确认中心由用户处理。

阶段四任务必须基于当前代码边界补齐或修正。不得因为旧设计文档列出某个类，就重复新建同职责类。开发开始前必须先检查目标类是否已存在，并在交接物中列出实际修改的类和原因。

### 1.2 角色定义

| 角色 | 责任 | 不得替代的角色 |
|---|---|---|
| 原生开发 | 修改 Kotlin、Android 配置和本地持久化边界内的实现，提供静态交接物 | 不得代替证据角色宣称真机通过 |
| RN/契约开发 | 只使用现有 `Native.d.ts` 方法和事件，完成 RN 包装或页面接入 | 不得自行新增未声明 Native 方法 |
| 契约审查 | 检查状态、字段、方法名、事件名和阶段边界 | 不得把代码存在误判为功能验收通过 |
| 证据角色 | 采集和整理真机通知正文、支付结果页 dump、元数据、日志和证据索引 | 不得由开发对话兼任或用静态结果替代真机证据 |
| 验收角色 | 按独立矩阵执行真机验收，记录通过、失败、未执行和阻塞 | 不得只根据开发自述填写通过 |

### 1.3 分支和提交规则

1. 每个任务从已确认的阶段四基线创建独立分支，分支名使用 `codex/phase4-<任务简称>`；不得在 `master` 或控制工作树直接修改。
2. 一个任务的代码提交只包含该任务的允许文件。审查修正必须在同一任务分支追加中文提交，不得把别的任务或证据文件混入。
3. 提交信息使用中文并包含任务编号，例如：`阶段四任务3：接入支付通知监听与证据落库`。
4. 交接时必须写明：基线分支、基线提交、当前分支、提交哈希、修改文件、未修改但检查过的边界、未解决问题和下一角色。
5. 证据角色可以在专用证据分支提交样本或报告；验收角色必须引用证据提交哈希。开发提交不得包含真机样本，也不得把“待验证”写成“通过”。

---

## 2. 阶段四范围与阶段五边界

### 2.1 阶段四允许交付

- 接入 `NotificationListenerService`，监听微信、支付宝支付通知，解析金额、商户、订单号、支付状态和时间。
- 生成结构化 `PaymentEvidence`，按 `sourceEventKey` 幂等落库；重复通知只更新已有证据，不重复挂账。
- 在无障碍观察中识别支付结果页 `PAYMENT_RESULT`，读取支付成功/失败证据，不截图、不读密码/付款码。
- 将 `PaymentEvidence` 与阶段三 `TransactionCandidate` 在同一 `sessionId` 内匹配，按金额、订单号、时间窗口和支付应用综合判定。
- 满足匹配条件时自动写 `Bill` 并更新账户余额，会话进入 `COMPLETED`。
- 不满足匹配条件（金额冲突、订单不一致、并发会话歧义等）时进入待确认中心，不静默入账。
- 暴露当前 `Native.d.ts` 已声明的 `getPaymentEvidence()`、待确认查询和确认/忽略能力。
- 支持支付结果先到、AI 后到的回填路径：支付证据先落库，AI 返回后只更新原会话和候选版本，不创建第二笔账单。

### 2.2 阶段四明确不做

下列能力属于阶段五及以后，不能通过本阶段任务、“兼容代码”或验收脚本提前实现：

| 阶段五能力 | 阶段四处理方式 |
|---|---|
| 银行短信、邮件账单、CSV/Excel 导入解析 | 不接入；`sourceType` 保留 `sms`/`import` 枚举，但不实现解析和入账 |
| 跨设备同步、云端备份恢复 | 不实现；账本只在本设备 Room 中 |
| 规则引擎高级匹配（商户别名映射、分类自动推断） | 只保留 `appliedRules` 记录字段，不实现规则编辑 UI 和自动推断 |
| 多用户/多账本 | 不实现；当前只有单一本地账本 |
| 历史账单批量编辑、合并、拆分 | 不实现；待确认中心只支持单条确认/忽略 |

现有代码树中 `PaymentNotificationService` 等 LiteNote 旧实现只能视为既有边界或待重写源，不能以此为理由保留“收到通知立即弹窗”的旧行为。

### 2.3 现有实现边界

以下类在阶段四基线中已经存在，后续开发必须补齐、修正或复用，不得重复新建同职责类：

| 现有职责 | 当前类 |
|---|---|
| 支付证据实体（含 `sourceEventKey` 幂等索引） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PaymentEvidenceEntity.kt` |
| 支付证据 DAO（含 `insertIfAbsent`、按会话/订单/事件键查询） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/PaymentEvidenceDao.kt` |
| AI 候选与支付证据匹配（含金额/订单/时间规则） | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt` |
| 旧通知监听服务（LiteNote 实现，需重写） | `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt` |
| 旧支付悬浮窗管理器（需改为决策引擎驱动） | `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentOverlayManager.kt` |
| 本地账本仓储（含 `Bill` 写入和账户余额更新） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt` |
| 数据桥接模块（含 `confirmPendingTransaction`、`ignorePendingTransaction`） | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` |
| 识别桥接模块（含 `getPaymentEvidence`） | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/RecognitionModule.kt` |
| 支付通知回退信号（阶段三预留） | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/PaymentNotificationFallbackSignal` |

这里的“已存在”只表示代码边界，不表示已经通过构建、测试或真机验收。任何开发交接都必须区分“静态代码存在”“已审查”和“有独立运行证据”。

> 注意：`CheckoutPaymentMatcher` 当前使用了 `AI_PENDING`、`WAITING_PAYMENT`、`PAYMENT_CONFIRMED`、`NEEDS_CONFIRMATION`、`AUTO_COMMITTED` 等内部状态值，这些不在 [07_phase3_tasks.md](07_phase3_tasks.md) 第 3.2 节定义的 8 个公开 `SessionStatus` 内。阶段四必须在本任务 T2 中统一会话状态值，明确内部旅程状态与公开 `SessionStatus` 的映射，不得把内部状态直接序列化给 RN。

---

## 3. 唯一 RN 契约与状态定义

### 3.1 `Native.d.ts` 是唯一公开契约

阶段四只读并实现 [Native.d.ts](../types/Native.d.ts) 已声明的接口。`03_native_module.md` 和其他设计文档只能作为背景说明，不能用来发明公开方法、事件或状态值；若两者冲突，以 `Native.d.ts` 为准。本次文档修订不修改 `docs/types/Native.d.ts`。

阶段四允许使用的公开接口如下：

| 模块 | 只允许使用的现有方法/事件 |
|---|---|
| `Native.Data` | `getBills()`、`getBillById()`、`addBill()`、`updateBill()`、`deleteBill()`、`getBillStatistics()`、`getPendingTransactions()`、`getPendingTransaction()`、`confirmPendingTransaction()`、`ignorePendingTransaction()` |
| `Native.Recognition` | `getCheckoutSessions()`、`getCheckoutSession()`、`getTransactionCandidate()`、`getPaymentEvidence(sessionId)`、`retryAiRecognition(candidateId)`、`getRecognitionStatus()` |
| `Native.Runtime` | `getRuntimeHealth()`（含 `notificationEnabled`、`accessibilityEnabled`、`accessibilityServiceRunning`）、`openAccessibilitySettings()` |
| `NativeEventEmitter` | 只订阅已声明事件；阶段四可使用 `onAiRecognitionCompleted`、`onRuntimeHealthChanged`，不新增专用“支付成功”或“入账完成”事件 |

未声明的“支付成功事件”“自动入账事件”“通知到达事件”均不属于阶段四公开接口。需要刷新时只能在现有事件后重新调用查询接口，不得自行新增事件。

### 3.2 `SessionStatus` 仍严格为 8 个值

`SessionStatus` 继续原样使用 [Native.d.ts](../types/Native.d.ts) 中的 8 个值，阶段四不得增加、改名：

```text
OBSERVING
SNAPSHOT_BUFFERED
AI_REQUESTING
WAITING_ENRICHMENT
COMPLETED
FAILED
CANCELLED
EXPIRED
```

阶段四的会话语义补充：

- `WAITING_ENRICHMENT`：阶段三 AI 结果已落库；阶段四在此状态等待支付证据到达，或支付证据已到达但尚未完成匹配决策。
- `COMPLETED`：阶段四在满足匹配条件并成功写 `Bill` 后才会话置为此值；阶段三不得产生此状态。
- `FAILED`：阶段四中 AI 失败且无支付证据兜底，或匹配过程出现不可恢复错误时使用。

### 3.3 内部旅程状态与公开 `SessionStatus` 的映射

`CheckoutPaymentMatcher` 当前使用的内部旅程状态必须映射到上述 8 个公开值，不得直接序列化：

| 内部旅程状态 | 映射到的公开 `SessionStatus` | 说明 |
|---|---|---|
| `OBSERVING` | `OBSERVING` | 直接映射 |
| `SNAPSHOT_BUFFERED` | `SNAPSHOT_BUFFERED` | 直接映射 |
| `AI_PENDING` | `AI_REQUESTING` | 对应 `RequestStatus.ELIGIBLE` 或 `IN_FLIGHT` |
| `AI_COMPLETED` | `WAITING_ENRICHMENT` | AI 成功，等待支付证据 |
| `WAITING_PAYMENT` | `WAITING_ENRICHMENT` | AI 已成功但支付证据尚未到达，仍属等待补充 |
| `PAYMENT_CONFIRMED` | `WAITING_ENRICHMENT` | 支付证据已到达，尚未完成匹配决策 |
| `NEEDS_CONFIRMATION` | `WAITING_ENRICHMENT` | 匹配冲突，已进入待确认中心，对外仍是等待补充 |
| `AUTO_COMMITTED` | `COMPLETED` | 自动入账成功 |
| `FAILED`/`CANCELLED`/`EXPIRED` | 同名 | 直接映射 |

> 关键约束：`NEEDS_CONFIRMATION`、`WAITING_PAYMENT`、`PAYMENT_CONFIRMED` 等内部状态不得作为 `SessionStatus` 的第 9+ 个值导出。待确认交易通过独立的 `PendingTransaction` 查询接口暴露，不通过 `SessionStatus` 表达。

### 3.4 `RequestStatus` 在阶段四的延伸

阶段三的 `RequestStatus` 只描述 AI 请求生命周期。阶段四引入支付证据后，`RequestStatus` 的终态规则补充：

| `RequestStatus` | 阶段四补充语义 | 是否终态 |
|---|---|---:|
| `SUCCEEDED` | AI 成功；若支付证据已匹配并入账，会话进入 `COMPLETED` | 是 |
| `FAILED_FINAL` | AI 最终失败；若支付证据已到达，可走纯通知入账路径，否则进入待确认 | 是 |

纯通知入账路径（无 AI 候选）使用独立 `sourceType = notification` 的 `PendingTransaction`，不依赖 `RequestStatus`。

### 3.5 匹配规则与冲突处理

阶段四匹配必须按以下规则，不得用“同金额 + 五分钟内”作为唯一条件自动合并：

| 规则 | 匹配条件 | 冲突处理 |
|---|---|---|
| 订单号一致 | AI 候选 `aiOrderId` 与证据 `orderId` 完全相等 | 优先级最高，直接匹配 |
| 金额规则 | 整数分比较；AI 候选金额 ≥ 支付金额视为正常（优惠后下降） | AI 候选金额 < 支付金额（实付高于结算页）视为冲突，进入待确认 |
| 时间窗口 | 支付发生时间与 AI 候选时间差 ≤ 5 分钟 | 超出窗口视为冲突 |
| 支付应用 | 证据 `platform` 必须在微信/支付宝白名单 | 不在白名单视为冲突 |
| 并发会话 | 多个会话同时满足金额+时间 | 不强行合并，建立并发候选，由用户在待确认中心选择 |

跨午夜时间口径：交易时间以支付发生时间（`occurredAt`）为准，不以通知到达时间为准。

---

## 4. 任务总览

阶段四共 9 个任务：7 个开发/契约任务，2 个独立证据与验收任务。任务 T8、T9 不是开发任务，不能由开发角色自行勾选通过。

| 编号 | 任务 | 角色 | 依赖 | 类型 |
|---|---|---|---|---|
| T1 | 会话状态统一与旅程状态映射 | 原生开发 + 契约审查 | 阶段三基线 | 开发 |
| T2 | 支付通知监听重写与证据落库 | 原生开发 + 契约审查 | T1 | 开发 |
| T3 | 支付结果页证据识别 | 原生开发 + 契约审查 | T1 | 开发 |
| T4 | AI 候选与支付证据匹配决策 | 原生开发 + 契约审查 | T1、T2、T3 | 开发 |
| T5 | 自动入账与账户余额更新 | 原生开发 + 契约审查 | T4 | 开发 |
| T6 | 待确认交易流转与冲突处理 | 原生开发 + 契约审查 | T4、T5 | 开发 |
| T7 | 前端桥接与权限引导 | RN/契约开发 + 契约审查 | T2、T5、T6 | 开发 |
| T8 | 真机支付证据样本采集 | 证据角色 | T2、T3 | 证据 |
| T9 | 真机阶段四验收 | 验收角色 | T4、T5、T6、T7、T8 | 验收 |

任务 T8 和 T9 的结果必须单独记录为“通过/失败/未执行/阻塞”，不能用开发分支的代码提交替代。若没有对应证据提交，文档状态只能写“待验证”。

---

## 5. 任务卡

<a id="task-t1"></a>

### T1. 会话状态统一与旅程状态映射

**目标**：规范 `CheckoutPaymentMatcher` 和相关会话状态值，使内部旅程状态（`AI_PENDING`、`WAITING_PAYMENT`、`PAYMENT_CONFIRMED`、`NEEDS_CONFIRMATION`、`AUTO_COMMITTED` 等）严格映射到第 3.2 节的 8 个公开 `SessionStatus`，不新增第 9 个值，也不把内部状态直接序列化给 RN。

**角色与依赖**：原生开发 + 契约审查；依赖阶段三基线（`SessionStatus` 已固定为 8 值）；契约审查核对内部状态到公开状态的映射表。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutContract.kt`（旅程状态枚举与映射）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/CheckoutSessionEntity.kt`（若需补内部状态字段）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/BridgeDtoMapper.kt`（会话序列化映射）
- 与上述文件直接相关且已有的状态协调文件；若需要新增文件，必须在交接前说明原因并先由契约审查批准

**禁止触碰文件**：

- `docs/types/Native.d.ts`（本任务只读）
- 通知监听、支付结果页解析、`Bill` 写入和账户余额更新相关文件
- 任何 RN 页面

**必须遵守**：

- `CheckoutSessionEntity.status` 只存 8 个公开会话值；内部旅程状态使用独立字段或独立旅程表，并明确字段名、迁移和 DAO 读写。
- 映射按第 3.3 节执行；不允许 `status = NEEDS_CONFIRMATION` 或把 `WAITING_PAYMENT` 直接序列化给 RN。
- 待确认交易通过独立 `PendingTransaction` 查询接口暴露，不通过 `SessionStatus` 表达。
- 现有 `CheckoutPaymentMatcher` 的状态转换逻辑保留，但对外输出必须经过映射层。

**提交要求**：中文提交信息 `阶段四任务T1：统一会话状态与旅程映射`；只提交允许文件。

**交接产物**：内部状态枚举清单、映射表、字段名与迁移说明、序列化边界审查结果、未修改但检查过的边界。

<a id="task-t2"></a>

### T2. 支付通知监听重写与证据落库

**目标**：重写 `PaymentNotificationService`，从“收到通知立即弹窗”改为“解析→生成 `PaymentEvidence`→按 `sourceEventKey` 幂等落库→交给匹配决策”。监听微信、支付宝支付通知，提取金额、商户、订单号、支付状态和发生时间，排除收款、到账、转入、红包、AA、提现等非消费语义。

**角色与依赖**：原生开发 + 契约审查；依赖 T1；契约审查检查 `sourceEventKey` 幂等、非消费语义排除和证据字段完整性。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentOverlayManager.kt`（改为决策引擎驱动，信息完整时不弹窗）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationModule.kt`（权限状态查询）
- `jotbill-app/android/app/src/main/res/xml/` 下通知服务配置
- `jotbill-app/android/app/src/main/AndroidManifest.xml`（通知监听服务注册）
- 必要的同目录解析器或归一化辅助文件；新增文件须在交接物中说明

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- `CheckoutPaymentMatcher.kt` 的匹配决策逻辑（T4 处理）
- `LedgerRepository.kt`、`Bill` 写入和账户余额更新相关文件
- 阶段三的结算页检测、截图和 AI 请求文件

**必须遵守**：

- 只监听微信 `com.tencent.mm` 和支付宝 `com.eg.android.AlipayGphone`；其他包名不处理。
- `sourceEventKey` 优先使用来源包名 + 通知 key + 通知 ID + post time + 归一化金额的组合；通知更新时沿用同一 key 并更新候选，不创建新证据。
- 解析器必须排除收款、到账、转入、红包、AA、提现、提现到账等非消费语义，不能只看“支付/消费”关键词。
- 金额字段以 `Long amountMinor`（分）保存；十进制文本只在解析边界转换一次。
- 时间以支付发生时间（`occurredAt`）为准，不以通知到达时间（`postTime`）为准；跨午夜交易按发生时间归属。
- 不直接弹窗、不直接写 `Bill`、不直接更新账户余额；只写 `PaymentEvidence` 并通知匹配决策。
- 原文不落库，只存 `rawTextHash`。

**提交要求**：中文提交信息 `阶段四任务T2：重写支付通知监听与证据落库`；只提交允许文件。

**交接产物**：通知字段提取表、`sourceEventKey` 生成规则、非消费语义排除清单、金额解析正则、证据字段写入映射、权限状态查询接口、未做真机验证的明确声明。

<a id="task-t3"></a>

### T3. 支付结果页证据识别

**目标**：在无障碍观察中识别支付结果页 `PAYMENT_RESULT`，读取支付成功/失败证据，生成 `PaymentEvidence`（`sourceType = accessibility`）。不截图、不读密码、不读付款码、不读银行卡号。

**角色与依赖**：原生开发 + 契约审查；依赖 T1；契约审查核对页面角色判定、敏感字段脱敏和证据字段完整性。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPageDetector.kt`（`PAYMENT_RESULT` 判定）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt`（结果页观察分支）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutContract.kt`（结果页角色契约）
- 必要的同目录结果页解析器辅助文件；新增文件须在交接物中说明

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- `CheckoutPaymentMatcher.kt` 的匹配决策逻辑（T4 处理）
- 通知监听服务文件（T2 处理）
- `LedgerRepository.kt`、`Bill` 写入和账户余额更新相关文件
- 结算页截图、AI 请求文件（阶段三已完成）

**必须遵守**：

- `PAYMENT_RESULT` 判定基于“支付成功/支付失败/交易完成/已付款”等文本语义和支付应用包名，不基于单一张图或单一关键词。
- 只读取支付状态、金额、订单号、商户等结构化字段；密码、付款码、银行卡号、CVV 必须脱敏或不读。
- 不截图支付结果页；结果页观察只产生 `PaymentEvidence`，不进入 `CheckoutSnapshotBuffer`。
- 支付失败的结果不生成成功证据；只记录失败状态供诊断，不触发入账。
- `sourceEventKey` 按结果页来源包名 + 页面指纹 + 时间组合生成，幂等落库。

**提交要求**：中文提交信息 `阶段四任务T3：接入支付结果页证据识别`；只提交允许文件。

**交接产物**：`PAYMENT_RESULT` 判定规则、敏感字段脱敏表、证据字段写入映射、与 T2 通知证据的合并规则、未做真机验证的明确声明。

<a id="task-t4"></a>

### T4. AI 候选与支付证据匹配决策

**目标**：复用 `CheckoutPaymentMatcher`，将 `PaymentEvidence` 与阶段三 `TransactionCandidate` 在同一 `sessionId` 内匹配，按第 3.5 节规则判定。满足条件时输出“可自动入账”决策，不满足时输出“进入待确认”决策。不在此任务写 `Bill`，不更新账户余额。

**角色与依赖**：原生开发 + 契约审查；依赖 T1、T2、T3；契约审查核对匹配规则、冲突原因和并发会话处理。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/PaymentEvidenceDao.kt`（必要查询补充）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/TransactionCandidateDao.kt`（必要查询补充）
- 与匹配决策直接相关的内部契约和状态协调文件；扩大范围必须在交接物中说明

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- `LedgerRepository.kt`、`Bill` 写入和账户余额更新相关文件（T5 处理）
- `PendingTransaction` 写入相关文件（T6 处理）
- 通知监听服务、支付结果页解析、结算页检测和 AI 请求文件

**必须遵守**：

- 匹配必须按第 3.5 节规则：订单号一致优先级最高；金额规则、时间窗口、支付应用白名单综合判定。
- AI 候选金额 ≥ 支付金额视为正常（优惠后下降），不进入待确认；AI 候选金额 < 支付金额视为冲突，进入待确认。
- 并发会话无法区分时不强行合并，建立并发候选，由用户在待确认中心选择。
- 支付结果先到时先写 `PaymentEvidence`，AI 后到时只更新原会话和候选版本，不创建第二笔账单。
- 匹配决策只输出 `Accepted(matched=true/false, conflictReason?)`，不写 `Bill`，不更新账户余额。
- `matched=true` 的候选交给 T5 自动入账；`matched=false` 或 `conflictReason != null` 的候选交给 T6 进入待确认。

**提交要求**：中文提交信息 `阶段四任务T4：接通候选与支付证据匹配决策`；只提交允许文件。

**交接产物**：匹配规则逐项映射表、冲突原因清单、并发会话处理说明、支付先到/AI 先到两种时序的状态转换图、未做真机验证的明确声明。

<a id="task-t5"></a>

### T5. 自动入账与账户余额更新

**目标**：在 T4 输出“可自动入账”决策后，通过 `LedgerRepository` 写 `Bill` 并更新账户余额，把会话置为 `COMPLETED`。写库必须在同一事务边界，任一写入失败不得宣称成功。

**角色与依赖**：原生开发 + 契约审查；依赖 T4；契约审查核对事务边界、幂等和账户余额更新规则。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`（必要查询补充）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AccountDao.kt`（余额更新查询）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`（调用入账的衔接点）
- 与自动入账事务直接相关的内部协调文件；扩大范围必须在交接物中说明

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- `PendingTransaction` 写入相关文件（T6 处理）
- 通知监听服务、支付结果页解析、结算页检测和 AI 请求文件
- RN 页面

**必须遵守**：

- 自动入账只在 `matched=true` 且 `conflictReason == null` 时触发；冲突候选必须走 T6 待确认路径，不静默入账。
- 写 `Bill` 和更新账户余额必须在同一 Room 事务内；任一失败整体回滚，会话不进入 `COMPLETED`。
- 账户余额按交易类型增减：支出/手续费扣减，收入/退款增加，转账双向调整，还款/不计收支按账户类型规则。
- `Bill.sourceType` 必须为 `ai_vision`（AI 主链路）或 `notification`（纯通知回退，无 AI 候选）。
- 入账成功后会话置为 `COMPLETED`，候选置为已入账；入账失败会话保持 `WAITING_ENRICHMENT` 并记录错误。
- 同一 `sessionId` 只能入账一次；重复决策或重复证据不得产生第二笔 `Bill`。

**提交要求**：中文提交信息 `阶段四任务T5：接通自动入账与余额更新`；只提交允许文件。

**交接产物**：事务边界图、账户余额更新规则表、幂等证明、入账成功/失败状态转换、未做真机验证的明确声明。

<a id="task-t6"></a>

### T6. 待确认交易流转与冲突处理

**目标**：把 T4 中 `matched=false` 或 `conflictReason != null` 的候选、纯通知回退（无 AI 候选）的支付证据、以及 AI 失败但支付成功的证据转换为 `PendingTransaction`，供用户在待确认中心确认入账或忽略。

**角色与依赖**：原生开发 + 契约审查；依赖 T4、T5；契约审查核对 `PendingTransaction` 字段、版本号和确认/忽略接口。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt`（若需补字段）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/PendingTransactionDao.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（`confirmPendingTransaction` 写 `Bill`）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`（确认/忽略桥接）
- 与待确认流转直接相关的内部协调文件；扩大范围必须在交接物中说明

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- 通知监听服务、支付结果页解析、结算页检测和 AI 请求文件
- RN 页面（T7 处理）

**必须遵守**：

- 待确认交易必须包含金额、类型、发生时间、来源、冲突原因（若有）；缺失字段用候选或证据字段补充。
- 纯通知回退（无 AI 候选）的 `PendingTransaction.sourceType = notification`，`candidateId` 为空。
- 冲突候选的 `PendingTransaction.conflictReason` 必须填充，供前端展示。
- `confirmPendingTransaction(id, input, version)` 必须基于 `version` 做乐观锁；版本不一致拒绝确认。
- 确认入账走 T5 的 `LedgerRepository` 写 `Bill` 路径，复用事务边界和余额更新规则。
- 忽略后 `PendingTransaction` 标记为已忽略，不写 `Bill`，不更新余额，但保留记录供审计。
- 待确认交易不静默入账，不自动过期删除；过期策略由后续阶段处理。

**提交要求**：中文提交信息 `阶段四任务T6：接通待确认交易流转`；只提交允许文件。

**交接产物**：`PendingTransaction` 字段映射表、冲突原因清单、确认/忽略状态转换、版本号乐观锁说明、未做真机验证的明确声明。

<a id="task-t7"></a>

### T7. 前端桥接与权限引导

**目标**：复用阶段三的 `ReviewScreen` 和 `AccessibilityPermissionScreen`，接入 `getPaymentEvidence()` 查询，新增通知权限引导页（`NotificationPermissionScreen`），在待确认中心展示支付证据和冲突原因，支持手动确认入账、忽略和重试识别。

**角色与依赖**：RN/契约开发；依赖 T2、T5、T6；契约审查必须逐项对照 `Native.d.ts`。

**允许修改文件**：

- `jotbill-app/src/native/recognition.ts`（`getPaymentEvidence` 封装）
- `jotbill-app/src/native/data.ts`（待确认查询封装，若需补类型）
- `jotbill-app/src/native/runtime.ts`（`notificationEnabled` 读取）
- `jotbill-app/src/screens/review/ReviewScreen.tsx`（展示支付证据和冲突）
- `jotbill-app/src/screens/settings/NotificationPermissionScreen.tsx`（新增）
- `jotbill-app/src/navigation/AppNavigator.tsx`、`jotbill-app/src/screens/SettingsScreen.tsx`、`jotbill-app/src/types/navigation.ts`（导航接入）
- 与现有 Native 模块注册直接相关的 RN 文件；具体页面必须在分支开始时登记

**禁止触碰文件**：

- `docs/types/Native.d.ts`，除非另立“契约变更”任务并获得明确批准
- 通知监听服务、支付结果页解析、匹配决策、自动入账的 Kotlin 文件
- 未声明的“支付成功事件”“自动入账事件”或专用通知到达事件

**必须遵守**：

- 通知权限状态从 `Runtime.getRuntimeHealth().notificationEnabled` 读取；跳转使用 `Runtime.openAccessibilitySettings()`（通知权限引导复用系统设置跳转，不新增公开方法）。
- 支付证据查询使用 `Recognition.getPaymentEvidence(sessionId)`；待确认查询使用 `Data.getPendingTransactions()`。
- 确认入账使用 `Data.confirmPendingTransaction(id, input, version)`；忽略使用 `Data.ignorePendingTransaction(id, version)`；重试使用 `Recognition.retryAiRecognition(candidateId)`。
- 当前没有公开“支付成功”或“入账完成”事件；刷新只能在现有事件后重新调用查询接口。
- 待确认中心必须展示冲突原因（若有）和支付证据摘要，不展示原文。

**提交要求**：中文提交信息 `阶段四任务T7：接入前端桥接与权限引导`；只提交登记过的允许文件。

**交接产物**：Native 方法/事件逐项映射表、页面调用链、错误和未实现接口清单、与 `Native.d.ts` 的静态比对结果。

<a id="task-t8"></a>

### T8. 真机支付证据样本采集

**角色**：独立证据角色，不属于开发验收。开发者可以提供待采集场景清单，但不能在代码对话中宣称样本已通过。

**依赖**：T2、T3 的分支提交已被审查；需要指定设备、系统版本、应用版本（微信、支付宝）和可复现操作路径。

**允许修改/新增文件**：

- `docs/qa/payment-evidence-samples/` 下的阶段四样本
- 样本索引、包名/通知渠道/采集时间/预期字段元数据
- 证据角色自己的中文采集报告和证据提交

**禁止触碰文件**：

- 不修改 Kotlin、TypeScript、`Native.d.ts` 或任务文档来“适配”样本
- 不采集或纳入支付密码页、付款码、银行卡号、CVV 等敏感字段原文
- 不把模拟通知、旧设备通知或开发机截图标记为真机证据

**样本数量基线**：阶段四主样本固定为 **2 个支付应用 × 3 类证据 = 6 个样本**，每个应用各 1 条支付成功通知、1 条非消费通知（用于验证排除）、1 个支付结果页 dump：

| 应用 | 支付成功通知样本 | 非消费通知样本 | 支付结果页 dump |
|---|---|---|---|
| 微信 | `wechat_payment_success.json` | `wechat_non_consumption.json` | `wechat_payment_result.xml` |
| 支付宝 | `alipay_payment_success.json` | `alipay_non_consumption.json` | `alipay_payment_result.xml` |

`docs/qa/payment-evidence-samples/` 的验收基线只统计上述 6 个文件。额外诊断样本必须放在单独的补充目录或在索引中标为“非主样本”，不能再写成“6+”或把收款通知计入主样本。

**提交要求**：中文提交信息 `阶段四任务T8：提交真机支付证据样本`；提交只允许的样本、索引和证据报告，不得携带业务源代码或契约文件。

**交接产物**：6 个样本、样本索引、每个文件的包名和通知渠道/窗口类、采集设备信息、预期解析字段、缺失/脱敏说明、证据提交哈希。证据角色只能报告样本已采集或未采集，不能替开发者宣称匹配或入账通过。

<a id="task-t9"></a>

### T9. 真机阶段四验收

**角色**：独立验收角色。验收结果由证据和验收角色签署，开发对话不能自行勾选通过。

**依赖**：T4、T5、T6、T7 的审查提交和 T8 的 6 个主样本证据；需要明确设备、系统、微信/支付宝版本、AI 服务配置和网络条件。

**允许修改/新增文件**：

- `docs/qa/` 下的阶段四验收报告、测试矩阵和证据索引
- 验收角色的截图、UI dump、关键日志摘要和失败复现记录

**禁止触碰文件**：

- 不为通过验收修改业务源代码、契约或样本原文
- 不接入或验证银行短信、邮件账单、导入解析、云端同步等阶段五能力
- 不把开发分支的静态审查、构建结果或单元测试结果当作真机通过证据

**阶段四验收矩阵**：

| 场景 | 预期结果 | 必需证据 |
|---|---|---|
| 微信支付成功通知到达 | 生成 `PaymentEvidence`，不立即弹窗 | 证据 ID、`sourceEventKey`、字段映射 |
| 支付宝支付成功通知到达 | 生成 `PaymentEvidence`，不立即弹窗 | 证据 ID、`sourceEventKey`、字段映射 |
| 收款/到账/红包等非消费通知 | 不生成证据，不进入待确认 | 排除日志和证据计数 |
| 通知更新（同一支付重复回调） | 沿用同一 `sourceEventKey`，不重复挂账 | 证据计数和更新时间 |
| AI 候选先到、支付通知后到 | 匹配成功，自动入账，会话 `COMPLETED` | 候选 ID、证据 ID、`Bill` ID、账户余额变化 |
| 支付通知先到、AI 候选后到 | 证据先落库，AI 回填原会话，匹配后入账 | 证据先到时间、AI 回填时间、入账结果 |
| AI 候选金额 < 支付金额 | 进入待确认，不静默入账 | 冲突原因、待确认 ID |
| 订单号不一致 | 进入待确认，不静默入账 | 冲突原因、待确认 ID |
| 并发会话无法区分 | 建立并发候选，由用户选择 | 并发会话 ID、待确认列表 |
| 待确认中心确认入账 | 写 `Bill`，更新余额，待确认标记已确认 | `Bill` ID、余额变化、待确认状态 |
| 待确认中心忽略 | 不写 `Bill`，不更新余额，保留记录 | 待确认状态、审计记录 |
| 支付结果页识别（无障碍） | 生成 `PaymentEvidence`（`accessibility` 来源），不截图 | 证据 ID、页面角色、脱敏日志 |
| 支付失败结果页 | 不生成成功证据，不触发入账 | 失败状态记录 |

**验收报告必须逐项标记**：`通过`、`失败`、`未执行` 或 `阻塞`，并引用证据文件和提交哈希。未执行真机操作时，只能写“未执行”，不能写“通过”。

**提交要求**：中文提交信息 `阶段四任务T9：提交真机阶段四验收报告`；提交只允许的验收报告和证据索引，不得携带业务源代码或契约文件。

**交接产物**：按矩阵逐项标记的验收报告、设备/应用版本、证据文件清单、失败与阻塞说明、验收提交哈希和下一阶段交接事项。

---

## 6. 阶段四状态与交接示例

### 6.1 允许的主链路

```text
阶段三终点：WAITING_ENRICHMENT + SUCCEEDED（AI 候选已落库）
  -> 支付通知/结果页证据到达
  -> 证据按 sourceEventKey 幂等落库
  -> 匹配决策（同 sessionId 内）
  -> matched=true：自动入账 -> COMPLETED
  -> matched=false 或 conflictReason!=null：进入待确认中心
  -> 用户确认入账 -> COMPLETED
```

### 6.2 支付先到的回填路径

```text
支付通知先到（无 AI 候选）
  -> 证据落库，会话仍为 WAITING_ENRICHMENT
  -> AI 候选后到
  -> 回填原会话和候选版本（不创建第二笔）
  -> 匹配决策 -> 自动入账或待确认
```

### 6.3 不允许的捷径

- 收到支付通知就立即弹窗或立即写 `Bill`。
- 通知先到就跳过 AI 候选，用不完整字段静默入账。
- AI 候选金额与支付金额不一致就自动取一方入账，不进待确认。
- 同金额 + 五分钟内就自动合并两笔连续支付。
- 把 `NEEDS_CONFIRMATION`、`WAITING_PAYMENT` 等内部状态当作 `SessionStatus` 的第 9+ 个值导出给 RN。
- 为了 RN 刷新自行新增未声明的“支付成功”或“入账完成”事件。
- 用支付密码页、付款码、银行卡号原文作为证据字段。

---

## 7. 证据、审查和完成定义

### 7.1 开发提交完成条件

开发任务只有在以下材料齐全时才可交给审查：

- 中文提交信息、分支名、基线提交和提交哈希。
- 允许/禁止文件清单与实际 `git diff --name-only` 一致。
- 公开接口、事件、状态和阶段边界的逐项对照表。
- 匹配规则、冲突原因、事务边界、幂等和并发行为说明。
- 明确列出未运行的构建、测试和真机验证。

### 7.2 契约审查完成条件

契约审查必须至少检查：

- `SessionStatus` 仍只有 8 个现有值；内部旅程状态严格映射，不直接序列化。
- `Native.d.ts` 已声明的 `getPaymentEvidence`、待确认和确认/忽略接口被正确使用；未声明的事件和方法均标记为后续契约。
- `PaymentEvidence` 按 `sourceEventKey` 幂等落库；非消费语义被排除；敏感字段被脱敏。
- 匹配决策只输出 `Accepted(matched, conflictReason)`，不直接写 `Bill`；写 `Bill` 和余额更新只在 T5 事务边界内。
- 待确认交易不静默入账；确认走 T5 路径，忽略保留记录。
- 实际类复用当前实现边界，没有重复创建同职责类。

### 7.3 真机完成条件

真机完成只能由 T8/T9 角色根据独立证据签署。阶段四验收至少需要 6 个主样本证据、微信/支付宝的通知和结果页流程证据、AI-支付匹配和自动入账证据、冲突入待确认证据、确认/忽略流程证据。缺任何一项都必须标记为“部分完成”或“待验证”，不能从代码提交推断通过。

---

## 8. 风险与未解决问题

| 风险/问题 | 阶段四处理 | 责任角色 |
|---|---|---|
| 微信/支付宝通知正文格式随版本变化 | 用 T8 的真机样本调参；未知格式保持解析失败并记录，不强行入账 | 证据角色 + T2 开发 |
| 通知先于 AI 且 AI 长时间未返回 | 证据先落库，超时后转纯通知待确认，不丢弃支付 | T4/T6 |
| 并发会话无法区分 | 不强行合并，建立并发候选，由用户选择 | T4 |
| 支付结果页无障碍节点稀疏 | 不强行判定；无法识别时不生成证据，转通知路径 | T3 |
| `CheckoutPaymentMatcher` 现有内部状态与公开 `SessionStatus` 不一致 | T1 统一映射，不新增公开值 | T1 + 契约审查 |
| 当前 RN 没有“入账完成”事件 | 不自行扩展公开契约；记录为后续契约需求 | 契约审查 |
| `PaymentNotificationService` 旧实现直接弹窗 | T2 重写为解析→证据→决策，不保留弹窗行为 | T2 |
| 真机证据尚未采集 | 状态为“待验证”，由 T8/T9 独立推进 | 证据/验收角色 |

阶段四文档、代码审查或开发对话不得把阶段五能力包装为“回退”“兼容”“自动完成”。边界不清时，停止扩展并由契约审查创建后续阶段任务。
