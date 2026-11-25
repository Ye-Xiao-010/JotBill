package com.jotbill.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AiErrorFormatterTest {
    @Test
    fun `extractServerErrorMessage 解析各主流厂商错误载荷并过滤 HTML`() {
        assertEquals(
            "Incorrect API key provided",
            AiErrorFormatter.extractServerErrorMessage(
                """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
            )
        )
        assertEquals(
            "Insufficient Balance",
            AiErrorFormatter.extractServerErrorMessage("""{"message":"Insufficient Balance"}""")
        )
        assertEquals(
            "模型不存在",
            AiErrorFormatter.extractServerErrorMessage("""{"msg":"模型不存在"}""")
        )
        assertNull(
            AiErrorFormatter.extractServerErrorMessage("<html><body>502 Bad Gateway</body></html>")
        )
    }

    @Test
    fun `evaluateTestResponse 拒绝 404 和非 JSON 假成功，放行真实聊天响应`() {
        val (notFoundOk, notFoundMsg) = AiErrorFormatter.evaluateTestResponse(
            404,
            """{"error":{"message":"Model not found"}}""",
            "bad-model"
        )
        assertFalse(notFoundOk)
        assertTrue(notFoundMsg.contains("HTTP 404"))
        assertTrue(notFoundMsg.contains("Model not found"))

        val (htmlOk, htmlMsg) = AiErrorFormatter.evaluateTestResponse(
            200,
            "<html>Login Required</html>",
            "deepseek-chat"
        )
        assertFalse(htmlOk)
        assertTrue(htmlMsg.contains("有效 JSON"))

        val (bodyErrOk, bodyErrMsg) = AiErrorFormatter.evaluateTestResponse(
            200,
            """{"error":{"message":"Rate limit reached"}}""",
            "qwen-plus"
        )
        assertFalse(bodyErrOk)
        assertTrue(bodyErrMsg.contains("Rate limit reached"))

        val (ok, okMsg) = AiErrorFormatter.evaluateTestResponse(
            200,
            """{"id":"chatcmpl-123","choices":[{"index":0,"message":{"role":"assistant","content":"hi"}}]}""",
            "deepseek-chat"
        )
        assertTrue(ok)
        assertTrue(okMsg.contains("deepseek-chat"))
    }

    @Test
    fun `readableError 透传 HTTP 状态码与服务端明细而不吞掉错误`() {
        val paymentMsg = AiErrorFormatter.readableError(
            AiHttpException(402, "Insufficient Balance")
        )
        assertTrue(paymentMsg.contains("余额不足"))
        assertTrue(paymentMsg.contains("Insufficient Balance"))

        val authMsg = AiErrorFormatter.readableError(
            AiHttpException(401, "Invalid token")
        )
        assertTrue(authMsg.contains("API Key 无效"))
        assertTrue(authMsg.contains("Invalid token"))

        val sseErrMsg = AiErrorFormatter.readableError(
            IOException("AI 服务返回错误: rate limit")
        )
        assertEquals("AI 服务返回错误: rate limit", sseErrMsg)

        assertEquals(
            "AI 请求超时，请稍后重试",
            AiErrorFormatter.readableError(SocketTimeoutException("read timed out"))
        )
        assertTrue(
            AiErrorFormatter.readableError(UnknownHostException("api.deepseek.com"))
                .contains("网络连接失败")
        )
    }
}
