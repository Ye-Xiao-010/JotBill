# 远程用户 2 · 钱迹能弹、本应用不能 —— HyperOS GreezeManager 冻结取证（2026-10-29）

> **证据（仓库外，勿复制进仓库）**
> 全机日志：`D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\bugreport-2026-10-28-233145\`
> `bugreport-Xiaomi 15 Ultra-2026-10-28-233444.zip` → 主文件
> `bugreport-xuanyuan-BP2A.250605.031.A3-2026-10-28-23-31-45.txt`
> （3,525,579 行 / 179 MB；下文行号均为该文件内行号，仅供定位，符号定位优先）
> 钱迹内部日志两条（用户提供）：23:26:47 支付宝 2.60 元 / 23:29:45 微信-拼多多 12.80 元。
>
> **设备**：Xiaomi 15 Ultra（xuanyuan）/ Android 16 / HyperOS 3.0.314.0.WOACNXM
> （build fingerprint `Xiaomi/xuanyuan/xuanyuan:16/BP2A.250605.031.A3/OS3.0.314.0.WOACNXM`）。
> 同机启用的无障碍服务：钱迹、JotBill、（小米）钱包AI记账、GKD，共 4 个。
>
> 本文为**新增分析**，不修改任何历史文档；结论与 2026-10-29 remote-user1 的
> “HyperOS 事件抑制”假说部分冲突，见 §3。

---

## 结论先行

**不是系统屏蔽了无障碍事件，而是我们的进程在支付那一刻被 HyperOS 的
`GreezeManager` 冻结了。**

- 23:29:38.612 我们被冻结，**冻结时主线程正卡在 `scanPaymentResultCore → collect` 里**
  （23:32:18 的 SIGQUIT 线程转储可证）。
- 钱迹在同机、同一支付、同一时刻**全程未被冻结**，靠的是一个服务连接时就建立的
  **1×1 `TYPE_ACCESSIBILITY_OVERLAY` 常驻窗口**：系统因此始终判定它
  `VISIBLE_APP`，`GreezeManager` 直接跳过（`was show on screen, skip it`）。
- 我们的弹窗直到 23:32:18（bugreport 抓取发 SIGQUIT 触发解冻）才出现，
  比支付成功页晚 **2 分 33 秒**；30 秒后 `AUTO_DISMISS`，转入待确认中心
  （`pending-orphan-a, amount=1280`）。

**一句话：钱迹“在屏”，我们“被冻”。**

---

## 1. 两台 App 的无障碍配置（dumpsys accessibility，行 839637）

```
Service[label=GKD, capabilities=161,
        eventTypes=[TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED],
        notificationTimeout=100]
Service[label=钱包AI记账-自动省钱助手, capabilities=243, eventTypes=TYPES_ALL_MASK,
        notificationTimeout=10]
Service[label=JotBill, capabilities=129,
        eventTypes=[TYPE_VIEW_CLICKED, TYPE_VIEW_TEXT_CHANGED, TYPE_WINDOW_STATE_CHANGED,
                    TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_SCROLLED],
        notificationTimeout=100]
Service[label=❤️钱迹-自动记账, capabilities=129,
        eventTypes=[TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED],
        notificationTimeout=10]
```

| 项 | 钱迹 | JotBill |
|---|---|---|
| capabilities | 129 = 读窗口内容 + 截图 | 129（相同） |
| 事件类型 | STATE_CHANGED + CONTENT_CHANGED | 5 种（更多） |
| notificationTimeout | 10 ms | 100 ms |
| 组件名 | `com.mutangtech.qianji/com.google.android.accessibility.selecttospeak.SelectToSpeakService` | `com.jotbill.app/...recognition.CheckoutAccessibilityService` |

**能力面我们不吃亏**（甚至更宽），所以差异不在“能不能读”，而在“进程还活着没有”。

---

## 2. 决定性差异：进程冻结

### 2.1 冻结 / 解冻时间线（`ExternalInterface: setUidState` + `GreezeManager`）

`allow=false` 即冻结。语义由相邻行确证：
`GreezeManager: FZ uid = 10367 reason =tobg success !` 后紧跟
`setUidState, uid = 10367 allow = false`（23:24:24.528/.532）；
`THAW` 后紧跟 `allow = true`。

JotBill（uid 10366，pid 27002）：

```
23:23:06.822  allow = true
23:23:37.833  allow = false
23:25:27.830  allow = true     ← THAW reason: BF
23:25:47.947  allow = false    ← 冻结（覆盖 23:26:47 支付宝 2.60 支付）
23:27:52.908  allow = true     ← THAW reason: BF
23:29:38.070  allow = false
23:29:38.078  allow = true     ← THAW reason: fg important（系统为投递支付交接事件临时解冻）
23:29:38.612  allow = false    ← ★ 冻结！扫描进行到第 130 ms
23:32:18.005  allow = true     ← THAW reason: Signal other3（bugreport 的 SIGQUIT）
23:33:40.863  allow = false
23:34:30.638  allow = true     ← THAW reason: Dumping
```

钱迹（uid 10367，pid 23684）：

```
23:23:09–23:24:09  allow = false（每 5 s 一次；此时无障碍服务尚未启动）
23:24:13.278  allow = true      ← THAW reason: Activity Front
23:24:24.532  allow = false     ← FZ reason: tobg success
23:24:35.232  allow = true
23:24:44.311  allow = false
23:24:55.055  allow = true      ← THAW reason: Excute Service（无障碍服务启动）
23:24:55.085  ...               ← ★ 41 ms 后建立 1×1 悬浮窗
此后到日志结束：**再无 allow = false**
```

### 2.2 被冻结时我们在做什么（23:32:18 线程转储，行 575054）

```
----- pid 27002 at 2026-10-28 23:32:18.003989530+0800 -----
"main" prio=5 tid=1 Runnable
  at java.util.regex.Matcher.usePattern(Matcher.java:349)
  at com.jotbill.app.recognition.AccessibilityPageSummaryCollector.normalizeToken(...:600)
  at com.jotbill.app.recognition.AccessibilityPageSummaryCollector.collect(...:321)
  at com.jotbill.app.recognition.CheckoutAccessibilityService.scanPaymentResultCore(...:1024)
  at com.jotbill.app.recognition.CheckoutAccessibilityService.observePaymentResultIfApplicable(...:852)
  at com.jotbill.app.recognition.CheckoutAccessibilityService.onAccessibilityEvent(...:543)
```

对应我们自己的日志：

```
23:29:38.078  THAW（fg important）
23:29:38.481  I CheckoutAccessibilityService: 支付环境主动扫描启动: pkg=com.tencent.mm
23:29:38.587  I CheckoutAccessibilityService: 支付窗口列表(4): 3:com.mipay.wallet:View |
              3:com.miui.home:FrameLayout | 3:com.android.systemui:FrameLayout |
              1:com.xunmeng.pinduoduo:FrameLayout
23:29:38.612  allow = false        ← 扫描到 collect() 中被冻结
（23:29:38.612 → 23:32:18.005 无任何本应用日志）
```

**SIGQUIT 转储的堆栈 = 冻结那一刻的现场**，所以“主线程卡了 2 分 40 秒”
并不成立；真相是**进程被按了暂停键**。这也解释了为什么
`jotbill-diag.log` 在同一时段出现整段空白——冻结中的进程根本写不了盘。

### 2.3 钱迹为什么不被冻结

**（a）常驻 1×1 无障碍悬浮窗**（WM dump，行 32372）

```
Window #0 Window{a209a16 u0 com.mutangtech.qianji}:
  mOwnerUid=10367 showForAllUsers=false package=com.mutangtech.qianji
  appop=CREATE_ACCESSIBILITY_OVERLAY
  mAttrs={(0,0)(1x1) gr=TOP START CENTER pkg = com.mutangtech.qianji
          ty=ACCESSIBILITY_OVERLAY fmt=TRANSLUCENT
          fl=NOT_FOCUSABLE NOT_TOUCHABLE HARDWARE_ACCELERATED
  Frames: parent=[0,169][1440,3200] frame=[0,169][1,170]
```

**（b）识别到支付时临时拉起 specialUse 前台服务**

```
23:26:47.158  GreezeManager: onForegroundServicesChanged pid=23684 uid=10367 fg=true   ← 支付宝
23:26:57.168  ... fg=false
23:29:45.103  GreezeManager: onForegroundServicesChanged pid=23684 uid=10367 fg=true   ← 微信
              （同刻 MyLog: serviceTypes=1073741824 = FOREGROUND_SERVICE_TYPE_SPECIAL_USE）
23:29:48.148  ... fg=false
```

**（c）系统侧判定为 VISIBLE_APP**

```
23:24:18.286  PolicyMaker: uid =10367 pkg=com.mutangtech.qianji reason=VISIBLE_APP
23:25:00.071  GreezeManager: Uid 10367 was show on screen, skip it
23:25:07.470  GreezeManager: Uid 10367 was show on screen, skip it
23:30:34.981  GreezeManager: Uid 10367 was show on screen, skip it
23:30:42.804  GreezeManager: Uid 10367 was show on screen, skip it

23:23:11.825  PolicyMaker: uid =10366 pkg=com.jotbill.app reason=accessibility      ← 不够
23:29:43.087  PolicyMaker: uid =10366 pkg=com.jotbill.app reason=accessibility      ← 不够
23:32:23.007  PolicyMaker: uid =10366 pkg=com.jotbill.app reason=VISIBLE_APP        ← 弹窗出现后
23:32:23.007  GreezeManager: Uid 10366 was show on screen, skip it                   ← 才被跳过
```

**最强旁证：本机 4 个启用的无障碍服务里，另外 3 个都挂着 1×1 保活窗**

| 服务 | 1×1 窗 | 类型 |
|---|---|---|
| 钱迹 | `a209a16` [0,169][1,170] | `ACCESSIBILITY_OVERLAY` (2032) |
| GKD | `4218839` [0,169][1,170] | `ACCESSIBILITY_OVERLAY` (2032) |
| 小米钱包 | `6dc8229` [1439,3199][1440,3200] | `APPLICATION_OVERLAY` (2038) |
| **JotBill** | **无** | — |

也就是说：这不是钱迹一家的偏方，而是这台 ROM 上“自动记账类 App”的**生存前提**。

### 2.4 结果对照

| 时刻 | 钱迹 | JotBill |
|---|---|---|
| 23:26:47 | 支付宝 2.60 识别并弹窗 | 冻结中 |
| 23:29:45.057 | 截图（`shot_1793201385035.jpg`） | 冻结中 |
| 23:29:45.19 | `AutoBillFloatingView` 弹窗上屏（type 2038, 0,0–1440,1697） | 冻结中 |
| 23:29:45.524 | — | 系统侧通知 `微信支付 / 已支付¥12.80`（我们收不到） |
| 23:29:50 | — | 用户按 Home 离开拼多多 |
| 23:32:18.3 | — | 我们的弹窗才创建（`Window{8bf3be7 u0 com.jotbill.app}`） |
| 23:32:48.323 | — | `AUTO_DISMISS` → `pending-orphan-a, amount=1280` 转待确认中心 |

---

## 3. 与既有结论的关系（重要：需要改写“事件抑制”）

`docs/qa/device/2026-10-29-remote-user1/ANALYSIS.md` 把 HyperOS 的行为归因为
“支付期三层隔离：事件抑制 + rootInActiveWindow 不指向收银台 + 窗口文本掏空”，
并据此建议“填观测盲区 / 窗口列表扫描 / 试探路径”。

**本份系统级日志表明：至少“事件抑制”这一层要改写为“进程冻结”。**

- 冻结中的进程**既收不到无障碍事件，也收不到通知回调，也不写诊断日志**；
- 因此 0.0.103 的“主动扫描时钟 / 窗口试探 / 词表扫描”在冻结面前全部无效
  （它们跑不起来），remote-user1 日志里的“试探路径零触发”不是逻辑缺陷；
- 反过来，只要进程活着，钱迹证明了**成功页的节点是读得到的**（它 5 秒内完成
  截图 + 解析 + 弹窗）。所以“窗口文本被掏空”的结论也可能需要重测。

⚠️ 历史文档按约定**加注不覆盖**，本条改写建议待用户裁定后再落笔到
`2026-10-29-remote-user1/ANALYSIS.md` 的头部注记。

---

## 4. 次要差异（本次不是主因，但解冻后仍会挡路）

1. **包名门禁**：我们的支付结果管线只在有效包名 ∈
   `{com.tencent.mm, com.eg.android.AlipayGphone}` 时启动
   （`CheckoutPageDetector.kt:15`、`CheckoutAccessibilityService.kt:533`）。
   本次 23:29 的事件包名是 `com.tencent.mm`（微信 `WxaLiteAppTransparentLiteUI`
   浮在拼多多上层），路由是通的。
   但**拼多多/淘宝/美团等站内原生成功页（包名=购物包）**永远进不了该管线
   —— 即 2026-10-29 remote-user1 分析的“站内支付路由盲区”，解冻后是下一个坑。
2. **notificationTimeout=100ms vs 钱迹 10ms**：次要，10 倍延迟对 5 秒窗口影响有限。
3. **`flagIncludeNotImportantViews`**：钱迹没有该 flag，我们的节点树更大；
   不是本次主因，但会增加采集成本。

---

## 5. 修复建议（按收益排序）

### 5.1 【主修·必做】无障碍服务挂常驻 1×1 保活窗

- 位置：`CheckoutAccessibilityService.onServiceConnected` 建立，
  `onUnbind` / `onDestroy` 移除。
- 参数对齐钱迹/GKD：`TYPE_ACCESSIBILITY_OVERLAY`（2032）、1×1、
  `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE`、`PixelFormat.TRANSLUCENT`、
  `gravity=TOP|START`、`y=状态栏高度`。
- 不需要 `SYSTEM_ALERT_WINDOW`（appop 是 `CREATE_ACCESSIBILITY_OVERLAY`，
  随无障碍服务自动授予），也不触发“显示在其他应用上层”的用户开关。
- 风险与对策：
  - 自身包名的事件：窗口创建瞬间可能产生一条 `TYPE_WINDOW_STATE_CHANGED`
    （pkg=`com.jotbill.app`）。当前 `resolveEffectiveShoppingPackage` 会把它
    归属到活动根窗口并走一次普通采集，与 systemui 噪声同级；
    `shouldSkipForeignEventCollection` 目前是**零调用死代码**，本次不动。
  - 窗口不可见/被裁掉会导致保活失效：坐标必须落在屏幕内（钱迹用 y=169）。
  - `removeView` 对已 detach 的 view 会抛异常 → 必须 try/catch。

### 5.2 【加固】进入支付环境时拉起 specialUse 前台服务（3–10 s）

钱迹在识别到支付时 `fg=true` 3–10 s。我们的清单已声明
`FOREGROUND_SERVICE_SPECIAL_USE`（`AndroidManifest.xml:9`），
但全仓**没有任何 `startForeground` 调用**。作为 5.1 的第二道保险。

### 5.3 【不要依赖】用户设置“省电策略=无限制 / 自启动”

钱迹与 JotBill 都**不在** `mPowerWhitelistedApps` 里，钱迹照样活得好——
这不是必要条件，不能作为唯一修复。

### 5.4 【次生】站内支付路由旁路

见 `2026-10-29-remote-user1/ANALYSIS.md` 修复建议 1。

---

## 6. 取证命令（可复现）

```bash
cd <解压目录>

# 冻结/解冻时间线
grep -a "setUidState, uid = 10366" bugreport-xuanyuan-*.txt

# 系统策略判定
grep -a "PolicyMaker" bugreport-xuanyuan-*.txt | grep -aE "1036[67]"

# 在屏跳过冻结
grep -a "was show on screen" bugreport-xuanyuan-*.txt

# 前台服务
grep -a "onForegroundServicesChanged" bugreport-xuanyuan-*.txt | grep -a 10367

# 我们的线程现场（SIGQUIT 冻结快照）
grep -a -n "pid 27002 at" bugreport-xuanyuan-*.txt

# 1×1 保活窗与弹窗
grep -a "ACCESSIBILITY_OVERLAY" bugreport-xuanyuan-*.txt
grep -a "AutoBillFloatingView" bugreport-xuanyuan-*.txt
```

> ⚠️ 该文件含二进制字节，`grep` 必须加 `-a`，否则命中后直接输出
> `Binary file ... matches` 并截断结果（本次已踩过一次）。

---

## 7. 遗留与存疑

1. **`allow=false` 语义**由 `FZ`/`THAW` 相邻行推得，不是公开文档；
   结论强度高，但建议在下一版真机用“保活窗 + GreezeManager 日志”复验。
2. **钱迹 12.80 那笔只读到 3 个节点**（`View` + `ContentDesc`：
   支付成功 / 拼多多 / ￥12.80），说明拼多多成功页是自绘页面。
   我们解冻后**是否也能读到同样的 3 个 ContentDesc** 尚未实测
   （`collectNode` 会读 `contentDescription`，理论上可以，但需真机验证）。
3. 23:32:18 的 THAW 是 bugreport 的 SIGQUIT 副作用，不是用户操作；
   它让“延迟弹窗”的现场得以保留，但也意味着**实际用户场景下可能永远不弹**。
4. 本机同时启用了钱包AI记账（capabilities=243、TYPES_ALL_MASK）——
   它是否也有保活窗、是否同样未被冻结，可作为第三条对照，本次未展开。

---

## 8. 钱迹 dex 反编译取证（2026-10-29 补充）

> **取证对象**：`D:\tools\rea\targets\qianji\com.mutangtech.qianji\base.apk`
> （工具链标注 **v4.5.3**；设备实跑 **v4.5.4** —— 规则可能随版本变化，
> 本节结论按 v4.5.3 标注）。
> **方法**：`unzip classes*.dex` → `dexdump -d` → 按方法名 / 字符串常量定位。
> 本节全部为**静态证据**；凡推断均显式标注「推断」。

### 8.1 包名白名单（8 个）

`Laa/a` … `Laa/h` 各实现的 `getPackageName()` 返回常量：

| 实现类 | 包名 |
|---|---|
| `Laa/a` | `com.eg.android.AlipayGphone` |
| `Laa/b` | `com.ss.android.ugc.aweme` |
| `Laa/c` | `com.jingdong.app.mall` |
| `Laa/d` | `com.sankuai.meituan` |
| `Laa/e` | `com.xunmeng.pinduoduo` |
| `Laa/g` | `com.unionpay` |
| `Laa/h` | `com.tencent.mm` |
| （另 1 个） | 本次未展开 |

`s9.b.a(String)` 的实现是 `Map.containsKey(packageName)` —— 事件先过**包名白名单**，
映射到对应的 `Laa/*` 页面策略。

### 8.2 事件入口：只用 `event.getSource()`

`onAccessibilityEvent`（dex 偏移 `0x2ce5xx`）的控制流：

1. `event.getPackageName()` → `s9.b.a()` 过白名单；
2. `event.getSource()` 取节点（**dex 中 `getRootInActiveWindow` 出现 0 次**）；
3. `s9.b.c(event, sourceNode)` → 页面级 `shouldMatch`；
4. 命中后 `s9.b.b(packageName, rootNode, allNodes)` → `extractLines/extractText`
   → 规则引擎填字段。

### 8.3 本次 12.80 命中的规则

- 拼多多策略（`Laa/e`）只有**两条**规则：
  - `PddPayFirstDialog`（先用后付）
  - `PddWalletBillDetail`（词：账单详情 / 当前状态 / 支付成功 / 商品详情 /
    支付时间 / 支付方式 / 交易单号 / 商户单号）
- `WeChatPaySuccess` 是**微信策略**（`Laa/h`）里的规则名（`Lz9/*.k()` 返回）。
- 钱迹账单 `BillInfo(sourceApp=WeChat, from=WeChatPaySuccess)` ⇒
  **事件包名 = `com.tencent.mm`**，那 3 个 `View + ContentDesc` 节点在**微信窗口内**。

### 8.4 对本应用的含义

- 我们的路由（事件包 = 微信 → 支付管线）与钱迹的包名白名单口径**一致**；
- 钱迹**只靠 `event.getSource()`** 就能读到那 3 个节点；我们的
  `findPaymentResultRoots` 同时取 `rootInActiveWindow` + `event.source` +
  活动窗口 root，是它的**超集** ⇒ 冻结修好后没有理由读不到；
- 我们与钱迹的**真实差异**不在"读节点"，而在"进程活着"（§2）。

### 8.5 存疑

1. 设备 v4.5.4 vs 反编译 v4.5.3，规则可能已变化；
2. dex 中 `getWindows` 出现 2 次、但 `AccessibilityWindowInfo` 类型描述符出现
   0 次 —— 判定为无关方法，**没有**据此得出"钱迹枚举全部窗口"的结论；
3. `getSource` 的调用点只定位到 `AccessibilityRecord.getSource()` 一处，
   **推断**其节点来源就是事件源子树，未做运行时验证。

---

## 9. 代码级复核：保活修复后能否弹窗（2026-10-29）

> 结论：**对这笔 12.80 高置信可弹（约 8–9 成）**；前提是保活窗在本机真实生效。
> 以下逐环核对的是**修复后的代码**（提交 `355a336`）。

| # | 环节 | 代码位置 | 结论 |
|---|---|---|---|
| 1 | 进程存活 | `installKeepAliveWindow` | ✅（待真机复验） |
| 2 | 事件到达 | 服务配置：5 种事件 / timeout=100 | ✅ 与钱迹同口径 |
| 3 | 事件归属 | `resolveEffectiveShoppingPackage` | ✅ 本笔事件包=微信 |
| 4 | 根节点 | `findPaymentResultRoots` | ✅ 含 `event.source`（钱迹同源） |
| 5 | 文本采集 | `collectNodeText` / `AccessibilityPageSummaryCollector` | ✅ 读 `contentDescription` |
| 6 | 角色判定 | `paymentResultTerms` / `classifyPageRole` | ✅ “支付成功”命中 |
| 7 | 解析 | `PaymentResultParser` | ✅ 金额 1280 / 商户“拼多多” / 时间回落 |
| 8 | 落库 | `recordPaymentResultEvidence` → 孤儿待确认 | ✅ `isNew=true` |
| 9 | 弹窗门禁 | `evaluateGate` | ✅ 观察帧新鲜 / 非敏感 / 非本应用 / 有权限 |
| 10 | 去重 | 通知兜底 8s 宽限 + `sourceEventKey` | ✅ 不双卡 |

### 9.1 残余风险

| 概率 | 风险 | 说明 |
|---|---|---|
| 中低 | 保活窗是否真免冻结 | 同机 3 个 App 同款机制 + 系统日志强证，仍需真机复验 |
| ~10% | 成功页那帧 `rootInActiveWindow` 指向拼多多 | 会走 `eventIsPayment && rootIsShopping → 返回购物包` 分支，支付扫描不启动。钱迹不读 rootInActiveWindow，故不受影响 |
| 已知 | 站内原生成功页（包名=购物包） | 钱迹有独立拼多多规则覆盖，我们无对应管线 |

### 9.2 可选加固（未实施，待裁定）

当 `eventPackage` 是支付包、但 `shoppingPackage` 被归到购物包时，**额外**触发一次
`observePaymentResultIfApplicable(event, eventPackage)`（对齐钱迹“只看事件包名”）。
代价：2026-09 修复过的“后台残留旧成功页误报”可能回归；收窄方案是仅在
`event.source` 子树含结果页关键词时触发。

### 9.3 原始证据

用户提供的两条钱迹内部日志原文见同目录 `qianji-bill-logs.txt`（只增不改）。
