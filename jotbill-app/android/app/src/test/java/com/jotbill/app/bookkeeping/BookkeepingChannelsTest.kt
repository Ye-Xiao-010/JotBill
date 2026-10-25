package com.jotbill.app.bookkeeping

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
}
