package com.jotbill.app.ai

import com.jotbill.app.data.entity.AiProviderConfigEntity

/** 将服务商配置转换为 OpenAI 兼容的聊天接口地址。 */
object AiEndpointResolver {
    fun chatCompletions(config: AiProviderConfigEntity): String {
        val configured = config.apiBaseUrl?.trim()?.trimEnd('/').orEmpty()
        val base = configured.ifEmpty { defaultBase(config.provider) }
        if (base.endsWith("/chat/completions")) return base

        val defaultRoot = defaultRoot(config.provider)
        if (configured.isEmpty() || configured == defaultRoot) {
            return defaultBase(config.provider)
        }
        return "$base/chat/completions"
    }

    private fun defaultRoot(provider: String): String = when (provider) {
        "qwen" -> "https://dashscope.aliyuncs.com"
        "glm" -> "https://open.bigmodel.cn"
        "siliconflow" -> "https://api.siliconflow.cn"
        else -> ""
    }

    private fun defaultBase(provider: String): String = when (provider) {
        "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        "glm" -> "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        "siliconflow" -> "https://api.siliconflow.cn/v1/chat/completions"
        "custom" -> throw AiChatException("INVALID_ARGUMENT", "自定义服务商必须填写 API 地址")
        else -> throw AiChatException("INVALID_ARGUMENT", "不支持的 AI 服务商: $provider")
    }
}

class AiChatException(val code: String, override val message: String) : Exception(message)
