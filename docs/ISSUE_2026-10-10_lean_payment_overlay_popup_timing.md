> **状态头**：历史取证 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# 问题诊断：精简版付款确认弹窗不在支付成功页出现（2026-10-10）

> **状态**：[快照·含未处置项]（2026-10-10 时点，**代码层已定位，根因待日志确认**）
>
> | 项 | 值 |
> |---|---|
> | 登记日期 | **2026-10-10** |
> | 问题标题 | **精简版付款确认弹窗不在支付成功页出现，切回本应用后才弹出** |
> | 分支 | `feat/lean-version`（**不合并 main**） |
> | 代码基线 | `HEAD 4eb89a5`，工作区干净 |
> | 报告人 | 用户真机测试（微信 / 支付宝两个支付 App） |
> | 诊断方式 | **纯代码走查**，未取真机日志 |
> | 关联验收项 | `qa/lean-version-acceptance.md` **L1**（唯一一票否决项） |
> | 标记 | 🔴 阻断 L1 · 🔧 可直接执行 |

---

## 一、问题标题与现象（用户原话）

> 「使用微信和支付宝两个支付app支付后，在支付成功的页面不会有我们所预计的窗口出现，
> 反倒是在切换回到我们的软件后出现了弹窗，但是经过日志检测，软件中的弹窗是待确认的弹窗，
> 不知道是否与我们预计在支付成功页要弹出的窗口为同一个」

拆成可判定项：

| # | 现象 | 状态 |
|---|---|---|
| ① | 微信/支付宝**支付成功页**上不弹窗 | ✅ 已复现（用户报告） |
| ② | **切回 JotBill 后**才弹窗，且盖在本应用界面上 | ✅ 已复现（用户报告） |
| ③ | 切回后弹的是不是同一个弹窗？ | ✅ **已解答：是**（见 §二） |

---

## 二、结论先行：是同一个弹窗，不是两个

全仓**只有一个**付款确认弹窗 —— `PendingConfirmOverlayManager`
（`jotbill-app/android/.../overlay/PendingConfirmOverlayManager.kt`，日志锚点 `付款确认弹窗已显示:`）。

它是 `TYPE_APPLICATION_OVERLAY` 系统悬浮窗（`:776-793`），
**设计上就是浮在微信/支付宝之上的**，不是 RN 页面内的 Modal。

> **判定**：用户看到的"预期中的窗口"和"切回来才弹的窗口"**是同一个**。
> 差异**不在弹窗种类，而在触发时机晚了**。

### 触发链路（精简版唯一一条）

```
支付结果页 → PaymentResultParser.parseResult
  → recordOrphanPaymentSignal(sourceType = "ACCESSIBILITY")
  → PendingChangeHub.emit(isNew = true)
  → PendingConfirmOverlayManager.onPendingChange
  → canShowNow() 四道门禁
  → windowManager.addView（悬浮窗上屏）
```

### 为什么通知链路不可能是"延后触发器"

精简版（D-3 裁定）通知链路**完全停用**，共 5 道闸：

| 位置 | 闸 |
|---|---|
| `CheckoutPaymentMatcher.kt:801` | `recordPaymentEvidence` 出口 |
| `CheckoutPaymentMatcher.kt:561` | `recordOrphanPaymentSignal` 出口 |
| `FeeExtractor.kt:74` | 手续费分类器入口自查 |
| `RefundLinker.kt:86` | 退款分类器入口自查 |
| `TransferClassifier.kt:85` | 转账分类器入口自查 |

判据同源：`CheckoutContract.kt:138-139` `isNotificationSourceDisabledInV1()`，
归一化后比对字符串 `"notification"`（`:151-157`）。

> **推论（重要）**：既然通知链路零产出，**待确认只可能由无障碍结果页产生**。
> 用户既然看到了弹窗，就说明**建卡那一刻 `canShowNow()` 是放行的** ——
> 问题必然落在「pending 何时被创建」或「门禁为何在错误时机放行」两侧。

---

## 三、根因

### 🔴 根因 A：「前台是本应用时不弹」这条守卫是**死代码**（已确认）

`PendingConfirmOverlayManager.kt:329-342`：

```kotlin
private fun canShowNow(): Boolean {
    val context = appContext ?: return false
    if (System.currentTimeMillis() - lastObservationAt > OBSERVATION_FRESH_MILLIS) {
        return false
    }
    if (lastObservationSensitive) {
        return false
    }
    // 前台是本应用时不弹：应用内待确认中心已覆盖该场景
    if (lastForegroundPackage == context.packageName) {
        return false
    }
    return hasOverlayPermission(context)
}
```

**问题**：`lastForegroundPackage` 只在 `onObservationState()`（`:168-175`）里更新，
而全仓**只有两个调用点**：

| 调用点 | 传入值 | 白名单约束 |
|---|---|---|
| `CheckoutAccessibilityService.kt:1048` | `shoppingPackage` | 8 个购物包 |
| `CheckoutAccessibilityService.kt:1688` | `paymentPackage` | `com.tencent.mm` / `com.eg.android.AlipayGphone` |

**本应用包名 `com.jotbill.app` 永远喂不进来** ⇒ `:338` 这个分支**永远为假**。

**后果（两层）**：

1. **切回 JotBill 后弹窗能盖上来** —— 现象 ②。30 秒内有过一帧"非敏感"观察即可放行，
   与它自己的设计注释（"应用内待确认中心已覆盖该场景"）**正好相反**。
2. **弹窗并不绑定在"支付成功页"上** —— 它只绑定"最近 30 秒内有帧"。
   这直接说明 `qa/lean-version-acceptance.md` L1 的判据（弹窗出现在支付成功页）
   在当前代码下**没有任何机制保证**。

> `worklog/2026-10-08.md` §二·前提 1 已预警过相邻风险
> （"侥幸成立，不是设计上成立"），但**未发现本条**：`lastForegroundPackage`
> 在精简版下**永远不会**被本应用包名覆盖。

---

### 🔴 根因 B：两个时间窗口径打架（已确认）

| 常量 | 值 | 位置 |
|---|---|---|
| 观察帧新鲜度 | **30 s** | `PendingConfirmOverlayManager.kt:65` `OBSERVATION_FRESH_MILLIS` |
| 候选等待 TTL | **15 s** | `PendingPromptPolicy.kt:28` `CANDIDATE_TTL_MILLIS` |
| （参考）弹窗自动消失 | 60 s | `PendingConfirmOverlayManager.kt:61` |

**组合出的失效路径**：

1. 支付成功页那一瞬间，若 `canShowNow()` 返回 false（最可能是 `lastObservationSensitive == true`）
   → `maybeShowCandidate` 把候选挂起（`:302-310`）；
2. 15 s 内始终没有新的非敏感帧 → `弹窗候选超时放弃(敏感页未结束), 通知兜底`（`:307`）
   → **这笔永久失去弹窗**，只剩系统通知（静默 miss，计入 L1 分母）；
3. 而在这 **30 s** 之内，用户切回 JotBill —— 帧状态仍是"非敏感 + `lastForegroundPackage` 停在支付包"
   → 根因 A 的守卫失效 → 下一条 pending 一进来**立即放行，正好弹在 JotBill 上**。

> **症状与代码完全吻合**：该弹的时候没弹，不该弹的时候弹了。

---

### 🟠 根因 C：成功页被判成敏感页（已确认机制，高概率是 B 的触发源）

`AccessibilityPageSummaryCollector.kt:184-196` 敏感词表（完整 10 项）：

```kotlin
private val sensitiveTerms = listOf(
    "密码", "验证码", "校验码", "二维码", "付款码",
    "人脸识别", "指纹", "银行卡", "安全验证", "身份验证"
)
private val sensitiveAsciiPattern = Regex("""(?i)\b(password|pin|otp)\b""")
```

三个放大因素叠加：

| # | 因素 | 锚点 |
|---|---|---|
| 1 | `includeHiddenText` 生产默认 **true** ⇒ 隐藏节点文本也参与判定 | `:297`（默认）、`:1685`（支付路径显式传 true） |
| 2 | `accumulator.sensitive` 是**整页 OR**，无作用域 ⇒ **一个隐藏节点翻整页** | `:503-513`、`:772-777`、`:338` |
| 3 | `银行卡` / `指纹` / `付款码` / `二维码` 极易误伤 | 见下 |

**误伤场景**：微信/支付宝成功页会显示付款方式（如"招商银行储蓄卡(1234)"），
钱包内还有隐藏的"收付款码/二维码"入口。任一命中 ⇒ 整页判敏感。

**命中后果**：`CheckoutAccessibilityService.kt:1696-1709` 直接 `continue` ——
**既不抽证据，也无任何兜底**（截图兜底在精简版已被开关停用，`:1888-1894`）。
这一帧**零产出**。

> ⚠️ 注意 `银行卡` 同时存在于 `CheckoutAccessibilityService.kt:50` 的
> `SENSITIVE_TEXT_KEYWORDS`（另一张更宽的表，仅用于 `collectNodeText`）——
> **两张表口径不一致**是既有技术债，本问题放大它。

---

### 🟡 附带发现 D/E/F（同一路径上的独立缺陷，建议一并记档）

| # | 位置 | 缺陷 | 后果 |
|---|---|---|---|
| **D** | `CheckoutPaymentMatcher.kt:739` | Ambiguous 分支 emit 的 `pendingId` 用 `stableId("pending-orphan", …)`（`:570`），实际插入的行用 `stableId("pending-ambiguous", sessionId, …)`（`:719`） | `readPendingRow` 读不到 ⇒ **该分支永远不弹窗** |
| **E** | `CheckoutPaymentMatcher.kt:727-736` | 兜底 `dao.insertIfAbsent(pending)` 建了行，但 emit 被 `if (insertedAmbiguousPending)` 挡住 | **建了卡、零事件、零通知** |
| **F** | `CheckoutPaymentMatcher.kt:491-518` | `recordPersonalTransfer` 写 `sourceType = "accessibility_transfer"`，**从不 `PendingChangeHub.emit`**；且该值不被 `BridgeDtoMapper.validateSourceType` 接受（`BridgeDtoMapper.kt:47-55`） | 微信/支付宝的**个人转账/收款**：不弹窗、不发通知、**且无法在 RN 侧确认入账** |

> **F 特别提示**：若用户测试用的是微信/支付宝里的**转账 / 收款**而非购物下单，
> 走的正是这条路径 —— 现象表现为"完全没有弹窗"，与本条主症状不同但同源，建议一并复核。

---

## 四、如何在 5 分钟内确认走的是哪条路径

日志文件：`Android/data/com.jotbill.app/files/jotbill-diag.log`（**先删旧文件**）。

```powershell
$log = "$env:USERPROFILE\..\..\Android\data\com.jotbill.app\files\jotbill-diag.log"
Select-String -Path $log -Encoding UTF8 -Pattern `
  '支付包页面未分类为结果页|支付结果页无可用节点树|支付结果页解析到证据|孤立支付证据已落待确认|pending变更事件|弹窗候选挂起|弹窗候选超时放弃|弹窗跳过|付款确认弹窗已显示'
```

| 观察到的日志 | 判定 |
|---|---|
| 无 `支付结果页解析到证据`，多条 `支付包页面未分类为结果页` 且 `sensitive=true` | **根因 C** |
| 有 `弹窗候选挂起等待非敏感帧: … sensitive=true, foreground=com.tencent.mm` | **根因 B + C** |
| 有 `pending变更事件`，时间戳**晚于**切回应用 | 建卡晚了，问题在证据采集侧 |
| 有 `弹窗候选超时放弃` | **根因 B**（15s TTL 打掉） |
| 有 `弹窗跳过: DEDUP_WINDOW` / `HOURLY_LIMIT` | `PendingPromptPolicy` 去重/频控 |
| 有 `pending变更事件` + `付款确认弹窗已显示` **都在微信时段内** | 门禁已放行 ⇒ 转为**悬浮窗上屏可见性**问题（另查） |

> 🔑 **关键判别点**：`弹窗候选挂起等待非敏感帧` 那行日志**自带 `sensitive=` 与 `foreground=`**
> （`PendingConfirmOverlayManager.kt:297-301`），**直接就能判定是哪道守卫挡的，不用猜**。

**项目已有工具**：`scripts/verify-lean-l1.ps1` 已把这几种形态编成
`BLOCKED_SENSITIVE` / `NO_EVIDENCE` / `NO_NODE_TREE` / `SKIPPED_POLICY` / `DEDUPED`
（`:257-272`），可直接跑 20 笔统计命中率。

---

## 五、修复方案（**待裁定后动手，尚未实施**）

| # | 对应根因 | 方案 | 风险 |
|---|---|---|---|
| **1** | A（核心） | 在 `onAccessibilityEvent` 里，当 `resolveEffectiveShoppingPackage()` 返回值**不在白名单内**时，主动喂一帧"前台已离开支付/购物应用"的状态给 `PendingConfirmOverlayManager`，让 `:338` 的守卫真正生效 | 低。需新增一个显式的"前台变更"喂入口，不能复用 `onObservationState` 的 sensitive 语义 |
| **2** | B | `OBSERVATION_FRESH_MILLIS` 收敛到与 `CANDIDATE_TTL_MILLIS` 同量级；候选超时前若已确认"用户仍停留在支付应用"，**延长而非放弃** | 中。会影响 L6（无障碍未启用时不弹窗）的口径，需同步复核 `lean-version-acceptance.md:145` |
| **3** | C | 敏感判定**只让可见节点**的敏感词翻转整页标记；隐藏节点仅记录不翻转。**角色与金额通道保持现状** | 中。微信结果页关键语义在隐藏 `contentDescription` 里（`CHECKOUT_OVERLAY_FLICKER_AND_PAYMENT_CAPTURE_FIX.md:36-38`），**必须只改敏感通道**，否则结果页路径整体失效 |
| **4** | D/E/F | emit 的 `pendingId` 与实际插入主键对齐；补齐缺失的 emit；`accessibility_transfer` 要么加进 `BridgeDtoMapper.supportedSourceTypes` 并 emit，要么明确并入 `accessibility` | 低。各自独立，可分开提交 |

> **修复顺序建议**：1 → 4 → 2 → 3。
> 方案 1 单独就能让"切回应用才弹"这个**最刺眼的症状**消失，且不触碰证据链路。
> 方案 3 风险最高（可能打掉 L1 的主要命中率），**必须在 1、2 落地并重跑 L1 后再动**。

---

## 六、与其他文档的关系

| 文档 | 关系 |
|---|---|
| `qa/lean-version-acceptance.md` L1 | **本问题直接使 L1 无法通过**（一票否决项） |
| `qa/lean-version-acceptance.md` L6 | 方案 2 会影响其"无障碍未启用时不弹窗"口径，需同步复核 |
| `worklog/2026-10-08.md` §二·前提 1 | 相邻风险预警（"侥幸成立，不是设计上成立"），**但未发现根因 A** |
| `ISSUE_REGISTER_2026-09-11.md` P19 | 相邻缺陷（已显示弹窗不自动撤下），同属弹窗生命周期治理 |
| `FIX_TASK_2026-09-29_attribution_pending_dup.md` | D-25 跨桶去重（90s 窗口）与本问题同处结果页建卡路径，需注意叠加 |

---

## 七、待办

- [ ] 取一笔真机日志，按 §四 判别表确定实际命中的根因（A / B / C）
- [ ] 用户裁定修复范围（是否含方案 3）
- [ ] 实施后重跑 `scripts/verify-lean-l1.ps1`，L1 命中率需 ≥ 90%
- [ ] 同步复核 `qa/lean-version-acceptance.md` L6 口径是否需修订
