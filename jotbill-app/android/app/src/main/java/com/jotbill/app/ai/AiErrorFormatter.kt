package com.jotbill.app.ai

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 携带 HTTP 状态码与服务端解析后错误信息的 AI 网络异常。 */
class AiHttpException(
    val statusCode: Int,
    val serverMessage: String? = null
) : IOException(AiErrorFormatter.formatHttpError(statusCode, serverMessage))

/** 统一解析 AI 服务商错误响应体并转换为用户可定位的中文错误提示。 */
object AiErrorFormatter {
    private const val MAX_SERVER_MESSAGE_LEN = 160

    /** 从 OpenAI / DashScope / DeepSeek / GLM / SiliconFlow 错误响应体中提取可读原因。 */
    fun extractServerErrorMessage(body: String?): String? {
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.startsWith("<")) return null

        val extracted = runCatching {
            val root = JSONObject(trimmed)
            val errorObj = root.optJSONObject("error")
            listOfNotNull(
                errorObj?.optString("message")?.takeIf { it.isNotBlank() },
                root.opt("error")?.takeIf { it is String }?.toString()?.takeIf { it.isNotBlank() },
                root.optString("message").takeIf { it.isNotBlank() },
                root.optString("msg").takeIf { it.isNotBlank() },
                root.optString("detail").takeIf { it.isNotBlank() }
            ).firstOrNull()
        }.getOrNull()

        val raw = extracted ?: if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) trimmed else null
        return raw
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_SERVER_MESSAGE_LEN)
    }

    /** 按 HTTP 状态码给出明确的排查方向，并附带厂商返回的具体信息。 */
    fun formatHttpError(statusCode: Int, serverMessage: String? = null): String {
        val base = when (statusCode) {
            400 -> "请求参数或模型能力不支持 (HTTP 400)"
            401 -> "API Key 无效或已过期 (HTTP 401)"
            402 -> "AI 账户余额不足，请充值后重试 (HTTP 402)"
            403 -> "没有访问该模型或接口的权限 (HTTP 403)"
            404 -> "模型名称或 API 地址不存在 (HTTP 404)"
            429 -> "AI 请求过于频繁或额度超限，请稍后重试 (HTTP 429)"
            in 500..599 -> "AI 服务暂时不可用 (HTTP $statusCode)"
            else -> "AI 服务返回错误 (HTTP $statusCode)"
        }
        val clean = serverMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        return if (clean != null) "$base：$clean" else base
    }

    /** 校验真实 POST chat/completions 探针响应，避免 404 或 HTML 登录页被误判为成功。 */
    fun evaluateTestResponse(statusCode: Int, body: String?, model: String): Pair<Boolean, String> {
        if (statusCode !in 200..299) {
            return false to formatHttpError(statusCode, extractServerErrorMessage(body))
        }
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return false to "接口返回空响应，请检查 API 地址是否正确"
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return false to "接口未返回有效 JSON，请检查 API 地址是否正确"
        if (root.has("error") && !root.isNull("error")) {
            val detail = extractServerErrorMessage(trimmed) ?: "未知错误"
            return false to "AI 服务返回错误：$detail"
        }
        if (root.has("choices") || root.has("id")) {
            return true to "连接成功，模型 $model 可用"
        }
        return false to "接口返回了非预期响应，请检查 API 地址和模型名称"
    }

    /** 将底层异常映射为用户可读的中文提示，保留 HTTP 状态码与服务端错误明细。 */
    fun readableError(error: Exception): String {
        if (error is AiHttpException) {
            return formatHttpError(error.statusCode, error.serverMessage)
        }
        if (error is AiChatException) {
            return error.message
        }
        val message = error.message.orEmpty()
        return when {
            error is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                "AI 请求超时，请稍后重试"
            error is UnknownHostException || error is ConnectException ||
                message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                "AI 网络连接失败，请检查网络或 API 地址后重试"
            error is IOException && message.startsWith("AI 服务") -> message
            error is IOException -> "AI 网络请求失败，请稍后重试"
            else -> message.ifBlank { "AI 请求失败" }
        }
    }
}
