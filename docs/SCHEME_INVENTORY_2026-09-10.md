# JotBill 识别 · 截图 · 入账链路 —— 方案全景（2026-09-10）

> **基线**：`HEAD 40f10e9` @ `fix/manual-capture-priority`。所有行号与常量均为**代码实测值**，未标注处即已直接读源码确认。
> **定位**：本文是"当前实际在跑的方案"清单，不是设计意图。与 `docs/CONFLICTS_2026-09-10.md`（冲突点）配套：本文说**是什么**，冲突清单说**哪里对不上**。
> **未覆盖**：RN/前端侧、导入（微信/支付宝账单）、报表、预算、备份等非识别链路（不在本次范围）。

> ## ⚠️ 后续裁定更新（2026-09-12 ~ 09-13，`docs/CONFLICT_REVIEW_SESSION_2026-09-11.md`）
>
> 本文记录的是 **`HEAD 40f10e9` 时"实际在跑的方案"**（代码现状），此后用户裁定**改变了若干目标口径**。读本文时请按下表区分"**代码现状**"与"**已定的新口径**"：
>
> | 位置 | 代码现状（本文所述） | 已定新口径 |
> |---|---|---|
> | **§6.6 金额判据**（`:552`） | **单向**：`AI ≥ 支付` 放行；`AI < 支付` 才冲突 | ⚠️ **双向有界容差** `|AI − 支付| ≤ max(¥1.00, 支付×5%)`（D-19）；超界才转待确认。**且须跨两层改**：`evaluate` + DAO 检索 SQL |
> | **§6.6 入账金额**（`:651`） | 取 **AI 候选金额** | ⚠️ **取支付证据金额**（D-19） |
> | **§6.7 弹窗全表**（`:591`） | AI 失败 + 无证据 ⇒ 落 `0` 金额待确认，被 `ZERO_AMOUNT` **跳过弹窗** | ⚠️ **改为允许弹窗并要求补金额**（D-21）；**配套前置**：断言排除条件须由精确值匹配改为集合/前缀匹配 |
> | **§6.6 置信度** | （代码本无置信度判据） | ⚠️ **明确不作为判据**（D-18）：`confidence` 仅诊断记录 |
> | **§6.9 多订单**（`:634`/`:667`） | "入账金额取 AI 候选 ⇒ 错配后果加重" | ⚠️ 金额口径已定（取证据金额）⇒ 该风险的性质从"错记"收敛为"少记"，更易暴露 |
> | **§七 通知证据**（`:707`） | 通知链路为活跃证据源 | ⏸ **v1 已停用**（D-3）：代码保留、**摘除证据作用** |
>
> **本文的"代码实测"部分仍然有效**（行号/常量/机制未变），只是**目标口径已更新** —— 实施改动前请以 `NEW_PROJECT_KICKOFF_V2.md` 0.3.0 + 核对记录为准。

---

## 〇、链路全景

```
无障碍事件
  │
  ├─▶ [1] 采集：AccessibilityPageSummaryCollector
  │        · 双通道 token 分流（signal / fingerprint）
  │        · L3 跨帧易变剔除（VolatileTokenTracker）
  │        └─▶ CheckoutPageObservation（角色/指纹/信号/敏感）
  │              │
  │              ├─▶ [2] 稳定窗口：CheckoutStabilityCoordinator（500ms 去抖 / 2000ms 上限）
  │              │       └─▶ StableCheckoutPage
  │              │             └─▶ [3] 认定门禁：CheckoutPageDetector（6 道）
  │              │                   └─▶ [4] 截图协调器：CheckoutSnapshotCaptureCoordinator
  │              │                         └─▶ [5] 内存缓冲：CheckoutSnapshotBuffer（槽位 / 120s TTL）
  │              │
  │              └─▶ [6] 购买意图：CheckoutPurchaseIntentCoordinator（5 种意图）
  │                        └─▶ consumeForAi ─▶ [7] AI：CheckoutAiVisionClient
  │                                              └─▶ [8] 匹配入账：CheckoutPaymentMatcher
  │
  ├─▶ 支付通知证据（Notification 服务）
  └─▶ 支付结果页证据（无障碍）
```

**两条平行入口**：无障碍（页面/截图/AI）与通知（支付证据）。二者在 [8] 匹配层收敛。

---

## 一、采集与页面认定层

### 1.1 包名白名单（硬边界）

| 类别 | 常量 | 实测内容 |
|---|---|---|
| 购物应用（8） | `DEFAULT_SUPPORTED_SHOPPING_PACKAGES`（`CheckoutPageDetector.kt:3-12`） | 淘宝 / 天猫 / 京东 / 美团 / 拼多多 / 滴滴 / 抖音 / 小红书 |
| 支付应用（2） | `DEFAULT_SUPPORTED_PAYMENT_PACKAGES`（`:15-18`） | 微信 `com.tencent.mm` / 支付宝 `com.eg.android.AlipayGphone` |

> `:102-104` 注释明示：购物包名是阶段三固定边界，**不能由调用方扩展**；包名只用于资格判断，不据此推断平台/商户/订单字段。

### 1.2 页面摘要采集（`AccessibilityPageSummaryCollector.kt`）

**核心创新：token 双通道分流**（568bfc8 引入）

| 通道 | 用途 | 是否受 L3 易变剔除 | 锚点 |
|---|---|---|---|
| `signalTokens` | 角色 / 金额 / 动作 / 敏感判定 | ❌ 不受影响 | `:311-317`（注释明示"语义门禁不因指纹稳定化而削弱"） |
| `fingerprintTokens` | 只用于构造页面指纹 | ✅ 受剔除（`:650-654`） | `:336-349` |

**采集约束**
- `includeHiddenText` 默认 `true`（`:297`）——弹窗式结算页/微信结果页关键节点会间歇 `isVisibleToUser=false`，不纳入会导致角色抖动。
- 轮播容器整棵子树跳过指纹（`:400-402`），类名语义仍保留。
- **位置键构造**（`:408-414`）：`末段类名` + （有 viewId 时）`@末段viewId` → 无 viewId 时退化为裸类名。
- `text` 与 `contentDescription` **共用同一位置键**（`:415-420`）。
- 文本容量截断时只打 `Log.w`（`:318-325`，标记 `textDropped`）。

**语义词表（9 类，`:113-215`）**

| 词表 | 作用 | 内容摘要 |
|---|---|---|
| `finalAmountTerms` | 最终金额信号 + 易变护栏豁免 | 应付/实付/订单金额/订单总额/优惠后合计/待支付/待付款/需付款… |
| `checkoutActionTerms` | 结算动作信号 | 提交订单/去支付/确认订单… |
| `quantityMinusTerms` / `quantityPlusTerms` | 规格层结构证据 | `减少数量` / `增加数量` |
| `specGuideTerms` | 规格引导词 | `已选：` / `已选:`（带冒号更特异，避免误伤统计文案） |
| `cartTerms` | 购物车 | 购物车/购物袋/去结算/全选 |
| `productTerms` | 商品页 | 商品详情/加入购物车/立即购买/选择规格 |
| `browsingTerms` | 浏览页 | 搜索/推荐/首页/分类/猜你喜欢 |
| `checkoutClassTerms` | 窗口类名结算语义（小写） | settlement/fillorder/checkout… |
| `sensitiveTerms` | 敏感页 | 密码/验证码/校验码… |
| `paymentHandoffTerms` | 交接页 | 支付密码/付款码/二维码… |
| `paymentResultTerms` | 结果页 | 支付成功/支付失败/交易完成… |

### 1.3 页面角色分类优先级（`classifyPageRole`，`:533-578`）

**严格短路顺序**（先命中先返回）：

| 序 | 判据 | 结果 |
|---|---|---|
| 1 | 命中 `paymentHandoffTerms` | `PAYMENT_HANDOFF` |
| 2 | 命中 `paymentResultTerms` | `PAYMENT_RESULT` |
| 3 | `isProductSpecSelectionLayer()` | `PRODUCT_DETAIL`（Y3 排除） |
| 4 | `!敏感 && 金额信号 && (动作信号 \|\| 类名结算语义)` | **`CHECKOUT`** |
| 5 | `cartTerms` | `CART` |
| 6 | `productTerms` | `PRODUCT_DETAIL` |
| 7 | `browsingTerms` | `SHOPPING_HOME` |
| 8 | — | `UNKNOWN` |

**角色可缓冲能力**（`CheckoutContract.kt:21-30`）

| 角色 | 自动可缓冲 | 手动重试可缓冲 |
|---|---|---|
| `CHECKOUT` | ✅ | ✅ |
| `SHOPPING_HOME` / `PRODUCT_DETAIL` / `CART` / `UNKNOWN` | ❌ | ✅ |
| `PAYMENT_HANDOFF` / `PAYMENT_RESULT` | ❌ | ❌ |

### 1.4 Y3 规格层排除（`isProductSpecSelectionLayer`，`:272-283`）

```
if (hasCheckoutActionSignal) return false          // ← 40f10e9 回归修正：动作信号一票否决排除
return hasQuantityPair || hasSpecGuide              // 数量增减成对 || 已选：引导词
```

- **Y3 原点**：规格弹层隐藏节点携带支付动作词（`actionSignal=true`）→ 每次轮播换字触发新会话+自动截图，截到的全是商品页图。
- **40f10e9 回归修正**：拼多多"确认订单"结算层与规格层**共用 `wd2.f0` 窗口、同样带"已选："** → 动作信号并存时不得排除，否则真结算页被误杀（14:08 取证：¥79 支付落孤立证据 `CHECKOUT_REFERENCE_INVALID`）。
- **残余面**：弹层隐藏节点带动作词 ⇒ 排除失效、仍判 `CHECKOUT`（5.10.1 真机实锤）。

### 1.5 页面指纹构造（`:341-349`）

```
pageFingerprint = SHA-256(
    FINGERPRINT_VERSION | shoppingPackage | normalizedWindowClass
  | pageRole.storageValue | SHA-256(stableText)
)
```

> **关键含义**：`windowClass` 参与指纹 ⇒ 规格层与结算层共用 `wd2.f0` 时，两层的指纹差异**只来自 `stableText` 与 `pageRole`**。`pageRole` 变化会改变指纹，但（见 §2.1）**不触发会话重置**。

### 1.6 L3 跨帧易变检测（`VolatileTokenTracker.kt`，568bfc8 新增）

| 项 | 实测值 |
|---|---|
| 机理 | 同一 `scope`（包名+窗口类名，`:303`）内同一**位置键**出现 **≥2 个不同文本** ⇒ 该位置判定"易变" |
| 观察窗口 | `DEFAULT_WINDOW_MILLIS = 15_000L`（`:99`） |
| 容量上限 | `DEFAULT_MAX_TRACKED_POSITIONS = 400`（`:102`），**满则静默 `return false` 不追踪**（`:66-70`） |
| 淘汰时机 | **先淘汰窗口外记录，再判定**（`:77-86`，568bfc8 修过"先判定后淘汰致陈旧误判"） |
| 护栏 | 命中 `finalAmountTerms` 的 token **永不进易变池**（`Collector:648-650`）——理由：结算页金额静态不轮播，轮播的是商品页到手价/折扣，故排除轮播不会让"换商品"逃过指纹变化 |

### 1.7 稳定窗口（`CheckoutStabilityCoordinator`，`Service:3214-3300`）

| 项 | 实测值 | 说明 |
|---|---|---|
| 去抖窗口 | `STABLE_DELAY_MILLIS = 500L`（`Service:55`），构造期强制 300~800ms（`:3278`） | 每来一个新指纹就**归零重计** |
| 最长等待上限 | `maxStableWaitMillis = 2_000L`（`:3229`） | 指纹持续漂移时强制确认一次——"宁可截到一帧略旧的结算页，也不能一张都截不到" |
| 抖动期保留 | `pendingKeptAcrossInvalidation`（`:3251`/`:3262`） | 同包非结算页失效会把 `hasActiveCheckout` 置 false，保留候选不受该检查限制 |
| 去重 | 指纹等于 `lastStableFingerprint` 则跳过（`:3263`） | 同一指纹不重复确认 |
| 依据 | **只以指纹 + 时间**，不使用事件次数（`:3212` 注释） | |

### 1.8 认定门禁（`CheckoutPageDetector.detect`，`CheckoutPageDetector.kt:112-138`）

6 道，**全过才 `Accepted`**；任一失败即 `Rejected` 并触发失效路径：

| 序 | 判据 | 拒绝码 |
|---|---|---|
| 1 | 购物包在白名单 | `UNSUPPORTED_SHOPPING_PACKAGE` |
| 2 | `pageRole == CHECKOUT` | `NOT_CHECKOUT_PAGE` |
| 3 | `hasFinalAmountSignal` | `MISSING_FINAL_AMOUNT` |
| 4 | `hasCheckoutActionSignal \|\| hasCheckoutClassSemantic` | `MISSING_CHECKOUT_ACTION` |
| 5 | `!isSensitivePage` | `SENSITIVE_PAGE` |
| 6 | 指纹为合法 SHA-256（64 位 hex） | `INVALID_PAGE_FINGERPRINT` |

> **第 4 道是 D1 缺陷的根**：`hasCheckoutActionSignal` 依赖真实点击事件（见 §4.1），App 改版/漏检即"全无"；`hasCheckoutClassSemantic`（`:36-43`）是补偿通道，但**只补偿检测器层，不补偿 `CheckoutSubmitActionDetector`**。

---

## 二、截图链路层

### 2.1 自动截图（`CHECKOUT_STABLE`）——核心决策在 `handleStable`（`Coordinator:1352-1540`）

**决策顺序（实测）**

| 序 | 判定 | 动作 |
|---|---|---|
| 1 | `pageDetector.detect()` 非 Accepted | 转 `handleInvalidated` |
| 2 | 有 `handoffCheckout` 挂起 → 见 §2.2 | 同包且指纹同 ⇒ **恢复原会话**；同包但指纹不同且差值 < `HANDOFF_DRIFT_TAIL_WINDOW_MILLIS = 2_500L`（`:308`）⇒ **直接 return（漂移尾窗）**；否则丢弃旧槽位 |
| 3 | `startsNewSession` 判定（`:1428-1431`） | 见下表 |
| 4 | `key in attemptedAutomaticCaptures` | return（**每 key 只试一次**，D3） |
| 5 | `driftWithinSameCheckout`（`:1503-1507`） | 记入 attempted + 发 `DUPLICATE`，**不重截** |
| 6 | 槽内已有**手动**候选（同会话同指纹） | 记入 attempted + 发 `PROTECTED`（~~手动候选受保护~~ → ⚠️ **2026-09-12（D-16 = B）：改为"自动优先"** —— 同指纹即"自动也认得出这一页"，应**放行自动覆盖**、不再拒绝；详见 `ISSUE_REGISTER` 顶部裁定表） |
| 7 | 槽内已有**自动**候选（同会话同指纹） | 记入 attempted + 发 `DUPLICATE` |
| 8 | 有 `pendingCapture` 在飞 | `queuedAutomatic = current`（排队） |
| 9 | — | 记入 attempted + `beginCapture(CHECKOUT_STABLE)` |

**会话重置条件（`:1424-1431`，Y4 锚点）**

```
driftWithinSameCheckout = previous != null
    && 同 shoppingPackage
    && 指纹不同
    && hasRetainableCandidate(previous, observation)

startsNewSession = previous == null
    || 换 shoppingPackage
    || (指纹不同 && !driftWithinSameCheckout)
```

**`hasRetainableCandidate`（`:1333-1350`）**——判定漂移是否可沿用：
1. 同 `shoppingPackage`
2. `captureTarget == CHECKOUT`
3. 槽位内存在**本会话的任意候选**

> ⚠️ **该判定不比对指纹、不看页面角色、无时间上限、无相似度下限**。⇒ 只要同包 + 槽内有本会话的任意候选，**指纹怎么变都沿用原会话**。这是 Y4（会话跨页存续污染）的代码锚点，已实测确认。文档 5.10.2 的"机制（日志推断，待复核）"至此**复核为代码事实**。

**截图编码约束**

| 项 | 值 | 锚点 |
|---|---|---|
| 长边上限 | `MAX_SCREENSHOT_LONG_EDGE = 1080` | `:303` |
| JPEG 质量 | `JPEG_QUALITY = 85` | `:304` |
| 编码后上限 | `MAX_ENCODED_SCREENSHOT_BYTES = 5 MiB` | `:305` |
| 回调看门狗 | `CAPTURE_CALLBACK_TIMEOUT_MILLIS = 3_000L` | `:324` |
| 事件分发超时 | `EVENT_DISPATCH_TIMEOUT_MILLIS = 5_000L` | `:306` |

**失败熔断策略**（`shouldFailClosedForDispatchFailure`，`:109-115`）

| 失败类别 | 是否熔断 | 理由 |
|---|---|---|
| `MAIN_THREAD_TIMEOUT` | ❌ 不熔断 | 一次 5 秒卡顿曾致 12 次手动截图全 `SERVICE_NOT_CONNECTED` |
| `EVENT_RUNTIME_ERROR` | ❌ 不熔断 | 单事件问题 |
| `MAIN_HANDLER_UNAVAILABLE` | ✅ 熔断 | 已无法维持状态机 |
| `FATAL_ERROR` | ✅ 熔断 | 运行时不可信 |
| `SERVICE_DESTROYED` | ✅ 熔断 | 正常收口 |

> 熔断不可逆（`screenshotThread` `quitSafely`），代价是截图能力永久失效 ⇒ 只有真正无法维持状态机才触发。

### 2.2 支付交接挂起（`handoffCheckout`）

- 中间页（拼多多提交后收银台、京东提交后加载页）判为非结算页时，若命中 `shouldRetainCheckoutAcrossPaymentInterstitial`（`Service:945-952`）⇒ **保留**挂起会话与快照（否则只能落 `NO_ACTIVE_SESSION` 孤儿待确认）。
- 挂起期漂移尾窗 2.5s（`:384-1390`）：迟到稳定事件若同包、指纹不同、且距 `stableAt` < 2500ms ⇒ 直接丢弃，**不视为新订单**。
- 返回原结算页 ⇒ `buffer.restoreFromPaymentHandoff()` + 恢复 `attemptedAutomaticCaptures`，**不重新截图、不覆盖原候选**（`:1397-1411`）。
- 返回到**另一个**结算页 ⇒ `buffer.discard(reason = "HANDOFF_RETURNED_TO_OTHER_CHECKOUT")`（`:1415`）。

### 2.3 手动截图与抢占（`requestManualCapture`，`Coordinator:1061-1204`）

| 场景 | 行为 | 锚点 |
|---|---|---|
| 自动在飞 | `detachPendingCapture()` 取消 + 发布 `PREEMPTED_BY_MANUAL_CAPTURE`（`:315`），自动 key **移出** `attemptedAutomaticCaptures` | `:1157-1188` |
| 手动在飞 / 已排队 | 仍返回 `CAPTURE_IN_FLIGHT` | `:1159` |
| **排队机制** | 已移除（f8365e8）；`queuedManual` 无赋值点，`drainQueuedCapture()` 仍留 5 处调用 | 见冲突清单 C6 |
| 补截兜底 | 手动失败 + key 已移出 ⇒ 后续稳定事件可自动补截 | — |
| 异步安全三重防线 | ①看门狗 requestId 比对（`:1808`）②迟到回调 requestId 比对（`:1911-1914`）③`completionPublished` CAS（`:1306`） | — |

**store 请求 target 覆盖**（`:1973`）：`sourceReason == MANUAL_CAPTURE` ⇒ `captureTarget = USER_RETRY`（**仅请求层覆盖**，会话管理 8 处 `captureTarget` 分支零扰动）。原因：`CHECKOUT` target 在 buffer 门禁只收 `CHECKOUT_STABLE` 来源，手动图会被整张丢弃（`NOT_CHECKOUT_PAGE`）。

### 2.4 断言通道（`user-asserted:` 专用槽位，113a7ff）

| 项 | 实测值 | 锚点 |
|---|---|---|
| 槽位前缀 | `USER_ASSERTED_SLOT_PREFIX = "user-asserted:"` | `Service:93` |
| 最小间隔 | `USER_ASSERTED_CAPTURE_MIN_INTERVAL_MILLIS = 3_000L` | `Service:81` |
| 看门狗 | `USER_ASSERTED_CAPTURE_WATCHDOG_MILLIS = 8_000L` | `Service:84` |
| 在飞标志 | `userAssertedCaptureInFlight` | `Service:348` |
| 会话登记 | `assertedSessionIds`（供 AI 返回后 promote） | `Service:361` |
| 入口 | `performUserAssertedCapture()` | `Service:2543` |
| 可用性 | `isUserAssertedCaptureAvailable()`（服务存活 + AI 已配置） | `Service:2529` |
| 收口 | `promoteAssertedSessionToPending()` | `Matcher:1328` |

**流**：点按钮 → 截图 → store（USER_RETRY target）→ 发 `STORED` → `consumeForAi(USER_RETRY)`（`:2739-2743`）→ `assertedSessionIds += sessionId` → `dispatchReadyToAi` → `overlay.onAiDispatched()` 收起按钮。

**静默化（40c36be，Z2）**：断言条目**不弹窗、不通知**，静默进待确认中心。
- `PendingPromptPolicy.SkipReason.USER_ASSERTED`（`PendingPromptPolicy.kt:43`），判据 `info.conflictReason == PENDING_CONFLICT_REASON_USER_ASSERTED`（`:77-79`）
- `PendingReminderNotifier.kt:87` 同判据跳过通知
- 协议常量收敛于 `PendingChangeHub.kt:49`（`PENDING_CONFLICT_REASON_USER_ASSERTED = "USER_ASSERTED"`）

### 2.5 三通道共存模型（`DECISIONS.md:52-56` 声明 + 实测）

| 通道 | 槽位 | target | 来源 reason | 用途 |
|---|---|---|---|---|
| 自动检测 | `shopping-package:<pkg>` | `CHECKOUT` | `CHECKOUT_STABLE` | 结算页稳定后自动截 |
| 会话绑定手动 | 同上 | `USER_RETRY` | `MANUAL_CAPTURE` | 手动重截更好的图 |
| 用户断言 | `user-asserted:<pkg>` | `USER_RETRY` | `MANUAL_CAPTURE` | 识别失败/普通页一键直出待确认 |

**共用同一 buffer**；槽位按前缀隔离，断言通道与结算槽位**零交互**。

### 2.6 内存缓冲（`CheckoutSnapshotBuffer.kt`）

| 项 | 实测值 | 锚点 |
|---|---|---|
| 槽位命名 | `shopping-package:<pkg>`（`CHECKOUT_SNAPSHOT_SLOT_PREFIX`） | `:13-17` |
| 快照 TTL | `DEFAULT_SNAPSHOT_TTL_MILLIS = 120_000L` | `:13` |
| 持久化 | **禁止**——`imageBytes` 不进 Room/文件/日志/RN；消费或覆盖时先清零 | `:23-27` |
| 释放契约 | `release()` 内部 `imageBytes.fill(0)` | `:47-50` |

**`store()` 判定顺序（严格，`:256-320`）**

| 序 | 判据 | 拒绝/结果 |
|---|---|---|
| 1 | slotId/sessionId/shoppingPackage/pageFingerprint 非空 | `INVALID_ARGUMENT` |
| 2 | **target 门禁**（`:264-275`）：`CHECKOUT` 要求 `CHECKOUT_STABLE` + 角色可缓冲；`USER_RETRY` 要求 `MANUAL_CAPTURE` + `canBufferUserRetrySnapshot()` | `NOT_CHECKOUT_PAGE` |
| 3 | `isSensitivePage` | `SENSITIVE_PAGE` |
| 4 | 图片空 | `EMPTY_IMAGE` |
| 5 | **消费墓碑**（`:287-293`）：同会话且已有 `consumedRequestKey` 且非 USER_RETRY | `JOURNEY_ALREADY_TRIGGERED` |
| 6 | **同指纹处理**（`:302-315`）：已有手动候选 + 新来自动 ⇒ `MANUAL_CANDIDATE_PROTECTED`；同来源 ⇒ `Unchanged`（去重）；否则移除旧候选 | — |
| 7 | USER_RETRY 落盘 ⇒ **清消费墓碑**（`:317-320`） | 手动重截 = 一次新的明确消费机会 |

> 顺序实测确认：**target 门禁 → 墓碑 → 同指纹**。这纠正了历史记录中"C4 墓碑拒绝挡路"的错误描述（真正挡路的一直是 target 门禁）。

**消费判定（`consumeForAi`，`:587` / 无指纹分支 `:687-701`）**

优先级实测：
1. **页面角色守卫先行**：只有 `USER_RETRY` 允许消费非结算页快照；`CHECKOUT` 快照必须来自 `SUBMIT_ACTION` / `PAYMENT_APP_HANDOFF` 等真实购买意图。
2. 无指纹分支：手动候选且（角色为 `CHECKOUT` **或** 意图为 `USER_RETRY`）⇒ **直接选中**；否则回退 `singleOrNull()`，多候选即 `AMBIGUOUS_CANDIDATES`。
3. TTL 过期 ⇒ `SNAPSHOT_EXPIRED`（`:652`/`:668`/`:717`）。

### 2.7 截图不可用原因（`SnapshotCaptureUnavailableReason`，`:47-56`）

`SERVICE_NOT_CONNECTED` / `API_UNSUPPORTED` / `CAPABILITY_UNAVAILABLE` / `SCREENSHOT_SECURE` / `SCREENSHOT_BUSY` / `INVALID_DISPLAY` / `SYSTEM_REJECTED` / `ENCODE_FAILED`

---

## 三、悬浮按钮层（`overlay/CheckoutCaptureOverlayManager.kt`）

### 3.1 三种模式（`:32-41`）

| 模式 | wireValue | 说明 |
|---|---|---|
| `ALWAYS_ON` | `always_on` | 购物 App 前台即显示 |
| `CHECKOUT_ONLY` | `checkout_only` | 仅结算页显示（**`parse()` 的默认值**，`:39`） |
| `OFF` | `off` | 关闭 |

> 注意：持久化键 `KEY_MODE` 的读取缺省是 `DEFAULT_MODE = "always_on"`（`:187`），而 `parse()` 缺省是 `CHECKOUT_ONLY`（`:39`）——两处缺省不一致（对应历史 Y2"模式默认值"问题）。

### 3.2 八种状态（`:117-126`）

`HIDDEN` / `READY` / `CAPTURING` / `CAPTURED_WAITING_PAYMENT` / `EXPIRED_RETRY` / `SENSITIVE` / `UNAVAILABLE` / `SERVICE_NOT_CONNECTED`

> `EXPIRED_RETRY` 为**死状态**：唯一入口是消费 `SnapshotCaptureStatus.EXPIRED`（`:465-466`），而该状态全仓无产出方。

### 3.3 时序常量

| 项 | 值 | 锚点 | 作用 |
|---|---|---|---|
| 手动点击最小间隔 | `MANUAL_CAPTURE_MIN_INTERVAL_MILLIS = 800L` | `:196` | 节流 |
| "已捕获"保持窗 | `CAPTURED_STATE_HOLD_MILLIS = 3_000L` | `:205` | 京东结算页 STORED 后数十 ms 即抛 `NON_CHECKOUT_PAGE`，否则用户看不到反馈 |
| 隐藏宽限 | `HIDE_GRACE_MILLIS = 1_500L` | `:212` | 防闪烁 |

### 3.4 可点判定（`canManualCapture()`，`:791`）

可点集合含 `READY`、`CAPTURING`（自动截图在飞时仍允许点，由协调器 `CAPTURE_IN_FLIGHT` 仲裁，**不能在自动开始前就把按钮禁用**）、`CAPTURED_WAITING_PAYMENT`（9da8171 加入，理由：漂移后金额已变/首帧不完整，用户可能想重截）。集合内条目在 `:812`。

---

## 四、购买意图与交接层

### 4.1 提交动作检测（`CheckoutSubmitActionDetector.kt`）

**判据（全部满足）**
1. `event.eventType == TYPE_VIEW_CLICKED`（`:47`）
2. `source.packageName == shoppingPackage`（`:50`）
3. 从 source 向上 ≤ `MAX_ANCESTOR_DEPTH = 4` 层内存在 `isSubmitNode`（`:58-82`）

**`isSubmitNode`（`:95-103`）**：`isVisibleToUser && isClickable && hasClickAction` 且（text / contentDescription ∈ `submitLabels`，或 `viewIdResourceName` 含 `submitResourceMarkers`）

- `submitLabels`（`:15-30`）：提交订单/去支付/确认订单/立即支付/确认支付/同意并支付/去付款（含"…按钮"变体）
- `submitResourceMarkers`（`:32-40`）：`submitorder` / `gopay` / `confirmorder` / `paynow` / `confirmpayment` / `agreeandpay` / `gopayment`

> ⚠️ `isVisibleToUser` 是硬条件 ⇒ 弹窗结算页提交按钮间歇不可见时**永不触发**。检测器层有 `hasCheckoutClassSemantic` 补偿，但**本检测器没有类名兜底**（D1）。

**去重**：`submitActionTriggeredKey`（`Service:2970-2977`），key = 包名+指纹。

### 4.2 五种购买意图（`PurchaseIntent`，`CheckoutContract.kt:43-49`）

`SUBMIT_ACTION` / `PAYMENT_APP_HANDOFF` / `PAYMENT_PAGE_VISIBLE` / `PAYMENT_NOTIFICATION_FALLBACK` / `USER_RETRY`

### 4.3 请求键与配额

| 项 | 值 | 锚点 |
|---|---|---|
| `CheckoutRequestKey` 组成 | `checkout\|shoppingPackage\|sessionId\|pageFingerprint` | `CheckoutContract.kt:315-322` |
| 关键性质 | **触发来源不参与计算** ⇒ 提交/交接/通知回退命中同一键 | `:306` 注释 |
| 每会话 AI 请求上限 | `DEFAULT_MAX_REQUESTS_PER_SESSION = 2` | `CheckoutPurchaseIntentCoordinator.kt:131` |
| 协调器边界 | 不执行网络/OCR/通知匹配/记账；`consumeForAi` 原子完成有效期+未消费+歧义判定 | `:122` |

---

## 五、AI 识别层（`ai/CheckoutAiVisionClient.kt`）

| 项 | 值 | 锚点 |
|---|---|---|
| 单次尝试超时 | `ATTEMPT_TIMEOUT_MILLIS = 15_000L` | `:261` |
| 总计超时 | `TOTAL_TIMEOUT_MILLIS = 32_000L` | `:262` |
| 重试退避 | `RETRY_BACKOFF_MILLIS = 350L` | `:263` |

**模型（`DECISIONS.md:23-33`）**：主用 通义千问 `qwen-vl-plus`；备用 智谱 `GLM-4V-Flash`；调试 硅基流动 `Qwen3-VL-8B-Instruct`。三家均兼容 OpenAI Vision 格式 ⇒ 单适配器。默认月度预算 10 元。模型下线 → `AI_AUTH` 错误码 + 设置页连通性测试。

**派发链**：`buffer.consumeForAi()`（`Service:2739`）→ `CheckoutAiTriggerResult.Ready` → `dispatchReadyToAi()` → 结果回 `Matcher`。

---

## 六、匹配与入账层（`CheckoutPaymentMatcher.kt`）

| 项 | 值 | 锚点 |
|---|---|---|
| 支付匹配窗口 | `PAYMENT_MATCH_WINDOW_MILLIS = 5 分钟` | `:24-25` |
| 证据合并窗口 | `PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS = 2 分钟` | `:28` |
| 断言来源标记 | `USER_ASSERTED_REASON = "USER_ASSERTED"` | `:41` |
| 断言缺金额 | `USER_ASSERTED_AMOUNT_MISSING` | `:44` |
| AI 未验证支付 | `PAYMENT_STATUS_AI_UNVERIFIED = "SUCCESS_AI_UNVERIFIED"` + `AI_UNVERIFIED_PAYMENT_REASON` | `:53`/`:56` |
| 证据唯一决策 | `EVIDENCE_ONLY_DECISION = "EVIDENCE_ONLY"` | `:38` |
| 默认账户 | 微信 `default-account-wechat` / 支付宝 `default-account-alipay` | `:117-118` |

**证据来源归一化**（`CheckoutContract.kt:82-88`）：`notification` / `accessibility`（含 `accessibility_result`）/ `manual`，其余为 `null`（先 `lowercase` 再匹配，故 `"NOTIFICATION"`/`"ACCESSIBILITY"` 亦合法）。

### 6.1 三条证据来源（实测，含载体与入口）

| 来源 | sourceType | 产生者 | 入口 | 数据形态 | 是否外发 |
|---|---|---|---|---|---|
| **支付通知** | `notification` | `PaymentNotificationService`（`NotificationListenerService`，`:42`） | `onNotificationPosted`（`:100`）→ `processNotification`（`:122`） | 通知 extras 七字段（title/titleBig/text/subText/summaryText/bigText/tickerText，`:130-136`）+ `postTime`/`key`/`id` | 否 |
| **结果页无障碍** | `accessibility` | `CheckoutAccessibilityService` | `observePaymentResultIfApplicable`（`:1436`，阶段四 T3），调用点 `:758`/`:858` | 无障碍节点文本 | 否（主路径） |
| **结果页截图兜底** | `accessibility`（`aiUnverified`） | 同上 | `maybeCapturePaymentResultScreenshot`（`:1654`） | 系统截图 + AI 视觉 | **是（受限）** |

> **三者共用同一载体与落库入口**：皆封装为 `PaymentNotificationFallbackSignal`（`CheckoutContract.kt:57-79`，最小结构化信号 + `isValid()` 校验，金额统一为**分**），皆走 `matcher.recordPaymentEvidence()`（`Matcher:614`）/ `recordOrphanPaymentSignal()`（`Matcher:425`），**仅以 `sourceType` 区分**。

### 6.2 支付通知证据是什么

**归一化产物 `PaymentEvidenceInput`**（`PaymentResultNormalizer.kt:12-29`）：`sourcePackage` / `amountMinor`（分）/ `occurredAt` / `merchant` / `orderId` / `paymentStatus` / `sourceEventKey` / `rawTextHash`，外加 `receivingAccountHint` / `targetAccountHint` / `recipientName` 三个可选提示。**不含通知原文**，只保留原文哈希。

**四类语义路由，顺序敏感**（`processNotification`，`PaymentNotificationService.kt:138-236`）：

| 序 | 路由 | 处理器 | 说明 |
|---|---|---|---|
| 1 | `normalizeRefund` | `RefundLinker` | 退款与结算会话无关，不走回退/会话匹配，直接做原交易关联决策 |
| 2 | `normalizeFee` | `handleFeeEvidence` | 同一通知拆 **fee（手续费独立待确认）+ main（主交易继续走 T3）** |
| 3 | `normalizeTransfer` | `TransferClassifier` | 转账/还款方向与账户归属决策 |
| 4 | `normalize` | `handlePaymentEvidence` | 消费语义；`EXCLUDE_KEYWORDS`（`:86-88`）排除 收款/到账/收到/转入/红包/转账/转出/提现/退款/退回/返还/领取/发放 |

> ⚠️ 第 4 步排除词含"红包""转账"，但 1~3 步**先行路由** ⇒ 排除实际只作用于最终消费路径。与 kickoff `:159`/`:951` 的"排除收款/红包"字面表述冲突（见冲突清单 B5）。

**落库两条去向**（`handlePaymentEvidence`，`:254-306`）
- 有活跃会话 ⇒ 经无障碍回退信号关联 ⇒ `recordPaymentEvidence(reference, signal)`
- 无会话 / 超时 / 门禁拒绝 ⇒ `recordOrphanPaymentSignal(signal, sourceType = "NOTIFICATION")` 落孤立待确认；超时情形先 `delay(EVIDENCE_GRACE_MILLIS)` 后按 `sourceEventKey` 复查（`:280-285`），避免同一次支付既落会话证据又落孤儿

### 6.3 结果页无障碍识别 —— 主路径与兜底子路径

**主路径（节点文本解析，不外发）**
1. 支付包事件 → `findPaymentResultRoots`（`:1448`，空树时直接进兜底）
2. 页面角色须为 `PAYMENT_RESULT`（`classifyPageRole` 第 2 优先级词表 `paymentResultTerms`）
3. `collectPageText(root)` → `PaymentResultParser.parseResult`（`:1531`）
4. 幂等去重 `markPaymentResultEvent(sourceEventKey, observedAt)`（`:1573`）
5. `recordPaymentResultEvidence`（`:2094`）→ `recordPaymentEvidence(..., sourceType = "ACCESSIBILITY")`

**截图 AI 兜底子路径（`maybeCapturePaymentResultScreenshot`，`:1654`）—— 四道闸门**

| 序 | 闸门 | 锚点 |
|---|---|---|
| 1 | **P0 隐私红线**：本轮观察到敏感页即禁止（不受节流影响）+ 敏感页锁存窗口 | `:1660-1663`、`:1674`（`SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS`） |
| 2 | **支付旅程门槛**：无任何交接上下文即禁止——否则会把聊天记录/朋友圈编码发往外部 AI | `:1668-1671` |
| 3 | 节流：在飞 / 距上次 < 15s / 成功后 90s 内不再兜底 | `:1682-1688` |
| 4 | API 30+ 守卫（`takeScreenshot` 为 API 30+，`NoSuchMethodError` 属 Error 不能靠 RuntimeException 捕获） | `:1691-1694` |

**结果页解析的防线（`PaymentResultParser.kt`）**

| 防线 | 值 / 规则 | 锚点 |
|---|---|---|
| 失败词优先于成功词 | 防混合文案误判成功 | `:155-158` |
| 成功词表不含"订单已提交" | 可能是货到付款/待支付 | `:69` |
| **历史回放闸门** | 页面显示时间距观察时刻 > `RESULT_PAGE_FRESHNESS_MILLIS`（30 分钟）⇒ 静默拒绝 | `:133`、`:168-173` |
| 个人转账优先于消费 | 强语义「转账给+收款人」；弱语义需"无商户且无订单号" | `:182-201` |
| 事件键**不含页面指纹** | `sha256("result-event\|pkg\|occurredAt/120000\|amountMinor\|orderId")`——微信 LiteApp 动态节点会致多指纹，旧实现落多条孤儿 | `:206-210` |
| 哈希前剔除订单号数字 | `sanitizeForHash` | `:203-204` |

### 6.4 跨来源收敛（两路如何合并）

**`mergeSessionEvidence`（`Matcher:2074-2102`）**

收敛条件（**全部满足**）：同会话 + 同 `amountMinor` + （订单号一致 **或** 双方均空）+ `|occurredAt 差| ≤ PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS`（2 分钟）。
命中 ⇒ **不插新行**，把更全字段（merchant / orderId）合并回既有证据并提升 `capturedAt`；`sourceType` 取**后来者**。
未命中（金额或订单号真冲突）⇒ 照常插入，交由候选评估或用户裁决。

> **动机**（`:2066-2068` 注释）：同一笔支付常同时产生"截图 AI 兜底"与"无障碍结果页"两条证据，两者 `sourceEventKey` 生成规则不同，**按主键查重挡不住** ⇒ 会在同一会话留下两条同额证据，前端显示成"两个金额"。

### 6.5 支付结果页到底会不会走 AI 截图识别？（实测裁定）

**结论：会，但只有一条专用兜底路径，且该路径的证据永不自动入账。**

**会：结果页截图 AI 兜底**（`maybeCapturePaymentResultScreenshot`，`:1654`）

调用点 4 处，**全部在文本主路径拿不到证据的分支**：

| 调用点 | 场景 |
|---|---|
| `:1455` | 节点树为空 |
| `:1555` | 解析结果 = `Failure` |
| `:1564` | 解析结果 = `NotPaymentResult` |
| `:1640` | 整轮扫描 `!sawResultPage` |

AI 调用：`CheckoutAiVisionClient.recognizePaymentResult()`（`:591`）——**与结算页 `recognize()`（`:377`）是两个独立方法、两套 prompt**（结果页用 `buildPaymentPrompt` `:830`；结算页用 `buildPrompt` `:1181`，`PROMPT_VERSION = "checkout-v1"` `:257`）。请求键 `sha256("payment-result-screenshot\|pkg\|分钟")`（`:1790-1792`）。

**五道闸门（全部通过才允许截图）**

| 序 | 闸门 | 值 / 锚点 |
|---|---|---|
| 1 | 本轮观察到敏感页 | 禁止，不受节流影响（`:1660-1663`） |
| 2 | **无支付交接上下文** | 禁止——防把聊天记录/朋友圈编码外发（`:1668-1671`） |
| 3 | 敏感页锁存窗口 | `SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS = 60_000L`（**60 秒**，`:75`、`:1674`） |
| 4 | 节流 | 在飞 / 距上次 < 15s / 成功后 < 90s（`:1682-1688`） |
| 5 | 环境守卫 | API 30+（`:1691`）、当前前台必须是支付包（`:1700-1706`）、AI 客户端可用（`:1708`） |

另有看门狗 `PAYMENT_SCREENSHOT_FALLBACK_WATCHDOG_MILLIS = 8_000L`（`:78`、`:1716-1722`）——MIUI 等系统静默丢弃 `takeScreenshot` 回调时强制复位 in-flight。

**永不自动入账（三重保险）**

| 保险 | 实现 | 锚点 |
|---|---|---|
| ① 证据标记为未证实 | `paymentStatus = PAYMENT_STATUS_AI_UNVERIFIED`（`"SUCCESS_AI_UNVERIFIED"`），注释明示"截图 AI 只看到一张图，无法证明支付真的完成" | `Service:1855` |
| ② 落库带 `aiUnverified = true` | `recordPaymentResultEvidence(..., aiUnverified = true)` | `Service:1874`；Matcher `:719` 据此写状态 |
| ③ 决策守卫强制置冲突原因 | `resolveConflictWithAiUnverifiedGuard`（`Matcher:78-85`）在**两条自动入账路径**（`:889`、`:1286`）把 `conflictReason` 置为 `AI_UNVERIFIED_PAYMENT` ⇒ 只能转待确认 | `Matcher:56`/`:83` |

> 守卫注释原文（`Matcher:70-72`）："截图 AI 只凭一张图推断支付完成，可能把历史账单、聊天凭证或未提交的收银台读成本次支付。**即使金额/订单号/时间三项校验全部通过，也不允许自动写入账本**。"
> 测试钉死：`AiUnverifiedPaymentGuardTest.kt`。相关提交：`0bd7824`（截图 AI 推断的支付结果不再自动入账）、`75415b5`（禁止敏感支付页触发截图 AI 兜底）。

**两道与文本路径共用的闸门**：30 分钟时效（`:1831`）、**共用去重键**（`:1839-1842`，与无障碍结果页同键 ⇒ 两条路径识别同一笔只落一条）。

**不会：另两条 AI 路径都到不了结果页**

| 路径 | 为什么到不了 |
|---|---|
| **结算快照 AI**（`recognize()`） | 必须先 store 进 buffer 且过 target 门禁：`CHECKOUT` target 只收角色 `CHECKOUT`；`USER_RETRY` 只收 `canBufferUserRetrySnapshot()`（`SHOPPING_HOME`/`PRODUCT_DETAIL`/`CART`/`CHECKOUT`/`UNKNOWN`）。**`PAYMENT_RESULT` 两个集合都不在**（`CheckoutContract.kt:21-30` + `Buffer:264-275`） |
| **用户断言通道**（点击悬浮按钮） | 只剩三道门禁：敏感页锁存 + **前台购物包白名单** + 截图 API（`Service:2534-2537` 注释自证）。`currentForegroundShoppingPackage()`（`:2515-2526`）对微信/支付宝返回 `null` ⇒ `:2560-2566` 直接 `REJECTED / NOT_CHECKOUT_PAGE` |

**裁定汇总**

| 场景 | 会 AI 截图 | 能自动入账 |
|---|---|---|
| 结果页文本可解析 | ❌（主路径不截图） | ✅（真实 `SUCCESS`） |
| 结果页空树/未命中 **且** 有交接上下文 **且** 无敏感页 | ✅ 兜底截图 | ❌ 强制待确认 |
| 结果页空树 **且** 无交接上下文 | ❌ | — |
| 用户在结果页点悬浮按钮 | ❌（非购物包被拒） | — |
| 购物 App 内结算页 | ✅ 结算快照 AI | 视证据（不属本兜底） |

### 6.6 自动入账判定链（`CheckoutPaymentMatcher`）—— 修正版完整链路

> 常见误解题：以为"AI 识别 + 支付证据匹配上 ⇒ 自动入账"。实际**匹配只是必要条件**，之后还有两道闸。

`autoCommitMatchedCandidate`（`Matcher:1493-1721`）的实际前置与闸门：

| 序 | 闸门 | 判据 | 锚点 |
|---|---|---|---|
| 0 | **AI 候选必须存在** | 候选需带 `aiOccurredAt`。`EVIDENCE_ONLY` 占位候选 `aiOccurredAt = null` ⇒ `evaluate` 必报 `TIME_MISSING` ⇒ 只能待确认。**没有 AI ⇒ 一定不自动入账** | `:751-772`、`:2021-2023` |
| 1 | 幂等：已终态 | 旅程已 `AUTO_COMMITTED`/`COMPLETED` ⇒ 直接返回 | `:1500-1502` |
| 2 | 幂等：Bill 已存在 | 主键 `bill:<sessionId>` 确定性生成 ⇒ 只补会话状态，不写第二笔 | `:1503-1510` |
| 3 | 幂等：用户已手动确认 | `Bill.originalTransactionId == sessionId` ⇒ 跳过自动写 Bill | `:1513-1516` |
| 4 | 候选金额非空 | `requireNotNull(candidate.amountMinor)` | `:1517-1519` |
| 5 | **规则引擎时机一** | 候选生成时已标 `ignore` ⇒ 不入账 | `:1520-1523` |
| 6 | **匹配评估**（见下表） | 任一冲突 ⇒ 待确认 | `:1989-2040` |
| 7 | **AI 未证实守卫** | 证据 `paymentStatus == SUCCESS_AI_UNVERIFIED` ⇒ 强制置 `AI_UNVERIFIED_PAYMENT` 冲突 | `:78-85`、`:1286-1289` |
| 8 | **规则引擎时机二**（入账前再跑一次） | `ignore` ⇒ 不写 Bill **且不进待确认**（会话停 `WAITING_ENRICHMENT` 等过期）；`send_to_review` ⇒ 强制待确认；`set_*` ⇒ 补全字段 | `:1524-1560` |
| 9 | 通过 | 写 Bill + 更新余额 + 会话 `AUTO_COMMITTED`（同一事务）；清理同会话待确认 + `PendingChangeHub.emit(removed=true)` 消除系统通知 | `:1690-1721` |

> 第 8 步即 `DECISIONS.md:83` 声明的"规则引擎执行时机：候选生成时 + 入账前都执行"。

**匹配评估 `evaluate()` 的四项判据（`:1989-2040`）**

| 判据 | 通过条件 | 冲突码 |
|---|---|---|
| 金额（**单向**） | `candidate.amountMinor >= evidence.amountMinor`（AI ≥ 支付，优惠券场景放行）；**AI < 支付才是冲突** | `AI_AMOUNT_LOWER_THAN_PAYMENT`；缺失为 `PAYMENT_AMOUNT_MISSING` / `AI_AMOUNT_MISSING` → ⚠️ **2026-09-12 口径已改（D-19）：双向有界容差** `|AI − 支付| ≤ max(¥1.00, 支付×5%)`；超界转待确认、**以支付金额入账**。**注意跨层**：DAO 检索 SQL 也是单边（`amount_minor >= :amountMinor`），须同步改 |
| 订单号 | 相等 **或 任一侧为空**（部分匹配放行）；两者都非空且不等才冲突 | `ORDER_ID_MISMATCH` |
| 时间 | **两侧都必须有时间**且 `|差| <= PAYMENT_MATCH_WINDOW_MILLIS`（5 分钟）。缺任一侧直接冲突（审查修复，早期 `TIME_PARTIAL` 放行已废除） | `TIME_MISSING` / `TIME_WINDOW_MISMATCH` |
| 平台 | `evidence.platform in DEFAULT_SUPPORTED_PAYMENT_PACKAGES`（微信/支付宝） | `PAYMENT_PACKAGE_UNSUPPORTED` |

> 字段口径：证据 `platform` 取**支付包名**（`:396`/`:467`/`:519`/`:716`）；候选 `platform` 取**购物平台名** `shoppingPlatformName()`（`:756`/`:948`），两者不混用。

**三条证据来源能否自动入账（不等价）**

| 证据来源 | `paymentStatus` | 可自动入账 |
|---|---|---|
| 无障碍结果页文本 | `SUCCESS` | ✅ |
| 支付通知 | `SUCCESS`（`Normalizer:304`） | ✅ |
| 结果页截图 AI 兜底 | `SUCCESS_AI_UNVERIFIED` | ❌ 被守卫强制待确认 |
| 用户断言通道（无证据） | — | ❌ `promoteAssertedSessionToPending` 直接落待确认 |

**"支付成功信号"不等于"任何成功页"——四类被挡**

| 情形 | 处理 | 锚点 |
|---|---|---|
| 支付失败页 | 失败词优先于成功词，不生成成功证据 | `PaymentResultParser:155-158` |
| 结果页显示时间距观察 > 30 分钟 | 判历史回放，静默拒绝 | `PaymentResultParser:168-173` |
| 个人转账（"转账给X"） | 不落消费证据，转待确认 transfer | `PaymentResultParser:182-201` |
| 通知含 收款/到账/转入/红包/提现… | 被 `EXCLUDE_KEYWORDS` 排除 | `Normalizer:86-88` |

**证据可先于 AI 到达**（重要分支，`:744-797`）：支付证据先到时**建 `EVIDENCE_ONLY` 占位候选**（`amountMinor` 填证据金额、`aiOrderId`/`aiOccurredAt` 为空）→ `advanceStatus(WAITING_ENRICHMENT)` → `pendingFromEvidenceOnly` **立即落待确认**（不等 AI）。AI 后续返回时才可能升级为自动入账。**注意：该路径同时会立即弹窗，与原始设计不符，详见 §6.8。**

### 6.7 弹窗/待确认的触发条件全表（订正：本文早前表述易被误读）

> **订正说明**：本节初版只写了"支付证据先到 ⇒ 立即弹窗"，易被误读成"不管 AI 成功失败都弹窗"。**实际上弹窗只在"落了待确认"时发生**；AI 成功且无证据时**不落待确认、不弹窗**。完整全表如下。

**何时落待确认 / 何时弹窗（全部实测）**

| 情形 | 落待确认 | 弹窗 | 锚点 |
|---|---|---|---|
| AI 识别成功 + **无**支付证据 | ❌ | ❌ **静默等支付证据**（`advanceStatus(WAITING_PAYMENT)`） | `Matcher:1194-1206` |
| AI 成功 + 有证据 + 无冲突 | ❌（直接自动入账） | ❌ | `:1290-1292` |
| AI 成功 + 有证据 + 有冲突 | ✅ | ✅ | `:1295-1301` |
| AI 失败 + **有**支付证据 | ✅（金额取证据） | ✅ | `:1124-1133` |
| AI 失败 + **无**支付证据 | ✅（**`amountMinor = 0L`**） | ~~❌ 被 `ZERO_AMOUNT` 跳过~~ → ⚠️ **2026-09-12（D-21）：改为允许弹窗**（弹窗本身可编辑金额），并要求确认前补金额>0；**前置必改**：断言排除条件须集合化，否则"断言+金额缺失"会误弹 | `:1137-1157` + `PendingPromptPolicy:82` |
| **支付证据先到（AI 未返回）** | ✅ | ✅ **立即**（见 §6.8） | `:1760-1786` |
| 断言通道（用户点击，无证据） | ✅ | ❌ 被 `USER_ASSERTED` 静默化 | `PendingPromptPolicy:75-80` |
| 孤立证据（无会话兜底） | ✅ | ✅（有金额时） | `:286-288` |

⇒ **AI 识别成败本身不是弹窗的判据**；判据是"是否产生了待确认"+"该待确认是否通过策略门禁"。

### 6.8 与原始设计的偏差：证据先到**不应**弹窗（应等 AI 定论 5~15 秒）

**这是本次发现的一处实现与设计不符，非观感问题。**

**设计原文**（`NEW_PROJECT_KICKOFF_V2.md:1357-1364`，原始设计基线）

```text
支付证据先到、AI 仍在 ARMED/IN_FLIGHT
  -> 若有未过期快照，允许一次 PAYMENT_NOTIFICATION_FALLBACK 触发
  -> 写入 PAYMENT_CONFIRMED，不弹窗              ← 设计明确要求"不弹窗"
  -> 进入 WAITING_ENRICHMENT，等待 5~15 秒        ← 明确的等待窗口
  -> AI 返回且字段完整：自动入账
  -> AI 超时/字段缺失/金额冲突：发送 onReviewRequired   ← AI 定论后才 review
```

同文件 `:1366` 呼应："AI 已返回但支付证据尚未到时，只保存 `WAITING_PAYMENT` 候选，不显示'已支付'或创建正式账单。"（**该条实现与设计一致**，见上表第 1 行。）

**实测实现**（`Matcher:744-797`）：证据先到 ⇒ 同一事务内 `pendingFromEvidenceOnly()` + `upsertPendingForSession()` ⇒ `PendingChangeHub.emit(isNew = true)` ⇒ 弹窗**立即触发**。**全程没有任何"等待 5~15 秒 AI 定论"的中间态。**

**精确诊断：设计要区分两件事，实现用一个事件同时驱动了**

| 设计意图 | 实现现状 | 是否符合 |
|---|---|---|
| 待确认**记录**立即落库（落袋优先，不丢） | 立即落库 ✅ | ✅ 符合 |
| 待确认**弹窗**等 AI 定论后再决定 | 与落库同一 `emit(isNew=true)` 事件驱动，立即弹窗 | ❌ **不符** |

根因：`PendingChangeHub.emit(isNew = true)` 同时驱动"待确认中心显示"（`PendingConfirmOverlayManager`）与"系统通知"（`PendingReminderNotifier`），**缺少一个"AI 定论前不打扰、只落袋"的中间态标记**。代码注释（`:748-750`）只说"让待确认中心立刻显示"，并未主张"立刻弹窗"——即意图是前者，实现连带做了后者。

**与 `qa:175` 拍板的关系（不矛盾）**：`qa:175` 的拍板"~~支付结果页或支付通知才是入账/弹窗的合法触发器~~"解决的是**触发权归属**（排除用户断言通道），**不涉及时序**。本节讨论的是**时序**（证据先到时应否等 AI）。两条并存才完整：弹窗须由支付证据触发 **且** 应等 AI 定论。

**影响面（需评估）**：AI 通常 3~20 秒返回（`CheckoutAiVisionClient` 单次 15s / 总计 32s）。偏差窗口 = 用户「先看到弹窗」到「AI 返回」之间的这段时间；最坏情况（AI 走到 32s 超时）弹窗会比设计早出现约半分钟，且此时弹窗内容不完整（`productDescription`/`categoryId` 均为空）。若 AI 最终判定无冲突，还会**自动入账并清理该待确认**——用户可能经历"弹窗出现 → 未及操作 → 被自动核销"。

**顺带核实**：已显示的弹窗在待确认被清理时**不会自动撤下**——`PendingConfirmOverlayManager.onPendingChange` 对 `info.removed` **无处理分支**，且开头即 `if (overlayView != null \|\| waitingCandidate != null) return`（`:160-163`）⇒ 陈旧弹窗会留到用户操作或 60s 自动消失（`AUTO_DISMISS_DELAY`）。

### 6.9 多订单正确性推演：同 App 短时间连发两单，AI 能否对应正确的订单？

**结论：设计是"一会话一订单"，完整走"支付→返回"的正常路径时能对应；但"同 App 短时间连发两单"是当前链路最脆弱的形态，且入账金额取 AI 候选金额（非证据金额）使错配后果加重。**
> ⚠️ **2026-09-12 更新（D-19）**：**入账金额已裁定取支付证据金额** ⇒ "错配后果加重"这一半已缓解（金额至少是对的）；剩余问题集中在 **Y4（会话不重置）**，其**修法已定案**（G6.3 Q3：金额变化即新会话 + 时间上限）。另：**v1 通知链路已停用**（D-3），涉及通知的验证场景不再属 v1 范围。

**设计上的三层隔离（能对应的理由，均实测）**

| 层 | 机制 | 锚点 |
|---|---|---|
| 会话层 | 新指纹 **且** 无保留候选 ⇒ 新会话 + `buffer.discard(旧槽位, "NEW_CHECKOUT_SESSION")` | `Coordinator:1428-1450` |
| 请求键层 | `checkout\|pkg\|sessionId\|pageFingerprint` —— AI 请求与会话/页面绑定 | `CheckoutContract.kt:315-322` |
| 证据层 | 证据落库绑定**交接引用的 sessionId**，按会话查重/合并 | `Matcher:744-746`、`:2074` |
| 交接返回 | 返回**另一个**结算页 ⇒ `HANDOFF_RETURNED_TO_OTHER_CHECKOUT` 丢弃旧槽位 | `Coordinator:1415` |

**四处风险（同 App 连发两单）**

| # | 风险 | 机制 | 锚点 |
|---|---|---|---|
| 1 | **会话不重置（= Y4）**：订单 2 的结算页被判"漂移沿用" ⇒ 不重截，AI 消费的仍是订单 1 的截图；订单 2 的证据落**会话 1** | `hasRetainableCandidate` 只查"同包 + 槽内有本会话任意候选"，不看角色/时间/相似度 | `:1424-1431`、`:1333-1350`、`:1503-1507` |
| 2 | **5 分钟时间窗拦不住连发错配**：两单都在窗内 ⇒ `TIME_WITHIN_WINDOW` 放行。该窗口防"跨时段错配"，不防"连发错配" | `PAYMENT_MATCH_WINDOW_MILLIS = 5 min` | `:25`、`:2025` |
| 3 | **入账金额取 AI 候选金额，非证据金额**：`amountMinor = requireNotNull(candidate.amountMinor)` → `BillEntity(amountMinor = amountMinor)` | 金额规则单向（AI ≥ 证据放行）叠加 ⇒ **订单 2 更便宜时错账** | `:1517`、`:1605-1607` → ⚠️ **2026-09-12 已裁定（D-19）：改为取支付证据金额**（并配双向容差）。⇒ 本条风险性质由"错记"收敛为"少记"（更易被用户发现） |
| 4 | **结果页去重键撞车**：同包名 + 同一 2 分钟桶 + 同金额 + 订单号双方都空 ⇒ 同键，第二单证据被判重丢弃（内存保留 60s）→ ⚠️ **2026-09-13 已裁决修复（第二轮核对·矛盾1，选甲）**：时间桶 2 分钟 → **30 秒**（`IMPL_PLAN` §2.6），本风险随之消除 | `sourceEventKey = sha256("result-event\|pkg\|occurredAt/120000\|amountMinor\|orderId")` | `PaymentResultParser:208-210`、`Service:1887-1895` |

**最坏情形推演（订单 1 ¥100 → 订单 2 ¥30，同 App、间隔 < 5 分钟、Y4 触发）**

| 步 | 事件 | 判定 |
|---|---|---|
| 1 | 订单 1 结算页截图 → 会话 1 锚定，AI 识别 ¥100 | 正常 |
| 2 | 订单 1 支付 → 证据 ¥100 → 四项判据过 → 自动入账 ¥100 | 正确 |
| 3 | 订单 2 结算页（指纹变）→ `driftWithinSameCheckout` ⇒ **DUPLICATE，不重截** | Y4 命中 |
| 4 | 订单 2 支付 → 证据 ¥30 落**会话 1** | 证据绑会话 |
| 5 | `evaluate(候选 ¥100, 证据 ¥30)`：100 ≥ 30 ⇒ 放行；订单号双方空 ⇒ 放行；时间窗内 ⇒ 放行 | **全过** |
| 6 | `autoCommitMatchedCandidate`：Bill 已存在（订单 1 的 `bill:<sessionId>`）⇒ **幂等跳过，不写第二笔** | `:1503-1510` |

⇒ **订单 2 的 ¥30 支出静默丢失**（无账单、无待确认——除非另有分支）。若两单金额相同，则第 5 步同样全过、第 6 步同样被幂等挡下（金额对但商品描述沿用订单 1，且只记一笔）。若订单 2 **更贵**（¥150 > AI ¥100），`AI_AMOUNT_LOWER_THAN_PAYMENT` 冲突 ⇒ 转待确认（安全）。

> **注意分界**：金额规则的单向性使"订单 2 ≤ AI 金额"全部放行，而幂等挡下第二笔 Bill ⇒ **少记**；"订单 2 > AI 金额"才被拦 ⇒ 转待确认。**少记比错记更难被用户发现。**

**缓解因素（现实中的保护）**
- 完整走"支付 → 返回购物 App"路径时，`handoffCheckout` 保留 + 返回不同结算页 ⇒ 旧槽位被丢弃、会话重置（`:1415`）——**主流"下单→付款→回来再买"流程大概率走这条**
- PDD/京东结算页通常携带订单号 ⇒ `selectEvidence` 按订单号精确匹配（`:2049-2053`）
- 断言通道手动兜底 + 待确认中心落袋

**建议的真机验证场景（一次覆盖风险 1/3/4）**：同 App（拼多多）5 分钟内连续两单，**金额相同**与**金额递减**各做一次，全程不点悬浮按钮；观察点：① 第二单结算页是否新建会话/重截（应建而未建 = Y4 实锤于多订单场景）② 第二单是否产生账单 ③ 若产生，金额/商品描述是否对应第二单 ④ `markPaymentResultEvent` 是否出现"同一事件已处理"把第二单证据吞掉（⚠️ **2026-09-13：D-6 已裁决收窄为 30 秒桶** ⇒ 预期改为"两笔证据均落库、第二单走归属/派发路径"）。

**偏差的实现链路（逐步锚点）**

| 步 | 动作 | 锚点 |
|---|---|---|
| 1 | 证据先到、候选不存在 ⇒ 建 `EVIDENCE_ONLY` 占位候选 | `Matcher:744-797` |
| 2 | `pendingFromEvidenceOnly()` 建待确认：`type="expense"`、`amountMinor=证据金额`、**`conflictReason = null`**、**`sourceType` 继承证据来源**（`"notification"` / `"accessibility"`） | `Matcher:1760-1786` |
| 3 | `upsertPendingForSession()` 首次插入 ⇒ `PendingChangeHub.emit(isNew = true)` | `Matcher:1844-1851` |
| 4 | `PendingConfirmOverlayManager.onPendingChange()` 订阅该事件 → 调 `PendingPromptPolicy.evaluate()` | `PendingConfirmOverlayManager:171-178` |
| 5 | `Decision.Prompt` ⇒ `recordPrompted()` + `maybeShowCandidate()` ⇒ **弹窗** | `:184-192` |

**为什么不会被"用户断言静默化"挡掉**：`PendingPromptPolicy` 的跳过条件（`:75-80`）要求 **`sourceType == AI_VISION` 且 `conflictReason == USER_ASSERTED` 同时成立**。EVIDENCE_ONLY 待确认的 `sourceType` 是 `notification`/`accessibility`（继承证据来源），**不是 `ai_vision`** ⇒ 不跳过 ⇒ 正常弹窗。

**只受两个软限制**

| 限制 | 值 | 锚点 |
|---|---|---|
| 去重：同商户 + 同金额 | 5 分钟内只弹一次（`DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L`） | `PendingPromptPolicy:19`、`:84-89` |
| 频控：滑动 1 小时 | 最多 10 次（`HOURLY_PROMPT_LIMIT = 10`） | `:22`、`:90-93` |

**AI 返回后的行为**：同一条待确认走 `upsertPendingForSession()` 的 **update 分支** ⇒ `emit(isNew = false)` ⇒ 弹窗侧命中 `UPDATED_NOT_NEW` 跳过、通知侧"内容更新事件"跳过 ⇒ **不重复打扰，只刷新内容**（商品描述 AII 产出后补上）。

**并行还有系统通知**：`PendingReminderNotifier` 订阅**同一** `PendingChangeHub` 事件，其 USER_ASSERTED 判据同样要求 `AI_VISION`（`:85-91`）⇒ EVIDENCE_ONLY **也会发系统通知**。两者是**独立开关**（弹窗 `pending_confirm_overlay` / `enabled`；通知各自 PREFS）与**独立节流**（通知另有 `MIN_NOTIFY_INTERVAL_MILLIS` 最小间隔）。⇒ 支付证据先到时会**弹窗 + 系统通知同时来**。

**三个连带行为（产品权衡点）**

| # | 行为 | 锚点 |
|---|---|---|
| 1 | **弹窗内容此时不完整**：`productDescription = null`（AI 才产）、`categoryId = null`（规则引擎未跑）⇒ 用户立刻确认会得到较粗糙的账单 | `Matcher:1758`（`productDescription = null`）、`:1780` |
| 2 | **弹窗不叠加**：已有弹窗或挂起候选时直接 `return` ⇒ 连续两笔支付，**第二笔当场不弹** | `PendingConfirmOverlayManager:160-163` |
| 3 | **敏感页挂起最多 15s**：当前在密码键盘/付款码页时挂起等非敏感帧，超时**放弃弹窗、退回系统通知**；弹窗自动消失 60s | `:219-227`（`CANDIDATE_TTL_MILLIS = 15_000L`）、`:57`（`AUTO_DISMISS_DELAY = 60_000L`） |

**通知证据**：`PaymentNotificationService` + `PaymentResultNormalizer`，金额统一用**分**（`minor units`，`MAX_SAFE_MINOR_UNIT` 防跨服务 Double 精度歧义）；通知关键词排除收款/到账/转入/红包/AA/提现（**注意：与批 3"红包要做"决策冲突，见冲突清单 B5**）。
> ⏸ **2026-09-12：v1 已停用通知链路（D-3）** —— 采集/解析/分类**代码全部保留**（便于新版本复用），但**不参与匹配与入账**：在证据落库入口按 `sourceType == "notification"` 短路，并同步纳入"孤儿待确认"入口。⇒ 上述"红包条款冲突（B5）"随之**推迟到新版本启用通知时**处理。

---

## 七、状态机与持久化

### 7.1 双层状态

| 层 | 枚举 | 值数 | 存储列 | 对外 |
|---|---|---|---|---|
| 公开 | `SessionStatus`（`CheckoutContract.kt:147-155`） | 8 | `status` | 序列化给 RN |
| 内部 | `CheckoutJourneyState`（`:191-207`） | 16 | `journey_state` | **不直接序列化** |

映射由 `toPublicSessionStatus()`（`:272-289`）完成，阶段四 T1 起不得新增第 9 个公开值。`DISMISSED` 兼容映射为 `CANCELLED`（`:176`）。

**终态**：`COMPLETED` / `FAILED` / `CANCELLED` / `EXPIRED`（`:159-164`）。

### 7.2 16 态迁移表（`:209-266`，实测）

| 当前 | 可迁移到 |
|---|---|
| `CHECKOUT_DETECTED` | SNAPSHOT_BUFFERED, CANCELLED, FAILED, EXPIRED |
| `SNAPSHOT_BUFFERED` | AI_TRIGGER_ARMED, PAYMENT_CONFIRMED, WAITING_ENRICHMENT, CANCELLED, FAILED, EXPIRED |
| `AI_TRIGGER_ARMED` | AI_PENDING, PAYMENT_CONFIRMED, WAITING_ENRICHMENT, CANCELLED, FAILED, EXPIRED |
| `AI_PENDING` | AI_COMPLETED, AI_FAILED, WAITING_PAYMENT, PAYMENT_CONFIRMED, CANCELLED, FAILED, EXPIRED |
| `AI_COMPLETED` / `AI_FAILED` | WAITING_PAYMENT, PAYMENT_CONFIRMED, WAITING_ENRICHMENT, NEEDS_CONFIRMATION, FAILED, EXPIRED |
| `WAITING_PAYMENT` | PAYMENT_CONFIRMED, CANCELLED, FAILED, EXPIRED |
| `PAYMENT_CONFIRMED` | AI_PENDING, WAITING_ENRICHMENT, AUTO_COMMITTED, NEEDS_CONFIRMATION, COMPLETED, FAILED, EXPIRED |
| `WAITING_ENRICHMENT` | AI_PENDING, AI_COMPLETED, PAYMENT_CONFIRMED, AUTO_COMMITTED, NEEDS_CONFIRMATION, FAILED, EXPIRED |
| `AUTO_COMMITTED` | COMPLETED |
| `NEEDS_CONFIRMATION` | AUTO_COMMITTED, COMPLETED, DISMISSED, CANCELLED |
| `COMPLETED` / `DISMISSED` / `CANCELLED` / `FAILED` / `EXPIRED` | **（终态，不可迁出）** |

> 执行方 `advanceStatus`（`Matcher:1439-1460`）**在非法迁移时静默 `return session`**（`:1450-1452`）——无日志、无异常（冲突清单 C5）。

### 7.3 审批门禁

`CheckoutSessionDao.kt:32`/`:58`/`:77` 与 `TransactionCandidateDao.kt:63`/`:82` 均以 `status NOT IN ('COMPLETED','CANCELLED','FAILED','EXPIRED')` 过滤活跃会话；`LedgerRepository.kt:56` 同集合。

### 7.4 安全与隐私约束（实测）

| 约束 | 实现 |
|---|---|
| 截图为内存态 | `CheckoutSnapshot.imageBytes` 不入 Room/文件/日志/RN（`Buffer:23-27`） |
| 敏感页拦截 | 角色判定第一优先级拦 `paymentHandoffTerms`；门禁第 5 道；store 第 3 道；`canBufferUserRetrySnapshot` 排除 |
| 日志脱敏 | 诊断名只输出"事件类型 + 购物包名 + 12 位截断指纹"（`Coordinator:144-152`），页面文本/金额/订单号一律不进日志 |
| 数据库加密 | SQLCipher 默认加密，主密钥由 Keystore 派生（`DECISIONS.md:100`） |
| 支付证据入参 | `PaymentNotificationFallbackSignal` 不含通知原文/页面文本/图片，只有脱敏哈希 |

---

## 八、与冲突清单的对应关系（方案性弱点）

| 方案模块 | 弱点 | 冲突清单编号 |
|---|---|---|
| §2.1 会话重置判据 | 不含"页面角色变化"维度、无时间上限/相似度下限 ⇒ 跨页污染 | `C1`（🔴） |
| §1.4 Y3 排除 | 动作信号一票否决 ⇒ 弹层隐藏节点带动作词时排除失效 | `C2`（🟠） |
| §1.2 / §1.6 双通道 | 信号侧有无标签金额兜底、指纹侧护栏只用标签词 ⇒ 判定分裂 | `C3`（🟠，本次新发现） |
| §1.6 L3 | 400 上限静默不追踪 + 15s 窗口跨层污染 + 位置键退化/共键 | `C4`（🟠） |
| §7.2 迁移执行 | 非法迁移静默 return；LedgerRepository 直写终态；`CheckoutJourney` 死类 | `C5`（🟠） |
| §2.3 排队机制 | 已移除但 `drainQueuedCapture()` 仍留 5 处调用 | `C6`（🟡） |
| §2.4 断言通道 | 未纳入 5.3 `HandoffStateHolder` 收敛；`USER_RETRY` 清墓碑与批 4 耦合 | `C7`（🟡） |
| §3.2 `EXPIRED_RETRY` | 死状态（无产出方） | `A1`（🟡） |
| §3.1 模式缺省 | 持久化缺省 `always_on` vs `parse()` 缺省 `CHECKOUT_ONLY` | 历史 Y2，未闭合 |
| §4.1 提交检测 | `isVisibleToUser` 硬条件，无类名兜底 | `D1` 缺陷根因 |

---

## 九、附：可复现取证命令

```bash
cd /d/JotBill/jotbill-app/android/app/src/main/java/com/jotbill/app

# 页面认定链
sed -n '112,145p'  recognition/CheckoutPageDetector.kt        # 6 道门禁
sed -n '533,578p'  recognition/AccessibilityPageSummaryCollector.kt  # 角色优先级
sed -n '272,283p'  recognition/AccessibilityPageSummaryCollector.kt  # Y3 排除
sed -n '341,349p'  recognition/AccessibilityPageSummaryCollector.kt  # 指纹公式
cat recognition/VolatileTokenTracker.kt                        # L3 全文

# 截图链
sed -n '1352,1540p' recognition/CheckoutSnapshotCaptureCoordinator.kt  # handleStable
sed -n '1333,1350p' recognition/CheckoutSnapshotCaptureCoordinator.kt  # 漂移沿用判据
sed -n '256,320p'   recognition/CheckoutSnapshotBuffer.kt              # store 判定顺序
sed -n '687,701p'   recognition/CheckoutSnapshotBuffer.kt              # 消费优先级

# 稳定窗口与门禁
sed -n '3203,3300p' recognition/CheckoutAccessibilityService.kt
sed -n '2953,2979p' recognition/CheckoutAccessibilityService.kt        # SUBMIT_ACTION
cat recognition/CheckoutSubmitActionDetector.kt
```
