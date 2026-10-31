# 省电优化工作记录

> 日期：2026-10-31
> 版本：0.0.106（versionCode 102）
> 分支：feat/lean-version
> 范围：无障碍服务事件采集链路节电优化（4 项）

---

## 优化项

### ① Sentry 性能追踪关闭（生产）

| 项 | 内容 |
|---|---|
| 文件 | `utils/SentryLogger.kt` |
| 改动 | `tracesSampleRate` 生产从 `0.2` → `0.0` |
| 省电 | 消掉周期性网络唤醒与性能插桩开销 |
| 影响 | 崩溃上报不受影响（`sampleRate` 默认 1.0，独立通道）；调试构建仍 100% 采样 |

### ② 未分类帧 pageTextLength 改采样

| 项 | 内容 |
|---|---|
| 文件 | `recognition/CheckoutAccessibilityService.kt` |
| 改动 | 整树遍历从每帧一次改为每 5 秒一次，高频帧打 `-2` 标记 |
| 省电 | 消掉高频 content-change 帧的冗余全树遍历 |
| 影响 | 诊断锚点保留约 80%（关键时刻大概率命中采样点）；该字段只进日志不参与判定，识别精度零变化 |

### ③ 跳过自身悬浮层 / 系统 UI 误归属事件

| 项 | 内容 |
|---|---|
| 文件 | `recognition/CheckoutAccessibilityService.kt` |
| 改动 | 接上原死代码 `shouldSkipForeignEventCollection`（恒 `return false` → 修正为跳过 self/systemui 误归属事件），补 `activeRoot` 回收 |
| 省电 | 自身悬浮层 / 系统 UI 的误归属事件不再驱动整树采集 |
| 影响 | 真实购物包事件照常进管线；**注意**：与 ④ 同时生效时本项冗余（安全网） |

### ④ XML 包名白名单（省电最大项）

| 项 | 内容 |
|---|---|
| 文件 | `res/xml/checkout_accessibility_service.xml` + `recognition/CheckoutAccessibilityService.kt` |
| 改动 | 事件白名单 10 包（8 购物 + 2 支付），系统层过滤非白名单事件、进程零唤醒；代码 `packageNames = null` 改为从 `DEFAULT_SUPPORTED_*` 派生的白名单 |
| 省电 | 消掉"全设备事件税"（抖音/小红书/launcher/输入法/微信聊天等非白名单事件在系统层过滤） |
| 影响 | 识别精度零变化（10 包全覆盖购物+支付场景）；`lastWindowPackage` fallback 路径不可达（`eventPackage` 永远非空），清理仅影响日志 |

---

## 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 269 / 0 全绿 |

## 省电收益预期

| 来源 | 优化前 | 优化后 |
|---|---|---|
| Sentry 性能追踪 | 周期网络 + 插桩 | **0** |
| 未分类帧全树遍历 | 每帧 | **每 5 秒** |
| 自身/systemui 误归属事件 | 每次全树采集 | **跳过**（④生效后冗余） |
| 非白名单事件（最大头） | 每事件唤醒进程 | **系统层过滤，进程不唤醒** |

## 未做的（需前置条件）

| 项 | 前置条件 |
|---|---|
| 砍 `TYPE_VIEW_SCROLLED` | 需 20 笔 L1 真机回归 |
| 事件级降采样 300~500ms | 需先定义采样窗口语义 + 补测试 |
| source 子树粗筛 | **不做**（HyperOS 2 三层隔离下 source 子树不可靠，会漏掉漏点事件） |

## 施工中的错误记录（供避免复犯）

1. **Python 脚本静默失败**：多步替换时前面成功、后面断言失败 → 整段不写盘，③ 的逻辑修正未生效（函数末尾仍 `return false`）。编译/测试全绿掩盖了问题。**教训**：多步替换分次写盘或最后统一验证。
2. **注释过时**：XML 白名单"不能用"的约束属于已删除的旧截图链路，不是当前代码的约束。**教训**：注释里的约束必须确认属于哪个代码版本。
3. **交互遗漏**：③ 与 ④ 同时生效时 ③ 冗余。**教训**：多项优化叠加时查交互。
