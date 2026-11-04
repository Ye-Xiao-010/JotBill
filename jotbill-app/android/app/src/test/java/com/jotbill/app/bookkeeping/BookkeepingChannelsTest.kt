package com.jotbill.app.bookkeeping

import com.jotbill.app.recognition.RecognitionPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道注册表（单一事实源）完整性：键唯一、支付渠道默认开、短信默认关。
 * RN 设置页与桥接层全部从这份注册表派生，这里钉死它的基本形状。
 */
class BookkeepingChannelsTest {

    @Test
    fun `渠道键唯一`() {
        val keys = BookkeepingChannels.CHANNELS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `八个渠道在位且支付类默认开短信默认关`() {
        val byKey = BookkeepingChannels.CHANNELS.associateBy { it.key }
        assertEquals(8, byKey.size)
        listOf("alipay", "wechat", "unionpay", "jd", "meituan", "pdd", "douyin").forEach {
            assertTrue("渠道 $it 应默认开启", byKey[it]!!.defaultEnabled)
        }
        assertTrue("短信通道 M6 前默认关", !byKey["mms"]!!.defaultEnabled)
    }

    @Test
    fun `每个渠道至少一个监听包名`() {
        BookkeepingChannels.CHANNELS.forEach { info ->
            assertTrue("渠道 ${info.key} 缺监听包名", info.packages.isNotEmpty())
        }
    }

    @Test
    fun `渠道键校验与注册表一致`() {
        BookkeepingChannels.CHANNELS.forEach { info ->
            assertTrue(BookkeepingChannels.isValidChannelKey(info.key))
        }
        assertTrue(!BookkeepingChannels.isValidChannelKey("nonexistent"))
    }

    /**
     * B2 一致性锁：每个渠道包必须落在 `RecognitionPackages` 中与其 kind 对应的
     * 识别集合里（渠道表是识别白名单的真子集视图，两表不得漂移）。
     */
    @Test
    fun `渠道 kind 与识别包名白名单一致`() {
        BookkeepingChannels.CHANNELS.forEach { info ->
            info.packages.forEach { pkg ->
                when (info.kind) {
                    BookkeepingChannels.ChannelKind.PAYMENT -> assertTrue(
                        "渠道 ${info.key} 的 $pkg 应属于识别层支付包白名单",
                        pkg in RecognitionPackages.PAYMENT_PACKAGES
                    )
                    BookkeepingChannels.ChannelKind.SHOPPING_CORE -> assertTrue(
                        "渠道 ${info.key} 的 $pkg 应属于识别层核心购物包",
                        pkg in RecognitionPackages.SHOPPING_PACKAGES_CORE
                    )
                    BookkeepingChannels.ChannelKind.SHOPPING_RESULT_ONLY -> assertTrue(
                        "渠道 ${info.key} 的 $pkg 应属于识别层降级购物包",
                        pkg in RecognitionPackages.SHOPPING_PACKAGES_RESULT_ONLY
                    )
                    BookkeepingChannels.ChannelKind.NOTIFICATION_RULES_ONLY -> assertTrue(
                        "通知规则渠道的 $pkg 不得进识别白名单",
                        pkg !in RecognitionPackages.SHOPPING_PACKAGES &&
                            pkg !in RecognitionPackages.PAYMENT_PACKAGES
                    )
                }
            }
        }
    }

    /** B3 相关口径钉死：短信渠道为通知规则档且默认关（用户主动开启才生效）。 */
    @Test
    fun `银行短信渠道为通知规则档且默认关`() {
        val mms = BookkeepingChannels.CHANNELS.first { it.key == "mms" }
        assertEquals(BookkeepingChannels.ChannelKind.NOTIFICATION_RULES_ONLY, mms.kind)
        assertTrue("短信渠道默认关（B3：用户主动开启才生效）", !mms.defaultEnabled)
    }
}
