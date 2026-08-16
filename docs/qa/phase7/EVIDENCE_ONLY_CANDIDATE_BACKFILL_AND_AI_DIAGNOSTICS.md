# 阶段七：证据先到候选兜底、支付交接稳定与 AI 生命周期诊断

## 背景与真机现象

版本 0.0.46 真机支付 5.90 元后，logcat 显示支付结果页解析正常：

- `支付结果页解析到证据: 金额=590分, 状态=SUCCESS`
- 截图 AI 兜底也能解析出 590 分
- 但落库结果为 `Accepted(status=WAITING_ENRICHMENT, matched=false, candidateId=null, ...)`

即会话已经通过交接引用建立，但当时没有 AI 候选，待确认交易只挂 sessionId、candidateId 为空。

另一个关键现象：

- 进入微信支付时 `支付交接诊断: currentStable=false, handoffStable=false`
- 但截图协调器仍有 `coordRef=8c1c910c...`，所以支付证据没有丢失
- 说明稳定结算页在切到微信前被提前清空了（拼多多提交订单后存在一段内部支付中间页）

## 修复内容（0.0.47）

### 1. 稳定结算页不再被中间页提前清空

`CheckoutAccessibilityService` 收到同一购物 App 内新指纹且被判定为结算页时，不再立即清空
`currentStableCheckout`。只有稳定窗口真正确认新页面后，才会替换旧结算页。这样拼多多
内部支付中间页切到微信时，手头正在提交的结算会话仍然存在。

### 2. 支付证据先到 AI 尚未返回时建立 EVIDENCE_ONLY 候选

`CheckoutPaymentMatcher.recordPaymentEvidence` 在候选为空时不再只写纯通知待确认，而是：

1. 用支付证据创建 `decision=EVIDENCE_ONLY` 的临时候选；
2. 金额、平台、商户（缺失时按购物包名兜底）、证据金额/订单号都写入候选；
3. 会话 `candidateId` 指向该候选；
4. 待确认交易同时关联 `candidateId + sessionId`。

后续 AI 结果到达时不会被重复请求逻辑跳过：

- AI 成功：覆盖临时候选字段、补齐商品描述并正常进入匹配/自动入账；
- AI 失败：回填失败原因和重试入口。

### 3. 历史证据会话补齐

`backfillEvidenceOnlyCandidates()` 扫描 `WAITING_ENRICHMENT`、`PAYMENT_CONFIRMED`、
`NEEDS_CONFIRMATION` 会话中尚无候选但已有成功支付证据的记录，统一补建候选和待确认关联。
真机升级到 0.0.47 后日志：

- `支付证据历史会话补齐候选: count=2`

### 4. 购买意图与 AI 派发诊断

新增日志：

- `购买意图结果: Accepted/Rejected/...`
- `购买意图已派发 AI: session=..., intent=..., requestKey=...`
- `结算页 AI 派发: ...`
- `结算页 AI 任务被取消/任务结束: ...`
- `Accepted` 日志补充 sessionId 前缀，便于核对证据是否落在同一会话。

## 安装验证记录

- 真机设备：25128PNA1C（Android 17 / HyperOS）
- 包名：`com.jotbill.app`，版本 0.0.47 / versionCode 47
- APK：`android/app/build/outputs/apk/release/app-release.apk`
- 安装后 MainActivity 正常 resumed，无障碍服务已重新启用并连接
- 历史补齐执行成功：`count=2`

## 注意事项

- **真机安装必须使用 release APK**。直接安装 debug APK 且本地未启动 Metro 时，
  RN 会因找不到 `index.android.bundle` 直接崩溃（`Unable to load script`）。
- MIUI 在 APK 升级后会清除该应用的辅助功能授权，安装后需要重新打开
  “已安装的服务”并勾选 JotBill，或通过 `settings put secure enabled_accessibility_services`
  重新加入组件后重启应用。
