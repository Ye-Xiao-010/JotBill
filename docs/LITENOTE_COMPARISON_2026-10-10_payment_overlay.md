> **状态头**：正典·参考 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# 上游精读：LiteNote 弹窗记账全流程解剖 + 与精简版逐环节对照（2026-10-10）

> **状态**：[参考] —— 上游源码精读
>
> | 项 | 值 |
> |---|---|
> | 登记日期 | **2026-10-10** |
> | 文档标题 | **LiteNote 弹窗记账全流程解剖 —— 七个环节逐行对照** |
> | 上游 | <https://github.com/GystuG/LiteNote>（MIT，默认分支 `main`） |
> | 取证方式 | GitHub API + `raw.githubusercontent.com` 逐文件抓取**上游源码全文**（非本地副本，本仓库无 LiteNote 移动端源码） |
> | 本仓库分支 | `feat/lean-version` @ `HEAD 4eb89a5` |
> | 关联问题 | [`ISSUE_2026-10-10_lean_payment_overlay_popup_timing.md`](./ISSUE_2026-10-10_lean_payment_overlay_popup_timing.md) |
> | 标记 | 🔴 架构级结论 · ⚖️ 需用户裁定 |

---

## 〇、一句话结论

> **LiteNote 根本没有无障碍服务。它是"通知一到就弹"的零门禁架构；
> 而精简版把通知链路砍掉（D-3），换成了为"AI 自动记账"设计的无障碍链路 ——
> 这条链路的全部时序假设都建立在"AI 会回来"之上，而精简版没有 AI。**

这不是调参能修的 bug，是**换触发源时欠下的架构债**。

---

## 一、上游文件清单（`com.litenote` 全量）

```
MainActivity.kt
MainApplication.kt
auth/          ← 账号体系
hotupdate/     ← 热更新
notification/  ← 支付通知监听 + 悬浮窗（唯一命脉）
utils/
```

**没有 `recognition/` 目录。`AndroidManifest.xml` 里没有任何 `<accessibility-service>`。**

> 对照：JotBill 精简版 `recognition/` 一个目录装着 `CheckoutAccessibilityService`（3000+ 行）、
> `CheckoutPaymentMatcher`、`AccessibilityPageSummaryCollector`、`CheckoutSnapshotBuffer`、
> `CheckoutPurchaseIntentCoordinator`、`CheckoutAiVisionClient` 等十余个类。

---

## 二、弹窗记账全流程（七个环节）

### 环节 0 · 谁拥有弹窗 —— 架构基石

`MainApplication.onCreate()` **完全不初始化任何弹窗相关对象**（只有 `loadReactNative` + Sentry）。

弹窗管理器诞生在**系统绑定通知服务的那一刻**：

```kotlin
// notification/PaymentNotificationService.kt : onCreate()
override fun onCreate() {
    super.onCreate()
    overlayManager = PaymentOverlayManager(this)   // ← 弹窗管理器在此诞生
    loadMonitoringConfig()
    ...
}
```

> 🔑 **这是 LiteNote 能"随时弹"的架构基石**：
> 弹窗的生命周期归 `NotificationListenerService` 所有，**与 RN 进程是否存活完全解耦**。
> 用户把 App 杀掉、界面停在微信里，只要通知服务还在被系统绑定，弹窗照弹。

**我们的现状**：`PendingConfirmOverlayManager.init()` 在 `MainApplication.onCreate():153`，
依赖 `PendingChangeHub` 的进程内单例。**进程被杀 ⇒ 监听器丢失 ⇒ 期间产生的 pending 永远不弹**。

### 环节 1 · 通知到达

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) { return }
    processNotification(sbn)
}
```

### 环节 2 · 过滤与解析（纯内存，微秒级）

`processNotification()` 依次四道：

| 步 | 逻辑 | 出处 |
|---|---|---|
| ① 包名白名单 | `packageName !in supportedPackages` → return（默认 `{com.tencent.mm, com.eg.android.AlipayGphone}`） | `processNotification` 首段 |
| ② 一次性抽字段 | `extractNotificationData()` 抽 title / titleBig / text / subText / summaryText / bigText / infoText / tickerText 八项 | 同文件 |
| ③ 语义过滤 | `isPaymentNotification()`：**排除词优先**（`收款/到账/收到/转入/红包`），再要求**支付词任一命中**（`支付/付款/消费/扣款/支出/交易提醒/花呗/余额宝/已支付`） | `companion object` |
| ④ 金额抽取 | `extractAmount()` 六条正则依次尝试 | 同上 |

上游六条金额正则（原文）：

```kotlin
private val AMOUNT_PATTERNS = listOf(
    Regex("""¥\s*([0-9]+\.?[0-9]*)"""),           // ¥50.00
    Regex("""([0-9]+\.?[0-9]*)\s*元"""),          // 50.00元 或 0.01元的支出
    Regex("""CNY\s*([0-9]+\.?[0-9]*)"""),         // CNY 50.00
    Regex("""人民币\s*([0-9]+\.?[0-9]*)"""),      // 人民币50.00
    Regex("""金额[：:]\s*([0-9]+\.?[0-9]*)"""),   // 金额：50.00
    Regex("""([0-9]+\.?[0-9]*)\s*元的"""),        // 支付宝：0.01元的支出
)
```

> 💡 **注意第 6 条**：`0.01元的支出` 是支付宝的真实文案形态。上游是**被真机文案教育过**的。

**全流程到这里是纯内存的，没有一次 IO。** 这是它快的根本原因。

### 环节 3 · 弹窗上屏（同步，主线程）

```kotlin
// processNotification 倒数第二行
showPaymentOverlay(amount, packageName, paymentData)
```

`PaymentNotificationService.showPaymentOverlay()` 转手就调
`overlayManager.showPaymentOverlay(amount, source, onConfirm, onDismiss)`。

`PaymentOverlayManager.showPaymentOverlay()` 内部：

| 步 | 逻辑 |
|---|---|
| ① **唯一门禁** | `if (!hasOverlayPermission()) return` |
| ② | `mainHandler.post { ... }` 切主线程 |
| ③ | 已有弹窗则先 `removeView` |
| ④ | `createOverlayView()` — inflate `payment_overlay.xml`，填金额 / 来源 |
| ⑤ | `createLayoutParams()` |
| ⑥ | **`windowManager.addView(newOverlayView, params)`** ← 弹窗上屏 |
| ⑦ | **`postDelayed(500)` 自检 `isAttachedToWindow`** |
| ⑧ | `scheduleAutoDismiss()` — 60s |
| ⑨ | `loadCategories()` — 网络加载分类 |

上游的布局参数（**注意比我们的多两个 flag**）：

```kotlin
return WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    layoutType,                                   // TYPE_APPLICATION_OVERLAY
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,   // ← 我们没有这两条
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.CENTER
    softInputMode = SOFT_INPUT_ADJUST_RESIZE
}
```

> 🔑 **第 ⑦ 步值得单独说**：上游自己写了
> ```kotlin
> mainHandler.postDelayed({
>     if (overlayView != null && overlayView?.isAttachedToWindow == true) {
>         Log.i(TAG, "悬浮窗在500ms后仍然可见 - 成功!")
>     } else {
>         Log.w(TAG, "悬浮窗被系统移除!")
>     }
> }, 500)
> ```
> **它预期到"addView 成功 ≠ 真的可见"**，并留了自检日志。
> 我们的 `displayOverlay()` 只在 `addView` **抛异常**时兜底（`:452-461`），
> **addView 成功但被系统/ROM 悄悄吃掉的情形完全没有观测**。

### 环节 4 · 分类网格加载（异步）

```kotlin
apiService.getExpenseCategories { categories -> mainHandler.post { ... } }
```

`BillApiService`（OkHttp）GET `${API_BASE_URL}/categories?type=expense`，带 `Authorization: Bearer <token>`。

网格构建（**这段 UI 骨架被我们原样移植进了 `PendingConfirmOverlayManager`**）：

| 特征 | 上游参数 | 我们的对应 |
|---|---|---|
| 每行分类数 | `CATEGORIES_PER_ROW = 3` | `CATEGORIES_PER_ROW = 3` ✅ 一致 |
| 最大可见行 | `MAX_VISIBLE_ROWS = 3` | `MAX_VISIBLE_ROWS = 3` ✅ 一致 |
| 等宽 | `LayoutParams(0, h, 1f)` + `marginStart/End = 4dp` | 一致 |
| 不满行占位 | `LayoutParams(0, 1, 1f)` 空 View | 一致 |
| 超 3 行限高 | `MAX_VISIBLE_ROWS * rowHeight + (MAX_VISIBLE_ROWS-1) * rowGap` | 一致 |
| 上次分类记忆 | `prefs.getInt(KEY_LAST_CATEGORY_ID, -1)` → 自动选中 | 一致 |

### 环节 5 · 用户确认记账（**这是"弹窗记账"的核心**）

```kotlin
confirmButton.setOnClickListener {
    val category = selectedCategory
    if (category == null) { return@setOnClickListener }   // 未选分类 ⇒ 静默返回

    confirmButton.isEnabled = false
    confirmButton.text = "记账中..."

    val remark = remarkInput.text?.toString()?.trim().orEmpty().ifBlank { null }
    apiService.createBill(
        amount = amount,
        categoryId = category.id,
        description = remark
    ) { success, errorMessage ->
        mainHandler.post {
            if (success) {
                saveLastCategoryId(category.id)
                sendBillCreatedBroadcast()
                showSuccessFeedback(view, amount, category.name)
            } else {
                showErrorFeedback(view, amount, errorMessage) { /* 恢复按钮 */ }
            }
        }
    }
}
```

实际发出的请求（`BillApiService.createBill`）：

```kotlin
val requestBody = CreateBillRequest(
    amount = amount,          // Double
    type = "expense",         // ← 硬编码
    categoryId = categoryId,
    description = description,
    date = today              // ← SimpleDateFormat("yyyy-MM-dd") 当天
)
POST ${API_BASE_URL}/bills
```

**上游弹窗记账的字段口径（注意有 4 个硬约束）**：

| 约束 | 上游 | 影响 |
|---|---|---|
| `type` 硬编码 `"expense"` | ✅ | 退款/转账/手续费**一律记成支出** |
| `date` 取**当天** | ✅ | 补记历史支付会记错日期 |
| **无账户字段** | ✅ | 余额无从更新 |
| **无商户字段** | ✅ | 通知里的商户信息被丢弃 |

> ⚠️ `showPaymentOverlay` 的 `onConfirm` 参数，KDoc 明确写着
> 「@param onConfirm 确认记账回调（**不再使用，保留接口兼容**）」，
> 但 `PaymentNotificationService` 仍传了一个 `launchAppWithPayment(paymentData)` 的 lambda 进去 —— **死参数**。

### 环节 6 · 结果反馈

| 结果 | 动作 |
|---|---|
| 成功 | `saveLastCategoryId()` → `sendBillCreatedBroadcast()` → `showSuccessFeedback()` → **1.5s 后** `dismissOverlay()` |
| 失败 | `showErrorFeedback()` → 换 `content_error` 面板 + 重试按钮 → 点重试换回 `content_main` |

### 环节 7 · 前端缓存刷新

```
PaymentOverlayManager.sendBillCreatedBroadcast()
  → LocalBroadcastManager.sendBroadcast(ACTION_BILL_CREATED = "com.litenote.BILL_CREATED")
  → PaymentNotificationModule 的 BroadcastReceiver（onHostResume 时经 registerPaymentReceiver() 注册）
  → sendBillCreatedEventToJS()
  → RCTDeviceEventEmitter.emit("onBillCreated", null)
  → App.tsx useEffect 监听 → invalidateCache.bills()
```

`App.tsx` 原文：

```tsx
const subscription = eventEmitter.addListener('onBillCreated', () => {
  console.log('[App] ✓ 收到账单创建成功事件！');
  invalidateCache.bills();
});
```

---

## 三、完整时序图（上游）

```
[系统] 用户授权通知访问 → bind PaymentNotificationService
        │
        └─> onCreate() → overlayManager = PaymentOverlayManager(this)
                          ↑ 弹窗能力此刻已就绪，与 RN 进程无关
[用户] 点"确认支付" → 微信切到支付成功页（前台）
        │
[系统] 微信投递通知
        │
        └─> onNotificationPosted(sbn) ──────────────┐  ← 系统级回调，
                                                      │    与"谁在前台"无关
   环节2  processNotification(sbn)                    │
        ├ 包名白名单 ✓                                │
        ├ isPaymentNotification ✓                     │  纯内存，微秒级
        ├ extractAmount = 35.00 ✓                     │
        └ buildPaymentData(json)                      │
   环节3  showPaymentOverlay(35.00, "com.tencent.mm") │
        ├ hasOverlayPermission ✓                     │  ← 唯一门禁
        └ mainHandler.post {                          │
             windowManager.addView(...)  ← ★ 弹窗此刻盖在微信支付成功页上
             postDelayed(500) 自检 isAttachedToWindow │
             scheduleAutoDismiss(60s)                 │
             loadCategories() → 网络                   │
          }                                           │
   环节4  categories 返回 → 构建 3×N 网格 → 自动选中上次分类
   环节5  用户点"记账" → apiService.createBill() → POST /bills
   环节6  success → saveLastCategoryId + 广播 + 成功面板 → 1.5s 后关闭
   环节7  广播 → JS onBillCreated → invalidateCache.bills()
```

---

## 四、七个环节逐项对照

| 环节 | LiteNote（上游） | JotBill 精简版 | 谁更强 |
|---|---|---|---|
| **0 谁拥有弹窗** | `NotificationListenerService` 持有，**与 RN 进程解耦** | `MainApplication` 持有，**进程被杀即失效** | 🔴 **上游** |
| **1 通知到达** | `onNotificationPosted` 系统回调 | 同（但已有 D-3 闸停用） | 平 |
| **2 过滤解析** | 纯内存：白名单 + 8 词排除 + 9 词支付 + **6 条金额正则** | 纯内存但**多一层**：结果页节点树采集 + 角色分类 + 敏感判定 + 指纹 | 🔴 **上游**（零延迟、零失败面） |
| **3 弹窗上屏** | 同步、唯一门禁是权限、**有 500ms 可见性自检** | 异步（两跳协程）、4 道门禁 + 候选挂起 15s TTL、**无可见性自检** | 🔴 **上游** |
| **4 分类网格** | 网络 `/categories?type=expense` | 本地 Room `categoryDao().getByType("expense")` | ✅ **我们**（离线可用） |
| **5 确认记账** | `POST /bills`，`type` 硬编码 expense、无账户、无商户、日期取当天 | `PendingConfirmService.confirm`：账户 + 规则引擎 + 乐观锁 + 类型正确 | ✅ **我们**（大幅更强） |
| **6 结果反馈** | 成功 1.5s 自动关 / 失败可重试 | 同 + `AlreadyHandled` / `Invalid` 分档 | ✅ **我们** |
| **7 前端刷新** | `LocalBroadcastManager` → `onBillCreated` → `invalidateCache.bills()` | `sharedJsEventEmitter` → `onPendingChanged` + `onBillCreated` | ✅ **我们**（双事件、含终态） |

> **公平结论**：我们在**环节 4/5/6/7**（数据正确性与可追溯）上全面优于上游；
> 上游在**环节 0/2/3**（时机与可靠性）上碾压我们。
> 而 L1 验收只考环节 3。

---

## 五、我们相对上游**丢掉的四样东西**（可复用清单）

| # | 上游有的 | 我们的现状 | 是否值得捡回 |
|---|---|---|---|
| **1** | `FLAG_SHOW_WHEN_LOCKED or FLAG_TURN_SCREEN_ON` | `createLayoutParams()` **缺这两条**（`:787`） | 🟡 锁屏支付场景才需要，可后置 |
| **2** | `postDelayed(500)` 检查 `isAttachedToWindow` 失败告警 | **完全没有** —— `addView` 成功即认为成功 | 🔴 **强烈建议捡回**。这是零成本的"弹窗到底上没上屏"观测，正对应本次"没弹出"的症状 |
| **3** | 弹窗归 `NotificationListenerService` 持有，**与 RN 进程解耦** | 归 `MainApplication`，**进程被杀即失效** | 🔴 **值得评估**（但通知链路已停用，收益有限） |
| **4** | `HeartbeatRunnable` 每 60s 打心跳，`日志含设备信息`（`Build.MANUFACTURER / MODEL / SDK_INT`） | `showPaymentOverlay` 里有设备信息日志，我们**没有** | 🟢 低成本，排查 ROM 差异时有用 |

---

## 六、上游自己的问题（**不要盲目照抄**）

| # | 问题 | 证据 |
|---|---|---|
| 1 | **分类加载失败 ⇒ 用户完全无法记账**（按钮永远灰） | `loadCategories` 失败分支只加一句 `TextView`「加载分类失败，请稍后重试」 |
| 2 | **会在支付密码页上方弹窗** | 无任何敏感页判定。我们的 `ROADMAP_FALLBACK_FIRST.md:342` 早已记下这条 |
| 3 | **`type` 硬编码 `"expense"`** | 退款/转账/手续费全记成支出 |
| 4 | **日期取当天** | 补记历史支付必错 |
| 5 | **无账户、无商户** | 余额不更新；通知里的商户被丢弃 |
| 6 | **零去重、零频控** | 同一笔支付的通知重复投递会反复弹 |
| 7 | **`onConfirm` 死参数** | KDoc 写"不再使用"，调用方仍传 lambda |
| 8 | **`PaymentNotificationHeadlessService` 是死代码** | `startService()` 全仓**无调用点** |
| 9 | **`BootCompletedReceiver` 只打一行日志** | 无任何实际动作 |
| 10 | **`default_filter_types = "alerting"`**（manifest meta-data） | 只收 alert 类通知，可能漏掉 silent 的支付通知 |

---

## 七、⚠️ 关键约束：这条路在我们的主场景下走不通

JotBill 自己的实测已把这条路证伪
（[`qa/payment-notification-effectiveness-2026-09-10.md`](./qa/payment-notification-effectiveness-2026-09-10.md)）：

| appName | 整段真机日志（583,459 行，含一笔 ¥55.00 真实支付）中的通知投递数 |
|---|---|
| 拼多多 | 1 |
| JotBill | 1 |
| **微信** | **0** |
| **支付宝** | **0** |

铁证时间线：

| 时间 | 事件 |
|---|---|
| 17:09:16.587 | `notifyScenarioChanged cmp=com.tencent.mm/.plugin.base.stub.WXPayEntryActivity, callerPackage=com.xunmeng.pinduoduo` |
| 17:09:16.866 | **`skip notify foregroundAppInfo: com.tencent.mm`** ← 微信为前台，系统跳过投递 |
| 17:09:21.806 | `支付结果页解析到证据: 金额=5500分` ← **实际起作用的是无障碍结果页** |
| 17:09:22.202 | JotBill 自己发通知：`有一笔交易待确认, ¥55 · 支出 · 支付结果页` |

原文判定：

> 「Android 通知机制下，应用处于前台时不需要投递提醒通知 ⇒ 购物 App **跳转式支付**
> （拼多多 → 微信/支付宝内支付）几乎不可能产生支付通知。这是**形态限制**，与代码无关。」

### ⇒ 对"两种形态都试过"的直接影响

| 测试形态 | 通知是否投递 | LiteNote 方案能否直接套用 |
|---|---|---|
| **跳转式**（拼多多 → 拉起微信） | ❌ 不投递（已实证） | ❌ **也弹不出来** |
| **非跳转式**（微信里直接扫码 / 转账 / 收款） | ✅ 会投递 | ✅ **可以直接套用** |

> **所以"两种都试过"这个答案本身就极有价值**：
> 非跳转式场景下弹不出来，**只可能是 D-3 闸把通知拦掉了**，而不是识别链路的问题
> —— 那条路上上游是能弹的。这是一个可以立刻验证的判别实验。

---

## 八、⚖️ 需要用户裁定的分叉

| 路线 | 做法 | 能过 L1？ | 工作量 | 代价 |
|---|---|---|---|---|
| **甲：修无障碍链路** | 根因 A（前台守卫死代码）+ B（30s/15s 窗口）+ C（敏感误判）+ 落卡兜底 | ✅ | 大 | 时机永远不如通知可靠（空树帧、ROM 杀服务） |
| **乙：复活通知链路（LiteNote 标准答案）** | 打开 D-3 的 5 道闸 + 补敏感页守卫 + 补去重频控 | ❌ | 小 | 通不过 L1（跳转式无通知）；但**非跳转式立刻可用** |
| **丙：双轨** | 通知（覆盖非跳转式）+ 无障碍（覆盖跳转式），共用同一套 `PendingConfirmService` | ✅ | 最大 | 需定义两条链路的去重口径 |

> **建议先做一个 10 分钟的判别实验**，再决定走哪条：
> 用**非跳转式**（微信里扫码付 1 块钱）跑一次，看日志里有没有
> `解析到支付证据: 包名=com.tencent.mm`（`PaymentNotificationService:238`）。
> - 有这行但没弹窗 ⇒ 证实被 D-3 拦掉 ⇒ **乙/丙成立**
> - 没这行 ⇒ 通知压根没投递 ⇒ **只能走甲**

---

## 九、三条沉淀

1. **迁移时换了触发源，必须重新验证时序假设。**
   本次根子是批 V-5 的「等 AI 定论」假设在精简版失效，
   而它**写在 `CheckoutPaymentMatcher` 里，与能力开关相隔三层**，
   代码上完全看不出这条路径在精简版已失去依托。

2. **上游的"笨办法"在特定维度确实更强。**
   LiteNote 的「通知一到就弹」看似粗糙，但它把时机问题**外包给了系统通知** ——
   零竞态、零门禁、零延迟、零解析失败面。
   我们换成无障碍后，时机成了自己的责任，而无障碍恰恰是最不可靠的那条链路。

3. **可观测性要照抄。**
   上游一个 `postDelayed(500) { isAttachedToWindow }` 就覆盖了
   "弹窗到底上没上屏"这个最容易出问题、却最难排查的问题。
   我们**恰恰缺这一条**，导致本次症状只能靠人工看图判断。
