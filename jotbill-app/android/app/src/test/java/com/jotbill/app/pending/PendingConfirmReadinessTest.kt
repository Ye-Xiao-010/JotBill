package com.jotbill.app.pending

import com.jotbill.app.recognition.MAX_SAFE_MINOR_UNIT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认按钮就绪判据单测（D-21：由"分类 + 账户"扩展为"分类 + 账户 + 金额 > 0"）。
 *
 * 覆盖说明（如实标注边界）：本用例覆盖的是**判据本身**——生产代码
 * `PendingConfirmOverlayManager.updateConfirmEnabled` 以本判据的返回值作为
 * `isEnabled` 的**唯一来源**（非平行实现），故断言判据即为断言按钮状态。
 * JVM 无法覆盖的部分 = 金额输入框的 TextWatcher 挂载与回调（UI 装配），
 * 其真机判定点：0 金额条目弹窗打开 ⇒ 按钮灰；输入金额 ⇒ 变亮；清空 ⇒ 复灰。
 */
class PendingConfirmReadinessTest {

    @Test
    fun readyOnlyWhenCategoryAccountAndPositiveAmountAllPresent() {
        assertTrue(PendingConfirmReadiness.isReady(true, true, 3580L))
        // 三要素缺一不可
        assertFalse(PendingConfirmReadiness.isReady(false, true, 3580L))
        assertFalse(PendingConfirmReadiness.isReady(true, false, 3580L))
        assertFalse(PendingConfirmReadiness.isReady(false, false, 3580L))
    }

    /** D-21 的核心：0 金额占位允许弹窗，但按钮必须置灰直到用户补金额。 */
    @Test
    fun notReadyForZeroAmountPlaceholder() {
        assertFalse(PendingConfirmReadiness.isReady(true, true, 0L))
        assertFalse(PendingConfirmReadiness.isReady(true, true, null))
    }

    @Test
    fun amountBoundsAreInclusive() {
        // 下界：¥0.01 可入账；再低一律不可
        assertTrue(PendingConfirmReadiness.isValidAmount(1L))
        assertFalse(PendingConfirmReadiness.isValidAmount(0L))
        assertFalse(PendingConfirmReadiness.isValidAmount(-1L))
        // 上界：安全整数上界可入账，越界不可（与 PendingConfirmService 校验同界）
        assertTrue(PendingConfirmReadiness.isValidAmount(MAX_SAFE_MINOR_UNIT))
        assertFalse(PendingConfirmReadiness.isValidAmount(MAX_SAFE_MINOR_UNIT + 1))
    }

    @Test
    fun nullAmountIsInvalid() {
        assertFalse(PendingConfirmReadiness.isValidAmount(null))
    }
}
