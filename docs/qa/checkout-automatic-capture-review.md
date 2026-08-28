# 结算页自动截图链路审查

> 审查日期：2026-08-25
> 范围：`CheckoutSnapshotCaptureCoordinator` / `CheckoutSnapshotBuffer` / `CheckoutPurchaseIntentCoordinator` / `PaymentNotificationService` 中「自动截图」整条链路
> 结论：链路整体健康，上一轮的竞态已全部修复；仍存在 1 个真实线程安全缺陷。

---

## 一、上一轮发现的问题 —— 已全部修复

| 上一轮问题 | 修复位置 | 现状 |
|---|---|---|
| 新指纹无条件 `discard` 清空缓冲 → NO_BUFFERED_SNAPSHOT | `CheckoutSnapshotCaptureCoordinator.kt:1409-1416` 新增 `driftWithinSameCheckout` 沿用旧会话 | ✅ 已修复 |
| 指纹漂移导致 8 秒连截 6 张互相覆盖 | 同文件 `:1488-1492` 漂移时复用已有候选，不再重复截图 | ✅ 已修复 |
| 京东「结算页稳定后即跳中间页」时序下唯一截图被取消 | `:1597-1611` `keepPendingForHandoff` 保留飞行中截图 | ✅ 已修复 |
| 交接栅栏指纹与候选指纹对不上 → 快照被自己锁死 | `CheckoutSnapshotBuffer.kt:458-462` `resolveHandoffFenceFingerprint` 按候选校正 | ✅ 已修复 |
| 交接后落盘时栅栏设置失败（槽位为空） | `CheckoutSnapshotCaptureCoordinator.kt:1923-1939` 补设栅栏 | ✅ 已修复 |
| 挂起会话失去快照后残留独占交接位 | `:1550-1575` `shouldReplaceHandoffCheckout` 接替 | ✅ 已修复 |

修复质量高：均附带针对性诊断日志（`观察失效:`、`交接栅栏已设置` 等），且按原因采样空槽位丢弃日志避免刷屏。

---

## 二、发现的真实问题（中等严重度）：缓冲线程安全不一致

### 现象
`CheckoutSnapshotBuffer` 中三个方法**缺少 `@Synchronized`**，而其余所有访问 `slots` 的方法都有：

| 方法 | 位置 | 锁 |
|---|---|---|
| `store` | `CheckoutSnapshotBuffer.kt:201` | ❌ 无锁 |
| `consumeForAi` | `CheckoutSnapshotBuffer.kt:539` | ❌ 无锁 |
| `retainForPaymentHandoff` | `CheckoutSnapshotBuffer.kt:430` | ❌ 无锁 |
| `current` / `currentCandidates` / `hasCandidate` | `:331 / :343 / :358` | ✅ @Synchronized |
| `discard` / `clear` / `close` | `:681 / :723 / :748` | ✅ @Synchronized |
| `purgeExpired` / `restoreFromPaymentHandoff` / `findReference` | `:759 / :486 / :505` | ✅ @Synchronized |

### 竞态路径（已坐实）
- **写侧（主线程）**：截图回调经 `screenshotCallbackExecutor = mainHandler.post`（`CheckoutAccessibilityService.kt:316`）回到主线程 → `finishCaptureOnMainThread` → `buffer.store(...)`。
- **读/消费侧（IO 线程）**：支付通知到达后，`PaymentNotificationService.scope = CoroutineScope(Dispatchers.IO)`（`PaymentNotificationService.kt:44`）→ `scope.launch`（`:230`）→ `tryFallbackSignal`（`:256`）→ `requestPaymentNotificationFallbackSignal`（`:427`）→ `requestPaymentNotificationFallbackForSignal`（`CheckoutAccessibilityService.kt:2130`，**内部无 mainHandler.post**）→ `coordinator.trigger(PAYMENT_NOTIFICATION_FALLBACK)`（`:2275`）→ `routeLocked`（`CheckoutPurchaseIntentCoordinator.kt:192`）→ `buffer.consumeForAi`（`:442`）。

对比之下，`USER_RETRY` 路径在 `performUserRetryForSession` 里做了 `mainHandler.post` 分发（`CheckoutAccessibilityService.kt:484`），因此只有 `PAYMENT_NOTIFICATION_FALLBACK` 这条路径跨线程。

### 影响
`slots` 是普通 `HashMap`（非 `ConcurrentHashMap`），`store` 与 `consumeForAi` 并发 put/remove/get 存在三类风险：
1. **结构损坏**：JDK8 `HashMap` 并发写可能丢条目甚至死循环。
2. **状态错乱**：`store` 的 `existing?.let { releaseCandidates(it) }`（`:248`）与 `consumeForAi` 的 `releaseCandidates(state)`（`:665`）并发，可能候选丢失、重复消费、`consumedRequestKey` 错乱。
3. **内存可见性**：无 `volatile`/锁，一个线程写入的候选另一线程可能看不到（"store 了却 consume 不到"或反之）。

触发窗口是「自动截图落盘」与「支付通知回退」恰好重叠——用户在结算页刚点提交、通知随即到达时概率不低。

### 修复建议
- **方案 A（最小改动，推荐）**：给 `store`、`consumeForAi`、`retainForPaymentHandoff` 补 `@Synchronized`，与其余方法对齐。三者内部调用 `purgeExpired`（`@Synchronized`，可重入）、`releaseCandidates`、`findCandidate` 等均不回调外部，补锁无死锁风险。
- **方案 B（对齐 USER_RETRY）**：在 `requestPaymentNotificationFallbackForSignal` 里把 `coordinator.trigger` 包进 `mainHandler.post`，让通知回退也收敛到主线程。需处理 `tryFallbackSignal` 的 `suspendCancellableCoroutine` + `withTimeoutOrNull` 的桥接。

---

## 三、次要观察点（非阻塞，供参考）

1. **截图编码在主线程执行**：`screenshotCallbackExecutor` 把 `takeScreenshot` 回调 post 到主线程，`onSuccess` 内 `encodeScreenshot`（`CheckoutSnapshotCaptureCoordinator.kt:1745`）的 JPEG 1080p 编码在主线程完成。低频操作，可接受；极端卡顿下可能影响 UI。
2. **`finishCapture` 主线程等待超时 5s**（`EVENT_DISPATCH_TIMEOUT_MILLIS = 5_000L`，`:306`）：截图回调线程 `await` 主线程处理完成，主线程 ANR 超 5s 会误判失败并 `failClosed`。属防御性设计，可接受。
3. **资源释放正确**：`encodeScreenshot` 四层释放（scaledBitmap → softwareBitmap → hardwareBitmap → hardwareBuffer）顺序正确无泄漏；`BoundedByteArrayOutputStream` 5MB 上限 + `ScreenshotTooLargeException` 拦截正确；`imageBytes.fill(0)` 抹零完整。
4. **敏感页防护完整（自动截图主路径）**：`buffer.store` 在 `:228` 有 `SENSITIVE_PAGE` 拒绝，`routeLocked` 在 `CheckoutPurchaseIntentCoordinator.kt:360-367` 有 `SENSITIVE_PAGE` 门禁。注意此结论仅限「自动截图主路径」；「支付结果截图兜底」那条平行通道是上一轮已单独确认的独立 P0，与本链路无关。
