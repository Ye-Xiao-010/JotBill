# 版本 B 实现方案（送 AI 触发点后移 · 支付过程槽位 · Y4 修法）

> 文档状态：**实现设计（待确认）** —— 本文件回答的是"**代码怎么改**"，区别于 `NEW_PROJECT_KICKOFF_V2.md`（回答"设计是什么"）。
>
> 编制日期：2026-09-13
>
> 设计依据：`docs/CONFLICT_REVIEW_SESSION_2026-09-11.md`（G1-3 / G1-4 / G1-5 / G1-6 / G4.x / G5.x / G6.3）+ `NEW_PROJECT_KICKOFF_V2.md` 0.3.0
>
> 代码基线：`recognition/` 目录；所有行号为本次实测值，名称均可唯一定位。
>
> **适用范围**：本方案覆盖 22 条代码评估清单中**尚缺实现规格的 3 项结构性改造**（触发点后移 / 支付过程槽位 / Y4），**外加 1 项由决策点 4 新确认的改造**（落库顺序，见 §2.4）。其余 8 项（D-9 / D-10 / D-11 / D-16 / D-19 / D-20 / D-21 / 死入口清理）规格已完整，可直接开工，不在本文重复。

---

## 0. 一句话结论

五项改造中，**②支付过程槽位是 ①的物理前提**（没有可存活的图，后移的 AI 触发点必然拿不到图）；**③Y4 是 ②的语义前提**（同包换单时不能让新会话顶掉支付中的图）；**⑤`handoffContext` 映射化与 ②同批**（两者共用 `sessionId` 键空间，分开实施会出现不可测的中间态）；**④落库顺序是 ①的语义补全**（AI 能派发之后，"何时打扰用户"才有意义）。

⇒ **实施顺序：③Y4 → ②槽位 + ⑤映射化 → ①触发点后移 → ④落库顺序**（与直觉相反）。

### 0.1 战略裁决（2026-09-13）：甲路线 —— 版本 B 先行，journey（ROADMAP 批 4）重新定位

> 用户裁决"选择甲"。要点：
> 1. **本方案批次先行**。批次编号由"批 1~5"更名 **V-1~V-5**，消除与 ROADMAP"批 4 = journey 重构"的撞名（两套文档此前各有一个"批 4"，含义不同）。
> 2. **批 V-2 的"页面金额字段"按 `docs/tdd/08_phase4_journey_spec.md` §3.1 口径设计**（订单总额、分；订单号可见时优先为身份）——它是改造 C 金额判据与 journey 锚点的**共享基建**，journey 将来落地直接复用。
> 3. **改造 C 的金额判据 + 时间上限定位为过渡期边界机制**：journey 落地时由"锚点身份"取代（启发式届时退役，金额提取保留）。
> 4. **journey（ROADMAP 批 4）重新定位而非取消**：暂缓编码，待版本 B 落地后以实测数据重新评估剩余价值（其难题清单的"并发两笔/会话身份"大半被改造 B/C/E 覆盖）；`roadmap/batch4-journey` 分支**冻结**（不删除、不继续开发）；其"顺手修"三缺口（advanceStatus 静默 / LedgerRepository 直写 / CheckoutJourney 死类）**不随 journey 悬置**，择批吸收。裁决注已同步 `ROADMAP` 第六节与 journey 规格头部。

---

## 1. 现状：三条链路的真实结构（代码实测）

### 1.1 AI 派发链（唯一入口）

```
IntentCoordinator.trigger(intent, fingerprint, callback, paymentNotification)   :204
  └─ routeLocked(intent, ...)                                                    :360-524
       ├─ 门禁 1  contextFor(intent) 解析上下文                                  :331-339
       ├─ 门禁 2  context.pageRole != CHECKOUT && !isInternalNonCheckoutRetry
       │            ⇒ NOT_CHECKOUT_PAGE                                          :370-377
       ├─ 门禁 3  context.isSensitivePage ⇒ SENSITIVE_PAGE                       :378-385
       ├─ 门禁 4  通知回退信号校验 ⇒ INVALID_PAYMENT_NOTIFICATION                :399-412
       ├─ 门禁 5  HANDOFF 只许一次 ⇒ PAYMENT_HANDOFF_ALREADY_ATTEMPTED           :413-420
       ├─ 门禁 6  会话请求预算 ⇒ REQUEST_BUDGET_EXHAUSTED                        :436-446
       └─ buffer.consumeForAi(slotId, intent, fingerprint)  ← 消费快照           :460-464
  └─ deliver(routed, consumer)                                                   :586
```

**要点**：
- **自动通道的 AI 只能经此入口派发**（`consumeForAi` 是自动通道"图→请求"的出闸）。绕过它 = 绕过预算/去重/回调。
  > ⚠️ **更正（2026-09-13，见 `DESIGN_REVIEW_2026-09-13.md` R2）**：本节初稿曾写"AI **只能**经此入口派发、`consumeForAi` 是**唯一**出闸、**不可另开旁路**"——**该判断不成立**。实测 `consumeForAi` 有**两个消费方**：
  > 1. 本入口（coordinator，`routeLocked:460-464`）；
  > 2. **`Service.performUserAssertedCapture` 直接调用**（`:2739`）→ `dispatchReadyToAi`（`:2755`），**完全绕过 coordinator**（不经过门禁、不消耗 `attemptsBySession` 预算）。
  >
  > ⇒ **断言通道是独立旁路，且应保持独立** —— 这正是"用户断言绕过页面分类"（G2.3 Q4 保底语义）的实现方式。**改造 A 的门禁放宽只作用于 coordinator 路径，不得触及断言通道的独立派发。**
  >
  > ⇒ **AI 派发共 4 条路径**（自动通道 2 条 + 手动通道 2 条），完整清单与外发约束见 `DESIGN_REVIEW_2026-09-13.md` R1。
- **门禁 2 是版本 B 的头号障碍**：支付成功时前台是微信/支付宝，上下文来自 `handoffContext`，其 `pageRole` 可能是 `PRODUCT_DETAIL`/`CART`（宽进后三类均可截）⇒ 会被 `NOT_CHECKOUT_PAGE` **直接拒掉**。
- `consumeForAi` 一旦成功 ⇒ 图**立即被消费**（`CALLER_MUST_RELEASE`）⇒ 无法再撑到"支付成功之后"。

### 1.2 交接链（现有"支付过程挂起"机制）

```
IntentCoordinator.onEvent(Invalidated)                                          :160-201
  ├─ reason == PAYMENT_APP_HANDOFF && 前台是白名单支付包 ⇒ isPaymentHandoff      :168-171
  ├─ activeContext → handoffContext（挂起，不清空）                              :179-183
  ├─ handoffFingerprint = retained?.pageFingerprint                             :187-188
  └─ trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, handoffFingerprint)  ← 现在：消费+派AI  :199
```

**要点（关键发现）**：
- **`handoffCheckout` 已经跨交接保存了 `ActiveCheckout`**（含 `sessionId` / `slotId` / `pageFingerprint`），并由 `SnapshotCoordinator.currentPaymentHandoffReference()`（`:1256-1273`）对外提供**不含图片**的 `CheckoutSnapshotReference`。
- ⇒ **"哪个会话去了支付"这个信息，现有代码已经在维护了** —— 版本 B **不需要新造"会话↔支付"关联**，只需把 `:199` 的"消费"改成"搬运"。

### 1.3 结果页链（版本 B 的新触发点）

```
Service（支付包观察路径）
  ├─ pageRole == PAYMENT_RESULT 判定                                           :1497-1526
  ├─ PaymentResultParser.parseResult(pageText, ...) ⇒ evidence                 :1531-1536
  ├─ markPaymentResultEvent(sourceEventKey)  ← 事件级去重                       :1573
  └─ scope.launch { recordPaymentResultEvidence(matcher, pkg, evidence, handoffContext) }  :1596-1603
       └─ recordPaymentResultEvidence                                          :2094+
            ├─ 路径一：handoffReference != null ⇒ matcher.recordPaymentEvidence(...)   :2112-2160
            ├─ 路径一兜底：findRecentCheckoutReferenceForPayment(...)              :2121-2142
            └─ 路径二：均失效 ⇒ recordOrphanPaymentSignal(...)（孤儿待确认）        :2143-2144
```

**要点**：
- `handoffContext`（`PaymentResultHandoffContext`，`:312`，由 `capturePaymentResultHandoffContext()` `:2026-2076` 构造）已携带 **`reference`（含 sessionId/slotId/fingerprint）** ⇒ **消费支付过程槽位所需的一切定位信息都已在手**。
- 证据落库目前**直接进匹配链路**（可能立即落待确认 ⇒ 触发弹窗）。**这是 P5 问题的来源，也是版本 B 必须先决策的点**（见 §3 决策点 4）。

### 1.4 槽位与 Buffer 结构

```kotlin
// CheckoutSnapshotBuffer.kt:217-225
private data class SlotState(
    val sessionId: String,                                    // 槽位级，val
    var manualCandidate: CheckoutSnapshot? = null,
    var automaticCandidate: CheckoutSnapshot? = null,
    var nextCandidateRevision: Int = 0,
    var consumedRequestKey: CheckoutRequestKey? = null,
    val expiredPageFingerprints: MutableSet<String> = mutableSetOf(),
    var handoffPageFingerprint: String? = null                // ← 现有"交接栅栏"= 槽位内标记
)
private val slots = mutableMapOf<String, SlotState>()          // key = slotId
```

| 事实 | 位置 | 对版本 B 的影响 |
|---|---|---|
| 槽位 key = `checkoutSnapshotSlotId(shoppingPackage)`（**按购物包**） | `Service:2050` 等 | ⚠️ **同包换单会复用同一槽位** |
| `store()` 中 `existing.sessionId != request.sessionId` ⇒ `releaseCandidates(旧)` + 新建 `SlotState` | `:295-300` | 🔴 **同包新会话会把支付中的图一起释放** |
| `store()` 只覆盖 `manualCandidate` / `automaticCandidate` | `:323-340` | ⇒ **新增的第三类字段天然不被覆盖** ✅ |
| `expiresAt = now + snapshotTtlMillis`（`now` = `store()` 时刻） | `:352` | 起算点 = 截图时刻（待改，见 §2.2） |
| 交接栅栏 `handoffPageFingerprint` 仅**限制消费条件**，**不保护图片存活** | `:476-521` | ⇒ 现有机制**无法**满足版本 B |

---

## 2. 三项改造的设计

### 2.0 前置决策：支付过程图存在哪里

文档 G1-3 的原话是"**新开一个槽位**"（与结算页槽位物理隔离）。经代码核实，**必须新开槽位**，但**key 不能用包名**：

| 方案 | 做法 | 判定 |
|---|---|---|
| **甲** 现有槽位加字段 | `SlotState` 增 `paymentCandidate` | ❌ **不可行**：`store()` 在**同包新会话**时会整体替换 `SlotState`（`:295-300`）⇒ 订单 1 支付中、用户在同 App 下订单 2 ⇒ **订单 1 的图被释放** ⇒ 恰好是最需要保护的多订单场景失守 |
| **乙** 独立槽位，key = 包名 | `payment:<pkg>` | ❌ 同上，仍是"一包一图"，并发两单互相顶替 |
| **丙（推荐）** 独立槽位，**key = sessionId** | `payment:<sessionId>` | ✅ **一单一图，天然不互顶**；且 sessionId 与"一单一会话"前提同源；消费端用 `handoffContext.reference.sessionId` 精确定位 |

**⇒ 采用方案丙**：`slotId = "payment:" + sessionId`。

> ⚠️ **与文档措辞的差异**：G1-3 写的是"新开一个槽位"，未指定 key。方案丙**符合"物理隔离"的设计目标**，且修正了"按包名"会踩的多订单缺陷。**需你确认此 key 选择**（见 §3 决策点 1）。

---

### 2.1 改造 A：送 AI 触发点后移（版本 B）

**目标**：AI 由"支付成功信号"触发，不再由"提交订单/支付应用调起"触发。

#### A-1｜枚举调整（`CheckoutContract.kt:43-49`）

```kotlin
enum class PurchaseIntent(val wireValue: String) {
    // SUBMIT_ACTION            ← 删除（D-11）
    PAYMENT_APP_HANDOFF         // 保留，但**职责改为"搬运"**，不再派 AI
    // PAYMENT_PAGE_VISIBLE     ← 删除（D-10）
    PAYMENT_RESULT              // ⚠️ 新增：**自动通道**唯一 AI 触发器
    // PAYMENT_NOTIFICATION_FALLBACK ← 保留但 v1 停用（D-3）
    USER_RETRY                  // 手动通道（断言 / 待确认中心重新识别）—— 不走 coordinator，见下注
}
```

**新增 `PAYMENT_RESULT` 的理由**：**自动通道**的 AI 必须经 `trigger()→routeLocked()→consumeForAi()` 派发，以复用会话预算、并发去重、结果回调三套机制。若在 Service 里直接调 AI 客户端，将绕过这三套机制 ⇒ **不可接受**。

> ⚠️ **通道边界（2026-09-13 修正，见 `DESIGN_REVIEW_2026-09-13.md` R1/R2）**：上表"唯一触发器"的措辞**仅适用于自动通道**。**实际 AI 派发共 4 条路径**：
>
> | 通道 | 路径 | 触发 | 是否经 coordinator | 是否等支付成功信号 |
> |---|---|---|---|---|
> | 自动 | ① 结果页主路径 | 结果页文本解析成功 | ✅ 经 | ✅ 是（即触发源） |
> | 自动 | ② 结果页截图兜底 | 结果页**文本解析失败** ⇒ 现场截结果页 | ✅ 经 | ✅ 是 |
> | **手动** | ③ **断言通道**（悬浮按钮） | **用户点击** | ❌ **绕过**（`Service:2739` 直接 `consumeForAi`） | ❌ **点击即派发** |
> | **手动** | ④ 待确认中心"重新识别" | 用户点击 | ❌ 直接派发 | ❌ **点击即派发** |
>
> ⇒ **手动通道的派发不受"支付成功信号"约束** —— 用户的显式点击本身就是"该记一笔"的表达（G2.3 Q4 保底语义）。
> ⇒ **改造 A 的门禁放宽（A-2）与 `PAYMENT_RESULT` 接线（A-3）只作用于 coordinator 路径，必须保持断言通道的独立旁路不动**；否则会打断"用户断言绕过页面分类"这一刻意设计。

#### A-2｜门禁 2 必须放宽（`routeLocked:370-377`）

**改前**：
```kotlin
if (context.pageRole != CheckoutPageRole.CHECKOUT && !isInternalNonCheckoutRetry) {
    return Rejected(NOT_CHECKOUT_PAGE)
}
```
**问题**：宽进后 `PRODUCT_DETAIL` / `CART` 也可截；支付成功时上下文来自 `handoffContext`，其角色可能就是这两类 ⇒ 会被误拒。

**改后**：改为"**可截角色集合**"判定：
```kotlin
// ⚠️ 2026-09-13 修正（第二轮核对·矛盾2）：不得复用 canBufferUserRetrySnapshot() ——
// 该集合含 SHOPPING_HOME / UNKNOWN（G2.7 已证为死配置集合），按字面实现会把
// UNKNOWN/首页放进自动通道门禁，违背本节风险表"不放开 UNKNOWN/BROWSING"。
val canDispatchAutoAi = context.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES
// ⚠️ 2026-09-15 定案（依据见 §8.5）：该常量【新增】于 `CheckoutContract.kt`
//    （与 `canBufferSnapshot()` 同处），取值 = {PRODUCT_DETAIL, CART, CHECKOUT}。
//    下方宽进配套 ① 的 `canBufferSnapshot()` 必须【引用同一常量】——
//    否则又会变成"两处各自写死"，与"单一出处"要求相悖。
if (!canDispatchAutoAi && !isInternalNonCheckoutRetry) {
    return Rejected(NOT_CHECKOUT_PAGE)
}
```
> ⚠️ **宽进需改【两处】，缺一不可（2026-09-13 补，见 `DESIGN_REVIEW_2026-09-13.md` R5）**：
> 1. **`canBufferSnapshot()`** —— 当前定义为 `this == CHECKOUT`（`CheckoutContract.kt:21-30`），须扩为 `{PRODUCT_DETAIL, CART, CHECKOUT}`；它是 `Buffer.store()` 的 `targetAllowed` 校验依据（`Buffer:264-272`）。
> 2. **`CheckoutPageDetector.detect()`（`:118-120`）** —— 当前把 `PRODUCT_DETAIL` / `CART` 直接判为 `NOT_CHECKOUT_PAGE`；**若不放宽，自动截图路径在 `handleStable` 处就被拦下，根本走不到 `store()`** ⇒ 只改第 1 处则"宽进"**完全不会生效**。
>
> ⇒ 原代码清单中"核对 `classifyPageRole` 8 级短路顺序在宽进语境下是否仍需调整"一条，应扩充为**同时覆盖 `detect()` 的 Accepted 判定放宽**。

#### A-3｜触发点接线（`Service` 结果页路径）

**位置**：`Service:1596` 的 `scope.launch { recordPaymentResultEvidence(...) }` **之后**。

**改后数据流**：
```
支付成功信号（结果页解析成功 + markPaymentResultEvent 通过）
  ↓
① 落证据（持久化事实，不可丢）
   matcher.recordPaymentEvidence(...)        ← 保留现状
  ↓
② 派发 AI（版本 B 新增）
   coordinator.trigger(
       intent   = PurchaseIntent.PAYMENT_RESULT,
       callback = callback
   )
   // ⚠️ 2026-09-15 修正（依据见 §8.1）：
   //   · 不传 sessionId —— 实测 trigger 签名（Coordinator:204-209）后 3 个参数
   //     均有默认值，原稿"需扩展 trigger 签名"不成立；sessionId 由 coordinator
   //     内部经【归属判定】产出（见 A-4 / §2.5 E-2），不由调用方传入。
   //   · 不传 pageFingerprint —— 消费时改用 handoffContexts[sessionId].pageFingerprint。
   //     原稿的 `reference.sessionId` / `reference.pageFingerprint` 属"单槽 handoffContext"
   //     时代的语义，改造 E 映射化后不成立。
  ↓
③ 结果
   Accepted → 消费支付过程槽位 → 送 AI
   无图/超时 → 落待确认（**不得静默丢弃**，D-4 风险 1）
```

#### A-4｜`trigger` 签名的必要扩展

`trigger()` 现依赖 `contextFor(intent)` 从 `activeContext`/`handoffContext` 取上下文。但**结果页到达时 `activeContext` 已被清空**（`onEvent:181`），只剩 `handoffContext`。

> ⚠️ **2026-09-13 重写（第二轮核对·矛盾3）**：本节原稿"`contextFor` 增 `PAYMENT_RESULT -> handoffContext ?: activeContext`"有双重错误——① 改造E 把 `handoffContext` 改为 `handoffContexts: Map<sessionId, Context>` 后**不存在单一 `handoffContext` 可取**；② `?: activeContext` 本身就是"回退猜测"，与本方案"不回退猜测、直接孤儿待确认"的裁定（E-2/R4）自相矛盾。
>
> **改法（最终版）**：`PAYMENT_RESULT` **不取 `activeContext`**。结果页到达时：
> 1. 按 §9.2 口径在 `handoffContexts` 中做**归属判定**（金额 + 时间窗）→ 产出 `sessionId`（恰好 1 命中）；
> 2. `contextFor(PAYMENT_RESULT)` 返回 `handoffContexts[sessionId]`；消费槽位 = `payment:<sessionId>`；
> 3. **0 命中 ⇒ `recordOrphanPaymentSignal` 孤儿待确认，不猜**；≥2 命中 ⇒ `Ambiguous` ⇒ 待确认。
>
> **连带必改**：现状 `recordPaymentResultEvidence` 的 `findRecentCheckoutReferenceForPayment`"最近活跃会话兜底"（`Service:2121-2142`）就是本方案要消灭的猜测路径 ⇒ **随改造E 一并摘除**（0 命中直接走孤儿分支，不得回退最近活跃会话）。这与"准确优先于自动化率"一致。

> ⚠️ **归属判定的落点声明（2026-09-15 补充，依据见 §8.2）** —— 原稿只说"做归属判定"，**未指明代码落在哪一层**，实现者会二次发挥。现明确：
>
> | 项 | 决定 |
> |---|---|
> | **执行位置** | **`IntentCoordinator` 内部**，在 `trigger(PAYMENT_RESULT)` 进入 `routeLocked` **之前**由 coordinator 自己完成 —— 因为 `handoffContexts` 由该 coordinator 持有（现状 `handoffContext` 即在 `Coordinator:145`） |
> | **判定输入** | `handoffContexts` 中各 Context 的**页面金额**（改造 C 新增字段）+ **会话时间**，对比证据的 `amountMinor` / `occurredAt` ⇒ **纯内存判定** |
> | **为何不直接调用 `resolveSessionForPayment`** | 它是 `Matcher:252` 的 `suspend fun`、需 DB 访问；而 coordinator 是纯内存调度器 ⇒ **复用其"判定原则"（金额 + 时间窗 + 未占用），不复用该函数本身** |
> | **`contextFor(PAYMENT_RESULT)` 的行为** | 返回 `handoffContexts[sessionId]`；**不得**回退 `activeContext`、**不得**回退"最近活跃会话" |
>
> ⚠️ **前置障碍（须先解决，否则编译不过）**：`IntentCoordinator` 侧**拿不到 sessionId** —— 实测 `SafeCheckoutContext`（`Coordinator:136-142`）、`StableCheckoutPage`（`PageDetector:74-77`）、`CheckoutPageObservation` **三者均无 sessionId 字段**。⇒ 须先补"sessionId 传递链路"，见 §8.3。

#### A-5｜受影响面（清单外但必须同步）

| 位置 | 现状 | 需改 |
|---|---|---|
| `Service:934-935` / `:2953-2989` `shouldTriggerSubmitAction`/`triggerSubmitAction` | SUBMIT_ACTION 链路 | 删除（D-11） |
| `CheckoutSubmitActionDetector`（整文件） | 动作词匹配 | 删除（D-11） |
| `aiTriggerReason`（`§7.7` 字段 / `CheckoutRequestKey`） | `SUBMIT_ACTION/HANDOFF/...` | 收敛为 `PAYMENT_RESULT` / `USER_RETRY` |
| `releaseCandidates` 释放粒度（`Buffer:726`/`:877-884`） | 无条件释放两张 | ✅ **2026-09-15 定案（依据见 §8.6）：收窄为"只释放被消费的那一张"**。现无条件释放两张，会在消费**自动图**时**连带抹掉未被消费的手动图** —— 而手动图正是"自动被拒后回退"的保底（D-16 = B）⇒ 必须保留。**这不是"复核后可能不改"，是必须改。** |

---

### 2.2 改造 B：支付过程专用槽位

#### B-1｜Buffer 新增能力

**新增字段**：`SlotState` **不变**（支付过程图存入**独立槽位**，不占 `SlotState` 字段）。

**新增 API（`CheckoutSnapshotBuffer`）**：

```kotlin
/**
 * 把结算页槽位中"该会话的候选图"移动到支付过程专用槽位。
 *
 * - 目的：让图在"用户进入支付应用 → 完成支付"整段期间**不被后续截图覆盖**，
 *   也不被同包新会话的 SlotState 替换连带释放。
 * - 语义：**所有权转移**（不复制字节）；源槽位中该候选被摘除但**不 release**，
 *   避免图片被抹零后支付成功时无图可送。
 * - 目标槽位：slotId = "payment:" + sessionId；expiresAt = now + paymentProcessTtlMillis。
 *
 * @return 搬入成功返回目标槽位引用；源无候选/已过期/已消费时返回 null。
 */
internal fun moveToPaymentProcess(
    sourceSlotId: String,
    sessionId: String,
    pageFingerprint: String?,
    paymentProcessTtlMillis: Long = PAYMENT_PROCESS_TTL_MILLIS   // 120_000L = 2 分钟，见 B-4
): CheckoutSnapshotReference?
```

**新增常量**：
- `PAYMENT_PROCESS_TTL_MILLIS = 120_000L`（**2 分钟**）—— 支付过程槽位专用，**自搬入时刻起算**；
- `SESSION_TTL_MILLIS = 5 * 60 * 1000L`（**5 分钟**）—— 会话上限，用于**结算页槽位**。

**新增前缀**：`PAYMENT_PROCESS_SLOT_PREFIX = "payment:"`。

> ⚠️ **R3 裁定（2026-09-13，见 `DESIGN_REVIEW_2026-09-13.md`）**：**不再使用固定 90 秒**（原 D-4/G6.3 Q2a）。记录 G1-6 自身论证"**慢的（输错重输 / 短信验证 / 换卡）可达 1~3 分钟**" ⇒ 90s 恰在最需要时间的场景下图过期。
> **用户最终裁定 TTL = 2 分钟**（否决了 AI 建议的 5 分钟会话上限，理由："5 分钟太长了，2 分钟就已经很不错"）⇒ 见 B-4。

**关键实现约束**：现有 `removeCandidate(state, candidate)` 会调 `.release()`（抹零字节）。搬移必须走**新的摘除路径**（`detachCandidate`），**只摘引用不抹零**。这是本改造**最容易出错的一点**。

#### B-2｜交接时改为"搬运"（`IntentCoordinator.onEvent:197-200`）

**改前**：
```kotlin
if (shouldTriggerHandoff) {
    trigger(PurchaseIntent.PAYMENT_APP_HANDOFF, handoffFingerprint)   // 消费 + 派 AI
}
```
**改后**：
```kotlin
if (shouldTriggerHandoff) {
    // 版本 B：交接只搬运，不派 AI（AI 由支付成功信号触发）
    snapshotCoordinator.moveToPaymentProcess(
        sourceSlotId = checkoutSnapshotSlotId(retained.shoppingPackage),
        sessionId    = retained.sessionId,
        pageFingerprint = handoffFingerprint
    )
    // 不再消耗 handoffAttempted / 不再 consumeForAi
}
```
> ⚠️ **2026-09-15 修正（依据见 §8.3）**：上面代码示例中的 `sessionId = retained.sessionId` 在**当前代码下编译不过** —— `retained` 是 `SafeCheckoutContext?`（`Coordinator:180`），而该类**没有 sessionId 字段**（`:136-142`）。须先完成 §8.3 的"sessionId 传递链路"改造，本段代码方可成立。
>
> `PAYMENT_APP_HANDOFF` 的 `handoffAttempted` 单次门禁（`:413-420`）随之失去意义 ⇒ 一并清理。

#### B-3｜消费时改为"从支付过程槽位取图"

在 A-3 的 `②` 处，`consumeForAi` 的 `slotId` 传 **`"payment:" + sessionId`**、`intent` 传 `PAYMENT_RESULT`。

**兜底（必须）**：支付过程槽位为空或图已过期时：
- **不得静默丢弃**；
- 走 `recordOrphanPaymentSignal(...)` 落待确认（已有路径，`Service:2143`）；
- 日志明确记 `PAYMENT_PROCESS_SNAPSHOT_MISSING`，便于量化"搬运漏检率"。

#### B-4｜生命周期：**事件驱动 + 2 分钟兜底**（R3 裁定，取代固定 90s）

**原设计的问题**：D-4/G6.3 Q2a 定"搬入时刻 + 90s"，但记录 G1-6 自述"**慢的（输错重输 / 短信验证 / 换卡）可达 1~3 分钟**" ⇒ **90s 与自身论证矛盾**，且失败恰集中在最需要时间的场景。

**裁定后的语义**：**主终点是事件（结果页到达），2 分钟是兜底超时**。

| 槽位 | TTL 常量 | 起算点 | 语义 |
|---|---|---|---|
| **结算页槽位** | `SESSION_TTL_MILLIS`（**5 分钟**）| `store()` 时刻 | 代表"当前结算页最新状态"；**主要靠新截图覆盖**，TTL 仅作内存兜底 |
| **支付过程槽位** | `PAYMENT_PROCESS_TTL_MILLIS`（**2 分钟**） | **搬入时刻**（前台切换） | 代表"这笔支付的结算页快照"；**不被覆盖**，终点取"**结果页到达（消费即释放）/ 2 分钟超时**"先到者 |

> ⚠️ **为什么两个槽位的时长不同（量纲差异，务必理解）**：
> - **结算页槽位等的是"用户决定"** —— 用户在结算页比价、等优惠券、犹豫，**停留数分钟是常态**，故需较长 TTL（会话上限）。
> - **支付过程槽位等的是"用户付款"** —— 从切到支付应用起算，**典型 5~30s、慢的 1~2 分钟**，故 2 分钟足够且能及时释放内存。
>
> ⇒ **若把两者统一为 2 分钟，会出现回归**：用户在结算页停留 >2 分钟后才提交订单 ⇒ 交接时**结算页槽位已空、无图可搬** ⇒ 支付成功时无图 ⇒ 落待确认。**这正是 R3 要修的场景。**

**关于 2 分钟的取舍（用户已确认）**：
- ✅ **覆盖**：典型支付 5~30s；"输错重输 / 短信验证"（通常 <2 分钟）；
- ⚠️ **不覆盖**：极端慢场景（换卡、验证码延迟 >2 分钟）⇒ 图过期 ⇒ 支付成功时无图 ⇒ 落待确认（**降级路径，安全侧**，不会错账）。
- ⇒ 用户裁定"2 分钟就已经很不错"，**接受该取舍**。原 AI 建议的 5 分钟已被否决（内存驻留过长）。

**`expiresAt` 用途扩散复核（G6.3 风险 2，仍需做）**：该字段同时被 `consumeForAi`（消费前校验）与 `purgeExpired`（定时清理）使用 ⇒ 两处均须确认在"两个不同 TTL 的槽位"下语义正确 —— **这比统一 TTL 更容易出错，是实现时的重点复核项**。

**搬入时必须显式重置 `expiresAt`**：支付过程槽位的候选来自结算页槽位（其 `expiresAt` 是"store 时刻 + 5 分钟"）⇒ **搬入时须重写为"搬入时刻 + 2 分钟"**，否则会沿用旧值。这是"起算点 = 搬入时刻"的落地要点。

#### B-5｜与现有交接栅栏的关系

| 机制 | 处置 |
|---|---|
| `handoffPageFingerprint`（槽位内标记） | **保留**：搬运后在支付过程槽位仍按"原指纹优先"消费（防误取他人图） |
| `retainForPaymentHandoff()` | **保留但降级**：不再作为"图存活"保护（它本来也不提供），仅保留其"限制消费指纹"语义 |
| `handoffCheckout` / `currentPaymentHandoffReference()` | **保留**：正是 B-3 定位 `sessionId` 的既有来源（§1.2） |

---

### 2.3 改造 C：Y4 修法（金额变化即新会话 + 时间上限）

**目标**：同包内"轮播漂移"仍沿用旧会话，但"真的换了订单"必须新建会话。

#### C-1｜判据落点（`SnapshotCoordinator`）

```kotlin
// 现（:1385-1402）
private fun hasRetainableCandidate(previous: ActiveCheckout, observation: CheckoutPageObservation): Boolean {
    if (previous.page.observation.shoppingPackage != observation.shoppingPackage) return false
    if (previous.captureTarget != SnapshotCaptureTarget.CHECKOUT) return false
    return buffer.currentCandidates(previous.slotId).any { it.sessionId == previous.sessionId }
}

// :1490-1497
val driftWithinSameCheckout = previous != null &&
    previous.page.observation.shoppingPackage == observation.shoppingPackage &&
    previous.page.observation.pageFingerprint != observation.pageFingerprint &&
    hasRetainableCandidate(previous, observation)
```

#### C-2｜两处新增判据

**判据 1（金额）**：`observation` 侧的最终金额 vs `previous` 侧金额不等 ⇒ **不沿用**。

> 🔴 **已核实（2026-09-13）：该判据当前无法直接实现，需先补数据字段。**
>
> `CheckoutPageObservation`（`CheckoutPageDetector.kt:26-42`）的字段为：
> `shoppingPackage` / `pageRole` / `pageFingerprint` / **`hasFinalAmountSignal: Boolean`** / `hasCheckoutActionSignal` / `isSensitivePage` / `windowClass` / `normalizedTextHash` / `observedAt`。
>
> ⇒ **只有"有没有最终金额"的布尔标记，没有金额数值**。
> ⇒ 全 `recognition/` 目录 grep 确认：**所有 `amountMinor` 均来自"支付证据"或"AI 候选"，页面侧没有任何金额提取**。
>
> ⇒ **Y4 判据 1 的前置工作 = 新增"页面金额（分）"字段 + 从页面文本提取数值**。这是本改造**唯一需要新增数据能力**的部分，须计入工作量。

**金额提取的实现建议**：
- 复用 `finalAmountTerms`（`Collector:113+`）**已定位的金额区域** —— 该词表已用于 `hasFinalAmountSignal`，且 `Collector:500-508` 已有"拼多多无标签金额"兜底逻辑，**存在可复用的定位前例**；
- 提取规则须与 `VolatileTokenTracker` 的"**命中 `finalAmountTerms` 的 token 永不入易变池**"口径**同源**（G6.3 要求），避免"信号通道认、指纹通道不认"的双通道分裂（该问题已记为 P8）；
- 提取失败（无金额/多金额歧义）⇒ **视为"无法判定"**，此时**沿用旧会话**（保守，避免误新建会话）。

**判据 2（时间上限）**：`now - previous.sessionCreatedAt > MAX_SESSION_REUSE_MILLIS` ⇒ **不沿用**。
- **取值**：初值 **2 分钟**（覆盖正常结算→支付全流程，短于会话上限 5 分钟）；标 `// 可调常量`。
- ⚠️ **必须与 §2.2 B-4 的 `PAYMENT_PROCESS_TTL_MILLIS` 区分** —— 两者**初值同为 2 分钟，但语义与起算点完全不同，不得联动修改**：

  | 常量 | 语义 | 起算点 | 超时后果 |
  |---|---|---|---|
  | **`MAX_SESSION_REUSE_MILLIS`**（本节 · Y4 判据 2） | **会话沿用上限** | `sessionCreatedAt`（会话建立） | 旧会话**不被沿用** ⇒ 新建会话 |
  | **`PAYMENT_PROCESS_TTL_MILLIS`**（§2.2 B-4） | **支付过程槽位存活时长** | **搬入时刻**（前台切换） | 图**过期释放** ⇒ 支付成功时无图 ⇒ 落待确认 |

  ⇒ 两者在"用户在结算页停留很久"场景会**同时**参与判定，代价方向却不同（前者拆会话、后者丢图）；**改一个不要顺手改另一个**。

- ⚠️ **与 0.3.3「已消除待实测校准」不矛盾**：0.3.3 消除的是**快照 TTL**（`PAYMENT_PROCESS_TTL_MILLIS`，不再依赖"支付流程耗时分布"）的待校准；**本常量属"会话沿用上限"这一性质不同的启发式**，其正确取法依赖真机"**同包换单间隔分布**"（尚未测量）⇒ 故仍标可调。journey 落地后本判据整体退役（见 C-3 末注），**不再追加测量投入**。

#### C-3｜回归保护（不可丢）

**必须保留**：同包内**指纹变化但金额不变** ⇒ 沿用旧会话。这是为修"真机支付交接只剩 `NO_BUFFERED_SNAPSHOT`"引入的（`Coordinator:1486-1489` 注释），去掉会复发。

> ⚠️ **定位与口径（2026-09-13 战略裁决·甲，见 §0.1）**：本改造的"页面金额字段 + 提取逻辑"按 `docs/tdd/08_phase4_journey_spec.md` §3.1 口径设计（**订单总额、分；订单号可见时订单号优先为身份**），作为与 journey 锚点**共享的基建**先行落地。金额判据 + 时间上限是**过渡期边界机制**——journey 将来落地时由锚点身份取代，届时本节启发式退役、金额提取保留。另：**P2-8（脱敏正则吞三位数金额，`CODE_REVIEW_BY_FEATURE` 在案）须随本批一并修复**——金额提取与金额信号共用同一文本通道，不修则 PDD ≥¥100 订单整链失效（含本判据的保守分支）。

---

### 2.4 改造 D：结果页证据落库与 AI 派发的顺序（决策点 4 = ②）

**目标**：证据**立即持久化**，但**"创建待确认 + 弹窗"推迟到 AI 定论之后** —— 使弹窗只在"真的需要用户介入"时出现，且出现时信息完整。同时修复 P5。

#### D-1｜关键发现：所需的"显式中间态"已经存在

现状并非"缺一个状态"，而是**把落待确认的时机放早了**（`SCHEME_INVENTORY §6.6` 记录）：

```
现状：证据先到 ⇒ 建 EVIDENCE_ONLY 占位候选 ⇒ advanceStatus(WAITING_ENRICHMENT)
                ⇒ pendingFromEvidenceOnly() 【立即落待确认】   ← 早了一步
                ⇒ 弹窗（AI 未返回，字段为空）
                ⇒ AI 返回无冲突 ⇒ 自动入账并清理该待确认 ⇒ 弹窗自消失
```

⇒ **`WAITING_ENRICHMENT` 本身就已经是"等待 AI 定论"的显式状态**，且它写在 Room 里 ⇒ **进程被杀后可恢复**（D-4 边界 2 自然满足）。

**⇒ 本改造的核心动作只有一条**：把 `pendingFromEvidenceOnly()` 的调用**从"证据落库时"推迟到"AI 定论且定论为需 review 时"**。

#### D-2｜改造后的完整数据流

```
支付成功信号（结果页解析通过 + markPaymentResultEvent 去重通过）
  │
  ├─ ① 持久化证据（立即，不丢）
  │     matcher.recordPaymentEvidence(...)  → 落 PaymentEvidenceEntity
  │
  ├─ ② 尝试派发 AI（改造 A 的接线）
  │     coordinator.trigger(PurchaseIntent.PAYMENT_RESULT, ...)
  │     │
  │     ├─ 派发成功（有图 + 预算未耗尽）
  │     │     ⇒ 会话进入 WAITING_ENRICHMENT
  │     │     ⇒ 【不落待确认、不弹窗】
  │     │     ⇒ 等 AI 定论 ↓
  │     │
  │     └─ 派发失败（支付过程槽位空 / 超预算 / AI 未配置）
  │           ⇒ 【立即落待确认】+ 弹窗（信息可能不全，但这是唯一可用信息）
  │           ⇒ 记日志 PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE
  │
  └─ ③ AI 定论（三条分支）
        ├─ 无冲突          ⇒ 自动入账；【不落待确认、不弹窗】        ← D-15/D-18 判据
        ├─ 有冲突 / 字段缺失⇒ 落待确认 ⇒ 弹窗（**此时信息完整**）
        └─ 失败 / 超时（32s）⇒ 落待确认 ⇒ 弹窗
                              （0 金额占位时按 **D-21** 允许弹窗并要求补金额）
```

> **与 D-21 的衔接**：D-21 允许"0 金额占位"弹窗。本改造下，"0 金额"出现在**AI 失败/超时**分支 ⇒ 落待确认 + 弹窗 + 强制补金额，与 D-21 一致。**两条裁定不冲突，是互补的**。

#### D-3｜两个边界的兜底（必须实现）

| 边界 | 兜底 |
|---|---|
| **AI 超时（最坏 32s）** | 走现有 `AI_RECOGNITION_FAILED` 路径转待确认（`Matcher:1137-1157`）⇒ 落待确认 + 弹窗。**不得长期悬置** |
| **进程被杀** | `WAITING_ENRICHMENT` 已持久化在 Room ⇒ 重启后扫描"处于该状态且无 AI 结果的会话" ⇒ **落待确认**（避免静默丢失）。**新增一条启动期兜底扫描** |
| **AI 已返回但规则引擎命中 `ignore`**（P18 形态） | 会话**有** AI 结果 ⇒ 上述"无 AI 结果"扫描条件**永不命中** ⇒ 悬置到会话过期。**✅ 2026-09-15 定案（依据见 §8.4）：选「给 `ignore` 显式终态」** —— AI 定论分支判定为 `ignore` 时**直接落显式终态**（不再悬置 `WAITING_ENRICHMENT`）。**不采用**"把扫描条件改为未达终态"：那会使 `ignore` 命中后仍被扫描捞出 ⇒ 落待确认 ⇒ 与"用户配置规则的意图"相悖。 |

#### D-4｜改动点清单

| # | 位置 | 改动 |
|---|---|---|
| 1 | `Matcher` 证据先到分支（`§6.6` 所述 `:744-797` 一带） | **移除** `pendingFromEvidenceOnly()` 的立即调用；改为仅 `advanceStatus(WAITING_ENRICHMENT)` |
| 2 | `Matcher` AI 定论处 | 新增：`conflictReason == null` ⇒ 直接入账（**不落待确认**）；否则落待确认 |
| 3 | `Service` 结果页路径（`:1596` 一带） | 证据落库成功后按 D-2 的 ② 尝试派发；派发失败即落待确认 |
| 4 | 启动期 | 新增"`WAITING_ENRICHMENT` 且无 AI 结果 ⇒ 落待确认"的兜底扫描 |

#### D-5｜对下游的正向连带

- **P18**（`ignore` 规则命中后"既不写 Bill 也不进待确认"，会话悬在 `WAITING_ENRICHMENT`）：本改造为 `WAITING_ENRICHMENT` 引入了**明确的退出路径**（AI 定论 / 超时 / 启动兜底）⇒ 顺手缓解 P18。**但 P18 的 `ignore` 语义本身仍待单独确认**，不在本改造范围。
- **P19**（已显示弹窗不自动撤下）：本改造后"弹窗自消失"的场景基本消除（弹窗只在需 review 时出现）⇒ **P19 的紧迫性下降**，但仍应补 `removed` 分支。

---

### 2.5 改造 E：`handoffContext` 映射化（R4 裁定）

**目标**：解决"**多订单并发时，结果页属于哪一单**"的归属问题。

#### E-1｜问题

`IntentCoordinator.onEvent`（`:179-188`）中 `handoffContext` 是**单一变量**，**新交接覆盖旧交接**：

```kotlin
if (isPaymentHandoff) {
    val retained = activeContext ?: handoffContext
    activeContext = null
    handoffContext = retained        // ← 覆盖：订单1 的交接上下文被订单2 顶掉
}
```

⇒ 订单1 支付中、用户返回下订单2 并交接后，**订单1 的结果页到达时已找不到自己的上下文**，只能退到 `findRecentCheckoutReferenceForPayment`（取**最近活跃**会话 = 订单2）⇒ 存在**误配**可能。

#### E-2｜裁定：映射化（甲）

`handoffContext` → **`handoffContexts: LinkedHashMap<sessionId, SafeCheckoutContext>`**（按 sessionId 键控，与改造 B 的支付过程槽位**同一键空间**，两者天然对齐）。

**结果页到达时的归属判定**（复用既有 `resolveSessionForPayment` 的纪律）：

| 匹配结果 | 动作 |
|---|---|
| **恰好 1 个**候选（金额 + 时间窗命中） | 用其 `sessionId` 定位支付过程槽位 ⇒ 派 AI |
| **0 个** | **孤儿待确认**（不猜） |
| **≥2 个** | **`Ambiguous` ⇒ 待确认**（**安全侧，不误账**） |

**容量与回收**：上限 `MAX_PENDING_HANDOFF_CONTEXTS` = **4**（⚠️ 2026-09-15 定案：`DECISIONS.md` 的 D-24 行已写明"**容量上限 4**"，属已定值而非"建议值"，实现时不得自行调整）；超出时**淘汰最旧**；随会话过期一并清理。

> ⚠️ **并发安全（2026-09-15 补充）**：`handoffContexts` 须与现有 `handoffContext` **沿用同一套访问保护** —— 现状 coordinator 的状态读写全部包在 `synchronized(this)` 内（如 `onEvent:163`、`trigger:210`）⇒ 新增映射的读写**必须在同一把锁内**，**不得裸读写 `LinkedHashMap`**（非线程安全）。

> ⚠️ **前置障碍（须先解决）**：`SafeCheckoutContext`（`Coordinator:136-142`）**没有 sessionId 字段** ⇒ 无法直接作为 `Map<sessionId, Context>` 的值类型来源。**须先完成 §8.3 的"sessionId 传递链路"。**

> ⚠️ **与"支付过程槽位"的协同**：两者都以 `sessionId` 为键 ⇒ 归属判定得到 sessionId 后，**直接 `payment:<sessionId>` 取图**，无需二次查找。**这是本改造与改造 B 必须同批实施的原因。**

#### E-3｜根本限制（诚实声明，不因本改造消除）

**微信/支付宝支付结果页不携带订单号**（只有金额 + 商户）⇒ 归属判定本质上只能依赖 **金额 + 时间窗** ⇒ **两单金额相同且时间接近时仍无法区分**。

⇒ 本改造的价值是：**把"上下文丢失"变成"可检测的歧义"**（⇒ 落待确认，安全侧），而**不是**让同金额两单能自动区分。后者在"结果页无订单号"的前提下**不可能实现**。

> ⇒ 已写入 `DESIGN_REVIEW_2026-09-13.md` R6/已知限制，并建议纳入验收指标。

---

### 2.6 连带修正：D-6 事件去重键收窄为 30 秒桶（2026-09-13 第二轮核对·矛盾1 裁决）

**问题（代码实测）**：结果页事件去重键 = `result-event|包名|occurredAt/120秒桶|金额|订单号`，命中即 `return` —— 发生在证据落库**与 AI 派发之前**。微信/支付宝结果页**通常无订单号** ⇒ **同包 + 同金额 + 2 分钟内两单必然撞键，第二单证据被静默丢弃**：不落待确认、不派 AI、什么都不发生。

> ⚠️ **该键有 2 个生成点，必须同步收窄（2026-09-15 核查补正）**：
>
> | # | 位置 | 属于哪条路径 | 命中判定点 |
> |---|---|---|---|
> | 1 | **`PaymentResultParser.kt:208-210`** | **主路径** —— 结果页文本解析成功 | `Service:1573`（`markPaymentResultEvent` 返回 false ⇒ 跳过） |
> | 2 | `CheckoutAccessibilityService.kt:1839-1842` | 截图兜底 —— 文本解析失败后对结果页截图送 AI | 同键，`:1844` 处判定 |
>
> ⇒ **两处共用同一键结构**（`Service:1838` 注释自述"与无障碍结果页共用同一个去重键"）⇒ **只改一处，另一条路径仍按 120 秒撞键**。其中**主路径覆盖绝大多数场景**（结果页文本可读即走它），**漏改主路径等于问题未修**，且回归测试会给出**假绿**。

**为何是矛盾**：D-6 裁定"订单级去掉、事件级保留"，但事件键内置"金额 + 订单号"两个**订单级判别信息** ⇒ 两层从未真正分离。第二单连待确认都没有 ⇒ **静默少记**，与改造E / 回归测试 13 的"`Ambiguous` ⇒ 待确认（安全侧）"承诺、与"宁可重复一条待确认，不冒错账风险"基调直接冲突。

**裁决（用户"按推荐方式来"，选甲）**：**时间桶 2 分钟 → 30 秒**（`occurredAt / 120_000L` → `occurredAt / 30_000L`），**键结构其余不变；上述两处生成点同步改**。

| 项 | 说明 |
|---|---|
| 防噪原意保留 | "同一结果页重复读"发生在秒级（无障碍事件连发），30 秒桶足够吸收 |
| 跨桶重复读 | 会话级幂等兜底（`upsertPendingForSession` / 证据按会话单条）——实现时确认 |
| 残余风险 | 同包同金额两单相隔 <30 秒仍撞键（人工连续完成两笔支付的间隔远大于 30 秒，可忽略） |
| **实施落点（2 处，缺一不可）** | ① **`PaymentResultParser.kt:209`**：`occurredAt / 120_000` → `/ 30_000` —— **主路径**；② **`CheckoutAccessibilityService.kt:1840`**：`occurredAt / 120_000L` → `/ 30_000L` —— 截图兜底路径。另：`recentPaymentResultEventKeys` 的 60s 保留窗相应复核 |
| 测试落点 | 回归测试 **15b 须钉到「主路径」（结果页文本解析成功）** —— 只测截图兜底路径会漏掉 `PaymentResultParser` 的漏改 |
| 批次 | **归入批 V-1**（无依赖、独立测试）；回归测试 15a / 15b 专守 |

---

## 3. 设计点裁决记录

> **裁决进度（2026-09-13）**：**决策点 1 / 2 / 3 / 4 全部已确认**。

| # | 决策点 | 选项 | 结论 |
|---|---|---|---|
| **1** | **支付过程槽位的 key** | 甲：现有槽位加字段 / 乙：`payment:<pkg>` / 丙：`payment:<sessionId>` | ✅ **已确认：丙 `payment:<sessionId>`**。理由：甲/乙在多订单场景会被同包新会话连带释放（§2.0） |
| **2** | **Y4 的"金额"从哪来** | ① 核实现有字段 ② 新增字段 + 提取逻辑 | ✅ **已确认：新增"页面金额（分）"字段 + 提取逻辑**（已核实 `CheckoutPageObservation` 无金额字段）；**提取失败 ⇒ 视为"无法判定"、沿用旧会话**（保守策略已确认）。⚠️ **2026-09-13（战略裁决·甲）**：字段口径对齐 `08_phase4_journey_spec.md` §3.1（订单总额、分），作共享基建，见 §0.1 |
| **3** | **结算页槽位"无倒计时"的兜底取值** | ① 会话级常量 ② 维持 120s | ✅ **已确认：会话级常量，取 5 分钟**（与 `WAITING_PAYMENT` 会话窗口同值）⇒ 图最长驻留 5 分钟已确认接受 |
| **4** | **结果页证据落库与 AI 派发的顺序** | ① 先落证据 → 立即落待确认+弹窗 → 再派 AI ② **先派 AI → AI 返回后统一决策** | ✅ **已确认：②**（规格见 **§2.4**） |

### 决策点 4 的确认记录

- **用户裁定（2026-09-13）**：选 **②**。
- **被否决的 ①**（= 现状语义）：证据落库时**同一事务内创建待确认** ⇒ `emit(isNew=true)` ⇒ **弹窗立即触发**，而 AI 尚未派发 ⇒ 弹窗内容为空（`productDescription` / `categoryId`）。AI 返回后若无冲突会自动入账并清理该待确认 ⇒ **"弹窗未及操作即被核销"**。这是 **P5** 记录的偏离（kickoff 原文要求"不弹窗 → 等 5~15 秒 → AI 完整则自动入账，超时才 review"，代码未实现）。
- **关键澄清（两个选项都不会丢证据）**：差别**只在"待确认与弹窗"这个面向用户的动作何时发生**，不在"证据是否立即写库"（两者都立即写）。

> ⚠️ **对齐说明**：此前在 `ISSUE_REGISTER_2026-09-11.md` 中，本文作者曾把 **P5 标为"已由 D-15/D-21 裁定"，该标注不准确** —— D-15 讲的是**手动路径**反馈、D-21 讲的是**0 金额弹窗**，**两者均未回答"证据先到是否立即弹窗"**。⇒ **P5 的本体问题由决策点 4 正式裁决**（选 ②），该错误标注已在 `ISSUE_REGISTER` 中一并纠正。

### 3.2｜设计评审派生裁定（R1~R4，2026-09-13）

> 来源：`docs/DESIGN_REVIEW_2026-09-13.md`。用户裁定"**按最佳方式来**" ⇒ 采纳下列方案。

| # | 事项 | 裁定 | 落地位置 |
|---|---|---|---|
| **R1/R2** | "送 AI 唯一出口 = 支付成功信号"**表述失真**；且方案 §1.1 曾误称"AI 只能经 coordinator 派发" | ✅ **按通道分列改写**：**自动通道**唯一触发 = 支付成功信号；**手动通道**（断言 / 重新识别）由用户点击触发、**不受支付信号约束**。方案 §1.1 已更正，并加显式约束"**改造 A 的门禁放宽与 `PAYMENT_RESULT` 接线只作用于 coordinator 路径，保持断言通道独立旁路不动**" | 本文 §1.1 / §2.1 A-1；`NEW_PROJECT_KICKOFF_V2.md` §7.6 / §2.3 / FR-011 / FR-015 |
| **R3** | **90s TTL 与设计自身论证矛盾**（记录自述"慢的可达 1~3 分钟"） | ✅ **改为事件驱动 + 2 分钟兜底** —— 主终点 = **结果页到达（消费即释放）**；**2 分钟**为支付过程槽位的兜底超时（**用户裁定**："5 分钟太长了，2 分钟就已经很不错"）。**两槽位 TTL 分离**：结算页槽位 = `SESSION_TTL_MILLIS`(5min) / 支付过程槽位 = `PAYMENT_PROCESS_TTL_MILLIS`(2min)，**因两者"等待对象"量纲不同**（等用户决定 vs 等用户付款） | 本文 §2.2 B-1 / **B-4（已重写）** |
| **R4** | `handoffContext` **单槽被新交接覆盖** ⇒ 多订单归属可能误配 | ✅ **选甲：映射化** —— 改为 `handoffContexts: Map<sessionId, Context>`，按 sessionId 键控（**与支付过程槽位同一键空间**）；结果页归属判定：1 命中⇒用之 / 0 命中⇒孤儿待确认 / **≥2 命中⇒`Ambiguous`⇒待确认（安全侧）**；容量上限建议 4、随会话过期回收 | 本文 **§2.5 改造 E（新增）** |

> **R4 的诚实声明**：本改造**不能**让"同金额两单"自动区分 —— 因**结果页不带订单号**，归属只能靠金额 + 时间，这是**根本限制**。本改造的价值是把"上下文丢失"变为"**可检测的歧义**"（⇒ 落待确认，不误账）。

**附：R5 已直接补入**（宽进需同时改 `canBufferSnapshot()` **与** `CheckoutPageDetector.detect()`，见 §2.1 A-2）；**R6 建议纳入验收指标**（详情页直达支付的降级代价 / 多订单根本限制 / 派发失败需量化告警）。

### 3.3｜第二轮核对派生修正（2026-09-13，用户"按推荐方式来"）

| # | 事项 | 裁决 | 落地 |
|---|---|---|---|
| **矛盾1** | 事件去重键吞掉第二笔同金额证据（与改造E / 回归测试 13 冲突，第二单静默少记） | ✅ **选甲：时间桶 2 分钟 → 30 秒** | 本文 **§2.6（新增）**；`CONFLICT_REVIEW` D-6 行；批 V-1 第 9 项 |
| **矛盾2** | A-2 门禁放宽代码复用 `canBufferUserRetrySnapshot()`（含 UNKNOWN/首页），与自身风险表"不放开 UNKNOWN"矛盾 | ✅ **改为宽进三元组直接判定** | 本文 §2.1 A-2（已修正） |
| **矛盾3** | A-4 的 `handoffContext ?: activeContext` 回退猜测，与改造E 映射化及"不回退猜测"矛盾；`findRecentCheckoutReferenceForPayment` 兜底未明说摘除 | ✅ **A-4 重写为映射版**；显式摘除兜底（`Service:2121-2142`） | 本文 §2.1 A-4（已重写）/ §2.5；`CONFLICT_REVIEW` G7 连带项同步更正 |
| **观察4** | D-22 兜底扫描"无 AI 结果"条件漏掉"AI 已返回但规则 `ignore`"形态（P18） | ✅ 补入边界表，实现时二选一 | 本文 §2.4 D-3 |
| **观察5** | 同金额**合并会话**形态走"派发失败 ⇒ 待确认"而非 `Ambiguous`，测试 13 只守分离形态 | ✅ 补测试 15a / 15b | 本文 §5 |

---

## 4. 实施顺序与批次

**依赖关系**：③Y4 是 ②的前提（同包换单不能顶掉支付中的图）；② 是 ①的前提（没有可存活的图，后移的触发点无图可用）。

| 批次 | 内容 | 依赖 | 可独立验证 |
|---|---|---|---|
| **批 V-1** | **8 项**（D-11 已于 2026-09-15 **移至批 V-4**）：D-9 / D-10 / D-16 / D-19 / D-20 / D-21 / 死入口清理 / **D-6 去重键收窄 30 秒桶（§2.6）**。⚠️ **内部须再分两小批**（见下方注） | 无 | ✅ 各项均有独立测试 |
| **批 V-2** | **改造 C（Y4）** —— 含决策点 2 的**新增页面金额字段 + 提取逻辑**（口径对齐 journey 规格 §3.1，共享基建，见 §0.1）+ **P2-8 脱敏吞金额修复**（金额提取同通道前置，见 §2.3 末注） | 无（可先行） | ✅ 轮播漂移回归 + 同包换单新建会话 + **≥¥100 订单真机回归** |
| **批 V-3** | **改造 B（支付过程槽位）+ 改造 E（`handoffContext` 映射化）** —— **必须同批**（两者共用 `sessionId` 键空间） | 批 V-2 | ✅ 槽位契约测试 + 归属歧义测试 |
| **批 V-4** | **改造 A（触发点后移）** —— 枚举 + 门禁放宽 + 结果页接线（**含宽进两处配套**）+ **D-11（删 `SUBMIT_ACTION` 整链路，2026-09-15 自批 V-1 移入）** | 批 V-3 | ✅ 端到端真机 |
| **批 V-5** | **改造 D（落库顺序）** —— 移除立即落待确认 + AI 定论分支 + 启动兜底扫描 | 批 V-4 | ✅ 弹窗时机测试 |

> ⚠️ **批 V-1 的规格出处与内部分批（2026-09-15 补）**：
> - **规格来源**：D-6 见本文件 §2.6；其余 7 项见 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的「**代码评估清单**」节（`:1654-1690`）。
> - ⚠️ **裁定口径不在 `DECISIONS.md`** —— 该文件是**按主题分节的"决策日志"**，仅覆盖部分 D 项；**D-10 / D-11 / D-20 在其中均无编号行**（2026-09-15 实测确认）⇒ 请查 `CONFLICT_REVIEW` 的**待议总表**。
> - **必须分两小批做**：
>   - **V-1a「纯删除」（零行为变化）** = D-10 死枚举 + 死入口清理 + D-20 删死代码 ⇒ 验收标准仅"**编译通过 + 全测试绿**"。
>   - **V-1b「行为改动」** = D-9 敏感页提前解除 + D-16 自动优先 + D-19 金额容差 + D-21 0 金额弹窗 + D-6 去重键收窄 ⇒ 每项都改运行时行为，**必须逐项独立验证**，不得混为一次大改。
>   - **理由**：初版把 8 项并列称为"已就绪"（= 规格完整），但"**规格完整 ≠ 零风险**"。混做会让"某处行为变化导致测试红"**无法归因**。

> ⚠️ **D-11 为何必须与批 V-4 同批（2026-09-15 裁定）**：D-11 的 `Service:934-936` 是**当前活的**派发路径（提交订单点击 ⇒ 消费快照 + 派 AI）。它单独先删会产生"**旧触发点已删、新触发点（`PAYMENT_RESULT`）未接**"的空窗。D-11 与批 V-4 本质是**同一语义变更的两半**（旧触发点 → 新触发点），合批方能无缝切换。
> **D-11 随批 V-4 一并进行的内容**：`CheckoutSubmitActionDetector`（整文件）、`shouldTriggerSubmitAction` / `triggerSubmitAction`、`Service:934-936` 调用点、`submitActionTriggeredKey`（字段 + 10 处重置点）、`PurchaseIntent.SUBMIT_ACTION` 枚举值、Coordinator 的 `SUBMIT_ACTION ->` 分支，以及 `CheckoutSnapshotBufferTest`（17 处）与 `CheckoutPurchaseIntentCoordinatorGateTest`（18 处）的用例适配（其中 2 个无法等价替换者用 `PAYMENT_RESULT` 重写）。

> 批 V-1 与批 V-2 **无依赖，可并行**；批 V-2→V-3→V-4→V-5 **必须串行**。
>
> **批 V-5 放最后的原因**：它改的是"AI 定论后如何决策"，必须在"AI 能被派发"（批 V-4）之后才可测。
>
> **批 V-3 合并 B 与 E 的原因**：结果页归属判定产出 `sessionId` 后**直接用于 `payment:<sessionId>` 取图**；分两批实施会出现"槽位按 sessionId、归属仍单槽"的**不一致中间态**，不可测。
>
> **批次编号说明（2026-09-13，战略裁决·甲）**：本方案批次更名 V-1~V-5，避免与 ROADMAP"批 4 = journey 重构"撞名；journey 已重新定位为暂缓实施（见 §0.1）。

---

## 5. 回归测试清单（本方案新增）

### 改造 C（Y4）
1. 同结算页**轮播漂移**（指纹变、金额不变）⇒ **沿用**旧会话（回归保护）
2. 同包 **2 分钟内换商品**（金额变化；时长以 `MAX_SESSION_REUSE_MILLIS` 初值 2 分钟为界 —— ⚠️ 该常量与 `PAYMENT_PROCESS_TTL_MILLIS` **同值不同义**，见 §2.3 C-2；0.3.3 后原"90s 内"表述随固定 90s 一并弃用）⇒ **新建**会话
3. 旧会话**超龄**（> `MAX_SESSION_REUSE_MILLIS`）⇒ **新建**会话

### 改造 B（支付过程槽位）
4. **两槽位 TTL 分离验证**：在**结算页停留 3 分钟**（> 支付槽 TTL、< 会话上限）后提交订单并完成支付 ⇒ 图**仍可用**（验证结算页槽位用会话级 TTL，**未**被 2 分钟误杀）
5. **慢支付场景（R3 直接验证）**：搬入后耗时 **~90 秒**（如"输错重输 / 短信验证"）⇒ 图**仍可用**（旧 90s 设计下会失败）
6. **搬入后 > 2 分钟仍未出现结果页** ⇒ 图过期 ⇒ 支付成功时走**落待确认**兜底（降级、安全侧）
7. **搬入时必须重置 `expiresAt`**：验证支付过程槽位的过期时间 = 搬入时刻 + 2 分钟（**不是**沿用结算页的 store 时刻 + 5 分钟）
8. 交接后**同包浏览其他商品**（触发 `store` 覆盖）⇒ 支付过程槽位的图**不受影响**
9. 交接后**同包新建会话**（`SlotState` 被替换）⇒ 支付过程槽位的图**不被连带释放**
10. 搬运**漏检**（无 `PAYMENT_APP_HANDOFF` 但支付成功）⇒ 落**待确认**，不静默丢弃

### 改造 E（`handoffContext` 归属）
11. **并发两单**：订单1 交接 → 下单2 交接 → **订单1 结果页到达** ⇒ 归属判定**仍能找到订单1**（验证未被覆盖）
12. 并发两单、**金额不同** ⇒ 各归其位，正确入账
13. 并发两单、**金额相同且时间接近** ⇒ **`Ambiguous` ⇒ 待确认**（安全侧，**不得误配**）——注（2026-09-13）：该形态要求两会话**已分离**（时间上限拆开 / 返回不同结算页）；**2 分钟内同金额两单会合并会话**，走下一条 15a 的派发失败分支
14. 结果为 **0 命中** ⇒ 孤儿待确认（不猜测）
15. 映射**容量上限**超限 ⇒ 淘汰最旧，不无限增长
15a. **同金额两单·合并会话形态**（2 分钟内同包两单，Y4 金额判据不拆会话）⇒ 第二单支付成功时：**证据必须存活**（依赖 §2.6 的 30 秒桶）⇒ 归属判定命中会话 1 ⇒ 支付过程槽位已被订单 1 消费 ⇒ **派发失败分支 ⇒ 落待确认**（非 `Ambiguous`、**非静默丢失**）
15b. **D-6 去重收窄回归**：同一结果页 30 秒内重复读 ⇒ 只落一条证据（防噪保留）；同包同金额两单相隔 >30 秒（< 2 分钟）⇒ **两笔证据均落库**，第二单走到归属判定/派发，**不得被去重吞掉**

### 改造 A（触发点后移）
16. 提交订单但**不支付** ⇒ **零 AI 请求**（版本 A 行为已消除）
17. 完成支付 ⇒ **恰好 1 次** AI 请求，且图来自支付过程槽位
18. 结果页证据被 `Rejected`（冲突）⇒ 落待确认，**且不重复派 AI**
19. **`PRODUCT_DETAIL` 直达支付**场景 ⇒ 门禁不误拒（验证 A-2 放宽 + **宽进两处配套**均已生效）
20. **断言通道回归**（R1/R2 关键）：用户点悬浮按钮 ⇒ **AI 立即派发**，**不受"支付成功信号"门禁影响**
21. **结果页截图兜底回归**（R1/R2 关键）：结果页**文本不可读** ⇒ 截图兜底仍能派发 AI

### 改造 D（落库顺序，决策点 4 = ②）
22. 支付成功 + AI 可派发 ⇒ **AI 返回前不落待确认、不弹窗**（验证弹窗时机已推迟）
23. AI 返回**无冲突** ⇒ 自动入账，**全程零弹窗**
24. AI 返回**有冲突/字段缺失** ⇒ 弹窗**恰好 1 次**，且卡片字段**非空**（`productDescription`/`categoryId` 有值）
25. **AI 派发失败**（支付过程槽位空 / AI 未配置）⇒ **立即落待确认**，不静默
26. **AI 超时（32s）** ⇒ 落待确认 + 弹窗（0 金额时按 D-21 强制补金额）
27. **进程被杀后重启**（会话处于 `WAITING_ENRICHMENT` 且无 AI 结果）⇒ 兜底落待确认

---

## 6. 风险与兜底

| 风险 | 兜底 |
|---|---|
| **搬运漏检** ⇒ 支付过程槽位无图 | 送 AI 前判空 ⇒ 落待确认 + 记 `PAYMENT_PROCESS_SNAPSHOT_MISSING` 日志（可量化） |
| **图片被误抹零**（`detachCandidate` 实现错误） | 单测钉死"搬移后源槽位无该候选，但目标槽位字节非全零" |
| **`expiresAt` 语义扩散**（B-4） | 逐处复核 `consumeForAi` / `purgeExpired` |
| **门禁放宽引入误派**（A-2） | 放宽仅到"可截三类"，**不放开 `UNKNOWN`/`BROWSING`**；负向测试覆盖 |
| **内存**：两槽位并存使图数上限翻倍 | 支付过程槽位由 **2 分钟兜底 TTL** 强制回收（⚠️ 0.3.3，原 90s 已弃用）；监控同时存活槽位数 |
| **决策点 2 未定** ⇒ Y4 无法开工 | ✅ **已核实：必须新增字段 + 提取逻辑** ⇒ 批 V-2 的**第一步就是补这个字段**（不是"查一下就有"），已计入工作量 |
| **改造 D 使"待确认"推迟** ⇒ 若 AI 永不返回，可能出现"证据在、用户看不到"的静默窗口 | 三重兜底：① AI 派发失败即落待确认；② AI 超时（32s）落待确认；③ 启动期扫描 `WAITING_ENRICHMENT` 无 AI 结果者落待确认 |
| **改造 D 改变既有行为**（证据先到不再立即弹窗） | 需同步改文档：`NEW_PROJECT_KICKOFF_V2.md` `FR-023` 的处理顺序 + `SCHEME_INVENTORY §6.7` 弹窗全表；并在 `ISSUE_REGISTER` **纠正 P5 的错误标注** |
| **R1/R2 表述失真被实现者按字面执行** ⇒ 打断断言通道 / 结果页截图兜底 | 文档按通道分列改写（§3.2 R1）；方案 §1.1 已加显式约束；**回归测试第 19/20 条专门守这两条路径** |
| **R3 两个槽位 TTL 不同**（2 分钟 vs 5 分钟）⇒ `expiresAt` 语义更易出错 | **搬入时必须显式重置 `expiresAt`**（不得沿用结算页的旧值）；`consumeForAi` / `purgeExpired` 两处逐处复核；**回归测试第 4、7 条专守** |
| **R3 极端慢支付（>2 分钟）** ⇒ 图过期 | 落待确认（**降级、安全侧，不错账**）；用户已确认接受该取舍 |
| **R4 映射化后归属判定逻辑变复杂** | 复用既有 `resolveSessionForPayment` 的纪律（1 命中/0 命中/≥2 命中三分支）；容量上限 4 + 随会话过期回收；**回归测试第 10~14 条覆盖** |

---

## 7. 本文之后仍待补齐的文档

- `§7.7` 的 `aiTriggerReason` 枚举 → 收敛为 `PAYMENT_RESULT` / `USER_RETRY`
- 新增"支付过程槽位"的**槽位契约**章节（本方案 §2.2 定稿后回填 `NEW_PROJECT_KICKOFF_V2.md §7.6.1`）
- `§7.5` 页面角色表 → 补 `canBufferSnapshot()` 扩面的对应说明

---

## 8. 开工前决策与修正（2026-09-15）

> **本节性质**：用户授权"依讨论记录代为拍板"后，对 `IMPL_READINESS_RISKS_2026-09-15.md` 所列待定项逐条定案，并修正查证中新发现的结构性缺陷。
> **纪律**：能用讨论记录中**已有裁定**的，直接引用；记录中**无直接裁定**的，**标注"推断"并给出依据** —— 不把推断说成裁定。

### 8.0 定案总表

| # | 事项 | 定案 | 依据性质 |
|---|---|---|---|
| 8.1 | A-3 的 `trigger` 调用 | **不传 `sessionId` / 不传 `pageFingerprint`** | 代码实测（签名有默认值） |
| 8.2 | 归属判定的落点 | **`IntentCoordinator` 内部、纯内存判定** | 架构推断 |
| **8.3** | **sessionId 传递链路（新发现，阻断级）** | **须先新增**，否则编译不过 | **代码实测** |
| 8.4 | `ignore` 语义 | **给 `ignore` 显式终态** | ⚠️ **推断**（记录无直接裁定） |
| 8.5 | `AUTO_DISPATCHABLE_PAGE_ROLES` 位置 | **新增于 `CheckoutContract.kt`** | 依 R5"单一出处"要求 |
| 8.6 | `releaseCandidates` 释放粒度 | **收窄为"只释放被消费的那一张"** | 依 D-16 = B |
| 8.7 | 批 V-1 的规格出处 | **指向 `CONFLICT_REVIEW` 代码评估清单节** | 事实（规格实际所在） |
| 8.8 | 死入口清理 | **4 方法 + 1 枚举全部删除** | 依 D-10/D-11 + 代码实测 |

### 8.1 A-3 的 `trigger` 调用修正

**实测**（`CheckoutPurchaseIntentCoordinator.kt:204-209`）：

```kotlin
fun trigger(
    intent: PurchaseIntent,
    pageFingerprint: String? = null,
    callback: PurchaseIntentResultCallback? = null,
    paymentNotification: PaymentNotificationFallbackSignal? = null
): PurchaseIntentCoordinationResult
```

⇒ 后 3 个参数**均有默认值** ⇒ 原稿"**需扩展 `trigger` 签名**"**不成立**。

**定案**：`coordinator.trigger(intent = PAYMENT_RESULT, callback = callback)` —— 不传 `sessionId`（由 coordinator 内部产出，见 8.2）、不传 `pageFingerprint`（消费时用 `handoffContexts[sessionId].pageFingerprint`）。

**原稿错误根因**：A-3 的 `sessionId = reference.sessionId` / `pageFingerprint = reference.pageFingerprint` 属**"单槽 `handoffContext`"时代的语义** —— 改造 E 映射化后不存在唯一的 `reference`。已在 A-3 就地更正。

### 8.2 归属判定的落点（定案）

| 项 | 定案 |
|---|---|
| 执行位置 | `IntentCoordinator` 内部，`trigger(PAYMENT_RESULT)` 进入 `routeLocked` **之前** |
| 判定输入 | `handoffContexts` 各 Context 的**页面金额**（改造 C 新增）+ 会话时间，对比证据 `amountMinor`/`occurredAt` ⇒ **纯内存** |
| 不直接调用 `resolveSessionForPayment` | 它是 `Matcher:252` 的 `suspend fun`、需 DB；coordinator 是纯内存调度器 ⇒ **复用其判定原则，不复用函数本身** |
| `contextFor(PAYMENT_RESULT)` | 返回 `handoffContexts[sessionId]`；**不得**回退 `activeContext`、**不得**回退"最近活跃会话" |

**依据（⚠️ 推断）**：`handoffContext` 现由 `IntentCoordinator` 持有（`Coordinator:145`），映射化后 `handoffContexts` 自然同处；且归属判定须在派发前产出 sessionId，放 coordinator 是唯一不需跨层传参的位置。

### 8.3 🔴 sessionId 传递链路（**新发现，阻断级**）

**问题（代码实测）**：改造 B 与改造 E 都依赖 `sessionId`，但 `IntentCoordinator` 侧**拿不到它**：

| 数据结构 | 位置 | 有无 sessionId |
|---|---|---|
| `SafeCheckoutContext` | `Coordinator:136-142` | ❌ **无**（仅 `slotId`/`shoppingPackage`/`pageRole`/`pageFingerprint`/`isSensitivePage`） |
| `StableCheckoutPage` | `PageDetector:74-77` | ❌ **无**（仅 `observation` + `stableAt`） |
| `CheckoutPageObservation` | `PageDetector:26-42` | ❌ **无** |
| `UserRetryCaptureContext` | 手动路径 | ✅ **有**（`:276` 有 `context.sessionId` 非空校验），但 `toSafeCheckoutContext()` 转换时**被丢弃** |

⇒ **后果**：改造 B 示例中的 `sessionId = retained.sessionId` **编译不过**；`Map<sessionId, Context>` 的 key **无来源**。

**定案（须先于批 V-3 完成）**：
1. 给 `SafeCheckoutContext` **新增 `val sessionId: String`**；
2. 补一条"sessionId 从会话侧传到 coordinator"的链路 —— 候选做法：`StableCheckoutPage` 增 `sessionId` 字段，由 `Service` 在 `onStable`（`Service:407-413`）时从 `SnapshotCoordinator` 的 `ActiveCheckout` 取值后填充；或由 `SnapshotCoordinator` 发事件时直接带上。
3. `toSafeCheckoutContext()` 转换（`Coordinator:228`/`:279`）**不得丢弃 sessionId**（现状会丢）。

> **实施要求**：本项是批 V-3（改造 B + E）的**前置**，**不可跳过**；建议**单独一个提交**，便于回归定位。

### 8.4 `ignore` 语义（定案：显式终态）

**背景**：P18（`ISSUE_REGISTER:74`）—— 规则引擎命中 `ignore` ⇒ 不写 Bill、也不进待确认，会话停 `WAITING_ENRICHMENT` 等过期。原表述为"实现时二选一"。

**定案**：**选「给 `ignore` 显式终态」** —— AI 定论分支判定为 `ignore` 时**直接落显式终态**。

**依据（⚠️ 推断 —— 记录中无用户对该语义的直接裁定，勿当作裁定引用）**：
1. `ignore` 是**规则引擎的裁定**（用户可配置，见 `RulesScreen`），**不是失败路径**；
2. "落袋优先"约束的是**失败路径不静默丢钱**，而 `ignore` 是"这笔按规则不该记"的正常结论 ⇒ 不适用"落待确认"兜底；
3. 若改选"扫描条件改为未达终态"，`ignore` 命中后会被扫描捞出 ⇒ **落待确认** ⇒ 用户配了"忽略"却仍被弹窗询问，语义冲突；
4. ⇒ **不给 `ignore` 落待确认，但要给它明确终态标记**，消除"悬置到过期"的静默态 —— 即原选项 A。

> ⚠️ **残留待确认**：`ignore` **是否可能误判**（规则过宽导致该记的被忽略）属**规则引擎侧**问题，**不在本改造范围**（§2.4 末注已声明）。本定案只解决"状态悬置"。

### 8.5 `AUTO_DISPATCHABLE_PAGE_ROLES` 的位置（定案）

**定案**：**新增于 `CheckoutContract.kt`**，取值 `{PRODUCT_DETAIL, CART, CHECKOUT}`；`canBufferSnapshot()`（同文件 `:21-30`）与 A-2 的 `routeLocked` 门禁**必须引用同一常量**。

**依据**：R5 明确要求宽进三类"**单一出处**"；"两处各自写死"正是本项目反复出现的漂移来源。`CheckoutContract.kt` 是契约文件、双方共同依赖 ⇒ 唯一合理位置。

**实测补充**：该常量**当前不存在**（全 `recognition/` 无此符号）⇒ 属**新增**，非"复用"。

### 8.6 `releaseCandidates` 释放粒度（定案：必须收窄）

**定案**：由"**无条件释放两张**"改为"**只释放被消费的那一张**"。

**依据（D-16 = B 的直接推论）**：
- D-16 定"**自动优先 + 自动被拒才回退手动**" ⇒ 消费**自动图**时，**手动图仍是回退备选**，不得连带抹掉；
- 现状"无条件释放两张"（`Buffer:726`/`:877-884`）会在消费自动图时**抹掉手动图** ⇒ 一旦自动图后续被判无效，**无图可回退** ⇒ 与 D-16 直接冲突。

⇒ 本项**必须改**；原表述"复核"应视为"已知需改项"（已在 A-5 表就地更正为定案）。

### 8.7 批 V-1 的规格出处（定案：指向代码评估清单节）

**定案**：§4 的批 V-1 行已就地补指路 —— 除 D-6 见本文件 §2.6 外，**其余 8 项规格在 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的「代码评估清单」节（`:1654-1690`）**。

**依据（事实）**：该节逐条给出文件路径 + 行号 + 改动方向，是这 8 项的真实规格来源；原文"规格已完整，不在本文重复"但**未指路** ⇒ 实现者只读本文件会漏。

> **给实现者的阅读范围（强制）**：任一批次开工前须同时读 ① 本文件对应章节、② `CONFLICT_REVIEW` 代码评估清单节的相关条目、③ `DECISIONS.md` 的对应 D 行。**三份互补，缺一会漏规格。**

### 8.8 死入口清理（定案：全部删除）

**定案**：4 个方法 + 1 个枚举**全部删除**，不做"重定位"。

| 符号 | 位置 | 删除依据 |
|---|---|---|
| `requestSubmitAction` | `Service:197-200` | D-11 删 `SUBMIT_ACTION` 整链路 |
| `requestPaymentAppHandoff` | `Service:203-206` | 改造 B 把交接改为 `onEvent` 内部搬运，不再需要外部触发入口 |
| `requestPaymentPageVisible` | `Service:209-212` | D-10 死枚举 |
| **`requestUserRetry`** | `Service:248-251` | **实测已被 `requestUserRetryForSession`（`:255-274`）取代** —— 后者带 `sessionId`、实现完整（"回写到指定失败会话"），前者仅为旧的无上下文版本 |
| `PAYMENT_PAGE_VISIBLE`（枚举） | `CheckoutContract.kt` | D-10 |

> ⚠️ **不在删除范围、易误删的邻接符号（必须保留）**：`requestPaymentNotificationFallback`（`:215-225`）与 `requestPaymentNotificationFallbackSignal`（`:232-245`）—— 属通知回退链路（D-3 v1 停用但**代码保留**），**不得随死入口一并删除**。

### 8.9 待清理项登记（2026-09-15）

> 本节的项**已知可清理但本批不做**，登记以免遗忘。

| 项 | 内容 | 状态 |
|---|---|---|
| **`res/layout/payment_overlay.xml`** | D-20 删除 `PaymentOverlayManager.kt` 后成为**孤儿资源**（唯一引用者已删）。本批范围仅"整文件 + 模块注册" ⇒ **不删**，登记待清理 | ⏸ 待清理 |
| **`PaymentNotificationModule` 的 `LifecycleEventListener` + 3 个空回调** | D-20 后回调已无行为（`onHostResume/onHostPause/onHostDestroy` 均为 `= Unit`），但 `addLifecycleEventListener(this)` 注册仍在。本批按 Q5 裁定**保留**（摘除属行为面改动）⇒ 登记为后续可评估项 | ⏸ 待评估 |
| **`PaymentNotificationHeadlessService.kt:9` 的死 import** | `LocalBroadcastManager` import 存在但未被使用（D-20 前即如此）⇒ 可清理，**但清理前须确认 gradle 依赖是否仍被其他文件使用**（否则不能移除依赖） | ⏸ 待评估 |

> ⚠️ **清理 `payment_overlay.xml` 时的红线**：该布局涉及的 `R.id.*` 与 `btn_category_*` 系列 drawable **与 `PendingConfirmOverlayManager` 共用** ⇒ **不得连带删除这些 id / drawable**（2026-09-15 实现时已核实）。
