# 修复任务书：归属判定失效 → 待确认重复（2026-09-29 真机实证）

> **本文是接手本任务的第一入口。** 结论全部来自真机（小米 25128PNA1C / Android 17）+ 数据库实表核对，
> 不是推断。每条都附了代码位置与日志证据。
>
> 分支：`fix/attribution-and-pending-dup`（从 `main` @ `7ae9471` 切出，**未合并**）
> 基线：`main` 7ae9471 · 385 tests / 0 failures
>
> ## ✅ 2026-09-29 21:20 更新：取证完成，①② 已修
>
> - **§二 的两条假设已分辨完毕**（真机两笔对照，见 §二.1）；原「取证优先」表述作废。
> - **§三 的 ①② 已落地**（裁定见 `DECISIONS.md` §三 的 D-25 两行），测试 410 / 0 failures。
> - **③（待确认过期/清理）仍未做** —— 待裁定。
> - **④（归属是否该吃 D-19 容差）已结论：暂不改**，见 §三.④ 的更正版。

---

## 一、现象（用户报告）

> 「明明只支付了两笔，但是记录却不至两条」

实测数据（`jotbill-dump.db` 明文导出核对）：

| 待确认 id | 时间 | 金额(分) | 来源 | 对应支付证据 |
|---|---|---|---|---|
| `pending-orphan-2` | 19:57:49 | 1176 | 自动 | `payment-423c` |
| `pending-orphan-9` | 19:59:52 | 1176 | 自动 | `payment-d41a` ← 同一笔的第 2 次 |
| `pending-97926eb4` | 20:01:21 | 1362 | **用户手动悬浮按钮** | 无（占位未回填） |
| `pending-orphan-3` | 20:01:35 | 1362 | 自动 | `payment-488a` |
| `pending-orphan-7` | 20:02:38 | 1362 | 自动 | **无新证据**（重复投递） |

**2 笔支付 → 5 条待确认。**
- ¥11.76 → 2 条（同一笔，隔 123 秒被当两笔）
- ¥13.62 → 3 条（手动断言 + 自动 ×2）

另有 **24 条历史遗留**（09-10 / 09-11 的测试垃圾，其中 8 条 `status=ignored` 也仍在表里）。
**待确认表无任何过期/清理机制** —— `PendingPromptPolicy.DEDUP_WINDOW_MILLIS = 5min` 只是**弹窗节流**，不删记录。

---

## 二、根因（已定位到行）

> ## ⚠️ 2026-09-29 复核更正：本文早期版本把 ① 写成「`labeledAmounts` 从不去重 ⇒ 同值双标签判 null」，
> **该结论错误，已作废。** 实际字段是 `LinkedHashSet<Long>`（`PageAmountExtractor.kt:140`），天然去重；
> `singleOrNull()` 对同值双标签本就返回该值。现有用例 `repeatedSameValueIsNotAmbiguous`
> （`PageAmountExtractorTest.kt:85`）已钉死该行为。
> **下面 §二 的 ① 与 §三 的 ① 均已替换为「取证优先」。**

### 已确证的事实：归属判定 0 命中，直接原因是 `finalAmountMinor` 为 null

`CheckoutPurchaseIntentCoordinator.kt:1041-1046`（改造 E-2 归属判定，纯内存）：

```kotlin
val matched = handoffContexts.values.filter { context ->
    val pageAmount = context.finalAmountMinor ?: return@filter false   // ← null 即判不匹配
    pageAmount == signal.amountMinor &&
        abs(signal.occurredAt - context.handoffAtMillis) <= PAYMENT_RESULT_MATCH_WINDOW_MILLIS
}
```

`finalAmountMinor` 只在两种情况为 null（`PageAmountExtractor.kt:167-173`）：**无金额** / **多金额歧义**。

```kotlin
private val amounts = ArrayList<Long>(4)           // List，不去重
private val labeledAmounts = LinkedHashSet<Long>()  // Set，去重
...
fun resolveFinalAmountMinor(): Long? {
    if (labeledAmounts.isNotEmpty()) return labeledAmounts.singleOrNull()
    val distinct = amounts.distinct()
    return distinct.singleOrNull()                   // ★ 兜底路径才去重
}
```

**真机证据**：结算页门禁日志 `amountSignal=true`，金额**确实被检测到**——但那走的是
**另一条通道**（`hasFinalAmountSignal`，`Collector:494-508`，带 PDD 无标签兜底），
与 `finalAmountMinor` 的 accumulator **不是同一条路**。这正是需要取证分辨的地方。

### ~~两条待分辨的假设~~ → **已分辨（2026-09-29 21:08~21:13 真机两笔对照）**

| 假设 | 内容 | 现有旁证 |
|---|---|---|
| ~~**A**~~ **部分成立，但根因不同** | ~~带标签通道在真机上根本不成立~~ ⇒ **实际是：结算页标签词时有时无**。详见 §二.1 | `CONFLICTS_2026-09-10.md` **C3**：「拼多多确认订单页可能只出现「提交订单 + ¥55.70」，没有「实付/合计」等标签」—— **该旁证成立**，但只覆盖部分渲染变体 |
| ~~**B**~~ **不成立** | ~~两个带标签金额确实不同~~ ⇒ 实测从未出现 `labeled` 非空却判歧义的样本；`null` 全部来自「`labeled` 为空 + 无标签候选 ≥2 个」 | — |

### 二.1 【取证结论】结算页标签词时有时无，`finalAmountMinor` 的 null 是**正确行为**

2026-09-29 21:08~21:13 在同一台设备、同一 APK（`0.0.98` build 94）连付两笔，**结果完全相反**。
这是决定性对照——排除"通道恒不成立"与"提取器有 bug"两种可能：

| | 第一笔 ¥19.90 | 第二笔 ¥17.99 |
|---|---|---|
| 结算页 `labeledTerms` | **`[]`** | **`[合计]`** |
| `labeled`（带标签池） | `[]` | `[1799]` |
| `unlabeled`（无标签候选） | `[1990,2990,5190,760]` | `[1799,1899,100,3194,4599,7599]` |
| `signal` | `ACTION_WITH_CURRENCY`（PDD 无标签兜底） | `LABEL_TERM` |
| `finalAmountMinor` | **null** | **1799** |
| 归属判定 | **0 命中，`pendingHandoffs=1`** | 成功 |
| 最终去向 | 孤儿待确认 **×2** | **自动入账 `bill-ae5370cb3650a6f8`** |

第一笔的诊断原文：

```
AmountDiag pkg=com.xunmeng.pinduoduo role=CHECKOUT win=android.widget.TextView nodes=217 tokens=41 dropped=false
  | labeledTerms=[] labeled=[] unlabeled=[1990,2990,5190,760] final=null 歧义=是
  | signal=ACTION_WITH_CURRENCY term=false action=true currency=true subtotal=false
```

**结论**：

1. `labeledTerms=[]` —— 不是"标签与金额跨节点无法配对"，是**这个页型本次渲染压根没出标签词**。
2. `unlabeled` 有 4 个不同金额 ⇒ 兜底 `amounts.distinct().singleOrNull()` 必然返回 null ⇒ **提取器的保守行为正确**。
3. **不能靠扩展标签词表解决**（词表已含"合计"，是页面没渲染），也**不能假设它永远没有**。
4. 因此 `PageAmountExtractor` **一行不动**——动它等于让提取器去猜。

**⇒ 修法落在归属判定，不在提取通道。** 裁定见 `DECISIONS.md` D-25。

### 二.2 【取证结论】重复的第二条根因：`PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 的假设失效

第一笔的两次观测（**同一 `sessionId=52c26818`**）：

```
21:08:42.210 证据落库 Accepted(sessionId=52c26818, evidenceId=payment-2493…)   事件指纹 df4cec6593cb
21:08:42.219 落待确认 pending-orphan-2, reason=PAYMENT_RESULT_NO_MATCHING_SESSION, inserted=true
21:09:02.185 证据落库 Accepted(sessionId=52c26818, evidenceId=payment-2493…)   事件指纹 a63c2a56f9a3  ← 变了
21:09:02.190 落待确认 pending-orphan-4, reason=SUSPECTED_DUPLICATE, inserted=true  ← ★ 又建一条
```

- **证据层完全正确**：两次都是 `payment-2493`，`mergeSessionEvidence` 的 2 分钟窗口正常收敛。
- **孤儿层没跟上**：`pendingId = stableId("pending-orphan", signal.sourceEventKey)`，
  而 `signal.sourceEventKey` 取自**新解析证据**，不是合并后那一行。
- **跨桶的算术**：`sourceEventKey = sha256("result-event|$pkg|${occurredAt/30_000}|$amount|$orderId")`，
  `1790687322157/30000 = 59689577`、`1790687342160/30000 = 59689578` ⇒ **相邻两桶**。
- **失效的假设**：`PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 的 KDoc 明写"跨桶重复读靠本窗口收敛为一条证据"——
  **该假设只对证据行成立**，收敛不到 `recordOrphanPaymentSignal` 独立新建的孤儿卡。
- 第二条自己标了 `SUSPECTED_DUPLICATE`（`DuplicateGuard` 命中）**却仍 `inserted=true`**
  ⇒ 不是"没识别重复"，是"识别了还照建"。这比原 §三.② 设想的严重。

### 二.3 【顺带实证】方案 B 的角色判据修复在生效

21:13:18 用户回到拼多多时（第一笔 handoff 仍活跃，距搬入已 5 分钟）：

```
支付过程槽位搬运失败(会话不匹配): slotSession=5706b39d, requestSession=52c26818
支付过程槽位搬入: session=52c26818, result=Rejected(SESSION_MISMATCH)
支付结果页未解析成购买证据: reason=INVALID_AMOUNT      ×2
支付结果页图钉扫描终止
```

**未产生任何误匹配。** 提交 `3e935e3` 的三条遗留缺陷修复（角色判据 / 摘除兜底 / 通知出口闸）在真机上确实挡住了。

### 二.4 【误配风险实测】handoff 残留窗口很长

第一笔 handoff 在 `21:08:37.606` 搬入，到 `21:13:18.027`（**4 分 41 秒后**）仍在尝试搬运；
两笔的 `pendingHandoffs` 都是 **1**。
⇒ 5 分钟窗内若用户连付两笔，`pendingHandoffs` 会变 2 —— 这正是 D-25 要求"多候选时金额恢复硬性消歧"的原因。

### 由主因派生的三条重复路径

| # | 重复来源 | 说明 |
|---|---|---|
| ① | 同一结果页被当两笔（19:57:49 / 19:59:52） | `source_event_key` 不同；间隔 123 秒 > D-6 的 **30 秒桶** ⇒ 挡不住 |
| ② | 手动断言占位没被回填 | 设计是「占位 → 等支付证据回填」（提醒被抑制）；归属 0 命中 ⇒ 回填失败 ⇒ 占位永久留存，自动路径又另落一条。日志：`20:01:21.815 PendingReminder: 跳过提醒: 用户断言条目待支付证据 id=pending-97926eb4` |
| ③ | 结果页重复投递跨过 30 秒桶（20:02:38） | 同一事件被投递 20 次，日志 `跳过重复解析: 594095f993ca` ×20（幂等在**事件层**生效，但**跨桶**后成为新事件） |

---

## 三、待修项（按优先级）

> ⚠️ 原 ①「`labeledAmounts.distinct()`」**已作废**（对 `LinkedHashSet` 是空操作，见 §二 更正框）。

### ✅ ① 【已完成】诊断出口 + 归属判定按 D-25 修订

**取证部分（已完成）**：`AccessibilityPageSummaryCollector` 加 `FinalAmountSignalEvidence` 4 值枚举 +
`logAmountDiagnostics`，新增 `PageAmountDiagnostics.kt`（纯函数）+ 15 例单测。
输出格式见 §二.1 的 `AmountDiag` 原文。**判读口径**：`labeledTerms` 空 / 非空、`final` 是否 null、
`dropped` 是否 true，三者组合直接区分 null 的成因。

**修复部分（已完成）**：裁定见 `DECISIONS.md` §三 的 D-25 第一行。判别表：

| 时间窗内候选 | 页面金额 | 结果 |
|---|---|---|
| 0 | — | 0 命中（孤儿待确认） |
| 1 | 可判定且相等 | 归属 |
| 1 | **不可判定 null** | **归属**（本次真机命中这条） |
| 1 | 可判定但不等 | 0 命中（矛盾信息，不硬归） |
| ≥2 | 唯一金额相等 | 归属 |
| ≥2 | ≥2 个金额相等 | Ambiguous（待确认） |
| ≥2 | 无金额相等 | 0 命中 |

⚠️ **第三行是本次收窄的一档**：单候选 + 金额可判定但不等仍判 0 命中。
理由：用户在 A 商城下单后切到 B 支付工具付另一单，B 的结果页会落进 A 的 5 分钟窗内，
硬归会把账单记到错的订单 —— **自动入错账比多落一张待确认严重得多**。
D-25 只放行"无矛盾信息"，不放行"矛盾信息"。

改动落在 `CheckoutPurchaseIntentCoordinator.attributePaymentResultLocked`，`PageAmountExtractor` 一行未动。
新增单测 5 例（含 3 例边界负例：可判定但不等 / 时间窗外 / 多候选且全 null）。

### ✅ ② 【已完成 · 2026-09-30 修订窗口】孤儿落库加第二道去重

裁定见 `DECISIONS.md` §三 的 D-25 第二行。口径：**同支付包 + 同金额 + 90 秒窗内已有 `pending`
⇒ 合并 `conflictReason`，不新建第二张卡**。

⚠️ **与原设想的三处偏差**（都是取证后被迫的，记录在此以免后续误改回去）：

1. **不含 `sessionId`**。原写"同 `sessionId` + 同金额 + 时间窗"，但孤儿路径拿不到它：
   归属 0 命中时 `sessionId` / `sessionIds` 恒为空（`notifyOrphanSinkLocked` 的实参就是 `null` / `emptyList()`），
   而 `orphanSink` 是非 suspend 的 fire-and-forget，`recordPaymentResultEvidence` 的返回值拿不进来。
   改用「包名 + 金额 + 时间窗」承载区分职责。
2. **窗口 90 秒，不是 2 分钟**。误用 `PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 会把 D-6 明确要避免的
   "同包同金额、2 分钟内两单撞键"重新引进来。
3. **不动 `PaymentResultParser` 的 30 秒桶**。桶本身是 D-6 的正确裁定，问题在兜底链路断了，不在桶宽。

新增 DAO 查询 `findRecentPendingByPlatformAndAmount`（带 `status = 'pending'` 条件，
`platform` 为 null 的行天然不匹配 ⇒ 保守侧）。

#### ⚠️⚠️ 2026-09-30 真机把定案值证伪了：60 秒不够，已调至 90 秒

**D-25 定案的当天就被真机打脸。** 2026-09-30 10:05~10:06（小米 25128PNA1C / 拼多多 build 0.0.98）：

```
10:05:45.908  支付结果页解析到证据: 金额=268分, 状态=SUCCESS, textLength=20
10:06:46.307  支付结果页解析到证据: 金额=268分, 状态=SUCCESS, textLength=20   ← 同一页面
```

数据库实表：

```
pending-orphan-044f32b  occurred_at=1790733945898  (10:05:45)
pending-orphan-bf77dd6  occurred_at=1790734006291  (10:06:46)
                       相差 60393 ms —— 比 60 秒窗多 393 毫秒
```

**根因比"窗口小了"更深一层**：三道防线的阈值**全部挤在 30~60 秒窄带内**，
只要用户在微信结果页停留超过 60 秒，就在同一刻集体失效：

| 防线 | 原阈值 | 60.393 秒时 |
|---|---|---|
| `markPaymentResultEvent` 事件记忆窗 | 60 秒 | ❌ 过期 |
| `sourceEventKey` 时间桶 | 30 秒 | ❌ 跨桶 |
| **D-25 去重窗** | **60 秒** | ❌ **超 393 毫秒** |

**处置**：`PAYMENT_RESULT_EVENT_MEMORY_MILLIS` 与 `ORPHAN_DEDUP_WINDOW_MILLIS`
**同时调至 90 秒，且须保持同值**（不一致时先失效的那道形同虚设）。

**单测**：新增 `孤儿去重_真机实测60393毫秒的同一结果页重复观测只建一张卡`，用真机原值
（`1790733945898` / `1790734006291`）钉死该场景。
⚠️ **判别力已实测验证**：把常量临时改回 60 秒 → 该用例 `FAILED`；改回 90 秒 → 通过。
不是"算出来应该能红"，是真的红过。

⚠️ **原「超过 60 秒」负例同时被修正**：它原本用 `now + 90_000L` 当"窗口外"的值，
窗口调到 90 秒后该值**正好卡在边界上**（`BETWEEN` 两端闭区间 ⇒ 仍会去重），用它当负例会**假绿**。
已改为 `now + 150_000L`——落在窗口外且**仍在 D-6 的 2 分钟内**，顺带证明本窗口确实窄于 D-6。

#### 🔜 待办：这不是根治，纯时间判据必然失效

现在三道防线**全部是纯时间维度**，只要用户在结果页停留超过 90 秒，重复仍会产生。
根治方向是把"同一笔支付"锚定到**结算上下文**而非时间。

⚠️ 已知障碍（2026-09-29 已确认）：孤儿路径拿不到 `sessionId`。
⚠️ 但 09-30 这笔暴露了一个变通可能——两条记录的 `conflictReason` 都是
`NO_ACTIVE_SESSION,PAYMENT_RESULT_NO_MATCHING_SESSION`，说明它们源自**同一次无交接的支付**。
若能在信号里带上"本次会话的结算页指纹"，就有了一个**非时间维度的锚点**。
这属于**新的设计变更**，不是调参数，需单独裁定。

### 🔍 附带发现（2026-09-30，与 D-25 无关的独立问题）

09-30 10:05 的 ¥2.68 归属失败**不是 D-25 的问题**，是另一个机制：

```
10:05:36.013  结算页感知: ACCEPTED, role=CHECKOUT          ← 页面认出来了
（全程无 候选已写入 / source=CHECKOUT_STABLE）
10:05:40.278  支付交接诊断: currentStable=false, handoffStable=false, coordRef=null
10:05:45.935  支付结果归属 0 命中, pendingHandoffs=0
```

**结算页从未达到「稳定」状态**——用户在结算页只停留 **4 秒**（10:05:36 → 10:05:40 切微信），
稳定帧还没确认就走了 ⇒ `coordRef=null` ⇒ 交接未登记 ⇒ `handoffContexts` 为空。

对照成功的两笔：

| 金额 | `CHECKOUT_STABLE` 写入 | 搬入 | 耗时 |
|---|---|---|---|
| ¥18.50 | 09:44:37.441 | 09:44:39.215 | 1.8 秒 |
| ¥13.83 | 09:50:41.181 | 09:51:31.512 | 50 秒 |
| **¥2.68** | **无** | **无** | **停留 4 秒即切走** |

**这个行为本身是正确的**（拿不到上下文就不猜，落待确认让用户裁决），
但代价是：**用户手快点得快，就必然落待确认**。
是否要放宽稳定帧确认的时序门槛，**尚未取证、未裁定**，仅记录在此。

### ⬜ ③ 【未做·待裁定】待确认的过期/清理机制

当前**只增不减**。需要决定形态：TTL 自动清理 / 「清理已忽略与已过期」手动入口 / 两者都要。
注意 `status=ignored` 的记录现在也占着空间（09-11 的 8 条 `ignored` 至今仍在表里）。

### ✅ ④ 【已结论：暂不改】归属判定的「精确相等」是否该改容差

原建议是"先跑一次真机看是否仍有 0 命中，有的话再考虑归属侧吃容差"。

**2026-09-29 真机结论：不需要。** 0 命中的成因不是"金额有容差差"，而是 `finalAmountMinor` 为 null
（§二.1 已证）。第二笔 `1799 == 1799` 精确相等，归属成功并自动入账 ⇒ **精确相等在本场景够用**。

⚠️ 另需注意 D-25 与 D-19 容差**口径刻意不同**，不得合并：
- D-19 容差用于**入账**（订单金额 vs 实付，允许运费/券差）
- 归属判定的金额是**消歧条件**（哪个会话），放宽会直接放大误配面

另有一条已知的设计边界（未改、也不打算在 v1 改）：结算页显示的可能是**商品单价**而非实付总额
（第一笔的 4 个候选里 1990 恰是实付，只因用户买了**一件**商品）。
多件商品时结算页金额本就 ≠ 实付 —— 但那属于"结算页有没有总额"的页型问题，
不是归属判定该兜的，兜了就是让归属去猜。

---

## 四、真机验证方法（已就绪）

```bash
# 1. 装包
cd jotbill-app/android && ./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. ★ 无障碍会被 install -r 保留，但若用了 force-stop 会被移出启用列表 —— 务必确认
adb shell settings get secure enabled_accessibility_services

# 3. Metro（debug 包走 RN，红黑屏 = 没起）
npm start   # 另一个终端
adb reverse tcp:8081 tcp:8081

# 4. 跑之前清日志（⚠️ 顺序反了会白跑，见 §五 的两条竞态）
#    正确顺序：先让进程停住 → 再删文件 → 再拉起进程
adb shell am force-stop com.jotbill.app
adb shell settings put secure enabled_accessibility_services \
  com.jotbill.app/com.jotbill.app.recognition.CheckoutAccessibilityService
adb shell rm -f /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
adb shell monkey -p com.jotbill.app -c android.intent.category.LAUNCHER 1
adb shell "ls -l /sdcard/Android/data/com.jotbill.app/files/"   # 必须看到 jotbill-diag.log

# 5. 支付若干笔后，取两个文件
adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-dump.db
sqlite3 jotbill-dump.db "SELECT substr(id,1,16), amount_minor, status, conflict_reason, datetime(created_at/1000,'unixepoch','localtime') FROM pending_transactions ORDER BY created_at DESC;"
```

> ⚠️ `jotbill-dump.db` **只在 `Application.onCreate` 时导出一次**（`DiagDbDump.dumpAsync`），
> 支付过程中不会刷新。要看支付后的表内容必须重启 App，或直接读上面的诊断日志。

**判定标准**：N 笔支付 ⇒ N 条待确认（或更少，因自动入账后不留待确认）。

---

## 五、本次已落地的诊断设施（别删）

| 文件 | 作用 |
|---|---|
| `utils/JotBillLog.kt` | 日志落盘到 `Android/data/<包>/files/jotbill-diag.log`。153 处调用点由 `Log.` 换成 `JotBillLog.`，内部仍先调 `android.util.Log` ⇒ **logcat 行为不变**。对比：旧 logcat 采集单文件 85.6MB，这个 8KB/15 秒 |
| `utils/DiagDbDump.kt` | SQLCipher 明文导出到 `jotbill-dump.db`，本地 sqlite3 可查。**必须 `wal_checkpoint(TRUNCATE)`**，否则导出旧快照 |

**已知踩坑**（都踩过了）：
- PowerShell `>` 重定向会**损坏二进制**，拉 db 必须 `cmd /c "adb exec-out ... > file"`
- `adb shell am force-stop` 会把应用置 stopped state，**连带停掉无障碍服务**
- PowerShell 控制台输出中文是乱码 ⇒ **不要用控制台 grep 日志判断**，要 `adb pull` 到本地用工具读
- `adb logcat -d -s <TAG>` 在本环境过滤不可靠，用 `logcat -d | Select-String` 或拉文件

**2026-09-29 补记（三条，都是白跑过一轮换来的）**：

- ⚠️ **无障碍重绑会**自动**拉起 App 进程**——不必等 `monkey`。所以「先启动 App、再 `rm` 日志」是错的：
  `rm` 删掉的是刚建好的文件，而进程持有的 `FileWriter` 句柄仍指向那个**已删除的 inode**，
  后续写入全部进黑洞。现象极具迷惑性：`init` 日志明写"诊断日志落盘已启用"，
  `ls` 却只有 `jotbill-dump.db`（它落盘更晚，侥幸逃过一劫）。
  ⇒ **顺序必须是：让进程停住 → 删文件 → 拉起进程**，见 §四 第 4 步。
- ⚠️ **`settings put secure enabled_accessibility_services ""` 报 `Bad arguments`**——空串不被接受，
  那一步其实没生效。靠"碰巧的时间差"生效的流程是不可靠的，别当成做法。
- ⚠️ **`logcat` 环形缓冲在本机只保留约 1~2 分钟**（实测：63032 行里最早一条是 21:00:54，
  而 `JotBillLog` 的 init 发生在 20:56）。而一次支付从结果页落库到通知弹出要 20 秒、
  从打开购物 App 到支付完成要 2 分钟以上 ⇒ **单靠 logcat 抓支付链路在时序上本来就不可靠**。
  这条反过来印证了应用内诊断日志（追加写、10MB 轮转）是这个场景该用的工具。
  ⚠️ 反面代价：`JotBillLog` 的写入是**单后台线程 + 追加**，进程被杀后句柄失效，
  新进程 `init` 会重新打开——但**删文件这个动作必须发生在进程启动之前**，否则旧句柄仍指向已删 inode。

---

## 六、纪律提醒

- `CLAUDE.md` §五：「**先复述再动手**」—— 改动前列出文件清单，确认后再改
- `CLAUDE.md` §六：文档「**加注不覆盖**」，用 `~~删除线~~` + ⚠️ 指向新裁定，不删原文
- `IMPL_PLAN` §8 的 8.2 / 8.4 标为 **⚠️推断**，勿当裁定引用
- **不要把推断写成裁定**
- 本分支修完再合并 main，**不要提前合**
