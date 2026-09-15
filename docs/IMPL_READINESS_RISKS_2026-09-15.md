# 实现前可执行性风险清单（2026-09-15）

> **背景**：用户提问"按现在的文档改代码，会不会遇到表述不清、AI 乱写代码的情况"。
> **方法**：逐节审读 `IMPL_PLAN_VERSION_B_2026-09-13.md` 全文 + `CONFLICT_REVIEW_SESSION_2026-09-11.md` 代码评估清单，并对文档声明的每个代码锚点做实测复核。
> **结论**：**文档的"设计意图"部分很扎实，风险集中在"设计意图 → 代码落点"的最后一公里。**

---

## 一、总体判断

| 维度 | 评价 |
|---|---|
| 设计意图的完整性 | ✅ **好** —— 五项改造都有目标、依据、裁定编号 |
| 代码锚点的准确性 | ✅ **好** —— 抽验 6 处锚点**全部对上**（见第五节） |
| 实现规格的详细度 | ⚠️ **不均** —— 改造 B / D-19 / D-21 很细；改造 A / E 有缺口 |
| **会不会导致 AI 乱写** | ⚠️ **会，但集中在 3 处高风险 + 6 处需拍板点**，均可提前消除 |

**一句话**：不是"文档写得不好"，而是有 **3 个位置会让 AI 写错方向**、**6 个位置会让 AI 自行决定**。前者必须补，后者建议先拍板。

---

## 二、🔴 高风险：不处理会写错方向

### 🔴 1. 代码评估清单里的 TTL 数值是已废弃的（**已于今日修复**）

`CONFLICT_REVIEW_SESSION_2026-09-11.md:1683`（**代码评估清单**，实现者会照做的一节）：

```
- [ ] 【G6.3 Q2a】快照/槽位 TTL 数值：DEFAULT_SNAPSHOT_TTL_MILLIS（Buffer:13）
      由 120_000L 改为 90_000L，与 G1-6 定案一致。
```

**问题**：`90_000L` 已被 0.3.3 否决（改为支付槽 2 分钟 / 结算页 5 分钟分离）。同一文档的裁定表 `:1512` **已加注**，但**代码清单这处漏了**。

**危害**：AI 读代码清单 ⇒ 直接把 TTL 写成 90 秒 ⇒ 与用户裁定冲突，且这个错误值会进入代码。

> ✅ **2026-09-15 已修**：加删除线 + 指向 `IMPL_PLAN §2.2 B-1/B-4`，并注明"本条不单独实施"。
> 同类：`:1689` "如需上限另加常量"的开放表述也已收口（0.3.3 已定为 5 分钟）。

### 🔴 2. 归属判定的"调用点"与"职责边界"未明确

改造 E（`handoffContext` 映射化）的核心是"结果页到达时判定它属于哪一单"。但**判定代码放在哪一层，文档有三处不一致的说法**：

| 出处 | 说法 | 隐含的调用点 |
|---|---|---|
| `IMPL_PLAN` A-3（`:207-211`） | `coordinator.trigger(..., sessionId = reference.sessionId)` | **Service 层已知 sessionId** |
| `IMPL_PLAN` A-4（`:224-227`） | "按 §9.2 口径在 `handoffContexts` 中做归属判定 → 产出 sessionId；`contextFor(PAYMENT_RESULT)` 返回 `handoffContexts[sessionId]`" | **coordinator 内部判定** |
| `IMPL_PLAN` E-2（`:503`） | "复用既有 `resolveSessionForPayment` 的纪律" | 未指明 |

**矛盾点**：A-3 的 `reference.sessionId` 是**改造 E 之前的语义**（单槽 `handoffContext` 才有唯一 reference）；改造 E 之后没有唯一 reference，必须靠"金额 + 时间窗"判定。**两处写法冲突。**

**且**：现有代码中 `resolveSessionForPayment` 是 **`CheckoutPaymentMatcher.kt:252` 的 `suspend fun`**，由 **`Service:2166` 调用**。改造 E 是要"改这个已有判定的输入来源"，还是"新写一套判定"？**文档未说。**

**危害**：AI 可能 ① 重复实现两套判定；② 放错层（在 coordinator 里写需要 DB 查询的逻辑）；③ 或按 A-3 的字面写 `reference.sessionId` 而拿不到值。

**建议**：补一段"改造 E 的落点声明"，明确三件事：判定函数放哪个类、由谁调用、`handoffContexts` 由谁持有（现在 `handoffContext` 在 `IntentCoordinator`）。

### 🔴 3. 批 V-1 的 8 项规格不在 `IMPL_PLAN`，且**没有指路**

`IMPL_PLAN:11`：

> 其余 8 项（D-9 / D-10 / D-11 / D-16 / D-19 / D-20 / D-21 / 死入口清理）**规格已完整，可直接开工，不在本文重复**。

**问题**：规格确实完整，但它们散在 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的**代码评估清单**节（`:1654-1690`）。`IMPL_PLAN` 说"不重复"，却**没有给出"去哪找"的索引**。

**危害**：若给 AI 的指令只说"按 IMPL_PLAN 实现批 V-1"，它会**找不到这 8 项的规格**，然后自行发挥（而 D-19 的 SQL 改法、D-21 的"断言排除集合化"这类细节，自行发挥几乎必错）。

**建议**：在 `IMPL_PLAN §4` 的批 V-1 行补一句指路：「8 项规格见 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 代码评估清单节（`:1654-1690`）」。

---

## 三、🟠 中风险：AI 会自行决定（建议先拍板）

| # | 位置 | 模糊表述 | 可能被写成的样子 | 建议 |
|---|---|---|---|---|
| 4 | `CONFLICT_REVIEW:1661` | "**清理/重定位**死入口：`requestSubmitAction` / `requestPaymentAppHandoff` / `requestPaymentPageVisible` / `requestUserRetry`（4 个无 caller）" | "清理"=删除，"重定位"=搬家 —— AI 可能选错 | **明确选"删除"**（4 个无 caller，且 `SUBMIT_ACTION` 链路整体删除）；`requestUserRetry` 若仍有 plan 保留需求，需单列 |
| 5 | `IMPL_PLAN:463` | "实现时**二选一**：给 `ignore` 显式终态，或把扫描条件改为'未达终态'" | AI 随机选一个 | **先拍板**。建议选"扫描条件改为未达终态"（改动小、不动规则引擎语义） |
| 6 | `IMPL_PLAN:511` | 容量上限 `MAX_PENDING_HANDOFF_CONTEXTS`（**建议 4**） | AI 可能改成 5/10 或加自适应逻辑 | **确认取值**（"建议 4"其实已够用，建议直接定 4） |
| 7 | `IMPL_PLAN` A-3 vs §1.1 | 现有签名 `trigger(intent, fingerprint, callback, paymentNotification)`（`:204`）；目标用法只给 3 参数 | AI 自行补 `callback` / `paymentNotification` 的值 | **补完整目标签名**（含 callback 传什么、paymentNotification 是否传 null） |
| 8 | `IMPL_PLAN:183-184` | "`AUTO_DISPATCHABLE_PAGE_ROLES = {PRODUCT_DETAIL, CART, CHECKOUT}`（宽进三类，**单一出处**）" | 常量放哪？`CheckoutContract.kt`？coordinator 内？ | 实测**代码中不存在该常量** ⇒ 是新增。**建议明确声明"新增于 `CheckoutContract.kt`（与 `canBufferSnapshot` 同处，保证单一出处）"** |
| 9 | `IMPL_PLAN` A-5 表（`:238`） | `releaseCandidates` 释放粒度 → 需改列写"**复核**（D-16 ③）" | "复核"不是动作，AI 可能跳过或过度修改 | 改为明确的**判定条件**（或显式写"经复核不需改动，理由：…"） |

---

## 四、🟡 低风险：技术细节未覆盖（不会写错方向，但可能埋雷）

10. **`LinkedHashMap` 的并发安全**：E-2（`:501`）指定 `handoffContexts: LinkedHashMap<sessionId, SafeCheckoutContext>`，但未提线程安全要求。`IntentCoordinator` 的回调可能跨线程（无障碍事件线程 / AI 回调线程）⇒ 若 AI 直接无锁读写，可能出现并发问题。**建议在规格里补一句"须与现有 `handoffContext` 相同的访问保护方式（现有实现如何加锁就照做）"**。

11. **改造 D 的"启动期兜底扫描"落地细节**：`IMPL_PLAN:472` 只说"新增'`WAITING_ENRICHMENT` 且无 AI 结果 ⇒ 落待确认'的兜底扫描"，未说**注册在哪**（Application? Service? `RuntimeModule`?）、**扫描频率**、**是否只在启动跑一次**。AI 需自行决定。

12. **`classifyPageRole` 8 级短路顺序**（`CONFLICT_REVIEW:1657`）："核对…是否需要调整" —— 这是**待评估项**而非规格，AI 可能在这一条上反复试探。

---

## 五、✅ 可以放心的部分（实测复核结论）

### 5.1 代码锚点全部有效

我实测复核了文档声明的 6 处承重锚点，**全部对上**（尽管 X1 修复曾大改 `CheckoutSnapshotCaptureCoordinator.kt` +139/-36）：

| 文档声明 | 实测 | 结果 |
|---|---|---|
| `hasRetainableCandidate`（`Coordinator:1385-1402`） | `:1385` | ✅ |
| `driftWithinSameCheckout`（`:1490-1497`） | `:1490` | ✅ |
| 敏感页锁存点（`Service:886-898`） | `:886-898` 确为 `observation.isSensitivePage` 分支 | ✅ |
| 结果页主路径去重键（`PaymentResultParser:208-210`） | `:208-210` | ✅ |
| 截图兜底去重键（`Service:1839-1842`） | `:1839-1842` | ✅ |
| `resolveSessionForPayment` | `Matcher:252`（`suspend fun`） | ✅ |

**原因**：这些修复提交（X1/Y5/Y4）都发生在方案编制**之前**，所以行号未漂移。

### 5.2 规格写得好的部分

- **改造 B（支付过程槽位）**：完整 Kotlin 签名 + 两个常量 + 槽位前缀 + 改造前后代码块，**且显式标出"最易错点"**（`removeCandidate` 会 `release()` 抹零 ⇒ 必须走新的 `detachCandidate` 路径）。
- **D-19（金额容差）**：给出 2 层落点（`Matcher.evaluate:1995-2009` + `TransactionCandidateDao.kt:75-87`）、**具体 SQL 表达式**（`ABS(...) <= MAX(100, :amountMinor * 5 / 100)`）、并说明"不改第二层则新容差完全失效"。
- **D-21（0 金额弹窗）**：5 个子项，含前置项（断言排除条件由精确相等改为集合/前缀匹配）、UI 启用条件、文案分支、**连带的 `PendingReminderNotifier` 同类漏洞核查**。
- **批 V-1 的 D-16**：3 处改动均给了行号 + 改动方向 + 明确"哪些不动"（`canEstablishUserRetrySession` / `hasAutomaticCandidate` 不动，有真机故障背书）。

### 5.3 回归测试已覆盖主要分支

`IMPL_PLAN §5` 有 27 条测试（含 15a/15b 两个补充形态），且**关键项标注了"专守某条风险"**（如 4/7 条专守两槽位 TTL 差异、20/21 条专守断言通道与截图兜底回归）。

---

## 六、给"开工"的具体建议

### 6.1 给 AI 的指令要写明阅读范围

**不要**只说"按 IMPL_PLAN 实现批 V-X"。建议模板：

> 实现批 V-1 的 `D-19`。规格见：
> ① `docs/IMPL_PLAN_VERSION_B_2026-09-13.md` §4（批次）与 §2.6（若涉及）
> ② `docs/CONFLICT_REVIEW_SESSION_2026-09-11.md` 代码评估清单节 `:1654-1690` 中的 **D-19 条**
> ③ `docs/DECISIONS.md` 的 D-19 行
> 先复述你的理解 + 列出要改的文件清单，我确认后再动手。

理由：文档是三份互补的（裁定 / 设计 / 代码清单），只给一份会漏。

### 6.2 先做批 V-1，验证流程

批 V-1 的 9 项独立、可测、无依赖，**风险最低**。用它跑通"给指令 → AI 复述 → 改代码 → 跑测试"的流程，再动结构性改造（批 V-2~V-5）。

### 6.3 开工前先拍板这 3 件事

1. **批 V-1 无指路问题**（`:11`）—— 决定是否补索引，或每次指令里手写
2. **"死入口清理"= 删除还是重定位**（第 4 条）
3. **`ignore` 语义选哪条路**（第 5 条）

其余 🟠 项可在对应批次开始前拍板。

### 6.4 结构性改造前，补 A-3/A-4/E-2 的落点声明

第 2 条（归属判定调用点）**必须在批 V-3 开工前解决** —— 它是改造 B 与改造 E 合并实施的关键，边界不清会同时影响两条。

---

## 七、本次一并修复的项

| # | 位置 | 修复 |
|---|---|---|
| 1 | `CONFLICT_REVIEW:1683` | `90_000L` 加删除线 + 指向 `IMPL_PLAN` §2.2 B-1/B-4 + 注明"本条不单独实施" |
| 2 | `CONFLICT_REVIEW:1689` | "如需上限另加常量" → 收口为已定案的 5 分钟会话上限 |

---

## 八、定案结果（2026-09-15，用户授权"依讨论记录代为拍板"）

**全部 8 项已定案**，落地位置：**`IMPL_PLAN_VERSION_B_2026-09-13.md` §8「开工前决策与修正」**（含定案总表、依据、依据性质标注）。各定案均已就地写回正文。

| # | 事项 | 定案 |
|---|---|---|
| 8.1 | A-3 `trigger` 调用 | 不传 `sessionId`/`pageFingerprint`（实测现有签名后 3 参数均有默认值 ⇒"需扩展签名"不成立） |
| 8.2 | 归属判定落点 | `IntentCoordinator` 内部、纯内存（复用判定原则，不复用 `resolveSessionForPayment` 函数） |
| **8.3** | **sessionId 传递链路** | 🆕 **新发现（阻断级）**：`SafeCheckoutContext` / `StableCheckoutPage` / `CheckoutPageObservation` **均无 sessionId** ⇒ 改造 B 的 `retained.sessionId` **编译不过**；须先补该链路 |
| 8.4 | `ignore` 语义 | 给 `ignore` **显式终态**（⚠️ **推断** —— 记录无直接裁定） |
| 8.5 | 常量位置 | 新增于 `CheckoutContract.kt`，与 `canBufferSnapshot()` 引用同一常量 |
| 8.6 | 释放粒度 | `releaseCandidates` 收窄为"**只释放被消费的那一张**"（D-16 = B 的直接推论） |
| 8.7 | 批 V-1 规格出处 | 指向 `CONFLICT_REVIEW` 代码评估清单节（`:1654-1690`） |
| 8.8 | 死入口清理 | 4 方法 + 1 枚举**全部删除**（含 `requestUserRetry` 已被 `requestUserRetryForSession` 取代） |

> ⚠️ **两项非"用户裁定"的标注**：8.2 / 8.4 属**依据推断**（记录中无直接用户表态），已在 `IMPL_PLAN` §8 明确标注"推断"；其余各项均有代码实测或既有裁定/裁定原则支撑。**未写入 `DECISIONS.md`**（正典只记用户裁定）。

**结论：无遗留待拍板项。** 8 项全部定案，且 8.3 的新发现已一并落盘。
