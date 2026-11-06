> **状态头**：正典·必读（现状规格） | 最后核实 2026-11-06 | 冲突时以 `jotbill-app/android` 代码为准

# 自动记账现状规格（CURRENT_SYSTEM）

> **定位**：唯一一份"现在系统怎么工作"的说明。读者 = 要改自动记账代码的 AI/新人，读完应能定位到该改哪个符号。
> **只写现状**：历史演进/已废止需求/批次编号见 `NEW_PROJECT_KICKOFF_V2.md`（设计史料）与各历史取证文档。
> 全文只用符号名（行号会漂，用 `grep` 按符号定位）。

## 1. 系统总览

```
六条信号源（采集层）
  ├─ 无障碍支付结果页文本 ──┐
  ├─ 支付通知（N-1/N-2）──┤
  ├─ 页面规则引擎（8 渠道）─┼─→ 归并/去重（合并层）──→ 落点判定
  ├─ 银行短信（mms 通知）──┤      ├ orderId 归并 2min（M1 金额/类型一致才并）├─→ Bill（自动入账）
  ├─ 账单导入 ─────────────┤      ├ 同包同额 15s / 跨包同额 15s(P3) / 文本哈希 └─→ Pending（待确认）
  └─ 手动录入/确认 ─────────┘      └ L1-L4 判重 + N-2 吸收 + 转账跨腿(M2)          │
                                                                    ↓
                                              RN 展示（Review/Pending）← PendingChangeHub 事件
```

## 2. 采集层（六条信号源）

| # | 信号源 | 入口类（符号） | 包名闸 | 时间闸/敏感页 | 产出 |
|---|---|---|---|---|---|
| 1 | 无障碍观察（总入口） | `CheckoutAccessibilityService.onAccessibilityEvent` → `CheckoutPageDetector.detect` | `RecognitionPackages.SHOPPING_PACKAGES_CORE` / `PAYMENT_PACKAGES` | 敏感页整帧跳过（`AccessibilityPageSummaryCollector` 敏感判定先于一切） | 页面角色/指纹/汇总 |
| 2 | 支付结果页文本 | `CheckoutAccessibilityService.scanPaymentResultCore` → `PaymentResultParser.parseResult` | 支付包 `supportsPaymentPackage`；购物包走 B1 接线（`pageRole == PAYMENT_RESULT`） | **30 分钟时间闸**（`RESULT_PAGE_FRESHNESS_MILLIS`，页面时间 >30min 拒）；敏感页零文本采集；事件级 15s 幂等（`markPaymentResultEvent`） | `PaymentEvidenceInput` |
| 3 | 支付通知 | `PaymentNotificationService` → `PaymentResultNormalizer`（消费/退款/转账/手续费四路） | `RecognitionPackages.NOTIFICATION_PACKAGES`（微信/支付宝；**手工维护的 `PAYMENT_PACKAGES` 真子集**，`BookkeepingChannels.CHANNELS` 经 `ChannelInfo.kind` 向它对齐、`BookkeepingChannelsTest` 锁一致性） | 微信身份闸 `wechatIdentityOk`；`PAYMENT_KEYWORDS` 闸；时间提取**优先业务标签**（支付时间/退款时间等，无标签回退正文首个时间，P16） | 证据 → N-1/N-2 |
| 4 | 页面规则引擎 | `AutoBookkeepingEngine`（PageRule 状态机）→ `AutoBookkeeper.book` | `BookkeepingChannels.CHANNELS`（8 渠道，默认 7 开 1 关） | 规则资产 key = `包/类名` > `包/*` > 裸类名（`PageRuleRegistry.matchKey`） | 记录 → 入账/待确认 |
| 5 | 银行短信 | 同 4（mms 系统通知 `typeNotificationStateChanged`，**无需 SMS 权限**） | `com.android.mms` 渠道（默认关，用户开关） | 抽取失败 → 不产出 | 同 4 |
| 6 | 账单导入 | `ImportEngine.execute`（微信/支付宝账单文件） | — | — | 行 → `decide()` 判重 |

- **分档**：`RecognitionPackages` = 包名单一出处。`SHOPPING_PACKAGES_CORE`（8 包，进结算观察）/ `SHOPPING_PACKAGES_RESULT_ONLY`（得物/唯品会/苏宁，只进结果页腿）/ `PAYMENT_PACKAGES`（微信/支付宝/云闪付）。
- **页级硬不变式**：敏感页（密码/验证码/付款码等词表 + `isSensitiveNode`）**零文本采集**。
- 指纹稳定性三层：`isRotatingWidgetClass`（静态轮播类名）+ `VolatileTokenTracker`（动态易变池）+ `PageAmountAccumulator` 金额豁免。

## 3. 合并层（同一笔不双记，不同笔不误并）

宿主：`CheckoutPaymentMatcher`（支付信号）+ `LedgerRepository.createBillWithDedup`（账单）+ `AutoBookkeeper`（规则/短信）。

| 机制 | 判据 | 窗口 | 跨包 | 跨源 | 位置（符号） |
|---|---|---|---|---|---|
| **orderId 强键归并** | 两侧 orderId 相等（**M5：trim/全角转半角/大写归一**，`OrderIdNormalizer` + SQL `UPPER(TRIM())`）→ 归并进既有卡 | **2min**（`PAYMENT_IDENTITY_MERGE_WINDOW_MILLIS`） | ✅ | ✅ | `CheckoutPaymentMatcher.recordOrphanPaymentSignal` → `findRecentPendingByOrderId` |
| **orderId 冲突护栏** | 两侧非空且不同 → **显式拒绝归并**，宁出两张可删的卡 | — | ✅ | ✅ | 同上（护栏段）+ `RecentPaymentCooldown.cooldownRemaining`（冷却层同款放行） |
| 同包同额窗（D-25） | 金额精确相等+同包+type=expense；orderId 冲突护栏放行异单号 | 15s（`PaymentTiming.SAME_TXN_WINDOW_MILLIS`） | ❌ | ✅（同包内） | `findRecentPendingByPlatformAndAmount` |
| **跨包同额窗（P3）** | 无 orderId+跨包+金额相等+type=expense ⇒ 归并进单卡 + 追加 `SUSPECTED_DUPLICATE`（用户一次决策）；**短信双向排除** | 15s | ✅ | ✅ | 同上 P3 段 → `findRecentPendingByAmountCrossPlatform` |
| 文本哈希主判据 | `(包, 金额, rawTextHash)` 含订单号摘要；**P2：哈希输入已剥离倒计时/时钟/相对时间字样** | 15s 保留 | ❌ | ❌ | `RecentResultObservation.findSameObservation` |
| sourceEventKey 幂等 | 确定性键：结果页 `result-event\|pkg\|15s桶\|额\|单`；消费通知 SHA-256（包+通知key+通知id+postTime+额+单）；退款/转账/手续费 **P6 业务字段族**（包+额+单(+目标/收款人)+秒级时间桶，正文无时间时改用脱敏内容哈希 M3）；规则/短信 `abk:ruleId:extractTime` | — | ❌ | ❌ | `insertIfAbsent`（同键不建第二张） |
| **L1-L4 判重** | L1 `DuplicateGuard.checkLevel1Consistent`（orderId 精确查重，**M1：金额与类型一致才合并，撞号不入账**；撞号处置——**嵌套事务内三处（N-2/转账/候选自动入账）预检式调用，不产生异常**（嵌套 Room 事务内层抛错会让外层整体回滚，2026-11-06 探针实证，`RoomNestedTransactionProbeTest` 守门），**顶层两处（规则链/确认）抛 `OrderConflictException` 由事务外 catch 兜底**；refund 豁免，manual 账单不被并入）；L2 sourceEventKey 幂等；L3 `checkLevel3`（`importRecordId`）；L4 `findSimilar`（金额精确+±10min+辅因子，只提示） | — | ✅ | ✅ | `DuplicateGuard`（方法：`checkLevel1Consistent`/`checkLevel3`/`findSimilar`） |
| **N-2 吸收** | 强证据入账命中同 orderId 卡（24h 窗）→ 同事务**先删卡再入账**；删除失败 → 恢复已删卡并**降级归并进卡**（不入账）；**M1 撞号预检**（不抛异常）→ 恢复已删卡+标记转建卡段带 `DEDUP_CHECK_FAILED`；**入账提交失败（P5 最终形态）→ 抛 `N2CommitFailedException` 故意击穿外层事务**（吸收删除随回滚复原、账单未写入），函数边界 catch 后以 `suppressStrongEvidence=true` 递归调用完成**第二段独立事务**（只做归并/建卡） | 24h | ✅ | ✅ | `recordOrphanPaymentSignal` N-2 段（`deleteByIdIfVersion` 乐观锁 + 两段式回落） |
| **合并即留痕** | 被归并信号的 `sourceEventKey` 幂等追加进卡片 `appliedRules.mergedSourceEventKeys`；**M7：数组上限 16（先到者优先），溢出转 `mergedSourceEventKeysOverflow` 计数** | — | — | — | `mergeSignalIntoExisting` → `appendMergedSourceEventKey` |
| **转账跨腿查重（M2）** | 结果页个人转账卡 × 通知转账卡：同包+同额+**同收款人**（2min 窗）⇒ 归并单卡；收款人缺失/不等绝不并 | 2min | ✅（两腿间） | ✅ | `findSameTransferCard` / `mergeTransferSignalInto`（`recordPersonalTransfer`、`TransferClassifier` 双向调用） |
| 规则引擎认领 | 同包同额指纹（**P4：Claim 加 orderId 护栏**，异单号放行）让弹窗管线让路 | 15s | ❌ | ❌ | `AutoBookkeepingEngine.deliver` / `shouldSuppressPopup` |

- **短信不参与跨源归并**（显式决策）：短信 orderId 恒空（规则资产无 orderNo 槽），走原同包逻辑。
- 导入强一致：`ImportEngine.decide` 无 `merchantOrderId` 时，**金额精确 + 同日 + 商户模糊三者全中且唯一**才 MERGED（`findStrongIdentityMergeTarget`），缺一退回只提示。
- 导入退款行先过 `checkRefundRowGuard`（B1 **计数差值**查重：同 (原单,金额) 键批次内第 1..baseline 行 SKIPPED、之后放行，同额真分次退款不丢；SKIPPED 带 `REFUND_DUPLICATE_COUNTED` 写 `import_skip` 批次审计留痕；B3 累计超额 → PENDING），详见 §5.1。
- 事件级冷却：`RecentPaymentCooldown`（消费后同键 15s 抑制；orderId 冲突放行）。

## 4. 落点判定

**自动入账（Bill）** 满足任一：
- N-2 强证据：通知 + orderId 非空 + 账户可解析（`defaultAccountIdForPayment` 包名映射）+ 非疑似重复 → `createBillWithDedup`；
- 页面规则/短信：`AutoBookkeeper.book` 账户可解析（含 `CASH_FALLBACK_ACCOUNT_ID` 现金兜底）且非"用户 set_account 规则命中"；
- 用户手动确认：`PendingConfirmService.confirm` → `confirmPendingTransaction`。

**驳回/不产出**（连卡都不建）：
- 结果页解析失败（无金额）/ 页面超 30 分钟 / 敏感页 → 采集中断，仅日志留痕；
- 规则抽取失败（`necessaryFields` 不满足）→ 无记录；
- 重复观测（幂等键/哈希/同额窗命中）→ `SUPPRESSED_*`/`ALREADY_STORED`，合并进既有卡 conflictReason；
- L4 疑似重复且无法吸收 → `CONFLICT_SUSPECTED_DUPLICATE` **转待确认**（不入账、不合并）；
- **M1 撞号**（同 orderId 不同金额/类型）→ 不入账、不合并、卡/证据均不丢弃，处置分两种形态：**嵌套事务内三处**（N-2 段 / `TransferClassifier` 自动入账 / `autoCommitMatchedCandidate`）**预检** `checkLevel1Consistent`，Conflict 直接回落建卡带 `DEDUP_CHECK_FAILED`（不产生异常——嵌套 Room 事务内层抛错会让外层整体回滚，探针守门 `RoomNestedTransactionProbeTest`）；**顶层两处**（规则链 `AutoBookkeeper.book`、确认/手动录入 `PendingConfirmService`）抛 `OrderConflictException`，由**事务外** catch 分别回落建卡 / 向用户报错。

**转待确认（Pending）** 冲突码（`conflictReason`；常量名与存储值不同时成对标注）：

| 码（常量名 = 存储值） | 含义 | 产出符号 | 可达性 |
|---|---|---|---|
| `"NO_DEFAULT_ACCOUNT"`（无常量，字面值） | 规则/短信路径账户无法解析 | `AutoBookkeeper.book`（`resolveAccountId` null → `fallbackToPending`） | 活跃 |
| `"ACCOUNT_UNRESOLVED"` | 通知侧账户无法解析/他人转账/约束不满足 | `PaymentNotificationService` | 活跃 |
| `CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW` = `"RULE_ACCOUNT_NEEDS_REVIEW"` | 短信 + 用户 `set_account` 规则命中 ⇒ 强制人工核对（仅 mms 渠道） | `AutoBookkeeper.book` 短信短路分支 | 活跃 |
| `RuleEngine.CONFLICT_RULE_FORCED_REVIEW` = `"RULE_FORCED_REVIEW"` | 用户规则强制转待确认 | `AutoBookkeeper.book` / `FeeExtractor` | 活跃 |
| `CONFLICT_AUTO_COMMIT_DEFERRED` = `"AUTO_COMMIT_DEFERRED"` | 退款/转账自动入账闸（`isNotificationAutoCommitAllowed` 恒 false）拦截 | `RefundLinker` / `TransferClassifier` | 活跃 |
| `CONFLICT_FEE_AMOUNT_UNPARSED` = `"FEE_AMOUNT_UNPARSED"` | 手续费金额解析失败整条转待确认 | `FeeExtractor` | 活跃 |
| `"IMPORT_SEMANTIC_UNSAFE"`（字面值） | 导入语义不安全（refund 无原单等） | `ImportEngine` | 活跃 |
| `CONFLICT_SUSPECTED_DUPLICATE` | L4 疑似重复提示；**P3 起也用于「跨包已归并、待用户一次决策」的标记**；**M4：带此码的卡首次确认被拦，RN 二次确认后带 `confirmedDuplicate` 放行** | `DuplicateGuard.findSimilar` / P3 归并段 | 活跃 |
| `DEDUP_CHECK_FAILED` | **M1：同 orderId 撞号（金额/类型不一致）回落待确认**；另用于会话匹配判重查询失败（该路径当前不可达） | `AutoBookkeeper.book` / N-2 预检回落段 / `TransferClassifier` 预检回落段 / `autoCommitMatchedCandidate` | **活跃**（M1 起） |
| `"AUTO_BOOK_FAILED"`（字面值） | 自动入账失败 | `AutoBookkeeper`（`fallbackToPending`） | 活跃 |
| `"PAYMENT_RESULT_NO_MATCHING_SESSION"`（字面值） | 支付结果页无匹配会话（孤儿卡） | `RecognitionModule` / `recordOrphanPaymentSignal` | 活跃 |
| `"AMBIGUOUS_SESSION_IDS:<id列表>"`（字面值+后缀） | 多会话歧义，每会话一张 + 孤儿兜底 | `CheckoutAccessibilityService` | 活跃 |
| `"EVIDENCE_PERSIST_FAILED"`（字面值） | 证据落库失败兜底 | `CheckoutAccessibilityService` | 活跃 |
| `"ORDER_ID_MISMATCH"`（字面值） | 订单号不符（`ORDER_ID_PARTIAL` 只是判定标签非落库码） | 会话匹配 `evaluate`（conflicts 字面） | **当前不可达**（见下） |
| `"TIME_MISSING"` / `"TIME_WINDOW_MISMATCH"` / `PAYMENT_TIME_OUTSIDE_WINDOW` | 时间缺失/超窗/页面超 30 分钟（均为 conflicts 字面） | 会话匹配 `evaluate` / `validatePayment` | **当前不可达** |
| `"EVIDENCE_AMOUNT_CONFLICT"`（字面值） | 占位候选与实付金额精确不等 | 会话匹配（占位补证据） | **当前不可达** |
| `"AI_AMOUNT_MISSING"` / `"AI_AMOUNT_LOWER_THAN_PAYMENT"` / `"AI_AMOUNT_HIGHER_THAN_PAYMENT"`（conflicts 字面）+ `AI_UNVERIFIED_PAYMENT_REASON = "AI_UNVERIFIED_PAYMENT"` | AI 金额缺失/不符/截图未证实 | 会话匹配 `evaluate` | **当前不可达**（AI 侧恒缺席） |
| `ACCOUNT_UNAVAILABLE` | 自动入账账户不可用 | 会话匹配 `autoCommitMatchedCandidate` 区 | **当前不可达**（本会话取证：仅出现在该函数区；`RULE_FORCED_REVIEW` 另有活跃产出见上） |

> **"当前不可达"口径**：以上六组码的产出点在会话匹配路径（`evaluate` / `autoCommitMatchedCandidate` / `pendingFromConflict`），而 `ensureSession` 无生产调用方、生产永不建会话（见第七章）——**两章口径一致**。改会话匹配逻辑时这些码的语义仍有效。
> **旧码名勘误**：旧文档曾写 `NO_MATCH` / `AMBIGUOUS` —— 实际落库字面为 `PAYMENT_RESULT_NO_MATCHING_SESSION` 与 `AMBIGUOUS_SESSION_IDS:<id列表>`。

- 个人转账走独立通道：`TransferClassifier`（源/目标账户匹配）与 `recordPersonalTransfer`（确定性 ID 幂等）；**P13：收款人命中的卡按 `transfer` 语义预填（不再定型 expense，不命中付款确认弹窗）**；**M2：两腿跨腿查重，同收款人归并单卡**。
- 退款/手续费独立键族 `pending-refund`/`pending-fee`（`RefundLinker`/`FeeExtractor`），不参与消费归并（L1 对 refund 豁免）。

## 5. 字段与账务

- **优先级链**：`FieldPriorityResolver.Source` 7 级 USER_LOCK(1) > RULE(2) > IMPORT(3) > EVIDENCE(4) > AI(5) > SESSION(6) > DEFAULT(7)；低级不得覆盖高级、锁定字段（USER_LOCK）不可覆盖。**当前生产活跃 3 级**：USER_LOCK / IMPORT / EVIDENCE（RULE 级**无独立落账产出**——用户规则 patch 由规则引擎产出后按 EVIDENCE 级落账；AI/SESSION 恒缺席）。
- 合并/吸收只补空字段（`?:`），不覆盖已有值。
- 账单写入与余额原子：`createBillWithDedup` 同事务（`withTransaction`），失败整体回滚；判重命中走 `mergeIntoExistingBill`（只补空，**M1：金额/类型不一致抛 `OrderConflictException`，不再静默合并**）。
- 手动/AI 录入幂等（P15）：`DataModule.addBill` 支持可选 `clientRequestId`（RN 表单会话/AI 提案 id），确定性主键 `manual:<id>`，重试/双击/重放命中既有账单即幂等返回；缺省保持随机 UUID。

### 5.1 余额模型与账务联动（2026-11-06 审查后补写）

- **余额是冗余存储，不是实时聚合**：`AccountEntity` 同存 `initialBalanceMinor`（期初）与 `currentBalanceMinor`（当前）；每笔账单写/改/删在同一 Room 事务内增量维护后者。不变式口径：**current = initial + Σ(账单delta) + Σ(校准diff)**。
- **六类型方向真值表**（唯一出处 `BalanceCalculator.delta`；导入侧 `ImportEngine.applyImportedBalance` / `ImportUndo.applyBalance` 为平行实现、方向逐字对齐）：

  | 类型 | 源账户 | 目标账户 |
  |---|---|---|
  | income / refund | +amount | — |
  | expense / fee | -amount | — |
  | transfer / repayment | -amount | **恒 +amount**（负债账户负数=欠款，+amount 即欠款减少向 0 靠近） |
  | adjustment | **0**（余额由 `calibrateAccountBalance` 直接设值） | — |

- **编辑 = 重算法**（`updateBillWithBalanceUpdate`）：事务内 `-1×旧账单` + `+1×新账单`，改账户时旧账户回滚、新账户应用两边都做；**删除 = 反向回滚**（`deleteBillWithBalanceUpdate`，含通知栏撤销 `AutoBookkeepingUndoReceiver`）。adjustment 账单三路径均锁死（`requireMutableBill`）。
- **校准语义**：`calibrateAccountBalance` 直接设余额 + 写 delta=0 的 adjustment 账单 + `balance_calibrations` 留痕 —— 它是**人工对账修复口**，校准本身改变余额但不改变 Σ账单delta，因此不变式必须把 Σ校准diff 纳入。
- **账户删除 = 软删**（`DataModule.deleteAccount` 只置 `isActive=false`）：余额与历史账单保留；净资产只算活跃账户，收支统计（`BillDao.getSummary` 等）不含账户活跃过滤。注意：已停用账户的历史账单**不可编辑**（`validateBillAccounts` 要求 active）但**可删除**。
- **退款防线（唯一出处 `RefundCapGuard`：累计退款 ≤ 原实付，覆盖四条路径）**：自动入账（`RefundLinker`）/ 确认（`confirmPendingTransaction`）/ **编辑**（`updateBillWithBalanceUpdate`，B2 起，超额抛 `LedgerValidationException` 整体回滚）/ **导入**（`ImportEngine.checkRefundRowGuard`，B3 起，超额转待确认）。无原单关联或原单已删不校验（无锚点）。超额文案唯一出处 `RefundCapGuard.REFUND_EXCEEDED_MESSAGE`（中文，经桥接层原样展示，RN 不套壳）。
- **退款跨源查重（B1，计数差值口径）**：L1 与导入合并对 refund 的豁免是**必要**的（通知链路退款账单 orderId=原支付单号，导入退款行单号=退款单号）；重复判定走**原单关联**——`checkRefundRowGuard` + `RefundSkipCounter`：同 (原单, 金额) 键下 baseline=批前库里已有退款数（账单+待确认卡），批次内第 1..baseline 行 SKIPPED、第 baseline+1 行起放行（**同额第二笔真实分次退款不吞**）；inspect/execute 各自一份计数器、跨 chunk 共享。SKIPPED 行写 `import_skip` 审计（`writeSkipAudit`：行号/金额/商户单号/脱敏样本，`insertIfAbsent` 幂等）。
- **余额一致性抽检（B4，只提示不改数）**：`BalanceReconciler`（口径含 Σ校准diff，**校准过的账户不误报**）；启动时后台协程 `MainApplication.onCreate` → `auditMismatches`（命中写审计 `entityType=balance_reconcile`，按日幂等）；RN 账户页经桥 `DataModule.getBalanceReconcileWarnings` 实时重算并展示横幅，指向既有「校准余额」入口。
- **并发**：全部余额读改写都在 Room `withTransaction` 内（单进程串行）；账单乐观锁只管 version，余额 RMW 与同事务，无丢更新窗口。金额上限 `MAX_SAFE_MINOR_UNIT` + `checkedAdd/checkedMultiply`，超限抛错即整事务回滚。

### 5.2 统计口径

- 收支统计/趋势/预算（`getBillStatistics`/`getSummary`/趋势/`sumExpenses`）= **账单聚合**口径（income+refund 计收入侧，expense+fee 计支出侧，transfer/repayment/adjustment 不计入）。
- 净资产/账户余额（`getNetWorth`/`getAccounts`）= **余额字段**口径（`current_balance_minor`，负债取 ABS）。
- 两套口径靠上述不变式保持一致；漂移的唯一自动发现通道 = B4 抽检。

## 6. RN 展示与手动确认

事件：落卡/入账 → `PendingChangeHub.emit` → `PendingReminderNotifier`（通知）/ 前台弹窗（`PendingPromptPolicy`，仅 `USER_ASSERTED*` 前缀优先 —— ⚠️ **该优先级当前作用在死码上**：`USER_ASSERTED_REASON` 全仓只有定义无写入点；`PENDING_CONFLICT_REASON_USER_ASSERTED` 仅用于通知过滤前缀匹配）→ RN `ReviewScreen`。
确认：RN → `DataModule`（`confirm`/`reject`）→ `PendingConfirmService` → `confirmPendingTransaction`（`deleteByIdIfVersion` 原子删卡 + L1 判重后写 Bill；**M4：带 `SUSPECTED_DUPLICATE` 的卡首次确认返回 `DUPLICATE_CONFIRM_REQUIRED`，RN 弹二次确认后带 `confirmedDuplicate=true` 放行**）。
通知消费监听：`PaymentNotificationService` 用户设置页二次过滤（配置只收窄不扩展）。
录入前查重（P12/M8）：AI 提案与手动录入保存前均经共享工具 `src/lib/billDuplicateCheck.ts` 做「同日同额同向」既有账单检测，命中弹确认（不硬阻断）。
归并可见性（M-补③）：待确认卡面展示「已归并 N 条信号（同一事实的多处观测）」——数据来自 `appliedRules.mergedSourceEventKeys`（上限 16）+ `mergedSourceEventKeysOverflow`（溢出计数），经 `BridgeDtoMapper.pending` 透出、RN 解析计数，只读展示不改确认语义。

## 7. 已知限制与死代码（改动前先看）

| 项 | 事实 | 影响 |
|---|---|---|
| 会话链路断裂 | `ensureSession`/`findReferenceForPayment` **无生产调用方**（两者均有测试调用：`CheckoutPaymentMatcherIntegrationTest` 等）；会话匹配/`evaluate` 不可达 | 自动记账不依赖会话；删改需拍板 |
| `aiOrderId` 恒 null | `TransactionCandidateEntity.aiOrderId`/`aiOccurredAt` 写入恒 null（AI 候选链路已删）；`TransactionCandidateDao.findActiveByOrderId` 恒空 | 三重校验实际只余金额一项 |
| 跳包跨包收敛（P3） | 跨包同额无 orderId **15s 内归并进单卡** + 追加 `SUSPECTED_DUPLICATE`（用户一次决策），短信双向排除；超 15s 仍各出一张卡 | 15s 物理论证未经真机验证，归并卡可删（安全侧） |
| 短信口径 | 默认自动入账现金兜底账户（乙口径）；`set_account` 规则命中强制转待确认 | 误报面靠负向测试与文案提示 |
| 手动重试必然拒绝 | `performUserRetryForSession` 恒返回 `SERVICE_NOT_CONNECTED`（入口壳保留） | 前端入口不可用 |
| `AI_TRIGGER_ARMED` 死状态 | `CheckoutJourneyState.AI_TRIGGER_ARMED` 枚举与迁移映射保留，生产不可达 | 待随会话链路清理 |
| 进程保活为零 | 无前台服务（Manifest 权限已声明未用）；杀进程窗口漏采无补偿 | 已知限制 |
| 无服务端能力 | 无服务端规则下发、无服务端去重（`litenote-backend` 非重心）；规则来自本地内置 JSON（`bookkeeping_builtin_rules.json`）+ 用户规则 | 规则更新随版本发布 |
| 支付方式不可见 | 普通消费通知/结果页不暴露"支付方式"字样（实测 grep 零命中），**"微信零钱 vs 绑卡支付"无法区分** —— 微信/支付宝固定映射到对应平台账户或规则账户；`payMethod` 刻意不做 | 账户推断仅短信层可用 |
| 账户无卡号字段 | `AccountEntity` 无卡号/尾号字段 ⇒ 短信只能按**银行名→账户名**匹配（`AccountInference.inferFromSignal`），同银行多账户命中**转待确认**（`RULE_ACCOUNT_NEEDS_REVIEW`）、不猜测 | 精度受限，唯一命中才映射 |
| 无解锁入口 | 用户手改字段自动进 `fieldLocks`（`DataModule.updateBill`），此后**不接受自动链路回填**；当前无"解除字段锁定"UI（刻意取舍：用户优先） | 需解锁功能时另行拍板 |
| N-2 表单竞态 | 待确认卡上的用户编辑是**表单态、卡上无痕**；N-2 吸收删卡后用户提交会因卡不存在而落空，需重填；账单侧由 L1 `checkLevel1` + `mergeIntoExistingBill`（只补空）兜底 ⇒ 不双记、不覆盖用户值 | 已知竞态，可接受 |
| 微信页面规则实验性 | 微信渠道规则可能打不中（基类 key 打不中子类页） | 微信内站内支付靠结果页文本腿兜底 |

## 8. 如何自证（改哪块跑哪些测试）

| 改动域 | 必跑测试 | 锁住的行为 |
|---|---|---|
| 结果页解析/站内接线 | `PaymentResultParserTest`、`PaymentResultInAppGuardTest` | 30min 闸、订单页负向守卫（**P8：强支付成功语义放行/弱语义抑制**）、敏感页零采集、幂等键、**P2 倒计时哈希稳定** |
| 归并/去重 | `CheckoutPaymentSignalMergeTest`（18 场景：orderId 归并 2min/冲突拒绝、同包同额 15s、**P3 跨包 15s 归并+超窗/短信排除**、N-2 吸收、P5 回落、M7 留痕上限）、`LedgerLevel1ConsistencyTest`（M1 一致性+M5 归一）、`RoomNestedTransactionProbeTest`（**嵌套事务守门**：外层 catch 内层 Room 事务异常后继续写库 ⇒ 外层整体回滚，钉死预检/两段式写法前提）、`TransferClassifierTest`（P13/M2 跨腿）、`RecentPaymentCooldownTest`、`RecentResultObservationTest`、`OrphanOutcomeTest` | 同包同额 15s、N-2 吸收、留痕、**撞号抛异常不吞证据** |
| 短信/规则入账 | `BankSmsNegativeTest`、`AutoBookkeepingEngineTest`（含 **P4 Claim orderId 护栏、P11 复位不丢挂起**）、`AutoBookkeeperEnrichmentTest` | 四类误报零记录、乙口径落库、规则命中转待确认、认领互斥 |
| 包名/分档 | `RecognitionPackagesTest`、`BookkeepingChannelsTest`、`CheckoutClassSemanticTest` | 白名单派生一致性、CORE/RESULT_ONLY 分档 |
| 通知解析 | `PaymentResultNormalizerTest`（跨零点、**P6 业务键族稳定、M3 无时间内容哈希、M6 秒级桶、P16 标签优先**）、`PaymentNotificationConfigTest` | 通知解析语义与键族稳定 |
| 指纹/敏感 | `VolatileTokenTrackerTest`、`PageFingerprintStabilityTest`、`SensitivePageLatchTest` | 漂移三层、敏感锁存 |
| 导入 | `ImportMergeTargetTest`、`WechatBillAdapterTest`、`AlipayBillAdapterTest`、`CsvTableReaderTest`、`ImportPerformanceSmokeTest` | 强一致三条件 MERGED、账单文件解析适配、CSV 读取、导入性能（**P9：账户未匹配行转待确认**，不落无账户账单） |
| 账务 | `CheckoutPaymentAmountToleranceTest`、`PendingConfirmServiceTest`（含 **M4 疑似重复二次确认**）、`LedgerRepaymentDirectionTest`（还款/转账双账户方向+删除回滚）、`LedgerEditDeleteBalanceTest`（编辑改账户双边、expense/refund 删除回滚、B2 编辑退款上限）、`BalanceReconcilerTest`（B4 口径含校准差额、校准不误报、审计按日幂等）、`ImportRefundGuardTest`（B1 计数差值查重：同额真分次退款放行、跳过留痕审计；B3 超额转 PENDING）、`ConfirmRefundCapTest`（确认路径退款上限） | 金额容差、确认删卡原子、余额方向/回滚/退款防线/抽检 |

全量：`./gradlew.bat :app:testDebugUnitTest`（基线 385 tests / 0 failures，2026-11-06 起）。

> 补充：①行为红线的**负向断言清单**（"不创建会话/无 `ensureSession` 日志"等通过判定）见 `docs/qa/golden-path-regression.md`，比本节更细；②排查金额解析可看 **`AmountDiag` 采样日志**（token / dropped 计数），出处 `docs/FIX_TASK_2026-09-29_attribution_pending_dup.md`。

## 附录 A：典型场景走查（改动合并/落点逻辑时逐条对照）

| 场景 | 预期行为 | 由哪条机制保证 |
|---|---|---|
| 同一订单返回再进结果页 | 只 1 张卡 | 幂等键 + 文本哈希 + 同额窗三层 |
| 跳包支付（拼多多→微信）同笔 | 15s 内跨包归并为 1 卡 + `SUSPECTED_DUPLICATE` 标记；超 15s 为 2 张可删的卡 | P3 跨包同额归并（短信排除） |
| 结果页先到、通知 1min 后到（同 orderId） | 归并进同一卡，`mergedSourceEventKeys` 留痕 | orderId 归并 2min 窗 |
| 同 orderId 不同金额（改价重付/单号复用） | **撞号**：不入账、不合并，回落建卡带 `DEDUP_CHECK_FAILED`（确认/手动路径向用户报错） | M1 一致性校验（`checkLevel1Consistent` + `OrderConflictException`） |
| 同包同额 15s 内两笔真单（orderId 不同） | **2 张卡**（冲突护栏放行） | orderId 冲突护栏 + 冷却放行 |
| 结果页卡在、N-2 强证据后到（同 orderId） | 1 笔账单、0 卡、`absorbed_pending` 审计留痕 | N-2 吸收（先删后写） |
| N-2 入账时删卡冲突 | 卡恢复、信号降级归并进卡、不入账 | `deleteByIdIfVersion` 乐观锁 + 降级分支 |
| 退款到达 | 独立 `pending-refund` 卡，不并入消费 | 键族隔离 + L1 refund 豁免 |
| 微信转账（"已转账给XX"） | `recordPersonalTransfer`/`TransferClassifier` 各自成卡，**同收款人跨腿归并为 1 卡**（M2） | 转账强语义 + M2 跨腿查重 |
| 银行短信 + 用户 set_account 规则命中 | 转待确认（`CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW`） | `AutoBookkeeper.book` 短信短路分支 |
| 短信 + 无规则 | 自动入账现金兜底账户 + `account_inferred_cash_fallback` 审计标记（P1） | `resolveAccountId` → `CASH_FALLBACK_ACCOUNT_ID` |
| 导入行与实时账单同商户同额同日（无商户单号） | MERGED 合并进既有账单；**导入行账户未匹配 ⇒ 转待确认（P9）** | `findStrongIdentityMergeTarget` 三条件 / `decide` |
| 连续两笔同额同商户 >15s | 2 笔账单/卡 | 不同 occurredAt 桶 + 无 orderId 归并 |
| 密码/付款码页出现 | 零文本采集、零记录 | 敏感页判定先于解析 |
| 订单详情页出现"支付成功"字样 | **强语义（支付成功/付款成功/支付完成）⇒ 放行产证据；弱语义（已付款/交易完成）⇒ 不产证据** | `isLikelyOrderDetailPage`（P8 细化） |

## 附录 B：主要采集字段

| 字段 | 结果页 | 通知 | 规则/短信 | 导入 | 手动 |
|---|---|---|---|---|---|
| 金额 `amountMinor` | ✅（¥/元/金额： 三模式） | ✅ | ✅ | ✅ | ✅ |
| 商户 `merchant` | ✅（4 模式） | ✅ | ✅（orgName 等） | ✅ | ✅ |
| 订单号 `orderId` | 页面有才采 | 较高（通知常带） | 部分规则有；**短信恒缺** | `merchantOrderId` 可空 | 用户填 |
| 时间 `occurredAt` | 页面时间优先（30min 闸） | 通知发送时间 | 抽取时刻/短信时间 | 账单时间 | 用户填 |
| 账户 `accountId` | 包名映射默认账户 | 包名映射 | 现金兜底/账户名匹配 | 账单账户 | 用户选 |
| 商品/分类 | 部分 | 少 | 规则 `item`/`detailType` | — | 用户填 |
> 订单号键在**提取/归并入口处统一格式归一**（M5：trim + 全角英数转半角 + 大写，`OrderIdNormalizer`；DAO 查询侧 `UPPER(TRIM())` 兼容历史写法），跨源归并不被大小写/全半角差异漏并；退款原单关联查询（`findExpensesByOrderId`/`findOriginalForRefund`）同样双侧归一（M5-补②）。**不做跨语义映射**（不同来源单号是否同值需真机取证）。

## 附录 C：改动意图 → 落点符号速查

| 想改什么 | 找哪里 |
|---|---|
| 加/减采集包名 | `RecognitionPackages`（+ `BookkeepingChannels.ChannelInfo.kind` 对齐，`BookkeepingChannelsTest` 锁） |
| 改结果页解析（金额/商户/单号/时间正则） | `PaymentResultParser`（`parseResult` / 各 `extract*`） |
| 改订单页负向守卫 | `PaymentResultParser.isLikelyOrderDetailPage` |
| 改 30 分钟时间闸 | `RESULT_PAGE_FRESHNESS_MILLIS`（`PaymentResultParser` 内消费） |
| 改归并窗口/冲突护栏 | `CheckoutPaymentMatcher.PAYMENT_IDENTITY_MERGE_WINDOW_MILLIS` + `recordOrphanPaymentSignal` 归并段 |
| 改同包同额 15s 窗 | `PaymentTiming.SAME_TXN_WINDOW_MILLIS` + `findRecentPendingByPlatformAndAmount` |
| 改跨包同额收敛（P3） | `recordOrphanPaymentSignal` P3 段 + `findRecentPendingByAmountCrossPlatform` |
| 改 L1 撞号判据（M1） | `DuplicateGuard.checkLevel1Consistent` + `LedgerRepository` 两处调用方 + `OrderConflictException` |
| 改转账跨腿查重（M2） | `CheckoutPaymentMatcher.findSameTransferCard` / `mergeTransferSignalInto` |
| 改 N-2 强证据/吸收 | `recordOrphanPaymentSignal`（N-2 段 + `strongEvidenceSignal`） |
| 改短信落库口径 | `AutoBookkeeper.book`（短信短路分支）+ `resolveAccountId` |
| 改页面规则/渠道 | `BookkeepingChannels` + 资产 `assets/bookkeeping/bookkeeping_builtin_rules.json`（⚠️ 改资产需拍板） |
| 改导入判重 | `ImportEngine.decide` / `findStrongIdentityMergeTarget` |
| 改字段优先级 | `FieldPriorityResolver`（⚠️ 不动清单，先拍板） |
| 改确认入账流程 | `PendingConfirmService.confirm` / `confirmPendingTransaction` |
