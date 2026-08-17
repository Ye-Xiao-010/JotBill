# 阶段七修复：拼多多支付证据"未关联结算页"（NO_ACTIVE_SESSION）根因修复

> 状态：**0.0.48 真机复测通过**
>
> 设备：25128PNA1C（Android 17 / HyperOS），adb serial `4a6e283b`
>
> 修复前版本：0.0.47（versionCode 47）；最终验证版本：0.0.48（versionCode 48）

## 1. 真机现象

拼多多结算页截图成功（日志 `截图状态: STORED, 来源=CHECKOUT_STABLE`），微信支付完成后，
待确认中心出现冲突原因为 `NO_ACTIVE_SESSION` 的待确认交易，前端显示
"未关联结算页，请核对后确认"（`pending_transactions.sessionId=NULL, candidateId=NULL`）。

## 2. 根因

拼多多提交订单后进入**同包名（com.xunmeng.pinduoduo）的内部收银台页**，该页面被
`CheckoutPageDetector` 判定为 `NON_CHECKOUT_PAGE`（Rejected）。旧逻辑把这次失效当成
"离开结算页"，三层上下文同时被销毁：

1. 服务层清空 `currentStableCheckout` / `handoffStableCheckout`；
2. `CheckoutStabilityCoordinator.invalidate(NON_CHECKOUT_PAGE)` 分发后，
   `CheckoutSnapshotCaptureCoordinator.handleInvalidated` 执行 `buffer.clear()`
   ——已 STORED 的结算页截图被销毁；
3. `CheckoutPurchaseIntentCoordinator.clearContextsLocked()` 清空 `activeContext`。

同时 `CheckoutStabilityCoordinator` 复位后 `hasActiveCheckout=false`，切微信时的
`invalidate(PAYMENT_APP_HANDOFF)` 被早退忽略，`PAYMENT_APP_HANDOFF` 事件不再分发，
`consumeForAi` 永远不会执行，`ensureSession` 从未落库。

微信支付成功页证据到达时：交接引用为 null、buffer 无快照、DB 无会话无候选，
`resolveSessionForPayment` 返回 NoMatch → `recordOrphanPaymentSignal(NO_ACTIVE_SESSION)`。

### 2.1 次级根因（真机逐轮复现发现）

| # | 问题 | 表现 |
|---|---|---|
| 1 | 结算页指纹高频漂移（倒计时/控件刷新约 2 秒一变） | 每次漂移都新建会话并 `discard` 旧槽快照，切微信时最后一张截图尚未完成 → `NO_BUFFERED_SNAPSHOT` |
| 2 | 意图上下文指纹领先于槽内实际快照 | `PAYMENT_APP_HANDOFF` 用漂移后的指纹消费 → `NO_MATCHING_PAGE_FINGERPRINT` |
| 3 | 支付交接栅栏无 TTL | 挂起快照过期被清理后栅栏残留，槽存在但候选为空时仍拒绝消费 |
| 4 | PDD 存在两种结算页形态 | 原生形态可自动 `CHECKOUT_STABLE` 截图；`wd2.f0` WebView 形态无障碍节点读不到金额/动作信号，只能靠悬浮按钮手动截图（`USER_RETRY`）兜底 |

## 3. 修复内容（0.0.48）

| 文件 | 修复 |
|---|---|
| `CheckoutAccessibilityService.kt` | 同购物包 `NON_CHECKOUT_PAGE`（支付中间页）不再清空稳定结算引用（服务层 Rejected 分支与 onInvalidated 回调双处保留）；`CheckoutStabilityCoordinator.invalidate` 返回是否分发，被复位吞掉的 `PAYMENT_APP_HANDOFF` 由 `dispatchPaymentHandoffIfNeeded` 手动补发（同一支付包 5 秒节流）；补发时若快照缺失则 `ensureSessionForHandoffRedispatch` 用交接引用预写结算会话；支付交接诊断/上下文日志按秒采样防日志风暴；支付结果页证据三处孤儿路径（交接引用失效/路径二 NoMatch/通知 reference 为空）新增"最近活跃结算会话"DB 兜底 |
| `CheckoutSnapshotCaptureCoordinator.kt` | `handleInvalidated` 将同包 `NON_CHECKOUT_PAGE` 视作"预交接"：挂起保留当前结算会话与快照（`retainForPaymentHandoff` + `handoffCheckout`），等待切支付应用或回到结算页；挂起后 2.5 秒内的同包指纹漂移尾波稳定事件不再销毁挂起会话 |
| `CheckoutPurchaseIntentCoordinator.kt` | 同包 `NON_CHECKOUT_PAGE` 不再清空 `activeContext`，保留至 `PAYMENT_APP_HANDOFF` 消费；交接指纹优先使用挂起上下文的原始指纹；`resolveHandoffFingerprint` 在意图指纹与槽内唯一候选不一致时校正为真实快照指纹（不弱化指纹校验，仍指向槽内真实快照）；`handoffAttempted` 只在快照真正被消费后置位，Rejected 不消耗重试机会 |
| `CheckoutSnapshotBuffer.kt` | 支付交接栅栏只保护仍存在的候选：候选为空时放行至 `NO_BUFFERED_SNAPSHOT`/`SNAPSHOT_EXPIRED` 真实语义，不再被残留栅栏误拒为 `NO_MATCHING_PAGE_FINGERPRINT`；新增栅栏拒绝/指纹不匹配/页面角色三处诊断日志 |
| `CheckoutPaymentMatcher.kt` | 新增 `findRecentCheckoutReferenceForPayment`：窗口内唯一活跃 CHECKOUT 会话兜底（覆盖 AI 已派发但内存引用与快照均失效的场景） |
| 版本号 | `build.gradle` / `package.json` / `package-lock.json`：0.0.47 → 0.0.48 |

## 4. 真机验证记录（0.0.48）

### 4.1 无支付复现（8 轮，覆盖两种结算页形态）

- 原生形态：`截图状态: STORED, 来源=CHECKOUT_STABLE` → 切微信
  `支付交接诊断: currentStable=true, handoffStable=true, coordRef=<uuid>`、
  `支付交接上下文: finalRef=<同一 uuid>`、`支付交接事件补发`、
  `支付交接会话预写入: session=<同一 uuid>`。
- WebView 形态（`wd2.f0`）：悬浮按钮手动截图 → `MANUAL_CAPTURE` →
  `USER_RETRY Accepted` → `结算页 AI 派发` → `购买意图已派发 AI` →
  `AI 结果匹配落库结果: Accepted(candidateId=...)`。

### 4.2 真实支付两笔

第一笔 17.80 元（微信支付成功页无障碍节点为空，截图 AI 兜底解析）：

```
10:01:07.998 支付交接诊断: payment=com.tencent.mm, currentStable=true, handoffStable=true, coordRef=62b79b36
10:01:07.998 支付交接上下文: buffered=true, aiRef=true, finalRef=62b79b36-...
10:01:10.530 支付结果页证据落库结果（交接上下文）: Accepted(sessionId=62b79b36, status=WAITING_ENRICHMENT, matched=false, candidateId=candidat…, evidenceId=payment-ab4c)
```

第二笔 29.80 元（截图兜底 + 无障碍成功页双证据，同一会话去重正常）：

```
10:22:41.340 支付交接诊断: currentStable=true, handoffStable=true, coordRef=0541e2b6
10:22:41.523 支付交接事件补发
10:22:41.544 支付交接会话预写入: session=0541e2b6
10:22:43.391 支付结果页证据落库结果（交接上下文）: Accepted(sessionId=0541e2b6, candidateId=candidat…, evidenceId=payment-975e)
10:22:46.846 支付结果页解析到证据: 金额=2980分, 状态=SUCCESS, textLength=21
10:22:46.875 支付结果页证据落库结果（交接上下文）: Accepted(sessionId=0541e2b6, evidenceId=payment-c477)
```

### 4.3 待确认中心

- 两笔真实支付均显示金额/商户/时间完整，冲突列不再显示"未关联结算页"；
- 同金额无重复待确认；
- 两笔均未自动入账（结算页 AI 因指纹漂移未派发，候选为 EVIDENCE_ONLY，
  按设计走待确认由用户确认，符合"AI 失败时必须关联 session/candidate"的验收口径）。

## 5. 已知残留风险

1. **收银台截图误判**：无支付复现中，支付结果页截图 AI 兜底可能把微信收银台
   （未支付）误判为成功页，产生一条关联会话的假待确认（用户忽略/删除即可，
   不会自动入账）。客户端已强制校验 AI 返回 `paymentStatus=SUCCESS`，属模型误判，
   后续可通过 prompt 收紧或增加"支付完成信号"前置降低。
2. **自动 AI 派发不稳定**：原生结算页形态下指纹高频漂移仍可能导致
   `PAYMENT_APP_HANDOFF` 消费失败（`NO_BUFFERED_SNAPSHOT`）。此时由
   "会话预写入 + EVIDENCE_ONLY 候选 + 支付证据金额"三层兜底保证关联与金额完整，
   商品级 AI 识别质量（自动入账）留待后续按"指纹相似度同会话"优化。
3. **WebView 形态结算页**（`wd2.f0`）无法自动识别金额/动作信号，依赖悬浮按钮
   手动截图（该路径已验证完整闭环）。

## 6. 防误记机制核查

支付包白名单、页面指纹校验（栅栏仅调整保护时机，未取消校验）、
`sourceEventKey` 去重（双证据同会话幂等）、5 分钟时间窗口、
截图兜底 15 秒节流与 90 秒成功冷却、只扫 active/focused 窗口——均未弱化。
