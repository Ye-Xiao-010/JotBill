package com.jotbill.app.bookkeeping

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「自动记账通知」开关：默认开；关闭后 [AutoBookkeepingNotifier] 不再发通知，
 * 重新打开恢复发送。开关只影响通知，不影响入账（入账侧不读该偏好）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoBookkeepingNotifierSwitchTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `默认开启时发送通知`() {
        assertTrue(BookkeepingChannels.isAutoBookedNotificationEnabled(context))

        AutoBookkeepingNotifier(context).notifyAutoBooked(
            billId = "bill-1", amountMinor = 1_000L, type = "expense",
            channelLabel = "支付宝", merchant = "测试商户"
        )

        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `关闭后不再发送通知`() {
        BookkeepingChannels.setAutoBookedNotificationEnabled(context, false)

        AutoBookkeepingNotifier(context).notifyAutoBooked(
            billId = "bill-2", amountMinor = 2_000L, type = "expense",
            channelLabel = "微信支付", merchant = "测试商户"
        )

        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun `重新开启后恢复发送`() {
        BookkeepingChannels.setAutoBookedNotificationEnabled(context, false)
        BookkeepingChannels.setAutoBookedNotificationEnabled(context, true)

        AutoBookkeepingNotifier(context).notifyAutoBooked(
            billId = "bill-3", amountMinor = 3_000L, type = "refund",
            channelLabel = "银行短信", merchant = null
        )

        assertEquals(1, shadowOf(manager).size())
    }
}
