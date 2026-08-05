# 01. AI 服务商适配器规范

> 技术实现文档(TDD)第一部分
>
> 关联文档:NEW_PROJECT_KICKOFF_V2.md 第8节、DECISIONS.md 第二节、types/Native.d.ts 第九部分
>
> 作用:定义 AI 视觉识别的服务商适配器接口、Prompt 模板、三家服务商的请求/响应差异,作为阶段一 AI 能力验证的实现基础

---

## 1. 架构总览

```
┌─────────────────────────────────────────────┐
│ AiVisionClient (Kotlin)                      │
│  - 接收 CheckoutSnapshot                     │
│  - 调用 AiProviderAdapter.recognize()        │
│  - 校验 JSON Schema                          │
│  - 返回 AiRecognitionResult                  │
└──────────────────┬──────────────────────────┘
                   │
        ┌──────────┼──────────┐
        v          v          v
┌──────────┐ ┌──────────┐ ┌──────────────┐
│ QwenAdapter│ │ GlmAdapter│ │SiliconFlow  │
│ (主用)    │ │ (备用)    │ │Adapter(调试)│
└──────────┘ └──────────┘ └──────────────┘
```

### 核心原则

- 三个适配器实现同一接口 `AiProviderAdapter`
- Prompt 模板统一管理,由 `PromptManager` 提供,适配器只负责格式转换
- 响应统一解析为 `AiRecognitionResult`,屏蔽服务商差异
- 成本控制在 `CostController` 中实现,适配器只报告 token 用量

---

## 2. AiProviderAdapter 接口定义

```kotlin
/**
 * AI 服务商适配器接口
 * 负责将统一的识别请求转换为各服务商的 API 格式,并将响应解析为统一结果
 */
interface AiProviderAdapter {

    /** 服务商标识 */
    val provider: AiProvider

    /** 该适配器是否支持视觉识别 */
    val supportsVision: Boolean

    /**
     * 识别结算页截图
     *
     * @param imageBase64  经过压缩和脱敏的图片 Base64(不含 data:image/xxx;base64, 前缀)
     * @param prompt       PromptManager 生成的统一 Prompt 文本
     * @param config       服务商配置(API Key/Base URL/Model)
     * @param timeoutMs    超时时间(毫秒)
     * @return 识别结果,包含提取字段和 token 用量
     */
    suspend fun recognize(
        imageBase64: String,
        prompt: String,
        config: AiProviderConfig,
        timeoutMs: Long
    ): AiRecognitionResponse

    /**
     * 测试连通性
     *
     * @param config 服务商配置
     * @return 连接测试结果
     */
    suspend fun testConnection(config: AiProviderConfig): TestConnectionResult
}

/**
 * AI 识别响应(适配器返回的统一格式)
 */
data class AiRecognitionResponse(
    val result: AiRecognitionResult?,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val success: Boolean,
    val error: AiAdapterError?
)

data class AiAdapterError(
    val code: NativeErrorCode,
    val message: String,
    val httpStatus: Int? = null
)
```

---

## 3. Prompt 模板

### 3.1 Prompt 版本管理

```kotlin
/**
 * Prompt 模板管理器
 * 负责 Prompt 的版本化管理和渲染
 */
class PromptManager {

    companion object {
        const val CURRENT_VERSION = "checkout_v1"
    }

    /**
     * 生成结算页识别 Prompt
     *
     * @return 包含版本号的 Prompt 文本
     */
    fun getCheckoutPrompt(): PromptContent {
        return PromptContent(
            version = CURRENT_VERSION,
            system = SYSTEM_PROMPT,
            user = USER_PROMPT_TEMPLATE
        )
    }
}
```

### 3.2 System Prompt

```text
你是一个支付结算页识别助手。用户会提供一张购物应用结算页的截图,你需要从中提取以下信息:

1. amountMinor: 应付/实付金额,单位为分(元 × 100),整数
2. platform: 消费平台(如 京东/淘宝/美团/拼多多/滴滴)
3. merchant: 商户名称(如适用,外卖/到店场景填写,电商场景可留空)
4. productDescription: 商品说明(电商场景填写,如"手机壳×1")
5. orderId: 订单号(如页面可见)
6. confidence: 置信度 0-1,你对提取结果的把握程度

注意事项:
- 金额以结算页显示的"应付""实付""合计"等字段为准,不要取商品单价
- 商户字段:外卖/到店/扫码场景为必填;电商/出行/虚拟商品场景可为空
- 如果图片不是结算页或无法识别,所有字段返回 null,confidence 返回 0
- 只返回 JSON,不要添加任何其他文字
```

### 3.3 User Prompt 模板

```text
请识别这张购物结算页截图,提取信息并以 JSON 格式返回。

要求返回的 JSON 格式:
{
  "amountMinor": <整数或null>,
  "platform": "<字符串或null>",
  "merchant": "<字符串或null>",
  "productDescription": "<字符串或null>",
  "orderId": "<字符串或null>",
  "confidence": <0到1的小数>
}
```

### 3.4 响应 JSON Schema

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "amountMinor": {
      "type": ["integer", "null"],
      "description": "金额(分),必须为正整数"
    },
    "platform": {
      "type": ["string", "null"],
      "description": "消费平台"
    },
    "merchant": {
      "type": ["string", "null"],
      "description": "商户名称(场景相关)"
    },
    "productDescription": {
      "type": ["string", "null"],
      "description": "商品说明"
    },
    "orderId": {
      "type": ["string", "null"],
      "description": "订单号"
    },
    "confidence": {
      "type": "number",
      "minimum": 0,
      "maximum": 1,
      "description": "置信度"
    }
  },
  "required": ["confidence"],
  "additionalProperties": false
}
```

### 3.5 本地校验规则

```kotlin
/**
 * AI 响应校验器
 * 在适配器返回后,本地校验 JSON 格式和字段合法性
 */
class AiResponseValidator {

    fun validate(json: String): AiRecognitionResult {
        // 1. 解析 JSON(失败返回 AI_PARSE 错误)
        // 2. 校验 confidence 必填(缺失返回 AI_PARSE)
        // 3. 校验 amountMinor 为正整数(若存在)
        // 4. 校验 confidence 在 0-1 范围
        // 5. 裁剪字符串字段长度(platform/merchant ≤ 50,productDescription ≤ 200,orderId ≤ 64)
        // 6. 返回 AiRecognitionResult
    }
}
```

---

## 4. 三家服务商适配器实现

### 4.1 智谱 GLM-4V-Flash 适配器(备用,OpenAI 兼容)

智谱 GLM API 兼容 OpenAI 格式,使用 `image_url` + Base64 data URL。

> **阶段一验证更新(2026-08-04):** 原计划使用的 `glm-4.7-flash` 为纯文本模型,不支持图片输入(错误码 1210)。已切换为 `glm-4v-flash` 多模态视觉模型,真机验证通过。

**请求格式:**

```json
{
  "model": "glm-4v-flash",
  "messages": [
    {
      "role": "system",
      "content": "<System Prompt>"
    },
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "<User Prompt>"
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,<Base64>"
          }
        }
      ]
    }
  ],
  "temperature": 0.1,
  "max_tokens": 500
}
```

**请求头:**

```
Authorization: Bearer <API Key>
Content-Type: application/json
```

**API 地址:** `https://open.bigmodel.cn/api/paas/v4/chat/completions`

**响应解析:**

```kotlin
// 从 response.choices[0].message.content 提取 JSON
// 从 response.usage 提取 prompt_tokens / completion_tokens
```

**关键实现:**

```kotlin
/**
 * 智谱 GLM 适配器
 * 兼容 OpenAI Vision 格式,使用 image_url + Base64 data URL
 */
class GlmAdapter : AiProviderAdapter {

    override val provider = AiProvider.GLM
    override val supportsVision = true

    override suspend fun recognize(
        imageBase64: String,
        prompt: String,
        config: AiProviderConfig,
        timeoutMs: Long
    ): AiRecognitionResponse {
        val request = buildOpenAICompatibleRequest(
            model = config.model,
            systemPrompt = promptSystem,
            userPrompt = promptUser,
            imageBase64 = imageBase64,
            imageUrl = "data:image/jpeg;base64,$imageBase64"
        )
        // 发送 POST 到 config.apiBaseUrl + "/chat/completions"
        // 解析响应
    }
}
```

**注意事项:**
- GLM-4V-Flash 当前免费,支持视觉输入
- Base URL 默认 `https://open.bigmodel.cn/api/paas/v4`
- 图片格式支持 JPEG/PNG,Base64 不含 `data:image/xxx;base64,` 前缀时需拼接
- ⚠️ 不要误用 `glm-4.7-flash`(混合思考模型,纯文本,不支持图片)

### 4.2 通义千问 qwen-vl-plus 适配器(主用)

通义千问支持两种调用方式:DashScope 原生格式和 OpenAI 兼容格式。推荐使用 OpenAI 兼容格式,减少适配器差异。

**请求格式(OpenAI 兼容):**

```json
{
  "model": "qwen-vl-plus",
  "messages": [
    {
      "role": "system",
      "content": [
        {
          "type": "text",
          "text": "<System Prompt>"
        }
      ]
    },
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "<User Prompt>"
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,<Base64>"
          }
        }
      ]
    }
  ],
  "temperature": 0.1,
  "max_tokens": 500
}
```

**请求头:**

```
Authorization: Bearer <API Key>
Content-Type: application/json
```

**API 地址:** `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`

**关键实现:**

```kotlin
/**
 * 通义千问适配器
 * 使用 DashScope OpenAI 兼容模式
 */
class QwenAdapter : AiProviderAdapter {

    override val provider = AiProvider.QWEN
    override val supportsVision = true

    override suspend fun recognize(
        imageBase64: String,
        prompt: String,
        config: AiProviderConfig,
        timeoutMs: Long
    ): AiRecognitionResponse {
        // 与 GlmAdapter 结构一致,仅 API 地址和默认模型不同
        // Base URL: https://dashscope.aliyuncs.com/compatible-mode/v1
        // 注意: qwen-vl-plus 的 system 消息 content 也需要是数组格式
    }
}
```

**注意事项:**
- qwen-vl-plus 的 system 消息 `content` 需要是数组格式(`[{type:"text",text:"..."}]`),而非纯字符串(与 GLM 略有差异)
- 图片大小限制:单张图片 Base64 不超过 10MB(压缩后通常 100-500KB,无问题)
- ✅ 阶段一已验证:qwen-vl-plus 真机识别正常

### 4.3 硅基流动 Qwen3-VL-8B-Instruct 适配器(开发调试)

硅基流动完全兼容 OpenAI 格式。

> **阶段一验证更新(2026-08-04):** 原计划使用 `Qwen/Qwen2-VL-7B-Instruct`,真机验证时改用 `Qwen/Qwen3-VL-8B-Instruct`(8B 视觉模型,识别能力更优,仍在 9B 以下免费范围内)。

**请求格式:**

与 GLM 适配器完全一致(OpenAI 兼容格式)。

**请求头:**

```
Authorization: Bearer <API Key>
Content-Type: application/json
```

**API 地址:** `https://api.siliconflow.cn/v1/chat/completions`

**模型名:** `Qwen/Qwen3-VL-8B-Instruct`

**关键实现:**

```kotlin
/**
 * 硅基流动适配器
 * 完全兼容 OpenAI 格式,仅用于开发调试
 */
class SiliconFlowAdapter : AiProviderAdapter {

    override val provider = AiProvider.SILICONFLOW
    override val supportsVision = true

    override suspend fun recognize(
        imageBase64: String,
        prompt: String,
        config: AiProviderConfig,
        timeoutMs: Long
    ): AiRecognitionResponse {
        // 与 GlmAdapter 完全一致
        // Base URL: https://api.siliconflow.cn/v1
        // 默认模型: Qwen/Qwen3-VL-8B-Instruct
    }
}
```

**注意事项:**
- 9B 以下模型永久免费,Qwen3-VL-8B-Instruct 在免费范围内
- 8B 视觉模型识别能力优于 7B,适合开发调试
- ⚠️ 不要误用 `Qwen/Qwen3.5-4B`(纯文本模型,不支持图片,会触发超时)
- ✅ 阶段一已验证:Qwen3-VL-8B-Instruct 真机识别正常

---

## 5. 三家服务商差异对比

| 维度 | GLM-4V-Flash | qwen-vl-plus | Qwen3-VL-8B-Instruct |
|---|---|---|---|
| API 兼容 | OpenAI 兼容 | OpenAI 兼容 | OpenAI 兼容 |
| Base URL | open.bigmodel.cn/api/paas/v4 | dashscope.aliyuncs.com/compatible-mode/v1 | api.siliconflow.cn/v1 |
| 图片字段 | `image_url` + data URL | `image_url` + data URL | `image_url` + data URL |
| system content | 字符串 | **数组**(差异点) | 字符串 |
| 默认模型 | glm-4v-flash | qwen-vl-plus | Qwen/Qwen3-VL-8B-Instruct |
| 价格 | 免费 | 约 1.5 元/百万 token | 免费(9B以下) |
| 上下文 | 200K | 128K | 128K |
| 速率限制 | 待确认 | 待确认 | 新用户 2000 万 token |
| 阶段一验证 | ✅ 通过 | ✅ 通过 | ✅ 通过 |

### 适配器差异处理

由于三家都兼容 OpenAI 格式,可以提取一个公共基类:

```kotlin
/**
 * OpenAI 兼容适配器基类
 * 三家服务商均兼容 OpenAI Vision 格式,差异仅在于:
 * 1. API 地址
 * 2. 默认模型名
 * 3. system content 格式(GLM/SiliconFlow 为字符串,Qwen 为数组)
 */
abstract class OpenAICompatibleAdapter : AiProviderAdapter {

    protected abstract val defaultBaseUrl: String
    protected abstract val defaultModel: String
    protected abstract val useArraySystemContent: Boolean

    override suspend fun recognize(
        imageBase64: String,
        prompt: String,
        config: AiProviderConfig,
        timeoutMs: Long
    ): AiRecognitionResponse {
        val baseUrl = config.apiBaseUrl ?: defaultBaseUrl
        val model = config.model.ifEmpty { defaultModel }
        val url = "$baseUrl/chat/completions"

        val requestBody = buildRequestBody(model, prompt, imageBase64)
        val response = httpClient.post(url, requestBody, config.apiKey, timeoutMs)

        return parseResponse(response)
    }

    private fun buildRequestBody(
        model: String,
        prompt: PromptContent,
        imageBase64: String
    ): String {
        // 根据 useArraySystemContent 决定 system content 格式
        // 其余结构统一
    }
}

class GlmAdapter : OpenAICompatibleAdapter() {
    override val provider = AiProvider.GLM
    override val defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4"
    override val defaultModel = "glm-4v-flash"
    override val useArraySystemContent = false
}

class QwenAdapter : OpenAICompatibleAdapter() {
    override val provider = AiProvider.QWEN
    override val defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    override val defaultModel = "qwen-vl-plus"
    override val useArraySystemContent = true  // 差异点
}

class SiliconFlowAdapter : OpenAICompatibleAdapter() {
    override val provider = AiProvider.SILICONFLOW
    override val defaultBaseUrl = "https://api.siliconflow.cn/v1"
    override val defaultModel = "Qwen/Qwen3-VL-8B-Instruct"
    override val useArraySystemContent = false
}
```

---

## 6. 图片预处理

```kotlin
/**
 * 图片预处理器
 * 在上传 AI 前对截图进行压缩和脱敏
 */
class ImagePreprocessor {

    /**
     * 预处理截图
     *
     * @param bitmap  原始截图 Bitmap
     * @return 压缩后的 Base64 字符串(不含 data URL 前缀)
     */
    fun preprocess(bitmap: Bitmap): String {
        // 1. 裁剪结算区域(如果有裁剪信息)
        // 2. 检测敏感内容(银行卡号/验证码/密码关键词)
        // 3. 压缩为 JPEG(质量 85,最大边 1920px)
        // 4. 转换为 Base64
        // 5. 释放原始 Bitmap
    }
}
```

### 压缩策略

| 参数 | 值 | 说明 |
|---|---|---|
| 格式 | JPEG | 比 PNG 小,结算页无透明需求 |
| 质量 | 85 | 兼顾体积和文字清晰度 |
| 最大边 | 1920px | 超过则等比缩放 |
| 预期体积 | 100-500KB | Base64 后 150-700KB |

### 脱敏检测

```kotlin
// 银行卡号:连续 13-19 位数字
private val BANK_CARD_PATTERN = Regex("\\d{13,19}")

// 验证码:4-8 位数字,附近有"验证码"字样
private val VERIFICATION_CODE_PATTERN = Regex("(?:验证码|验证码为)[:：\\s]*(\\d{4,8})")

// 密码关键词
private val SENSITIVE_KEYWORDS = listOf("密码", "验证码", "指纹", "人脸", "CVV", "安全码")

// 二维码关键词
private val QR_CODE_KEYWORDS = listOf("二维码", "扫码", "条形码")
```

---

## 7. 故障转移与成本控制

### 7.1 故障转移流程

```
用户提交订单 → 触发 AI 识别
  → 尝试主用服务商(qwen-vl-plus)
    → 成功 → 返回结果
    → 失败(超时/5xx/网络错误)
      → 尝试备用服务商(glm-4v-flash)
        → 成功 → 返回结果(记录主用失败原因)
        → 失败 → 返回错误,进入待确认(不阻塞支付)
```

### 7.2 CostController

```kotlin
/**
 * AI 成本控制器
 * 在调用 AI 前检查预算和频率限制
 */
class CostController {

    /**
     * 检查是否允许调用 AI
     *
     * @return 不允许时返回错误码
     */
    suspend fun checkCanCall(): NativeErrorCode? {
        // 1. 检查月度预算:已花费 + 预估成本 > 月度预算 → AI_BUDGET_EXCEEDED
        // 2. 检查频率限制:距上次调用 < 最小间隔(5秒) → AI_RATE_LIMIT
        // 3. 检查日调用次数:超过日上限(100次) → AI_RATE_LIMIT
        // 4. 返回 null 表示允许
    }

    /**
     * 记录一次 AI 调用
     */
    suspend fun recordCall(
        provider: AiProvider,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
        costMinor: Long,
        success: Boolean
    ) {
        // 写入 AiCostRecord 表
        // 更新月度累计
    }
}
```

### 7.3 成本估算

```kotlin
/**
 * 根据服务商和 token 用量估算成本(分)
 */
fun estimateCost(provider: AiProvider, tokensIn: Int, tokensOut: Int): Long {
    return when (provider) {
        AiProvider.GLM -> 0  // 免费
        AiProvider.SILICONFLOW -> 0  // 免费(9B以下)
        AiProvider.QWEN -> {
            // qwen-vl-plus: 输入 1.5元/百万token,输出约 2元/百万token
            val costIn = tokensIn.toLong() * 150 / 1_000_000  // 分
            val costOut = tokensOut.toLong() * 200 / 1_000_000  // 分
            costIn + costOut
        }
        AiProvider.CUSTOM -> 0  // 用户自定义,暂不估算
    }
}
```

---

## 8. 阶段一验证清单

阶段一需使用本规范实现以下验证:

| # | 验证项 | 方法 | 通过标准 |
|---|---|---|---|
| 1 | 三家服务商 API 连通性 | 用 testConnection() 逐个测试 | 三家都返回 success=true |
| 2 | OpenAI 兼容格式通用性 | 同一请求体发三家 | 至少两家能正确返回 JSON |
| 3 | system content 格式差异 | Qwen 用数组、其余用字符串 | Qwen 不报格式错误 |
| 4 | 结算页识别准确率 | 10 张真实结算页截图 | 主用模型 ≥ 80% 正确提取金额 |
| 5 | 商户字段场景差异 | 外卖/电商各 5 张 | 外卖商户提取率 ≥ 80%,电商可为空 |
| 6 | 响应延迟 | 记录 10 次请求耗时 | 主用模型中位数 ≤ 5 秒 |
| 7 | JSON Schema 校验 | 测试异常响应(非结算页) | confidence=0 时正确处理 |
| 8 | 图片压缩效果 | 对比原图和压缩图识别率 | 压缩后识别率不低于原图 90% |
| 9 | 成本估算准确性 | 对比实际账单和估算 | 偏差 ≤ 20% |
| 10 | 故障转移 | 模拟主用失败 | 自动切换备用并返回结果 |

### 验证失败的处理

- 验证项 1-3 失败:检查 API Key/地址/格式,必要时调整适配器
- 验证项 4 失败(准确率 < 80%):调整 Prompt 模板,或更换主用模型
- 验证项 6 失败(延迟 > 5 秒):调整 timeoutMs,或更换为更快的模型
- 验证项 8 失败:调整压缩参数(提高质量或分辨率)
