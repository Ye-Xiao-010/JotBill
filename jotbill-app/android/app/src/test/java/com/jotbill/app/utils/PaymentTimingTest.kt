package com.jotbill.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PaymentTiming] 的口径自检。
 *
 * 两个常量在 KDoc 里被声明为"必须同值"，但它们是两个独立的 `const val`。
 * 没有断言的话，将来只改一个不会被任何用例发现 —— 而"两套阈值不一致时，
 * 先失效的那道形同虚设"正是本对象要消除的问题。
 */
class PaymentTimingTest {

    @Test
    fun sameTxnWindowAndConsumedCooldownStayInSync() {
        assertEquals(
            "同一笔判定窗与消费后冷却必须同值（15 秒），改一漏一会让其中一道防线失效",
            PaymentTiming.SAME_TXN_WINDOW_MILLIS,
            PaymentTiming.CONSUMED_COOLDOWN_MILLIS
        )
    }

    @Test
    fun sameTxnWindowIsFifteenSeconds() {
        assertEquals(
            "15 秒是用户 2026-10-24 裁定的口径；改这个值必须同步改验收文档与全部 KDoc",
            15_000L,
            PaymentTiming.SAME_TXN_WINDOW_MILLIS
        )
    }
}
