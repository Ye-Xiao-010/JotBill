# 批 4 规格:Journey(结算旅程)模型重构

> 起草日期:2026-08-29
> 分支:`roadmap/batch4-journey`(长分支,浸泡 ≥1 周后单 PR 合入,revert 即回滚)
> 前置:批 0 安全网 193 例全绿(47 例特征测试钉死现有行为);批 1 兜底闭环已上线
> 状态:**规格已确认(2026-08-29)——按 §9 顺序实施,S2 起动工**

---

## 0. 目标与非目标

**目标**
1. 订单身份从"整树文本指纹哈希"换成稳定锚点,指纹降级为"页面变化信号";
2. 旅程状态机唯一归属旅程状态,收敛 service/coordinator/buffer 三套真相源;
3. 支付结果兜底的平行通道并入统一门禁策略;
4. 最终退役大部分历史防护补丁(现状 ~850 行竞态防护/15 个机制)。

**非目标(硬性红线,重构期间不得触碰)**
- 敏感页 7 层防线(检测器/提交动作/手动截图/store/重试上下文/routeLocked/通知回退);
- AI 未验证证据永不自动落账(批 0 已钉死为集成契约);
- 每会话 AI 请求预算制与"被拒不扣预算";
- 快照仅内存、释放抹零、不落盘;
- 批 0 的 47 例特征测试必须保持绿色,除非 §8 显式列为有意行为变化。

## 1. 现状问题(引用审计事实)

- 身份锚定:整树文本 SHA-256(`AccessibilityPageSummaryCollector.kt:276-284`)——轮播文字、
  改地址、换优惠券都产生"新订单",靠 15 个防护机制兜(drift 复用/交接栅栏/尾窗/
  中间页保留/墓碑/接管守卫…),机制间相互作用已两次成为 bug 源(快照被自己的栅栏锁死、
  悬浮状态被抖动压制)。
- 已钉死的微妙行为(特征测试):①不同指纹的自动候选不并存,无条件替换;②
  `retainForPaymentHandoff` 不过期清理,栅栏可锚定到过期候选指纹。
- 会话与槽位的键不一致:buffer 槽位 = `shopping-package:<pkg>`;checkout_sessions 表
  **无 slotId 列**,按 包+指纹+时间窗 检索(`findActiveByPage`)。

## 2. Journey 身份定义

**锚点集合**(判定"同一笔订单"):
- `shoppingPackage`(购物包名,必选);
- `windowClassSemantic`(结算类名语义:fillorder/checkout…,可选弱锚);
- `amountMinor`(首次稳定观察到的应付金额;用户编辑地址/换券导致金额变化时**更新锚点而非换身份**,见 §3-b);
- 时间连贯性:未被死亡规则(§3)终结。

**指纹的新角色**:不再是订单边界。它继续用于:buffer 候选匹配、交接栅栏、
请求键幂等(`CheckoutRequestKey`)、诊断日志。`handleStable` 的判定从
"指纹变了吗"改为"锚点集合属于哪个 journey"。

**JourneyId 生成**:出生时由 `snapshotIdFactory` 同源工厂生成随机 ID;
对外(API/日志)只暴露 ID 与锚点摘要,不暴露指纹原文之外的页面文本。

## 2a. S5b 实施修订(2026-08-29,实现期发现)

1. **金额锚点推迟**:CheckoutPageObservation 目前只携带 hasFinalAmountSignal 布尔量,
   不携带金额值;金额提取正则误判风险高,不在此轮引入。因此 S5b 的锚点 =
   (shoppingPackage, windowClass),**同包旅程归并**——同包不同金额的连续订单
   暂时归并为同一旅程,第二笔证据按已确认取舍走孤儿待确认(不静默)。
   并发世代键(#g<N>)随之推迟到金额锚点落地后激活,当前代码不引入世代逻辑。
2. **惰性死亡判定**:D1/D2/D3 不用定时器,在每次事件到达(handleStable/
   handleInvalidated 入口)时按 shouldDie 求值。无事件时旅程在内存中滞留无害
   (快照 TTL 照常清理),下次事件触发即清算——对外行为与定时器等价,
   实现复杂度大幅降低。
3. **漂移尾窗泛化**:交接挂起期间的同包稳定事件 = 同锚点 + WAITING_PAYMENT 阶段
   → 维持忽略(等待支付),原 2.5 秒尾窗逻辑被阶段语义吸收。

## 3. 生命周期状态机(扩展既有 CheckoutJourneyState)

现状:`CheckoutJourneyState` 16 态 + `canTransitionTo` 已上线
(`CheckoutContract.kt:191-301`,journey_state 落库于 checkout_sessions)。
本重构**扩展它**,不新造状态机。

### 3-a 出生
- 出现稳定 CHECKOUT 页,且不存在"活跃 journey"(活跃 = 未死亡,见下)或
  活跃 journey 的锚点集合不匹配(包不同 / 金额与类名锚点均不同)。

### 3-b 存活与锚点更新
- 同包 + 同锚点:指纹漂移**不换身份**,只评估"是否需要补截"(无存活候选时按预算重截);
- 金额锚点变化(用户在结算页编辑地址/数量/换券):**同 journey,更新金额锚点**;
- 同包收银台/选择支付方式等中间页:journey 内事件(NON_CHECKOUT_PAGE 保留语义不变);
- 切到支付包:PAYMENT_APP_HANDOFF,journey 进入 WAITING_PAYMENT(沿用现有状态)。

### 3-c 死亡(参数待用户确认,建议值加粗)
| 规则 | 建议参数 |
|---|---|
| D1 交接后超时:进入 WAITING_PAYMENT 后未收到任何支付证据且未回到结算页 | **8 分钟** |
| D2 结算页停滞:稳定 CHECKOUT 后既未提交也未交接 | **10 分钟** |
| D3 用户明确离开:前台切到非支付包、非中间页语境的普通浏览页且持续 | **3 分钟** |
| D4 被新 journey 取代:同包出现锚点不同的新稳定 CHECKOUT | 即时(旧 journey → EXPIRED,证据按 §3-d 处理) |
| D5 显式关闭:服务断连/销毁/onInterrupt | 即时(EXPIRED,内存快照抹零不变) |

### 3-d 难题清单(规格必须逐条回答)
| 难题 | 裁决 |
|---|---|
| **并发两笔**(journey A 在 WAITING_PAYMENT,journey B 同包进入结算页) | A 与 B 并存:A 靠 金额+5 分钟窗+订单号 等通知匹配(现状 matcher 语义,已按指纹检索、天然支持);B 拥有自己的槽位与预算。**槽位键升级**:`checkoutSnapshotSlotId(pkg)` → `checkoutSnapshotSlotId(pkg, journeyGeneration)`,同包第 N 个未终结 journey 使用 generation N;唯一活跃 journey 沿用现键(向后兼容,历史会话不迁移) |
| 拆分支付(一笔订单两次支付) | 已知限制,不新增机制:第二笔证据因会话已消费/关闭走孤儿待确认(现状即如此,不恶化) |
| 支付失败返回结算页 | journey 复活:restoreFromPaymentHandoff 语义保留;可重截受预算与墓碑约束,手动重试通道不变 |
| 敏感页(密码页) | journey 不死亡(密码页是旅程一部分);敏感锁存 60 秒与截图抑制逻辑原样保留 |
| 通知先于截图到达 | 现状双路径已兜(调用方兜底),journey 化不改变证据路径 |
| 同包金额相同的两笔 consecutive 订单 | 锚点相同 → 归并为同 journey:第二笔的证据按"会话已消费"走孤儿待确认(现状一致)。在待确认中心可见,不静默 |

## 4. 三套真相源收敛

- 新增 `JourneyStateHolder`(进程内单例,主线程读写):活跃 journey、锚点、世代计数、
  各 journey 的 pending/attempted 引用。service 的
  `currentStableCheckout/handoffStableCheckout/handoffForegroundPackage` 与协调器的
  active/handoff 上下文改为从 holder 派生;
- 协调器门禁逻辑本体**不动**(批 0 的 17 例门禁测试必须保持绿色),只换上下文来源;
- buffer 接口不变(`CheckoutBufferGateway` 两个方法),槽键生成函数签名扩展。

## 5. 支付结果兜底通道统一

- 新增 `PaymentEvidenceGatePolicy`(纯 Kotlin,可单测):敏感判定(锁存+当前观测)、
  预算、sourceEventKey 幂等,主路径与截图兜底共用;
- 截图兜底保留独立 `encodePaymentScreenshot`(系统截图 API 不同),但**产生证据前必须过
  同一 policy**;`recognizePaymentResult` 的结果仍走 `PAYMENT_STATUS_AI_UNVERIFIED` 降级。

## 6. CheckoutJourneyState 三个既有缺口的处置

1. `advanceStatus` 非法迁移静默 no-op(matcher :1371-1392)→ **保持 no-op 但增加
   Log.w**(有意的可观测性变化,语义不变——会话推进失败不应崩溃服务);
2. `LedgerRepository` 绕过状态机直写 COMPLETED/DISMISSED(:426-436/:512-523)→
   **S2 先常量化**:终态值改由 `CheckoutJourneyState` 枚举派生(消除双处字面量,
   语义不变);真正的统一推进入口在 **S5** 落地——LedgerRepository 不能依赖
   matcher(循环依赖),届时用共享纯迁移助手而非注入;
3. `CheckoutJourney` 未实例化包装类(Contract:329-364)→ 删除(死代码)。

## 7. 有意行为变化清单(相对批 0 特征测试,总计 1 类)

- 槽位键格式:`shopping-package:<pkg>` → 单旅程场景**保持原键不变**(向后兼容);
  并发第 N 旅程才启用 `shopping-package:<pkg>#g<N>`。
  `CheckoutSnapshotBufferTest` 增补并发世代用例,原有 26 例语义不变。

除此之外,47 例特征测试必须全绿——这是"重构没有悄悄改行为"的机器证明。

## 8. 测试与验收

1. **单元**:新增 journey 生命周期矩阵测试(出生/锚点更新/D1-D5 死亡/并发/复活);
2. **回归**:批 0 全量 193 例 + 新增用例全绿;
3. **真机矩阵**:结算页→微信、结算页→支付宝、扫商户码、付款码被扫、转账、
   连续两笔支付、改地址后支付、支付失败重试;
4. **浸泡**:本人日常使用 ≥1 周,期间黄金日志无"快照丢失/误清空"症状;
5. **合入**:单 PR --no-ff 合并 main;出问题 revert 单 merge 即回滚。

## 9. 实施切分(分支内按序提交)

| # | 内容 | 依赖 |
|---|---|---|
| S1 | 本规格(本提交) | - |
| S2 | §6 三缺口处置(独立可测,先行落地) | S1 确认 |
| S3 | PaymentEvidenceGatePolicy + 兜底通道接入 | S1 |
| S4 | JourneyStateHolder + service/coordinator 上下文收敛 | S2 |
| S5 | 槽位世代化 + handleStable/handleInvalidated 重写为 journey 语义 | S4 |
| S6 | 生命周期矩阵测试 + 全量回归 + 退役失效防护机制 | S5 |
| S7 | 设备矩阵 + 浸泡 | S6 |

## 10. 三个决定(2026-08-29 用户已确认)

1. **死亡参数**:按建议值——D1=8 分钟 / D2=10 分钟 / D3=3 分钟。
2. **同包同金额连续两笔**:归并为同 journey,第二笔证据走孤儿待确认(不静默)。
3. **槽位世代化**:仅并发第二笔启用 `#g<N>` 世代键,单旅程保持旧键,历史会话零迁移。

三项均已定稿,S2 起按 §9 顺序实施。
