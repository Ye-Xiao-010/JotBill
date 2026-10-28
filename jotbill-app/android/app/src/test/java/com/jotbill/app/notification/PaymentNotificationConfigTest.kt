package com.jotbill.app.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N-0c：通知监听用户配置的读取与收窄语义。
 *
 * 钉死三条边界：
 * 1. 配置只做**收窄**，解析器不认识的包永不放行（放行只会产生噪声与误报）；
 * 2. 空配置回退内置默认，不允许"清空配置"把匹配面放大成全量通知；
 * 3. 写入后 [PaymentNotificationConfig.invalidate] 让缓存立即失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PaymentNotificationConfigTest {

    private companion object {
        const val PREFS = "payment_notification_config"
        const val WECHAT = "com.tencent.mm"
        const val ALIPAY = "com.eg.android.AlipayGphone"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PaymentNotificationConfig.invalidate()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PaymentNotificationConfig.invalidate()
    }

    private fun writeApps(json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("monitored_apps", json).commit()
        PaymentNotificationConfig.invalidate()
    }

    private fun writeKeywords(json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("filter_keywords", json).commit()
        PaymentNotificationConfig.invalidate()
    }

    @Test
    fun noConfigMeansAllSupportedAppsMonitored() {
        assertTrue(PaymentNotificationConfig.isAppMonitored(context, WECHAT))
        assertTrue(PaymentNotificationConfig.isAppMonitored(context, ALIPAY))
    }

    @Test
    fun unsupportedPackageNeverMonitoredEvenWhenConfigured() {
        writeApps("""[{"packageName":"com.foo.bar","appName":"未知应用","enabled":true}]""")

        assertFalse(PaymentNotificationConfig.isAppMonitored(context, "com.foo.bar"))
        // 反过来：用户确实添加了它，只是没有解析规则 —— 调用方据此给提示而非静默
        assertTrue(PaymentNotificationConfig.isUnsupportedButConfigured(context, "com.foo.bar"))
    }

    @Test
    fun disabledAppIsNotMonitored() {
        writeApps(
            """[{"packageName":"$WECHAT","appName":"微信","enabled":true},""" +
                """{"packageName":"$ALIPAY","appName":"支付宝","enabled":false}]"""
        )

        assertTrue(PaymentNotificationConfig.isAppMonitored(context, WECHAT))
        assertFalse(PaymentNotificationConfig.isAppMonitored(context, ALIPAY))
    }

    @Test
    fun emptyAppListFallsBackToBuiltInWhitelist() {
        writeApps("[]")

        assertTrue(
            "空配置不得放大匹配面，应回退内置白名单",
            PaymentNotificationConfig.isAppMonitored(context, WECHAT)
        )
    }

    @Test
    fun keywordsAreParsedAndEmptyMeansUnconfigured() {
        writeKeywords("""["支付","消费"]""")
        assertEquals(listOf("支付", "消费"), PaymentNotificationConfig.filterKeywords(context))

        writeKeywords("[]")
        assertEquals(
            "空关键词表表示未配置，由调用方回退内置表",
            emptyList<String>(),
            PaymentNotificationConfig.filterKeywords(context)
        )
    }

    @Test
    fun invalidateReloadsFromPrefs() {
        writeApps("[]")
        assertTrue(PaymentNotificationConfig.isAppMonitored(context, WECHAT))

        // 直接写 prefs 但不失效缓存：读到的仍是旧值
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                "monitored_apps",
                """[{"packageName":"$WECHAT","appName":"微信","enabled":false}]"""
            )
            .commit()
        assertTrue(
            "缓存未失效时仍应读到旧值",
            PaymentNotificationConfig.isAppMonitored(context, WECHAT)
        )

        PaymentNotificationConfig.invalidate()
        assertFalse(
            "invalidate 后必须重新读取",
            PaymentNotificationConfig.isAppMonitored(context, WECHAT)
        )
    }

    @Test
    fun malformedJsonFallsBackInsteadOfThrowing() {
        writeApps("这不是 JSON")
        writeKeywords("也不是 JSON")

        assertTrue(PaymentNotificationConfig.isAppMonitored(context, WECHAT))
        assertEquals(emptyList<String>(), PaymentNotificationConfig.filterKeywords(context))
    }
}
