package com.jotbill.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 描述型字段扁平化测试。
 *
 * 真机故障回归：模型把 productDescription 返回为
 * `{"name":"加厚垃圾袋","details":[...]}`，而协议只接受字符串或 null，
 * 严格校验抛出 SchemaException 后整份结果（含已正确识别的金额 39.50、
 * 商户名）被全部丢弃，会话落 FAILED。
 */
class DescriptiveValueFlattenTest {

    private val maxLength = 300

    @Test
    fun 纯字符串保持原值() {
        assertEquals(
            "加厚垃圾袋",
            flattenDescriptiveValue("加厚垃圾袋", maxLength)
        )
    }

    @Test
    fun 结构化商品对象按优先键提取文本() {
        val value = JSONObject()
            .put("name", "加厚垃圾袋")
            .put("details", JSONArray().put("超大加厚").put("50只"))

        assertEquals(
            "加厚垃圾袋",
            flattenDescriptiveValue(value, maxLength)
        )
    }

    @Test
    fun 无优先键的对象递归扁平化全部值() {
        val value = JSONObject()
            .put("brand", "某品牌")
            .put("size", "80x100cm")

        val flattened = flattenDescriptiveValue(value, maxLength)
        requireNotNull(flattened)
        assertEquals(true, flattened.contains("某品牌"))
        assertEquals(true, flattened.contains("80x100cm"))
    }

    @Test
    fun 数组按顺序连接() {
        val value = JSONArray().put("酱牛肉150g").put("无料包")

        assertEquals(
            "酱牛肉150g 无料包",
            flattenDescriptiveValue(value, maxLength)
        )
    }

    @Test
    fun 嵌套对象数组可提取() {
        val value = JSONArray()
            .put(JSONObject().put("name", "商品甲"))
            .put(JSONObject().put("title", "商品乙"))

        assertEquals(
            "商品甲 商品乙",
            flattenDescriptiveValue(value, maxLength)
        )
    }

    @Test
    fun 空白与空结构返回null() {
        assertNull(flattenDescriptiveValue(null, maxLength))
        assertNull(flattenDescriptiveValue(JSONObject.NULL, maxLength))
        assertNull(flattenDescriptiveValue("   ", maxLength))
        assertNull(flattenDescriptiveValue(JSONObject(), maxLength))
        assertNull(flattenDescriptiveValue(JSONArray(), maxLength))
    }

    @Test
    fun 多余空白被归一化为单空格() {
        assertEquals(
            "酱牛肉 无料包",
            flattenDescriptiveValue("酱牛肉  \n 无料包 ", maxLength)
        )
    }

    @Test
    fun 超长内容被截断而非报错() {
        val flattened = flattenDescriptiveValue("商".repeat(500), maxLength)
        requireNotNull(flattened)
        assertEquals(maxLength, flattened.length)
    }

    @Test
    fun 数字与布尔值取字面量() {
        assertEquals("2", flattenDescriptiveValue(2, maxLength))
        assertEquals("true", flattenDescriptiveValue(true, maxLength))
    }
}
