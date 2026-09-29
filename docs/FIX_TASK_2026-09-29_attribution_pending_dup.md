# 修复任务书：归属判定失效 → 待确认重复（2026-09-29 真机实证）

> **本文是接手本任务的第一入口。** 结论全部来自真机（小米 25128PNA1C / Android 17）+ 数据库实表核对，
> 不是推断。每条都附了代码位置与日志证据。
>
> 分支：`fix/attribution-and-pending-dup`（从 `main` @ `7ae9471` 切出，**未合并**）
> 基线：`main` 7ae9471 · 385 tests / 0 failures

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

### 两条待分辨的假设（均未证实，⚠️ 不得当裁定引用）

| 假设 | 内容 | 现有旁证 |
|---|---|---|
| **A** | **带标签通道在真机上根本不成立**：PDD 结算页「实付」与「¥11.76」是两个 TextView。`observe(rawValue)` 要求**同一节点文本内**标签与金额共存 ⇒ 标签节点无金额提前 return（`:153-155`），金额节点无标签只进 `amounts` ⇒ `labeledAmounts` 恒空 ⇒ 落到兜底 `amounts.distinct().singleOrNull()`（`:171`）⇒ 页面有 ≥2 个不同金额（单价/优惠/运费/实付）即 null | `CONFLICTS_2026-09-10.md` **C3** 原文（引 `Collector:500-508`）：「**拼多多确认订单页可能只出现「提交订单 + ¥55.70」，没有「实付/合计」等标签**」 |
| **B** | 两个**带标签**的金额确实不同（订单金额 ¥15.00 + 实付 ¥11.76）⇒ 返回 null 是**裁定 (A) 的正确行为** | `IMPL_PLAN_VERSION_B:444-448` 明写「若将来要提高命中率：用**显式分组**，**不得**依赖词表顺序」；`PageAmountExtractor.kt:129-133` KDoc 亦已记载该裁定 |

**关键差异**：假设 A 是**实现缺口**（标签与金额跨节点无法配对，可修）；假设 B 是**需要新裁定**（分组优先级要走
`CONFLICT_REVIEW_SESSION → DECISIONS.md` 流程，不能顺手改）。

**现有诊断设施分辨不了** —— 门禁日志只打布尔（`amountSignal=…`），没打候选金额。

### 由主因派生的三条重复路径

| # | 重复来源 | 说明 |
|---|---|---|
| ① | 同一结果页被当两笔（19:57:49 / 19:59:52） | `source_event_key` 不同；间隔 123 秒 > D-6 的 **30 秒桶** ⇒ 挡不住 |
| ② | 手动断言占位没被回填 | 设计是「占位 → 等支付证据回填」（提醒被抑制）；归属 0 命中 ⇒ 回填失败 ⇒ 占位永久留存，自动路径又另落一条。日志：`20:01:21.815 PendingReminder: 跳过提醒: 用户断言条目待支付证据 id=pending-97926eb4` |
| ③ | 结果页重复投递跨过 30 秒桶（20:02:38） | 同一事件被投递 20 次，日志 `跳过重复解析: 594095f993ca` ×20（幂等在**事件层**生效，但**跨桶**后成为新事件） |

---

## 三、待修项（按优先级）

> ⚠️ 原 ①「`labeledAmounts.distinct()`」**已作废**（对 `LinkedHashSet` 是空操作，见 §二 更正框）。

### ① 【先取证，再改判定】在 collector 收口处落诊断日志

**不要先改 `resolveFinalAmountMinor`** —— 假设 A 与 B 的修法完全不同（实现缺口 vs 需要新裁定），
改错方向的成本远高于多打一行日志。

**改动点**：`AccessibilityPageSummaryCollector.kt:347` 附近，`resolveFinalAmountMinor()` 调用处，
加一条 WARN，把三个值落进已有的 `jotbill-diag.log`：

- `labeledAmounts`（去重后的带标签金额集合）
- `amounts.distinct()`（无标签兜底候选）
- `finalAmountMinor`（最终结果）

建议顺带把 `hasFinalAmountSignal` 的判定依据也打出来，因为它走的是**另一条通道**
（`:494-508`，带 PDD 无标签兜底），两者不一致正是线索所在。

配套：抽一个纯函数便于补单测。

**取证结果决定下一步**：

| 若为 | 修法性质 | 走不走裁定流程 |
|---|---|---|
| **假设 A**（标签与金额跨节点无法配对） | **实现缺口**：改「标签与金额跨节点配对」，或按 `IMPL_PLAN:469` 已指出的先例（复用 `finalAmountTerms` 定位金额区域）补强 | 否，但**须先补单测钉住负例** |
| **假设 B**（两个带标签金额确实不同） | **需要新裁定**：分组优先级 | **是**，须走 `CONFLICT_REVIEW_SESSION → DECISIONS.md`，不能顺手改 |

**真机验一次即可分辨**：付一笔，看日志里 `labeledAmounts` 是空还是有值。

### ② 孤儿落库加「同金额 + 时间窗」二次去重（**不受 ① 取证阻塞，可并行**）

不只靠 `source_event_key`。`recordOrphanPaymentSignal` 目前只按 `stableId("pending-orphan", sourceEventKey)` 幂等，
而 `source_event_key` 含时间桶 ⇒ 跨桶即失效。

**⚠️ 需先想清楚**：直接按金额去重会与 D-6「同额两笔必须都保留」冲突。安全做法是
**仅在 `conflictReason = PAYMENT_RESULT_NO_MATCHING_SESSION` 且金额相同、时间差 < N 秒时合并**，
且合并时保留两条 conflictReason（已有合并先例见 `CheckoutPendingTimingTest`）。

### ③ 待确认的过期/清理机制

当前**只增不减**。需要决定形态：TTL 自动清理 / 「清理已忽略与已过期」手动入口 / 两者都要。
注意 `status=ignored` 的记录现在也占着空间。

### ④ 归属判定的「精确相等」是否该改容差

`pageAmount == signal.amountMinor` 是**精确相等**，而全系统入账判定用 D-19 容差
`max(¥1.00, 支付额×5%)`。若结算页订单金额与实付有差额（运费/优惠券），精确相等仍会不命中。

**建议**：① 改完先跑一次真机，看是否仍有 0 命中；**有的话**再考虑归属侧是否也吃容差
（但归属比入账更敏感，放宽需谨慎评估误配风险）。

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

# 4. 跑之前清日志
adb shell rm -f /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
adb logcat -c

# 5. 支付若干笔后，取两个文件
adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-dump.db
sqlite3 jotbill-dump.db "SELECT substr(id,1,16), amount_minor, status, conflict_reason, datetime(created_at/1000,'unixepoch','localtime') FROM pending_transactions ORDER BY created_at DESC;"
```

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

---

## 六、纪律提醒

- `CLAUDE.md` §五：「**先复述再动手**」—— 改动前列出文件清单，确认后再改
- `CLAUDE.md` §六：文档「**加注不覆盖**」，用 `~~删除线~~` + ⚠️ 指向新裁定，不删原文
- `IMPL_PLAN` §8 的 8.2 / 8.4 标为 **⚠️推断**，勿当裁定引用
- **不要把推断写成裁定**
- 本分支修完再合并 main，**不要提前合**
