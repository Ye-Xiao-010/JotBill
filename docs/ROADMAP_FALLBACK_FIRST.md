# 落袋优先路线图(Fallback-First Roadmap)

> 起草日期:2026-08-28
> 背景:当前结算识别链路"能用但不好用"。本路线图的核心承诺:**任何一批做完,应用都不会比之前更不可用**。
> 编号沿用外部讨论:#1 指纹=身份改旅程模型;#2 无结算会话支付兜底;#3 红包/转账;#4 站内支付结果页扩面;#5 小众 App 实验模式;#6 子分类;#7 类型不确定→send_to_review;#9 导入验收+待确认核销。#8 未提供定义,预留批 0 插队位。

---

## 一、总原则:怎么保证业务流程不崩塌

1. **冻结核心**:批 1-3 对三个核心文件**零改动**——`CheckoutSnapshotCaptureCoordinator.kt`、`CheckoutSnapshotBuffer.kt`(批 0 的机械搬移除外)、`AccessibilityPageSummaryCollector.kt`。前三批全是围绕核心的增量(pending 路径、导入路径、规则策略)。核心没动,现有主链路就不会崩。
2. **特征测试先行**:批 0 先用测试把**现有行为**钉死(不是测理想行为,是测现在实际的行为),后续任何改动以"特征测试全绿"为底线。
3. **不变式清单**作为每批验收线(见第五节)。
4. **新行为全部带开关**:开关关闭 = 行为与改前逐字节等同;出问题关开关即回滚,不用卸载。
5. **每批出包跑黄金流程真机脚本**(批 0.4 成文),含负向用例。
6. **唯一动核心的批 4**:先规格、分支开发、真机浸泡一周、单 PR 合入、revert 即回滚。不做双路径并存(维护成本陷阱)。

---

## 二、批 0:安全网(新增,预计 3-5 个工作日,可与批 1 并行)

**目标:把"现在能用"变成"可验证的能用"。这是后面所有批次的安全带,也是批 4 的硬前置。**

### 0.1 CheckoutSnapshotBuffer 可测化 + 特征测试
- 现状:846 行,栅栏/TTL/tombstone 是回归重灾区,零直接 JVM 测试。
- 改动:把决策逻辑(slots 状态机)抽成纯 Kotlin 引擎,时间以 `now: Long` 参数传入;Android 侧保留 `@Synchronized` 包装与 main-looper Handler 过期调度,委托给引擎。**纯机械搬移,不改任何判定**。
- 新增 `CheckoutSnapshotBufferTest`:store→consume 主链路、栅栏指纹拦截、`retainForPaymentHandoff`/`restoreFromPaymentHandoff` 往返、TTL 过期、`MANUAL_CANDIDATE_PROTECTED`、`JOURNEY_ALREADY_TRIGGERED`、`purgeExpired`、敏感页拒绝。
- 风险控制:特征测试先对旧实现写绿,再搬移,搬移后必须仍全绿。

### 0.2 routeLocked 门禁序列测试
- `CheckoutPurchaseIntentCoordinatorTest`:按序枚举每个拒绝原因(COORDINATOR_CLOSED → NO_CHECKOUT_CONTEXT → NOT_CHECKOUT_PAGE → SENSITIVE_PAGE → INVALID_PAYMENT_NOTIFICATION → 已尝试 → 预算耗尽 → buffer 各拒绝),验证"被拒不扣预算、不烧 one-shot"。

### 0.3 支付匹配器特征测试
- `CheckoutPaymentMatcherTest` + 内存假仓储:`validatePayment` 时间窗、`evaluate` 各分支(金额覆盖/订单号/时间)、`resolveSessionForPayment` 歧义与孤儿路径、`AiUnverifiedPaymentGuard` 永不自动落账。**这组测试直接保护批 1-3。**

### 0.4 黄金流程真机脚本成文
- 落盘 `docs/qa/golden-path-regression.md`,每项标注期望日志行,证据落 `docs/qa/device/`:
  - 正向:拼多多+微信支付全链路(结算→截图→交接→通知→落账);京东弹窗结算页;手动补截。
  - **负向(不崩塌的关键)**:密码输入页全程无截图/无 store 日志;非结算页不建会话;通知回退在敏感页被拒(`CheckoutAccessibilityService.kt:2159-2175`);AI 未验证证据只进待确认。
- 此后每批出包必跑,不再依赖临时复测。

### 0.5 敏感页防线清单成文
- 把现有 7 层拦截(检测器、提交动作、手动截图、buffer.store、重试上下文、routeLocked、通知回退入口)写成 checklist 并入 `docs/TESTING.md`,作为每批 PR 检查项。

---

## 三、批 1:兜底闭环补全(#2+#7,纯增量,预计 3-5 天)

**现状核查(2026-08-28,对照源码,修正初稿的"纯黑洞"说法):**
- **支付结果页可读的场景已覆盖**:文本证据 + 截图 AI 双保险,`recordPaymentResultEvidence`
  直达 matcher,不要求结算会话,全分支终局为"落账或孤儿待确认"(扫商户码即属此类,
  phase7 真机已验证)。
- **有结算会话时通知证据也已覆盖**:即使 buffer 引用失效,仍有"最近活跃会话兜底"与
  "支付通知无结算会话可匹配,已转纯通知待确认"两级兜底(`:2228` 起)。
- **真黑洞只有一个**:无结算会话 + 无可读结果页 + 只有支付通知(典型:付款码被扫,
  用户手机上不会出现结果页)。通知回退入口在 `:2175` 因 `NO_CHECKOUT_CONTEXT`
  直接拒绝,证据丢弃。
- 其余缺口属于后续批次:转账/红包关键词(#3)、购物 App 内置收银台结果页(#4)、
  小众 App(#5)。

### 方案
- **数据面(补黑洞,必做)**:通知回退入口允许无会话时走孤儿路径落 pending;前置条件
  保持 `signal.isValid()` + 支付包白名单不变,孤儿永为待确认、永不自动落账。
- **提醒面 v1(必做)**:pending 创建 → `bridge/NativeEventEmitter.kt`(通道已存在)推 JS →
  新增系统通知渠道"待确认提醒",点击深链进 `ReviewScreen`(复用已有 PendingEditPanel)。
  不做跨应用弹窗,风险最低。
- **提醒面 v2(可选,flag 控制)**:原生轻量浮条"检测到 ¥X 支付,点击记录",复用
  `overlay/CheckoutCaptureOverlayManager` 的窗口模式;仅当敏感页 60 秒锁存未激活时显示。
- **引导策略独立成纯 Kotlin `PendingPromptPolicy`**(可单测):去重键(金额+商户+时间窗)、
  频控上限(次/小时)、金额下限、勿扰时段;**必须继承 `lastSensitivePaymentPageAt` 锁存与
  `isSensitivePage` 门禁**——新 UI 表面绝不允许出现在密码页上方。
- **#7 类型策略**:`send_to_review` 动作已存在(`RuleEngine.kt:130`),仅补:PendingEditPanel
  增加类型选择器 + 智能默认;"类型不确定→send_to_review"作为设置项,默认开启。

### 改动范围
- 新文件:`PendingPromptPolicy.kt`、pending 提醒通知器、(v2)轻浮条。
- 触及:`CheckoutAccessibilityService` 通知回退入口(仅放宽无会话前置,不改信号校验)、
  `CheckoutPaymentMatcher.recordOrphanPaymentSignal`(只加事件钩子,不改判定)、
  `bridge/DataModule.kt`(事件注册)、`AndroidManifest.xml`+`notification/`(渠道)、
  JS `ReviewScreen.tsx`/PendingEditPanel。
- **明确不碰**:CaptureCoordinator、Buffer、Collector、Detector、指纹。

### 回归保障
- 总开关默认关闭首发,验证 off 态行为等同现状;开启后跑黄金脚本 + 真机用例:
  **付款码被扫(仅通知)→ pending**(补黑洞);**扫商户码 → pending**(回归确认
  已有行为不回退);确认 → 落账全链路。
- 策略单测:去重、频控、敏感锁存、金额下限、无会话通知孤儿入口。

---

## 四、批 2:导入验收 + 待确认核销(#9,低风险,约 1 周 + 真机半天)

- **前半(无代码)**:补齐 phase6/phase7 T9 矩阵(现全部"未执行/阻塞")。需要先采集 T8 官方账单样本(微信/支付宝导出)——**用户动作,请提前准备**。
- **后半(小功能,非"一小块")**:新增 `importing/ReconcileService`:导入官方账单时按「金额精确相等 + 方向一致 + 日期 ±1 天(可配)」匹配待确认项。
  - **v1 先报告后执行**:展示"可核销 N 条"清单,用户批量确认后才核销;自动化模式放 flag,后置。
  - 走既有"整批撤销"基础设施,可回退。
- 改动范围:importing/ 新文件、ledger 仓储查询、JS 对账页;**不碰 recognition/**。
- 保障:匹配规则单测;核销可撤销。

---

## 五、批 3:红包/转账(#3,先方案后编码,零代码起步)

- 交付物:`docs/DECISIONS.md` 增补产品决策,用户拍板后才编码:
  1. 收红包记不记?发红包算社交支出还是转账?
  2. 降噪:金额下限/周期聚合阈值,做成设置项而非硬编码。
  3. **隐私**:红包对方昵称/备注进敏感词脱敏清单。
- 技术挂点:批 1 的 pending 闭环 + `TransferClassifier`/`RefundLinker` 扩展;类型选择复用 #7 的选择器。

---

## 六、批 4:journey 模型重构(#1,唯一动核心,先规格后编码)

### 硬前置(缺一不动工)
- 批 0 全部特征测试绿;黄金脚本最近一次全过。
- 规格文档先行,必须覆盖难题清单:journey 出生/死亡(超时?跨包漂移?)、**并发两笔**(前一单等支付通知、后一单已进结算页)、拆分支付、失败重试、journey 与 buffer 栅栏的新键语义。
- journey 身份锚点 = 购物包 + 窗口类名 + 金额(订单号可见时优先);指纹降级为"页面变化→可能需要重截"的信号,不再是订单边界。

### 改动范围
- `CheckoutSnapshotCaptureCoordinator.kt`:`handleStable`/`handleInvalidated`(~1337-1667 区域)重写为 JourneyState 状态机。
- `CheckoutSnapshotBuffer.kt`:栅栏键从会话指纹改为 journey(对外语义保持)。
- `CheckoutAccessibilityService.kt`(2968 行):抽出 `HandoffStateHolder`,收敛 service/coordinator/buffer 三套真相源。
- **支付结果兜底通道并入主门禁策略对象**(`encodePaymentScreenshot`/`recognizePaymentResult` 平行实现消除),共用敏感页与证据门禁。
- **明确不做**:不改敏感页 7 层防线、不改 AI 证据降级规则(`PAYMENT_STATUS_AI_UNVERIFIED` 永不自动落账)、不改每会话预算制。

### 不崩塌保障
- 分支开发 → 特征测试全绿(**有意行为变化逐条列出**)→ 设备矩阵全过 → 真机日常浸泡 ≥1 周 → 单 PR 合入;回滚 = revert 或重装旧 APK。
- 合入后一周内每次真实支付后核对黄金脚本正向用例。

---

## 七、批 5:支付场景扩面(#4→#5,依赖批 1 闭环)

- **#4 站内支付结果页**:放开 `PAYMENT_RESULT` 的包名限制 + 通用解析器;实验 flag,只产待确认候选。
- **#5 小众 App 实验模式**:用户自开、白名单自选、只产 pending 候选(kickoff 已预留设计)。
- 顺序:#4 先于 #5;两者都默认关闭,逐步放量。

---

## 八、随时插队:#6 子分类(单独短迭代)

- schema v11→v12(`JotBillDatabase.kt:44`,现有迁移 1→11 共 10 个):bill 表加 `parentId` + 统计查询适配。
- 迁移事务化 + 前置备份提醒;补 Room 迁移单测。
- **互斥规则**:任何批次若需 schema 变更,必须与本批重排,避免迁移链冲突。

---

## 九、不变式清单(每批验收线,违反即打回)

1. 敏感页(密码/验证码/付款码)永不截图、永不入 buffer、永不送 AI——7 层防线任何一层不得削弱。
2. AI 未验证证据(`SUCCESS_AI_UNVERIFIED`/`PAYMENT_STATUS_AI_UNVERIFIED`)永不自动落账,只进待确认。
3. 快照仅内存暂存,释放必抹零,任何路径不得写盘。
4. 每会话 AI 请求预算上限不变,被拒不扣预算。
5. 用户手动操作优先级最高,规则命中不阻断用户确认。
6. 黄金流程脚本正向用例全过、负向用例全静默。

---

## 十、里程碑总览

| 批次 | 内容 | 大小 | 出包验收线 |
|---|---|---|---|
| 0 | 安全网(buffer/门禁/matcher 测试 + 黄金脚本) | 3-5 天 | 特征测试全绿,脚本成文 |
| 1 | 兜底闭环补全 #2+#7 | 3-5 天 | 付款码被扫(仅通知)→pending;扫商户码回归不回退;off 态等同现状 |
| 2 | 导入验收 + 核销 #9 | 1 周 | T9 矩阵填实;核销可撤销 |
| 3 | 红包/转账方案 #3 | 方案 2-3 天 | DECISIONS 拍板后才编码 |
| 4 | journey 重构 #1 | 2-3 周(含浸泡) | 特征测试全绿+矩阵全过+浸泡 1 周 |
| 5 | 扩面 #4/#5 | 各 1 周 | 实验态只产待确认 |
| 插队 | 子分类 #6 | 2-3 天 | 迁移单测 + 备份演练 |

**需要用户提前准备**:T8 官方账单样本(批 2 前置);#3 产品决策(批 3 前置);#8 的定义(决定是否占用批 0 插队位)。
