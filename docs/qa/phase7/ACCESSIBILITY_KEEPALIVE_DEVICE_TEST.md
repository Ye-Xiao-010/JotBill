# 无障碍服务保活真机定向验证

> 本文件是开发侧定向验证记录，不是 T8 完整证据，也不能替代 T9 独立验收。

## 1. 验证环境

| 项 | 值 |
|---|---|
| 设备 ID | `4a6e283b` |
| 设备型号 | `25128PNA1C`（MIUI/HyperOS） |
| JotBill 包名 | `com.jotbill.app` |
| 版本 | `0.0.28`（阶段七集成自构建 release 包） |
| 无障碍组件 | `com.jotbill.app/.recognition.CheckoutAccessibilityService` |
| 对照应用 | `com.mutangtech.qianji`（钱迹） |

## 2. 现象

JotBill 开启无障碍后，从最近任务上滑清理会被 MIUI `ProcessSceneCleaner` 直接 `force-stop`：

```text
ProcessSceneCleaner: SwipeUpClean: force-stop pkgName=com.jotbill.app info=AS:504
ActivityManager: Force stopping com.jotbill.app ... SwipeUpClean
```

结果：`enabled_accessibility_services` 中 JotBill 条目被移除，进程被杀。

## 3. 根因

钱迹/GKD 的无障碍服务 `feedbackType` 是完整集合：

```text
FEEDBACK_SPOKEN | FEEDBACK_HAPTIC | FEEDBACK_AUDIBLE |
FEEDBACK_VISUAL | FEEDBACK_GENERIC | FEEDBACK_BRAILLE
```

JotBill 此前在 XML 和 `onServiceConnected` 中都被强制为只有 `FEEDBACK_GENERIC`。MIUI 清理策略对“只读观察型”无障碍服务不会在上滑清理时保留绑定；补成完整反馈类型后，系统将其作为需要持续运行的无障碍服务处理。

## 4. 代码修复

- `res/xml/checkout_accessibility_service.xml`：声明 `feedbackSpoken|feedbackHaptic|feedbackAudible|feedbackVisual|feedbackGeneric`。
- `CheckoutAccessibilityService.onServiceConnected`：运行态同步设置完整 `feedbackType`（含 `FEEDBACK_BRAILLE`）。
- `AndroidManifest.xml`：无障碍服务 `intent-filter` 增加 `android:priority="1000"`，与钱迹一致。

## 5. 复测结果

开启后执行 `HOME -> 最近任务 -> 上滑划掉 JotBill`：

| 检查项 | 修复前 | 修复后 |
|---|---|---|
| `ProcessSceneCleaner` 决策 | `force-stop` | 仅移除任务，无 force-stop |
| `enabled_accessibility_services` | JotBill 条目消失 | 条目保留 |
| `dumpsys accessibility` Bound services | 消失 | 重新绑定，`Service[label=JotBill]` |
| `pidof com.jotbill.app` | 为空 | 有 PID，进程继续运行 |

## 6. 边界说明

- 本验证覆盖“从最近任务上滑清理”，未覆盖重启、用户强制停止、厂商深度清理。
- T8/T9 完整证据和验收仍由独立角色按阶段七任务文档执行。
