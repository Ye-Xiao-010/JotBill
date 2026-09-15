# 结算页识别链路结论核查（对照真实源码）

> 核查日期：2026-08-19
> 核查范围：`jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/`
> 方法：直接读取 .kt 源码，逐行比对粘贴报告中的行号与函数声明，不采信报告自身结论。

## 总判定

| 结论 | 判定 | 关键证据 |
|---|---|---|
| 1. 非购物应用事件被归属为拼多多 | ✅ 属实（归因需精确化） | `CheckoutAccessibilityService.kt:561-565` |
| 2. 指纹由整树文本生成、动态内容形成新指纹 | ✅ 属实 | `AccessibilityPageSummaryCollector.kt:122-171` |
| 3. 新指纹清空缓冲并重新截图 | ✅ 属实 | `CheckoutSnapshotCaptureCoordinator.kt:858-873` |
| 4. 敏感支付页仍被截图送 AI（P0） | ✅ 属实（三项独立缺陷全部坐实） | `CheckoutAccessibilityService.kt:1408-1469` / `1296` / `1566` |
| 5. 截图仅内存暂存、不落盘 | ✅ 属实 | recognition 目录 0 处 `FileOutputStream`；`CheckoutSnapshotBuffer.kt:46` |

---

## 结论 1：非购物应用事件被归属为拼多多

**属实。** `resolveEffectiveShoppingPackage` 在 `CheckoutAccessibilityService.kt:561-565`：

```kotlin
if (!eventIsKnown &&
    (pageDetector.supportsShoppingPackage(activeRootPackage) || rootIsPayment)
) {
    return activeRootPackage
}
```

`com.jotbill.app`（自身悬浮层）与 `com.android.systemui` 均不属于购物/支付包（`!eventIsKnown`），当活动根窗口为拼多多时即被改写为拼多多。

**精确化（与报告一致）：改写本身不必然触发重新截图。** `CheckoutStabilityCoordinator.observe`（`CheckoutAccessibilityService.kt:2443-2447`）在指纹相等时直接 `return` 并撤销稳定回调：

```kotlin
if (observation.pageFingerprint == lastStableFingerprint) {
    pendingObservation = null
    handler.removeCallbacks(stableRunnable)
    return
}
```

因此「连续 6 次 STORED」证明每次采集出的指纹确实不同——**事件改写是触发器，指纹漂移才是根因，二者缺一不可**。拼多多自身的 `android.widget.ViewSwitcher` 文本轮播（如「XX人已拼」）即是漂移来源（见结论 2）。

---

## 结论 2：指纹由整树文本生成、动态内容形成新指纹

**属实。** `AccessibilityPageSummaryCollector.collect`（`AccessibilityPageSummaryCollector.kt:150-158`）五元组 SHA-256：

```kotlin
val pageFingerprint = sha256(
    listOf(
        FINGERPRINT_VERSION,           // "checkout-page-v1"
        shoppingPackage.trim(),
        normalizedWindowClass,
        pageRole.storageValue,
        normalizedTextHash             // sha256(归一化文本)
    ).joinToString("|")
)
```

`normalizedTextHash` 由整棵节点树的 `text/contentDescription/hintText/tooltipText` 经 `normalizeTokens` 归一化后求哈希（`AccessibilityPageSummaryCollector.kt:137,149`）。

**降噪覆盖面不足属实。** `noisePattern`（`AccessibilityPageSummaryCollector.kt:47-49`）仅匹配文本里出现「轮播/carousel」等字面词：

```kotlin
private val noisePattern = Regex(
    """(?i)(广告|推荐商品|猜你喜欢|loading|加载中|骨架屏|占位符|动画中|轮播|刷新中|请稍候|carousel|placeholder)"""
)
```

它**不识别 `ViewSwitcher`/`ViewFlipper`/`Marquee` 这类轮播控件本身**。拼多多轮播出的实际文案（如「1.2万人已拼」）既不含噪点词、也不含「轮播」二字，会原样进入指纹。`sensitiveNumberPattern`（`AccessibilityPageSummaryCollector.kt:44,267`）只把连续 3–24 位数字替换为 `number`，文字部分变化仍穿透。

---

## 结论 3：新指纹清空缓冲并重新截图

**属实。** `CheckoutSnapshotCaptureCoordinator.handleStable`（`CheckoutSnapshotCaptureCoordinator.kt:858-873`）：

```kotlin
val startsNewSession = previous == null ||
    previous.page.observation.shoppingPackage != observation.shoppingPackage ||
    previous.page.observation.pageFingerprint != observation.pageFingerprint
...
previous?.let { oldCheckout ->
    buffer.discard(oldCheckout.slotId)          // 释放旧槽位
    attemptedAutomaticCaptures.removeAll { it.sessionId == oldCheckout.sessionId }
}
ActiveCheckout(sessionId = sessionIdFactory(), ...)  // 全新会话
```

指纹一变即视为新订单边界 → `buffer.discard` 释放旧候选 → 新建会话 → 发起新异步截图。

**抹零链路确认：** `discard`（`CheckoutSnapshotBuffer.kt:543-546`）
→ `releaseCandidates`（`CheckoutSnapshotBuffer.kt:614-621`）
→ `CheckoutSnapshot.release()`（`CheckoutSnapshotBuffer.kt:46`，`imageBytes.fill(0)`）。

**机理比报告更具体：** 若「最后一次漂移把已有图清零、新图又未落入 buffer」发生在提交点击与切到支付包之间，则交接时 `slots[slotId]` 已空 → 最终 `NO_BUFFERED_SNAPSHOT`。此处缓冲策略在时序竞态下确为净负收益。

---

## 结论 4（P0）：敏感支付页仍被截图并送 AI

**三项独立缺陷全部坐实。**

### 缺陷 1：兜底闸门无敏感判定
`maybeCapturePaymentResultScreenshot`（`CheckoutAccessibilityService.kt:1412-1436`）的全部前置条件仅为：兜底进行中 / 15 秒节流 / 90 秒成功冷却 / 当前前台为支付包 / AI 客户端可用。**无 `isSensitivePage` 检查。**

### 缺陷 2：敏感 return 位于错误分支（永远拦不到 UNKNOWN 页）
`observePaymentResultIfApplicable`（`CheckoutAccessibilityService.kt:1264-1299`）控制流：

```kotlin
if (observation.pageRole == CheckoutPageRole.PAYMENT_RESULT) { sawResultPage = true }
if (observation.pageRole != CheckoutPageRole.PAYMENT_RESULT) {
    // 敏感 UNKNOWN 页（如微信密码页）走这里，仅打日志后 continue，不拦截
    continue
}
// 以下敏感检查只在 role == PAYMENT_RESULT 时才可能到达
if (observation.isSensitivePage) { return }
```

循环结束后（`CheckoutAccessibilityService.kt:1403-1404`）：

```kotlin
if (!sawResultPage) {
    maybeCapturePaymentResultScreenshot(paymentPackage, handoffContext)
}
```

敏感的 UNKNOWN 页 `sawResultPage` 为 false → 照常触发截图兜底。

### 缺陷 3：支付成功状态硬编码
`consumePaymentResultScreenshot`（`CheckoutAccessibilityService.kt:1566`）：

```kotlin
paymentStatus = "SUCCESS",
```

且 30 分钟时效闸门（`CheckoutAccessibilityService.kt:1544`）依赖 `occurredAt`，而 `occurredAt` 在 AI 未返回时间时缺省为 `System.currentTimeMillis()`（L1541 `?: System.currentTimeMillis()`），使 `abs(occurredAt - now) ≈ 0` → **闸门自动放行**。只要 AI 从图里读出任意金额即落 `SUCCESS` 证据，未支付时即误生成账单。

### 架构根因
结算页主路径有 `CheckoutSnapshotBuffer.store` 的 `SENSITIVE_PAGE` 拒绝（`CheckoutSnapshotBuffer.kt:193-195`）与 `routeLocked` 的 `PurchaseIntentGateRejection.SENSITIVE_PAGE`（`CheckoutPurchaseIntentCoordinator.kt:360-367`）。但支付结果兜底走**平行独立实现**（`encodePaymentScreenshot` 而非 `encodeScreenshot`，且不经过 buffer），整套校验体系被旁路。

---

## 结论 5：截图仅内存暂存、不落盘

**属实。**
- 对 recognition 目录检索 `FileOutputStream|writeBytes|filesDir|cacheDir|openFileOutput|Environment.getExternalStorage` → **零命中**。
- `CheckoutSnapshot.release()`（`CheckoutSnapshotBuffer.kt:46`）执行 `imageBytes.fill(0)`；`discard`/`clear`/`close` 均经 `releaseCandidates` 调 `release()`，销毁时字节即抹零。
- `DEFAULT_SNAPSHOT_TTL_MILLIS = 120_000L`（`CheckoutSnapshotBuffer.kt:12`），**确为 120 秒**，与文档写的 30 秒不符（文档其余偏差报告已列，均属实）。
  > ⚠️ **2026-09-12 更新**：该"文档 vs 代码"不符已处理 —— **代码 120s 改为 90s**，且**起算点改为"搬入支付过程槽位时刻"**（G6.3 Q2a/Q2b）；文档侧"30 秒"已改为"结算页槽位无倒计时 / 支付过程槽位 90 秒"。本行所述"120 秒"为**当时的代码实测值**，保留作历史取证。

---

## 给排查者的提醒

1. **结论 1 的归因务必精确表述**：事件归属改写 + 稳定协调器指纹相等早返回，两者叠加后，单纯「外来事件」不会重截；能连续 STORED 说明指纹真的在变。报告中「触发器 + 放大器」的定性正确。
2. **结论 4 是真实 P0，且不止一个 if 缺失**，而是一条完整绕过所有敏感防线的平行通道；修复需 P0-a/P0-b/P0-c 三处独立改动（报告修复建议准确）。
3. 现有单测仅覆盖 `PaymentResultParserTest`、`SessionStatusMappingTest`；指纹生成、缓冲 TTL、截图兜底均无自动化覆盖，依赖真机 logcat 验证。
