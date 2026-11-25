package com.jotbill.app.ai

import com.jotbill.app.data.entity.AiProviderConfigEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiEndpointResolverTest {
    @Test
    fun `qwen 根地址解析为兼容模式聊天接口`() {
        val config = config(provider = "qwen", baseUrl = "https://dashscope.aliyuncs.com")

        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `自定义版本地址追加聊天路径`() {
        val config = config(provider = "custom", baseUrl = "https://example.com/v1/")

        assertEquals(
            "https://example.com/v1/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `根域习惯地址自动补 v1 版本段`() {
        // 此前拼出 <host>/chat/completions 直接 404，而 testAiProvider 只 GET 根路径
        // 会误报"可访问"，用户无从定位
        val config = config(provider = "custom", baseUrl = "https://api.openai.com")

        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `带路由前缀但无版本段的地址同样补 v1`() {
        val config = config(provider = "custom", baseUrl = "https://proxy.example.com/openai")

        assertEquals(
            "https://proxy.example.com/openai/v1/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `非 v1 版本段不被二次追加`() {
        val config = config(provider = "custom", baseUrl = "https://example.com/api/v2")

        assertEquals(
            "https://example.com/api/v2/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `完整聊天地址保持不变`() {
        val config = config(
            provider = "custom",
            baseUrl = "https://example.com/api/chat/completions"
        )

        assertEquals(
            "https://example.com/api/chat/completions",
            AiEndpointResolver.chatCompletions(config)
        )
    }

    @Test
    fun `deepseek 默认地址和根地址均解析为聊天接口`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiEndpointResolver.chatCompletions(config(provider = "deepseek", baseUrl = null))
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiEndpointResolver.chatCompletions(
                config(provider = "deepseek", baseUrl = "https://api.deepseek.com")
            )
        )
    }

    @Test
    fun `自定义服务商缺少地址时拒绝请求`() {
        val error = assertThrows(AiChatException::class.java) {
            AiEndpointResolver.chatCompletions(config(provider = "custom", baseUrl = null))
        }

        assertEquals("INVALID_ARGUMENT", error.code)
    }

    private fun config(provider: String, baseUrl: String?): AiProviderConfigEntity =
        AiProviderConfigEntity(
            id = "provider-id",
            name = "测试服务商",
            provider = provider,
            model = "test-model",
            apiKeyEncrypted = null,
            apiBaseUrl = baseUrl,
            isDefault = true,
            supportsVision = false,
            createdAt = 1,
            updatedAt = 1
        )
}
