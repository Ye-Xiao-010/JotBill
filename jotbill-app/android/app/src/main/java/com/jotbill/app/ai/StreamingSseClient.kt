package com.jotbill.app.ai

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 读取 OpenAI 兼容 SSE，并把增量内容交给上层。 */
class StreamingSseClient(
    private val httpClient: OkHttpClient
) {
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun start(sessionId: String, request: Request, callbacks: Callbacks) {
        activeCalls.remove(sessionId)?.cancel()
        val call = httpClient.newCall(request)
        activeCalls[sessionId] = call
        val terminal = AtomicBoolean(false)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                activeCalls.remove(sessionId, call)
                if (!terminal.compareAndSet(false, true)) return
                if (call.isCanceled()) callbacks.onCancelled() else callbacks.onError(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val errorBody = runCatching { response.body?.string() }.getOrNull()
                        val serverMessage = AiErrorFormatter.extractServerErrorMessage(errorBody)
                        activeCalls.remove(sessionId, call)
                        if (terminal.compareAndSet(false, true)) {
                            callbacks.onError(AiHttpException(response.code, serverMessage))
                        }
                        return
                    }

                    try {
                        val source = response.body?.source()
                            ?: throw IOException("AI 服务未返回响应内容")
                        var sawPayload = false
                        var sawDone = false
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val payload = when {
                                line.startsWith("data:") -> line.removePrefix("data:").trim()
                                line.trimStart().startsWith("{") -> line.trim()
                                else -> continue
                            }
                            if (payload == "[DONE]") {
                                sawDone = true
                                break
                            }
                            if (payload.isEmpty()) continue
                            sawPayload = true
                            parsePayload(payload, callbacks)
                        }
                        if (!sawDone && !call.isCanceled()) {
                            // 未见完成标记即断流（半包/被截断）不得当成功：否则 UI 留空气泡、
                            // 截断的 tool_calls 参数被当作有效结果。部分厂商在 200 响应体里
                            // 返回 {"error": ...}（限流等），同样在此抛出。
                            throw IOException(
                                if (sawPayload) "AI 服务流式响应在完成标记前结束（可能被截断）"
                                else "AI 服务未返回有效内容"
                            )
                        }
                        activeCalls.remove(sessionId, call)
                        if (terminal.compareAndSet(false, true)) callbacks.onCompleted()
                    } catch (error: Exception) {
                        activeCalls.remove(sessionId, call)
                        if (!terminal.compareAndSet(false, true)) return
                        if (call.isCanceled()) callbacks.onCancelled() else callbacks.onError(error)
                    }
                }
            }
        })
    }

    fun cancel(sessionId: String): Boolean = activeCalls.remove(sessionId)?.let {
        it.cancel()
        true
    } ?: false

    fun close() {
        activeCalls.values.forEach(Call::cancel)
        activeCalls.clear()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun parsePayload(payload: String, callbacks: Callbacks) {
        val root = JSONObject(payload)
        // 200 状态码内的错误载荷（DashScope/Qwen 限流等）：不抛出会表现为"成功但空回复"
        if (root.has("error")) {
            val message = root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            throw IOException("AI 服务返回错误: ${message ?: payload.take(200)}")
        }
        root.optJSONObject("usage")?.let { usage ->
            callbacks.onUsage(
                usage.optInt("prompt_tokens").takeIf { it > 0 },
                usage.optInt("completion_tokens").takeIf { it > 0 }
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
        delta.stringValue("reasoning_content")?.let(callbacks::onThinkingDelta)
        delta.stringValue("content")?.let(callbacks::onTextDelta)
        delta.optJSONArray("tool_calls")?.let { toolCalls ->
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function")
                callbacks.onToolCallDelta(
                    index = toolCall.optInt("index", index),
                    id = toolCall.stringValue("id"),
                    name = function?.stringValue("name"),
                    argumentsDelta = function?.stringValue("arguments")
                )
            }
        }
    }

    private fun JSONObject.stringValue(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is String -> value.takeIf { it.isNotEmpty() }
            else -> value.toString().takeIf { it.isNotEmpty() }
        }
    }

    interface Callbacks {
        fun onThinkingDelta(content: String)
        fun onTextDelta(content: String)
        fun onToolCallDelta(index: Int, id: String?, name: String?, argumentsDelta: String?)
        fun onUsage(tokensIn: Int?, tokensOut: Int?)
        fun onCompleted()
        fun onCancelled()
        fun onError(error: Exception)
    }
}
