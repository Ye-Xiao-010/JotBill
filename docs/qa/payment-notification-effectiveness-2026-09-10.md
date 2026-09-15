# 支付通知链路有效性诊断（2026-09-10）

> **问题来源**：用户反馈"似乎没有见到支付通知发挥作用的场景"。
> **结论**：**不是观感问题——真机证据显示通知链路在本项目全部验证中从未产出过支付证据**，且原因是三层叠加，其中第一层是结构性限制而非代码缺陷。
> **证据基线**：`docs/qa/device/2026-09-10-e-detail-buy-fullchain.logcat.txt`（583,459 行，覆盖 16:4x~17:10，含一笔 ¥55.00 真实支付）；`docs/qa/device/2026-09-10-d-z2-pass-and-y5-wedge.logcat.txt`；`docs/qa/device/2026-09-10-z1-a-spec-layer.logcat.txt`。代码基线 `HEAD 40f10e9`。

---

## 一、核心证据：整段日志中微信/支付宝零通知投递

对 E 场景日志按通知源做全量统计（系统侧 `NotifySyncService: onNotificationPosted ... appName : X`）：

| appName | 条数 |
|---|---|
| 小米汽车互联服务 | 4 |
| Android 系统 | 3 |
| 小米澎湃 AI 引擎 | 2 |
| 小米互联通信服务 | 2 |
| 钉钉 | 1 |
| **拼多多** | **1** |
| **JotBill** | **1** |
| **微信** | **0** |
| **支付宝** | **0** |

⇒ **在包含一笔 ¥55.00 真实支付的整段日志里，微信与支付宝一次系统通知都没有投递。**

### 为什么没有通知：支付全程在微信前台完成

`2026-09-10-e-…logcat.txt` 铁证链：

| 时间 | 证据 |
|---|---|
| 17:09:16.587 | `AppStartScenario: notifyScenarioChanged ... cmp=com.tencent.mm/.plugin.base.stub.WXPayEntryActivity, callerPackage=com.xunmeng.pinduoduo` —— **微信支付页由拼多多拉起到前台** |
| 17:09:16.855 | `cmp=com.tencent.mm/.plugin.wallet_index.ui.OrderHandlerUI` —— 进入微信订单收银台，仍在**前台** |
| 17:09:16.866 | `ProcessManager: skip notify foregroundAppInfo: com.tencent.mm` —— 微信为前台应用 |
| 17:09:21.806 | `支付结果页解析到证据: 金额=5500分, 状态=SUCCESS, textLength=21` |
| 17:09:21.837 | `支付结果页证据落库结果（交接上下文）: Accepted(sessionId=23eafff1, status=WAITING_ENRICHMENT, matched=false, …)` |
| 17:09:22.202 | JotBill **自己**发通知：`title: 有一笔交易待确认, text: ¥55 · 支出 · 支付结果页` |

**判定**：Android 通知机制下，应用处于前台时不需要投递提醒通知 ⇒ 购物 App **跳转式支付**（拼多多 → 微信/支付宝内支付）几乎不可能产生支付通知。这是**形态限制**，与代码无关。

### 附带铁证：用户"见到的通知"其实是 JotBill 自己发的

17:09:22.202 那条待确认通知的文案里明写 **"支付结果页"** —— 即证据来源标注为**无障碍结果页路径**。也就是说：
- **起作用的是结果页路径**（17:09:21.806 解析 → 21.837 Accepted）；
- **不是通知路径**（该时段微信通知数 = 0）。

---

## 二、第二层：MIUI 反复杀服务并拒绝重启

即便有通知，也存在收不到的风险。E 场景日志中的服务生命周期：

| 时间 | 事件 |
|---|---|
| 16:42:59.044 | `NotificationListeners: Removing active service ComponentInfo{com.jotbill.app/…PaymentNotificationService}` |
| 16:42:59.045 | `AutoStartManagerService: MIUILOG- **Reject RestartService** service :ComponentInfo{…PaymentNotificationService}` |
| 16:42:59.046 | `NotificationListeners: **0** notification listener binding died` |
| 16:43:09 | 服务重新创建（pid 24155）→ `SmartPower: … R(service create …PaymentNotificationService)` |
| 16:43:47.995 | 再次 `0 notification listener binding died` |
| 16:43:48.007 | `AutoStartManagerService: MIUILOG- **Reject service** :Intent { act=android.service.notification.NotificationListenerService … }` + `NotificationListeners: AutStart **Unable to bind** notification listener service` |
| 16:44:16 / 16:44:22 | 同上（binding died → Reject service → Unable to bind） |
| 16:44:26 | 创建并连接（pid 28881） |
| 16:49:15.405 | 移除 + **Reject RestartService** |
| 16:49:25.422 | 创建并连接（pid **29090**，此后无 Service 生命周期日志） |
| 17:09:16~22 | 支付发生；进程 29090 仍活跃（大量 `CheckoutAccessibilityService` 日志） |

**要点**
- 系统**确实把 JotBill 注册为通知监听器**（`ComponentInfo{com.jotbill.app/…}` 出现在 `NotificationListeners` 中）⇒ **"通知使用权"已授予**，Manifest 声明正确（`BIND_NOTIFICATION_LISTENER_SERVICE` + `exported="false"` + `default_filter_types` 含 silent，`AndroidManifest.xml:58-71`）。
- 但 MIUI 的 `AutoStartManagerService` **多次拒绝绑定/重启**，`0 notification listener` 表明监听器存活数为 0。
- **可辨性边界**：17:09 支付时进程 29090 存活，但"绑定是否仍在"**无法从本日志断言**（binding 可能先于进程死亡）。⇒ 本层是**叠加风险**，非本次零产出的确证原因；本次确证原因是第一层（无通知投递）。

**需要的前置配置**（MIUI 专项）：自启动白名单 + 省电策略"无限制" + 通知使用权；三者缺一即可能收不到。

---

## 三、第三层：唯一的监听器"干活"记录是一次拒绝

全库检索（所有 `docs/qa/device/*.txt` 与 `.tmp-*.logcat.txt`）：

| 检索项 | 命中 |
|---|---|
| `解析到支付证据` / `解析到退款证据` / `解析到转账` / `解析到手续费` / `支付证据处理完成` | **0** |
| `通知未通过解析（非消费或金额无效）` | **1**（`2026-09-10-z1-a-spec-layer.logcat.txt:21692`，15:15:29.778） |

15:15:29 那一条的上下文（同日志 `:21685`）：`NotifySyncService: onNotificationPosted Id:-2000893148 , appName : 微信`，1ms 后 JotBill 记录"未通过解析"。

⇒ **至少证明监听器能收到并处理微信通知**；但内容被判定为"非消费或金额无效"。**该条是否为支付通知无法从日志判定**（JotBill 刻意不记录通知原文，只记 `title_digest` 到 Sentry）。因此这条**不能**定性为"支付通知被误杀"，只能定性为"链路可通、该条未通过解析"。

---

## 四、那通知路径到底覆盖什么场景？

通知路径的设计定位是**兜底**，其真正的覆盖面与当前验证形态**恰好相反**：

| 通知路径擅长的场景 | 当前真机验证的场景 |
|---|---|
| 扫码付 / 被扫（用户停在微信/支付宝扫一扫，不产生购物 App 交接） | ❌ 购物 App 跳转支付 |
| 免密代扣、自动续费、订阅扣款（无任何前台页面可读） | ❌ 有结算页与支付结果页 |
| 锁屏 / 息屏状态完成支付 | ❌ 全程亮屏操作 |
| 支付后**立刻退回**、不留结果页 | ❌ 停在结果页 |
| 结果页节点树不可读 **且** 无交接上下文（兜底被 §五 门槛禁止） | ❌ 有交接上下文 |

⇒ **现有全部验证都是"跳转式支付"，而这正是通知路径最不擅长的形态**。缺的不是验证轮次，而是**换一种支付形态**去验。

---

## 五、顺带发现的两条 E 场景缺陷（与通知问题无关）

| # | 现象 | 证据 |
|---|---|---|
| 1 | **熔断-重建循环**：`reason=SERVICE_DESTROYED` 反复熔断协调器，`pendingCapture=CHECKOUT_STABLE` 被 CANCELLED | `16:50:56.728`、`16:51:10.907`、`16:51:39.836` 三次；每次随后"协调器重建完成，截图能力已恢复" |
| 2 | **系统级截图撞车真实复现**：断言截图与结果页截图 AI 抢同一个系统截图 API | `17:09:17.322` 结果页兜底取得 62501 bytes → `17:09:17.442` `截图状态: UNAVAILABLE, 原因=SCREENSHOT_BUSY`；同段另有 `17:09:18.779 支付结果页截图兜底未生成证据: code=JSON_SCHEMA_INVALID, attempts=1` |

第 2 条正是 `docs/qa/checkout-manual-capture-priority-fix.md` 第四节残留风险 1 所述的"抢占的系统级撞车窗口"——**真机已复现**，属该风险的第二个数据点。

---

## 六、建议的验证动作

1. **前置（否则测了也白测）**：MIUI 设置 → 应用管理 → JotBill → 自启动允许、省电策略"无限制"、通知使用权开启；随后 `adb shell dumpsys notification_manager --noredact | grep -A3 jotbill` 确认监听器在 connected 列表内。
2. **造一个"通知应当生效"的场景**（任选其一，越靠前越能隔离变量）：
   - **锁屏状态下**用微信/支付宝扫码付款（或让他人扫你的付款码）→ 应有通知；
   - 微信**扫一扫**付款后**立刻按 Home 退回桌面**（微信转后台 → 触发通知）；
   - 小额**免密代扣/自动续费**（无前台页面）。
3. **判定点**：`adb logcat -s PaymentNotificationService` 应出现 `解析到支付证据`；随后确认证据落库去向（会话匹配 / 孤立待确认）。
4. **对照实验**：同一支付在"跳转式"（拼多多→微信）与"扫码式"下各做一次，量化通知路径的**实际覆盖率**。

---

## 七、结论汇总

| 层 | 判定 | 是否代码缺陷 |
|---|---|---|
| 一：跳转式支付不产生通知 | ✅ 确证（微信通知投递 = 0） | ❌ 形态限制 |
| 二：MIUI 拒绝绑定/重启监听器 | ✅ 确证现象；对本次零产出的因果性**不可断言** | ⚠️ 厂商适配/配置 |
| 三：链路本身可通 | ✅ 确证（15:15 收到并处理过微信通知） | ❌ |
| 四：验证形态与通知覆盖面错位 | ✅ 确证 | ❌ 验证设计问题 |

**一句话**：**不是通知没生效，而是当前所有验证场景都不产生通知；通知路径要生效，必须换"非跳转式支付"形态去验，并先打通 MIUI 自启动/省电白名单。**
