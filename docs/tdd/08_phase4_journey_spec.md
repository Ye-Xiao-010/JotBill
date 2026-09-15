# 08. 批 4 journey 模型重构规格（#1）

> 技术规格文档（TDD 第八部分）
>
> 关联：ROADMAP_FALLBACK_FIRST.md 第六节、DECISIONS.md、docs/types/Native.d.ts、02_room_schema.md
>
> 起草日期：2026-08-31　状态：**规格已拍板（2026-08-31，难题清单决策按草案采纳），硬前置待 0.0.93 真机跑黄金脚本**。⚠️ **2026-09-13 战略裁决（甲）：本规格暂缓实施** —— 版本 B（`docs/IMPL_PLAN_VERSION_B_2026-09-13.md` 批 V-1~V-5）先行；§3.1 锚点的"金额"分量由批 V-2 **先行建成共享基建**（字段口径对齐本节）；版本 B 改造 C 的金额判据为过渡期边界机制，本规格落地时由锚点身份取代（启发式退役、金额提取保留）；分支 `roadmap/batch4-journey` **冻结**（不删除、不继续开发）。详见 `ROADMAP_FALLBACK_FIRST.md` 第六节裁决注与 `IMPL_PLAN_VERSION_B` §0.1。
>
> 作用：把"结算页自动截图"的订单边界从「会话指纹」改为「journey」，根治指纹漂移导致的快照丢失/重截；本规格是批 4 编码的唯一依据，编码前必须先通过本规格评审。

---

## 1. 目标与定位

**根治问题**：当前订单边界 = 会话指纹（页面指纹），京东/拼多多结算页持续漂移导致快照互相覆盖、唯一截图被取消、交接栅栏锁死自己。批 0 已用启发式（漂移沿用旧会话、复用候选不重截、交接栅栏指纹校正）逐一修补，但根因未除——指纹不是订单边界。

**目标**：journey = 购物包 + 窗口类名 + 金额（订单号可见时优先），指纹降级为"页面变化 → 可能需要重截"的信号，不再是订单边界。journey 内漂移天然沿用，不再依赖启发式兜底。

**唯一动核心的批**：批 0-3、5-6 全是围绕核心的增量；批 4 是唯一重写三个核心文件（CaptureCoordinator、SnapshotBuffer、AccessibilityService handoff 收敛）的批次，按"先规格 → 分支开发 → 特征测试全绿 → 设备矩阵 → 真机浸泡 ≥1 周 → 单 PR"执行。

---

## 2. 硬前置（缺一不动工）

1. **批 0 全部特征测试绿**：buffer 引擎抽取 + routeLocked 门禁序列 17 例 + matcher Robolectric 4 例，全量 193/193。✅ 已完成（2026-08-29）。
2. **黄金脚本就绪**：`docs/qa/golden-path-regression.md`（2026-08-31 补齐，3 正向 + 4 负向用例，期望日志关键词 + 通过标准）。⚠️ 首次真机执行需在 0.0.93 装机后跑，校准日志关键词并落证据到 `docs/qa/device/`；校准通过后作为批 4 编码的硬前置满足项。
3. **本规格经评审拍板**：难题清单（第 4 节）每一项必须有明确决策，不留"编码时再说"。

---

## 3. journey 模型定义

### 3.1 身份锚点

```
journeyId = hash(shoppingPackage + windowClassName + amountMinor)
// 订单号（orderId）可见时，journeyId = hash(shoppingPackage + orderId)，金额作为校验而非身份
```

- **购物包**：结算页所在 App 包名（现状 `slotId = "shopping-package:" + shoppingPackage`，Buffer :14-18；Coordinator `slotIdFor` :2185）。
- **窗口类名**：结算页 Activity/窗口类名（区别同包内不同结算页）。
- **金额**：订单总额（分）。订单号可见时优先用订单号，金额降为校验字段。
- **指纹**：降级为"页面变化信号"。journey 内指纹漂移 = 可能需要重截（候选过期时），不改变 journey 归属。

**现状差距**：`ensureSession`（Matcher :166-178）以 `CheckoutSnapshotReference`（含 pageFingerprint）+ `CheckoutRequestKey.from(sessionId, shoppingPackage, pageFingerprint)`（Contract :315-322）作锚点，sessionId 由 Coordinator `sessionIdFactory = { UUID.randomUUID() }`（:298）在 handleStable 开新会话时生成（:1431）/ setUserRetryContext（:1015）。journey 锚点未独立成"包+类名+金额"。

**改造**：显式化 journey 锚点；sessionId 保留为公开字段（CheckoutSnapshotReference / CheckoutRequestKey 对外语义不变），journeyId 作为内部栅栏与状态机键。

### 3.2 状态机（复用 16 态，扩展非新造）

复用 `CheckoutJourneyState`（CheckoutContract.kt :191-207）+ `canTransitionTo`（:209-266），**不新增枚举值**：

| 状态 | 含义 |
|---|---|
| CHECKOUT_DETECTED | 结算页首次稳定识别 |
| SNAPSHOT_BUFFERED | 截图入 buffer |
| AI_TRIGGER_ARMED | ~~购买意图触发~~ → ⚠️ **等待支付成功信号**（状态名沿用，等待条件已变），待联网 |
| AI_PENDING | AI 请求派发 |
| AI_COMPLETED | AI 返回成功证据 |
| AI_FAILED | AI 返回失败 |
| WAITING_PAYMENT | 等待支付证据 |
| PAYMENT_CONFIRMED | 支付证据已匹配 |
| WAITING_ENRICHMENT | 等待补全（账户/分类） |
| AUTO_COMMITTED | 自动入账完成 |
| NEEDS_CONFIRMATION | 进待确认中心 |
| COMPLETED / DISMISSED / CANCELLED / FAILED / EXPIRED | 终态 |

迁移规则见 `canTransitionTo`（:209-266）。终态集合 `COMPLETED, DISMISSED, CANCELLED, FAILED, EXPIRED` 不可迁出（:265）。

**journey_state 落库**：`CheckoutSessionEntity.journeyState` 列（Contract :188 注释"阶段四 T1 起内部旅程状态只存入 journeyState 列，不直接序列化给 RN"）；公开 `status` 列由 `toPublicSessionStatus()`（:272-289）映射，不得新增第 9 个公开值。

### 3.3 出生

- **出生点**：结算页稳定（pageRole=CHECKOUT）+ journey 锚点可计算 → `CHECKOUT_DETECTED`。
- 现状出生点在 `ensureSession`（Matcher :166）落 `AI_PENDING`（跳过 CHECKOUT_DETECTED/SNAPSHOT_BUFFERED）。改造后 journey 出生应从 `CHECKOUT_DETECTED` 起步，截图入 buffer 后迁 `SNAPSHOT_BUFFERED`，~~购买意图触发~~ → ⚠️ **支付成功信号触发**迁 `AI_TRIGGER_ARMED`/`AI_PENDING`。
  > ⚠️ **2026-09-12（版本 B）**：`AI_TRIGGER_ARMED` → `AI_PENDING` 的**触发条件已改变** —— 不再是"提交订单/支付应用调起"，而是"**检测到支付成功信号**（支付结果页无障碍文本）"；另 `PRODUCT_DETAIL`/`CART` 已允许预捕获（宽进）。出处：`CONFLICT_REVIEW_SESSION_2026-09-11.md` G1-3/G6.3 + `NEW_PROJECT_KICKOFF_V2.md` 0.3.0。
- **幂等**：同 journey 锚点已存在活跃会话 → 复用（不新建）；journey 锚点变化 → 新 journey。

### 3.4 死亡

- **正常终态**：`AUTO_COMMITTED → COMPLETED`（自动入账）、`NEEDS_CONFIRMATION → COMPLETED/DISMISSED`（用户确认/忽略）、`CANCELLED`（用户放弃）、`FAILED`（AI/入账失败）。
- **超时**：复用 buffer TTL（现状 120s 量级，`CheckoutSnapshotBuffer` 过期调度）+ session `expiresAt`（Matcher `sessionExpiresAt`）。超时 → `EXPIRED`。
- **跨包漂移**：异包 = 新 journey；旧 journey 的快照/栅栏按 `releaseCandidates`（Buffer :864-871）释放，journey 标记 `CANCELLED`（"HANDOFF_RETURNED_TO_OTHER_CHECKOUT"，Coordinator :1400）。
- **失败重试**：见第 4.3 节。

---

## 4. 难题清单（规格必须覆盖，每项须有决策）

### 4.1 并发两笔（前一单挂起等支付通知 + 后一单已进结算页）

- **现状**：槽位按包划分（`slotId = shopping-package:包名`，单包单槽，Buffer :14-18）。journey 作键后，同包两笔 journey 不能共存在单槽内，会互相覆盖栅栏。
- **决策草案**：**槽位键改为 journeyId（同包多 journey 共存）**。理由：单包单 journey 抢占会丢失前一单的飞行中快照（前一单可能正等支付通知落账）。多 journey 共存 = 每个 journey 独立 SlotState，栅栏/墓碑/候选互不干扰。
- **影响**：`SlotState`（Buffer :217-225）外层键从 slotId 改为 journeyId；`store`（:295-300 槽重建）、`consumeForAi`（:619-638 栅栏）、`retainForPaymentHandoff`（:496）、`restoreFromPaymentHandoff`（:544-549）、`hasCandidate`（:418）、`findReference`（:573）均按 journeyId 索引。
- **歧义兜底**：支付证据到达时若多个 journey 候选（同金额同时间窗），沿用现状 `resolveSessionForPayment`（Matcher :246）的 Ambiguous 输出 → 待确认中心让用户选择，不强行合并。

### 4.2 拆分支付（一笔订单分多次付款）

- **场景**：订单总额 100 元，先付 60 元再付 40 元；或部分付款后改用其他支付方式。
- **决策草案**：journey 不绑定单笔支付金额。journey 锚点的金额字段 = 订单总额（结算页显示），单笔支付证据按 `journeyId + 实付金额` 匹配。**拆分支付进待确认**（合并支付的已知限制，DECISIONS 第五节"MVP 不自动拆分，进待确认由用户手动拆分"），不自动合并多次支付到同一 journey。
- **影响**：`CheckoutPaymentMatcher.evaluate`（:1908）的金额覆盖判定（AI ≥ 支付视为正常）保持；AI < 支付或多次支付 → `NEEDS_CONFIRMATION`。

### 4.3 失败重试（AI 失败 / 用户重试）

- **现状**：`setUserRetryContext`（Coordinator :904-1046）在 :915-927 继承会话、:1015 生成新 sessionId（USER_RETRY 路径）。`advanceStatus`（Matcher :1371）状态机推进。
- **决策草案**：USER_RETRY **续接原 journey**，不新建 journey。状态重置到 `SNAPSHOT_BUFFERED`（或 `AI_TRIGGER_ARMED`），允许重新截图 + 重新派发 AI。`attemptedAutomaticCaptures`（Coordinator :393）按 `(journeyId, fingerprint)` 去重——journey 内漂移产生新指纹仍可重截（修复现状"沿用即永不重截"的副作用）。
- **影响**：`setUserRetryContext` :1015 的 sessionId 生成改为复用 journeyId；Buffer `:317-320` 用户重试解墓碑逻辑保留（墓碑按 journey 生效）。

### 4.4 journey 与 buffer 栅栏的新键语义

- **现状两层键**（B 核实）：
  - 槽位归属键 = `SlotState.sessionId`（会话 UUID，Coordinator :298/:1431/:1015 生成，Buffer 只存不生成）。
  - 交接消费栅栏 = `SlotState.handoffPageFingerprint`（:224，经 `resolveHandoffFenceFingerprint` :135-146 校正后的指纹）。
- **改造**：两层键统一为 journeyId。
  - **栅栏拦截**（`consumeForAi` :619-638）：现状"请求指纹 ≠ 栅栏指纹 → 拒绝（NO_MATCHING_PAGE_FINGERPRINT/AMBIGUOUS_CANDIDATES）"→ 改为"**同 journey 放行**（取 journey 内最新候选 by revision），**异 journey 拦截**"。
  - **栅栏校正**（`resolveHandoffFenceFingerprint` :135-146）：journey 键下退化为"选 journey 内最新候选"或整体取消（指纹漂移不再改变归属，无需校正）。
  - **墓碑**（`consumedRequestKey` :610-612）：按 journey 生效，同 journey 不重复发 AI（`JOURNEY_ALREADY_TRIGGERED` wire 名天然一致）。新订单 = 新 journey = 新墓碑，不再被同包同指纹旧墓碑挡住。
  - **交接**（`retainForPaymentHandoff` :496/:505-510、`restoreFromPaymentHandoff` :544-549）：sessionId 参数换 journeyId；restore 的双精确匹配（sessionId+指纹）简化为"同 journey 即恢复"。
  - **handoff 标记**（`hasPaymentHandoffFence` :467-474）：journeyId 匹配 + 栅栏非空即 true。
- **对外语义保持**：`CheckoutRequestKey.from(sessionId, shoppingPackage, pageFingerprint)`（Contract :315-322）格式不变；journey 需携带 sessionId 或可映射，RN 侧无感。

---

## 5. 改动范围（对齐现状行号）

### 5.1 Coordinator：handleStable/handleInvalidated → JourneyState 状态机

- **现状**：字段组合状态机（无显式枚举）——`activeCheckout`（:397，`ActiveCheckout` :321-329，key=`CaptureKey(sessionId, pageFingerprint)` :316-319）、`handoffCheckout`（:401/:362-365）、`pendingCapture`/`queuedAutomatic`/`queuedManual`（:398-400）、`attemptedAutomaticCaptures`（:393）。
- **改造**：`handleStable`（:1337-1525）/`handleInvalidated`（:1527-1667）重写为以 journeyId 为键的状态机。
  - 订单边界判定（`startsNewSession` :1413-1416 / `driftWithinSameCheckout` :1409-1416）改为 journey 锚点比较：同 journey 指纹漂移天然沿用，不再需要 `hasRetainableCandidate`（:1318-1335）启发式兜底。
  - 漂移复用不重截（:1488-1492）改为"journey 内有未过期候选则不重截，候选过期则重截"。
  - 交接恢复（:1358-1402 `restoreFromPaymentHandoff`）、挂起接替（:1550-1575 `shouldReplaceHandoffCheckout` 纯函数 :202-206）、keepPendingForHandoff（:1597-1611）、补设栅栏（finishCaptureOnMainThread :1923-1939）均按 journeyId 适配。
- **触及但语义保持**：`finishCaptureOnMainThread`（:1843-1942，`belongsToHandoff` :1860、`keyMatches` :1861）、`drainQueuedCapture`（:2001-2050 :2034-2047）、`requestManualCapture`（:1049-1182）、`currentPaymentHandoffReference`（:1189-1206 :1196）、`handoffCheckoutHasSnapshot`（:805）。

### 5.2 Buffer：栅栏键 会话指纹 → journey

- 键字段：`SlotState.sessionId`（:217）→ journeyId（或新增 journeyId 字段，sessionId 保留对外）。
- `store`（:249-377）：:287-293 墓碑、:288/:295-300 归属与槽重建、:317-320 重试解墓碑、:346 写入。
- `consumeForAi`（:587-721）：:610-612 墓碑、:619-638 栅栏拦截、:707-711 `CheckoutRequestKey.from`。
- `retainForPaymentHandoff`（:478-531）：:496 会话匹配、:505-510 指纹校正（退化）。
- `restoreFromPaymentHandoff`（:535-550）：:544-549 双精确匹配（简化）。
- `hasCandidate`（:418）、`hasNonCheckoutManualCandidate`（:439）、`hasPaymentHandoffFence`（:467-474 :473）、`discard`（:736）、`clear`（:780）、`releaseCandidates`（:870）、`findReference`（:573）。
- **对外载体不变**：`CheckoutSnapshot`（:26-49）、`CheckoutSnapshotReference`（:52-63）、`SnapshotCaptureRequest`（:66-77）的 sessionId 字段保留。

### 5.3 抽出 HandoffStateHolder（收敛三套真相源）

- **现状**：HandoffStateHolder **尚未抽出**（已核查 Service 全文无该类）。三套真相源分散：
  1. Coordinator：`activeCheckout`/`handoffCheckout`/`pendingCapture`（:397-401）。
  2. Buffer：`SlotState`（:217-225，sessionId/handoffPageFingerprint/consumedRequestKey）。
  3. AccessibilityService：handoff 相关内联状态（observePaymentResultIfApplicable :1348、recordPaymentResultEvidence :1975 等）。
- **改造**：抽 `HandoffStateHolder` 单例，统一持有"当前 journey / 挂起 journey / 飞行请求 / 交接栅栏"四类状态，Coordinator/Buffer/Service 读写均经它。消除三套对同一真相的分散维护。

### 5.4 支付结果兜底通道并入主门禁

- **现状平行实现**：`encodePaymentScreenshot`（Service :1778）/ `recognizePaymentResult`（client 调用 :1674）/ `recordPaymentResultEvidence`（:1975）是一条独立于"自动截图主路径"的支付结果兜底通道。
- **改造**：兜底通道并入主门禁策略对象，**共用敏感页 7 层防线与证据门禁**（检测器、提交动作、手动截图、buffer.store、重试上下文、routeLocked、通知回退入口）。消除 `encodePaymentScreenshot`/`recognizePaymentResult` 与主路径的平行实现重复。
- **触及**：`observePaymentResultIfApplicable`（:1348/:709/:809）、`recordPaymentResultEvidence`（:1975/:1505/:1751）。

---

## 6. 三个缺口的修复

1. **缺口1：advanceStatus 非法迁移静默 no-op**
   - 位置：`CheckoutPaymentMatcher.advanceStatus`（:1371-1392），:1382-1384 `if (!currentState.canTransitionTo(targetState)) return session`。
   - 现状：非法迁移静默返回原 session，无日志无异常。状态机不一致通常是 bug 信号，静默会掩盖问题。
   - 修复：非法迁移抛 `IllegalStateException` 或至少记 W 级日志（含 from/to/sessionId），让不一致可观测。

2. **缺口2：LedgerRepository 绕过状态机直写**
   - 位置：`LedgerRepository.confirmPendingTransaction`（:318）内 :429-430 直写 `status="COMPLETED"/journeyState="COMPLETED"`；`ignorePendingTransaction`（:479）内 :516-517 直写 `status="CANCELLED"/journeyState="DISMISSED"`。
   - 现状：直写 journeyState 绕过 `advanceStatus` 的 `canTransitionTo` 校验，可能写入非法迁移。
   - 修复：改为调 `matcher.advanceStatus`（或等价状态机推进），保证所有 journey 状态变更经统一门禁。

3. **缺口3：CheckoutJourney 包装类从未实例化**
   - 位置：`CheckoutContract.kt :329-364`（`data class CheckoutJourney`，含 `transitionTo` :349-355、`bindRequestKey` :357-363）。
   - 现状：全仓无实例化点（删除候选）。
   - 修复：journey 化后**正式启用为 journey 载体**（承载 journeyId/state/锚点/expiresAt），或删除以免误导。规格建议启用（journey 需要独立载体，HandoffStateHolder 持有 CheckoutJourney）。

---

## 7. 明确不做（不变式，违反即打回）

1. **敏感页 7 层防线不削弱**：检测器、提交动作、手动截图、buffer.store、重试上下文、routeLocked、通知回退入口——密码/验证码/付款码永不截图、永不入 buffer、永不送 AI。
2. **AI 未验证证据永不自动落账**：`SUCCESS_AI_UNVERIFIED`/`PAYMENT_STATUS_AI_UNVERIFIED` 只进待确认。
3. **快照仅内存暂存**，释放必抹零，任何路径不得写盘。
4. **每会话 AI 请求预算上限不变**（现状 2 次），被拒不扣预算。
5. **用户手动操作优先级最高**，规则命中不阻断用户确认。
6. **公开 SessionStatus 8 值不变**（`toPublicSessionStatus` :272-289），journey 16 态是内部状态，不新增第 9 个公开值。

---

## 8. 不崩塌保障与验收线

### 8.1 执行流程

1. 分支开发（`roadmap/batch4-journey`）。
2. 特征测试全绿：批 0 现有 193 例 + 新增 journey 特征测试（journey 出生/死亡、并发两笔、漂移天然沿用、栅栏 journey 键、墓碑 journey 键、HandoffStateHolder 收敛）。**有意行为变化逐条列出**并在测试中钉死。
3. 设备矩阵全过（小米 17u 主力 + AOSP）。
4. 真机日常浸泡 ≥1 周，每次真实支付后核对黄金脚本正向用例。
5. 单 PR 合入；回滚 = revert 或重装旧 APK。

### 8.2 验收线

- 特征测试全绿（含 journey 新特征）。
- 设备矩阵全过。
- 真机浸泡 ≥1 周，连续真实支付无快照丢失。
- 黄金脚本正向全过、负向全静默。
- "完全可用"定义（ROADMAP 第十节）：结算页自动截图在连续真机日常使用中无快照丢失；付款码被扫 / 扫商户码 / 支付结果页三种场景的钱 100% 出现在已入账或待确认；pending 即时提醒、一键确认；敏感页零截图。

### 8.3 回滚

- revert 单 PR 即回滚（journey 化前的会话指纹行为）。
- 或重装 journey 化前的 APK。

---

## 9. 现状行号索引（编码时核对）

| 主题 | 文件 | 行号 |
|---|---|---|
| 16 态 + canTransitionTo | CheckoutContract.kt | :191-266 |
| CheckoutJourney 包装类（缺口3） | CheckoutContract.kt | :329-364 |
| CheckoutRequestKey | CheckoutContract.kt | :308-326 |
| ensureSession（journey 出生） | CheckoutPaymentMatcher.kt | :166-178 |
| advanceStatus + 缺口1 | CheckoutPaymentMatcher.kt | :1371-1392（:1382-1384） |
| resolveSessionForPayment | CheckoutPaymentMatcher.kt | :246 |
| recordPaymentEvidence / recordOrphanPaymentSignal | CheckoutPaymentMatcher.kt | :608 / :419 |
| evaluate（金额覆盖判定） | CheckoutPaymentMatcher.kt | :1908 |
| 缺口2（LedgerRepository 直写） | LedgerRepository.kt | :429-430 / :516-517 |
| SlotState + 栅栏键 | CheckoutSnapshotBuffer.kt | :217-225 |
| resolveHandoffFenceFingerprint | CheckoutSnapshotBuffer.kt | :135-146（调用 :505-509） |
| store / consumeForAi / retain / restore | CheckoutSnapshotBuffer.kt | :249-377 / :587-721 / :478-531 / :535-550 |
| handleStable / handleInvalidated | CheckoutSnapshotCaptureCoordinator.kt | :1337-1525 / :1527-1667 |
| 漂移沿用 / 复用不重截 | CheckoutSnapshotCaptureCoordinator.kt | :1409-1416 / :1488-1492 |
| keepPendingForHandoff / 补设栅栏 | CheckoutSnapshotCaptureCoordinator.kt | :1597-1611 / :1923-1939 |
| sessionIdFactory | CheckoutSnapshotCaptureCoordinator.kt | :298（:1431/:1015 生成） |
| observePaymentResultIfApplicable | CheckoutAccessibilityService.kt | :1348 |
| encodePaymentScreenshot / recognizePaymentResult | CheckoutAccessibilityService.kt | :1778 / :1674 |
| recordPaymentResultEvidence | CheckoutAccessibilityService.kt | :1975 |

> 注：本规格行号基于 2026-08-31 代码快照（commit 0966fcf 之后）。编码前以最新代码核对；批 0 抽纯引擎后 `resolveHandoffFenceFingerprint` 已从 :458-462 上移至 :135-146。

### 规格 rebase 核对清单（2026-09-09，动工前必读）

快照基线之后主分支已合入 113a7ff（2026-09-06 用户断言通道，Service +383/-1）与 `fix/manual-capture-priority` 4 提交（2026-09-09，协调器/Buffer/Overlay）。第 5 节全部行号需以 `9da8171` 重核，并补充以下核对项：

1. **断言通道是 5.4 之外的第二条平行通道**：`user-asserted:` 专用槽位（Service :87-93）、`performUserAssertedCapture`（:2534）、`assertedSessionIds`（:361）、`promoteAssertedSessionToPending`（Matcher :1328）的生命周期须一并纳入 5.3 HandoffStateHolder 收敛范围，否则规格落地即过时。
2. **本分支 4 个触点**（详见 `docs/qa/checkout-manual-capture-priority-fix.md` 第六节）：
   - requestManualCapture 抢占段（Coordinator :1157-1188，`PREEMPTED_BY_MANUAL_CAPTURE` :315）——journey 化时手动抢占语义必须保留；
   - store 请求 target 覆盖（finishCaptureOnMainThread :1968-1971，手动来源挂 USER_RETRY target）——5.2 Buffer 改造时注意 :317-320 清墓碑语义依赖；
   - consumeForAi 无指纹~~手动优先~~ → ⚠️ **2026-09-12（D-16 = B）：改为"自动优先 + 自动被拒才回退手动"**（`Buffer :687-701`）——与 5.2 栅栏/歧义改造合并设计；
   - Overlay 可点集合含 CAPTURED_WAITING_PAYMENT（Overlay :752-760）。
3. **带入批 4 的三条已知风险**：PREEMPTED 后按钮闪烁（handleCancelled else 分支 → HIDDEN，建议加入保持分支）；无指纹消费 requestKey 以手动候选指纹生成（锚点偏移，matcher 集成覆盖待确认）；抢占的系统级撞车窗口待 G3-2 真机用例证明（`docs/qa/golden-path-regression.md`）。
4. **2026-09-09 首轮真机验证新增两个批 4 佐证缺陷**（证据：`docs/qa/device/2026-09-09-g3-manual-capture-round1.logcat.txt`，详见 `docs/qa/checkout-manual-capture-priority-fix.md` 5.3 节）：
   - **X1 支付交接后会话分裂**：交接消费并派发 AI（f7fa9cb7）后 1ms，协调器以 `HANDOFF_RETURNED_TO_OTHER_CHECKOUT` 丢弃刚挂起的槽位（迟到稳定事件误判新订单），Service 支付上下文 fallback 新会话（a42c902f），支付证据落 `matched=false, WAITING_ENRICHMENT`，AI 识别与证据永远无法匹配 → 自动入账断裂、纯待确认+弹窗。直接印证 5.3"三套真相源分裂"改造的必要性；journey 化时需一并治理 handleStable 的 `HANDOFF_RETURNED_TO_OTHER_CHECKOUT` 分支对交接挂起期的迟到稳定事件处理。
   - **X2 结算截图 AI 任务疑似无超时挂起**：任务协程已启动（会话预写入完成）但 36 分钟零生命周期日志（无结束/失败/取消）。需核查 `CheckoutAiVisionClient.recognize()` 超时传播，建议列入批 4 外围修复项。
   - **决策项（批 4 前确认）**：付款确认弹窗是否增加"信息充足不弹"门禁（现 `PendingPromptPolicy` 无该判断，"不弹"仅靠自动入账成功间接实现）。
     > ⚠️ **2026-09-12 已裁定（D-15 + D-21）**：**不采纳"信息充足不弹"这一支**；维持"手动只免除页面分类、其余判断保留"，并把"用户点了却没落待确认"的原因**显式反馈到弹窗**；另**"0 金额占位"改为允许弹窗并要求补金额**（含"确认按钮需金额 > 0"与"断言排除条件集合化"两项配套）。出处：`CONFLICT_REVIEW_SESSION_2026-09-11.md` G5.1/G5.2。
5. **Z1 修复设计已定稿（2026-09-10，详见修复记录 5.7 节）——指纹轮播漂移治理，并入批 4**：
   - **Y3 前置**：classifyPageRole 商品页特征（productTerms/"已选："/`tv_select_sku` 语义）前置排除 CHECKOUT 误判——批 4 第一刀，同步缓解"持续处理中"；
     > ⚠️ **2026-09-12 定性已推翻（G1-2 用户裁定）**：**商品规格选择层"本来就属于该商品页面，需要截图"**，规格切换导致价格/商品变化**必须重截**。
     > ⇒ **"规格层被判为可截"不是误判、不是缺陷**，而是**期望行为**。**本条的"批 4 第一刀 = Y3 前置排除"方向与设计对立** —— 待处理项改为 **"重新评估 `isProductSpecSelectionLayer`（Y3）的存废"**（`ISSUE_REGISTER` P7 已同步校正）。
     > ⇒ 但 **L3 跨帧易变性检测（下一条）与设计一致，仍有效**（其为"排除轮播漂移"且**命中 `finalAmountTerms` 的 token 永不入池**，所以"换商品"仍会改指纹）。
   - **L3 跨帧易变性检测（主攻）**：同窗口类名下 N 秒内更换 ≥2 次的 token 标记易变、只出指纹保留信号；易变池按 包名+窗口类名 维护、命中 finalAmountTerms 的 token 永不入池（换商品重截护栏）；全 App 自动适配、抗改版；
   - **L4 分应用黑名单仅作 JSON 配置兜底**，不写代码；L2 内容归一化视 L3 效果决定；
   - 与 journey 锚点（包+类名+金额）互补：L3 稳指纹、journey 稳会话，双保险消除 `NEW_CHECKOUT_SESSION`/`HANDOFF_RETURNED_TO_OTHER_CHECKOUT` 对含手动候选槽位的整槽丢弃。
   - **Z2 已修（40c36be，非批 4）**：断言待确认静默化，弹窗/通知触发权归还支付证据链路；批 4 journey 化时注意 promoteAssertedSessionToPending 与该协议常量（PendingSourceTypes）的依赖关系。
