package com.jotbill.app.ai

import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AiCostRecordEntity
import com.jotbill.app.data.entity.AiProviderConfigEntity
import com.jotbill.app.data.entity.ChatMessageEntity
import com.jotbill.app.data.entity.ChatSessionEntity
import com.jotbill.app.security.KeyStoreSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** AI 对话、会话存储和语音转写的原生实现。 */
class AiChatModule(
    private val reactContext: ReactApplicationContext,
    private val database: JotBillDatabase,
    private val secretStore: KeyStoreSecretStore,
    private val scope: CoroutineScope
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    private val streamingClient = StreamingSseClient(httpClient)
    private val tools = AiChatTools(database)

    /**
     * 用户请求取消的会话集合：多轮 Tool Calling 在轮间空窗期 activeCalls 中没有
     * 该会话的 Call，仅靠 streamingClient.cancel 会返回 false 且第 2 轮照常发起，
     * 用户以为已停止、实际继续计费。取消后每一轮开始前都检查此标志。
     */
    private val cancelledSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun sendMessage(inputJson: String): String {
        val input = JSONObject(inputJson)
        val content = input.optString("content").trim().ifEmpty { "请描述这张图片" }
        if (content.length > MAX_CONTENT_LENGTH) {
            throw AiChatException("INVALID_ARGUMENT", "消息内容不能超过 $MAX_CONTENT_LENGTH 个字符")
        }
        val images = readImages(input.optJSONArray("imageBase64List"))
        val requestId = input.optString("requestId").trim().ifEmpty { UUID.randomUUID().toString() }
        val config = resolveProvider(input.optionalString("configId"))
        if (images.isNotEmpty() && !config.supportsVision) {
            throw AiChatException("AI_VISION_UNSUPPORTED", "当前 AI 服务商配置不支持图片输入")
        }

        val now = System.currentTimeMillis()
        val session = upsertSession(
            sessionId = input.optionalString("sessionId"),
            providerId = config.id,
            content = content,
            hasImages = images.isNotEmpty(),
            now = now
        )
        val imageJson = images.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }
        database.withTransaction {
            val next = database.chatDao().nextSequence(session.id)
            database.chatDao().appendMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = session.id,
                    seqNum = next,
                    role = "user",
                    content = content,
                    imageDataJson = imageJson,
                    createdAt = now
                ),
                now
            )
        }

        val history = database.chatDao().getMessages(session.id).takeLast(MAX_HISTORY_MESSAGES)
        val baseMessages = buildBaseMessages(history, images, content)
        emitChunk(requestId, session.id, "session_created")
        emitChunk(requestId, session.id, "thinking", round = 1)
        cancelledSessions.remove(session.id)
        startStreaming(
            requestId,
            session.id,
            config,
            baseMessages,
            1,
            now,
            StringBuilder(),
            RoundAwareUsageTotals()
        )

        return JSONObject()
            .put("requestId", requestId)
            .put("sessionId", session.id)
            .toString()
    }

    fun cancelMessage(sessionId: String): Boolean {
        // 先记标志再取消：即使此刻没有活跃 Call（轮间空窗），下一轮开始前也会中止
        cancelledSessions.add(sessionId)
        return streamingClient.cancel(sessionId)
    }

    suspend fun getChatSessions(page: Int, limit: Int): String {
        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 100)
        val sessions = database.chatDao().getSessions(safeLimit, (safePage - 1) * safeLimit)
        return JSONArray().also { array ->
            sessions.forEach { session ->
                array.put(sessionToJson(session, database.chatDao().countMessages(session.id)))
            }
        }.toString()
    }

    suspend fun getChatSession(sessionId: String): String {
        val session = database.chatDao().getSession(sessionId)
            ?: throw AiChatException("NOT_FOUND", "对话会话不存在")
        val messages = database.chatDao().getMessages(sessionId)
        return JSONObject()
            .put("session", sessionToJson(session, messages.size))
            .put("messages", JSONArray().also { array -> messages.forEach { array.put(messageToJson(it)) } })
            .toString()
    }

    suspend fun deleteChatSession(sessionId: String) {
        val session = database.chatDao().getSession(sessionId)
            ?: throw AiChatException("NOT_FOUND", "对话会话不存在")
        streamingClient.cancel(sessionId)
        database.chatDao().deleteSession(session)
    }

    suspend fun renameChatSession(sessionId: String, title: String) {
        val normalized = title.trim()
        if (normalized.isEmpty() || normalized.length > 60) {
            throw AiChatException("INVALID_ARGUMENT", "会话标题长度必须为 1 到 60 个字符")
        }
        if (database.chatDao().renameSession(sessionId, normalized, System.currentTimeMillis()) == 0) {
            throw AiChatException("NOT_FOUND", "对话会话不存在")
        }
    }

    suspend fun togglePinChatSession(sessionId: String): Boolean {
        if (database.chatDao().togglePinned(sessionId, System.currentTimeMillis()) == 0) {
            throw AiChatException("NOT_FOUND", "对话会话不存在")
        }
        return database.chatDao().getSession(sessionId)?.isPinned
            ?: throw AiChatException("NOT_FOUND", "对话会话不存在")
    }

    suspend fun transcribeAudio(audioBase64: String, mimeType: String?): String {
        val normalizedAudio = audioBase64.substringAfter("base64,", audioBase64)
        if (normalizedAudio.isBlank()) throw AiChatException("INVALID_ARGUMENT", "音频内容为空")
        try {
            Base64.decode(normalizedAudio, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw AiChatException("INVALID_ARGUMENT", "音频 Base64 格式无效")
        }
        val config = resolveProvider(null)
        // 语音转写需要音频模型：把聊天/视觉模型名直接塞进 chat/completions 的
        // input_audio 块，绝大多数服务商直接返回 400（如 qwen-plus/glm-4v-flash）。
        // 模型名不含音频能力特征时提前给出明确指引，而不是让用户收到笼统的 ASR 错误。
        if (ASR_CAPABLE_MODEL_HINTS.none(config.model.lowercase()::contains)) {
            throw AiChatException(
                "AI_ASR_UNSUPPORTED",
                "当前模型 ${config.model} 不支持语音转写，请在 AI 服务商配置中选择语音模型" +
                    "（如 qwen3-omni、qwen-audio、glm-4-voice 或含 audio 的模型）"
            )
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "请将用户提供的音频准确转写为原语言文字，只返回转写文本。")
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", "请转写这段音频"))
                                    .put(
                                        JSONObject()
                                            .put("type", "input_audio")
                                            .put(
                                                "input_audio",
                                                JSONObject()
                                                    .put("data", normalizedAudio)
                                                    .put("format", audioFormat(mimeType))
                                            )
                                    )
                            )
                    )
            )
        val request = requestBuilder(config)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val startedAt = System.currentTimeMillis()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AiHttpException(
                        response.code,
                        AiErrorFormatter.extractServerErrorMessage(responseBody)
                    )
                }
                val root = JSONObject(responseBody)
                val text = extractMessageContent(root)
                    ?: throw IOException("AI 服务未返回语音转写文本")
                val usage = root.optJSONObject("usage")
                recordCostSafely(
                    config,
                    "asr",
                    usage?.optInt("prompt_tokens")?.takeIf { it > 0 },
                    usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                    true
                )
                return JSONObject()
                    .put("text", text.trim())
                    .put("latencyMs", System.currentTimeMillis() - startedAt)
                    .toString()
            }
        } catch (error: Exception) {
            recordCostSafely(config, "asr", null, null, false)
            throw AiChatException("AI_ASR_ERROR", readableNetworkError(error))
        }
    }

    fun close() {
        streamingClient.close()
    }

    private suspend fun upsertSession(
        sessionId: String?,
        providerId: String,
        content: String,
        hasImages: Boolean,
        now: Long
    ): ChatSessionEntity {
        val dao = database.chatDao()
        if (sessionId != null) {
            val existing = dao.getSession(sessionId)
                ?: throw AiChatException("NOT_FOUND", "对话会话不存在")
            val updated = existing.copy(aiProviderId = providerId, isActive = true, updatedAt = now)
            dao.updateSession(updated)
            return updated
        }
        val title = content.takeIf { it.isNotBlank() && it != "请描述这张图片" }
            ?.replace(Regex("\\s+"), " ")
            ?.take(30)
            ?: if (hasImages) "图片对话" else "新对话"
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            aiProviderId = providerId,
            isActive = true,
            isPinned = false,
            createdAt = now,
            updatedAt = now
        ).also { dao.insertSession(it) }
    }

    private suspend fun resolveProvider(configId: String?): AiProviderConfigEntity {
        val dao = database.aiProviderConfigDao()
        val config = configId?.let { dao.getById(it) } ?: dao.getDefault() ?: dao.getAll().firstOrNull()
        return config ?: throw AiChatException("AI_CONFIG_MISSING", "请先在设置中配置 AI 服务商")
    }

    private suspend fun buildBaseMessages(
        history: List<ChatMessageEntity>,
        latestImages: List<String>,
        latestContent: String
    ): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", tools.buildSystemPrompt(latestContent)))
        history.forEachIndexed { index, message ->
            val json = JSONObject().put("role", message.role)
            if (index == history.lastIndex && message.role == "user" && latestImages.isNotEmpty()) {
                json.put("content", multimodalContent(message.content.orEmpty(), latestImages))
            } else {
                json.put("content", message.content.orEmpty())
            }
            messages.put(json)
        }
        return messages
    }

    private fun buildChatRequest(config: AiProviderConfigEntity, messages: JSONArray): Request {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("temperature", 0.2)
            .put("tools", tools.toolDefinitions())
        return requestBuilder(config)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun multimodalContent(content: String, images: List<String>): JSONArray =
        JSONArray().put(JSONObject().put("type", "text").put("text", content)).also { array ->
            images.forEach { image ->
                array.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", image))
                )
            }
        }

    private fun requestBuilder(config: AiProviderConfigEntity): Request.Builder {
        val builder = Request.Builder()
            .url(AiEndpointResolver.chatCompletions(config))
            .header("Content-Type", "application/json")
        config.apiKeyEncrypted
            ?.let(secretStore::decryptSecret)
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun startStreaming(
        requestId: String,
        sessionId: String,
        config: AiProviderConfigEntity,
        messages: JSONArray,
        round: Int,
        startedAt: Long,
        accumulatedText: StringBuilder,
        usage: RoundAwareUsageTotals
    ) {
        val request = buildChatRequest(config, messages)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolAccumulator = ToolCallAccumulator(requestId, round)
        streamingClient.start(sessionId, request, object : StreamingSseClient.Callbacks {
            override fun onThinkingDelta(content: String) {
                reasoning.append(content)
                emitChunk(requestId, sessionId, "thinking_delta", content = content)
            }

            override fun onTextDelta(content: String) {
                if (text.isEmpty() && accumulatedText.isNotEmpty() &&
                    !accumulatedText.endsWith("\n") && !content.startsWith("\n")
                ) {
                    accumulatedText.append("\n\n")
                }
                text.append(content)
                accumulatedText.append(content)
                emitChunk(requestId, sessionId, "text_delta", content = content)
            }

            override fun onToolCallDelta(
                index: Int,
                id: String?,
                name: String?,
                argumentsDelta: String?
            ) {
                val startedCall = toolAccumulator.onDelta(index, id, name, argumentsDelta)
                if (startedCall != null && startedCall.name != null) {
                    emitChunk(
                        requestId,
                        sessionId,
                        "tool_call_start",
                        toolCallId = startedCall.id,
                        toolName = startedCall.name
                    )
                }
            }

            override fun onUsage(tokensInValue: Int?, tokensOutValue: Int?) {
                usage.recordRoundUsage(tokensInValue, tokensOutValue)
            }

            override fun onCompleted() {
                scope.launch {
                    try {
                        usage.commitRound()
                        // 轮间空窗的取消请求：立即按已取消收口（保留已生成文本），不再发起下一轮
                        if (cancelledSessions.contains(sessionId)) {
                            finishStream(
                                requestId,
                                sessionId,
                                config,
                                accumulatedText.toString().ifBlank { text.toString() },
                                usage.totalIn(),
                                usage.totalOut(),
                                startedAt,
                                true
                            )
                            return@launch
                        }
                        val calls = toolAccumulator.completedCalls()
                        val results = executeToolCalls(requestId, sessionId, calls)
                        if (results.isNotEmpty() && round < MAX_TOOL_ROUNDS) {
                            // 多轮 Tool Calling 闭环：把 assistant 的 tool_calls（含 reasoning_content）与 tool 结果回传，
                            // 追加进消息上下文后继续下一轮，让 AI 基于查询/统计结果作答。
                            appendToolRoundMessages(
                                messages = messages,
                                toolCalls = calls,
                                results = results,
                                assistantText = text.toString(),
                                reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() }
                            )
                            emitChunk(requestId, sessionId, "thinking", round = round + 1)
                            startStreaming(
                                requestId,
                                sessionId,
                                config,
                                messages,
                                round + 1,
                                startedAt,
                                accumulatedText,
                                usage
                            )
                        } else {
                            finishStream(
                                requestId,
                                sessionId,
                                config,
                                accumulatedText.toString().ifBlank { text.toString() },
                                usage.totalIn(),
                                usage.totalOut(),
                                startedAt,
                                false
                            )
                        }
                    } catch (error: Exception) {
                        // 下一轮 buildChatRequest（Keystore 解密）或工具执行抛出时不得炸掉 scope
                        Log.w(TAG, "多轮工具调用执行失败", error)
                        recordCostSafely(config, "chat", usage.totalIn(), usage.totalOut(), false)
                        emitChunk(
                            requestId,
                            sessionId,
                            "error",
                            message = readableNetworkError(error)
                        )
                    }
                }
            }

            override fun onCancelled() {
                usage.commitRound()
                finishStream(
                    requestId,
                    sessionId,
                    config,
                    accumulatedText.toString().ifBlank { text.toString() },
                    usage.totalIn(),
                    usage.totalOut(),
                    startedAt,
                    true
                )
            }

            override fun onError(error: Exception) {
                scope.launch {
                    recordCostSafely(config, "chat", usage.totalIn(), usage.totalOut(), false)
                    emitChunk(
                        requestId,
                        sessionId,
                        "error",
                        message = readableNetworkError(error)
                    )
                }
            }
        })
    }

    /** 执行工具调用并发射 tool_result 事件；返回 (调用, 结果) 供下一轮回传上下文。 */
    private suspend fun executeToolCalls(
        requestId: String,
        sessionId: String,
        toolCalls: Collection<ToolCallState>
    ): List<Pair<ToolCallState, JSONObject>> {
        return toolCalls.map { call ->
            val result = tools.executeTool(call.name, call.normalizedArguments())
            emitChunk(
                requestId,
                sessionId,
                "tool_result",
                toolCallId = call.id,
                toolName = call.name ?: "unknown",
                resultJson = result.toString()
            )
            call to result
        }
    }

    private fun finishStream(
        requestId: String,
        sessionId: String,
        config: AiProviderConfigEntity,
        content: String,
        tokensIn: Int?,
        tokensOut: Int?,
        startedAt: Long,
        cancelled: Boolean
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            cancelledSessions.remove(sessionId)
            try {
                if (content.isNotBlank() && database.chatDao().getSession(sessionId) != null) {
                    try {
                        database.withTransaction {
                            database.chatDao().appendMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    seqNum = database.chatDao().nextSequence(sessionId),
                                    role = "assistant",
                                    content = content,
                                    imageDataJson = null,
                                    createdAt = now
                                ),
                                now
                            )
                        }
                    } catch (error: Exception) {
                        // 落库失败（磁盘满/库关闭）不得沿 SupervisorJob 上抛崩溃整个 App
                        Log.w(TAG, "AI 回复落库失败", error)
                    }
                }
            } finally {
                recordCostSafely(config, "chat", tokensIn, tokensOut, !cancelled)
                emitChunk(
                    requestId,
                    sessionId,
                    "done",
                    thinkingTimeMs = now - startedAt,
                    cancelled = cancelled
                )
            }
        }
    }

    private suspend fun recordCostSafely(
        config: AiProviderConfigEntity,
        requestType: String,
        tokensIn: Int?,
        tokensOut: Int?,
        success: Boolean
    ) {
        runCatching { recordCost(config, requestType, tokensIn, tokensOut, success) }
    }

    private suspend fun recordCost(
        config: AiProviderConfigEntity,
        requestType: String,
        tokensIn: Int?,
        tokensOut: Int?,
        success: Boolean
    ) {
        database.aiCostRecordDao().insert(
            AiCostRecordEntity(
                id = UUID.randomUUID().toString(),
                provider = config.provider,
                model = config.model,
                requestType = requestType,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                costMinor = 0,
                success = success,
                createdAt = System.currentTimeMillis(),
                month = YearMonth.now().toString()
            )
        )
    }

    private fun emitChunk(
        requestId: String,
        sessionId: String,
        type: String,
        content: String? = null,
        round: Int? = null,
        thinkingTimeMs: Long? = null,
        cancelled: Boolean? = null,
        message: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
        resultJson: String? = null
    ) {
        val payload = Arguments.createMap().apply {
            putString("requestId", requestId)
            putString("sessionId", sessionId)
            putString("type", type)
            content?.let { putString("content", it) }
            round?.let { putInt("round", it) }
            thinkingTimeMs?.let { putDouble("thinkingTimeMs", it.toDouble()) }
            cancelled?.let { putBoolean("cancelled", it) }
            message?.let { putString("message", it) }
            toolCallId?.let { putString("toolCallId", it) }
            toolName?.let { putString("toolName", it) }
            resultJson?.let { putString("resultJson", it) }
        }
        reactContext.runOnUiQueueThread {
            if (reactContext.hasActiveReactInstance()) {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(STREAM_EVENT, payload)
            }
        }
    }

    private fun sessionToJson(session: ChatSessionEntity, messageCount: Int): JSONObject =
        JSONObject()
            .put("id", session.id)
            .put("title", session.title)
            .put("aiConfigId", session.aiProviderId)
            .put("isActive", session.isActive)
            .put("isPinned", session.isPinned)
            .put("createdAt", Instant.ofEpochMilli(session.createdAt).toString())
            .put("updatedAt", Instant.ofEpochMilli(session.updatedAt).toString())
            .put("_count", JSONObject().put("messages", messageCount))

    private fun messageToJson(message: ChatMessageEntity): JSONObject =
        JSONObject()
            .put("id", message.id)
            .put("sessionId", message.sessionId)
            .put("seqNum", message.seqNum)
            .put("role", message.role)
            .put("content", message.content)
            .put("toolCalls", JSONObject.NULL)
            .put("toolCallId", JSONObject.NULL)
            .put("toolName", JSONObject.NULL)
            .put("toolResult", JSONObject.NULL)
            .put("imageUrl", message.imageDataJson)
            .put("createdAt", Instant.ofEpochMilli(message.createdAt).toString())

    private fun readImages(input: JSONArray?): List<String> {
        if (input == null) return emptyList()
        if (input.length() > MAX_IMAGES) {
            throw AiChatException("INVALID_ARGUMENT", "每次最多发送 $MAX_IMAGES 张图片")
        }
        return buildList {
            for (index in 0 until input.length()) {
                val image = input.optString(index).trim()
                if (!image.startsWith("data:image/") || !image.contains(";base64,")) {
                    throw AiChatException("INVALID_ARGUMENT", "第 ${index + 1} 张图片格式无效")
                }
                if (image.length > MAX_IMAGE_BASE64_LENGTH) {
                    throw AiChatException("INVALID_ARGUMENT", "第 ${index + 1} 张图片过大")
                }
                add(image)
            }
        }
    }

    private fun extractMessageContent(root: JSONObject): String? {
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
            ?: return null
        if (content is String) return content
        if (content is JSONArray) {
            return buildString {
                for (index in 0 until content.length()) {
                    content.optJSONObject(index)?.optString("text")?.let(::append)
                }
            }.takeIf { it.isNotBlank() }
        }
        return content.toString()
    }

    private fun audioFormat(mimeType: String?): String = when (mimeType?.lowercase()) {
        "audio/m4a", "audio/x-m4a", "audio/mp4" -> "mp4"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/webm" -> "webm"
        else -> "mp4"
    }

    private fun readableNetworkError(error: Exception): String =
        AiErrorFormatter.readableError(error)

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        private const val TAG = "AiChatModule"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val STREAM_EVENT = "onAiStreamChunk"
        const val MAX_CONTENT_LENGTH = 8000
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_IMAGES = 4
        const val MAX_IMAGE_BASE64_LENGTH = 12 * 1024 * 1024
        // 多轮 Tool Calling 上限：防止模型反复调用工具导致无限循环。
        const val MAX_TOOL_ROUNDS = 4
        /** 模型名命中任一特征才允许走 input_audio 语音转写。 */
        val ASR_CAPABLE_MODEL_HINTS = listOf("audio", "omni", "asr", "whisper", "voice")
    }
}
