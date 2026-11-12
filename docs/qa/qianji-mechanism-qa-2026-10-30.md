# 钱迹机制问答记录（2026-10-30）

> 本文汇总 2026-10-30 围绕“钱迹为什么能弹窗”的讨论结论，并写明全部日志/证据的存放位置。
> 取证细节与逐条行号见同目录：
> - `docs/qa/qianji-popup-forensics-2026-10-29.md`（三层机制 + 证据索引）
> - `docs/qa/device/2026-10-29-remote-user2/ANALYSIS.md`（冻结取证）
> - `docs/qa/device/2026-10-29-remote-user3/ANALYSIS.md`（我方支付宝弹窗不可见事故）
>
> 设备：Xiaomi 15 Ultra（xuanyuan）/ HyperOS 3.0.314.0.WOACNXM。
> 钱迹：设备运行 v4.5.4；反编译对象 v4.5.3。

---

## 一、日志与证据的存放位置

### 1.1 原始日志（仓库外，未入库）

| 内容 | 路径 | 大小 |
|---|---|---|
| 微信收到的原压缩包 | `D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\bugreport-2026-10-28-233145\bugreport-Xiaomi 15 Ultra-2026-10-28-233444.zip` | 106 MB |
| 解压后的主日志 | `D:\tmp\br-qianji-20261028\bugreport-xuanyuan-BP2A.250605.031.A3-2026-10-28-23-31-45.txt` | 179 MB / 3,525,579 行 |
| dumpstate 日志 | `D:\tmp\br-qianji-20261028\dumpstate_log.txt` | 31 KB |

> 原始压缩包**刻意未拷入仓库**（体积过大，不适合 git）；本文件 §1.1 记录其外部路径。

### 1.2 派生检索副本（临时目录，可能被清理）

| 内容 | 路径 | 大小 |
|---|---|---|
| 全部钱迹相关行（8,993 行） | `D:\tmp\qianji-all.txt` | 1.8 MB |
| 去噪后的动作时间线（7,741 行） | `D:\tmp\qianji-timeline.txt` | 1.7 MB |
| 23:23–23:34 精简动作 | `D:\tmp\qj-actions.txt`、`D:\tmp\qj-2323-2334.txt` | 各 803 KB |
| 钱迹进程自身日志 | `D:\tmp\qianji-proc.txt` | 190 KB |
| 钱迹 APK | `D:\tools\rea\targets\qianji\com.mutangtech.qianji\base.apk` | 32 MB |
| 钱迹 dex / 反汇编 | `D:\tmp\qjdex\classes*.dex`、`D:\tmp\qjdex\d1.txt` | 27 MB + 131 MB |

### 1.3 仓库内的分析文档

| 文件 | 内容 |
|---|---|
| `docs/qa/qianji-popup-forensics-2026-10-29.md` | 三层机制与证据索引（行号） |
| `docs/qa/qianji-mechanism-qa-2026-10-30.md` | **本文**（问答记录 + 日志位置） |
| `docs/qa/device/2026-10-29-remote-user2/ANALYSIS.md` | 冻结取证（§1–§9） |
| `docs/qa/device/2026-10-29-remote-user2/qianji-bill-logs.txt` | 钱迹两条账单日志原文 |
| `docs/qa/device/2026-10-29-remote-user3/ANALYSIS.md` | 我方 0.0.104 支付宝弹窗不可见事故 |

### 1.4 行号引用约定

下文所有“行 N”均指 **§1.1 中的解压主日志**
`bugreport-xuanyuan-BP2A.250605.031.A3-2026-10-28-23-31-45.txt`。

---

## 二、问答记录

### Q1. 钱迹为什么能在支付成功页弹窗？

三层同时成立，缺一不可：

1. **进程不被冻结**：常驻 1×1 `TYPE_ACCESSIBILITY_OVERLAY` 窗，系统判 `VISIBLE_APP`；
2. **事件能进来**：只处理包名白名单内的事件，读 `event.getSource()` 子树；
3. **窗口能上屏**：弹窗前 70ms 拉起 specialUse 前台服务（`UID_VISIBLE`），再用 2038 悬浮窗上屏并拿到输入焦点。

---

### Q2. 钱迹的保活策略是什么？

**一条常驻、一条临时。**

**常驻：1×1 无障碍悬浮窗**

```
23:24:55.055  无障碍服务启动（THAW reason: Excute Service）       行 50254
23:24:55.085  建 1×1 窗（finishDrawingLocked）                     行 50291
23:24:55.096  窗口上屏（showSurfaceRobustly）                      行 50313
```

窗口属性（WM dump，行 32372–32390）：

```
Window{a209a16 u0 com.mutangtech.qianji}
  ty=ACCESSIBILITY_OVERLAY  (0,0)(1x1)  frame=[0,169][1,170]
  fl=NOT_FOCUSABLE NOT_TOUCHABLE
```

效果：

```
23:24:18.286  PolicyMaker: uid=10367 ... reason=VISIBLE_APP          行 45785
23:25:00.071  GreezeManager: Uid 10367 was show on screen, skip it   行 51753
23:30:34.981  同上                                                  行 134767
```

服务启动前它会被正常冻结（23:24:24、23:24:44 两次 `FZ`）；启动后到日志结束**再未被冻**。

**临时：弹窗期间的 specialUse 前台服务**（见 Q3）。

**不依赖用户设置**：钱迹不在系统电池白名单 `mPowerWhitelistedApps` 中，也没有请求自启动。

---

### Q3. 临时拉起的 FGS 作用是什么？

**它是弹窗的“载体 + 前台身份”，只在弹窗存在期间活着。**

时间绑定（两笔完全一致）：

```
23:26:47.157  FGS 启动   → 23:26:57.167  FGS 停止   （10.0s = 弹窗停留）
23:29:45.102  FGS 启动   → 23:29:48.147  FGS 停止   （ 3.0s = 弹窗停留）
```

作用：

1. **持有悬浮窗**：服务类是 `.auto.floatview.AutoBillFloatingService`，窗口由它 `addView`；服务停，窗口随之消失。
2. **给进程前台身份**：
   ```
   Background started FGS: Allowed ... code:UID_VISIBLE
   GreezeManager: onForegroundServicesChanged ... fg=true
   ```
   弹窗期间不会被冻结、不会被 LMK 杀掉。
3. **让“后台加窗”通过系统限制**（推断）：Android 12+ 限制后台启动 FGS，MIUI 还限制后台加悬浮窗；FGS 是常见前提。日志能证明它踩着 `code:UID_VISIBLE` 的边界启动，不能直接证明这一步是决定性条件。

---

### Q4. 弹窗策略：怎么做到在最顶层？

**不是靠特殊窗口类型，而是三件事凑齐。**

1. **层级正确**：`TYPE_APPLICATION_OVERLAY`（2038）天然位于所有 Activity 窗口之上，只低于状态栏/输入法等系统窗口。
2. **结构正确**：两个 2038 窗口
   - 背景窗：`FLAG_NOT_TOUCHABLE`，系统把 alpha 压到 0.80（只压暗、不抢触摸）；
   - 卡片窗：`0,0–1440,1697`（上半屏 53%）、`NOT_FOCUSABLE`（不抢键盘焦点，但可点）。
   先加背景、再加卡片，所以卡片在上。
3. **前提正确**：加窗前先起 FGS（`UID_VISIBLE`），并等支付页稳定后才加。
   系统侧确认：`onOpActiveChanged system_alert_window active:true`、
   `SmartWindowScenario: ENTER_FLOAT_WINDOW type:APPLICATION_OVERLAY`、
   `Input focus has changed to Window{...qianji}`。

对照我方 0.0.104：同样是 2038，但无 FGS、全屏可获焦、且在 `opMode` 恢复后 0ms 加窗 ⇒ attach 成功却 30 秒未成为活动窗口（用户“什么都没看到”）。

---

### Q5. 微信和支付宝，钱迹的弹窗逻辑一样吗？

**弹窗层完全一样；识别层不一样。**

相同（两笔逐项一致）：

- 同一个 `AutoBillFloatingService`；
- 两个 2038 窗口、同样的 `NOT_TOUCHABLE` 背景 + `0,0–1440,1697`/`NOT_FOCUSABLE` 卡片；
- 窗口拿到输入焦点；
- 用户点 2 次 → 保存 → FGS 停止。

不同（识别层）：

| | 支付宝 2.60（23:26:47） | 微信-拼多多 12.80（23:29:45） |
|---|---|---|
| 事件包名 | `com.eg.android.AlipayGphone` | `com.tencent.mm`（微信窗口，拼多多在下层） |
| 命中规则 | `AlipayPaySuccess`（支付宝策略） | `WeChatPaySuccess`（微信策略） |
| 节点 | 10 个 `TextView`（`Text=`） | 3 个 `View`（`ContentDesc=`） |
| 提取字段 | `outAsset=邮储银行储蓄卡(2910)` | `description=拼多多` |
| FGS 时长 | 10.0s（用户 9.0s 后才点） | 3.0s（用户 2.3s 后就点） |

FGS 时长差来自用户点击速度，不是逻辑差异。

---

### Q6. 钱迹的无障碍权限是什么？

**不是运行时权限，而是“用户手动开启的无障碍服务”。** APK 清单：

```xml
<service
    android:name="com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    android:stopWithTask="false"
    android:isAccessibilityTool="true"
    android:foregroundServiceType="specialUse"
    android:persistent="true" />
```

服务配置（APK 内 `res/-N.xml` → `accessibility_service_config`）：

| 配置项 | 值 |
|---|---|
| `accessibilityEventTypes` | `0x820` = `WINDOW_STATE_CHANGED + WINDOW_CONTENT_CHANGED` |
| `accessibilityFlags` | `0x5b` = `RETRIEVE_INTERACTIVE_WINDOWS + REPORT_VIEW_IDS + ENHANCED_WEB + INCLUDE_NOT_IMPORTANT_VIEWS`（+0x1 保留位） |
| `canRetrieveWindowContent` | `true` |
| `canTakeScreenshot` | `true` |
| `notificationTimeout` | `10ms` |
| `packageNames` | 11 个包：支付宝、微信、数字人民币、云闪付、抖音、美团、美团外卖、拼多多、京东、淘宝、饿了么 |

开启后系统授予：**读窗口内容 + 截图 + `CREATE_ACCESSIBILITY_OVERLAY`（1×1 窗使用的 appop）**。

---

### Q7. 为什么没开自启动，钱迹也能“自启动”？

**先纠正前提：本份日志里它并没有自启动。**

```
23:20:04.019  am_proc_start: [0,23684,10367,com.mutangtech.qianji,
              prestart-top-activity,{...MainActivity}]                行 222511
```

进程是**用户点开钱迹**时启动的；无障碍服务是 23:24:55 在进程已存活时由系统绑定的。

那为什么看起来“一直活着、还会被拉回来”？四层原因：

1. **绑定方是系统，不是应用**
   ```
   ServiceRecord{a1d6978 u0 com.mutangtech.qianji/...SelectToSpeakService c:android}   行 783043
   ConnectionRecord{19b6adb u0 CR FGSA CAPS ... flags=0x2101001}                       行 783083
   binding=AppBindRecord{d0a2099 ...:system}                                           行 783081
   infoAllowStartForeground=[callingPackage: android; callingUid: 1000; uidState: PER]
   ```
   `callingPackage: android` ⇒ 绑定来自 `system_server`。MIUI 的“自启动”管的是**应用自己发起的启动**（开机广播、后台服务、拉起 Activity），管不到系统绑定无障碍服务。
2. **`android:stopWithTask="false"`**：从最近任务划掉钱迹，服务不会被停。
3. **1×1 窗 + `isAccessibilityTool="true"`**：窗口在屏让冻结器跳过；`isAccessibilityTool` 让系统按无障碍工具对待。
4. **自带“自愈”逻辑**（仅资源字符串可证，本次日志未触发）：
   `auto_record_a11y_fault_*`、`auto_record_a11y_heal_success`、`auto_record_a11y_heal_failed`。

补充：它声明了 `android:persistent="true"`，但该属性对第三方应用通常不生效，只能算意图声明，不能当机制。

---

### Q8. 这份日志存在哪里？

见本文 **§1.1–§1.3**。要点：

- 原始压缩包在微信目录：`D:\xwechat_files\...\bugreport-2026-10-28-233145\`（106 MB，**未入库**）；
- 解压主文件与派生索引在 `D:\tmp\br-qianji-20261028\`、`D:\tmp\`（**临时**）；
- 仓库内只有分析文档与钱迹账单日志原文，没有 106 MB 原始包。

---

## 三、今天未展开、可继续追问的点

### 识别层
- 第 1 次 / 第 2 次点击分别点的是什么（日志只有触摸事件，无坐标/控件）
- 分类“日用品”是默认值还是规则命中
- 支付宝能提取邮储卡、微信为什么没提取到账户
- 3 个 ContentDesc 节点与 10 个 TextView 节点分别如何被规则引擎切词
- 重复账单检测（`DuplicateBillPromptView`）为何本次未出现
- AI 兜底的触发条件（两笔都是“本地识别”）

### 保活/弹窗
- 1×1 窗由无障碍服务还是 `AutoBillFloatingService` 创建（日志无调用栈）
- FGS 通知渠道 `qianji_auto_record_status` 的内容与可见性
- toast 内容（被 `Suppressing toast` 抑制）
- 无障碍“自愈”逻辑的实际触发条件（本次未触发）
- 钱迹是否有闹钟/JobScheduler 等其他保活手段

### 我方对照
- 0.0.105 的 A/B/C 三项尚未真机验证
- 同笔重复卡（D 项）未修
- `TYPE_ACCESSIBILITY_OVERLAY` 兜底方案未实施

---

## 四、存疑（不要把推断当结论）

1. 设备运行 v4.5.4，反编译是 v4.5.3，规则/参数可能已变。
2. `android:persistent="true"` 对第三方应用通常无效，不能作为保活机制的证据。
3. FGS 是“后台加窗合法前提”属推断；日志只能证明时序与 `UID_VISIBLE`。
4. 窗口“被压在支付应用之下”是我方 0.0.104 事故的推断（无系统 `WindowManager` 日志）。
5. 钱迹“自愈”只有资源字符串，未在日志中观察到实际执行。
