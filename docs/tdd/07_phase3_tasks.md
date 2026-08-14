# 07. 阶段三任务拆分与执行基线

> 技术实现文档（TDD）第七部分。
>
> 关联文档：[阶段三至阶段四总览](../NEW_PROJECT_KICKOFF_V2.md)、[Native Module 设计](03_native_module.md)、[公开 RN 契约](../types/Native.d.ts)。发生冲突时，以 `docs/types/Native.d.ts` 为唯一 RN 契约。
>
> 本文是开发、审查、证据和验收的分工基线。任务完成必须以分支、提交和交接物为单位记录，开发对话不得自行宣称真机样本或真机验收通过。

---

## 1. 文档使用规则

### 1.1 本阶段的执行对象

阶段三的工作对象是结算页观察、内存截图、购买意图触发、AI 识别和交易候选落库。阶段三可以在购买意图出现后触发一次 AI 请求，并把经过校验的结果写入 `TransactionCandidate`；这不等于支付成功，也不等于正式记账。

阶段三任务必须基于当前代码边界补齐或修正。不得因为旧设计文档列出某个类，就重复新建同职责类。开发开始前必须先检查目标类是否已存在，并在交接物中列出实际修改的类和原因。

### 1.2 角色定义

| 角色 | 责任 | 不得替代的角色 |
|---|---|---|
| 原生开发 | 修改 Kotlin、Android 配置和本地持久化边界内的实现，提供静态交接物 | 不得代替证据角色宣称真机通过 |
| RN/契约开发 | 只使用现有 `Native.d.ts` 方法和事件，完成 RN 包装或页面接入 | 不得自行新增未声明 Native 方法 |
| 契约审查 | 检查状态、字段、方法名、事件名和阶段边界 | 不得把代码存在误判为功能验收通过 |
| 证据角色 | 采集和整理真机 dump、元数据、日志和证据索引 | 不得由开发对话兼任或用静态结果替代真机证据 |
| 验收角色 | 按独立矩阵执行真机验收，记录通过、失败、未执行和阻塞 | 不得只根据开发自述填写通过 |

### 1.3 分支和提交规则

1. 每个任务从已确认的阶段三基线创建独立分支，分支名使用 `codex/phase3-<任务简称>`；不得在 `master` 或控制工作树直接修改。
2. 一个任务的代码提交只包含该任务的允许文件。审查修正必须在同一任务分支追加中文提交，不得把别的任务或证据文件混入。
3. 提交信息使用中文并包含任务编号，例如：`阶段三任务3：修正结算页指纹去重`。
4. 交接时必须写明：基线分支、基线提交、当前分支、提交哈希、修改文件、未修改但检查过的边界、未解决问题和下一角色。
5. 证据角色可以在专用证据分支提交样本或报告；验收角色必须引用证据提交哈希。开发提交不得包含真机样本，也不得把“待验证”写成“通过”。

---

## 2. 阶段三范围与阶段四边界

### 2.1 阶段三允许交付

- 观察受支持购物应用的无障碍窗口，保守判定 `CHECKOUT`。
- 在结算页稳定后截图并写入 `CheckoutSnapshotBuffer`，快照只保留在内存，默认 30 秒过期。
- 在 `SUBMIT_ACTION`、`PAYMENT_APP_HANDOFF` 或 `PAYMENT_PAGE_VISIBLE` 等阶段三允许信号出现后，消费有效快照并发起 AI 请求。
- 用 `checkoutRequestKey` 做请求幂等和有限重试。
- 校验 AI 返回并写入 `transaction_candidates` 对应的 `TransactionCandidate`。
- 暴露当前 `Native.d.ts` 已声明的查询、重试、运行状态和手动截图能力。
- 为阶段四保留结构化通知回退入口，但只作为门禁明确的预留契约。

### 2.2 阶段三明确不做

下列能力属于阶段四，不能通过本阶段任务、“兼容代码”或验收脚本提前实现：

| 阶段四能力 | 阶段三处理方式 |
|---|---|
| `NotificationListenerService` 接入、通知正文解析和支付通知持久化 | 不接入、不扩展；通知回退只保留结构化入口和拒绝/不可用结果 |
| 支付结果页证据识别 | 不读取“支付成功/失败”作为支付证据，不采集结果页样本 |
| AI 结果与支付证据匹配 | 不实现金额、商户、订单号匹配，不填充候选的支付证据字段 |
| 正式账单和自动入账 | 不创建或更新 `Bill`，不把候选自动升级为正式账单 |
| 支付成功驱动的会话完成 | 阶段三 AI 成功后最多进入 `WAITING_ENRICHMENT`；`COMPLETED` 由后续阶段在满足支付证据契约后使用 |

现有代码树中若已有 `PaymentNotificationService` 或同进程回退调用，只能视为既有边界或预留信号源。本阶段不得以此为理由增加通知监听、支付结果解析、`PaymentEvidence` 生成、AI-支付匹配或入账逻辑。`PAYMENT_NOTIFICATION_FALLBACK` 在本文中始终标记为“阶段四预留”，不计入阶段三可验收的 AI 主链路。

### 2.3 现有实现边界

以下类在阶段三基线中已经存在，后续开发必须补齐、修正或复用，不得重复新建同职责类：

| 现有职责 | 当前类 |
|---|---|
| 无障碍服务生命周期、结算页观察和信号入口 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt` |
| 无障碍页面摘要收集 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/AccessibilityPageSummaryCollector.kt` |
| 结算页资格和页面稳定检测 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPageDetector.kt` |
| 页面角色、购买意图、请求键等内部契约 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutContract.kt` |
| 无障碍提交动作识别 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutSubmitActionDetector.kt` |
| 自动/手动截图生命周期 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutSnapshotCaptureCoordinator.kt` |
| 内存快照槽位、过期、消费和释放 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutSnapshotBuffer.kt` |
| 购买意图门禁、请求去重和快照所有权交接 | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPurchaseIntentCoordinator.kt` |
| 结算截图悬浮窗状态和 UI 管理 | `jotbill-app/android/app/src/main/java/com/jotbill/app/overlay/CheckoutCaptureOverlayManager.kt` |
| 悬浮窗权限、配置、手动截图和状态事件的 RN 模块 | `jotbill-app/android/app/src/main/java/com/jotbill/app/overlay/OverlayModule.kt` |
| AI 视觉请求客户端 | `jotbill-app/android/app/src/main/java/com/jotbill/app/ai/CheckoutAiVisionClient.kt` |

这里的“已存在”只表示代码边界，不表示已经通过构建、测试或真机验收。任何开发交接都必须区分“静态代码存在”“已审查”和“有独立运行证据”。

---

## 3. 唯一 RN 契约与状态定义

### 3.1 `Native.d.ts` 是唯一公开契约

阶段三只读并实现 [Native.d.ts](../types/Native.d.ts) 已声明的接口。`03_native_module.md` 和其他设计文档只能作为背景说明，不能用来发明公开方法、事件或状态值；若两者冲突，以 `Native.d.ts` 为准。本次文档修订不修改 `docs/types/Native.d.ts`。

阶段三允许使用的公开接口如下：

| 模块 | 只允许使用的现有方法/事件 |
|---|---|
| `Native.Runtime` | `getRuntimeHealth()`、`openAccessibilitySettings()`；权限状态从 `RuntimeHealth.accessibilityEnabled` 和 `accessibilityServiceRunning` 读取 |
| `Native.Overlay` | `setManualCaptureButtonEnabled()`、`setManualCaptureButtonMode()`、`getManualCaptureButtonMode()`、`getOverlayPermissionStatus()`、`requestOverlayPermission()`、`getManualCaptureButtonState()`、`requestManualCapture()` |
| `Native.Recognition` | `getCheckoutSessions()`、`getCheckoutSession()`、`getTransactionCandidate()`、`getPaymentEvidence()`、`retryAiRecognition(candidateId)`、`getRecognitionStatus()` |
| `NativeEventEmitter` | 只订阅 `RecognitionEvents` 已声明事件；阶段三可使用 `onCheckoutCaptureOverlayStateChanged`、`onCheckoutDetected`、`onSnapshotCaptured`、`onAiRecognitionCompleted` |

旧草稿中的未声明别名、显示/隐藏命令、独立取消命令和专用会话变化事件均不属于阶段三公开接口。任务正文、伪代码、验收步骤和交接物不得把这些旧别名当作阶段三接口：

| 旧接口类别 | 正确处理 |
|---|---|
| 权限状态检查旧别名 | 使用 `Runtime.getRuntimeHealth()`；需要跳转时使用 `Runtime.openAccessibilitySettings()` |
| 悬浮窗显示/隐藏和手动捕获旧别名 | 使用 `Overlay` 现有配置方法和 `Overlay.requestManualCapture()` |
| 识别重试旧别名 | 使用 `Recognition.retryAiRecognition(candidateId)` |
| 会话取消命令 | 当前无公开取消接口；取消只能是原生生命周期/内部状态转换，RN 取消功能列为后续契约，不在本阶段实现 |
| 会话按 ID 查询旧别名 | 使用 `Recognition.getCheckoutSession(sessionId)` |
| 专用会话变化事件 | 当前未声明；收到现有事件后重新调用查询接口，专用事件列为后续契约，不在本阶段实现 |

### 3.2 `SessionStatus` 严格为 8 个值

`SessionStatus` 必须原样使用 [Native.d.ts](../types/Native.d.ts) 中的 8 个值，不能增加、改名或把请求状态并入此类型：

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

阶段三的会话语义：

- `OBSERVING`：观察结算页但尚无有效快照。
- `SNAPSHOT_BUFFERED`：安全快照已进入内存，等待购买意图。
- `AI_REQUESTING`：购买意图已经通过门禁，AI 请求正在排队或执行。
- `WAITING_ENRICHMENT`：AI 结果已经校验并写入 `TransactionCandidate`，等待阶段四支付证据；阶段三不得改成 `COMPLETED`。
- `FAILED`：本次会话对外显示失败；是否还能重试由独立 `RequestStatus` 判断。
- `CANCELLED`：用户或页面生命周期取消未完成会话。
- `EXPIRED`：快照或会话超过有效期。
- `COMPLETED`：阶段三只保留兼容读取含义，不得用来宣称阶段三已完成支付或已入账。

### 3.3 独立的 `RequestStatus`

`RequestStatus` 是阶段三内部/持久化字段，不能导出为 `SessionStatus`，也不能把它写入 `CheckoutSession.status`。建议字段名为 `ai_request_status`，请求主键为 `checkoutRequestKey`；若现有数据库字段名不同，必须在交接物中写明映射，不得悄悄复用 `status` 字段。

`RequestStatus` 取值固定为以下 10 个值：

| `RequestStatus` | 含义 | 是否终态 |
|---|---|---:|
| `NOT_ELIGIBLE` | 还没有满足结算页或安全门禁，没有有效请求资格 | 否 |
| `SNAPSHOT_BUFFERED` | 快照已保存，但尚未收到购买意图 | 否 |
| `ARMED` | 快照有效且已建立请求键，等待购买意图 | 否 |
| `ELIGIBLE` | 购买意图已通过，等待调度器取得请求所有权 | 否 |
| `IN_FLIGHT` | 已向 AI 服务上传，等待响应 | 否 |
| `SUCCEEDED` | AI 响应已校验且候选已成功写入 | 是 |
| `FAILED_RETRYABLE` | 网络、超时、服务端临时错误等允许重试 | 否，重试后继续流转 |
| `FAILED_FINAL` | 响应非法、配置错误、敏感/过期、预算耗尽或达到次数上限 | 是 |
| `CANCELLED` | 页面离开、用户取消或服务生命周期终止 | 是 |
| `EXPIRED` | 快照或请求键超过有效期 | 是 |

两套状态的映射不是一一对应关系，必须按以下规则记录：

| 会话状态 `SessionStatus` | 允许的请求状态 | 说明 |
|---|---|---|
| `OBSERVING` | `NOT_ELIGIBLE` | 尚未形成可用快照 |
| `SNAPSHOT_BUFFERED` | `SNAPSHOT_BUFFERED` 或 `ARMED` | 两者均表示不联网；`ARMED` 仅表示请求键已准备好 |
| `AI_REQUESTING` | `ELIGIBLE` 或 `IN_FLIGHT` | 前者是排队/抢占，后者是实际网络请求 |
| `WAITING_ENRICHMENT` | `SUCCEEDED` | 候选已落库，但没有支付证据，不得入账 |
| `FAILED` | `FAILED_RETRYABLE` 或 `FAILED_FINAL` | 对外会话状态不承担失败可重试细节 |
| `CANCELLED` | `CANCELLED` | 终态，不得被普通重复事件唤醒 |
| `EXPIRED` | `EXPIRED` | 终态，不得上传过期快照 |
| `COMPLETED` | `SUCCEEDED` 或后续阶段已有值 | 阶段三不负责产生该会话状态 |

失败和重试规则：

1. 同一 `checkoutRequestKey` 同时只有一个 `IN_FLIGHT` 请求；重复提交、支付交接或重复回调只能更新观察时间，不能创建第二个请求。
2. 每个阶段三会话默认最多 2 次 AI 尝试：首次请求加一次重试。重试必须仍有有效安全快照、购买意图和未过期请求键；本地截图次数不计入 AI 次数。
3. 网络断开、连接超时、服务端临时错误和明确可恢复的限流错误进入 `FAILED_RETRYABLE`；退避后回到 `ELIGIBLE`，再进入 `IN_FLIGHT`。
4. 非法 JSON/字段校验失败、认证或配置错误、敏感页面、快照过期、无快照、用户取消和达到 2 次上限进入 `FAILED_FINAL`，不得自动重试。
5. AI 成功后先在同一事务边界写入候选，再把会话设为 `WAITING_ENRICHMENT` 并把请求设为 `SUCCEEDED`。任一写入失败不得宣称成功。
6. 阶段三失败不创建正式账单；`FAILED_FINAL` 只能进入待处理/人工查看语义，相关 RN 操作若无现有契约必须延期。

### 3.4 阶段三内部值与公开值的映射

内部 Kotlin 页面角色可以使用 `PAYMENT_HANDOFF` 作为“从购物应用切换到支付应用”的语义，但对外 `PageRole` 必须映射为 `PAYMENT_APP`。内部 `CheckoutJourneyState` 或 `RequestStatus` 不得直接序列化为 `Native.d.ts` 的 `SessionStatus`。

---

## 4. 任务总览

阶段三共 8 个任务：6 个开发/契约任务，2 个独立证据与验收任务。任务 T7、T8 不是开发任务，不能由开发角色自行勾选通过。

| 编号 | 任务 | 角色 | 依赖 | 类型 |
|---|---|---|---|---|
| T1 | 无障碍服务与页面摘要边界 | 原生开发 + 契约审查 | 阶段二数据层可用 | 开发 |
| T2 | 结算页检测、指纹和敏感门禁 | 原生开发 + 契约审查 | T1 | 开发 |
| T3 | 截图协调、内存缓冲和过期 | 原生开发 + 契约审查 | T2 | 开发 |
| T4 | 购买意图、AI 请求和候选落库 | 原生开发 + 契约审查 | T3 | 开发 |
| T5 | `SessionStatus`/`RequestStatus` 持久化边界 | 原生开发 + 契约审查 | T4 | 开发 |
| T6 | 悬浮窗与 RN 现有契约接入 | RN/契约开发 + 契约审查 | T3、T5 | 开发 |
| T7 | 真机无障碍 dump 证据 | 证据角色 | T1、T2 | 证据 |
| T8 | 真机阶段三验收 | 验收角色 | T3、T4、T5、T6、T7 | 验收 |

任务 T7 和 T8 的结果必须单独记录为“通过/失败/未执行/阻塞”，不能用开发分支的代码提交替代。若没有对应证据提交，文档状态只能写“待验证”。

---

## 5. 任务卡

<a id="task-t1"></a>

### T1. 无障碍服务与页面摘要边界

**目标**：复用现有 `CheckoutAccessibilityService` 和 `AccessibilityPageSummaryCollector`，保证服务只读观察购物应用，不点击、不输入、不处理支付确认，并输出足以供检测器使用的页面摘要。

**角色与依赖**：原生开发；依赖阶段二本地数据层和当前 Android 服务注册；契约审查检查服务生命周期和公开权限查询方式。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/AccessibilityPageSummaryCollector.kt`
- `jotbill-app/android/app/src/main/res/xml/checkout_accessibility_service.xml`
- `jotbill-app/android/app/src/main/AndroidManifest.xml`
- 与上述文件直接相关且已有的注册文件；若需要新增文件，必须在交接前说明原因并先由契约审查批准

**禁止触碰文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**`
- 支付结果页读取、支付匹配、正式账单和自动入账相关文件
- `docs/types/Native.d.ts`（本任务只读）以及任何未列入允许范围的 RN 页面

**必须遵守**：

- 无障碍观察白名单固定覆盖 8 个购物包名：淘宝 `com.taobao.taobao`、天猫 `com.tmall.wireless`、京东 `com.jingdong.app.mall`、美团 `com.sankuai.meituan`、拼多多 `com.xunmeng.pinduoduo`、滴滴 `com.sdu.didi.psnger`、抖音 `com.ss.android.ugc.aweme`、小红书 `com.xingin.xhs`。
- 权限状态不能调用未声明的旧别名；RN 侧只能通过 `Runtime.getRuntimeHealth()` 读取 `accessibilityEnabled`/`accessibilityServiceRunning`，通过 `Runtime.openAccessibilitySettings()` 跳转。
- 只输出脱敏摘要和哈希，不在日志或持久化数据中写入页面原文、密码、验证码或完整订单号。

**提交要求**：中文提交信息 `阶段三任务T1：修正无障碍观察边界`；只提交允许文件。

**交接产物**：分支/基线/提交信息、实际修改类、8 个包名白名单、生命周期说明、权限接口映射表、静态审查结果和未验证项。

<a id="task-t2"></a>

### T2. 结算页检测、指纹和敏感门禁

**目标**：复用 `CheckoutPageDetector`、`CheckoutContract` 和现有摘要收集结果，保守地区分 `SHOPPING_HOME`、`PRODUCT_DETAIL`、`CART`、`CHECKOUT`、`PAYMENT_APP`、`PAYMENT_RESULT`、`UNKNOWN`，只有满足最终金额、结算动作、包名和敏感门禁的 `CHECKOUT` 才能进入截图候选。

**角色与依赖**：原生开发；依赖 T1；契约审查核对内部角色到公开 `PageRole` 的映射。阶段三不把 `PAYMENT_RESULT` 当支付证据。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPageDetector.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutContract.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/AccessibilityPageSummaryCollector.kt`
- 必要的同目录测试样例或纯逻辑辅助文件；新增文件须在交接物中说明

**禁止触碰文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**`
- `CheckoutPaymentMatcher.kt`、支付结果页解析、`PaymentEvidence` 匹配、`Bill`/`PendingTransaction` 写入相关文件
- `docs/types/Native.d.ts` 和任何不存在的 RN 接口声明

**必须遵守**：

- 单个价格、单个“立即购买”、购物车金额或包名都不足以判定 `CHECKOUT`。
- `CHECKOUT` 至少同时具备最终金额语义和结算动作语义，并通过敏感页面保护。
- 内部 `PAYMENT_HANDOFF` 对外映射为 `PAYMENT_APP`；结果页观察仅作为页面角色事实，不触发阶段三支付确认。
- 页面指纹不得包含完整截图、支付密码、验证码、完整银行卡号或完整订单号。

**提交要求**：中文提交信息 `阶段三任务T2：收紧结算页检测门禁`；只提交允许文件。

**交接产物**：页面角色规则、拒绝原因表、指纹字段和脱敏规则、阶段四明确留白项、静态边界审查结果。

<a id="task-t3"></a>

### T3. 截图协调、内存缓冲和过期

**目标**：复用 `CheckoutSnapshotCaptureCoordinator` 和 `CheckoutSnapshotBuffer`，实现稳定结算页自动截图、手动截图共存、30 秒过期、消费后释放和敏感页拒绝。

**角色与依赖**：原生开发；依赖 T2；契约审查核对 `SnapshotSourceReason`、`SnapshotCaptureResult` 和 `Overlay` 公开结果。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutSnapshotCaptureCoordinator.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutSnapshotBuffer.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/overlay/CheckoutCaptureOverlayManager.kt`

**禁止触碰文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**`
- 任何截图持久化、支付结果识别、支付证据匹配和正式账单文件
- `docs/types/Native.d.ts`；不得新增未声明的悬浮窗显示/隐藏或手动捕获公开方法

**必须遵守**：

- `SnapshotSourceReason` 只能使用现有公开值 `CHECKOUT_STABLE` 和 `MANUAL_CAPTURE`。
- 快照只在内存中存在，默认 `expiresAt = capturedAt + 30000`；过期、消费、服务销毁和非支付离开都必须释放图片字节。
- 稳定页面的重复无障碍事件不得重复截图；手动入口使用 `Overlay.requestManualCapture()`，仍必须通过结算页和敏感页门禁。
- 进入支付应用只允许消费已有结算快照，不能重新截图支付密码页或支付结果页。

**提交要求**：中文提交信息 `阶段三任务T3：修正结算截图缓冲生命周期`；只提交允许文件。

**交接产物**：快照生命周期图、状态/拒绝码映射、内存释放点、自动和手动截图去重规则、未做设备验证的明确声明。

<a id="task-t4"></a>

### T4. 购买意图、AI 请求和候选落库

**目标**：复用 `CheckoutPurchaseIntentCoordinator` 和 `CheckoutAiVisionClient`，在有效购买意图后消费快照、按请求键去重、最多进行 2 次 AI 尝试，并将校验后的 AI 结果写入 `TransactionCandidate`。

**角色与依赖**：原生开发；依赖 T3；契约审查检查 AI 请求状态与会话状态分离，并确认没有支付证据匹配或账单写入。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPurchaseIntentCoordinator.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/ai/CheckoutAiVisionClient.kt`
- 与 `TransactionCandidate` 写入和现有会话 DAO 直接相关的原生识别文件；扩大范围必须在交接物中说明

**禁止触碰文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**` 的监听、解析或匹配逻辑
- 支付结果页证据、`PaymentEvidence` 生成/匹配、`Bill`、`PendingTransaction` 和自动入账相关文件
- `docs/types/Native.d.ts` 及未声明的重试/取消接口

**必须遵守**：

- 阶段三有效触发信号为 `SUBMIT_ACTION`、`PAYMENT_APP_HANDOFF`、`PAYMENT_PAGE_VISIBLE` 和契约允许的 `USER_RETRY`。
- `PAYMENT_NOTIFICATION_FALLBACK` 只能作为结构化预留信号，不能在本任务实现通知监听、通知正文解析、支付证据或 AI-支付匹配。
- 同一 `checkoutRequestKey` 只能有一个进行中的请求；提交和支付交接同时发生时只允许一次上传。
- AI 成功只写 `TransactionCandidate` 并将会话置为 `WAITING_ENRICHMENT`；不写 `Bill`，不把 `matched` 当作支付成功，不填充阶段四支付证据字段。
- 失败必须按第 3.3 节的 `RequestStatus` 规则落库；不能把 `FAILED_RETRYABLE` 当作新的 `SessionStatus` 值。

**提交要求**：中文提交信息 `阶段三任务T4：接通购买意图与候选写入`；只提交允许文件。

**交接产物**：触发信号表、请求键和幂等证明、成功/失败状态转换、候选字段写入映射、AI 请求次数记录、阶段四未实现清单。

<a id="task-t5"></a>

### T5. `SessionStatus`/`RequestStatus` 持久化边界

**目标**：在现有 Room 会话、候选和 DAO 边界上补齐状态持久化，使公开会话字段严格保持 8 个 `SessionStatus`，并用独立 `RequestStatus` 保存 AI 请求生命周期。

**角色与依赖**：原生开发 + 契约审查；依赖 T4；必须先检查当前实体、DAO 和数据库迁移版本，再决定是复用现有列、增加迁移还是停止并报告边界问题。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/CheckoutSessionEntity.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/CheckoutSessionDao.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/TransactionCandidateEntity.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/TransactionCandidateDao.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt` 及必要迁移文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutContract.kt` 和 T4 已允许的状态协调文件

**禁止触碰文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**`
- `PaymentEvidenceEntity.kt`/`PaymentEvidenceDao.kt` 的阶段四匹配逻辑
- `Bill`、`PendingTransaction`、自动入账和支付结果页文件
- `docs/types/Native.d.ts`；不得增加第 9 个 `SessionStatus`

**必须遵守**：

- `CheckoutSessionEntity.status` 只存 8 个公开会话值；`RequestStatus` 使用独立字段或独立请求表，并明确字段名、迁移和 DAO 读写。
- `SessionStatus` 和 `RequestStatus` 的映射按第 3.3 节执行；不允许 `status = FAILED_RETRYABLE` 或把 `IN_FLIGHT` 直接序列化给 RN。
- 阶段三 AI 成功的终点是 `SessionStatus.WAITING_ENRICHMENT` + `RequestStatus.SUCCEEDED` + 候选落库。
- `COMPLETED`、支付证据字段和正式账单状态只能由阶段四及以后处理。

**提交要求**：中文提交信息 `阶段三任务T5：分离会话与请求状态`；若需要数据库迁移，迁移文件必须与本任务同提交，不能把业务账单改动混入。

**交接产物**：状态值清单、迁移说明、状态映射表、终态和重试规则、并发/幂等说明、数据库写入边界审查结果。

<a id="task-t6"></a>

### T6. 悬浮窗与 RN 现有契约接入

**目标**：复用 `OverlayModule`、`CheckoutCaptureOverlayManager` 和现有 Native 类型，完成手动截图配置、权限引导、状态查询和识别查询的 RN 接入；不新增公开接口。

**角色与依赖**：RN/契约开发；依赖 T3、T5；契约审查必须逐项对照 `Native.d.ts`。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/overlay/OverlayModule.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/overlay/OverlayPackage.kt`
- `jotbill-app/src/native/overlay.ts`
- `jotbill-app/src/native/runtime.ts` 及与现有 Native 模块注册直接相关的 RN 文件
- 已存在的设置/识别页面；具体页面必须在分支开始时登记

**禁止触碰文件**：

- `docs/types/Native.d.ts`，除非另立“契约变更”任务并获得明确批准
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/**`
- 未声明的悬浮窗操作、手动捕获方法或专用会话变化事件
- 支付结果页、支付证据匹配、正式账单和自动入账页面

**必须遵守**：

- 无障碍权限显示使用 `Runtime.getRuntimeHealth()` 和 `Runtime.openAccessibilitySettings()`。
- 手动截图调用 `Overlay.requestManualCapture()`；状态使用 `getManualCaptureButtonState()` 和已声明的 `onCheckoutCaptureOverlayStateChanged`。
- 识别查询使用 `getCheckoutSessions`、`getCheckoutSession`、`getTransactionCandidate`、`retryAiRecognition(candidateId)` 和 `getRecognitionStatus`。
- 当前没有公开会话取消接口；不得在 RN 中伪造按钮调用不存在的方法。会话刷新只能在现有事件后重新查询。

**提交要求**：中文提交信息 `阶段三任务T6：按现有契约接入识别悬浮窗`；只提交登记过的允许文件。

**交接产物**：Native 方法/事件逐项映射表、页面调用链、错误和未实现接口清单、与 `Native.d.ts` 的静态比对结果。

<a id="task-t7"></a>

### T7. 真机无障碍 dump 证据

**角色**：独立证据角色，不属于开发验收。开发者可以提供待采集页面清单，但不能在代码对话中宣称 dump 已通过。

**依赖**：T1、T2 的分支提交已被审查；需要指定设备、系统版本、应用版本和可复现操作路径。

**允许修改/新增文件**：

- `docs/qa/checkout-samples/` 下的阶段三主样本 XML
- 样本索引、包名/窗口类/采集时间/预期角色元数据
- 证据角色自己的中文采集报告和证据提交

**禁止触碰文件**：

- 不修改 Kotlin、TypeScript、`Native.d.ts` 或任务文档来“适配”样本
- 不采集或纳入阶段三主验收的支付密码页、付款码、支付成功页和通知正文；这些属于阶段四证据
- 不把模拟 XML、旧设备 XML 或开发机截图标记为真机证据

**样本数量基线**：阶段三主样本固定为 **8 个购物平台 × 2 个页面 = 16 个 XML**，每个平台恰好 1 个商品详情页和 1 个结算页：

| 平台 | 商品详情样本 | 结算页样本 |
|---|---|---|
| 淘宝 | `taobao_detail.xml` | `taobao_checkout.xml` |
| 天猫 | `tmall_detail.xml` | `tmall_checkout.xml` |
| 京东 | `jd_detail.xml` | `jd_checkout.xml` |
| 美团 | `meituan_detail.xml` | `meituan_checkout.xml` |
| 拼多多 | `pdd_detail.xml` | `pdd_checkout.xml` |
| 滴滴 | `didi_detail.xml` | `didi_checkout.xml` |
| 抖音 | `douyin_detail.xml` | `douyin_checkout.xml` |
| 小红书 | `xhs_detail.xml` | `xhs_checkout.xml` |

`docs/qa/checkout-samples/` 的验收基线只统计上述 16 个文件。额外诊断样本必须放在单独的补充目录或在索引中标为“非主样本”，不能再写成“16+”或把支付样本计入 16 个平台样本。

**提交要求**：中文提交信息 `阶段三任务T7：提交真机结算页样本证据`；提交只允许的 XML、索引和证据报告，不得携带业务源代码或契约文件。

**交接产物**：16 个 XML、样本索引、每个文件的包名和窗口类、采集设备信息、预期 `PageRole`、缺失/节点稀疏说明、证据提交哈希。证据角色只能报告样本已采集或未采集，不能替开发者宣称分类器通过。

<a id="task-t8"></a>

### T8. 真机阶段三验收

**角色**：独立验收角色。验收结果由证据和验收角色签署，开发对话不能自行勾选通过。

**依赖**：T3、T4、T5、T6 的审查提交和 T7 的 16 个主样本证据；需要明确设备、系统、购物应用版本、AI 服务配置和网络条件。

**允许修改/新增文件**：

- `docs/qa/` 下的阶段三验收报告、测试矩阵和证据索引
- 验收角色的截图、UI dump、关键日志摘要和失败复现记录

**禁止触碰文件**：

- 不为通过验收修改业务源代码、契约或样本原文
- 不接入或验证 `NotificationListenerService`、支付结果页、AI-支付匹配、正式账单或自动入账
- 不把开发分支的静态审查、构建结果或单元测试结果当作真机通过证据

**阶段三验收矩阵**：

| 场景 | 预期结果 | 必需证据 |
|---|---|---|
| 8 个平台进入商品详情页 | 不建立结算快照，不发起 AI 请求 | 每个平台 1 个详情页 dump/日志 |
| 8 个平台进入结算页 | 稳定后最多捕获 1 张内存快照 | 每个平台 1 个结算页 dump、悬浮窗/原生状态记录 |
| 提交订单或支付应用交接 | 消费有效快照，最多产生 1 个关联 AI 请求 | 请求键、请求次数和候选结果记录 |
| 同页重复事件 | 不重复截图、不重复上传 | 重复事件前后状态和请求计数 |
| 快照超过 30 秒 | 不上传过期图片，进入失败/待处理路径 | 过期时间与拒绝结果 |
| 敏感页面或截图能力拒绝 | 不进入 AI 请求，不保存图片 | 拒绝码和脱敏日志 |
| AI 成功 | 写入 `TransactionCandidate`，会话为 `WAITING_ENRICHMENT` | 候选 ID、会话状态、请求状态 |
| AI 临时失败 | 按 `FAILED_RETRYABLE` 最多重试一次 | 两次请求上限和退避记录 |
| AI 最终失败 | `FAILED_FINAL`，不创建正式账单 | 错误分类和无账单证据 |

**验收报告必须逐项标记**：`通过`、`失败`、`未执行` 或 `阻塞`，并引用证据文件和提交哈希。未执行真机操作时，只能写“未执行”，不能写“通过”。

**提交要求**：中文提交信息 `阶段三任务T8：提交真机阶段三验收报告`；提交只允许的验收报告和证据索引，不得携带业务源代码或契约文件。

**交接产物**：按矩阵逐项标记的验收报告、设备/应用版本、证据文件清单、失败与阻塞说明、验收提交哈希和下一阶段交接事项。

---

## 6. 阶段三状态与交接示例

### 6.1 允许的主链路

```text
OBSERVING + NOT_ELIGIBLE
  -> 结算页稳定并截图
SNAPSHOT_BUFFERED + SNAPSHOT_BUFFERED/ARMED
  -> 购买意图通过门禁
AI_REQUESTING + ELIGIBLE/IN_FLIGHT
  -> AI 返回并校验
WAITING_ENRICHMENT + SUCCEEDED
  -> 等待阶段四支付证据
```

### 6.2 不允许的捷径

- 结算页出现就上传 AI。
- 看到微信/支付宝包名就伪造支付成功。
- 通知先到就创建 `PaymentEvidence` 或跳过快照门禁。
- AI 返回候选就写 `Bill` 或把会话写成 `COMPLETED`。
- 为了让 RN 刷新而自行增加未声明的专用事件，或为了取消而自行增加未声明的公开命令。
- 用 `FAILED_RETRYABLE`、`IN_FLIGHT` 等请求状态填充公开 `SessionStatus`。

---

## 7. 证据、审查和完成定义

### 7.1 开发提交完成条件

开发任务只有在以下材料齐全时才可交给审查：

- 中文提交信息、分支名、基线提交和提交哈希。
- 允许/禁止文件清单与实际 `git diff --name-only` 一致。
- 公开接口、事件、状态和阶段边界的逐项对照表。
- 失败、重试、过期、释放和并发行为说明。
- 明确列出未运行的构建、测试和真机验证。

### 7.2 契约审查完成条件

契约审查必须至少检查：

- `SessionStatus` 只有 8 个现有值。
- `RequestStatus` 独立存储，映射和终态规则完整。
- 文档和代码只使用 `Native.d.ts` 已声明的公开接口；未声明的旧别名、取消命令和专用事件均标记为后续契约。
- AI 成功只产生 `TransactionCandidate` 和 `WAITING_ENRICHMENT`，没有支付证据匹配、正式账单或自动入账。
- 通知回退只有预留契约，不扩大为阶段四实现。
- 实际类复用当前实现边界，没有重复创建同职责类。

### 7.3 真机完成条件

真机完成只能由 T7/T8 角色根据独立证据签署。阶段三验收至少需要 16 个主 dump 样本、8 个平台的详情/结算流程证据、AI 触发和候选落库证据、失败/重试/敏感页证据。缺任何一项都必须标记为“部分完成”或“待验证”，不能从代码提交推断通过。

---

## 8. 风险与未解决问题

| 风险/问题 | 阶段三处理 | 责任角色 |
|---|---|---|
| 不同应用版本无障碍节点差异 | 用 T7 的 16 个真机主样本调参；未知结构保持 `UNKNOWN` | 证据角色 + T2 开发 |
| WebView/小程序节点稀疏 | 不强行判定；允许手动截图仍需安全门禁，无法识别则待处理 | T2/T3 |
| Android 截图能力或 `FLAG_SECURE` 拒绝 | 记录结构化拒绝码，释放中间资源，不上传 | T3 |
| AI 返回成功但候选写入失败 | 不把会话置为成功；保留可诊断错误并由审查确认事务边界 | T4/T5 |
| 通知早于 AI 或晚于快照 | 阶段三只保留预留信号，不做支付匹配；交给阶段四 | 阶段四负责人 |
| 当前 RN 没有取消和会话变化事件 | 不自行扩展公开契约；记录为后续契约需求 | 契约审查 |
| 公开 `PaymentEvidence` 类型已存在 | 类型存在不代表阶段三可实现支付证据；阶段三不得填充或匹配 | 契约审查 |
| 真机证据尚未采集 | 状态为“待验证”，由 T7/T8 独立推进 | 证据/验收角色 |

阶段三文档、代码审查或开发对话不得把阶段四能力包装为“回退”“兼容”“自动完成”。边界不清时，停止扩展并由契约审查创建后续阶段任务。
