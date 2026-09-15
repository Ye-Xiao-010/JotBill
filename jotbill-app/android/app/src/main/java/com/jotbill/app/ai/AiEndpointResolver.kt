package com.jotbill.app.ai

import com.jotbill.app.data.entity.AiProviderConfigEntity
import java.net.URI

/** 将服务商配置转换为 OpenAI 兼容的聊天接口地址。 */
object AiEndpointResolver {
    /** 返回已经过协议、凭据和查询参数校验的 AI 基地址。 */
    fun baseUrl(config: AiProviderConfigEntity): String {
        val configured = config.apiBaseUrl?.trim()?.trimEnd('/').orEmpty()
        if (configured.isEmpty()) {
            return defaultRoot(config.provider).ifEmpty {
                throw AiChatException("INVALID_ARGUMENT", "自定义服务商必须填写 API 地址")
            }
        }
        return validateBaseUrl(configured)
    }

    /** 校验 AI 地址，避免把凭据或非安全明文端点交给网络客户端。 */
    fun validateBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            throw AiChatException("INVALID_ARGUMENT", "AI API 地址格式无效")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            throw AiChatException("INVALID_ARGUMENT", "AI API 地址必须使用有效的 HTTP(S) 主机")
        }
        if (uri.userInfo != null || uri.rawAuthority?.contains('@') == true || uri.fragment != null) {
            throw AiChatException("INVALID_ARGUMENT", "AI API 地址不得包含用户信息或片段")
        }
        if (uri.query != null) {
            throw AiChatException("INVALID_ARGUMENT", "AI API 地址不得包含查询参数")
        }
        if (scheme == "http" && !isLoopbackHost(host)) {
            throw AiChatException("INVALID_ARGUMENT", "非回环 AI API 地址必须使用 HTTPS")
        }

        val path = uri.path.orEmpty().lowercase()
        if (listOf("api_key", "apikey", "access_token", "password", "secret", "credential")
                .any(path::contains)) {
            throw AiChatException("INVALID_ARGUMENT", "AI API 地址路径疑似包含凭据")
        }
        return normalized
    }

    fun chatCompletions(config: AiProviderConfigEntity): String {
        val configured = config.apiBaseUrl?.trim()?.trimEnd('/').orEmpty()
        val base = baseUrl(config)
        if (base.endsWith("/chat/completions")) return base

        val defaultRoot = defaultRoot(config.provider)
        if (configured.isEmpty() || configured == defaultRoot) {
            return defaultBase(config.provider)
        }
        // 用户按"根域习惯"填地址（如 https://api.openai.com）时补 /v1 版本段，
        // 否则拼出 <host>/chat/completions 直接 404；已带 v1/v2/v4 等版本段的不动。
        val withVersion = if (hasVersionSegment(base)) base else "$base/v1"
        return "$withVersion/chat/completions"
    }

    /** 地址路径中是否已含版本段（v1/v2/v4 等）。 */
    private fun hasVersionSegment(base: String): Boolean =
        base.substringAfter("://", "").substringAfter('/', "")
            .split('/')
            .any { it.matches(Regex("v[0-9]+")) }

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

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1"
}

class AiChatException(val code: String, override val message: String) : Exception(message)
