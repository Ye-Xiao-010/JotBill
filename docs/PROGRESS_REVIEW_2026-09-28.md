> **状态头**：历史取证 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# JotBill 进度总览（2026-09-28 重建）
> **状态**：[归档] —— **已由 `00-现状正本.md` 取代**
> **⚠️ 已知缺陷**：本文件曾把 ROADMAP 批 1/2/3/5/6 与 journey 列为「待确认的未完成项」，**该扩大范围的判断已被用户纠正** —— 范围应由用户确认，不由核查方从文档推断
> 当前状态见 `00-现状正本.md`

> 用途：长时间中断后重新上手时的单一入口。**本文件按「事实 → 待办 → 建议路径」组织，不做叙述。**
> 重建依据：git 历史 + 测试报告 + `.workbuddy/memory/` 日志（9-17 / 9-18 / 9-28）+ 2026-09-28 复核。

---

## 一、一句话定位

**方案 B 的改造阶段（批 V-1 ~ V-5）已全部完成并提交**，当前处于**收尾验收阶段**：
剩下的是「真机回归 + 文档回填 + 遗留缺陷清理」，**没有未完成的结构性改造**。

---

## 二、事实状态（已核实）

| 项 | 值 |
|---|---|
| 分支 | `main` |
| 最新提交 | `eb81d5f`（2026-09-17 22:02，"docs: V-5 实施前核查补正"） |
| 工作区 | **干净**（无未提交改动、无 stash） |
| 未跟踪 | `docs/showcase/`（展示站，9-22 修改）、`jdk17/`、`msjdk17.zip` |
| 测试基线 | **374 tests / 0 failures / 0 ignored**（报告 `index.html`，9-17 21:57 生成） |
| 9-18 ~ 9-28 | **零提交**（代码/文档均无变化） |
| 远程同步 | ⚠️ **无法独立核实** —— `git ls-remote` 报 `could not read Username for 'https://github.com'`（凭据失效，非代理问题）。9-18 09:30 曾成功推送 `ddc34be..eb81d5f` |

---

## 三、已完成（改造阶段全清单）

| 批次 | 内容 | 关键提交 |
|---|---|---|
| **V-1a** | D-10 死枚举 + 4 个死入口 + D-20 删旧弹窗（纯删，零行为变化） | （在 `e949156` 内） |
| **V-1b** | D-6 去重键 120s→30s（2 生成点）/ **D-9 敏感页锁存提前解除**（红线项，水位方案）/ D-16 自动优先（3 触发点）/ D-19 金额容差（2 层：Matcher + Room SQL）/ D-21 0 金额允许弹窗 | `1e9e9dc` `55e3c1b` `893b1f9` `e2b24d7` |
| **V-2** | 改造 C（Y4 金额变化即新会话 + 时间上限）/ **页面金额字段 + 提取器** / P2-8 脱敏吞三位数金额 | `542e078` |
| **V-3** | §8.3 sessionId 传递链路（阻断级前置）/ 改造 B 支付过程槽位（`payment:<sessionId>`，双 TTL 分离）/ 改造 E `handoffContexts` 映射化（容量 4） | `31e4673` |
| **V-4** | 改造 A 触发点后移 / **宽进真生效**（`detect()` 判据按角色分档 + `Buffer` 6 处）/ D-11 删 `SUBMIT_ACTION` 整链路 / 孤儿冲突原因合并 | `e45e97d` |
| **V-5** | 改造 D 落卡时机推移（`pendingFromEvidenceOnly` 3→1 调用点）/ 双兜底（AI 超时 + 启动扫描）/ `ignore` 显式终态 / 派发档位三态 | `3dabf5a` |

**测试演进**：261（V-1a 后）→ 292 → 312 → 344 → 365 → **374**（+113，全程 0 失败）

---

## 四、未完成（4 类，按建议优先级排序）

### 1️⃣ 真机回归 —— **最高优先级**（唯一能验证改造真实有效的手段）

JVM 测不到的 UI 判定点，按批次：

| 批次 | 真机判定点 |
|---|---|
| **V-3** | logcat 出现「支付交接搬运」/「支付过程槽位已搬入」；结算页 TTL 120s→300s（回归 #4） |
| **V-4** | **详情页/购物车页帧进入自动截图链路**（宽进真生效）→ 观察截图频率与轮播下 store 次数；`SUBMIT_ACTION` 相关日志**不再出现**；**≥¥100 订单**（P2-8 验收 #19） |
| **V-5** | AI 无冲突 ⇒ **零弹窗**且 30s 后无残留（P5 修复）；派发失败 ⇒ **立即**出现带金额的卡；孤儿落库 ⇒ **合并双码** `NO_ACTIVE_SESSION,PAYMENT_PROCESS_SNAPSHOT_MISSING`；`ignore` 命中 ⇒ 会话直接关闭 |
| **V-1b** | D-9：logcat **不出现**「断言截图受理」只出现「断言截图拒绝: 敏感页锁存窗口内」；解除时出现「敏感页锁存提前解除: 连续 3 帧明确非敏感购物页」；D-16：不再出现 `MANUAL_CANDIDATE_PROTECTED` |

⚠️ **若真机发现"宽进效果不如预期"，优先查遗留缺陷 ①（`findReferenceForPayment` 第三层拦阻）**。

### 2️⃣ docs 回填（6 处）

- `§7` 三项
- A-3 的 `paymentResult` 形参偏差（与 §8.1 字面的**有意偏差**）
- V-4b 判据分档设计
- 孤儿合并口径
- `NEW_PROJECT_KICKOFF_V2` FR-023
- `SCHEME_INVENTORY §6.7`
- `ISSUE_REGISTER` P5 纠正

⚠️ **另：`CONFLICT_REVIEW` 的「代码评估清单」checklist 全部仍是 `- [ ]`（含已完成项）—— 文档滞后于实施，需补勾。**

### 3️⃣ 遗留缺陷（7 条，均已登记未处置）

| # | 缺陷 | 来源 |
|---|---|---|
| **①** 🔴 | **`Matcher.findReferenceForPayment` 第三层拦阻**（仍要求 `pageRole == CHECKOUT`）⇒ 详情页直达支付时会话角色为 `PRODUCT_DETAIL` ⇒ 服务层证据落库路径判 null ⇒ **宽进打折** | V-4 |
| ② | `findRecentCheckoutReferenceForPayment` 兜底未摘除（与"0 命中不回退猜测"裁定冲突） | V-4 |
| ③ | **D-3/G3.1 通知链路 v1 停用未实现**（`sourceType == "notification"` 短路不存在）⇒ v1 仍可能产生纯通知待确认 | 9-28 对账新发现 |
| ④ | session-linked 待确认的 `categoryId` 恒 null（规则 `set_category` 只对孤儿卡/账单生效） | V-5 |
| ⑤ | `autoCommitMatchedCandidate` 的 `IGNORED` 预检**不可达**（防御性保留） | V-5 |
| ⑥ | `promoteAssertedSessionToPending` 的 `ignore` 分支不给会话终态 | V-5 |
| ⑦ | **D-2 遗留**：规格层轮播控件是否落在 `rotatingWidgetClassNames`(6 类)/`VolatileTokenTracker` 覆盖内 —— **未核实** | 9-28 对账新发现 |

### 4️⃣ `IMPL_PLAN §8.9` 待清理/待裁定（5 项）

`payment_overlay.xml` 孤儿资源（删时注意 `R.id`/drawable 与 `PendingConfirmOverlayManager` 共用）/
`PaymentNotificationModule` 的 `LifecycleEventListener` + 3 个空回调 /
`PaymentNotificationHeadlessService:9` 死 import /
`recordPersonalTransfer` 的 2 分钟时间桶（D-6 同类缺陷）/
0 金额待确认条目 `dedupKey` 退化为 `"|0"`。

**另（本次新记录）**：**R6 —— 0 金额行金额框预填 `"0.00"`**，用户直接追加拿不到预期值（建议 `amountMinor <= 0L` 时 `setText("")`）。

---

## 五、新增问题（本次核实发现）

1. 🔴 **git 凭据失效** —— `git ls-remote origin` 报 `could not read Username for 'https://github.com': terminal prompts disabled`
   - **不是代理问题**（9-18 推送时代理即可用）
   - ⇒ 需重新配置凭据（Windows 凭据管理器 / `credential.helper` / SSH / token）
   - ⇒ **未修复前无法确认远程是否有 `eb81d5f`**

2. **未跟踪目录** `docs/showcase/`（9-22 修改，HTML/CSS/JS 展示站）—— 是否纳入版本控制待定

3. **`jdk17/` + `msjdk17.zip`**（未跟踪）—— 疑似为构建/验证准备的 Java 17 环境，用途待确认

---

## 六、建议的三条推进路径

| 路径 | 内容 | 适合场景 |
|---|---|---|
| **A｜先修凭据（5 分钟）** | 恢复远程访问 → 确认 `eb81d5f` 已推送 → 之后恢复"每完成一步就推"的习惯 | **建议先做**：成本极低，且上次 git 事故的教训是"没有远程副本就没退路" |
| **B｜真机回归** | 按 §4-1 的判定点一次跑完 V-1b/V-3/V-4/V-5 | **价值最高**：它是唯一能证明改造真的有效的手段 |
| **C｜清理收尾** | 遗留缺陷（尤其 ①③）+ §8.9 + docs 回填 + checklist 补勾 | **低风险可并行**：不依赖真机，可快速推进 |

### 建议节奏

1. **先 A**（修凭据 + 确认远程）
2. **再 B**（真机回归，一次跑完；把发现的问题记成清单）
3. **最后 C**（按真机结果决定优先修哪条遗留；docs 回填与 checklist 补勾可随时做）

---

## 七、快速重新上手：读什么

| 顺序 | 文件 | 用途 |
|---|---|---|
| 1 | `CLAUDE.md` | 5 步流程 + 协作约定（**接任务先复述 + 停下等确认**） |
| 2 | 本文件 | 当前状态与待办 |
| 3 | `docs/IMPL_PLAN_VERSION_B_2026-09-13.md` §4 | 批次表（含各批规格指引） |
| 4 | `docs/CONFLICT_REVIEW_SESSION_2026-09-11.md` 的「代码评估清单」 | 待办清单（⚠️ **checklist 未勾选是滞后的，以代码为准**） |
| 5 | `.workbuddy/memory/MEMORY.md` | 长期记忆（含**本机环境坑**与**必须记住的实现决策**） |
| 6 | `.workbuddy/memory/2026-09-17.md` / `2026-09-16.md` | 各批实施细节与核查结论 |

> ⚠️ **记忆文件里的"未提交"字样可能是历史快照**（如 `2026-09-17.md` 末段写 V-3/V-4/V-5 未提交，实际 9-17 22:01~22:02 已提交）⇒ **判断状态一律以 git 为准**。
