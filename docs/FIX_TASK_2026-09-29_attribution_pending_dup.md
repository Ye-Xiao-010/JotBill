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

### 主因：`finalAmountMinor` 恒为 null ⇒ 归属判定恒 0 命中

`CheckoutPurchaseIntentCoordinator.kt:1041-1046`（改造 E-2 的归属判定，纯内存）：

```kotlin
val matched = handoffContexts.values.filter { context ->
    val pageAmount = context.finalAmountMinor ?: return@filter false   // ← null 即判不匹配
    pageAmount == signal.amountMinor &&
        abs(signal.occurredAt - context.handoffAtMillis) <= PAYMENT_RESULT_MATCH_WINDOW_MILLIS
}
```

`finalAmountMinor` 来自 `PageAmountExtractor.kt:167`：

```kotlin
fun resolveFinalAmountMinor(): Long? {
    if (labeledAmounts.isNotEmpty()) {
        return labeledAmounts.singleOrNull()   // ★ 2 个带标签金额 ⇒ null
    }
    val distinct = amounts.distinct()          // ★ 兜底路径才去重，且够不着
    return distinct.singleOrNull()
}
```

**`labeledAmounts` 按出现次数累加，从不去重。** 拼多多结算页几乎必然同时显示「订单金额」与
「实付」两个标签 —— 即使两个数值**完全相同**，也是 2 个元素 ⇒ `singleOrNull()` 返回 null。

⚠️ **这不是实现 bug，是裁定的后果没被评估。** `MEMORY.md` V-2 记载的裁定：
「`订单金额`+`实付`并存 ⇒ 返回 null（不得用词表顺序当优先级）」。裁定本身正确（保守、不猜），
但下一步没人算到：拼多多 100% 命中该形态 ⇒ 归属判定结构性失效。

**真机证据**：结算页门禁日志明确 `amountSignal=true`，金额**检测到了**，只是收口时被判成歧义丢弃。

### 由主因派生的三条重复路径

| # | 重复来源 | 说明 |
|---|---|---|
| ① | 同一结果页被当两笔（19:57:49 / 19:59:52） | `source_event_key` 不同；间隔 123 秒 > D-6 的 **30 秒桶** ⇒ 挡不住 |
| ② | 手动断言占位没被回填 | 设计是「占位 → 等支付证据回填」（提醒被抑制）；归属 0 命中 ⇒ 回填失败 ⇒ 占位永久留存，自动路径又另落一条。日志：`20:01:21.815 PendingReminder: 跳过提醒: 用户断言条目待支付证据 id=pending-97926eb4` |
| ③ | 结果页重复投递跨过 30 秒桶（20:02:38） | 同一事件被投递 20 次，日志 `跳过重复解析: 594095f993ca` ×20（幂等在**事件层**生效，但**跨桶**后成为新事件） |

---

## 三、待修项（按优先级）

### ① `PageAmountExtractor.resolveFinalAmountMinor()` — 一行改动

`labeledAmounts.distinct().singleOrNull()` —— **先去重再判唯一**。

同一金额的多个标签（订单金额 = 实付）是**无歧义**的，不该判 null。这不违反裁定精神：
裁定禁的是「用词表顺序随便挑一个」，这里是「去重后仍唯一才算唯一」。

配套：`PageAmountExtractorTest` 补一例「订单金额 = 实付 同值双标签 ⇒ 期望返回该值而非 null」。

**预期效果**：归属判定恢复 ⇒ 自动入账 ⇒ 重复来源 ① ② 消失。

### ② 孤儿落库加「同金额 + 时间窗」二次去重

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
