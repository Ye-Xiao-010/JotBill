# 阶段七真机修复：结算悬浮按钮闪烁与支付结果漏记

> 状态：**已修复并装机（0.0.29），支付结果链路待真机再次支付复测**
>
> 设备：25128PNA1C（MIUI/HyperOS），adb serial `4a6e283b`
>
> 修复前版本：0.0.28；修复后版本：0.0.29（versionCode 29）

## 1. 真机现象

1. 在拼多多结算/确认订单页，JotBill 手动截图悬浮按钮以无法点击的频率闪烁。
2. 完成一次拼多多 + 微信支付并在支付成功页停留后，JotBill 没有生成账单、活跃会话或待确认交易（待确认中心为 0）。

## 2. 根因

### 2.1 悬浮按钮闪烁

- 拼多多页面会高频发出 `TYPE_WINDOW_CONTENT_CHANGED`，其中混有
  `com.android.systemui`、`com.jotbill.app` 自身悬浮窗、视频通话悬浮窗以及
  `packageName` 为空的事件。
- 旧逻辑只读取 `event.packageName`：空包名或第三方窗口事件被直接判成
  `LEFT_SUPPORTED_SHOPPING_APP`，每收到一次就 `removeView()`；
  下一帧又收到拼多多事件并重新 `addView()`，形成新窗口反复创建/销毁的闪烁。
- 真机 WindowManager 日志曾出现每分钟数十次
  `ENTER_FLOAT_WINDOW / EXIT_FLOAT_WINDOW`，窗口尺寸与本按钮完全一致
  （168×168，x=36，y=660），确认闪烁来自结算截图按钮自身。

### 2.2 支付成功漏记

- `PaymentNotificationService` 清单只声明
  `android.service.notification.default_filter_types="alerting"`，
  微信/支付宝的支付凭证经常是静默通知，通知监听器根本收不到，通知兜底失效。
- 微信支付成功页由 `WxaLiteAppTransparentLiteUI` 等透明 LiteApp 承载，
  `rootInActiveWindow` 只是 `OrderHandlerUI` 的空壳；真机复测日志显示
  `role=UNKNOWN`，而真正带语义的节点在 `windows` 列表的同包名窗口里。
- 该成功页把 `支付成功 / 拼多多 / ￥27.45 / 返回商家` 全部放在节点的
  `contentDescription`，`text` 为空；旧解析只收集 `node.text`，即使选对根节点
  也拿不到证据。
- 购物应用已切到微信/支付宝、但事件包名仍是购物包时，旧代码会把根节点包名不一致
  当成 `NODE_TREE_UNAVAILABLE`，先清空结算页交接引用；随后支付结果页即使
  可读，也没有会话引用可用。

### 2.3 MIUI onInterrupt 僵尸服务

- MIUI 语音助手等系统组件会触发无障碍服务 `onInterrupt()`，但不一定随后调用
  `onServiceConnected()`。旧实现把 `serviceConnected=false`、AI/匹配器、
  RN 回调和 static activeService 全部拆除；系统保持“已绑定”状态时，
  服务不再处理任何事件，只能手工开关无障碍服务恢复。

## 3. 修复内容

| 文件 | 修复 |
|---|---|
| `CheckoutAccessibilityService.kt` | 事件包名解析改为“事件包名 + 活动窗口根包名 + 最近已知包名”综合判定；购物→支付根节点切换按 `PAYMENT_APP_HANDOFF` 保留会话引用；支付结果页改为候选根节点列表（活动窗口 + 事件源 + 全部同包名窗口），逐个解析直到命中 `PAYMENT_RESULT`；结果页文本同时收集 `text` 与 `contentDescription`；增加按秒采样诊断日志；`onInterrupt()` 只结束当前旅程，新增 `onUnbind()` 做真正断连清理 |
| `PaymentResultParser.kt` | 增加微信 LiteApp 成功页 `支付成功 <商户> ￥金额` 形态的商户提取正则；补内容描述-only 成功页单元测试 |
| `CheckoutCaptureOverlayManager.kt` | 隐藏前保留 1.5 秒宽限期，期间禁用按钮但复用同一窗口；页面恢复则取消隐藏；相同状态不再重复刷新和重复发事件；开关关闭/服务断开才立即移除窗口 |
| `AndroidManifest.xml` | 通知监听过滤类型从 `alerting` 改为 `alerting\|silent`，接收静默支付凭证 |
| `build.gradle` / `package.json` / `package-lock.json` | 版本号 0.0.28 → 0.0.29 |

## 4. 已完成的真机验证

### 4.1 悬浮按钮


- 0.0.29 安装后，无障碍服务与通知监听权限均保留，服务处于绑定状态。
- 拼多多“确认订单”页停留 30 秒：窗口列表中始终只有一个 JotBill 悬浮窗；
  日志中事件包名在 `com.xunmeng.pinduoduo`、`com.android.systemui`、
  `com.jotbill.app` 之间交错，但有效归属包名持续为 `com.xunmeng.pinduoduo`，
  不再出现 add/remove 循环。
- 证据：
  - `docs/qa/phase7/device/2026-08-16-pdd-checkout-overlay-stable.png`（已裁剪，仅保留按钮区域）
  - `docs/qa/phase7/device/2026-08-16-pdd-checkout-overlay-stable.xml`（已清空 text/content-desc，避免个人地址与姓名）
  - `docs/qa/phase7/device/2026-08-16-pdd-checkout-overlay-stable.logcat.txt`

### 4.2 支付成功页诊断

- 21:54 的真实拼多多 + 微信支付复测中，`CheckoutAccessibilityService` 持续收到
  微信包事件，但所有活动窗口都被分类为 `role=UNKNOWN`；同一时刻 UI 层级显示
  `WxaLiteAppTransparentLiteUI` 的 4 个节点分别以 content-desc 提供
  `支付成功 / 拼多多 / ￥27.45 / 返回商家`，确认“选错根节点 + 只读 text”双重漏因。
- 证据：
  - `docs/qa/phase7/device/2026-08-16-pdd-wechat-payment-result-diagnosis.logcat.txt`

## 5. 待复测

- 用户再完成一笔真实的拼多多 + 微信支付，并在支付成功页停留数秒：
  1. 打开 JotBill“待确认中心”，确认出现待确认交易或已入账账单；
  2. 保留复测期间的 `CheckoutAccessibilityService` 与
     `PaymentNotificationService` 日志。
- 期望日志从 21:54 的 `role=UNKNOWN` 变为
  `支付结果页解析到证据: 金额=2745分, 状态=SUCCESS`，并出现匹配或待确认落库结果。
- 若仍未命中，新日志会分别列出每个候选窗口的 role，据此继续排查窗口可见性。
