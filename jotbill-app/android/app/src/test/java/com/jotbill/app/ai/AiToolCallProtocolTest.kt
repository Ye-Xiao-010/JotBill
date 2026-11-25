package com.jotbill.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolCallProtocolTest {
    @Test
    fun `ToolCallAccumulator 处理省略 index 的同轮多工具调用不会互相覆盖`() {
        val acc = ToolCallAccumulator(requestId = "req-1", round = 1)

        // 工具 1：index=0
        val started1 = acc.onDelta(index = 0, id = "call_acc", name = "get_accounts", argumentsDelta = "")
        assertNotNull(started1)
        assertEquals("call_acc", started1!!.id)

        // 工具 2：某些兼容服务商分帧下发且省略 index，导致同样传入 index=0，但 id 不同
        val started2 = acc.onDelta(index = 0, id = "call_bud", name = "get_budgets", argumentsDelta = "")
        assertNotNull(started2)
        assertEquals("call_bud", started2!!.id)

        // 后续参数增量归属到工具 2，不污染工具 1
        assertNull(acc.onDelta(index = 0, id = null, name = null, argumentsDelta = "{}"))

        val calls = acc.completedCalls()
        assertEquals(2, calls.size)
        assertEquals("get_accounts", calls[0].name)
        assertEquals("{}", calls[0].normalizedArguments())
        assertEquals("get_budgets", calls[1].name)
        assertEquals("{}", calls[1].normalizedArguments())
    }

    @Test
    fun `ToolCallAccumulator 处理标准并发多索引流式参数分片`() {
        val acc = ToolCallAccumulator(requestId = "req-2", round = 1)

        assertNotNull(acc.onDelta(0, "call_1", "query_bills", "{\"categoryName\":"))
        assertNotNull(acc.onDelta(1, "call_2", "get_statistics", "{\"startDate\":"))
        assertNull(acc.onDelta(0, null, null, "\"餐饮\"}"))
        assertNull(acc.onDelta(1, null, null, "\"2026-11-01\"}"))

        val calls = acc.completedCalls()
        assertEquals(2, calls.size)
        assertEquals("""{"categoryName":"餐饮"}""", calls[0].normalizedArguments())
        assertEquals("""{"startDate":"2026-11-01"}""", calls[1].normalizedArguments())
    }

    @Test
    fun `appendToolRoundMessages 将空 arguments 归一为 {}，并在思考模型轮次保留 reasoning_content`() {
        val messages = JSONArray()
        val call = ToolCallState(id = "call_acc", name = "get_accounts") // arguments 为空字符串
        val result = JSONObject().put("success", true).put("message", "共 2 个账户")

        appendToolRoundMessages(
            messages = messages,
            toolCalls = listOf(call),
            results = listOf(call to result),
            assistantText = "",
            reasoningContent = "先查用户的账户余额再汇总。"
        )

        assertEquals(2, messages.length())
        val assistantMsg = messages.getJSONObject(0)
        assertEquals("assistant", assistantMsg.getString("role"))
        assertEquals("", assistantMsg.getString("content"))
        assertEquals("先查用户的账户余额再汇总。", assistantMsg.getString("reasoning_content"))

        val toolCallJson = assistantMsg.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("{}", toolCallJson.getJSONObject("function").getString("arguments"))

        val toolMsg = messages.getJSONObject(1)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call_acc", toolMsg.getString("tool_call_id"))
        assertTrue(toolMsg.getString("content").contains("共 2 个账户"))
    }

    @Test
    fun `appendToolRoundMessages 非思考模型不写入 reasoning_content 字段`() {
        val messages = JSONArray()
        val call = ToolCallState(id = "call_1", name = "get_budgets")
        val result = JSONObject().put("success", true)

        appendToolRoundMessages(
            messages = messages,
            toolCalls = listOf(call),
            results = listOf(call to result),
            assistantText = "我帮您查一下预算",
            reasoningContent = null
        )

        val assistantMsg = messages.getJSONObject(0)
        assertEquals("我帮您查一下预算", assistantMsg.getString("content"))
        assertFalse(assistantMsg.has("reasoning_content"))
    }

    @Test
    fun `RoundAwareUsageTotals 同轮覆盖去重且跨轮准确累加`() {
        val totals = RoundAwareUsageTotals()
        // 第 1 轮收到两次累计 usage 快照（仅取最后一次，不重复翻倍）
        totals.recordRoundUsage(100, 10)
        totals.recordRoundUsage(100, 25)
        totals.commitRound()

        // 第 2 轮收到最终 usage
        totals.recordRoundUsage(160, 40)
        totals.commitRound()

        assertEquals(260, totals.totalIn())
        assertEquals(65, totals.totalOut())
    }
}
