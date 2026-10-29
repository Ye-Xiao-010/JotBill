# 钱迹为什么能弹窗 —— 日志取证（2026-10-29）

> 本文只回答一个问题：**钱迹为什么能在支付成功页弹窗**。
> 每条结论都附证据位置（文件 + 行号 / 时间戳），不写没有出处的推断。
> 本应用自身的修复与对照见：
> - `docs/qa/device/2026-10-29-remote-user2/ANALYSIS.md`（冻结取证）
> - `docs/qa/device/2026-10-29-remote-user3/ANALYSIS.md`（支付宝弹窗不可见）

---

## 0. 证据文件清单（绝对路径）

| 编号 | 文件 | 说明 |
|---|---|---|
| **E1** | `D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\bugreport-2026-10-28-233145\bugreport-Xiaomi 15 Ultra-2026-10-28-233444.zip` | 全机日志压缩包（106 MB） |
| **E1a** | 压缩包内 `bugreport-xuanyuan-BP2A.250605.031.A3-2026-10-28-23-31-45.txt` | 3,525,579 行 / 179 MB；**本文所有"行 N"均指此文件**；本地解压副本：`D:\tmp\br-qianji-20261028\` |
| **E2** | `docs/qa/device/2026-10-29-remote-user2/qianji-bill-logs.txt` | 钱迹内部账单日志两条（用户提供，原样存档） |
| **E3** | `D:\tools\rea\targets\qianji\com.mutangtech.qianji\base.apk` | 钱迹 **v4.5.3**；dex 解包副本 `D:\tmp\qjdex\classes*.dex`，反汇编输出 `D:\tmp\qjdex\d1.txt` |
| **E4** | `D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\jotbill-diag-Xiaomi-20261029-102652\`<br>`D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\jotbill-diag-Xiaomi-20261029-103251\` | 本应用 **0.0.104** 对照日志（每份含 `jotbill-diag.log`、`.log.1`、`meta.txt`） |
| **E5** | `docs/qa/device/2026-10-29-remote-user3/ANALYSIS.md` | 0.0.104 支付宝弹窗不可见事故分析 |

**设备**：Xiaomi 15 Ultra（xuanyuan）/ Android 16 / HyperOS 3.0.314.0.WOACNXM
（`ro.build.fingerprint = Xiaomi/xuanyuan/xuanyuan:16/BP2A.250605.031.A3/OS3.0.314.0.WOACNXM`）。
**钱迹版本**：设备运行 **v4.5.4**；反编译对象 **v4.5.3**（规则可能随版本变化，见 §7）。

---

## 1. 一句话结论

钱迹能弹窗，靠的是**三层同时成立**，缺一层都不行：

1. **进程不被冻结** —— 常驻 1×1 无障碍悬浮窗，系统策略判 `VISIBLE_APP`；
2. **事件能进来** —— 只处理 8 个白名单包的 `event.getSource()` 子树；
3. **窗口能上屏** —— 弹窗前 70ms 拉起 specialUse 前台服务（`UID_VISIBLE`），再用 2038 悬浮窗上屏并拿到输入焦点。

---

## 2. 第一层：钱迹不被冻结

### 2.1 它有一个常驻 1×1 无障碍悬浮窗

| 时刻 | 日志 | 文件/行 |
|---|---|---|
| 23:24:55.055 | `THAW uid = 10367 pid = [23684] reason : Excute Service`（无障碍服务启动） | E1a 行 **50254** |
| 23:24:55.085 | `wms.finishDrawingLocked ... Window{a209a16 u0 com.mutangtech.qianji}` | E1a 行 **50291** |
| 23:24:55.096 | `wms.showSurfaceRobustly ... Window{a209a16 ...}` | E1a 行 **50313** |

窗口属性（`dumpsys window windows`，E1a 行 **32372–32390**）：

```
Window #0 Window{a209a16 u0 com.mutangtech.qianji}:
  mOwnerUid=10367 ... appop=CREATE_ACCESSIBILITY_OVERLAY
  mAttrs={(0,0)(1x1) pkg = com.mutangtech.qianji ty=ACCESSIBILITY_OVERLAY
          fl=NOT_FOCUSABLE NOT_TOUCHABLE HARDWARE_ACCELERATED
  Frames: parent=[0,169][1440,3200] frame=[0,169][1,170]
```

### 2.2 系统因此跳过冻结

| 时刻 | 日志 | 文件/行 |
|---|---|---|
| 23:24:18.286 | `PolicyMaker: uid =10367 pkg=com.mutangtech.qianji reason=VISIBLE_APP` | E1a 行 **45785** |
| 23:25:00.071 | `GreezeManager: Uid 10367 was show on screen, skip it` | E1a 行 **51753** |
| 23:30:34.981 | `GreezeManager: Uid 10367 was show on screen, skip it` | E1a 行 **134767** |

**23:24:55 之后直到日志结束（23:34），钱迹没有出现过一次 `allow=false`（冻结）。**

### 2.3 反证：同机本应用被冻结

| 时刻 | 日志 | 文件/行 |
|---|---|---|
| 23:25:47.947 | `ExternalInterface: setUidState, uid = 10366 allow = false` | E1a 行 **65706** |
| 23:29:38.070 | 同上 | E1a 行 **88891** |
| 23:29:38.078 | `GreezeManager: THAW uid = 10366 ... reason : fg important` | E1a 行 **88922** |
| **23:29:38.612** | `setUidState, uid = 10366 allow = false`（**扫描中途被冻结**） | E1a 行 **90675** |
| 23:32:18.005 | `GreezeManager: THAW uid = 10366 ... reason : Signal other3`（bugreport 的 SIGQUIT） | E1a 行 **1114351** |
| 23:32:23.007 | `GreezeManager: Uid 10366 was show on screen, skip it`（我们弹窗出现后） | E1a 行 **1670008** |

对照：23:29:38.612 → 23:32:18.005 之间，本应用**零日志**；而钱迹在 23:29:45 完成了截图 + 弹窗。

---

## 3. 第二层：事件能进来（钱迹读什么）

### 3.1 无障碍配置（E1a 行 839636 / 839637）

```
Service[label=❤️钱迹-自动记账, capabilities=129,
        eventTypes=[TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED],
        notificationTimeout=10]

Enabled services: {com.mutangtech.qianji/com.google.android.accessibility.selecttospeak.SelectToSpeakService}, ...
```

注：类名是 AOSP 的 `SelectToSpeakService`（钱迹打包的同名服务类，用来取得无障碍连接），
本身只有 2 种事件、10ms 超时。

### 3.2 入口逻辑（E3，反编译）

`onAccessibilityEvent`（d1.txt 偏移 `0x2ce5xx`）：

1. `event.getPackageName()` → `s9.b.a(String)`，实现是 `Map.containsKey(packageName)`（**包名白名单**）；
2. `event.getSource()` 取节点；
3. `s9.b.c(event, sourceNode)` 做页面级 `shouldMatch`；
4. 命中后 `s9.b.b(packageName, rootNode, allNodes)` → `extractLines/extractText` → 规则引擎。

**关键事实**：d1.txt 中 `getRootInActiveWindow` 出现 **0 次**；`getSource`、`getChild`、`getChildCount` 有引用。
⇒ 钱迹的节点来源是 **`event.getSource()` 子树**，不依赖"活动窗口根"。

### 3.3 白名单 8 个包（E3，各 `Laa/*.getPackageName()` 常量）

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

### 3.4 落到这两笔账单（E2）

- **12.80（23:29:45）**：`BillInfo(sourceApp=WeChat, from=WeChatPaySuccess, nodeCount=3)`
  节点：`[6] View ContentDesc="支付成功"`、`[7] View ContentDesc="拼多多"`、`[8] View ContentDesc="￥12.80"`。
  `WeChatPaySuccess` 是**微信策略**（`Laa/h`）里的规则名 ⇒ **事件包名 = `com.tencent.mm`**。
- **2.60（23:26:47）**：`from=AlipayPaySuccess, nodeCount=10` ⇒ **事件包名 = `com.eg.android.AlipayGphone`**。
- 拼多多策略（`Laa/e`）只有 `PddPayFirstDialog`、`PddWalletBillDetail`（"账单详情/当前状态/支付成功/支付时间/支付方式/交易单号/商户单号"），
  与这两笔的 `from` 不符 ⇒ 这两笔都不是"拼多多站内页"规则命中的。

---

## 4. 第三层：窗口能上屏

### 4.1 支付宝 2.60（23:26:47）

| 时刻 | 日志 | 文件/行 |
|---|---|---|
| 23:26:47.157 | `ActivityManager: Background started FGS: Allowed [... AutoBillFloatingService ... code:UID_VISIBLE]` | E1a 行 **73766** |
| 23:26:47.164 | `am_foreground_service_start: [... AutoBillFloatingService, serviceTypes=1073741824(SPECIAL_USE)]` | E1a 行 230993 |
| 23:26:47.227 | `App com.mutangtech.qianji has a system alert window (type = 2038) ... alpha = 0.80` | E1a 行 **73781** |
| 23:26:47.243 | 第二个 2038 窗口 `f86953d` | E1a 行 73787/73788 |
| 23:26:47.763 | `wms.Input focus has changed to Window{aa9a85 u0 com.mutangtech.qianji}` | E1a 行 **74327** |
| 23:26:47.806 | `handleWindowFocusChanged: AutoBillFloatingView{... 0,0-1440,1697}` | E1a 行 **231063** |
| 23:26:44.578 | `onSecureChanged ... PayPwdDialogActivity, hasSecure = false` | E1a 行 **73370** |

### 4.2 微信-拼多多 12.80（23:29:45）

| 时刻 | 日志 | 文件/行 |
|---|---|---|
| 23:29:45.103 | `GreezeManager: onForegroundServicesChanged ... fg=true` + `serviceTypes=0x40000000` | E1a 行 **96917** |
| 23:29:45.170 | `App com.mutangtech.qianji has a system alert window (type = 2038)` | E1a 行 **96965** |
| 23:29:45.183 | 第二个 2038 窗口 | E1a 行 **96982** |
| 23:29:45.193 | `wms.Input focus has changed to Window{e416874 u0 com.mutangtech.qianji}` | E1a 行 **97009** |
| 23:29:45.255 | `handleWindowFocusChanged: AutoBillFloatingView{... 0,0-1440,1697}` | E1a 行 **232990** |

### 4.3 窗口形态（两次一致）

- **两个** `TYPE_APPLICATION_OVERLAY`（2038）窗口：
  - 背景窗：`FLAG_NOT_TOUCHABLE`，系统把 alpha 从 1.00 压到 0.80（"let touches pass through"）；
  - 卡片窗：`NOT_FOCUSABLE`，`0,0–1440,1697`（屏幕上半 53%）。
- 卡片**不需要键盘输入**（不可获焦），只接受点击 ⇒ 与我们的"金额/备注输入框必须可获焦"根本不同。

---

## 5. 对照：为什么我们 0.0.104 第一次没上屏

| 维度 | 钱迹 | 本应用 0.0.104 | 证据 |
|---|---|---|---|
| 冻结防护 | 常驻 1×1 无障碍窗 | 已补，验证通过（0 次心跳异常） | E4 |
| 弹窗前前台服务 | 有（addView 前 70ms） | **无** | E4 无 `PendingOverlayService` |
| 窗口形态 | 半屏 + 不可获焦 + 双窗 | 全屏 + 可获焦 + 单窗 | 代码 `createLayoutParams()` |
| 加窗时机 | 无 AppOp 收权 | 在 `IGNORED→ALLOWED` 后 **0ms** | E4 10:31:43–46 |
| 上屏判据 | 窗口拿到输入焦点 | 只查 `isAttachedToWindow` | E4 10:31:46.626 |
| 结果 | 用户可见 | attach 成功，但 30 秒未成为活动窗口 | E4：`rootPkg` 一直是 `com.eg.android.AlipayGphone` |

详见 **E5**。

---

## 6. 复现命令

> ⚠️ E1a 含二进制字节，`grep` **必须加 `-a`**，否则命中后只输出 `Binary file ... matches` 并截断。

```bash
cd /d/tmp/br-qianji-20261028   # E1a 解压目录

# 冻结/解冻（钱迹 vs 本应用）
grep -a "uid = 10367" bugreport-xuanyuan-*.txt | grep -aE "THAW|setUidState"
grep -a "uid = 10366" bugreport-xuanyuan-*.txt | grep -aE "THAW|setUidState"

# 在屏跳过冻结 + 策略判定
grep -a "was show on screen" bugreport-xuanyuan-*.txt
grep -a "PolicyMaker" bugreport-xuanyuan-*.txt | grep -aE "1036[67]"

# 弹窗窗口（两次实例）
grep -a "system alert window" bugreport-xuanyuan-*.txt | grep -a qianji
grep -a "AutoBillFloatingView" bugreport-xuanyuan-*.txt
grep -a "Input focus has changed to Window{aa9a85\|Input focus has changed to Window{e416874" bugreport-xuanyuan-*.txt

# 前台服务
grep -a "AutoBillFloatingService" bugreport-xuanyuan-*.txt

# 无障碍配置
grep -a "label=❤️钱迹" bugreport-xuanyuan-*.txt

# dex 取证（E3）
unzip -p base.apk classes.dex classes2.dex classes3.dex classes4.dex classes5.dex > /tmp/qj.dex.bin
grep -aoc "getRootInActiveWindow" /tmp/qj.dex.bin   # 0
grep -aoc "getSource" /tmp/qj.dex.bin               # 10
/d/Android/Sdk/build-tools/36.1.0/dexdump.exe -d classes.dex > /tmp/d1.txt
grep -n "Ls9/b;.a:(Ljava/lang/String;)Z" /tmp/d1.txt
```

---

## 7. 存疑（不要把推断当结论）

1. **版本差**：设备运行 v4.5.4，反编译是 v4.5.3；规则/窗口参数可能已经变化。
2. **`getWindows` 的 2 次出现**：dex 中 `getWindows` 出现 2 次，但类型描述符
   `AccessibilityWindowInfo` 出现 0 次 ⇒ 判定为无关方法，**没有**据此得出
   "钱迹枚举全部窗口"的结论。
3. **1×1 窗免冻结只在本文这台设备上被证明**：同机 3 个 App（钱迹/GKD/小米钱包）
   都用同款，但只有这一台机器、这一份日志。
4. **窗口可见性**：E1a 只能证明钱迹窗口拿到输入焦点（`Input focus has changed`），
   不能直接证明用户肉眼看到；"用户看到"来自用户反馈与后续账单确认。
5. **支付宝那笔的 `hasSecure=false`** 只证明 23:26:44 之后密码页已退出，
   不能推广为"所有支付成功页都不安全"。
