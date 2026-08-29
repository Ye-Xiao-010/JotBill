# 待确认提醒链路:日志定位指南

> 建立日期:2026-08-29(批 1 兜底闭环)
> 目的:真机调试时,通过 logcat 日志快速圈定问题发生在链路的哪一段。
> 隐私约定:本链路日志只含金额(分)、类型、来源、ID 前 16 位,**不含商户名与订单号**。

## 一、链路总览

```
支付发生
  ├─ 路径A:支付结果页(无障碍) CheckoutAccessibilityService
  │    observePaymentResultIfApplicable → recordPaymentResultEvidence
  │    → matcher.recordPaymentEvidence(有会话) / recordOrphanPaymentSignal(无会话)
  └─ 路径B:支付通知 PaymentNotificationService
       onNotificationPosted → 解析到支付证据 → handlePaymentEvidence
       → tryFallbackSignal(无障碍侧尝试消费快照)
            ├─ Accepted/AiDispatched:证据随会话落库(无障碍侧)
            └─ 被拒/超时/不可用:调用方兜底 recordOrphanPaymentSignal(:261-282)

两条路径最终都汇聚到:
  CheckoutPaymentMatcher / RefundLinker / TransferClassifier / FeeExtractor / ImportEngine
       → pending_transactions 表落库(insertIfAbsent 幂等)
       → PendingChangeHub.emit(进程内事件)
            ├─ PendingReminderNotifier → 系统通知"有一笔交易待确认"
            └─ DataModule → JS 事件 onPendingCreated → React Query 失效 → 待确认中心刷新
```

## 二、关键日志行(tag → 含义)

| Tag | 日志消息(片段) | 含义 | 缺失说明什么 |
|---|---|---|---|
| `PaymentNotificationService` | 解析到支付证据: 包名= | 通知已解析出金额/时间 | 通知没被解析:检查排除关键词或金额正则 |
| `PaymentNotificationService` | 支付通知回退不可用(...),已转纯通知待确认 | 无会话/被拒后,调用方兜底落库 | 若无此行且无下文,查 `支付证据已由无障碍链路落库` |
| `PaymentNotificationService` | 支付证据已由无障碍链路落库,跳过纯通知兜底 | 双保险去重生效 | 正常 |
| `PaymentNotificationService` | 支付通知无结算会话可匹配,已转纯通知待确认 | 有会话上下文但引用失效,无障碍侧兜底 | 正常(降级路径) |
| `CheckoutPaymentMatcher` | 孤立支付证据已落待确认(并发会话) | 孤儿 pending 落库(多会话歧义分支) | 数据库层失败,查 W 级 `纯通知待确认写入失败` |
| `CheckoutPaymentMatcher` | 孤立支付证据已落待确认: ... inserted=true | 孤儿 pending 落库成功(inserted=false 为幂等跳过) | inserted=false → 同一笔支付重复处理,正常 |
| `RefundLinker` / `TransferClassifier` / `FeeExtractor` | 各自 outcome 日志 | 退款/转账/手续费分类器决策 | 通知解析成 REFUND/TRANSFER/FEE 后走各自链路 |
| `PendingChangeHub` | pending变更事件: id=..., type=..., amount=..., isNew= | 事件分发中心收到创建点通知 | 有落库日志但无此行 → 该创建点未接 hub |
| `PendingChangeHub` | 无消费者,事件丢弃(数据已持久化) | RN 未启动且通知器未注册时的正常降级 | 数据不丢,仅提醒缺失 |
| `PendingReminder` | 待确认提醒已初始化: channel=pending_reminder | Application 启动时渠道与监听就绪 | 缺失 → MainApplication.init 未执行 |
| `PendingReminder` | 跳过提醒: 内容更新事件 / 0 金额占位条目 / 用户已关闭开关 | 三类正常跳过 | 按需核对 |
| `PendingReminder` | 跳过提醒: 应用通知权限未授予 | API 33+ 运行时权限未批 | 引导用户在系统设置开通知 |
| `PendingReminder` | 节流抑制提醒: suppressed=N | 2 秒窗口内多笔支付被合并 | 正常防刷屏 |
| `PendingReminder` | 待确认提醒已发送: id=..., amount=... | 系统通知已 post | 有此行但通知栏无 → 查系统侧(免打扰/渠道关闭) |
| `DataModule` | pending 事件推送 JS 失败(RN 可能未就绪) | JS 事件旁路失败(数据不丢) | RN 未启动时出现属正常 |

## 三、按症状排查

**症状:支付完成了,但待确认中心没有记录**
1. `adb logcat -s PaymentNotificationService` → 有无"解析到支付证据"?无:通知格式未被解析(伴 `通知未通过解析` Sentry breadcrumb),或通知监听服务未运行/权限丢失。
2. 有解析 → 查 `CheckoutPaymentMatcher` / `RefundLinker` 等落库日志。无:数据库写入异常(W 级别日志)。
3. 有落库 → 查待确认中心查询条件(状态=pending),或应用版本是否含本功能。

**症状:待确认有记录,但没收到系统通知**
按序查 `PendingReminder` tag:初始化 → 跳过原因(更新事件/0金额/开关关闭/权限)→ 节流 → 已发送,逐行比对上表。

**症状:待确认中心数据不实时**
查 `PendingChangeHub` 是否有事件、`DataModule` 是否推送失败(RN 未就绪时属正常,进 App 后下拉刷新兜底)。

**症状:同一笔支付出现两条待确认**
比对两条的 id 前缀:不同 `stableId` 来源(如 pending-orphan 与 pending-ambiguous)说明出现并发会话歧义,记录 `sessions=N` 日志供分析。

## 四、调试命令

```bash
# 提醒链路全量
adb logcat -s PendingReminder PendingChangeHub DataModule

# 证据链路全量
adb logcat -s PaymentNotificationService CheckoutPaymentMatcher CheckoutAccessibilityService

# 组合(推荐日常调试)
adb logcat -s PendingReminder:* PendingChangeHub:* PaymentNotificationService:* CheckoutPaymentMatcher:*
```
