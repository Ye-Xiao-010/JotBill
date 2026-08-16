# 09. 阶段五任务拆分与执行基线

> 技术实现文档（TDD）第九部分。
>
> 关联文档：[新项目启动基线](../NEW_PROJECT_KICKOFF_V2.md)、[阶段四任务基线](08_phase4_tasks.md)、[Room Schema](02_room_schema.md)、[公开 RN 契约](../types/Native.d.ts)。发生冲突时，以 `docs/types/Native.d.ts` 为唯一 RN 契约。
>
> 本文是开发、审查、证据和验收的分工基线。任务完成必须以分支、提交和交接物为单位记录，开发对话不得自行宣称真机样本或真机验收通过。

---

## 1. 文档使用规则

### 1.1 本阶段的执行对象

阶段五的工作对象是完整业务语义：退款识别与原交易关联、转账方向与账户归属、还款不重复计入支出、手续费单独记录、个人规则引擎、字段锁定与数据优先级、多级去重。

阶段四已经在支付证据与 AI 候选匹配后产出 `expense` 主链路的自动入账与待确认；阶段五把"消费之外的钱"（退款、转账、还款、手续费）纳入同一套证据-决策-入账框架，并让"用户确认过的判断"以规则形式沉淀下来，同时保证同一笔交易不因多来源重复入账。

阶段五任务必须基于当前代码边界补齐或修正。不得因为旧设计文档列出某个类，就重复新建同职责类。开发开始前必须先检查目标类是否已存在，并在交接物中列出实际修改的类和原因。

### 1.2 角色定义

| 角色 | 责任 | 不得替代的角色 |
|---|---|---|
| 原生开发 | 修改 Kotlin、Android 配置和本地持久化边界内的实现，提供静态交接物 | 不得代替证据角色宣称真机通过 |
| RN/契约开发 | 只使用现有 `Native.d.ts` 方法和事件，完成 RN 包装或页面接入 | 不得自行新增未声明 Native 方法 |
| 契约审查 | 检查交易类型、字段、方法名、事件名和阶段边界 | 不得把代码存在误判为功能验收通过 |
| 证据角色 | 采集和整理真机退款/转账/还款/手续费通知正文、元数据、日志和证据索引 | 不得由开发对话兼任或用静态结果替代真机证据 |
| 验收角色 | 按独立矩阵执行真机验收，记录通过、失败、未执行和阻塞 | 不得只根据开发自述填写通过 |

### 1.3 分支和提交规则

1. 每个任务从已确认的阶段五基线创建独立分支，分支名使用 `codex/phase5-<任务简称>`；不得在 `master` 或控制工作树直接修改。阶段五代码基线为阶段四代码分支合并结果（当前为 `codex/phase4-review-fixes`，合并到 `master` 后以 `master` 为准）。
2. 一个任务的代码提交只包含该任务的允许文件。审查修正必须在同一任务分支追加中文提交，不得把别的任务或证据文件混入。
3. 提交信息使用中文并包含任务编号，例如：`阶段五任务T2：接入退款识别与原交易关联`。
4. 交接时必须写明：基线分支、基线提交、当前分支、提交哈希、修改文件、未修改但检查过的边界、未解决问题和下一角色。
5. 证据角色可以在专用证据分支提交样本或报告；验收角色必须引用证据提交哈希。开发提交不得包含真机样本，也不得把"待验证"写成"通过"。
6. 涉及 `docs/types/Native.d.ts` 的契约变更必须独立成提交并先经契约审查批准，不得与业务实现混在同一提交。

---

## 2. 阶段五范围与阶段六边界

### 2.1 阶段五允许交付

- 重新路由阶段四被排除的非消费语义：退款/退回/返还生成 `type=refund` 证据与待确认，转账/转出生成 `type=transfer`，还款生成 `type=repayment`，手续费/服务费生成 `type=fee`；不再静默丢弃。
- 退款按 FR-061 关联原交易：原交易号优先，其次金额+商户+平台+时间窗口生成候选；支持多次部分退款；退款总额超过原实付金额时提示异常；原交易缺失时创建独立退款记录并提示手动关联。
- 转账与还款按 FR-062 处理：自有账户间转账不计收支；还款从资产账户转到负债账户、不重复计入支出；无法判断对方账户归属时进入待确认。
- 手续费单独记录为 `type=fee`（计入支出），与主交易拆分；提现、转账、还款附带的费用均可记录。
- 个人规则引擎按 FR-050/051/052 落地：规则在候选生成时和入账前两个时机执行；命中信息写入 `appliedRules`；规则具备优先级、启用状态、创建来源和命中次数；冲突时展示将应用的规则和原因，不静默选择；用户修正后可创建作用于未来（可选历史）的规则。
- 字段锁定与 7 级数据优先级：用户锁定的字段不被任何低优先级来源覆盖；低优先级数据只能作为冲突提示展示。
- 多级去重按 FR-060 落地第 1、2、4 级：来源订单号/交易号精确匹配、同一来源事件标识精确匹配、金额+币种+时间窗口+账户+商户相似度综合匹配（仅提示疑似重复，不自动合并）。
- 待确认中心支持修改交易类型、金额、账户、目标账户、退款原交易选择，以及"修正后创建规则"入口（FR-070 的单笔处理部分）。

### 2.2 阶段五明确不做

下列能力属于阶段六及以后，不能通过本阶段任务、"兼容代码"或验收脚本提前实现：

| 后续能力 | 阶段五处理方式 |
|---|---|
| 微信/支付宝官方账单文件导入解析、导入去重第 3 级（导入记录标识） | 不接入；`sourceType` 保留 `import` 枚举，去重引擎预留第 3 级挂点 |
| 银行短信、邮件账单 | 不接入；`sourceType` 保留 `sms` 枚举 |
| 跨设备同步、云端备份恢复 | 不实现；账本只在本设备 Room 中 |
| 待确认中心批量处理、误合并记录拆分 | 不实现；本阶段只支持单笔确认/忽略/编辑 |
| 余额校准记录撤销、历史账单批量编辑 | 不实现；余额校准维持现状 |
| 多用户/多账本 | 不实现；当前只有单一本地账本 |

### 2.3 现有实现边界

以下类在阶段五基线中已经存在，后续开发必须补齐、修正或复用，不得重复新建同职责类：

| 现有职责 | 当前类 |
|---|---|
| 账单实体（已含 `targetAccountId`、`originalTransactionId`、`orderId`） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/BillEntity.kt` |
| 余额计算（七种类型 + 转账/还款双账户） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（`BalanceCalculator`） |
| 规则实体与 DAO（含优先级、启用状态，缺命中次数/创建来源） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/RuleEntity.kt`、`.../data/dao/RuleDao.kt` |
| 规则 CRUD 与批量应用桥接 | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`（`getRules`/`addRule`/`updateRule`/`deleteRule`/`applyRulesToExisting`） |
| 候选实体（已含 `applied_rules` 列） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/TransactionCandidateEntity.kt` |
| 待确认实体（已含 `type`、`conflictReason`、`orderId`、`accountId`） | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt` |
| 通知解析（当前把退款/转账/转出/提现等作为非消费语义排除） | `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentResultNormalizer.kt` |
| 匹配决策与自动入账（`expense` 主链路） | `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt` |
| 待确认流转与确认入账 | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`、`.../bridge/DataModule.kt` |
| 规则页（当前只读列表） | `jotbill-app/src/screens/settings/RulesScreen.tsx` |
| 待确认中心 UI | `jotbill-app/src/screens/review/ReviewScreen.tsx` |

这里的"已存在"只表示代码边界，不表示已经通过构建、测试或真机验收。任何开发交接都必须区分"静态代码存在""已审查"和"有独立运行证据"。

> 注意：`PaymentResultNormalizer` 的非消费排除关键词当前包含"退款、退回、返还、转账、转出、提现"，这些语义在阶段五必须重新路由到对应交易类型，而不是继续丢弃；纯收款（别人转钱给我）仍不生成账单。

---

## 3. 唯一 RN 契约与定义

### 3.1 `Native.d.ts` 是唯一公开契约

阶段五只读并实现 [Native.d.ts](../types/Native.d.ts) 已声明的接口；本次文档修订不修改 `docs/types/Native.d.ts`。已识别的契约缺口与处理方式见 3.7。

阶段五允许使用的公开接口如下：

| 模块 | 只允许使用的现有方法/事件 |
|---|---|
| `Native.Data` | 账单 CRUD、统计、待确认查询与确认/忽略（同阶段四），另加 `getRules()`、`addRule()`、`updateRule()`、`deleteRule()`、`applyRulesToExisting()` |
| `Native.Recognition` | 同阶段四（会话、候选、证据、重试、状态查询） |
| `Native.Runtime` | 同阶段四 |
| `NativeEventEmitter` | 只订阅已声明事件；阶段五不新增"规则命中""退款到达"等专用事件 |

### 3.2 `TransactionType` 仍严格为 7 个值

`TransactionType` 继续原样使用 [Native.d.ts](../types/Native.d.ts) 中的 7 个值，阶段五不得增加、改名：

```text
income      // 收入
expense     // 支出
transfer    // 转账（不计收支）
refund      // 退款（冲减原支出）
repayment   // 还款（不计收支，负债账户减少）
fee         // 手续费（单独记支出）
adjustment  // 不计收支（押金/余额校准等）
```

各类型余额与统计口径以 `BalanceCalculator` 和 `BillDao` 现有 SQL 为准：`income/refund` 计入收入侧，`expense/fee` 计入支出侧，`transfer/repayment/adjustment` 不计收支。

### 3.3 字段优先级（7 级）

字段更新优先级从高到低（FR 与启动文档 5.4 节一致）：

1. 用户明确编辑并锁定的值（`fieldLocks`）。
2. 用户确认的个人规则结果。
3. 带官方交易号的账单导入字段（阶段六启用）。
4. 支付成功通知/结果页的结构化字段。
5. AI 视觉识别的高置信度字段。
6. 支付会话推断的平台字段。
7. 默认规则和历史建议。

低优先级数据不得覆盖更高优先级数据，但可以作为冲突提示展示。任何自动补全、规则应用、证据回填都必须经过该优先级合并器，不得直接覆写。

### 3.4 规则引擎执行契约

- **执行时机**：两个——候选生成时（为 AI 候选或通知识别补充字段）和入账前（用户确认前与自动入账前的最终校验）。两次执行都记录命中信息到 `appliedRules`。
- **作用范围**：AI 候选路径与纯通知路径都必须执行规则；纯通知路径的商户文本可由规则补全平台和分类。
- **动作语义**：`set_platform`/`set_merchant`/`set_category`/`set_account` 只按 3.3 优先级补全或覆盖允许的字段；`ignore` 直接忽略该笔（不写 `Bill`，审计留痕）；`send_to_review` 强制进入待确认。
- **冲突处理**：多条规则命中且动作冲突时，按优先级（数字小者优先）+ 创建时间排序确定将应用规则，并把冲突规则与原因写入待确认展示，不静默选择。
- **命中记录**：规则实体必须记录命中次数与创建来源（`manual`/`from_correction`）；执行命中时累加，供规则页展示与冲突提示。

### 3.5 退款、转账、还款、手续费判定规则

阶段五所有新语义默认进入待确认，仅当满足下列强匹配条件时才允许自动入账；任何不确定场景都进待确认，不静默入账。

| 语义 | 触发文本特征（样本调参） | 归属与关联规则 | 自动入账条件 |
|---|---|---|---|
| 退款 | 退款/退回/返还 + 金额到账 | `type=refund`；原交易号优先关联本地 `Bill`；无交易号时按金额+商户+平台+时间窗口生成候选列表供用户选择；支持多次部分退款；退款总额 > 原实付金额时提示异常；原交易缺失时创建独立退款记录并提示手动关联；退款接收账户以通知为准，可与原支付账户不同 | 原交易号唯一命中本地未删除 `Bill`，且累计退款额 ≤ 原实付金额 |
| 转账 | 转出/转账/转到零钱/转到银行卡（本人账户特征） | `type=transfer`；源账户=支付应用默认账户，目标账户按文本关键词与本地账户名匹配；无法判断对方账户是否属于用户时进入待确认 | 源与目标账户均精确匹配本地启用账户，且源 ≠ 目标 |
| 还款 | 还款/已还/信用卡还款/花呗还款 | `type=repayment`；源账户=扣款账户（资产），目标账户=对应负债账户（信用卡/消费信贷，按关键词与账户名匹配）；不重复计入支出 | 源与目标账户均精确匹配本地启用账户，且目标为负债类型 |
| 手续费 | 手续费/服务费/提现费（通常伴随主交易文案） | `type=fee`；独立成账单计入支出；若伴随主交易（提现/转账/还款），通过备注或 `originalTransactionId` 关联主账单 | 不自动入账，一律待确认（金额小、特征弱） |

转账给他人（非本人账户）不属于"自有账户间转账"，按支出语义进入待确认由用户选择类型，不得自动判定。

### 3.6 多级去重顺序（FR-060）

| 级别 | 匹配条件 | 阶段五处理 |
|---|---|---|
| 1 | 来源订单号或交易号精确匹配 | 实现：`Bill` 写入前按 `(sourceType, orderId)` 查重，命中则复用/更新，不重复入账 |
| 2 | 同一来源事件标识精确匹配 | 实现：沿用 `sourceEventKey` 幂等；账单主键确定性生成（sessionId）保证同会话只入账一次 |
| 3 | 导入文件记录标识精确匹配 | 预留挂点，阶段六实现 |
| 4 | 金额、币种、时间窗口、账户和商户相似度综合匹配 | 实现：仅在待确认创建与确认入账前做"疑似重复提示"，不自动合并、不静默丢弃 |

去重检查失败或并发写入冲突时，宁可进入待确认，不得丢弃证据。

### 3.7 已识别契约缺口与处理方式

| 缺口 | 说明 | 处理方式 |
|---|---|---|
| `fieldLocks` | `Bill` 接口未声明字段锁定集合 | T5 若需对 RN 暴露，必须先立"契约变更"提交修改 `Native.d.ts` 并经契约审查批准；原生内部存储先行 |
| 规则命中次数/创建来源 | `Rule` 接口未声明 `hitCount`/`source` | 同上；规则页展示可先用现有字段，缺口走契约变更 |
| 退款原交易候选查询 | 待确认编辑需要按条件搜索原交易 | 复用现有 `getBills()` 查询，不新增公开方法 |

未声明接口一律不得私自新增；需要时按 1.3 第 6 条流程执行。

---

## 4. 任务总览

阶段五共 9 个任务：7 个开发/契约任务，2 个独立证据与验收任务。任务 T8、T9 不是开发任务，不能由开发角色自行勾选通过。

| 编号 | 任务 | 角色 | 依赖 | 类型 |
|---|---|---|---|---|
| T1 | 规则引擎执行核心与命中记录 | 原生开发 + 契约审查 | 阶段四基线 | 开发 |
| T2 | 退款识别与原交易关联 | 原生开发 + 契约审查 | T1 | 开发 |
| T3 | 转账与还款识别 | 原生开发 + 契约审查 | T1 | 开发 |
| T4 | 手续费识别与关联 | 原生开发 + 契约审查 | T1 | 开发 |
| T5 | 字段锁定与数据优先级合并 | 原生开发 + 契约审查 | T1 | 开发 |
| T6 | 多级去重 | 原生开发 + 契约审查 | T2、T3 | 开发 |
| T7 | 前端桥接：规则管理与新语义表单 | RN/契约开发 + 契约审查 | T1~T6 | 开发 |
| T8 | 真机新语义证据样本采集 | 证据角色 | T2、T3、T4 分支审查通过 | 证据 |
| T9 | 真机阶段五验收 | 验收角色 | T1~T7、T8 | 验收 |

任务 T8 和 T9 的结果必须单独记录为"通过/失败/未执行/阻塞"，不能用开发分支的代码提交替代。若没有对应证据提交，文档状态只能写"待验证"。

---

## 5. 任务卡

<a id="task-t1"></a>

### T1. 规则引擎执行核心与命中记录

**目标**：新建规则执行引擎，在候选生成时（AI 候选与纯通知路径）和入账前（自动入账与用户确认）两个时机执行规则；`RuleEntity` 补命中次数与创建来源列并做 Room 迁移；命中信息写入 `appliedRules`，`ignore` 与 `send_to_review` 动作接入待确认流转。

**角色与依赖**：原生开发 + 契约审查；依赖阶段四基线；契约审查核对执行时机、冲突排序和命中记录。

**允许修改文件**：

- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/rules/RuleEngine.kt`（执行核心）与同目录必要辅助文件（新增须在交接物中说明）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/RuleEntity.kt`（补 `hitCount`、`source` 列）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/RuleDao.kt`（命中累加与排序查询）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt` 及迁移脚本（版本号 +1 与 Migration）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/TransactionCandidateEntity.kt`、`.../entity/PendingTransactionEntity.kt`（若需补规则命中字段）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`（候选生成时机挂点）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`（入账前时机挂点、`addRule` 补 `source`）

**禁止触碰文件**：

- `docs/types/Native.d.ts`（除非另立契约变更任务并获批）
- 通知解析器语义路由（T2/T3/T4 处理）
- RN 页面（T7 处理）

**必须遵守**：

- 规则执行是纯函数式的判定：输入候选/待确认字段与规则集合，输出补全结果、动作与命中清单；不写库、不弹窗。
- 执行时机一：候选生成时——AI 候选落库前、纯通知 `PendingTransaction` 创建前；执行时机二：入账前——自动入账决策后、`confirmPendingTransaction` 写 `Bill` 前。
- 两次执行的命中都必须记录；`appliedRules` 存规则 ID 与动作摘要，不存全文。
- 动作冲突按 3.4 节排序，冲突信息写入待确认的展示字段（复用 `conflictReason` 或补充内部字段），不静默选择。
- `ignore` 动作：不写 `Bill`、不更新余额，但保留审计记录（含命中规则 ID）。
- `hitCount` 累加必须在事务内，避免并发丢失。
- 迁移必须向前兼容：已有规则行 `hitCount=0`、`source='manual'`。

**提交要求**：中文提交信息 `阶段五任务T1：接入规则引擎执行核心`；只提交允许文件。

**交接产物**：执行时机挂点清单、规则条件/动作逐项映射表、冲突排序说明、迁移脚本、命中记录样例、未做真机验证的明确声明。

<a id="task-t2"></a>

### T2. 退款识别与原交易关联

**目标**：把退款/退回/返还语义从"非消费排除"改为独立识别路径：解析退款通知/结果页生成退款证据与 `type=refund` 待确认，按 FR-061 关联原交易（原交易号优先，其次金额+商户+平台+时间窗口候选），支持多次部分退款、超退异常提示、原交易缺失时独立退款记录；满足 3.5 节强匹配条件时允许自动入账（冲减原支出，金额加回退款接收账户）。

**角色与依赖**：原生开发 + 契约审查；依赖 T1（规则在退款路径同样执行）；契约审查核对退款余额口径与关联规则。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentResultNormalizer.kt`（退款语义识别与路由）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt`（退款证据落库分支）
- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/RefundLinker.kt`（原交易关联决策）与同目录必要辅助文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`（退款自动入账衔接）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`（原交易号/组合条件查询）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt`（若需补原交易候选字段）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（退款入账余额路径，若需调整）

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- 转账/还款/手续费路由（T3/T4 处理）
- RN 页面（T7 处理）

**必须遵守**：

- 退款证据沿用 `sourceEventKey` 幂等；退款通知重复到达只更新，不重复挂账。
- 退款余额口径：`refund` 计入收入侧统计、金额加回退款接收账户（以通知为准，可与原支付账户不同）。
- 自动入账条件严格执行 3.5 节；原交易号命中多笔时不自动入账，进入待确认由用户选择。
- 累计退款额按"原 `Bill` 已关联退款之和 + 本笔"计算；超过原实付金额时 `conflictReason=REFUND_EXCEEDED`，提示异常但仍允许用户确认。
- 原交易缺失或已删除：创建独立退款记录，`conflictReason=ORIGINAL_MISSING`，提示手动关联；不阻塞其他流程。
- 退款通知里解析出的原交易号、退款单号、接收账户必须结构化落库，原文仍只存哈希。

**提交要求**：中文提交信息 `阶段五任务T2：接入退款识别与原交易关联`；只提交允许文件。

**交接产物**：退款语义关键词表、原交易关联决策树、部分退款/超退口径、余额更新规则表、幂等说明、未做真机验证的明确声明。

<a id="task-t3"></a>

### T3. 转账与还款识别

**目标**：把转账/转出语义路由为 `type=transfer`、还款语义路由为 `type=repayment`：按 3.5 节判断方向与账户归属，目标账户按关键词与本地账户名匹配推断；推断失败或对方账户无法确认属于用户时进入待确认；满足强匹配条件时自动入账并复用 `LedgerRepository` 双账户余额更新（转账双向调整，还款不重复计入支出）。

**角色与依赖**：原生开发 + 契约审查；依赖 T1；契约审查核对方向判定与余额口径。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentResultNormalizer.kt`（转账/还款语义识别与路由）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt`（转账/还款证据分支）
- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/TransferClassifier.kt`（方向与账户归属决策）与同目录必要辅助文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`（自动入账衔接）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AccountDao.kt`（账户名匹配查询）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt`（若需补目标账户候选字段）

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- 退款路由（T2 处理）、手续费路由（T4 处理）
- RN 页面（T7 处理）

**必须遵守**：

- 转账不计收支；还款不计收支且目标必须为负债类型账户；两者余额更新复用现有 `applyBalanceDelta` 双账户逻辑。
- 源账户默认取支付应用默认账户（沿用阶段四映射）；目标账户只按"文本关键词 + 本地启用账户名精确/包含匹配"推断，不做模糊猜测。
- 账户推断失败、源=目标、目标非负债（还款场景）一律 `conflictReason=ACCOUNT_UNRESOLVED` 进待确认。
- 转账给他人（文本特征无法确认是本人账户）按支出语义进待确认，由用户改类型，不得自动判定为自有账户转账。
- 自动入账条件严格执行 3.5 节；并发或幂等沿用 `sourceEventKey` 与确定性主键。

**提交要求**：中文提交信息 `阶段五任务T3：接入转账与还款识别`；只提交允许文件。

**交接产物**：转账/还款关键词表、账户归属判定表、双账户余额更新映射、待确认冲突原因清单、未做真机验证的明确声明。

<a id="task-t4"></a>

### T4. 手续费识别与关联

**目标**：把手续费/服务费/提现费语义识别为 `type=fee` 独立账单（计入支出）：与主交易（提现/转账/还款）同通知到达时拆分记录，通过备注或 `originalTransactionId` 关联主账单；一律进入待确认，不自动入账。

**角色与依赖**：原生开发 + 契约审查；依赖 T1；契约审查核对拆分口径与统计口径。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentResultNormalizer.kt`（手续费语义识别与金额拆分）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt`（手续费证据分支）
- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/FeeExtractor.kt`（手续费拆分）与同目录必要辅助文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/seed/DefaultDataInitializer.kt`（若需补手续费种子分类）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt`（若需补主交易关联字段）

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- 退款/转账/还款路由（T2/T3 处理）
- RN 页面（T7 处理）

**必须遵守**：

- `fee` 计入支出统计、扣减对应账户余额（沿用 `BalanceCalculator` 现有口径）。
- 同一通知同时含主交易与手续费时：主交易走对应类型路径，手续费独立成 `fee` 待确认；两笔的 `sourceEventKey` 必须可区分（追加语义后缀）。
- 手续费金额解析失败时不强行入账，整条进待确认并标记解析失败。
- 手续费与主交易的关联只写 `originalTransactionId`（主账单已存在时）或备注提示，不阻塞确认。

**提交要求**：中文提交信息 `阶段五任务T4：接入手续费识别与关联`；只提交允许文件。

**交接产物**：手续费关键词表、拆分规则说明、`sourceEventKey` 区分规则、种子分类变更、未做真机验证的明确声明。

<a id="task-t5"></a>

### T5. 字段锁定与数据优先级合并

**目标**：落地 3.3 节 7 级字段优先级：实现统一的优先级合并器，所有自动补全（规则、证据回填、AI 回填、默认建议）经过合并器按优先级写入；用户编辑并锁定的字段（`fieldLocks`）不被任何低优先级来源覆盖，只产生冲突提示；`BillEntity` 补字段锁定存储与迁移。

**角色与依赖**：原生开发 + 契约审查；依赖 T1（规则动作经合并器生效）；契约审查核对优先级矩阵与锁定语义。

**允许修改文件**：

- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/rules/FieldPriorityResolver.kt`（优先级合并器）与同目录必要辅助文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/BillEntity.kt`（补 `field_locks` 列）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt` 及迁移脚本（版本号 +1 与 Migration）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（写入路径经合并器）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`（`updateBill` 应用锁定；锁定输入经现有 `BillUpdateInput` 通道）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/rules/RuleEngine.kt`（动作输出改为经合并器）

**禁止触碰文件**：

- `docs/types/Native.d.ts`（`fieldLocks` 对 RN 的暴露走 3.7 契约变更流程，本任务先做原生内部）
- 通知语义路由文件（T2/T3/T4 处理）
- RN 页面（T7 处理）

**必须遵守**：

- 锁定字段集合为空时行为与现状完全一致（纯增量，不改变既有账单语义）。
- 低优先级值与已存在高优先级值冲突时只记录冲突提示（待确认展示或审计），不得覆写。
- 证据回填（阶段四"支付先到、AI 后到"路径）也必须经合并器：AI 字段不得覆盖证据结构化字段，除非前者优先级更高（按 3.3 节，证据高于 AI）。
- 迁移向前兼容：已有账单 `field_locks` 为空集合。
- 锁定只作用于字段级（平台/商户/分类/账户/金额等），不支持行级整体锁。

**提交要求**：中文提交信息 `阶段五任务T5：接入字段锁定与优先级合并`；只提交允许文件。

**交接产物**：优先级矩阵逐项映射表、锁定字段清单、合并器输入输出示例、迁移脚本、冲突提示展示方案、未做真机验证的明确声明。

<a id="task-t6"></a>

### T6. 多级去重

**目标**：落地 3.6 节去重第 1、2、4 级：`Bill` 写入前按 `(sourceType, orderId)` 查重、沿用 `sourceEventKey` 与确定性主键幂等、待确认创建与确认入账前做金额+币种+时间窗口+账户+商户相似度的"疑似重复提示"；第 3 级预留挂点。

**角色与依赖**：原生开发 + 契约审查；依赖 T2、T3（新语义账单共用去重）；契约审查核对各级判定与不丢弃原则。

**允许修改文件**：

- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/rules/DuplicateGuard.kt`（去重判定）与同目录必要辅助文件
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`（`(sourceType, orderId)` 与相似度查询）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（写入路径接 `DuplicateGuard`）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutPaymentMatcher.kt`（自动入账前查重）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/PendingTransactionEntity.kt`（若需补疑似重复标记字段）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`（确认入账前查重）

**禁止触碰文件**：

- `docs/types/Native.d.ts`
- 通知语义路由文件
- RN 页面（T7 处理）

**必须遵守**：

- 第 1 级命中：同 `(sourceType, orderId)` 已有账单时，新证据只更新原账单可变字段（经 T5 合并器），不产生第二笔。
- 第 2 级沿用现状：`sourceEventKey` 幂等 + sessionId 确定性主键；本任务只补覆盖面（新语义路径也走同一幂等键族）。
- 第 4 级只提示不合并：命中相似交易时在待确认标记"疑似重复"并附相似度原因；用户仍可确认两笔并存。
- 退款（多次部分退款）不适用第 1 级合并：退款按退款单号去重，不按原交易号合并。
- 任何去重检查异常（查询失败、并发冲突）都进待确认，不丢弃证据、不静默入账。

**提交要求**：中文提交信息 `阶段五任务T6：接入多级去重`；只提交允许文件。

**交接产物**：四级判定流程图、各级命中行为表、相似度因素与阈值说明、退款豁免规则、并发行为说明、未做真机验证的明确声明。

<a id="task-t7"></a>

### T7. 前端桥接：规则管理与新语义表单

**目标**：补全 `RulesScreen` 的规则创建/编辑/启停/删除与"应用到历史"入口（含冲突展示与命中次数，若契约未暴露则只展示现有字段）；待确认中心支持修改交易类型、金额、账户、目标账户、退款原交易选择，并提供"修正后创建规则"入口（FR-051）；新增/编辑账单表单支持转账/还款的双账户选择；账单编辑展示锁定标记（若契约已批准暴露）。

**角色与依赖**：RN/契约开发；依赖 T1~T6；契约审查必须逐项对照 `Native.d.ts`。

**允许修改文件**：

- `jotbill-app/src/screens/settings/RulesScreen.tsx`（CRUD 与应用入口）
- `jotbill-app/src/screens/review/ReviewScreen.tsx`（类型编辑、目标账户、原交易选择、规则创建入口、疑似重复提示）
- `jotbill-app/src/screens/bills/AddBillScreen.tsx`、`EditBillScreen.tsx`（转账/还款双账户表单；文件名以实际为准，分支开始时登记）
- `jotbill-app/src/native/data.ts`、`recognition.ts`（类型与封装补充，不新增未声明方法）
- `jotbill-app/src/navigation/AppNavigator.tsx`（若需新路由）
- 与现有 Native 模块注册直接相关的 RN 文件；具体页面必须在分支开始时登记

**禁止触碰文件**：

- `docs/types/Native.d.ts`，除非另立"契约变更"任务并获得明确批准
- 规则引擎、通知路由、去重、优先级合并的 Kotlin 文件
- 未声明的"规则命中事件""退款到达事件"等专用事件

**必须遵守**：

- 规则 CRUD 使用已声明的 `getRules/addRule/updateRule/deleteRule/applyRulesToExisting`；命中次数等契约缺口字段在批准前不得引用。
- "修正后创建规则"只允许把本次修正映射为单条规则的 `condition/action` 草稿，用户确认后才落库；不静默创建。
- 待确认编辑后的确认仍走 `confirmPendingTransaction(id, input, version)`；类型修改仅通过 input 通道，不改方法签名。
- 退款原交易选择复用 `getBills()` 查询；不做本地全文搜索。
- 转账/还款表单：`targetAccountId` 必填校验（transfer/repayment），源 ≠ 目标校验，还款目标限定负债账户。
- 缓存刷新沿用阶段四 ReviewScreen 的联动失效清单，不得遗漏账户与报表。

**提交要求**：中文提交信息 `阶段五任务T7：接入规则管理与新语义表单`；只提交登记过的允许文件。

**交接产物**：Native 方法/事件逐项映射表、页面调用链、表单校验清单、契约缺口处理说明、与 `Native.d.ts` 的静态比对结果。

<a id="task-t8"></a>

### T8. 真机新语义证据样本采集

**角色**：独立证据角色，不属于开发验收。开发者可以提供待采集场景清单，但不能在代码对话中宣称样本已通过。

**依赖**：T2、T3、T4 的分支提交已被审查；需要指定设备、系统版本、应用版本（微信、支付宝）和可复现操作路径。

**允许修改/新增文件**：

- `docs/qa/new-semantics-samples/` 下的阶段五样本
- 样本索引、包名/通知渠道/采集时间/预期字段元数据
- 证据角色自己的中文采集报告和证据提交

**禁止触碰文件**：

- 不修改 Kotlin、TypeScript、`Native.d.ts` 或任务文档来"适配"样本
- 不采集或纳入支付密码、付款码、银行卡号、CVV 等敏感字段原文
- 不把模拟通知、旧设备通知或开发机截图标记为真机证据

**样本数量基线**：阶段五主样本固定为 **4 类语义 × 2 个支付应用 = 8 个样本**：

| 应用 | 退款通知 | 转账通知 | 还款通知 | 手续费通知 |
|---|---|---|---|---|
| 微信 | `wechat_refund_notification.json` | `wechat_transfer_notification.json` | `wechat_repayment_notification.json` | `wechat_fee_notification.json` |
| 支付宝 | `alipay_refund_notification.json` | `alipay_transfer_notification.json` | `alipay_repayment_notification.json` | `alipay_fee_notification.json` |

补充样本（如部分退款二次到账、多笔并发转账）必须放在单独的补充目录或在索引中标为"非主样本"。若某类通知在指定应用确实无法产生（如应用无该场景），允许标注"场景不可得"并用最接近的真实样本替代，但必须在索引中说明，不得以模拟文本充数。

**提交要求**：中文提交信息 `阶段五任务T8：提交真机新语义证据样本`；提交只允许的样本、索引和证据报告，不得携带业务源代码或契约文件。

**交接产物**：8 个样本、样本索引、每个文件的包名和通知渠道、采集设备信息、预期解析字段（类型/金额/原交易号/目标账户线索）、缺失/脱敏说明、证据提交哈希。

<a id="task-t9"></a>

### T9. 真机阶段五验收

**角色**：独立验收角色。验收结果由证据和验收角色签署，开发对话不能自行勾选通过。

**依赖**：T1~T7 的审查提交和 T8 的 8 个主样本证据；需要明确设备、系统、微信/支付宝版本、AI 服务配置和网络条件。

**允许修改/新增文件**：

- `docs/qa/` 下的阶段五验收报告、测试矩阵和证据索引
- 验收角色的截图、UI dump、关键日志摘要和失败复现记录

**禁止触碰文件**：

- 不为通过验收修改业务源代码、契约或样本原文
- 不接入或验证账单导入、银行短信、云端同步等阶段六能力
- 不把开发分支的静态审查、构建结果或单元测试结果当作真机通过证据

**阶段五验收矩阵**：

| 场景 | 预期结果 | 必需证据 |
|---|---|---|
| 微信退款通知到达 | 生成 `type=refund` 待确认，预填原交易候选 | 待确认 ID、关联原 `Bill` ID |
| 支付宝退款通知到达 | 同上 | 同上 |
| 原交易号唯一命中的退款 | 自动入账，原支出冲减，余额加回 | `Bill` ID、余额变化 |
| 原交易缺失的退款 | 独立退款待确认，标记 `ORIGINAL_MISSING` | 冲突原因、待确认状态 |
| 累计退款超过原实付 | 待确认并标记 `REFUND_EXCEEDED` | 冲突原因 |
| 多次部分退款 | 各自独立入账，累计正确 | 多笔 `Bill` ID 与合计 |
| 微信转账通知（本人账户） | `type=transfer`，双账户余额调整，不计收支 | 余额前后对比 |
| 支付宝转账通知（对方不明） | 进入待确认，`ACCOUNT_UNRESOLVED` | 冲突原因 |
| 还款通知（信用卡/花呗） | `type=repayment`，负债减少，不计支出 | 双账户余额变化 |
| 手续费通知/拆分 | `type=fee` 独立待确认，计入支出 | 待确认 ID、类型 |
| 规则在候选生成时命中 | 字段按规则补全，`appliedRules` 记录 | 命中清单 |
| 规则 `ignore` 命中 | 不写 `Bill`，审计留痕 | 审计记录、账单计数不变 |
| 规则 `send_to_review` 命中 | 强制进入待确认 | 待确认 ID |
| 规则冲突 | 展示将应用规则与原因，不静默 | 待确认展示字段 |
| 用户修正后创建规则 | 规则落库并可作用于后续交易 | 规则 ID、后续命中记录 |
| 字段锁定后证据回填 | 锁定字段不变，冲突提示展示 | 字段前后对比 |
| 同订单号第二笔支付 | 第 1 级去重命中，不重复入账 | 账单计数、更新记录 |
| 疑似重复提示 | 第 4 级命中，待确认标记，用户可选 | 待确认标记、用户操作结果 |

**验收报告必须逐项标记**：`通过`、`失败`、`未执行` 或 `阻塞`，并引用证据文件和提交哈希。未执行真机操作时，只能写"未执行"，不能写"通过"。

**提交要求**：中文提交信息 `阶段五任务T9：提交真机阶段五验收报告`；提交只允许的验收报告和证据索引，不得携带业务源代码或契约文件。

**交接产物**：按矩阵逐项标记的验收报告、设备/应用版本、证据文件清单、失败与阻塞说明、验收提交哈希和下一阶段交接事项。

---

## 6. 阶段五状态与交接示例

### 6.1 退款主链路

```text
退款通知到达
  -> Normalizer 识别退款语义（不再按非消费丢弃）
  -> 退款证据按 sourceEventKey 幂等落库
  -> RefundLinker 关联原交易（原交易号优先，其次组合候选）
  -> 唯一命中且累计退款 ≤ 原实付：自动入账（type=refund，冲减原支出，余额加回）
  -> 否则：type=refund 待确认（预填原交易候选 / ORIGINAL_MISSING / REFUND_EXCEEDED）
  -> 用户确认 -> 入账
```

### 6.2 转账/还款主链路

```text
转账/还款通知到达
  -> Normalizer 识别语义（transfer / repayment）
  -> TransferClassifier 判定方向与目标账户（关键词 + 账户名匹配）
  -> 双账户均可精确匹配且约束满足：自动入账（双账户余额调整，不计收支）
  -> 否则：待确认（ACCOUNT_UNRESOLVED），由用户选择目标账户或改类型
```

### 6.3 规则引擎链路

```text
候选生成（AI 候选 / 纯通知）
  -> 时机一：RuleEngine 补全字段 / ignore / send_to_review
  -> 匹配决策（阶段四）+ 去重（T6）
  -> 时机二：入账前最终校验（含字段优先级合并 T5）
  -> 自动入账或待确认（冲突展示将应用规则）
  -> 用户修正 -> 可选创建规则（FR-051）-> 命中次数累加
```

### 6.4 不允许的捷径

- 收到退款/转账/还款/手续费通知就静默丢弃或直接写 `Bill`，不经决策。
- 原交易号命中多笔时自动选一笔入账。
- 转账给他人自动判定为自有账户转账。
- 手续费并入主交易金额，不单独记录。
- 规则动作直接覆写用户锁定字段或更高优先级字段。
- 规则冲突时静默任选，不展示原因。
- 用"金额+时间窗口接近"自动合并疑似重复（第 4 级只提示）。
- 为了 RN 刷新或新语义展示自行新增未声明事件或方法。
- 以模拟通知文本替代真机样本用于验收。

---

## 7. 证据、审查和完成定义

### 7.1 开发提交完成条件

开发任务只有在以下材料齐全时才可交给审查：

- 中文提交信息、分支名、基线提交和提交哈希。
- 允许/禁止文件清单与实际 `git diff --name-only` 一致。
- 公开接口、交易类型、字段优先级和阶段边界的逐项对照表。
- 规则执行时机、关联决策、去重判定、余额口径和幂等行为说明。
- 明确列出未运行的构建、测试和真机验证。

### 7.2 契约审查完成条件

契约审查必须至少检查：

- `TransactionType` 仍只有 7 个现有值；新语义只路由到既有类型，不新增类型值。
- `Native.d.ts` 已声明接口被正确使用；`fieldLocks`、命中次数等缺口未私自暴露，契约变更走独立提交且已获批。
- 规则只在两个既定时机执行；命中记录完整；`ignore` 有审计；冲突不静默。
- 字段优先级合并器覆盖所有自动写入路径；锁定字段不被覆盖。
- 退款/转账/还款/手续费自动入账条件与 3.5 节一致；不确定场景一律待确认。
- 去重第 1/2/4 级行为与 3.6 节一致；异常不丢弃证据。
- 余额更新与统计口径复用 `BalanceCalculator` 与现有 SQL，未引入第二套口径。
- 实际类复用当前实现边界，没有重复创建同职责类。

### 7.3 真机完成条件

真机完成只能由 T8/T9 角色根据独立证据签署。阶段五验收至少需要 8 个主样本证据、四类新语义的识别与流转证据、退款关联与超退证据、规则两时机命中与冲突证据、字段锁定证据、去重证据。缺任何一项都必须标记为"部分完成"或"待验证"，不能从代码提交推断通过。

---

## 8. 风险与未解决问题

| 风险/问题 | 阶段五处理 | 责任角色 |
|---|---|---|
| 微信/支付宝退款/转账/还款通知格式随版本变化 | 用 T8 真机样本调参；未知格式保持解析失败并记录，不强行入账 | 证据角色 + T2/T3 开发 |
| 转账对方账户无法确认归属 | 一律待确认，由用户选择；不自动判定 | T3 |
| 退款原交易不在本地（历史账单未导入） | 独立退款记录 + 手动关联；批量补关联留给后续阶段 | T2 |
| 手续费金额与主交易在同一文本中难以拆分 | 解析失败整条进待确认，不强拆 | T4 |
| 规则条件表达力有限（现有 6 种条件） | 只实现已声明条件；扩展条件走契约变更 | T1 + 契约审查 |
| `fieldLocks`/命中次数契约缺口 | 原生先行，RN 暴露走契约变更流程 | T5/T7 + 契约审查 |
| 第 4 级相似度阈值过严或过松 | 初版保守阈值，按 T8/T9 结果调参；只影响提示不影响入账 | T6 |
| 阶段四代码尚未合并 master | 阶段五分支从阶段四最完整分支创建；合并顺序由项目负责人决定 | 契约审查 |
| 真机证据尚未采集 | 状态为"待验证"，由 T8/T9 独立推进 | 证据/验收角色 |

阶段五文档、代码审查或开发对话不得把阶段六能力包装为"回退""兼容""自动完成"。边界不清时，停止扩展并由契约审查创建后续阶段任务。
