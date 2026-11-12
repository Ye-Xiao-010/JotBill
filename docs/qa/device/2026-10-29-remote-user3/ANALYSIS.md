# 远程用户 3 · 支付宝弹窗不可见 / 微信正常（0.0.104）取证（2026-10-29）

> **证据（仓库外）**
> `D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\jotbill-diag-Xiaomi-20261029-102652\`
> `D:\xwechat_files\wxid_gobliea0j02212_378b\msg\file\2026-10\jotbill-diag-Xiaomi-20261029-103251\`
> 每份含 `jotbill-diag.log` + `.log.1` + `meta.txt`。
> **设备**：Xiaomi 25019PNF3C / sdk 36 / **0.0.104(100)**。
> 用户反馈：微信能弹，支付宝不弹，但支付宝有通知。

---

## 结论先行

**0.0.104 的冻结修复生效**：`10:22:48 保活窗已安装/已附着`，全日志 **0 次**
`服务心跳异常`，10:22–10:33 无冻结；微信 4 笔全部在 60–500ms 内弹窗。

**支付宝这笔弹了两次，但第一张用户完全看不到**：

1. 第一张 `pending-orphan-3`（15.50）10:31:46.126 addView 成功、
   `attached=true`，但 **30 秒内无障碍活动窗口一直是支付宝**
   （`rootPkg=com.eg.android.AlipayGphone`）⇒ 窗口被压在支付应用之下，
   用户侧"什么都没弹"。
2. 第二张 `pending-orphan-9`（同额，10:32:04 落的重复卡）10:32:16.162
   addView 后 **成为活动窗口**（`rootPkg=com.jotbill.app`），
   10:32:17.892 出现 `class=android.widget.Button` 自身事件 + pending 被处理
   ⇒ 用户点击确认的是这一张。

---

## 1. 支付宝 15.50 完整时间线

| 时刻 | 事件 |
|---|---|
| 10:31:43.510 | 证据落库：`pending-orphan-3, amount=1550, source=accessibility` |
| 10:31:43.513 | 通知：`待确认提醒已发送`（用户看到的那条） |
| 10:31:43.517 | 门禁：`NO_OVERLAY_PERMISSION ... opMode=IGNORED`（MIUI 收权） |
| 10:31:43.5–45.1 | 每 ~100ms 重评，持续 `IGNORED` |
| 10:31:45.27–45.36 | `SENSITIVE_FRAME`（支付宝密码/安全键盘页，正确拦截） |
| **10:31:46.102** | `弹窗候选挂起后放行: waitedMs=2586, reason=PASS` |
| 10:31:46.126 | `付款确认弹窗已显示`（**attach 成功但不可见**） |
| 10:31:46.626 | `已上屏确认: attached=true`（自检只查 attach） |
| 10:32:04.194 | 第二张卡 `pending-orphan-9, amount=1550`（**21s > 15s 去重窗**） |
| 10:32:04.197 | `弹窗候选入队: SLOT_BUSY` |
| 10:32:16.136 | 第一张 `AUTO_DISMISS`（满 30 秒）→ 队列补播第二张 |
| 10:32:16.662 | 无障碍活动窗口变为 `com.jotbill.app`（**第二张可见**） |
| 10:32:17.892 | `pkg=com.jotbill.app, class=android.widget.Button`（用户点击） |
| 10:32:18.604 | `付款确认弹窗已关闭: reason=SUCCESS` |

## 2. 为什么第一张不可见

- 10:31:46.102 的 PASS 发生在 `opMode` 从 `IGNORED` 恢复后的**第一帧**：
  AppOps 已返回 `canDraw=true`，但窗口层尚未恢复；
- 自检只验 `isAttachedToWindow`（attach 成功），无法区分"在屏"与"被压住"；
- 30 秒里所有 `无障碍事件采样` 的 `rootPkg` 都是支付宝，反证我们的窗口
  从未成为活动窗口。

## 3. 与钱迹对照（同一台机器、2026-10-28 23:26:47 支付宝 2.60）

钱迹在支付宝上前台**弹得出来**，系统日志：

```
23:26:47.157  ActivityManager: Background started FGS: Allowed
              [callingPackage: com.mutangtech.qianji;
               cmp=.../.auto.floatview.AutoBillFloatingService; code:UID_VISIBLE]
23:26:47.164  am_foreground_service_start: [... serviceTypes=1073741824(SPECIAL_USE)]
23:26:47.227  WindowManager: App com.mutangtech.qianji has a system alert window (type = 2038)
23:26:47.763  wms.Input focus has changed to Window{aa9a85 u0 com.mutangtech.qianji}
```

差异：钱迹在 `addView` **之前 70ms** 拉起 specialUse 前台服务（`UID_VISIBLE`），
然后把窗口加到活动层；我们没有任何前台服务。

## 4. 本轮修复（A+B+C）

| 项 | 实现 | 目的 |
|---|---|---|
| A | `OVERLAY_PERMISSION_SETTLE_MILLIS=500ms`，`canDraw=true` 连续稳定后才放行（`OVERLAY_PERMISSION_SETTLING`，到点自唤醒） | 把 addView 推离权限恢复瞬间 |
| B | 新增 `PendingOverlayService`（specialUse FGS），`addView` 前启动、`dismissInternal` 后停止 | 对齐钱迹，保持 `UID_VISIBLE` |
| C | 上屏自检改为 `attached && isShown && hasWindowFocus`；失败重建一次，二次失败只告警不误关 | 把"被压住的窗口"识别出来并补救 |

## 5. 未修（下一批）

**同笔重复卡**：`orphan-3`（10:31:43）与 `orphan-9`（10:32:04）相差 21 秒，
超过 `ORPHAN_DEDUP_WINDOW_MILLIS=15s` 且页面文本哈希变化 ⇒ 同一笔落两张卡。
建议：同包同额且存在**未处理 pending** 时抑制新建，或把窗口扩到 60s。

## 6. 存疑

1. 本轮日志没有系统侧 `WindowManager`/`GreezeManager`，无法直接观察窗口层级；
   "被压在支付应用之下"由"30 秒内活动窗口一直是支付宝"反推。
2. 第二张无 FGS 也成功上屏，说明 FGS 不是唯一条件 —— A（稳定期）与 B 谁在起
   决定作用，需下一版真机复验（修复后应看到 `permissionStableFor≥500ms` 且
   `focus=true`）。
3. 微信 4 笔在 0.0.104 下均无 `NO_OVERLAY_PERMISSION` 记录 —— 该收权只出现在
   支付宝前台，与 MIUI 支付保护名单一致（推断）。
