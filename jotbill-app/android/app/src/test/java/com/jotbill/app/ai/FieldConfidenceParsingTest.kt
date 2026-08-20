package com.jotbill.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fieldConfidence 宽容解析测试。
 *
 * 真机故障回归：模型在 orderId 返回 null 时省略了它的置信度，
 * 而客户端要求 fieldConfidence 精确包含 5 个键，导致整份已正确识别的结果
 * （商品 25g*10盒、商户 金桂白牡丹、金额 75.60、FINAL_PAYABLE）被拒收，
 * 会话落 FAILED，最终账单只有金额没有 AI 字段。
 */
class FieldConfidenceParsingTest {

    private val knownKeys = setOf(
        "platform",
        "merchant",
        "productDescription",
        "amount",
        "orderId"
    )

    @Test
    fun 缺少orderId置信度时保留其余字段() {
        val source = JSONObject()
            .put("platform", 0.95)
            .put("merchant", 0.9)
            .put("productDescription", 0.88)
            .put("amount", 0.99)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(4, parsed.size)
        assertEquals(0.99, parsed["amount"])
        assertFalse(parsed.containsKey("orderId"))
    }

    @Test
    fun 完整置信度全部保留() {
        val source = JSONObject()
            .put("platform", 0.95)
            .put("merchant", 0.9)
            .put("productDescription", 0.88)
            .put("amount", 0.99)
            .put("orderId", 0.5)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(5, parsed.size)
        assertEquals(0.5, parsed["orderId"])
    }

    @Test
    fun 整体缺失时返回空表而非报错() {
        assertTrue(extractFieldConfidence(null, knownKeys).isEmpty())
        assertTrue(extractFieldConfidence(JSONObject(), knownKeys).isEmpty())
    }

    @Test
    fun 显式null置信度被忽略() {
        val source = JSONObject()
            .put("amount", 0.9)
            .put("orderId", JSONObject.NULL)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(1, parsed.size)
        assertFalse(parsed.containsKey("orderId"))
    }

    @Test
    fun 超出0到1区间的数值被忽略() {
        val source = JSONObject()
            .put("amount", 1.5)
            .put("merchant", -0.2)
            .put("platform", 1.0)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(1, parsed.size)
        assertEquals(1.0, parsed["platform"])
    }

    @Test
    fun 非数值类型被忽略而不报错() {
        val source = JSONObject()
            .put("amount", "0.9")
            .put("merchant", JSONObject().put("value", 0.8))
            .put("platform", 0.7)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(1, parsed.size)
        assertEquals(0.7, parsed["platform"])
    }

    @Test
    fun 协议外的额外键不进入结果() {
        val source = JSONObject()
            .put("amount", 0.9)
            .put("unexpectedField", 0.6)

        val parsed = extractFieldConfidence(source, knownKeys)

        assertEquals(1, parsed.size)
        assertFalse(parsed.containsKey("unexpectedField"))
    }
}
