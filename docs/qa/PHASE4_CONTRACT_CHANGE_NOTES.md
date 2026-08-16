# 阶段四契约变更说明

> 本文件补记阶段四开发中实际发生的 `docs/types/Native.d.ts` 变更，
> 用于契约审查追溯。阶段四任务文档 `08_phase4_tasks.md` 原本要求
> “本次文档修订不修改 Native.d.ts”，实际实现存在以下三项契约同步/变更。

## 变更清单

| 模块 | 方法 | 性质 | 说明 |
|---|---|---|---|
| `Recognition` | `openAccessibilitySettings(): Promise<void>` | 契约补登记 | Kotlin 侧阶段三已实现该方法，阶段四仅在 Native.d.ts 补声明，消除代码与契约不一致 |
| `Recognition` | `isAccessibilityEnabled(): Promise<boolean>` | 契约补登记 | 同上，Kotlin 侧已实现，阶段四补声明 |
| `Runtime` | `openNotificationListenerSettings(): Promise<void>` | 新增方法 | 通知权限引导需要跳转“通知访问”专用系统设置页；原 T7 任务卡要求复用 `openAccessibilitySettings()` 会打开错误页面，因此增加最小专用方法 |

## 审查结论

- `Recognition` 的两项属于**既有实现的契约补登记**，不改变运行时行为。
- `Runtime.openNotificationListenerSettings` 属于**新公开方法**，应在阶段四 T7 交接物中标记为“已登记契约变更”，并同步更新任务文档；未声明其他事件或状态值。
- `SessionStatus` 仍严格保持 8 个值，未因本文件变更。
