package com.jotbill.app.ai

import android.util.Base64
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
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
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val streamingClient = StreamingSseClient(httpClient)

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

        val messages = database.chatDao().getMessages(session.id).takeLast(MAX_HISTORY_MESSAGES)
        val request = buildChatRequest(config, messages, images, content)
        emitChunk(requestId, session.id, "session_created")
        emitChunk(requestId, session.id, "thinking", round = 1)
        startStreaming(requestId, session.id, config, request, now)

        return JSONObject()
            .put("requestId", requestId)
            .put("sessionId", session.id)
            .toString()
    }

    fun cancelMessage(sessionId: String): Boolean = streamingClient.cancel(sessionId)

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
                    throw IOException("AI 服务返回 HTTP ${response.code}: ${responseBody.take(500)}")
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

    private suspend fun buildChatRequest(
        config: AiProviderConfigEntity,
        history: List<ChatMessageEntity>,
        latestImages: List<String>,
        latestContent: String
    ): Request {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(latestContent)))
        history.forEachIndexed { index, message ->
            val json = JSONObject().put("role", message.role)
            if (index == history.lastIndex && message.role == "user" && latestImages.isNotEmpty()) {
                json.put("content", multimodalContent(message.content.orEmpty(), latestImages))
            } else {
                json.put("content", message.content.orEmpty())
            }
            messages.put(json)
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.2)
            .put(
                "tools",
                JSONArray()
                    .put(queryBillsTool())
                    .put(getStatisticsTool())
                    .put(createBillsTool())
            )
        return requestBuilder(config)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun createBillsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "create_bills")
                .put("description", "整理一到多条待用户确认的账单，不直接写入账本")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "bills",
                                JSONObject()
                                    .put("type", "array")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put("amount", JSONObject().put("type", "number"))
                                                    .put(
                                                        "type",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("enum", JSONArray(listOf("income", "expense")))
                                                    )
                                                    .put("description", JSONObject().put("type", "string"))
                                                    .put("categoryName", JSONObject().put("type", "string"))
                                                    .put("date", JSONObject().put("type", "string"))
                                            )
                                            .put(
                                                "required",
                                                JSONArray(listOf("amount", "type", "description", "categoryName", "date"))
                                            )
                                    )
                            )
                        )
                        .put("required", JSONArray(listOf("bills")))
                )
        )

    private fun queryBillsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "query_bills")
                .put("description", "只读查询本地账单，最多返回 20 条")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "type",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray(
                                                listOf(
                                                    "income",
                                                    "expense",
                                                    "transfer",
                                                    "refund",
                                                    "repayment",
                                                    "fee",
                                                    "adjustment"
                                                )
                                            )
                                        )
                                )
                                .put("keyword", JSONObject().put("type", "string"))
                                .put(
                                    "startDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "endDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", MAX_QUERY_BILLS)
                                )
                        )
                )
        )

    private fun getStatisticsTool(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "get_statistics")
                .put("description", "只读统计指定日期范围内的本地收入、支出和结余")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "startDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                                .put(
                                    "endDate",
                                    JSONObject().put("type", "string").put("description", "YYYY-MM-DD")
                                )
                        )
                )
        )

    private suspend fun systemPrompt(latestContent: String): String {
        val prompt = StringBuilder(
                "你是 JotBill 的本地记账助手。使用简体中文回答。" +
                "你只能读取用户提供或系统给出的账单摘要，不得声称已经删除、修改或保存账单。" +
                "查询账单时调用 query_bills，需要汇总时调用 get_statistics。" +
                "需要记账时必须调用 create_bills 工具生成待确认账单。" +
                "收到图片时提取金额、平台、商户、商品说明和日期，并调用 create_bills；不确定的字段明确说明。"
        )
        if (QUERY_KEYWORDS.any(latestContent::contains)) {
            val recentBills = database.billDao().query(
                type = null,
                categoryId = null,
                accountId = null,
                platform = null,
                merchant = null,
                sourceType = null,
                startDate = null,
                endDate = null,
                keyword = null,
                orderBy = "date",
                orderDirection = "desc",
                limit = 20,
                offset = 0
            )
            prompt.append("\n以下是最多 20 条本地账单摘要，仅用于回答本次查询：")
            if (recentBills.isEmpty()) {
                prompt.append("\n暂无账单。")
            } else {
                recentBills.forEach { bill ->
                    val date = Instant.ofEpochMilli(bill.occurredAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    prompt.append("\n- ")
                        .append(date)
                        .append(' ')
                        .append(bill.type)
                        .append(' ')
                        .append(bill.amountMinor)
                        .append("分")
                    bill.merchant?.let { prompt.append(" 商户:").append(it.take(80)) }
                    bill.description?.let { prompt.append(" 说明:").append(it.take(120)) }
                }
            }
        }
        return prompt.toString()
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
        request: Request,
        startedAt: Long
    ) {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallState>()
        var tokensIn: Int? = null
        var tokensOut: Int? = null
        streamingClient.start(sessionId, request, object : StreamingSseClient.Callbacks {
            override fun onThinkingDelta(content: String) {
                emitChunk(requestId, sessionId, "thinking_delta", content = content)
            }

            override fun onTextDelta(content: String) {
                text.append(content)
                emitChunk(requestId, sessionId, "text_delta", content = content)
            }

            override fun onToolCallDelta(
                index: Int,
                id: String?,
                name: String?,
                argumentsDelta: String?
            ) {
                val state = toolCalls.getOrPut(index) {
                    ToolCallState(id = id ?: "tool-$requestId-$index")
                }
                id?.let { state.id = it }
                if (!name.isNullOrBlank() && state.name == null) {
                    state.name = name
                    emitChunk(
                        requestId,
                        sessionId,
                        "tool_call_start",
                        toolCallId = state.id,
                        toolName = name
                    )
                }
                argumentsDelta?.let(state.arguments::append)
            }

            override fun onUsage(tokensInValue: Int?, tokensOutValue: Int?) {
                tokensIn = tokensInValue ?: tokensIn
                tokensOut = tokensOutValue ?: tokensOut
            }

            override fun onCompleted() {
                scope.launch {
                    emitToolResults(requestId, sessionId, toolCalls.values)
                    finishStream(
                        requestId,
                        sessionId,
                        config,
                        text.toString(),
                        tokensIn,
                        tokensOut,
                        startedAt,
                        false
                    )
                }
            }

            override fun onCancelled() {
                finishStream(requestId, sessionId, config, text.toString(), tokensIn, tokensOut, startedAt, true)
            }

            override fun onError(error: Exception) {
                scope.launch {
                    recordCostSafely(config, "chat", tokensIn, tokensOut, false)
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

    private suspend fun emitToolResults(
        requestId: String,
        sessionId: String,
        toolCalls: Collection<ToolCallState>
    ) {
        toolCalls.forEach { call ->
            val result = when (call.name) {
                "query_bills" -> queryBillsResult(call.arguments.toString())
                "get_statistics" -> statisticsResult(call.arguments.toString())
                "create_bills" -> createBillsResult(call.arguments.toString())
                else -> JSONObject()
                    .put("success", false)
                    .put("message", "不支持的工具调用: ${call.name ?: "unknown"}")
            }
            emitChunk(
                requestId,
                sessionId,
                "tool_result",
                toolCallId = call.id,
                toolName = call.name ?: "unknown",
                resultJson = result.toString()
            )
        }
    }

    private suspend fun queryBillsResult(arguments: String): JSONObject = try {
        val input = JSONObject(arguments.ifBlank { "{}" })
        val type = input.optionalString("type")
        require(type == null || type in BILL_TYPES)
        val limit = input.optInt("limit", 5).coerceIn(1, MAX_QUERY_BILLS)
        val startDate = dateBoundary(input.optionalString("startDate"), endOfDay = false)
        val endDate = dateBoundary(input.optionalString("endDate"), endOfDay = true)
        require(!input.has("startDate") || startDate != null)
        require(!input.has("endDate") || endDate != null)
        require(startDate == null || endDate == null || startDate <= endDate)
        val categoryNames = database.categoryDao().getAll().associate { it.id to it.name }
        val bills = database.billDao().query(
            type = type,
            categoryId = null,
            accountId = null,
            platform = null,
            merchant = null,
            sourceType = null,
            startDate = startDate,
            endDate = endDate,
            keyword = input.optionalString("keyword")?.take(100),
            orderBy = "date",
            orderDirection = "desc",
            limit = limit,
            offset = 0
        )
        val rows = JSONArray().also { array ->
            bills.forEach { bill ->
                array.put(
                    JSONObject()
                        .put("id", bill.id)
                        .put("amount", bill.amountMinor / 100.0)
                        .put("type", bill.type)
                        .put(
                            "date",
                            Instant.ofEpochMilli(bill.occurredAt)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .toString()
                        )
                        .put("description", bill.description ?: bill.merchant ?: "")
                        .put("categoryName", bill.categoryId?.let(categoryNames::get) ?: "")
                )
            }
        }
        JSONObject()
            .put("success", true)
            .put("data", JSONObject().put("data", rows))
            .put("message", if (bills.isEmpty()) "未查询到账单" else "查询到 ${bills.size} 条账单")
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "账单查询参数无效")
    }

    private suspend fun statisticsResult(arguments: String): JSONObject = try {
        val input = JSONObject(arguments.ifBlank { "{}" })
        val startDate = dateBoundary(input.optionalString("startDate"), endOfDay = false) ?: 0L
        val endDate = dateBoundary(input.optionalString("endDate"), endOfDay = true)
            ?: System.currentTimeMillis()
        require(!input.has("startDate") || dateBoundary(input.optionalString("startDate"), false) != null)
        require(!input.has("endDate") || dateBoundary(input.optionalString("endDate"), true) != null)
        require(startDate <= endDate)
        val summary = database.billDao().getSummary(startDate, endDate)
        JSONObject()
            .put("success", true)
            .put(
                "data",
                JSONObject()
                    .put("totalIncome", summary.totalIncome / 100.0)
                    .put("totalExpense", summary.totalExpense / 100.0)
                    .put("balance", (summary.totalIncome - summary.totalExpense) / 100.0)
                    .put("incomeCount", summary.incomeCount)
                    .put("expenseCount", summary.expenseCount)
            )
            .put("message", "统计完成")
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "统计参数无效")
    }

    private fun dateBoundary(value: String?, endOfDay: Boolean): Long? {
        if (value == null) return null
        return runCatching {
            val date = LocalDate.parse(value.take(10))
            val boundary = if (endOfDay) date.plusDays(1).atStartOfDay() else date.atStartOfDay()
            boundary.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - if (endOfDay) 1 else 0
        }.getOrNull()
    }

    private fun createBillsResult(arguments: String): JSONObject = try {
        val source = JSONObject(arguments).optJSONArray("bills") ?: JSONArray()
        val bills = JSONArray()
        for (index in 0 until minOf(source.length(), MAX_PROPOSED_BILLS)) {
            val bill = source.optJSONObject(index) ?: continue
            val amount = bill.optDouble("amount", 0.0)
            if (!amount.isFinite() || amount <= 0) continue
            val type = bill.optString("type").takeIf { it in setOf("income", "expense") }
                ?: "expense"
            bills.put(
                JSONObject()
                    .put("amount", amount)
                    .put("type", type)
                    .put("description", bill.optString("description").take(200))
                    .put("categoryName", bill.optString("categoryName", "其他").take(50))
                    .put("date", normalizedDate(bill.optString("date")))
            )
        }
        if (bills.length() == 0) {
            JSONObject().put("success", false).put("message", "未识别到有效账单")
        } else {
            JSONObject()
                .put("success", true)
                .put("data", JSONObject().put("bills", bills))
                .put("message", "已整理 ${bills.length()} 条待确认账单")
        }
    } catch (_: Exception) {
        JSONObject().put("success", false).put("message", "AI 返回的账单格式无效")
    }

    private fun normalizedDate(value: String): String {
        val normalized = value.trim().take(10)
        return if (normalized.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            normalized
        } else {
            java.time.LocalDate.now().toString()
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
            try {
                if (content.isNotBlank() && database.chatDao().getSession(sessionId) != null) {
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

    private fun readableNetworkError(error: Exception): String {
        val message = error.message.orEmpty()
        return if (
            error is IOException &&
            (message.contains("Unable to resolve host") ||
                message.contains("Failed to connect") ||
                message.contains("timeout", ignoreCase = true))
        ) {
            "网络连接失败，请检查网络后重试"
        } else {
            message.ifBlank { "AI 请求失败" }
        }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val STREAM_EVENT = "onAiStreamChunk"
        const val MAX_CONTENT_LENGTH = 8000
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_IMAGES = 4
        const val MAX_PROPOSED_BILLS = 10
        const val MAX_QUERY_BILLS = 20
        const val MAX_IMAGE_BASE64_LENGTH = 12 * 1024 * 1024
        val BILL_TYPES = setOf("income", "expense", "transfer", "refund", "repayment", "fee", "adjustment")
        val QUERY_KEYWORDS = listOf("查询", "查账", "账单", "统计", "最近", "今天", "昨天", "本月", "这个月", "多少")
    }

    private data class ToolCallState(
        var id: String,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )
}
