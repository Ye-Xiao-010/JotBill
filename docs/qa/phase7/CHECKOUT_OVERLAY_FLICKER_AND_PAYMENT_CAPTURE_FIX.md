# 阶段七真机修复：结算悬浮按钮闪烁与支付结果漏记

> 状态：**0.0.31 真机复测通过**
>
> 设备：25128PNA1C（MIUI/HyperOS），adb serial `4a6e283b`
>
> 修复前版本：0.0.28；最终验证版本：0.0.31（versionCode 31）

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

## 5. 真机复测结论（0.0.31）

- 结算页悬浮按钮：22:30:54 真机手动点击，日志 `enabled=true` →
  `手动截图点击已受理` → `截图状态: STORED, 来源=MANUAL_CAPTURE`，按钮可点击。
- 微信支付成功页：`flagIncludeNotImportantViews` 生效后，无障碍节点成功读到
  `支付成功` 语义，日志 `支付结果页解析到证据: 状态=SUCCESS, textLength=21`；
  同时截图 AI 兜底也成功解析出同一笔金额，形成双保险。
- 待确认中心：出现 1 条“支出 / 来源=无障碍节点 / 平台=com.tencent.mm /
  商户=拼多多 / 未关联结算页，请核对后确认”的待确认交易，金额与实付一致。
- 证据：
  - `docs/qa/phase7/device/2026-08-16-payment-result-capture-verified.logcat.txt`（金额已脱敏）

## 6. 后续优化

- 同一支付结果页会随多个内容变化事件重复解析；落库已按 sourceEventKey 幂等，
  可进一步在服务层做按页面指纹的短窗口去抖，减少日志与数据库查询。
- 手动截图触发 AI 识别仍可能因截图不是标准结算页而 FAILED；此时支付结果页
  兜底会把正确金额写入待确认，不影响最终记账闭环。
