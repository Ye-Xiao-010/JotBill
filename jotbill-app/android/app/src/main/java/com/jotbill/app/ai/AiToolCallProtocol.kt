package com.jotbill.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** 单次工具调用的流式累积状态。 */
internal data class ToolCallState(
    var id: String,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder()
) {
    /**
     * 规范化工具参数为合法 JSON 文本：
     * 无参工具（如 get_accounts / get_budgets）在流式输出时常见 arguments=""，
     * 第 2 轮回传空字符串会被 OpenAI/DeepSeek/DashScope 拒收（HTTP 400），必须归一为 "{}"。
     */
    fun normalizedArguments(): String = arguments.toString().trim().ifEmpty { "{}" }
}

/**
 * 同一轮 SSE 中多工具调用的增量拼装器。
 * 兼容两类服务商：
 * 1. 标准 OpenAI 流式：每个并发工具带独立 index（0, 1, ...）；
 * 2. 省略 index 的兼容层：多工具分帧下发且全落到 index=0，通过检测全新非空 id 自动开新槽，
 *    防止覆盖前一个工具的 id/name 或把两段 JSON 拼坏。
 */
internal class ToolCallAccumulator(
    private val requestId: String,
    private val round: Int
) {
    private val orderedCalls = mutableListOf<ToolCallState>()
    private val indexToSlot = mutableMapOf<Int, Int>()

    /**
     * 合入一条工具增量；若该工具首次拿到有效 name（用于触发 tool_call_start 事件），返回该状态，否则返回 null。
     */
    fun onDelta(
        index: Int,
        id: String?,
        name: String?,
        argumentsDelta: String?
    ): ToolCallState? {
        val cleanId = id?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        val existingSlot = indexToSlot[index]
        val existing = existingSlot?.let(orderedCalls::getOrNull)

        val state = if (existing == null ||
            (cleanId != null && existing.id != cleanId && !existing.name.isNullOrBlank())
        ) {
            val newState = ToolCallState(
                id = cleanId ?: "tool-$requestId-$round-${orderedCalls.size}"
            )
            orderedCalls.add(newState)
            indexToSlot[index] = orderedCalls.lastIndex
            newState
        } else {
            if (cleanId != null) {
                existing.id = cleanId
            }
            existing
        }

        var justStarted = false
        if (cleanName != null && state.name == null) {
            state.name = cleanName
            justStarted = true
        }
        argumentsDelta?.let(state.arguments::append)
        return if (justStarted) state else null
    }

    fun completedCalls(): List<ToolCallState> = orderedCalls.filter { !it.name.isNullOrBlank() }
}

/**
 * 跨轮 Token 用量统计器：
 * 单轮内若厂商分块重复下发累计 usage（如部分 GLM 流式模式），同轮取最后一次快照；
 * 轮次结束后再把本轮快照累计到总用量，避免同轮内重复叠加虚高。
 */
internal class RoundAwareUsageTotals {
    @Volatile private var committedIn: Int? = null
    @Volatile private var committedOut: Int? = null
    @Volatile private var roundIn: Int? = null
    @Volatile private var roundOut: Int? = null

    @Synchronized
    fun recordRoundUsage(tokensInValue: Int?, tokensOutValue: Int?) {
        tokensInValue?.let { roundIn = it }
        tokensOutValue?.let { roundOut = it }
    }

    @Synchronized
    fun commitRound() {
        roundIn?.let { committedIn = (committedIn ?: 0) + it }
        roundOut?.let { committedOut = (committedOut ?: 0) + it }
        roundIn = null
        roundOut = null
    }

    @Synchronized
    fun totalIn(): Int? = combine(committedIn, roundIn)

    @Synchronized
    fun totalOut(): Int? = combine(committedOut, roundOut)

    private fun combine(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }
}

/** 将本轮 assistant 的 tool_calls（含思考模型 reasoning_content）与 tool 结果追加到消息上下文。 */
internal fun appendToolRoundMessages(
    messages: JSONArray,
    toolCalls: Collection<ToolCallState>,
    results: List<Pair<ToolCallState, JSONObject>>,
    assistantText: String?,
    reasoningContent: String? = null
) {
    val assistantToolCalls = JSONArray()
    toolCalls.forEach { call ->
        assistantToolCalls.put(
            JSONObject()
                .put("id", call.id)
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", call.name)
                        .put("arguments", call.normalizedArguments())
                )
        )
    }
    // 空正文统一传 "" 而非 null，兼容 OpenAI/DeepSeek 及更严格的 Qwen/GLM 兼容网关
    val assistantMessage = JSONObject()
        .put("role", "assistant")
        .put("content", assistantText?.takeIf { it.isNotBlank() } ?: "")
        .put("tool_calls", assistantToolCalls)
    // DeepSeek deepseek-reasoner 等思考模型要求多轮工具调用回传同轮 reasoning_content，否则报 400
    if (!reasoningContent.isNullOrEmpty()) {
        assistantMessage.put("reasoning_content", reasoningContent)
    }
    messages.put(assistantMessage)

    results.forEach { (call, result) ->
        messages.put(
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", call.id)
                .put("content", result.toString())
        )
    }
}
