# 黄金流程回归脚本（Golden Path Regression）

> 建立日期：2026-08-31（补批 0.4 遗留 —— ROADMAP 批 0.4 原计划落盘，从未创建）
> 目的：每批出包必跑的回归基线。核心承诺：**任何一批做完，应用都不会比之前更不可用**（落袋优先）。
> 对应：ROADMAP_FALLBACK_FIRST.md 第八节不变式清单、第十节出包验收线、批 0.4"黄金流程真机脚本成文"。
> 状态：**框架就绪，待 0.0.93 装机后首次真机执行**（校准日志关键词 + 落证据到 docs/qa/device/）。

---

## 一、前置条件

| 项 | 要求 |
|---|---|
| 设备 | 小米 17u Android 16（主力调试机）+ AOSP/原生（设备矩阵），见 DECISIONS 第六节 |
| APK | 当前批次 `assembleDebug` 包（versionName 与 commit message 对齐） |
| 安装方式 | **干净安装**：先卸载旧包再装新包（避免旧 schema / 旧数据干扰迁移） |
| 权限 | 无障碍服务启用、POST_NOTIFICATIONS 通知权限、悬浮窗权限三项均授予 |
| AI | 设置页配置 qwen-vl-plus API key（主用）；连通性测试按钮验证通过 |
| 日志 | `adb logcat -c` 清空，避免历史日志干扰判定 |

**权限验证**：覆盖安装后核对无障碍/通知/悬浮窗三项均保留（批 1 真机曾发现覆盖安装丢权限）。

**⚠️ 服务假死检查（2026-09-10 新增，每次装机后必做）**：MIUI 上覆盖安装（`adb install -r`）后，无障碍服务可能**假死**——设置里显示"已启用"、进程存活，但**不投递任何事件**（无 `无障碍事件采样` 日志）。表现为悬浮按钮完全不出场、识别链路静默。真机实证：0.0.96 装机后 12 分钟服务零日志，按钮"不出现"即此因，与代码无关。

验证方法（装机后执行，无输出=假死）：

```powershell
adb logcat -c; adb shell monkey -p com.xunmeng.pinduoduo -c android.intent.category.LAUNCHER 1
Start-Sleep 12; adb logcat -d -s CheckoutAccessibilityService   # 无"无障碍事件采样"行 = 假死
```

**半死形态（2026-09-10 16:2x 第三次事故新增，零采样检查抓不到）**：采样行存在但全部 `rootPkg=com.miui.home`/`eventPkg=com.android.systemui`（桌面时钟噪声），购物 App 前台却零其包名事件——事件管道活着但对前台应用全盲，悬浮按钮不回来、门禁零输出。判定：连续 30 秒采样无一行 `pkg=<前台购物App>` 即命中。恢复同下（开关重绑即愈）。

恢复程序（开关重绑，30 秒内完成）：

```powershell
$cur = adb shell settings get secure enabled_accessibility_services
$svc = "com.jotbill.app/com.jotbill.app.recognition.CheckoutAccessibilityService"
$without = ($cur -replace [regex]::Escape($svc), "") -replace ":{2,}", ":"
adb shell settings put secure enabled_accessibility_services "$($without.Trim(':'))"
Start-Sleep 3
adb shell settings put secure enabled_accessibility_services $cur
adb shell settings put secure accessibility_enabled 1
```

重绑后用上面的验证方法复核事件恢复投递。

---

## 二、执行命令

### 方式 A：应用内诊断日志（**推荐，一次回归一个文件**）

> 2026-09-29 起可用。App 会把日志直接写到外部私有目录，**无需 adb、无需权限、无 UI 入口**。

```bash
# 1. 干净安装（debug 包才有诊断日志）
adb uninstall com.jotbill.app 2>/dev/null; adb install -r app-debug.apk

# 2. 照常跑用例（不需要开 adb logcat）

# 3. 取文件 —— 手机文件管理器进 Android/data/com.jotbill.app/files/ 复制，或：
adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
```

- 文件名固定 `jotbill-diag.log`，**追加写**，所以**先删旧文件再跑**可让文件只含本轮。
- 单文件上限 10MB，溢出后重命名为 `.log.1` 重新开始。
- 启动时首行含 `版本号 / build 号`，便于对齐代码。
- ⚠️ **本文件替代不了 logcat 的两种情况**：① 崩溃 / ANR（进程死前来不及 flush）
  ② 系统侧事件（如 MIUI `Reject RestartService` 拒绑服务）。这两种仍需方式 B。

### 方式 B：logcat（疑难现场用）

```bash
# 1. 确认设备
adb devices

# 2. 干净安装
adb uninstall com.jotbill.app 2>/dev/null; adb install -r app-debug.apk

# 3. 清日志
adb logcat -c

# 4. 组合过滤（**务必带 tag 过滤** —— 曾因未过滤产出 85MB 文件）
adb logcat -s PendingReminder:* PendingChangeHub:* PaymentNotificationService:* CheckoutPaymentMatcher:* CheckoutAccessibilityService:* > .tmp-golden.logcat.txt
```

> ⚠️ **体积提醒**：`docs/qa/device/` 已累计 **153 MB**（单文件最大 85.6 MB），
> 且该目录按项目纪律「只增不改」。**日常回归请用方式 A**；
> 方式 B 的产物请放 `.tmp-` 前缀目录（已被 `.gitignore` 覆盖），不要直接进 `qa/device/`。

---

## 三、正向用例（必须全过，3/3）

### G1 拼多多 + 微信支付全链路

**操作**：打开拼多多 → 选商品进结算页（金额可见）→ 点提交订单 → 调起微信支付 → 输入密码 → 支付结果页。

**期望日志链**：
- `CheckoutSnapshotCaptureCoordinator`：结算页稳定 → 截图（结算页角色 CHECKOUT）
- `CheckoutPaymentMatcher`：AI 证据解析 → 支付证据匹配（PAYMENT_CONFIRMED）
- `PendingReminder` 或自动入账：待确认提醒已发送 / 账单落账

**通过判定**：钱 100% 出现在已入账或待确认（不丢失、不重复）。

**证据**：`docs/qa/device/<date>-g1-pdd-wechat-fullchain.png` + `...-g1-pdd-wechat-fullchain.logcat.txt`。

### G2 京东弹窗结算页

**操作**：京东 App → 购物车 → 结算页（弹窗形态）→ 提交订单 → 支付 → 结果页。

**期望**：弹窗结算页被识别为 CHECKOUT，不跳中间页时序丢截图；支付证据匹配。

**通过判定**：同 G1。

**证据**：`docs/qa/device/<date>-g2-jd-popup-*.png/.logcat.txt`。

### G3 手动补截（三通道，2026-09-09 重写）

> 前置：分支 `fix/manual-capture-priority`（修复记录见 `docs/qa/checkout-manual-capture-priority-fix.md`）。
> 三条子用例覆盖悬浮按钮的全部点击路径（路由见 Service `requestManualCaptureFromOverlay`）。

**G3-1 已捕获状态手动重截（原 C3/C6 场景）**

**操作**：进入结算页 → 等自动截图成功（按钮显示"已捕获"）→ 立即点悬浮按钮。

**期望**：手动截图落**手动候选槽**（日志 `候选已写入: ... source=MANUAL_CAPTURE`）；与自动候选同指纹时手动图**顶替**自动图（无歧义）；"已捕获"状态可点击（原死路：CHECKOUT target 门禁整张丢弃 + 状态不可点）。

**G3-2 自动截图进行中抢占（原 C1/C2 场景，出包红线；2026-09-09 依真机可操作性修订）**

> 原设计"盯住 CAPTURING 瞬间精准点击"经真机实测不可行：结算页上的"处理中"仅持续数百毫秒，肉眼不可捕捉。修订为**高频点击代测**（可靠性依据：800ms 节流 + 1~2s 截图回调，在飞窗口占空比高，2 次/秒 × 15 秒必然命中）。

**操作**（2026-09-09 二次修订）：进入一个**指纹会漂移的结算页**（拼多多结算页即满足：倒计时/推荐位持续触发自动截图）→ **以每秒 1 次（间隔 >800ms 节流窗）稳定点击悬浮按钮，持续 30 秒**。首轮"约 2 次/秒乱点"实测 30 击 0 命中——节流吸收 83% 点击，受理点击又落在截图间隙；稳定 1 次/秒使过节流率达 100%，只剩"自动在飞"单一时序变量。

**期望**：日志出现 `PREEMPTED_BY_MANUAL_CAPTURE`（自动取消）→ 手动结果先回；手动若因系统截图撞车失败（SCREENSHOT_BUSY / INTERNAL_ERROR），后续稳定事件自动补截兜底，**不得死锁**。原"精准点击"保留为可选加分项。

**通过判定**：无 CAPTURE_IN_FLIGHT 卡死、无排队静默取消、无 SCREENSHOT_BUSY 死锁。**本用例不过则修复分支不得合入**——这是抢占机制唯一无法在 JVM 侧证明的点。

**G3-3 识别失败页用户断言（113a7ff 通道）**

**操作**：在识别失败/普通购物页（槽位无活候选）点悬浮按钮。

**期望**：日志 `断言截图受理` → 写入专用槽位 `user-asserted:`（不碰结算槽位）→ 直达 AI → 落待确认。

**统一通过判定**：手动图被识别入账或进入待确认；不重复派发 AI（每会话预算 ≤ 2 次）；三次连续点击受 800ms 节流。

**证据**：`docs/qa/device/<date>-g3-manual-capture-*.png/.logcat.txt`。

---

## 四、负向用例（必须全静默，4/4）

> 不变式（违反即打回，见 ROADMAP 第九节）：敏感页永不截图、AI 未验证证据永不自动落账、快照不落盘、每会话预算不变。

### N1 密码输入页零截图

**操作**：在微信支付密码页停留 10 秒（含多次进出）。

**期望**：**全程无**截图日志、无 `buffer.store`、无 AI 请求、无候选写入。敏感页 7 层防线任何一层不得削弱。

**通过判定**：日志中无 CheckoutSnapshotCaptureCoordinator 截图行 / 无 buffer.store / 无 AI 派发。

**证据**：`docs/qa/device/<date>-n1-password-page-silent.logcat.txt`（截图无，证明无敏感页泄露）。

### N2 非结算页不建会话

**操作**：在购物 App 的非结算页（商品详情、首页、购物车）停留。

**期望**：不创建 checkout session（无 `ensureSession` / 无 AI_PENDING 会话落库）。

**通过判定**：无 CheckoutPaymentMatcher.ensureSession 日志 / 无活跃结算会话。

### N3 通知回退在敏感页被拒

**操作**：密码页期间收到支付通知。

**期望**：通知回退在敏感页被拒（`CheckoutAccessibilityService.kt:2159-2175` 敏感页门禁），不落敏感截图、不进 buffer。

**通过判定**：日志出现"敏感页"拒绝行；无敏感截图；pending 落纯通知待确认（数据不丢）。

### N4 AI 未验证证据只进待确认

**操作**：构造 AI 返回未验证状态（PAYMENT_STATUS_AI_UNVERIFIED / SUCCESS_AI_UNVERIFIED）的支付证据。

**期望**：永不自动落账，只进待确认中心（`NEEDS_CONFIRMATION`），用户一键确认后才入账。

**通过判定**：自动入账路径不触发；待确认中心有条目。

---

## 五、执行节奏

| 时点 | 动作 |
|---|---|
| 每批出包前 | 干净安装 + 跑全量黄金脚本（3 正向 + 4 负向） |
| 合入后一周内 | 每次真实支付后核对 G1 正向用例 |
| 首次执行 | 0.0.93（本批 6）装机后，校准日志关键词并落证据 |

---

## 六、通过标准

- **正向全过：3/3**（G1/G2/G3 全过）
- **负向全静默：4/4**（N1/N2/N3/N4 全静默）
- **任何一项失败 = 本批不通过，不得出包**（落袋优先红线）

---

## 七、与"完全可用"验收定义的关系

ROADMAP 第十节"完全可用"验收定义：结算页自动截图在连续真机日常使用中无快照丢失；付款码被扫 / 扫商户码 / 支付结果页三种场景的钱 100% 出现在已入账或待确认；pending 即时提醒、一键确认；敏感页零截图。

黄金脚本 = "完全可用"的可重复回归基线。G1/G2/G3 对应"结算页自动截图无丢失 + 三种场景钱 100% 落账"；N1/N2/N3 对应"敏感页零截图"；N4 对应"AI 未验证只进待确认"。

---

## 八、首次执行校准清单（0.0.93）

首次真机跑后，把以下"期望日志关键词"校准为精确日志行（当前为关键词，待实测确认）：

| 用例 | 待校准的日志关键词 |
|---|---|
| G1/G2 | 结算页稳定、截图、AI 证据、PAYMENT_CONFIRMED |
| G3-1 | 候选已写入: source=MANUAL_CAPTURE / 顶替自动图 |
| G3-2 | PREEMPTED_BY_MANUAL_CAPTURE / 无 SCREENSHOT_BUSY 死锁 |
| G3-3 | 断言截图受理 / user-asserted: 槽位 |
| N1 | 无截图（反向确认） |
| N2 | 无 ensureSession |
| N3 | 敏感页拒绝（CheckoutAccessibilityService:2159-2175 对应日志行） |
| N4 | NEEDS_CONFIRMATION / 无自动入账 |

校准后更新本表为精确日志行 + 行号。
