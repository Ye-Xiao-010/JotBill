package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-9「敏感页锁存提前解除」判据单测。
 *
 * ## 为什么必须存在这一组用例
 *
 * 本项触碰**不变式 1「敏感页零截图」**（项目最硬的红线）。解除逻辑一旦写宽，
 * 密码键盘/付款码就可能被截并编码外发，而"少拦一帧"在真机上几乎不可观测。
 * 因此解除判据必须由负向用例钉死：**N1 空树帧交替 / N2 支付包内 / N3 UNKNOWN**。
 *
 * ## 关于"零截图"的等价信号（JVM 边界，如实标注）
 *
 * JVM 无法驱动 `AccessibilityService` 的截图动作，故 N1 断言的是**断言截图门禁
 * 恒为 blocked**：`CheckoutAccessibilityService` 中该门禁是截图前的最后一道页面
 * 判定，命中即 `publishAssertedOverlayResult(REJECTED, SENSITIVE_PAGE)` + `return`，
 * **其后不存在任何 `takeScreenshot` 调用** ⇒ 门禁恒 blocked 等价于零截图。
 * 真机判定点：logcat 全程不出现 `断言截图受理: pkg=...`，只出现
 * `断言截图拒绝: 敏感页锁存窗口内`。
 *
 * 本用例驱动的是生产代码使用的**同一个** [SensitivePageLatch] 实例形态，
 * 不是平行实现。
 */
class SensitivePageLatchTest {

    private companion object {
        const val WINDOW = 60_000L
        const val BASE = 1_700_000_000_000L
        const val SHOPPING = "com.jingdong.app.mall"

        /** 支付包（不在购物白名单内）。 */
        const val PAYMENT = "com.tencent.mm"
    }

    private fun latch() = SensitivePageLatch(blockMillis = WINDOW)

    /** 购物包内一帧。 */
    private fun SensitivePageLatch.frame(
        now: Long,
        role: CheckoutPageRole,
        sensitive: Boolean = false,
        pkg: String = SHOPPING
    ) = onShoppingObservation(
        shoppingPackage = pkg,
        pageRole = role,
        isSensitivePage = sensitive,
        now = now
    )

    // ---------------------------------------------------------------- 正向

    @Test
    fun releasesAfterThreeConsecutiveIdentifiableNonSensitiveShoppingFrames() {
        val latch = latch()
        assertFalse(
            "第 1 帧不得解除",
            latch.frame(BASE + 100, CheckoutPageRole.PRODUCT_DETAIL)
        )
        assertFalse(
            "第 2 帧不得解除（阈值 = ${SensitivePageLatch.RELEASE_FRAME_THRESHOLD}）",
            latch.frame(BASE + 200, CheckoutPageRole.PRODUCT_DETAIL)
        )
        assertTrue(
            "第 3 帧必须解除",
            latch.frame(BASE + 300, CheckoutPageRole.PRODUCT_DETAIL)
        )
        assertFalse(
            "解除后断言通道不再被锁存拦截",
            latch.isAssertionCaptureBlocked(BASE + 301)
        )
    }

    @Test
    fun releasesForCartAndCheckoutRolesToo() {
        for (role in listOf(CheckoutPageRole.CART, CheckoutPageRole.CHECKOUT)) {
            val latch = latch()
            var released = false
            repeat(SensitivePageLatch.RELEASE_FRAME_THRESHOLD) { index ->
                released = latch.frame(BASE + 100L * (index + 1), role)
            }
            assertTrue("$role 属于解除集合，3 帧后应解除", released)
        }
    }

    /** 解除集合必须是 G1-8 定稿的三类，不得被顺手放宽。 */
    @Test
    fun releaseRoleSetMatchesSpecification() {
        assertEquals(
            setOf(
                CheckoutPageRole.PRODUCT_DETAIL,
                CheckoutPageRole.CART,
                CheckoutPageRole.CHECKOUT
            ),
            SensitivePageLatch.RELEASE_PAGE_ROLES
        )
        assertEquals("G1-8 定稿 N = 3 帧", 3, SensitivePageLatch.RELEASE_FRAME_THRESHOLD)
        assertTrue(
            "解除集合必须含 CHECKOUT（结算页可截 ⇒ 可证已离开支付）",
            SensitivePageLatch.RELEASE_PAGE_ROLES.contains(CheckoutPageRole.CHECKOUT)
        )
        // 护栏：严禁改用 canBufferUserRetrySnapshot()（5 成员，含 SHOPPING_HOME/UNKNOWN）
        assertFalse(
            "SHOPPING_HOME 不得进入解除集合",
            SensitivePageLatch.RELEASE_PAGE_ROLES.contains(CheckoutPageRole.SHOPPING_HOME)
        )
        assertFalse(
            "UNKNOWN 不得进入解除集合",
            SensitivePageLatch.RELEASE_PAGE_ROLES.contains(CheckoutPageRole.UNKNOWN)
        )
    }

    // ---------------------------------------------------------------- 负向

    /**
     * **N1**｜密码页 ↔ 空树帧交替 ⇒ 全程零截图。
     *
     * 空树帧最容易让人误以为"页面已离开敏感页"：它读不到任何文本，采集器如实
     * 报告 `isSensitivePage = false` + `pageRole = UNKNOWN` —— **"非敏感"在这里
     * 是默认值而非证据**。本用例把敏感帧的角色设为 `CHECKOUT`（看起来"合格"），
     * 以最苛刻形态证明：敏感优先于角色，空树帧既不合格也不得重置已有锁存。
     */
    @Test
    fun passwordPageAlternatingWithEmptyTreeFramesStaysBlocked() {
        val latch = latch()
        var now = BASE
        var blockedFrames = 0
        var totalFrames = 0
        repeat(20) {
            // 密码页帧：购物应用内敏感页（密码键盘/付款码）
            now += 100
            latch.frame(now, CheckoutPageRole.CHECKOUT, sensitive = true)
            totalFrames++
            if (latch.isAssertionCaptureBlocked(now)) blockedFrames++

            // 空树帧：text 读不到 ⇒ isSensitivePage=false、pageRole=UNKNOWN
            now += 100
            latch.frame(now, CheckoutPageRole.UNKNOWN)
            totalFrames++
            if (latch.isAssertionCaptureBlocked(now)) blockedFrames++
        }
        assertEquals(
            "20 轮交替共 $totalFrames 帧必须全程被拦截（零截图）",
            totalFrames,
            blockedFrames
        )
    }

    /** **N2**｜支付包内不解除：白名单外的包不具有解除资格。 */
    @Test
    fun doesNotReleaseInsidePaymentPackage() {
        val latch = latch()
        latch.onPaymentSensitiveFrame(BASE)
        var released = false
        repeat(10) { index ->
            released = latch.frame(
                now = BASE + 100L * (index + 1),
                role = CheckoutPageRole.CHECKOUT,
                pkg = PAYMENT
            ) || released
        }
        assertFalse("支付包帧不得触发解除", released)
        assertEquals("非白名单包不得累积连续帧", 0, latch.debugQualifiedFrameStreak())
        assertTrue(
            "支付包内的锁存必须继续生效",
            latch.isAssertionCaptureBlocked(BASE + 1_000)
        )
    }

    /** **N3**｜UNKNOWN 帧不解除：角色不可识别时保守维持锁存。 */
    @Test
    fun doesNotReleaseOnUnknownFrames() {
        val latch = latch()
        // 先建立锁存（否则"不解除"无从谈起）
        latch.onPaymentSensitiveFrame(BASE)
        var released = false
        repeat(100) { index ->
            released = latch.frame(
                now = BASE + 100L * (index + 1),
                role = CheckoutPageRole.UNKNOWN
            ) || released
        }
        assertFalse("UNKNOWN（空树/转场/文本不足）不得解除", released)
        assertEquals(0, latch.debugQualifiedFrameStreak())
        assertTrue(
            "角色不可识别时必须维持锁存",
            latch.isAssertionCaptureBlocked(BASE + 10_000)
        )
    }

    /** 补充负向｜`SHOPPING_HOME`（首页）不解除 —— G1-2 明确"首页不截"。 */
    @Test
    fun doesNotReleaseOnShoppingHomeFrames() {
        val latch = latch()
        latch.onPaymentSensitiveFrame(BASE)
        var released = false
        repeat(10) { index ->
            released = latch.frame(
                now = BASE + 100L * (index + 1),
                role = CheckoutPageRole.SHOPPING_HOME
            ) || released
        }
        assertFalse("首页不在解除集合内", released)
        assertTrue(latch.isAssertionCaptureBlocked(BASE + 1_000))
    }

    /** 补充负向｜`PAYMENT_HANDOFF` / `PAYMENT_RESULT` 亦不在解除集合内。 */
    @Test
    fun doesNotReleaseOnPaymentSideRoles() {
        for (role in listOf(CheckoutPageRole.PAYMENT_HANDOFF, CheckoutPageRole.PAYMENT_RESULT)) {
            val latch = latch()
            latch.onPaymentSensitiveFrame(BASE)
            var released = false
            repeat(10) { index ->
                released = latch.frame(BASE + 100L * (index + 1), role) || released
            }
            assertFalse("$role 不得解除", released)
        }
    }

    // ---------------------------------------------------------------- 连续性

    /** 裁定 A｜`root == null`（不可观测帧）必须中断连续计数。 */
    @Test
    fun unobservableFrameBreaksStreak() {
        val latch = latch()
        latch.frame(BASE + 100, CheckoutPageRole.PRODUCT_DETAIL)
        latch.frame(BASE + 200, CheckoutPageRole.PRODUCT_DETAIL)
        // 空树早退帧：走不到敏感判定
        latch.onUnobservableFrame()
        assertFalse(
            "中断后第 1 帧不得解除（2 + 空树 + 1 不是真连续）",
            latch.frame(BASE + 300, CheckoutPageRole.PRODUCT_DETAIL)
        )
        assertFalse(
            "中断后第 2 帧仍不得解除（计数从 0 重新累积）",
            latch.frame(BASE + 400, CheckoutPageRole.PRODUCT_DETAIL)
        )
        assertTrue(
            "中断后必须重新连续满 ${SensitivePageLatch.RELEASE_FRAME_THRESHOLD} 帧才解除",
            latch.frame(BASE + 500, CheckoutPageRole.PRODUCT_DETAIL)
        )
    }

    /** 跨支付停留的合格帧不得凑数。 */
    @Test
    fun paymentSensitiveFrameBreaksStreak() {
        val latch = latch()
        latch.frame(BASE + 100, CheckoutPageRole.PRODUCT_DETAIL)
        latch.frame(BASE + 200, CheckoutPageRole.PRODUCT_DETAIL)
        latch.onPaymentSensitiveFrame(BASE + 300)
        assertFalse(
            "支付停留后旧合格帧作废",
            latch.frame(BASE + 400, CheckoutPageRole.PRODUCT_DETAIL)
        )
    }

    /** 解除后再次遇到敏感帧 ⇒ 自动重新拦截（水位语义自洽，无需显式失效）。 */
    @Test
    fun sensitiveFrameAfterReleaseReArmsLatch() {
        val latch = latch()
        latch.onPaymentSensitiveFrame(BASE)
        repeat(SensitivePageLatch.RELEASE_FRAME_THRESHOLD) { index ->
            latch.frame(BASE + 100L * (index + 1), CheckoutPageRole.PRODUCT_DETAIL)
        }
        assertFalse(latch.isAssertionCaptureBlocked(BASE + 400))

        val sensitiveAt = BASE + 500
        latch.frame(sensitiveAt, CheckoutPageRole.CHECKOUT, sensitive = true)
        assertTrue(
            "解除后再次出现敏感页必须立即重新锁存",
            latch.isAssertionCaptureBlocked(sensitiveAt + 1)
        )
    }

    /** 窗口自然过期后仍不拦截（回归原语义，避免"解除"把过期判定改坏）。 */
    @Test
    fun latchExpiresNaturallyAfterWindow() {
        val latch = latch()
        latch.onPaymentSensitiveFrame(BASE)
        assertTrue(latch.isAssertionCaptureBlocked(BASE + WINDOW - 1))
        assertFalse(latch.isAssertionCaptureBlocked(BASE + WINDOW))
    }

    // -------------------------------------------------- 红线关键证据：通道隔离

    /**
     * **解除不得放松自动兜底通道**（G1-8 风控①）。
     *
     * 这是"水位方案"相对"清零两个时间戳"方案的核心差异：断言（手动）通道解除后，
     * 支付结果页截图兜底（**自动**通道）必须仍受锁存保护。
     */
    @Test
    fun releaseDoesNotUnblockPaymentFallbackChannel() {
        val latch = latch()
        latch.onPaymentSensitiveFrame(BASE)
        repeat(SensitivePageLatch.RELEASE_FRAME_THRESHOLD) { index ->
            latch.frame(BASE + 100L * (index + 1), CheckoutPageRole.CART)
        }
        val at = BASE + 400
        assertFalse("断言通道应已解除", latch.isAssertionCaptureBlocked(at))
        assertTrue(
            "自动兜底通道的锁存窗口不得被解除缩短",
            latch.isPaymentFallbackBlocked(at)
        )
        assertTrue(
            "自动兜底的锁存年龄仍是真实值",
            latch.paymentLatchAgeMillis(at) < WINDOW
        )
    }

    /** 从未见过敏感页时两条通道都不拦截（避免"解除"引入新的过度拦截）。 */
    @Test
    fun noLatchMeansNoBlocking() {
        val latch = latch()
        assertFalse(latch.isAssertionCaptureBlocked(BASE))
        assertFalse(latch.isPaymentFallbackBlocked(BASE))
    }
}
