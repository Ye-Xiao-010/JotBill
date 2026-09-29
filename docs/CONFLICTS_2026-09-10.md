# JotBill 冲突点清单（2026-09-10 取证版）
> **状态**：[快照]（2026-09-10 时点） —— 勿据此改代码
> **仍活着的条目**：C3（L3 双通道判定分裂）、C5（批 4 三缺口）、C6（`drainQueuedCapture` 死路径）、C7（断言通道未收敛）、D1（worktree 重复提交）
> **已知错误**：抬头称「23 项」实为 **24 项**；称「本次新发现 2 条」实为 **3 条**
> 入口：`00-现状正本.md`

> **取证基线**：`HEAD 40f10e9` @ `fix/manual-capture-priority`（无 upstream，未推送）。本文件所有行号均为 **HEAD 实测值**。
> **取证方式**：三层交叉验证 ——
> ① 文档声明 → 源码/git 实测；
> ② 文档声明 → 文档声明（含 git 考古）；
> ③ 分支 → 分支（`git diff` / `git show`）。
> **证据标注**：`实测` = 本次直接读源码或 git 得出；`引用` = 引自既有文档、本次未再复核（需在使用前先复核）。
> **严重度**：🔴 阻断（阻塞合入/出包） · 🟠 高（影响核心承诺或被误采信） · 🟡 中（需排期清理） · ⚪ 低/受控（已裁定或无害）
>
> **本次实测新发现 2 条**，已在表中标 `【新】`：C3（L3 双通道判定分裂）、D1（worktree 重复提交）。

> ## ⚠️ 后续裁定更新（2026-09-12 ~ 09-13，`docs/CONFLICT_REVIEW_SESSION_2026-09-11.md`）
>
> 本清单取证于 2026-09-10。此后经 6 组讨论式核对，**部分条目的"定性"或"结论"已被用户裁定更新**：
>
> | 条目 | 变化 |
> |---|---|
> | **A6**（"截图上传时机 = 购买意图触发"声明不完整） | 所引 `DECISIONS.md:57`（现 `:58`）**已更新为版本 B** ⇒ 该"声明侧缺口"**已闭合**；且 A6 的观察前提"支付交接可能带规格层截图"**已不再是问题**（规格层可截是设计期望——见 P7 校正说明） |
> | **C1**（Y4 会话跨页污染，🔴 阻断） | **修法已定案**（G6.3 Q3）：**金额变化即新会话 + 时间上限**（非"角色维度"）。⇒ 状态从"待定方案"推进到"可执行" |
> | **B2**（表面矛盾：零误判 vs 全程误判） | 涉及的 **Y3 定性已推翻**：规格层被判可截**是期望行为**，非"误判"（G1-2）⇒ 该"矛盾"的解读需改写为"页面形态不同"，而非缺陷（`ISSUE_REGISTER` P7 已同步） |
> | **A5 锚点漂移对照表** | 本文行号为 `HEAD 40f10e9` 实测；`ISSUE_REGISTER` P11 另记 4 处漂移待重锚（`9da8171..HEAD`） |
> | 通知相关条目（如涉及"通知是可信来源"） | **v1 已停用通知链路**（D-3）：代码保留、摘除证据作用 ⇒ 相关"通知应生效"的判断在 v1 不再是待解决项 |
>
> 逐条详情见 `ISSUE_REGISTER_2026-09-11.md` 顶部的裁定对照表。

---

## 零、总览（23 项）

| 层面 | 🔴 | 🟠 | 🟡 | ⚪ | 小计 |
|---|---|---|---|---|---|
| A 文档 ↔ 代码 | 0 | 3 | 3 | 1 | 7 |
| B 文档 ↔ 文档 | 2 | 1 | 2 | 1 | 6 |
| C 代码 ↔ 代码 | 1 | 4 | 2 | 0 | 7 |
| D 分支 ↔ 分支 | 1 | 1 | 2 | 0 | 4 |
| **合计** | **4** | **9** | **9** | **2** | **23** |

**四条 🔴 阻断项**：`B4`（红线未过却已出包）、`B5`（规范与决策矛盾未修订）、`C1`（Y4 会话跨页污染）、`D1`（worktree 重复提交）。

---

## A. 文档 ↔ 代码（声明与实现不一致）

| # | 冲突点 | 实测证据（HEAD 40f10e9） | 状态 |
|---|---|---|---|
| A1 🟡 | **死枚举 + 同名撞车**：`SnapshotCaptureStatus.EXPIRED` 零产出方 | 枚举定义 `CheckoutSnapshotCaptureCoordinator.kt:29`；全仓唯一引用是消费分支 `overlay/CheckoutCaptureOverlayManager.kt:465` → `EXPIRED_RETRY` 永不可达。**同名的 session 级 `SessionStatus.EXPIRED` 是活的**（`CheckoutContract.kt:155` 定义、`:210-265` 有迁移产出） | 未闭合。`DECISIONS.md:59` 已自我标注，属"已知未清理"：死枚举 + 死 UI 状态（`Overlay:123`）仍在代码里，且与活跃的 session 级同名 |
| A2 🟡 | **两个"120s"撞名** | `DECISIONS.md:59` 的 120s = 快照 TTL，实测 `CheckoutSnapshotBuffer.kt:13`（`DEFAULT_SNAPSHOT_TTL_MILLIS = 120_000L`）✅ 一致；但 `qa/checkout-manual-capture-priority-fix.md:25`（C3 条目）的"锁窗 120s"是**按钮锁窗**，实为"指纹不变期间（秒级~十秒级）"，非快照 TTL | 未闭合（术语需分名，否则后人按 120s 设计会错） |
| A3 🟡 | **决策日志落后于 QA 记录** | `qa:187` 明写"经验教训（**入 DECISIONS 候选**）"：`"无提交订单按钮且能改数量 = 非结算页"`；实测 `DECISIONS.md` 三/五节 **无此条** | 未闭合（DECISIONS 未收录） |
| A4 🟠 | **行号锚点策略自相矛盾** | `qa:5` 声明"本文件所有行号以 `9da8171` 为准"；`qa:264` 又要求"第 5 节行号**全部需以 `9da8171` 重核**"——同一文档两套口径。实测 `9da8171..HEAD` 识别链路 4 文件 **+307 / −20** | 未闭合。正解应是统一重锚到 HEAD，而非继续引用 9da8171 |
| A5 🟠 | **锚点漂移实测 4 处**（详见下表） | — | 未闭合（第六节 rebase 核对的首要工作） |
| A6 🟠 | **"截图上传时机 = 购买意图触发"声明不完整** | `DECISIONS.md:57` 声明购买意图触发；实测支付交接携带的候选**可能是规格层截图**（`qa:231-260` 铁证链）→ 声明与实测行为不一致 | 归并入 `C1`（Y4），此处仅登记声明侧缺口 → ⚠️ **2026-09-12 已闭合**：① `DECISIONS:58` 已改为"**支付成功信号触发（版本 B）**"；② "支付交接携带规格层截图"**不再是问题**（规格层可截是设计期望，G1-2） |
| A7 ⚪ | **已核对一致项**（防误伤，列出供复用） | `DECISIONS:53` 悬浮按钮显示 → 实测有模式开关；`:55` 手动抢占不排队 → `Coordinator:1159`；`:56` 同指纹不重复 → `Buffer:310-315`；`:59` 120s TTL → `Buffer:13`；`:59` EXPIRED_RETRY 死状态 → 实测成立（见 A1） | 已闭合 ✅ 四条声明均与代码一致 ⚠️ **2026-09-12 两点更新**：① `DECISIONS:54/:55`（手动优先/受保护）**已按 D-16 改为"自动优先"**；② `:59` 的 **TTL 已由 120s 改为 90s** 且**起算点改为"搬入支付过程槽位时刻"**（G6.3 Q2a/Q2b）→ ⚠️ **0.3.3（2026-09-13，D-23）再次修订**：固定 90s 亦弃用，最终 = 事件驱动 + 两槽位分离（支付过程 **2 分钟兜底** / 结算页 5 分钟会话上限），见 `DECISIONS:59` |
| A8 🟠【新】 | **证据先到即弹窗 —— 与原始设计"不弹窗"不符** | 设计原文（`NEW_PROJECT_KICKOFF_V2.md:1357-1364`）："支付证据先到、AI 仍在 ARMED/IN_FLIGHT → 写入 PAYMENT_CONFIRMED，**不弹窗** → 进入 WAITING_ENRICHMENT，**等待 5~15 秒** → AI 返回且字段完整：自动入账 → AI 超时/字段缺失/金额冲突：发送 onReviewRequired"。实测（`Matcher:744-797`）：证据先到 ⇒ 同一事务内 `pendingFromEvidenceOnly()` + `upsertPendingForSession()` ⇒ `emit(isNew = true)` ⇒ **弹窗立即触发，无任何等待窗口** | **未闭合**。精确诊断：设计要区分"待确认记录立即落库（落袋）"与"弹窗等 AI 定论"，实现用**同一个 `emit(isNew=true)` 事件**驱动了两者，缺"AI 定论前只落袋不打扰"的中间态标记。代码注释（`:748-750`）只主张"让待确认中心立刻显示"，未主张"立刻弹窗"⇒ 属实现连带越过设计。**不矛盾**于 `qa:175` 拍板（那条解决触发权归属、排除断言通道，不涉时序）。影响：最坏（AI 走满 32s 超时）弹窗比设计早约半分钟，且内容不完整（`productDescription`/`categoryId` 空）；若 AI 判定无冲突则自动入账并清理该待确认 ⇒ 用户可能"弹窗未及操作即被核销"。另：已显示的弹窗在待确认被清理时**不会自动撤下**（`PendingConfirmOverlayManager` 对 `info.removed` 无分支，且 `:160-163` 提前 return），陈旧弹窗留至用户操作或 60s 自动消失 |

### A5 明细：锚点漂移对照表

| 引用位置 | 文档行号 | HEAD 实测行号 | 判定 |
|---|---|---|---|
| `Overlay` 的 `canManualCapture()` | `qa:45` 写 `:752-760` | **`:791`**（定义）；可点集合含 `CAPTURED_WAITING_PAYMENT` 在 **`:812`** | ❌ 漂移 39 行（9da8171 时确为 `:741`/`:759-760`，锚点当时成立） |
| `Collector` 的 `isProductSpecSelectionLayer` | `qa:200` 表 A 写 `:276` | 函数定义 **`:272`**，`:276` 是函数体内首个 `if` | ⚠️ 偏 4 行（指向函数内，尚可用） |
| `Service` 的 `performUserAssertedCapture` | `qa:266` 写 `:2534` | **`:2543`** | ❌ 漂移 9 行 |
| `Coordinator` 的 store 请求构造点 | `qa:267` 写 `:1968-1971` | 该区间是**注释块**；赋值句 `captureTarget = if (...)` 在 **`:1973`** | ⚠️ 指向注释而非代码（自始不精确） |
| `Coordinator` 抢占段 | `qa:267` 写 `:1157-1188` | ✅ 命中（`:1159` 为 `inFlight` 判定） | ✅ 未漂移 |
| `Buffer` consumeForAi 手动优先 | `qa:267` 写 `:687-701` | ✅ 命中（`:689-692` 为手动优先分支） | ✅ 未漂移（⚠️ **2026-09-12**：该"手动优先"**口径已改为"自动优先"**（D-16），但**锚点本身仍准确**） |
| `Matcher` promoteAssertedSessionToPending | `qa:266` 写 `:1328` | ✅ 精确命中 `:1328` | ✅ 未漂移 |
| `Service` 断言槽位 `user-asserted:` | `qa:266` 写 `:87-93` | ✅ `USER_ASSERTED_SLOT_PREFIX = "user-asserted:"` 在 `:93` | ✅ 未漂移 |
| `Service` assertedSessionIds | `qa:266` 写 `:361` | ✅ 精确命中 `:361` | ✅ 未漂移 |

> 结论：漂移面集中在 **`Overlay` / `AccessibilityPageSummaryCollector` / `CheckoutAccessibilityService`** 三个文件（正是 `9da8171..HEAD` 被改的 4 个文件之三）；`Coordinator` / `Buffer` 未漂移，可继续复用原锚点。

---

## B. 文档 ↔ 文档（结论被推翻 / 自相矛盾）

| # | 冲突点 | 双方原文 | 裁定 |
|---|---|---|---|
| B1 🟠 | **根因裁定未回填** | `qa:294-305`（§7.4）正文结论："第一嫌疑是安全软件实时扫描与 git ref 原子写竞态——**360 与 Defender 双实时防护同时开启**"；`qa:331-334`（§7.7）"**重要发现——根因裁定修正**：Defender 排除列表原本就有 `D:\` 整盘排除 → 7.4 的'双实时防护竞态'第一嫌疑**不成立**，**360 升为第一嫌疑**" | §7.4 正文**未回填修正**，只在前向的 §7.7 推翻。读者按顺序读会采信已失效结论。应回填 §7.4 或加显著跳转标记 |
| B2 🟡 | **表面矛盾：零误判 vs 全程误判** | `qa:183`（5.9）"首轮真机验证商品页判定完美（**17x PRODUCT_DETAIL，零 CHECKOUT 误判**）"；`qa:224`（5.10.1）"规格层全程判 `role=CHECKOUT, actionSignal=true`（**Y3 残余实锤**）" | 不冲突但**未点明**：两者是不同页面形态（商品详情页 vs 商品规格选择弹层）。现状描述易被读成矛盾，需在 5.10.1 补一句区分 → ⚠️ **2026-09-12 补充**：不止"需点明"，且用词需改——**规格层被判可截是设计期望**（G1-2），故 `qa:224` 的"**Y3 残余实锤**"这一**缺陷定性不成立**，应读作"规格层被识别为可截页面（符合设计）" |
| B3 🟡 | **X2 定性修正并存未划线** | `qa:121`"X2 **重大修正：间歇性，非系统性**"，从"疑似无超时挂起"下调为"单次丢失、机制未知" | 原定性仍在 §5.7 表内（未加删除线/未标注被修正），与 C4 条目的处理方式不一致 |
| B4 🔴 | **红线未过却已出包**（流程冲突） | 红线两处声明：`qa:65`"**G3-2 真机用例必须通过后才能合入**"；`qa:73`"**出包红线：G3-2 抢占用例不过则本分支不得合入**"。但 5.10.2 实测 D 项为"**⚠️ 场景未形成**"（0 × `PREEMPTED_BY_MANUAL_CAPTURE`），而 5.10 出包记录显示 **15:05 已构建 versionCode 93 / 0.0.97 并覆盖安装成功** | **红线事实未闭合，出包动作已先行**。需明确：是"仅出调试包用于验证"还是"红线降级"。当前表述下二者无法区分 |
| B5 🔴 | **规范与决策矛盾且未修订** | `ROADMAP_FALLBACK_FIRST.md:250-252` 明确："决策记录落 DECISIONS 第五节；**kickoff 文档 :159/:951 的'排除收款/红包'条款需同步修订，避免规范自相矛盾**"。实测 `NEW_PROJECT_KICKOFF_V2.md:159`、`:951`、`:1952`、`:2041` **仍写"排除收款/到账/转入/红包"** | 路线图已识别但**至今未执行修订**。批 3 要做红包 ⇒ 规范文本仍与决策对立 |
| B6 ⚪ | **刻意保留的考古痕迹 + 已闭合项** | `qa:26` C4 条目保留删除线，`qa:33` 说明"C4 的'墓碑拒绝'描述**自始错误**：target 门禁在墓碑之前" | C4 为刻意考古，无害。**实测验证该修正正确**：`Buffer` 中 `targetAllowed`（`:264-275`）确实先于墓碑（`:287-293`）与同指纹处理（`:302`）✅ |

---

## C. 代码 ↔ 代码（机制内部冲突 / 死路径）

| # | 冲突点 | 实测证据（HEAD 40f10e9） | 影响 |
|---|---|---|---|
| C1 🔴 | **Y4 会话跨页存续污染**：槽位锚定后指纹容差无限——换页与轮播一律按"同会话漂移"处理；页面角色切换不重置会话；`NEW_CHECKOUT_SESSION` 仅在未锚定时触发 | **锚点已于 2026-09-10 17:0x 代码复核实锤**（`qa:249` 原自述"待代码复核"现已闭合）：`Coordinator:1424-1431` 会话重置判据 = `previous == null \|\| 换包 \|\| (指纹不同 && !driftWithinSameCheckout)`，**不含"页面角色变化"维度**；`:1333-1350` `hasRetainableCandidate` 只查"同包 + target==CHECKOUT + 槽内有本会话任意候选"，**不比对指纹、无时间上限、无相似度下限** ⇒ 同包内指纹怎么变都沿用原会话（`:1503-1507` 直接发 DUPLICATE、不重截）。日志侧证据：`qa:231-260` 铁证链（会话 `ab3a9a3e` 跨规格层→主页→商品页→真结算页 4 次切换不重置）+ `qa:224`（50s 指纹 4 态循环，同指纹回归 `STORED` 未被标记易变） | ~~**用户不手动点击时，支付交接的 AI 参考可能是规格层截图**~~ ⚠️ **已不成立**（规格层可截是设计期望，G1-2）。同时**整体吞掉 5.7 设计的护栏②**（"换商品指纹必变 → 必重截"）——属护栏失效而非新需求。**注**：指纹公式含 `pageRole`（`Collector:346`），故角色变化其实会改变指纹，但仍被 `hasRetainableCandidate` 吞掉——~~修复应加**角色维度**而非仅指纹判据~~ → ⚠️ **2026-09-12 修法更新（G6.3 Q3）：改为"金额变化即新会话 + 时间上限"** |
| C2 🟠 | **Y3 残余**：规格选择弹层被判 `CHECKOUT` 并触发自动截图 | `Collector:272-283` `isProductSpecSelectionLayer()` 在 `hasCheckoutActionSignal == true` 时**早退 `false`**（`:276-278`）⇒ 规格层排除整体失效；而 `hasCheckoutActionSignal` 由**含隐藏节点**（`includeHiddenText` 默认 `true`，`:297`）且**不受易变剔除影响**（`:311-317`）的信号文本判定 ⇒ 弹层任何隐藏节点带动作词即误判 | 40f10e9 修好了"真结算页被误杀"，但~~**残余面（规格层被误判）仍在**；后果有界：1 截 1 会话，无风暴（`qa:224`）~~ → ⚠️ **2026-09-12 定性已推翻（G1-2 用户裁定）**：**规格层"本来就属于该商品页面，需要截图"**，规格切换导致价格/商品变化**必须重截** ⇒ **"规格层被自动截图"是期望行为，不是残余缺陷**。待处理项改为 **"重新评估 Y3（`isProductSpecSelectionLayer`）的存废"**（其前置排除与设计对立） |
| C3 🟠【新】 | **L3 指纹/信号双通道判定分裂**：同一个无标签金额 token，在**信号通道被认可**、在**指纹通道被当易变剔除** | 信号侧：`hasFinalAmountSignal()` 有 **PDD 无标签兜底**（`Collector:500-508`："拼多多确认订单页可能只出现'提交订单 + ¥55.70'，没有'实付/合计'等标签"）；指纹侧：易变护栏**只用标签词表**（`Collector:648-650` `hitsFinalAmount = finalAmountTerms.any { value.contains(it) }`，`:113-122` 全为 `应付/实付/订单金额/待支付…` 标签词） | 双通道分流（568bfc8 引入）后**两个通道对同一 token 判定不一致** ⇒ 无标签金额既参与页判又被剔出指纹，护栏①（换商品必重截）在 PDD 结算页形态下不可靠。**本次新发现，文档未记载** |
| C4 🟠 | **L3 四个结构性弱点**（实测确认 3 条、引用 1 条） | ① `Collector:408-414` 位置键 = 末段类名 + 可选 `@viewId`，**无 viewId 时退化为裸类名** ✅ 实测；② `Collector:415-420` `text` 与 `contentDescription` **共用同一位置键** ⇒ 两者不等时第二次观察即双双出局 ✅ 实测；③ scopeKey = 包名+窗口类名（`Collector:303`），规格层/结算层共用 `wd2.f0` ⇒ 轮播污染跨层传播 ≤ **15s**（`VolatileTokenTracker:99` `DEFAULT_WINDOW_MILLIS = 15_000L`）✅ 实测；④ 容量守卫静默降级：`VolatileTokenTracker:66-70` 在 `maxTrackedPositions`（`:102` = **400**）满时**静默 `return false` 不追踪**；`Collector:318-325` 文本截断只打 `Log.w` ✅ 实测 | 门禁判定可在**无异常、无告警**的情况下退化（仅 logcat 可见）。归为**体感闪烁加固项**，非阻塞 |
| C5 🟠 | **批 4 三个缺口全部实测仍在** | ① `advanceStatus` 非法迁移**静默 `return session`**（`CheckoutPaymentMatcher.kt:1450-1452`，无日志无异常）；② `LedgerRepository` **绕过状态机直写终态**（`:454-455` `status = "COMPLETED"` + `journeyState = "COMPLETED"`；`:557` `journeyState = "DISMISSED"`）；③ `CheckoutJourney` 包装类（`CheckoutContract.kt:329-364`）**全仓零实例化**（仅定义处 `:329`/`:349`/`:357`） | `ROADMAP:254-258` 与 `tdd/08_phase4_journey_spec.md:164-165` 均已要求修，**至今未动**。状态机不一致被静默掩盖，是 bug 信号 |
| C6 🟡 | **死路径并存**：排队机制已移除但调用点仍在 | `queuedManual`（`Coordinator:412`）自 `f8365e8` 起**无任何赋值点**（仅 `:632`/`:699`/`:1438`/`:1628` 四处 `= null`）；但 `drainQueuedCapture()`（定义 `:2070`）仍有 **5 处调用**：`:1725`、`:1827`、`:1932`、`:1953`、`:2010` | 恒为空转。文档（`qa:24` C2）称"排队机制整体移除"，与代码"保留但不赋值"表述有落差 |
| C7 🟡 | **断言通道未纳入收敛 + 墓碑改造耦合风险** | 断言通道实测存在：专用槽位 `user-asserted:`（`Service:93`）、`performUserAssertedCapture`（`:2543`）、`promoteAssertedSessionToPending`（`Matcher:1328`）、静默化门禁（`PendingPromptPolicy:43`/`:72-79`、`PendingReminderNotifier:87`、`PendingChangeHub:49`）。但 `qa:266` 要求"断言通道纳入 5.3 `HandoffStateHolder` 收敛范围"，**未做**；且 `Buffer:317-320` 的 `USER_RETRY` 落盘清墓碑语义，与批 4 改造 `:317-320` 的计划**直接耦合** | 双槽位并存时"无指纹消费"歧义面扩大；批 4 动墓碑会**反向影响**手动重截落盘通道 |

---

## D. 分支 ↔ 分支

| # | 冲突点 | 实测证据 | 影响 |
|---|---|---|---|
| D1 🔴【新】 | **worktree 分支陈旧 + 重复提交** | 工作树 `D:/JotBill/.worktrees/phase4-t5-t6-fixes` @ `469fb7b`（`codex/phase4-t5-t6-fixes`）的**唯一独有提交**，与 mainline `ada1402` **同消息、同 stat（4 文件 / +314 / −54）** ⇒ 同一改动的两个 SHA（cherry-pick/amend 残留）。且 `git diff ada1402 469fb7b` 显示该分支在 **14 个文件**上**落后于 HEAD**（`CheckoutPaymentMatcher.kt`、`CheckoutSnapshotBuffer.kt`、`PaymentResultParser.kt`、`src/lib/queryClient.ts`、`src/native/recognition.ts`、`src/screens/review/ReviewScreen.tsx`、`NotificationPermissionScreen.tsx` 等） | 若从该 worktree 继续开发并合并：会**重放已合入改动** + **回退 HEAD 的后续修复**。建议判定该 worktree 是否已废弃 |
| D2 🟡 | **分支基数庞大** | 62 本地分支 / 57 远端分支；当前分支**无 upstream（未推送）**；`codex/phase4-*` 12 支、`codex/phase5-*` 7 支 | 合入/清理策略缺位，易重演 D1 |
| D3 🟠 | **rebase 冲撞面（第六节 4 触点 + 断言通道）** | 锚点校验见 `A5`：`Coordinator:1157-1188` ✅ / `Buffer:687-701` ✅ / `Overlay` ❌ 已漂移（`:752-760` → `:791`,`:812`）/ `Coordinator` store 构造点 ⚠️（`:1968-1971` → 赋值在 `:1973`） | 批 4 rebase 开工前必须先重锚，否则改错位置 |
| D4 🟡 | **批 0 特征测试与修复目标对冲**（标注：本次**未逐类打开**，需复核） | `app/src/test/.../recognition/` 下 20 个测试类，其中 `PageFingerprintStabilityTest`、`CheckoutSnapshotBufferTest`（含 `storeRejectsTargetReasonRoleCombinations`）、`TokenBudgetAllocationTest`、`VolatileTokenTrackerTest`、`HandoffFenceFingerprintTest`、`UserRetrySlotTakeoverTest` 与 Y4/Y3/L3 修复目标**直接对冲** | 修复 C1/C2/C3/C4 时需先逐类判定"哪些是新行为、哪些必须保留"，否则会出现"改了代码就红一片"或"为了绿测而弱化修复" |

---

## 一、处置优先级建议

| 序 | 处置 | 依据 | 解锁 |
|---|---|---|---|
| 1 | 修正 **B4 表述**：明确 0.0.97 是"验证包"还是红线降级 | 纯文档，3 分钟 | 消除"红线形同虚设"的流程缺口 |
| 2 | 处理 **D1**：判定 `phase4-t5-t6-fixes` worktree 是否废弃（删除或 rebase 到 HEAD） | 纯 git 操作，无代码风险 | 消除"回退后续修复"的隐患 |
| 3 | **C1（Y4）代码锚点复核 ✅ 已完成** → 下一步为定方案 → 修复 + 特征测试 | 见上一轮结论：Y4 是 E/D 两个场景的共同祸根 | E / D / C 场景验证强度 |
| 4 | 统一重锚（**A4/A5/D3**）：把第六节所有锚点重核到 HEAD | 机械但必须，rebase 前的前置 | 批 4 开工 |
| 5 | **B5** 修订 `NEW_PROJECT_KICKOFF_V2.md:159/:951`（收款/红包条款） | 一行文本 | 消除规范自相矛盾 |
| 6 | 回填 **B1**（§7.4 根因）+ **B3**（X2 划线） | 纯文档 | 防止后人采信失效结论 |
| 7 | **C3**（双通道分裂）优先于 **C4**：先补 `VolatileTokenTracker` 护栏的 PDD 无标签金额识别 | 本次新发现，直接削弱护栏① | L3 加固 |
| 8 | **C5/C6/A1** 死路径与死枚举清理 | 排期项 | 代码卫生 |
| 9 | **C7** 断言通道纳入 5.3 收敛（与批 4 墓碑改造合并设计） | 设计耦合 | 批 4 |

---

## 二、附录：本次已闭合项（避免重复劳动）

| 项 | 原冲突 | 闭合方式 | 实测复核 |
|---|---|---|---|
| a | `MIGRATION_MAP:275` 净资产口径前后端算法不一 | 已在 `MIGRATION_MAP` 内统一为流派 B | 引用 |
| b | `CODE_REVIEW:152` 首页 `toISOString()` UTC+8 口径矛盾 | 已由 `876223e` 修复 | `git log` 实测 |
| c | `CODE_REVIEW:155` 全账单页月合计把 transfer/repayment 计为支出 | 已由 `682efcd` 修复 | `git log` 实测 |
| d | C4"墓碑拒绝"描述自始错误 | `qa:33` 已修正；**实测 `Buffer:264-275` 确认 target 门禁先于墓碑 `:287-293`** | ✅ 实测确认 |
| e | `DECISIONS:59` "过期 30 秒补截"承诺从未实现 | `DECISIONS` 已自我标注"勿据此设计" | 引用 |

---

**取证清单（可复现命令）**

```bash
cd /d/JotBill
git rev-parse --abbrev-ref HEAD                  # fix/manual-capture-priority
git diff 9da8171 HEAD --stat -- .../recognition/ .../overlay/   # 锚点漂移面
git show ada1402 --stat ; git show 469fb7b --stat               # D1 重复提交
git diff ada1402 469fb7b --stat                                 # D1 落后面
grep -rn "EXPIRED" --include=*.kt .../com/jotbill/app/          # A1 死枚举
grep -rn "queuedManual\|drainQueuedCapture" --include=*.kt .../ # C6 死路径
sed -n '264,320p' .../CheckoutSnapshotBuffer.kt                 # 附录 d 顺序验证
sed -n '272,283p' .../AccessibilityPageSummaryCollector.kt      # C2 Y3 残余
sed -n '500,508p;642,652p' .../AccessibilityPageSummaryCollector.kt  # C3 双通道分裂
```
