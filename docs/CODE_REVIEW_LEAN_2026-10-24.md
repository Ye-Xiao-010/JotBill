# 精简版代码审查（2026-10-24）

> **状态**：[滚动更新] —— 审查结论 + 修复记录（§七/§九~§十七）。场景级审查（§十七）已把 16 个用户场景逐条对齐代码现实，仍遗留待拍板清单与全部真机验证
>
> | 项 | 值 |
> |---|---|
> | 审查对象 | 分支 `feat/lean-version` @ `9c6351b`（2026-10-22 15:48） |
> | 审查方式 | 通读生产代码 + 全仓 grep 复核 + 重跑编译与单测；**未取真机日志** |
> | 基线核实 | `:app:compileDebugKotlin` **BUILD SUCCESSFUL**；`:app:testDebugUnitTest` **202 / 0 失败 / 0 忽略**（2026-10-24 09:15 重跑） |
> | 标记 | 🔴 阻断 L1 · 🟠 数据正确性 · 🟡 鲁棒性 · ⚪ 残留/维护性 |

---

## 一、结论摘要

**代码能编译、单测全绿，但"全绿"不等于"链路完整"。** 本次审查确认 5 个行为缺陷、1 个规格矛盾、13 处精简版残留死代码、1 条验收标准本身不可执行。

最重要的一条：**弹窗候选只有一个槽位，且没有队列/补播**。第一笔在等待 MIUI 还权时，第二笔的弹窗事件被直接丢弃 —— 待确认卡照建、通知照发，但**弹窗永久不出现**。L1 要求连续 20 笔，这是最可能在验收时暴露的漏弹来源。

其次：**90 秒同额落库去重会静默合并真实的第二笔同额支付**。这与 `9c6351b` 自己主张的"误杀比误报严重得多"直接冲突，且文档 §七 写的"相隔 <30 秒"与实际 90 秒不符。

单测 202 项全绿，但其中至少 3 个测试类保护的是**精简版不可达的路径**（手动补截上下文、session/candidate 匹配），给人"覆盖充分"的错觉。

---

## 二、缺陷清单

### 🟠 P1-1 弹窗候选单槽位，第二笔被静默丢弃

**位置**：`overlay/PendingConfirmOverlayManager.kt`

```kotlin
// :325-329  onPendingChange
if (overlayView != null || waitingCandidate != null || inFlightCandidate != null) {
    // 已有弹窗 / 挂起候选 / 在途上屏：不叠加
    return
}
```

```kotlin
// :364-366  maybeShowCandidate
if (overlayView != null || waitingCandidate != null || inFlightCandidate != null) {
    return
}
```

**触发**：第一笔支付到达时门禁返回 `NO_OVERLAY_PERMISSION`（MIUI 进后台收回 `SYSTEM_ALERT_WINDOW`，真机实测 `waitedMs=1113 / 1976 / 4031`，最长记录 57s），候选进入 `waitingCandidate` 挂起。此窗口内第二笔支付的 `PendingChangeHub.emit` 到达 —— 直接 `return`。

**影响**：
- 第二笔的 pending 行**照常落库**（`recordOrphanPaymentSignal` 不受影响），系统通知**照常发送**
- 但弹窗事件**永久丢失**：没有队列、没有补播、没有任何"稍后重试"的记录
- `PendingPromptPolicy` 对后续 `isNew=false` 的更新事件返回 `UPDATED_NOT_NEW`，也不会补弹

**为什么是 P1 而不是 P0**：连续两笔支付的自然间隔通常大于 4 秒，不一定命中；但 L1 要求 20 笔连续跑，且 MIUI 上挂起是常态而非异常，20 笔里命中一次就会拉低命中率。**静默**是它比普通漏弹更严重的地方 —— 日志里只有一条 `弹窗门禁判定` 之后什么都没有，事后无法归因。

**建议修法**：把单个 `waitingCandidate` 改成有界队列（上限 2~3），当前弹窗关闭后按 FIFO 补播；或在 `return` 处记录 `弹窗候选丢弃: reason=SLOT_BUSY`，至少让 L1 的 miss 可归因。前者治本，后者是取证最低要求。

---

### 🟠 P1-2 90 秒同额落库去重会吞掉真实的第二笔同额支付

> ✅ **2026-10-24 已修**：窗口收窄至 **15 秒**，并新增**页面文本哈希**主判据
> （`pending/RecentResultObservation.kt`）。同包同额 + 同哈希 ⇒ 判同一笔（不论间隔）；
> 哈希不同 ⇒ 真实第二笔，正常建卡。事件键桶宽同步 30s → 15s，否则同桶内仍会撞 `pendingId`。
> 验证：212 项单测全绿；关掉哈希登记后 3 条相关用例如期转红（判别性成立）。
> 详见 §七「修复记录」。

**位置**：
- `recognition/CheckoutPaymentMatcher.kt:118` `ORPHAN_DEDUP_WINDOW_MILLIS = 90_000L`
- `data/dao/PendingTransactionDao.kt:76-92` `findRecentPendingByPlatformAndAmount`

```sql
WHERE status = 'pending'
  AND platform = :platform
  AND amount_minor = :amountMinor
  AND occurred_at BETWEEN :windowStart AND :windowEnd
```

查询窗口是 `occurredAt ± 90s`，即**前后各 90 秒**。

**影响**：用户连续两笔**同额**真实支付（同平台、间隔 ≤90 秒），第二笔被判为"同一笔的重复观测"：
- 不建新卡
- 不 emit、不弹窗
- 只把 `conflictReason` 合并进第一张卡
- **用户永远不会看到第二笔**（除非自己去翻支付 App 账单）

**与既有文档/提交的冲突**：
- `00-现状正本.md` §七 写"同包同金额两单相隔 **<30 秒**仍撞去重键" —— 实际是 90 秒
- `9c6351b` 的提交说明明确主张"分钟级窗口会静默吞掉真实的第二笔同额支付 —— 误杀比误报严重得多"，但同一分支上 `ORPHAN_DEDUP_WINDOW_MILLIS` 就是 90 秒

**边界说明**：这是 D-25 的既有设计（2026-09-29 为修"同一笔落两张卡"而加），**不是本次改造引入**。但它与精简版"每笔支付都该被记录"的产品目标冲突，且方向是丢账。审查建议：把窗口收窄到 30 秒（与事件键桶同宽），或改为"同额 + 同 `rawTextHash`"才判重复 —— 同一笔支付的重复观测文本哈希相同，真实第二笔的哈希不同。

---

### 🟡 P2-1 成功反馈 1.5 秒后无身份校验地关闭"当前弹窗"

**位置**：`overlay/PendingConfirmOverlayManager.kt:1009-1016` + `dismissInternal:1059`

```kotlin
private fun showSuccessFeedback(view: View, amountMinor: Long, categoryName: String) {
    ...
    mainHandler.postDelayed({ dismissInternal("SUCCESS") }, SUCCESS_DISPLAY_DELAY)  // 1500ms
}
```

`dismissInternal` 无差别关闭 `overlayView`，**不校验它是不是发起确认的那一个**。

**触发**：
1. 用户在弹窗 A 点"确认入账"，`confirm` 协程开始写库
2. 写库期间 A 被 `AUTO_DISMISS`（30s）或用户点关闭 → `overlayView = null`
3. 确认成功回调 `mainHandler.post` → `showSuccessFeedback(viewA, …)` → 1.5 秒后 `dismissInternal("SUCCESS")`
4. 此时弹窗 B 已经上屏 → **B 被误关**

**对比**：同一文件里的 `scheduleVisibilityCheck` 有身份校验（`if (overlayView !== view) return`），`showSuccessFeedback` 没有 —— 同一类问题在同一文件里已有一半的正确写法。

**影响**：用户看到下一张弹窗闪一下就消失。pending 不丢，但 L1/L2 会记成一次 miss/异常。

**建议修法**：`showSuccessFeedback` 捕获 `displayedPendingId`，延迟回调里比对 `overlayView === view` 再 `dismissInternal`。

---

### 🟡 P2-2 转账 emit 了，但策略层仍然拒绝弹窗 —— 与提交说明矛盾

> ✅ **2026-10-24 用户裁定：转账不弹窗。** 代码现状即正确，无需改动。
> 已在 `PendingPromptPolicy` 补 KDoc 明确"这是有意为之，不是遗漏"，
> 避免后人据 `0481878` 的提交说明再去"修"。
> 转账仍会 emit 并进入待确认中心（实时刷新），只是不弹悬浮窗。

**位置**：`pending/PendingPromptPolicy.kt:103`

```kotlin
if (info.type != "expense") return Decision.Skip(SkipReason.NOT_EXPENSE)
```

**事实**：
- `recordPersonalTransfer`（`CheckoutPaymentMatcher:483`）已按 `0481878` 补上 `PendingChangeHub.emit`
- 但它建的是 `type = "transfer"`
- `PendingPromptPolicy.evaluate` 在 `NOT_EXPENSE` 分支直接跳过

⇒ **转账进待确认中心，但不弹窗。**

而 `0481878` 的提交说明写的是："待确认中心里能看到这张卡，但付款确认弹窗永远不弹。真机验收要求'识别到支付成功页就弹出来'，**转账同属支付，不能例外**。"

**结论**：要么提交说明夸大了修复范围（实际只修了"实时刷新"，没修"弹窗"），要么策略层漏改。**需要产品口径确认**，不能由审查单方面判定。若目标是"转账也弹窗"，`NOT_EXPENSE` 需放行 `transfer`，且弹窗 UI 的"支出"文案要同步改（当前布局按支出写死）。

---

### 🟡 P2-3 冷却映射会被同额并存的第二张卡覆盖

**位置**：`pending/RecentPaymentCooldown.kt:114-139`

```kotlin
fun rememberCard(pendingId: String, paymentPackage: String, amountMinor: Long) {
    val key = cooldownKey(paymentPackage, amountMinor)
    synchronized(lock) {
        cardKeys[pendingId] = key
        // 换 key 时清理上一条映射
        val stale = cardKeys.entries.firstOrNull { it.key != pendingId && it.value == key }
        if (stale != null) cardKeys.remove(stale.key)
        ...
```

**触发**：同平台同额两笔都未确认，两张卡并存（这在 P1-2 的 90 秒窗口外是合法的）。第二张 `rememberCard` 会把第一张的 `pendingId → key` 映射删掉。

**影响**：用户确认**第一张**时 `markConsumed` 返回 null ⇒ 冷却不启动 ⇒ 若仍停在结果页，会再建第三张卡。方向是误报（可删），不是丢账。

**为什么只算 P2**：同额两笔并存 + 都未确认 + 用户确认的是先登记的那张，三个条件同时成立才触发。

---

### 🟡 P2-4 首个 root 解析失败即 return，不尝试其余 root

**位置**：`recognition/CheckoutAccessibilityService.kt:796-891`

`findPaymentResultRoots` 会依次加入 `rootInActiveWindow` / `event.source` / 活动窗口 root。但循环体里，第一个 root 若返回 `NotPaymentResult` 或 `Failure`，直接 `return`（`:886`、`:890`），**不会继续检查下一个 root**。

**触发**：支付成功页上叠加了系统对话框（权限弹窗、输入法、MIUI 安全提示），`rootInActiveWindow` 指向对话框而非支付页。

**影响**：这一帧漏判。若后续没有新事件（成功页静止），该笔永久 miss。

**建议修法**：把 `return` 改成 `continue`，并只在 `Success`/`PersonalTransfer` 时 `return`。

---

### 🟡 P2-5 结果页没有轮询兜底，完全依赖无障碍事件驱动

**事实**（grep 复核）：

```
grep -n "postDelayed|scheduleAtFixedRate|Timer|repeat(" CheckoutAccessibilityService.kt
→ 无任何匹配
```

`observePaymentResultIfApplicable` 只在 `onAccessibilityEvent` 里被调用，没有任何定时重扫。截图链路的 `PAYMENT_SCREENSHOT_FALLBACK_WATCHDOG_MILLIS` 看门狗已随整链删除。

**影响**：支付成功页出现的那一帧若被判敏感（根因 C）或节点树为空，且之后页面**完全静止**（无事件），就再也不会重试。成功页通常有动画/倒计时会产生事件，所以这是概率性风险而非必现 —— 但根因 C 是**稳定复现**的（10-12 支付宝单笔被抑制 122 次），两者叠加时，"全被挡 + 无后续事件"就是零证据。

**建议**：进入支付包后，用一个 500ms~1s 的短周期重扫兜底（仅在支付包前台且未取到证据时），上限 5~10 秒。这比调敏感词表安全得多。

---

### 🟡 P2-6 服务层不校验分类/账户存在性

**位置**：`pending/PendingConfirmService.kt:89-115`

`confirm` 只校验 `validateBillType` / `validateSourceType` / `isValidAmount`，`categoryId` / `accountId` 直接取 `overrides ?: rulePatch ?: pending`，三者皆 null 时**不拒绝**，把 null 写进账单。

**当前为什么没出事**：弹窗层 `PendingConfirmReadiness.isReady` 要求分类+账户+金额三者齐备，按钮才可点。**UI 单点保障**。

**风险**：任何绕过弹窗的入口（RN 待确认中心、未来的批量确认、脚本调用）传 null 就会落出无分类/无账户的账单。属于纵深防御缺失，不是当前可复现缺陷。

---

## 三、精简版残留死代码（⚪ 不阻断，但影响"精简"的可信度）

以下全部经过全仓 grep 复核，**不是推测**。

| # | 位置 | 事实 | 影响 |
|---|---|---|---|
| 1 | `CheckoutAccessibilityService.kt:794,829` | `sawResultPage` 声明并赋值，**从未读取** | 编译警告级死变量 |
| 2 | `recognition/SensitivePageLatch.kt` 整类 | 4 处调用只维护状态；`isAssertionCaptureBlocked` / `isPaymentFallbackBlocked` / 两个 age 方法**零外部调用**；`latchReleased` 只用于打日志 | 每次购物页事件空转；断言通道已删 |
| 3 | `CheckoutAccessibilityService.kt:443-454` | `shouldSkipForeignEventCollection` **零调用**，且函数体三个分支全部 `return false` —— 逻辑已被掏空 | 整方法可删 |
| 4 | `CheckoutAccessibilityService.kt:574` | `pageDetector.detect(observation)` 结果只进 `logCheckoutGateSampled` | 结算页门禁判定链在精简版无行为作用，纯开销 |
| 5 | `CheckoutPaymentMatcher.kt:284` | `ensureSession` **零调用点** | 会话创建入口已断 |
| 6 | `CheckoutPaymentMatcher.kt:849` | `recordPaymentEvidence` 对新装用户**不可达**：`resolveSessionForPayment` 需要已有 candidate，而 candidate 只能由 AI 或它自己的 provisional 分支产生（鸡生蛋） | `candidate==null` / `WAITING_ENRICHMENT` / `promoteEvidenceOnly…` / `backfillEvidenceOnly…` / `upsertPendingForSession` / `candidateAfterPayment` / `evaluate` / `selectEvidence` / `mergeSessionEvidence` / `validatePayment` 约 1,200 行全部不可达 |
| 7 | `CheckoutContract.kt:172-200` | `UserRetryCaptureContext` 生产零引用；`CheckoutManualRetryFallbackTest` 仍在测它 | **测试在保护死代码** |
| 8 | `CheckoutContract.kt:27` | `canBufferSnapshot()` 零调用 | 截图链路残留 |
| 9 | `CheckoutContract.kt:160-169` | `SnapshotSourceReason.MANUAL_CAPTURE` / `SnapshotCaptureTarget` 零引用 | 死枚举 |
| 10 | `PaymentResultParser.kt:154,165` | `pageFingerprint` 参数在方法体内**完全未使用** | 死参数，且 KDoc 明确说"不含页面指纹" |
| 11 | `recognition/CheckoutPageDetector.kt:89` 等多处注释 | 仍描述"截图协调器""Native.d.ts" | `Native.d.ts` 已不存在；注释漂移 |
| 12 | `CheckoutPaymentMatcher.kt:1864` | `upsertPendingForSession` 插入后 emit，但**没有** `RecentPaymentCooldown.rememberCard` | 当前不可达；一旦会话链路恢复，`9c6351b` 的 bug 会在 session 路径复现 |
| 13 | `PendingPromptPolicy.kt:97-102` | `AI_VISION + USER_ASSERTED` 分支：`AI_VISION` 来源在精简版无产出方 | 死分支（无害） |

> **累计**：`CheckoutPaymentMatcher` 2,336 行里约一半在精简包不可达；`CheckoutAccessibilityService` 1,208 行里约 150 行是空转/日志专用。这不是"删干净了"，而是"主路径改道、旧路基保留"。

---

## 四、测试覆盖缺口

| 缺口 | 说明 |
|---|---|
| 候选单槽位丢弃 | 无任何测试覆盖 `waitingCandidate != null` 时新候选被丢弃 |
| 成功反馈误关下一张 | 无测试覆盖"确认中弹窗被关闭 → 延迟 dismiss 命中下一张" |
| `rememberCard` 同额并存 | `RecentPaymentCooldownTest` 8 条，`twoSequentialSameAmountPaymentsBothLand` 是**顺序**场景，不覆盖并存 |
| 冷却 × 建卡集成 | 只有 `RecentPaymentCooldown` 纯类单测，无 `recordOrphanPaymentSignal` 的集成断言 |
| 事件键 90s 记忆窗 | `PAYMENT_RESULT_EVENT_MEMORY_MILLIS=90s` 与 30s 桶的矛盾无测试 |
| 死路径测试 | `CheckoutManualRetryFallbackTest` 等仍全绿，但测的是不可达代码 |

**另一个事实**：`PAYMENT_RESULT_EVENT_MEMORY_MILLIS = 90_000L` 的 KDoc 说"须与 `ORPHAN_DEDUP_WINDOW_MILLIS` 保持同值"，但事件键本身是 `occurredAt / 30_000`。**键 30 秒就换，90 秒的记忆窗有 60 秒是无效的** —— 第 1 道防线实际只有 ≤30 秒效力。这一条注释与实现不符，且直接决定了第 2 道（90 秒落库去重）才是真正起作用的防线。

---

## 五、验收标准缺陷：L6 在精简版不可执行

`qa/lean-version-acceptance.md` L6 的操作是：

> 在系统设置里关掉无障碍服务，然后**完成一笔支付**（或用任意方式产生一条待确认）。

**问题**：精简版唯一的数据源是无障碍结果页（`CheckoutAccessibilityService:486` 的注释自己写着"这是精简版**唯一**的数据来源"）。无障碍关闭 ⇒ 零证据 ⇒ 零 pending ⇒ **不可能有通知兜底可验**。通知链路（`PaymentNotificationService`）已按 D-3 停用，也不能作为来源。

**结论**：L6 只能靠"导入账单 / 手动记账"产生待确认来验，不能用"完成一笔支付"。当前文档写法会让人在真机上白跑一遍，然后误判为"通知兜底失效"。

**建议**：把 L6 的操作改成"关闭无障碍 → 用「导入账单」或手动新增产生一条待确认 → 验证系统通知"，并注明"支付路径在无障碍关闭时不产证据，属预期"。

---

## 六、已知未修问题的复核（确认仍在）

| 问题 | 复核结果 |
|---|---|
| 根因 A：`FOREGROUND_IS_SELF` 死守卫 | **仍在**。`evaluateGate:458` 读 `lastForegroundPackage`，而它只由 `onObservationState` 喂入，两个调用点分别传 `shoppingPackage` / `paymentPackage`，`com.jotbill.app` 永远进不去 ⇒ 分支恒假。代码注释（`:237-268`）已如实记录回退原因，**不是遗漏** |
| 根因 C：成功页被判敏感 | **仍在**。`sensitiveTerms` 含"银行卡""二维码""付款码""指纹"，`includeHiddenText=true`，整页 OR。`CheckoutAccessibilityService:814-826` 命中即 `continue`，零产出 |
| 根因 B：时间窗打架 | **部分缓解**。`CANDIDATE_TTL_MILLIS` 15s→30s，但 `OBSERVATION_FRESH_MILLIS` 仍是 30s，两者相等而非"同量级收敛"；仍无"用户仍停留支付应用则延长"的逻辑 |
| `9c6351b` 冷却修复 | **代码在位**（`recordOrphanPaymentSignal` 两处 emit 前登记），**未真机验证**（提交说明自述） |
| 弹窗不绑定支付成功页 | **仍在**。门禁只要求"30 秒内有观察帧 + 非敏感 + 非本应用 + 有权限"，与"当前是否在支付成功页"无关 |

---

## 七、建议修复顺序

| 序 | 项 | 理由 |
|---|---|---|
| 1 | P1-1 候选队列 / 至少加丢弃日志 | 直接决定 L1 命中率，且改动局限在 `PendingConfirmOverlayManager` 单文件 |
| 2 | P2-4 首个 root 失败改 `continue` | 一行改动，纯收益 |
| 3 | P2-1 成功反馈加身份校验 | 一行改动，消除"误关下一张" |
| 4 | P1-2 同额去重窗口收窄或改判据 | 需要产品裁定（90s → 30s，还是改 `rawTextHash` 比对） |
| 5 | P2-2 转账是否弹窗 | 需要产品裁定，代码与提交说明二者必有一错 |
| 6 | P2-5 支付包短周期重扫 | 比动敏感词表安全，可作为根因 C 的替代缓解 |
| 7 | 死代码清理 | 单独一批、只删不加，验收仅"编译 + 202 全绿" |
| 8 | L6 验收标准改写 | 纯文档，避免真机白跑 |

> **不建议现在动根因 C（敏感词表）**：`ISSUE_2026-10-10` 已明确"风险最高，必须在 1、2 落地并重跑 L1 后再动"。本次审查确认该判断仍成立 —— 微信结果页的关键语义在隐藏 `contentDescription` 里，改敏感通道会连带打掉结果页解析。

---

## 九、修复记录（2026-10-24）

### 用户裁定

| # | 裁定 | 落地方式 |
|---|---|---|
| 1 | **转账不弹窗** | 代码现状即正确（`PendingPromptPolicy` 的 `NOT_EXPENSE`），补 KDoc 说明"有意为之" |
| 2 | **同额第二笔 15 秒内接受合并** | 时间窗 90s → 15s + 页面文本哈希主判据（不改 DB schema） |

### 改动清单

| 文件 | 改动 |
|---|---|
| `pending/RecentResultObservation.kt`（新增） | 内存表：建卡登记 `(包名, 金额) → rawTextHash`，`removed=true` 时清除，10 分钟过期、64 条上限、FIFO 淘汰 |
| `recognition/CheckoutPaymentMatcher.kt` | 在 `sourceEventKey` 幂等之后、D-25 之前插入哈希判据；两处建卡后 `remember`；`ORPHAN_DEDUP_WINDOW_MILLIS` 90s → 15s |
| `recognition/PaymentResultParser.kt` | 事件键桶宽 `30_000` → `15_000`（否则同桶内仍会撞 `pendingId`，哈希判据走不到） |
| `recognition/CheckoutAccessibilityService.kt` | `PAYMENT_RESULT_EVENT_MEMORY_MILLIS` 90s → 15s |
| `MainApplication.kt` | `RecentResultObservation.init()` |
| `pending/PendingPromptPolicy.kt` | 转账不弹窗的 KDoc + 窗口值同步 |
| `pending/RecentPaymentCooldown.kt` | 失效分析中的旧窗口值同步 |

### 测试

- 新增 `RecentResultObservationTest` 8 条：同哈希命中 / 不同哈希放行 / 包或金额不同不命中 /
  空哈希不登记 / 消费后解除 / 保留期过期 / FIFO 淘汰。
- `CheckoutPaymentMatcherIntegrationTest`：
  - 原 20 秒、60.393 秒两个真机用例改为**同哈希**语义（仍钉死"同一笔不建两张卡"）；
  - 新增 `孤儿不同哈希_间隔20秒视为两笔真实交易`；
  - 新增 `孤儿同哈希_前一笔确认后再次出现视为新交易`；
  - `孤儿去重_超过90秒窗口…` 改为 `超过15秒窗口且无哈希…`，间隔改 20 秒。
- `PaymentResultParserTest`：两个桶测试更名为 FifteenSecond，逻辑不变。

### 验证（实际输出）

```
:app:compileDebugKotlin   BUILD SUCCESSFUL in 59s
:app:testDebugUnitTest    BUILD SUCCESSFUL in 1m 1s
测试报告：212 / 0 失败 / 0 忽略（202 / 0 基线 + 10 条新增）
```

**判别性验证**：临时在 `RecentResultObservation.remember` 加哨兵使登记失效后重跑集成测试 ——

```
孤儿同哈希重复观测_冲突原因合并进既有卡 FAILED
孤儿同哈希重复观测_真机实测60393毫秒只建一张卡 FAILED
孤儿同哈希重复观测_间隔20秒不新建第二张卡 FAILED
19 tests completed, 3 failed
```

撤销哨兵后恢复 212/0/0。证明这 3 条确实由哈希判据支撑，不是恒真断言。

### 第二轮：数字统一 + 弹窗修复（2026-10-24）

#### 数字统一（用户确认）

| 项 | 改动 |
|---|---|
| 新增 `utils/PaymentTiming.kt` | `SAME_TXN_WINDOW_MILLIS = 15_000`、`CONSUMED_COOLDOWN_MILLIS = 15_000` |
| `PaymentResultParser` 桶宽 | 内联 `15_000` → 引用 `PaymentTiming` |
| `ORPHAN_DEDUP_WINDOW_MILLIS` | 引用 `PaymentTiming` |
| `PAYMENT_RESULT_EVENT_MEMORY_MILLIS` | 引用 `PaymentTiming` |
| `RecentPaymentCooldown.COOLDOWN_MILLIS` | **10 秒 → 15 秒**，与裁定对齐 |
| `RecentResultObservation` | **新增 15 秒匹配上限**；保留期同步为 15 秒；`findSameObservation` 显式写时间条件 |
| `PaymentResultParser.rawTextHash` | 拼入 `sha256(orderId)`，恢复订单号区分力（仍不落原文） |

> 取舍已确认：页面**不显示时间**时，同一笔停留超过 15 秒会各自建卡（误报方向），
> 换取"真实第二笔不被吞"。页面**显示时间**时 `occurredAt` 恒定，D-25 窗口天然命中，
> 不受此取舍影响。

#### 弹窗修复

| 问题 | 修法 |
|---|---|
| 入账中按钮被重新点亮 | 新增 `isConfirming`；`updateConfirmEnabled` 在该状态直接置灰返回；`Invalid`/异常分支才重置 |
| 已处理条目发假通知 | 确认入口即取消 `autoDismissRunnable`；`AlreadyHandled` 保持 `isConfirming=true` |
| 上屏自检失败不清 `overlayView` | 失败分支 `dismissInternal("NOT_ATTACHED")`（不走 AUTO_DISMISS 通知） |
| 旧卡回填污染新卡 | `loadCategories` / `loadAccounts` 的 `mainHandler.post` 首行加 `overlayView !== view` |
| 弹窗确认后 RN 缓存不刷新 | `useAppStateManager` 回前台时 `invalidateCache.bills()` + `recognition()` |

#### 测试

- `RecentResultObservationTest` 8 → 10 条：新增 15.000 秒命中、15.001 秒放行；FIFO 用例改**反序构造**（原正序写法字典序最小恰是首条，不判别）；超窗清理改为验证"条目真的被删除"。
- `CheckoutPaymentMatcherIntegrationTest`：10 秒同哈希合并、60.393 秒同哈希各自建卡（裁定取舍）、20 秒不同哈希建两张。
- `PaymentResultParserTest`：跨桶用例间隔 40 秒 → **20 秒**（`observedAt` 恰在 30 秒桶边界，20 秒在 30 秒桶下同桶、15 秒桶下跨桶 ⇒ 有判别性）。
- 一条用例首跑失败：测试时钟固定为 `START_NOW`，哈希窗口按观测时刻算，60 秒的 `occurredAt` 差被算成 0 秒。修法是第二次观测前 `now = secondOccurredAt`。

#### 验证（实际输出）

```
:app:compileDebugKotlin        BUILD SUCCESSFUL
:app:compileDebugUnitTestKotlin BUILD SUCCESSFUL
:app:testDebugUnitTest         BUILD SUCCESSFUL
测试报告：214 / 0 失败 / 0 忽略（202 基线 + 12 条新增）
```

### 未做 / 遗留

- ⚠️ **未真机验证**：需装包后跑 L1 20 笔。重点确认三件事：① 同额第二笔 15 秒外正常建卡；② 同一笔停留 >15 秒会多一张卡（裁定的预期代价）；③ 弹窗确认后切回 App 余额/账单立即刷新。
- 审查中的 P1-1（候选单槽位）、P2-4（首 root 失败即 return）**本次未修**，仍在。
- 死代码清单未清理（`SensitivePageLatch` 整类、`sawResultPage`、`shouldSkipForeignEventCollection` 等）。
- `RecentResultObservation.init()` 仍不幂等（重复注册监听器），因 `forget` 幂等暂不致错，留待清理批次。

---

## 十、子智能体复审（2026-10-24）

两个独立审查代理分别复核了「去重改动」与「弹窗逻辑与限制」。以下只列**经主审核实**的条目，
推断项已标注。

### A. 去重改动（针对 §九）

| 级别 | 问题 | 核实结果 |
|---|---|---|
| 🟠 **需拍板** | `RecentResultObservation.findSameObservation` 只按 `(包, 金额, hash)` 匹配，**没有 15 秒上限**，唯一时限是 10 分钟保留期。15.001 秒后的同哈希证据仍会合并 | ✅ 成立。与"超过 15 秒视为新交易"的字面裁定冲突。但加 15 秒上限会让 09-30 的 60.393 秒同一笔故障复现 —— 二者不可兼得，见下方「取舍」 |
| 🟠 | `sanitizeForHash` 把 `orderId` 替换成 `<order-id>`、3+ 位数字替换成 `<number>`，**订单号无法区分两笔** | ✅ 成立。`RecentResultObservation` KDoc 里"订单号至少一处不同 ⇒ 哈希不同"是错的，需修正 |
| 🟡 | `upsertPendingForSession` / `recordPersonalTransfer` 建卡后**未登记**哈希与冷却 | ✅ 成立。前者在精简版不可达（`ensureSession` 零调用），属潜在缺陷；后者转账不弹窗，但会重复建卡 |
| 🟡 | D-25 的时间窗用 `signal.occurredAt`（**页面时间**），而桶/事件记忆用观察时刻 | ✅ 成立。页面有时间时同一笔 `occurredAt` 恒定 ⇒ 时间窗永远命中；页面无时间时才按观测间隔生效。三处口径不一致 |
| 🟡 | `sameResultPageAcrossFifteenSecondBucketProducesDistinctEventKeys` 用 40 秒间隔、`observedAt=1_800_000_000_000`（`%30000==0`）⇒ **30 秒桶下同样通过**，无判别性 | ✅ 成立。应改 20 秒间隔（15s 跨桶、30s 同桶） |
| ⚪ | `prunesByInsertionOrderNotByIdOrdering` 用 `pending-0…64` 正序，字典序最小恰是 `pending-0` ⇒ 按 id 裁剪也通过 | ✅ 成立。需反序构造才有判别性 |
| ⚪ | `init()` 不幂等，集成测试每个 `@Before` 注册一次 | ✅ 成立，但 `forget` 幂等，不致假绿 |
| ⚪ | 时钟回拨时 `now - seenAt` 为负，条目不过期 | ✅ 成立，靠 64 条上限兜底 |

**取舍（需用户拍板）**：页面**无时间**时，同一笔的重复观测与"同额同文本的第二笔"无法区分。

- **当前实现**（哈希优先，10 分钟）：同一笔永远合并（修复 09-30）；真实第二笔若文本相同会被吞（误杀）。
- **加 15 秒上限**：严格遵守裁定；但同一笔停留 >15 秒会建第二张卡（09-30 复现，误报）。

页面**有时间**时不存在这个问题：两笔真实支付的时间必然不同 ⇒ 哈希不同 ⇒ 正常建卡。

### B. 弹窗逻辑与限制（新增发现，均非本次改动引入）

| 级别 | 问题 | 核实结果 |
|---|---|---|
| 🔴 | **弹窗确认入账后 RN 缓存不刷新**：`emitJsEvents` 发 `onBillCreated` / `onPendingChanged`，但 `src/` 全仓**零订阅者**（`addPendingChangedListener` 只有定义无调用），`refetchOnWindowFocus=false` | ✅ 成立，`AIChatScreen.tsx:1995` 的注释自己承认"grep 零命中"。用户切回 App 看到旧余额/旧账单，可能手工再记一遍 ⇒ 重复账单 |
| 🟠 | **入账中按钮会被重新启用**：`onConfirmClicked` 置 `isEnabled=false`，但 `updateConfirmEnabled` 被金额输入/分类/账户三处回调触发，会重新点亮 | ✅ 成立。再点一次 → `AlreadyHandled` → 成功页被错误页覆盖 |
| 🟠 | **已处理条目仍发"这笔还没记上"通知**：`showErrorFeedback(dismissAfter=true)` 不取消 `autoDismissRunnable`，30 秒后 `AUTO_DISMISS` 触发，此时 `displayedAutoDismissInfo` 仍在 | ✅ 成立（`AlreadyHandled` 场景）。通知之后无 `removed` 事件来 cancel，永久挂通知栏 |
| 🟡 | 上屏自检失败（`attached=false`）只打日志，不清 `overlayView` | ✅ 成立。30 秒内所有新候选被 `overlayView != null` 挡住（非永久，`AUTO_DISMISS` 会清） |
| 🟡 | `loadCategories` / `loadAccounts` 的 `mainHandler.post` 无 `overlayView !== view` 身份校验 | ✅ 成立。旧卡回调会污染新卡的 `categoryViews` 并预选分类 |
| 🟡 | `inFlightCandidate` 只在正常路径释放，`Error` 逃逸会让占位永久非空 | ✅ 成立，概率极低但后果是"进程内永久不再弹窗" |
| 🟡 | MIUI 实测收权 57s，`CANDIDATE_TTL_MILLIS` 仍是 30s，且重评只由观察帧驱动 | ✅ 成立（`PendingPromptPolicy` 注释自述 57s）。注释声称的修复不成立 |
| 🟡 | 0 金额占位：创建通知与超时通知都被 `amountMinor<=0` 跳过，按钮又必然置灰 30 秒超时 ⇒ 全链路零提醒 | ✅ 成立 |

> 未采纳的推断项：`displayOverlay` 不复核 `row.type`（需核对 matcher 的 emit，当前未证实）；
> `loading_container` 永不显示（布局默认 gone，代码只置 GONE，成立但纯 UI 瑕疵）。

---

## 十一、第二轮子智能体复审（数字统一 + 弹窗修复）

两个代理分别复核「时间口径与去重」「弹窗状态机与 RN 刷新」。以下为**经主审核实**的条目。

### 已修（本轮复审后）

| 级别 | 问题 | 修法 |
|---|---|---|
| 🔴 P0 | **`isConfirming` 未绑定卡片**：点确认后立刻点 X，`dismissInternal` 重置 `isConfirming=false`，旧协程返回后无身份校验 ⇒ ① `Confirmed` 的 1.5s `dismissInternal` 关掉**新卡**；② `AlreadyHandled` 残留 `true` 让新卡按钮永久灰；③ `Invalid/null` 抹掉新卡的确认状态 | `mainHandler.post` 首行加 `overlayView !== view` 守卫 |
| 🔴 P0 | **`AlreadyHandled` 错误页无出口**：`showErrorFeedback` 把 `content_main` 置 GONE（X/稍后都在其中），而 `autoDismiss` 已在确认入口取消 ⇒ 不点"重试"就永久停在全屏遮罩 | 该分支排 3 秒自动关闭；`Invalid`/`null` 分支重新 `scheduleAutoDismiss()`（30 秒兜底） |
| 🟡 P2 | `Invalid`/`null` 直接 `isEnabled=true` 绕过 readiness（金额可能已被改成非法） | 改为 `updateConfirmEnabled(confirmButton)` |
| 🟡 P2 | 集成测试未复位 `RecentPaymentCooldown`，单例状态可能跨用例污染 | `@Before`/`@After` 补 `reset()` |
| ⚪ P2 | 多处注释仍是 30 秒 / 10 秒 / 10 分钟 | 全部同步为 15 秒 |

### 未修（需拍板 / 待办）

| 级别 | 问题 | 说明 |
|---|---|---|
| 🟠 P1 | **`RecentResultObservation` 与 D-25 完全等价，主判据冗余** | 页面无时间时 `occurredAt ≡ observedAt`，两判据相同；页面有时间时 `occurredAt` 恒定，D-25 恒命中且**更宽**。⇒ 不存在"哈希命中而 D-25 不命中"的输入。把 `findSameObservation` 改成恒 `null`，5 条相关用例仍全绿。**删掉不改变任何行为**，可省 190 行 + 10 条测试；保留的唯一理由是给"未来 D-25 改用观测时刻"留接口 |
| 🟠 P1 | `rawTextHash` 拼订单号**零测试覆盖** | `PaymentResultParserTest` 对 `rawTextHash` 断言为 0，删掉 `orderIdDigest` 无用例转红。建议补"同商户同额同文本、仅订单号不同 ⇒ 哈希不等" |
| 🟡 P2 | `confirm` 无超时；`CancellationException` 直抛后不重置 `isConfirming` | DB 锁时"入账中..."可能长期停留；X 仍可点，非绝对不可恢复。建议 `withTimeout(10s)` + `finally` 重置 |
| 🟡 P2 | 页面时间**只到分钟**时，同额两笔会撞 `sourceEventKey` ⇒ 同 `pendingId` ⇒ 只建一张卡 | 真实限制，需在验收文档的限制清单里登记 |
| ⚪ P2 | 冷却用 `elapsed >= 15s`（开区间），`PaymentTiming` 声明闭区间 | 1 毫秒缝隙，可改 `>` 对齐 |
| ⚪ P2 | RN 回前台 `invalidateCache.bills()` 失效 15 个 key，无节流 | 建议 500ms 去抖；已确认无循环依赖、key 均存在 |

### 验证为"无问题"的两项

- `visibilityCheckRunnable` 内调 `dismissInternal` 安全：runnable 首行已置 `visibilityCheckRunnable = null`，不会自移除，`finally` 正常执行。
- `loadCategories`/`loadAccounts` 的 `overlayView !== view` 不存在 null 窗口：`addView` 后紧接 `overlayView = view` 才发起加载。

---

### 第三轮复审（修复的修复）

| 级别 | 问题 | 修法 |
|---|---|---|
| 🔴 P0 | **3 秒超时闭包没有身份校验**：`postDelayed({ dismissInternal("ALREADY_HANDLED") })` 未存字段，`dismissInternal` 无法取消。用户 3 秒内点"重试"→ 新卡上屏 → 定时器到点关掉**新卡** | `dismissInternal(reason, expect: View?)` 增加目标校验；成功反馈 1.5s 与已处理 3s 两处都传 `expect = view` |
| 🟠 P1 | **身份校验吞掉了全局副作用**：`overlayView !== view` 挡在 `emitJsEvents` 之前，写库成功但 RN 缓存不失效 | 拆成 `isCurrentCard`：`Confirmed` 的 `emitJsEvents` / `KEY_LAST_CATEGORY_ID` 无条件执行，只有 UI 展示受守卫；`emitJsEvents` 显式传 `pendingRow.id`，不再读可能已变的 `displayedPendingId` |
| 🟠 P1 | **转账链路绕开统一口径**：`recordPersonalTransfer` 用 120 秒桶且无冷却/哈希，同收款人同额两笔间隔 <2 分钟会被静默合并 | 桶宽改为 `PaymentTiming.SAME_TXN_WINDOW_MILLIS`（15 秒） |
| 🟠 P1 | `removed → forget` 接线零覆盖（删掉 `init()` 用例仍绿，靠建卡兜底自愈） | 集成测试在 emit 后直接断言 `findSameObservation` 为 null |
| 🟡 P2 | 两条边界用例不判别行内 15 秒条件（`pruneLocked` 已先清理） | 修正 KDoc，说明行内条件是防御性的；不强行造测试 |
| 🟡 P2 | 两常量"必须同值"无任何断言 | 新增 `PaymentTimingTest`：同值断言 + 15 秒值断言 |
| ⚪ P2 | `CheckoutAccessibilityService` 注释称 60 秒场景"由哈希拦住"，与 15 秒窗口矛盾 | 改为"不再保证合并，是裁定取舍" |
| ⚪ P2 | `forgetForTest` 零调用 | 删除 |

**已核对无缺陷**：`overlayView` 只在 `addView` 后赋值、只在 `dismissInternal`/`displayOverlay` 的 catch 置 null；`isConfirming` 无永久 true 路径；`updateConfirmEnabled` 显式传按钮、不读全局字段；`pending` 与 `recognition` 的包依赖不构成循环；FIFO 反序构造有效；`PaymentResultParserTest` 20 秒前提成立。

---

## 十二、功能级审查（2026-10-24，第三轮子智能体）

目标从"单点缺陷"转为"功能点之间的冲突"。两个代理跑完，第三个（功能边界）因频率限制由主审代跑。

> ✅ **2026-10-24 已修四条**（见 §十四"收尾批次"）：P0-1 platform 展示映射、
> P1-3 确认失败误广播、P1-4 悬空分类/账户校验、P1-6 前台确认缓存刷新
> （根因是 `App.tsx` 事件监听模块配错，不是没有订阅）。

### P0

| # | 冲突点 | 证据 | 后果 |
|---|---|---|---|
| 1 | **`platform` 双语义** | `CheckoutPaymentMatcher.recordOrphanPaymentSignal` 写 `platform = signal.paymentPackage`（`com.tencent.mm`）；`pendingFromEvidenceOnly` 写 `shoppingPlatformName`（"拼多多"）。`BridgeDtoMapper` 原样透传，RN 直接展示：`ReviewScreen:562`（平台）、`BillDetailScreen:174`、`DashboardScreen:580`、`AllBillsScreen:305` | 精简版唯一存活路径是孤儿路径 ⇒ **每一笔账单的"平台"都显示 `com.tencent.mm`**。规则引擎 `platform_equals` 又期望包名（`RulesScreen:61` 提示"如 com.tencent.mm"）⇒ 存包名是有意的，缺的是展示层映射 |
| 2 | **敏感词 × 通知全停** | `AccessibilityPageSummaryCollector.sensitiveTerms` 含"银行卡/二维码/验证码"；`isNotificationSourceDisabledInV1` 让通知零产出 | 结果页出现"招商银行储蓄卡(1234)"⇒ 整帧 `continue` ⇒ 无弹窗、无待确认、无通知。根因 C 与 D-3 叠加，静默丢账 |

### P1

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 3 | **确认失败仍广播 `removed=true`** | `LedgerRepository.confirmPendingTransaction` 的 emit 在事务外无条件执行，`ConfirmResult(false)` 两个分支（pending 不存在 / 版本冲突）根本没删行 | 卡还在、通知被撤、冷却启动、哈希登记被清 ⇒ 同额新支付被静默抑制。**已修**：`if (result.success)` 才 emit |
| 4 | **RN 快捷确认绕过分类校验** | `ReviewScreen.handleConfirm` 传 `input={}`；`PendingConfirmService` 不校验 category；`bills.category_id` 是 `ForeignKey(RESTRICT)` | 分类被删后再确认 ⇒ FK 异常，`describeConfirmError` 无映射，RN 编辑面板又没有分类选择器 ⇒ **永久无法入账** |
| 5 | **合并入既有账单丢弃用户输入** | `LedgerRepository.mergeIntoExistingBill`；`PendingConfirmService` 返回 `merged` 但两个入口都不展示 | 同 `(sourceType, orderId)` 已有账单时，用户改的金额/分类/账户被丢弃，成功页仍显示"用户选的分类 + 旧金额" ⇒ 账实不符且无提示 |
| 6 | **前台确认后缓存不刷新** | `useAppStateManager` 只在 background→active 失效 | 弹窗盖在 App 前台确认后，账单/余额/统计仍是旧值 —— 正是"以为没记上再记一遍"的成因 |
| 7 | **通知引导文案误导** | `NotificationPermissionScreen:151` 称"支付成功通知才能证明确实付款了" | 通知链路已按 D-3 停用，用户按指引开启后依然什么都不记 |
| 8 | **30s 自动关闭 × 15s 判据空档** | `AUTO_DISMISS_DELAY=30s` 只清 UI 不消费该笔；三道同笔判据都是 15s | 停留结果页 >15s 后再观测 ⇒ 同一笔再建一张卡、再弹一次窗（裁定取舍的延伸） |

### P2

- **`LEAN_DEFAULT` 在代码里不存在**：`grep` 全仓零引用，`build.gradle` 无该字段。`docs/qa/lean-version-acceptance.md` §零 的"能力开关必须关闭"前置条件已随截图链路整链删除而失效，需改写。
- 死代码：`SensitivePageLatch`（5 处，整类无消费者）、`sawResultPage`（2 处，只写不读）、`shouldSkipForeignEventCollection`（1 处，恒 false 且零调用）、`ensureSession`（零调用）、`UserRetryCaptureContext`（生产零引用）、`SnapshotCaptureTarget`（零引用）。
- `autoCommitMatchedCandidate` 仍在 `recordPaymentEvidence` 内，精简版不可达；一旦会话链路恢复会违反"不做自动入账"。
- D-21 的"0 金额允许弹窗"在本版本不可达（`isValid()` 要求金额 ≥1）。

---

## 十四、收尾批次（2026-10-24）

修功能级审查的四条必修项。验证：`testDebugUnitTest` **218 / 0 失败**（216 + 2）；
eslint 对 6 个改动文件检查，无新增错误（2 个 error 为 DashboardScreen 既有 hook 依赖问题）。

### 1. platform 展示映射（P0-1）

- 新增 `src/utils/platform.ts`：`formatPlatform()` 把 `com.tencent.mm` → 微信支付、
  `com.eg.android.AlipayGphone` → 支付宝，未知值原样、空值空串。
- 改动 4 处展示：`ReviewScreen`（待确认"平台"）、`BillDetailScreen`（"来源"）、
  `DashboardScreen`（分类兜底链 + 列表 meta）、`AllBillsScreen`（列表分类兜底）。
- **存储层不动**：规则引擎 `platform_equals` 期望包名（`RulesScreen` 提示"如 com.tencent.mm"），
  映射只发生在展示层。

### 2. 前台确认缓存刷新（P1-6，根因修正）

- **真正的根因**：`App.tsx` 的 `onBillCreated` 监听挂在 `PaymentNotificationModule` 上，
  但事件实际从 `DataModule` 的 `sharedJsEventEmitter` 发出（`emitJsEvents` → DataModule）
  —— 模块不匹配，监听器注册了却永远收不到。
- 修正：监听改挂 `NativeModules.Data`；`addPendingChangedListener` 回调补
  `invalidateCache.bills()`（确认/忽略都改变账单与待确认两侧）。
- `useAppStateManager` 的回前台失效保持不变（双保险）。

### 3. 悬空分类/账户校验（P1-4）

- `PendingConfirmService.confirm` 在构造账单前校验 `resolvedCategoryId` / `resolvedAccountId`
  是否存在，悬空返回 `Invalid` 并给中文提示（"分类已被删除，请编辑后重新选择分类"）。
- 新增 2 条测试：`confirmRejectsDeletedCategoryAsInvalid`、`confirmRejectsDeletedAccountAsInvalid`
  （断言 Invalid、不落账单、待确认保留）。

### 4. 文档同步

- `qa/lean-version-acceptance.md` §零："能力开关"前置条件加注失效（`LEAN_DEFAULT`
  已随截图链路删除，全仓零引用，精简包只有一种形态）。

---

## 十三、审查边界

- 本次**未取真机日志**，所有"触发条件"均为代码推理；标 🟠/🟡 的条目需真机或单测复现后才可升级为"已确认故障"。
- 未审查 RN 侧 60+ 页面的 UI 逻辑（仅核对了 `ReviewScreen` 的 `sourceType` 映射与 `PendingTransaction` 契约）。
- 未审查导入、备份、AI 对话、热更新、Sentry 等非弹窗链路。
- 行号会漂，复现请用符号名（`maybeShowCandidate`、`showSuccessFeedback`、`shouldSkipForeignEventCollection`）。

---

## 十五、第三批修复：P1-1 候选队列 + P2-4 多 root 续检 + L6 改写（2026-10-24）

### P1-1 弹窗候选有界队列（修复）

| 项 | 内容 |
|---|---|
| 新增 `overlay/PromptQueue.kt` | 有界 FIFO 补播队列（纯逻辑类，时钟注入可单测）：容量上限、入队幂等（同 pendingId 保留最早）、出队 TTL 丢弃、`clear()` |
| `PendingConfirmOverlayManager` | ① `onPendingChange` / `maybeShowCandidate` 两处"槽位忙即 return"改为 `enqueuePrompt`；② 原管线体抽出为 `processPendingChange`，补播走**完整管线**（重读行、重过策略与门禁 —— 排队期间该笔可能已被 RN 待确认中心处理，直接重放旧的上屏决策不安全）；③ 补播触发点：`dismissInternal` 收尾 / `releaseInFlight` / 读行失败与"已被处理"两分支 / 挂起候选超时；④ 三种入队结果全留痕：`弹窗候选入队: SLOT_BUSY` / `入队去重` / `丢弃: QUEUE_FULL`；⑤ 功能停用即清队列 |
| 容量 = 3 | MIUI 还权挂起最长 57s，3 笔覆盖 L1 连续支付场景；超出丢弃但可归因 —— 审查建议的"治本（队列）+ 取证（日志）"同时满足 |

### P2-4 首个 root 失败改 continue（修复）

`observePaymentResultIfApplicable` 的 root 循环：`Failure` / `NotPaymentResult` 由 `return` 改为 `continue`，日志追加 `root=i/N` 定位；只有证据已处理（Success / PersonalTransfer）才终止本帧。"一帧最多产一条证据"的性质不变（Success/转账路径本身 return）。

### L6 验收标准改写（纯文档）

原操作"完成一笔支付"在精简版不可执行（无障碍关闭 ⇒ 零证据 ⇒ 零 pending）。改写前**先验证了替代路径可达**：`ImportEngine` 对语义不安全行建 pending 并 emit `isNew=true`，`PendingReminderNotifier` 监听同一枢纽发"有一笔交易待确认"，链路不经过无障碍 ⇒ L6 操作改为「关闭无障碍 → 导入账单 → 验证系统通知」，并注明 0 金额条目被通知侧拦截、手动记账直接落账单不建待确认（不能用于本条）。

### 测试

- 新增 `PromptQueueTest` 8 条：FIFO / 同 pendingId 幂等 / 容量满拒绝且出队后恢复 / waitedMs / TTL 整点（含端点）补播 / 超时首条弃且不拖累下一笔 / 全超时归零 / clear。
- 首跑 1 条失败（226 中唯一）：`expiredHeadIsDroppedAndNextIsStillServed` 原写法两笔**同时**入队后才推时钟，第二笔同样超时，用例却把 poll 结果强转 Ready ⇒ ClassCastException。属**用例设计错误**（实现行为正确：drain 循环本就逐条消化 Expired），修正为"第二笔在首笔已超时后入队"。

### 验证（实际输出）

```
:app:compileDebugKotlin + :app:testDebugUnitTest   BUILD SUCCESSFUL
测试报告：226 / 0 失败 / 0 忽略（218 基线 + 8 条新增）
```

### 截至2026-10-24 仍未修

- §七清单中仅剩：P2-5 支付包短周期重扫兜底、死代码清理（§三 13 项，单独批次）
- P2-3 冷却映射被同额并存卡覆盖；P2-6 服务层不校验分类/账户存在性（纵深防御）
- §十一未修项：`RecentResultObservation` 与 D-25 等价的冗余判定（删否需拍板）、confirm 无超时、页面时间只到分钟时撞 `sourceEventKey`
- §十二 P0-2 敏感词 × 通知全停（根因 C）—— 按 ISSUE 判断，等 1、2 落地重跑 L1 后再动
- **全部修复未真机验证**，L1 20 笔待跑；P1-1 的队列补播无单测覆盖（OverlayManager 依赖 View/WindowManager，需真机或 instrumented 验证）

---

## 十六、第四轮子智能体复审（2026-10-24，针对 §十五 的队列补播与多 root 续检）

两个独立代理分别复核「P1-1 队列接线」「P2-4 continue 改动」；第三个（五提交功能级冲突）中途取消未出报告。
以下只列**经主审核实**的条目。

### 已核实并修复（本批）

| 级别 | 问题 | 核实结论 | 修法 |
|---|---|---|---|
| 🟠 | **挂起候选超时的主执行路径漏接 drain**：drain 接在了 `maybeShowPendingCandidate` 的超时分支，但 `candidateExpiryRunnable` 按 due 时间排在后续观察帧之前先执行，该分支只在墙钟前跳时可达（近乎死臂） | ✅ 成立——正是 MIUI 挂起 30s 后第二笔入队的场景，补播不触发 | runnable 放弃分支补 `drainQueuedPrompts()`；死臂保留作墙钟前跳的安全网（drain 幂等） |
| 🟠 | **`removed=true` / `isNew=false` 事件被入队占坑**：弹窗自己确认成功会同步 emit removed（此刻成功页在屏 ⇒ 必然槽位忙）⇒ 入队 ⇒ 补播后读空行刷"事务未提交"WARN 假线索，且白占容量 3 的队列坑，可挤掉真实新支付 | ✅ 成立，确定性命中路径 | `onPendingChange` 入口按 `removed` / `!isNew` 过滤并留痕（与 `Skip(REMOVED)` / `Skip(UPDATED_NOT_NEW)` 语义一致，免读行） |
| 🟠 | **混合文案页"摘樱桃"误报（P2-4 副作用）**：`PAYMENT_FAILED` 是整窗文本含失败关键词的**明确反向证据**（解析器先查失败词后查成功词）；continue 后 `event.source` 子树文本可能恰好不含失败词却含成功词+金额，会为一笔已失败的支付落成功卡 | ✅ 成立，为本批新增行为面 | 收敛：`PAYMENT_FAILED` 保持 `return`（反向证据不被同帧推翻）；仅证据不足类（`INVALID_AMOUNT`/`INVALID_OCCURRED_AT`）与 `NotPaymentResult` continue——P2-4 的目标场景（系统对话框 root）产生的正是 `NotPaymentResult`，不受影响 |
| 🟡 | `displayOverlay` 无悬浮窗权限提前 return 漏 drain（门禁放行到本处的 TOCTOU 窗口内 MIUI 收权） | ✅ 成立 | 该分支补 drain |
| 🟡 | `displayOverlay` catch（addView 抛异常）漏 drain | ✅ 成立 | catch 末尾补 drain |
| 🟡 | **补播链中断**：drain 每次只补一笔，若补播的这笔读行失败或 evaluate Skip，队尾失去唯一触发点静默滞留 | ✅ 成立 | 读行失败回主线程补 drain；Skip 分支补 forget + drain |
| 🟡 | **重入队刷新 TTL，候选可无限续期**：出队→管线撞上槽位忙→重入队，`enqueuedAt` 取当前时刻 | ✅ 成立（逻辑推演，路径确定） | `PromptQueue` 记忆 pendingId 的**首次**入队时刻，重入队延续 TTL；终态（上屏/挂起/Skip/读行失败/过期/清空）由调用方 `forget` 或内部清除。新增 2 条判别性测试 |

### 已核实、暂不修（登记）

- **陈旧结果页窗口的重复落卡**（P2-4 复审 🟡）：无页面时间 + 遗留窗口仍活动/聚焦 + 距原卡 >15s 时，sourceEventKey 跨桶、哈希 15s 过期、D-25 窗口 miss，三道防线全部失效 ⇒ 同一笔旧支付再建一条待确认。**主路径预存在**（root1 角色分支 continue 本就可达），本批仅扩大暴露面；方向是可删误报，与"真实第二笔不被吞"的裁定取舍同向。若要收口需把哈希防线从"15 秒"放宽到"行仍 pending"，**需拍板**，暂不动。
- `支付包页面未分类为结果页` 逐 root 逐事件 Log.i + 为取 pageTextLength 做整树遍历 —— **预存在**的 D-9 形态噪声源（量级远大于本批改动的两条日志），归入死代码/日志清理批次。
- 同窗口双实例（`===` 去重失效）一帧处理两次、窗口清单 root 未 recycle —— 预存在，API 33+ 靠 GC 兜底，登记备查。

### 复审确认无问题（两代理一致 + 主审核实）

- 无无限循环/自激：每次 drain 至多补播一笔，重入队需经历"异步读行 + 主线程投递 + 另一事件占槽"三个外部条件，主线程串行下无同步自环。
- 主线程约定成立：promptQueue 全部触点（offer/poll/forget/clear/drain）均在主线程。
- 补播后新建 candidate 的 30s 从补播时刻起算，无"放行即弃"的双计；代价是总放弃窗口最长约 60s（队列 30s + 挂起 30s），语义一致。
- 与冷却/哈希登记解耦：两者都在落库事务内、emit 之前完成，弹窗排队/补播不影响其正确性；补播卡确认走同一 `PendingConfirmService.confirm`。
- `PromptQueue` 类与 8+2 条测试无恒真断言；`pollsExactlyAtTtlBoundary` 可杀死 `>=` 回归。

### 验证（实际输出）

```
:app:compileDebugKotlin + :app:testDebugUnitTest   BUILD SUCCESSFUL
测试报告：228 / 0 失败 / 0 忽略（226 基线 + 2 条新增：重入队不重置 TTL / forget 重置）
```

---

## 十七、场景级审查（2026-10-24 晚，三个子智能体）

方法转变：不再按"单点缺陷"审，而是把 **16 个用户场景**（产品视角的承诺）逐条对到代码现实，
承诺不成立即为缺陷。场景清单与逐条核实状态独立成档：`docs/qa/lean-user-scenarios.md`。
三个代理分别负责「主干与弹窗交互」「连续多笔与去重」「转账/导入/边界」；主审核实全部发现。

### 场景核实总览

16 场景：✅ 成立 10 / ⚠️ 部分成立 6（其中 2 项为本轮修复后转成立）/ ❌ 1（"红点提示"为口头承诺夸大，已修正口径——应用内无任何角标，召回只靠系统通知）。

### 已核实并修复（提交 8a8160e）

| 级别 | 问题 | 修法 |
|---|---|---|
| 🔴 | **P1-5 合并入既有账单丢弃用户全部修改**：同 (sourceType, orderId) 已有账单时 `mergeIntoExistingBill` 不写金额/分类/账户，成功页却显示"用户选的分类 + 旧金额" | 弹窗 `showSuccessFeedback(merged=true)` 显示"已并入既有账单 ¥X，本次修改未生效"；DataModule 响应带 `mergedIntoExistingBill`，RN 确认后 alert 如实提示 |
| 🟠 | **P1-7 通知权限页误导文案**（宣称支付通知能证明付款并自动入账，实际 D-3 停用） | 按现状改写：通知监听只用于退款/手续费识别，不开启不影响自动记账 |
| 🟠 | **批量导入通知被 2s 节流吞掉**（第 2 条起只留日志） | `PendingReminderNotifier` 对导入来源豁免节流 |
| 🟡 | **敏感页日志采样键共用**：微信/支付宝共用 reason 文本共享 3s 窗，L1 跨平台互相抑制 | 采样键加入 paymentPackage |
| ⚪ | NOT_FOUND 错误英文+UUID 直出；悬空分类提示指向不存在的编辑控件 | `describeConfirmError` 补两条映射（指向真实出路） |
| 🟠 | 转账卡快捷确认必败（缺源/目标账户） | `handleConfirm` 对 transfer 直接引导编辑面板 |
| ⚪ | 文档漂移 ×3 | 验收文档 `CANDIDATE_TTL` 15s→30s；`useAppState` 过时注释；场景对照表落档 |

### 已核实并修复（第二批，本提交）

| 级别 | 问题 | 核实结论 | 修法 |
|---|---|---|---|
| 🟡 | **判据口径文档与代码矛盾**：验收文档写"超过 15 秒**或**哈希不同 ⇒ 各自建卡"，但 15 秒内哈希不同的同额两笔实际被 D-25 时间窗合并（事件键/哈希放行后 D-25 仍收口） | ✅ 成立——且**符合**用户裁定"15 秒内接受合并"（§九），是文档措辞错 | 现状正本 §七与验收文档判据行按代码行为改写；L1 测试者不再误判 |
| 🟡 | **分钟级时间 × 同额两笔被吞**：occurredAt 恒等 ⇒ D-25 必然合并；有订单号时事件键/哈希均已放行，单 D-25 吞；§十一要求的限制登记未做 | ✅ 成立 | 已知限制写入现状正本 + 验收文档判据块；治本（D-25 改用观测时刻）需拍板，登记 |
| 🟡 | **"仅订单号不同 ⇒ 哈希不同"零测试覆盖**（§十一登记未变）：集成测试全部注入哈希字符串，删掉 `orderIdDigest` 拼接 228 项预计全绿 | ✅ 成立 | 补 `PaymentResultParserTest` 2 条：仅订单号不同 ⇒ hash 不等；同页重复观测 ⇒ hash 稳定 |
| ⚪ | **"确认失败不广播 removed"零测试覆盖**：删 `if (result.success)` 无用例转红 | ✅ 成立 | 新增 `LedgerRepositoryConfirmEmitTest` 3 条（Robolectric）：版本冲突/行不存在 ⇒ 零事件；成功 ⇒ 恰一条 removed |
| 🟡 | **队列补播 TTL 30s 限定**：挂起 30s + 队列 30s 叠加时，排队 >30s 的条目出队即弃（有留痕，数据不丢） | ✅ 成立（时序推演） | 场景对照表加"≤30s"限定；解耦放大需拍板，登记 |
| ⚪ | 冷却边界 `>=` vs `>` 1ms 缝隙（§十一登记） | 主审推演后**保留原语义**：恰在 15.000s 时即使冷却放行，下游去重链（闭区间）也判同一笔，卡结果等价、差异不可观测；既有用例钉死该行为 | 注释记录推演结论，不改语义 |

### 已核实、登记不修（需拍板 / 需真机数据）

- **RN 编辑面板无分类选择器**（场景 8 根治）：错误映射已指向真实出路；补选择器需 UI 设计，登记。
- **转账卡三无**（无哈希/冷却/事件键登记）：页面无时间时同一笔跨 15s 桶重复建卡（误报可删）；转账文案可达性（"转账成功"不在成功词表）**需真机确认**。
- **历史订单页误解析**：支付 App 内小程序"订单已支付"页在无时间字段时可建卡（误报可删）；收紧需真机文案数据。
- **FAILURE_KEYWORDS 不含"系统繁忙/支付环境异常"**：加反向词有"成功页偶含该词被整页判失败"的误杀风险，等真机数据。
- **通知监听 refund/fee/transfer 旁路不过 D-3 闸**：与"无障碍关闭=捕获归零"口径冲突（多记方向，退款还有自动入账分支），产品拍板去留。
- P2-3 冷却映射覆盖、`RecentResultObservation` 与 D-25 冗余判定（§十一）：维持登记。

### 代理"已核对无问题"要点（主审抽核一致）

- 队列 10 处 drain 触点覆盖全部"槽位由忙变闲"路径（独立重推无漏网）；补播重读重评链完整；无自激循环。
- 通知 id（创建/超时/撤销共用 pendingId.hashCode()）自洽；弹窗为 WindowManager 视图与通知 id 空间不相交。
- `removed→forget` 接线有判别性集成测试（直接断言 `findSameObservation == null`）。
- 无时间场景 0~15s / 15~30s / >30s 三段行为无内部矛盾；`PaymentTiming` 15s 数值单源 + 同值断言在位。
- Data 模块注册名/监听匹配、确认入口统一（弹窗与 RN 桥同走 `PendingConfirmService`）、缓存失效链端到端通。

### 验证（实际输出）

```
:app:compileDebugKotlin + :app:testDebugUnitTest   BUILD SUCCESSFUL
测试报告：233 / 0 失败 / 0 忽略（228 基线 + 5 条新增：哈希区分力 ×2 + 确认广播 ×3）
eslint（3 个 RN 改动文件）：0 error（3 个既有 no-void 警告）
```

---

## 十八、P2-5 落地：支付结果页重扫兜底（2026-10-24 晚）

§十五遗留项 P2-5（"结果页无轮询兜底，完全依赖无障碍事件驱动"）按 §七 修复顺序 #6 的建议实现。

### 设计

| 项 | 内容 |
|---|---|
| 触发 | `scanPaymentResultCore` 每帧**无证据产出**（含空树帧）即排一次重扫；Success/转账/幂等命中路径提前 return 不排 |
| 节奏 | 1s 间隔 × 上限 8 次；**任一真实事件到达即重置预算**（`observePaymentResultIfApplicable` 入口 `count=0` + 撤销待执行重扫）——交互中的页面不受影响，只兜"完全静止"的场景 |
| 执行体 | 合成事件（`TYPE_WINDOW_CONTENT_CHANGED` + 记忆的 windowClass）走 `scanPaymentResultCore` **同一管线**：读行重试、三道去重、敏感门禁、幂等键、`onObservationState` 喂帧全部复用——静止页不再饿死弹窗门禁 |
| 停止条件 | 预算用尽；活动窗口根不再是白名单支付包（含本应用弹窗在前台——证据已产出过）；`onUnbind`/`onDestroy` 摘回调（Handler 比服务实例活得长） |
| 线程 | 无障碍回调与 Handler 同在主 looper，字段无竞争 |

日志锚点：`支付结果页重扫兜底: pkg=..., count=N/8` / `支付结果页重扫兜底停止: 已离开支付包`。每会话最多 8 行，无刷屏风险。

### 为什么这样实现而不是给事件循环加定时器

直接在 `onAccessibilityEvent` 上挂全局定时器会把"用户停在支付页"与"用户已离开"两种状态耦合进事件流；重扫体复用扫描核心意味着**不会出现第二套解析逻辑**，去重与敏感门禁的既有正确性直接继承。合成事件只填 `eventType/packageName/className` 三个字段，`findPaymentResultRoots` 对 `event.source` 已有 try/catch 防护。

### 验证

```
:app:compileDebugKotlin + :app:testDebugUnitTest   BUILD SUCCESSFUL（233 基线全绿）
```

⚠️ **未真机验证**：重扫与 MIUI 事件流的交互（尤其敏感帧期间的重扫日志量、弹窗门禁新鲜度提升效果）需 L1 真机确认。若日志中重扫行异常频繁出现 `count=8/8`，说明存在持续无证据的页面形态，按行内 pkg/class 现场归因。

### 追加：成功反馈交互调整（2026-10-24 晚，用户裁定）

真机体验反馈：确认入账后的成功页 1.5s 偏久，且停留期间触摸被全屏遮罩吞掉（点了没反应，像卡住）。裁定：**0.8s + 点击屏幕任意位置立即关闭**。实现：`SUCCESS_DISPLAY_DELAY` 1_500→800；`showSuccessFeedback` 在全屏根 view 挂 click 监听（此刻 content_main 已 GONE 无并发交互），点按走 `dismissInternal("SUCCESS_TAP", expect=view)` 与到点关闭同一条收口。真机日志验证旧行为为精确 1.503s（28.766 确认 → 30.269 关闭）。同时验证：22:22 那笔确认后 1.7s 的同额重复观测被 15s 冷却正确拦截（§九修复在真机生效）。
