package com.jotbill.app.recognition

/**
 * 敏感页锁存 + D-9「提前解除」（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ## 锁存要解决的问题
 *
 * 支付密码页与随后的**空节点树帧**交替出现：空树帧自身无法判定敏感，若只按
 * 单轮结果拦截，截图仍会在密码页停留期间被触发。因此把"见过敏感页"锁存一个
 * 短窗口（[blockMillis]），窗口内一律禁止对应通道截图。
 *
 * ## 为什么需要"提前解除"
 *
 * 锁存无上限地按时间自然过期，会误伤：付款完成后数秒内回到购物 App 再次购买，
 * 手动补截被拒 60s（G1-7 实测：手动通道被拒、自动通道不受影响）。而此刻页面
 * 是**可读的、明确的、非敏感的购物页**——锁存已完成使命却继续拦截（G1-8 裁定修）。
 *
 * ## 解除条件（G1-8 定稿，用户 2026-09-11 20:07 确认）
 *
 * ```
 * 前台包名 ∈ DEFAULT_SUPPORTED_SHOPPING_PACKAGES
 *   ∧ pageRole ∈ {PRODUCT_DETAIL, CART, CHECKOUT}   ← 正向证据：识别到具体购物角色
 *   ∧ !isSensitivePage                              ← 非敏感
 *   ⇒ 连续 N = 3 帧后解除
 * ```
 *
 * **③ 为什么"非敏感"不能单独作为解除条件**（用户明确要求写进注释）：
 * `AccessibilityPageSummaryCollector` 的 `isSensitivePage = accumulator.sensitive ||
 * containsSensitiveSignal(lowerText)` —— **采集不到文本时两项都为 false ⇒ 报告
 * "不敏感"**；空文本下 `classifyPageRole` 一路落空 ⇒ `UNKNOWN`。也就是说：
 * **"非敏感"是默认值，不是证据**——页面上真有密码键盘、但树读不到内容时，
 * 采集器如实地说"我不敏感、我也不知道这是哪一页"。若"非敏感即解除"，等于
 * **把"读不到"当成"安全"**，恰恰废掉本锁存存在的唯一理由。故必须叠加
 * "读到足够文本并判定为具体购物角色"这一**正向证据**：能截的页面，才能证明
 * 用户已安全离开支付。`UNKNOWN` 与 `SHOPPING_HOME` 明确排除（前者定义即
 * "文本不足/转场/无法判断"；后者是首页，按 G1-2 不截）。
 *
 * ## 解除方式：水位，而非清零时间戳
 *
 * 解除**不清除**任何锁存时间戳，只抬高 [releaseWatermarkAt]。理由：本类的
 * 两个时间戳被**两条门禁**消费，而它们的隐私敏感度不同——
 *
 * | 消费方 | 通道 | 解除是否应生效 |
 * |---|---|---|
 * | [isAssertionCaptureBlocked]（用户断言截图） | 手动 | **是**（D-9 的目标） |
 * | [isPaymentFallbackBlocked]（支付结果页兜底） | **自动** | **否**（G1-8 风控①：支付包内的锁存有效期逻辑不变） |
 *
 * 若按"清零两个时间戳"实现，会顺带解锁自动兜底路径；水位方案让两条通道
 * 各自取用，**自动兜底路径零改动** —— 严格符合"宁可少解除，不可多解除"。
 *
 * 语义自洽：解除后若**再次**观察到敏感页，锁存时间戳会晚于水位 ⇒
 * 自动重新拦截，无需显式失效。
 */
internal class SensitivePageLatch(
    /** 锁存窗口。由调用方传入（生产 = `SENSITIVE_PAGE_SCREENSHOT_BLOCK_MILLIS`）。 */
    private val blockMillis: Long,
    /** 连续确认帧数阈值。 */
    private val releaseFrameThreshold: Int = RELEASE_FRAME_THRESHOLD
) {

    companion object {
        /** 连续确认帧数（G1-8 风控②，用户定稿 N=3：兼顾流畅性与安全性）。 */
        const val RELEASE_FRAME_THRESHOLD = 3

        /**
         * "明确非敏感购物页"的允许角色（G1-8 定稿）。
         *
         * ⚠️ **严禁改用 `CheckoutPageRole.canBufferUserRetrySnapshot()`**：
         * 那是"手动截图允许页"（5 个成员，含 `SHOPPING_HOME` 与 `UNKNOWN`），
         * 比解除条件**宽**，复用即等于让空树帧（`UNKNOWN`）也能解除 —— 红线降级。
         */
        val RELEASE_PAGE_ROLES = setOf(
            CheckoutPageRole.PRODUCT_DETAIL,
            CheckoutPageRole.CART,
            CheckoutPageRole.CHECKOUT
        )
    }

    /** 最近一次在支付应用中观察到敏感页面的时间。 */
    private var lastSensitivePaymentPageAt = 0L

    /** 最近一次在购物应用中观察到敏感页面的时间。 */
    private var lastSensitiveShoppingPageAt = 0L

    /** 最近一次"读到明确非敏感购物页 ×N 帧"而解除的时刻；0 = 从未解除。 */
    private var releaseWatermarkAt = 0L

    /** 当前连续合格帧计数；任何不合格帧（敏感/UNKNOWN/首页/非白名单包/不可观测）归零。 */
    private var qualifiedFrameStreak = 0

    /**
     * 支付包路径：观察到敏感帧。
     *
     * 顺带中断连续计数：跨支付停留的"合格帧"不构成**连续**证据。
     */
    fun onPaymentSensitiveFrame(now: Long) {
        lastSensitivePaymentPageAt = now
        qualifiedFrameStreak = 0
    }

    /**
     * 购物包路径：本帧**连节点树都取不到**（`root == null`）。
     *
     * 该帧同样走不到敏感判定，只中断连续计数 —— 否则"合格页×2 → 空树帧 →
     * 合格页×1"会凑满 3 帧，而它们并非真正连续（裁定 A）。
     */
    fun onUnobservableFrame() {
        qualifiedFrameStreak = 0
    }

    /**
     * 购物包路径：观察到一帧。每帧必须调用一次。
     *
     * @return true 表示**本帧触发了解除**（供调用方打日志取证）。
     */
    fun onShoppingObservation(
        shoppingPackage: String,
        pageRole: CheckoutPageRole,
        isSensitivePage: Boolean,
        now: Long
    ): Boolean {
        if (isSensitivePage) {
            lastSensitiveShoppingPageAt = now
            qualifiedFrameStreak = 0
            return false
        }
        // 正向证据：必须同时具备（a）白名单购物包（b）具体购物角色。
        // 只看 (c) 非敏感是不够的——见类注释③：空树帧天然"非敏感"。
        val qualifies = shoppingPackage in DEFAULT_SUPPORTED_SHOPPING_PACKAGES &&
            pageRole in RELEASE_PAGE_ROLES
        if (!qualifies) {
            qualifiedFrameStreak = 0
            return false
        }
        qualifiedFrameStreak++
        if (qualifiedFrameStreak < releaseFrameThreshold) {
            return false
        }
        releaseWatermarkAt = now
        return true
    }

    /**
     * 断言截图（手动通道）门禁：水位之后的锁存才生效。
     *
     * 等价于原 `now - maxOf(支付锁存, 购物锁存) < 窗口`，但**解除后旧锁存不再拦截**。
     * 该门禁是本通道唯一继承自自动链路的页面判定，也是不变式 1 的入口。
     */
    fun isAssertionCaptureBlocked(now: Long): Boolean =
        assertionLatchAgeMillis(now) < blockMillis

    /**
     * 诊断用：断言门禁当前生效的锁存年龄。
     *
     * 从未见过敏感页、或解除之后未再出现敏感页 ⇒ 返回 [Long.MAX_VALUE]
     * （表示"无生效锁存"，**不是**"很久以前"），供拒绝日志取证。
     */
    fun assertionLatchAgeMillis(now: Long): Long {
        val latestLatchAt = maxOf(lastSensitivePaymentPageAt, lastSensitiveShoppingPageAt)
        // 从未见过敏感页：无锁存可言。
        if (latestLatchAt == 0L) return Long.MAX_VALUE
        // 解除之后没有再出现过敏感页：锁存已被解除。
        if (latestLatchAt <= releaseWatermarkAt) return Long.MAX_VALUE
        return now - latestLatchAt
    }

    /**
     * 支付结果页截图兜底（**自动**通道）门禁：**不受解除影响**。
     *
     * 语义与 D-9 之前完全一致（G1-8 风控①：支付包内的锁存有效期逻辑不变）。
     */
    fun isPaymentFallbackBlocked(now: Long): Boolean =
        now - lastSensitivePaymentPageAt < blockMillis

    /** 诊断用：支付侧锁存距今毫秒数（供拒绝日志取证，不参与判定）。 */
    fun paymentLatchAgeMillis(now: Long): Long = now - lastSensitivePaymentPageAt

    /** 测试/诊断用：当前连续合格帧计数。 */
    internal fun debugQualifiedFrameStreak(): Int = qualifiedFrameStreak
}
