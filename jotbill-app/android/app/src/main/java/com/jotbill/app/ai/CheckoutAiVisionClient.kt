package com.jotbill.app.ai

import android.util.Base64
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AiProviderConfigEntity
import com.jotbill.app.data.entity.AiCostRecordEntity
import com.jotbill.app.recognition.CheckoutAiTriggerResult
import com.jotbill.app.recognition.CheckoutRequestKey
import com.jotbill.app.recognition.PurchaseIntent
import com.jotbill.app.security.KeyStoreSecretStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume

private fun defaultCheckoutAiVisionHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

/**
 * 把 AI 返回的描述型字段值归一化为单行字符串。
 *
 * 真机故障回归：模型有时把 productDescription 返回成结构化对象
 * （如 `{"name":"加厚垃圾袋","details":[...]}`）或字符串数组，而协议只接受
 * 字符串或 null。此前严格校验直接抛异常，导致整份已成功识别的结果（含金额、
 * 商户）被全部丢弃。这里改为尽力扁平化提取可读文本。
 *
 * 提取规则：
 * - 字符串/数字/布尔：直接取字面值；
 * - 数组：按顺序递归扁平化后用空格连接；
 * - 对象：优先取 name/title/text/value/description 等常见键，
 *   若均不存在则递归扁平化所有值；
 * - null 或提取结果为空白：返回 null。
 *
 * @param value 原始 JSON 值。
 * @param maxLength 归一化结果的最大长度，超出即截断。
 * @return 归一化后的非空字符串；无可用文本时返回 null。
 */
internal fun flattenDescriptiveValue(value: Any?, maxLength: Int): String? {
    val flattened = flattenDescriptiveValueInternal(value, depth = 0) ?: return null
    val normalized = flattened
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (normalized.isEmpty()) {
        return null
    }
    return normalized.take(maxLength)
}

/** 描述型字段扁平化时优先采用的键名，按优先级排列。 */
private val DESCRIPTIVE_PREFERRED_KEYS = listOf(
    "name",
    "title",
    "text",
    "value",
    "description",
    "productName",
    "spec",
    "specification"
)

private const val DESCRIPTIVE_MAX_DEPTH = 4

private fun flattenDescriptiveValueInternal(value: Any?, depth: Int): String? {
    if (depth > DESCRIPTIVE_MAX_DEPTH) {
        return null
    }
    return when (value) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { it.isNotBlank() }
        is Number, is Boolean -> value.toString()
        is JSONArray -> (0 until value.length())
            .mapNotNull { index ->
                flattenDescriptiveValueInternal(value.opt(index), depth + 1)
            }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

        is JSONObject -> {
            val preferred = DESCRIPTIVE_PREFERRED_KEYS
                .asSequence()
                .filter { value.has(it) && !value.isNull(it) }
                .mapNotNull { flattenDescriptiveValueInternal(value.opt(it), depth + 1) }
                .joinToString(" ")
            if (preferred.isNotBlank()) {
                preferred
            } else {
                value.keys()
                    .asSequence()
                    .mapNotNull { flattenDescriptiveValueInternal(value.opt(it), depth + 1) }
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() }
            }
        }

        else -> null
    }
}

/**
 * 从 fieldConfidence 对象中提取可用置信度，缺项与非法值一律忽略。
 *
 * 真机故障回归：模型在 orderId 为 null 时省略了它的置信度，而客户端要求
 * fieldConfidence 精确包含 5 个键，导致整份已正确识别的结果
 * （商品、商户、金额 75.60、FINAL_PAYABLE）被拒收，会话落 FAILED。
 *
 * 该字段全链路无消费方，仅作诊断参考，不参与金额校验、匹配与自动入账判定，
 * 因此不应具备否决整份结果的能力。
 *
 * @param source fieldConfidence 对象；null 表示模型未返回。
 * @param knownKeys 协议关注的键集合。
 * @return 实际提供且落在 0..1 区间内的置信度映射，可能为空。
 */
internal fun extractFieldConfidence(
    source: JSONObject?,
    knownKeys: Set<String>
): Map<String, Double> {
    if (source == null) {
        return emptyMap()
    }
    return knownKeys.mapNotNull { key ->
        if (!source.has(key) || source.isNull(key)) {
            return@mapNotNull null
        }
        val value = (source.opt(key) as? Number)?.toDouble() ?: return@mapNotNull null
        if (value !in 0.0..1.0) {
            return@mapNotNull null
        }
        key to value
    }.toMap()
}

/** 视觉客户端的稳定失败码；不把服务商原始响应暴露给上层或日志。 */
enum class AiVisionFailureCode(val wireValue: String) {
    AI_CONFIG_MISSING("AI_CONFIG_MISSING"),
    AI_CONFIG_INVALID("AI_CONFIG_INVALID"),
    AI_VISION_UNSUPPORTED("AI_VISION_UNSUPPORTED"),
    SNAPSHOT_INVALID("SNAPSHOT_INVALID"),
    IMAGE_TOO_LARGE("IMAGE_TOO_LARGE"),
    REQUEST_FAILED("AI_REQUEST_FAILED"),
    TIMEOUT("AI_TIMEOUT"),
    HTTP_ERROR("AI_HTTP_ERROR"),
    PROTOCOL_ERROR("AI_PROTOCOL_ERROR"),
    RESPONSE_TOO_LARGE("AI_RESPONSE_TOO_LARGE"),
    JSON_SCHEMA_INVALID("AI_SCHEMA"),
    RETRY_EXHAUSTED("AI_RETRY_EXHAUSTED"),
    CANCELLED("AI_CANCELLED"),
    DUPLICATE_REQUEST("AI_TRIGGER_DUPLICATE"),
    CLIENT_CLOSED("AI_CLIENT_CLOSED")
}

/** 不含图片的请求元数据，供后续支付匹配和 WAITING_ENRICHMENT 使用。 */
data class AiVisionRequestMetadata(
    val requestKey: CheckoutRequestKey,
    val intent: PurchaseIntent,
    val sessionId: String,
    val snapshotId: String,
    val slotId: String,
    val shoppingPackage: String,
    val pageFingerprint: String,
    val candidateRevision: Int,
    val capturedAt: Long,
    val snapshotExpiresAt: Long
)

/** 已通过本地 JSON 校验的识别字段；这里只提供匹配输入，不执行支付或记账。 */
data class AiVisionFields(
    val screenType: String,
    val platform: String?,
    val merchant: String?,
    val productDescription: String?,
    val amountMinor: Long,
    val amountSemantic: String,
    val currency: String,
    val orderId: String?,
    val paymentMethod: String?,
    val occurredAt: String?,
    val confidence: Double,
    val fieldConfidence: Map<String, Double>
)

/** 视觉请求结果；客户端不会把图片或 Base64 放入结果。 */
sealed class AiVisionResult {
    abstract val metadata: AiVisionRequestMetadata

    data class Succeeded(
        override val metadata: AiVisionRequestMetadata,
        val fields: AiVisionFields,
        val requestId: String,
        val provider: String,
        val model: String,
        val attempts: Int,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val latencyMillis: Long
    ) : AiVisionResult()

    data class Failed(
        override val metadata: AiVisionRequestMetadata,
        val code: AiVisionFailureCode,
        val attempts: Int,
        val requestId: String?,
        val provider: String?,
        val model: String?,
        val lastFailureCode: AiVisionFailureCode? = null,
        val httpStatus: Int? = null,
        val retryable: Boolean = false,
        val latencyMillis: Long
    ) : AiVisionResult()
}

/** 结算页视觉结果的本地消费者；结果不包含图片，回调可替换且不负责记账。 */
fun interface CheckoutAiResultCallback {
    fun onResult(result: AiVisionResult)
}

/**
 * 消费购买意图协调器交付的 Ready.snapshot，并通过现有 OpenAI 兼容配置发起一次视觉请求。
 *
 * 调用 recognize 后，Ready.snapshot 的释放责任转移给本客户端；所有正常返回、异常和协程取消
 * 路径都会在 finally 中清零图片。客户端不保存图片、不写会话表、不执行支付匹配或记账。
 */
class CheckoutAiVisionClient(
    private val database: JotBillDatabase,
    private val secretStore: KeyStoreSecretStore,
    private val httpClient: OkHttpClient = defaultCheckoutAiVisionHttpClient()
) {
    companion object {
        private const val TAG_REQUEST_TYPE = "checkout_vision"
        private const val PROMPT_VERSION = "checkout-v1"
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        private const val MAX_ATTEMPTS = 2
        private const val ATTEMPT_TIMEOUT_MILLIS = 15_000L
        private const val TOTAL_TIMEOUT_MILLIS = 32_000L
        private const val RETRY_BACKOFF_MILLIS = 350L
        private const val MAX_PLATFORM_LENGTH = 120
        private const val MAX_MERCHANT_LENGTH = 200
        private const val MAX_PRODUCT_LENGTH = 300
        private const val MAX_ORDER_ID_LENGTH = 128
        private const val MAX_PAYMENT_METHOD_LENGTH = 80
        private const val MAX_TIMESTAMP_LENGTH = 64
        private const val JSON_MEDIA_TYPE_VALUE = "application/json; charset=utf-8"

        private val JSON_MEDIA_TYPE = JSON_MEDIA_TYPE_VALUE.toMediaType()
        private val SAFE_AMOUNT_SEMANTICS = setOf(
            "FINAL_PAYABLE",
            "ACTUAL_PAYABLE",
            "DISCOUNTED_TOTAL",
            "ORDER_TOTAL"
        )
        private val RESULT_KEYS = setOf(
            "screenType",
            "platform",
            "merchant",
            "productDescription",
            "amount",
            "amountSemantic",
            "currency",
            "orderId",
            "paymentMethod",
            "occurredAt",
            "confidence",
            "fieldConfidence"
        )
        /**
         * 结算页结果中必须存在的核心键。
         *
         * 这些字段直接决定金额语义与入账判定，缺失即无法安全使用，必须拒收。
         * RESULT_KEYS 中的其余键属于补充信息，缺失只降低完整度，不影响正确性。
         */
        private val REQUIRED_RESULT_KEYS = setOf(
            "screenType",
            "amount"
        )
        private val FIELD_CONFIDENCE_KEYS = setOf(
            "platform",
            "merchant",
            "productDescription",
            "amount",
            "orderId"
        )
        private val AMOUNT_PATTERN = Regex("\\d{1,9}(\\.\\d{1,2})?")

        /** 剥离模型输出外层 Markdown 代码围栏（```json ... ```），捕获组为正文。 */
        private val CODE_FENCE_PATTERN = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

        /** 诊断日志脱敏：连续 6 位以上数字串（订单号/卡号片段）统一替换。 */
        private val LONG_DIGITS_PATTERN = Regex("\\d{6,}")

        /** 支付结果页交易类型：向个人转账。 */
        const val TRANSFER_TYPE_PERSONAL = "PERSONAL_TRANSFER"

        /** 支付结果页交易类型：商户消费（默认，兼容未返回 transferType 的旧输出）。 */
        const val TRANSFER_TYPE_MERCHANT_PAYMENT = "MERCHANT_PAYMENT"

    }

    private sealed class AttemptResult {
        data class Success(
            val fields: AiVisionFields,
            val tokensIn: Int?,
            val tokensOut: Int?
        ) : AttemptResult()

        data class Failure(
            val code: AiVisionFailureCode,
            val httpStatus: Int? = null
        ) : AttemptResult()
    }

    private data class ResponseUsage(
        val tokensIn: Int?,
        val tokensOut: Int?
    )

    /**
     * 仅用于诊断模型输出为何未通过本地 Schema；不会改变失败码、重试或业务流程。
     * 原始字段值不写入异常，避免将订单或支付敏感信息带入日志。
     */
    private class SchemaException(message: String) : Exception(message)

    private class ResponseTooLargeException : Exception()

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeRequestKeys = ConcurrentHashMap.newKeySet<String>()
    private val completedRequestKeys = ConcurrentHashMap.newKeySet<String>()
    private val cancelledRequestKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var closed = false

    /**
     * 接收一个已从 CheckoutSnapshotBuffer 消费的 Ready；调用成功进入本方法后由本方法负责 release。
     * 同一 checkoutRequestKey 只允许一个逻辑请求，网络重试不会再次消费缓冲区。
     */
    suspend fun recognize(ready: CheckoutAiTriggerResult.Ready): AiVisionResult {
        val snapshot = ready.snapshot
        val metadata = metadataOf(ready)
        val requestKey = ready.requestKey.value
        val activeAdded = activeRequestKeys.add(requestKey)
        val completedAdded = activeAdded && completedRequestKeys.add(requestKey)
        if (!activeAdded || !completedAdded) {
            if (activeAdded) activeRequestKeys.remove(requestKey)
            snapshot.release()
            return failedResult(
                metadata = metadata,
                code = AiVisionFailureCode.DUPLICATE_REQUEST,
                attempts = 0,
                requestId = null,
                provider = null,
                model = null,
                startedAt = System.currentTimeMillis()
            )
        }

        var costConfig: AiProviderConfigEntity? = null
        var shouldRecordCost = false
        var result: AiVisionResult? = null
        val startedAt = System.currentTimeMillis()
        var imageDataUrl: String? = null
        try {
            val finalResult = try {
                when {
                    closed -> failedResult(
                        metadata,
                        AiVisionFailureCode.CLIENT_CLOSED,
                        0,
                        null,
                        null,
                        null,
                        startedAt
                    )

                    snapshot.imageBytes.isEmpty() -> failedResult(
                        metadata,
                        AiVisionFailureCode.SNAPSHOT_INVALID,
                        0,
                        null,
                        null,
                        null,
                        startedAt
                    )

                    snapshot.imageBytes.size > MAX_IMAGE_BYTES -> failedResult(
                        metadata,
                        AiVisionFailureCode.IMAGE_TOO_LARGE,
                        0,
                        null,
                        null,
                        null,
                        startedAt
                    )

                    else -> {
                        val mimeType = imageMimeType(snapshot.imageBytes)
                        if (mimeType == null) {
                            failedResult(
                                metadata,
                                AiVisionFailureCode.SNAPSHOT_INVALID,
                                0,
                                null,
                                null,
                                null,
                                startedAt
                            )
                        } else {
                            val config = resolveProvider()
                            when {
                                config == null -> failedResult(
                                    metadata,
                                    AiVisionFailureCode.AI_CONFIG_MISSING,
                                    0,
                                    null,
                                    null,
                                    null,
                                    startedAt
                                )

                                !config.supportsVision -> failedResult(
                                    metadata,
                                    AiVisionFailureCode.AI_VISION_UNSUPPORTED,
                                    0,
                                    null,
                                    config.provider,
                                    config.model,
                                    startedAt
                                )

                                else -> {
                                    costConfig = config
                                    val endpoint = AiEndpointResolver.chatCompletions(config)
                                    val apiKey = decryptApiKey(config)
                                    imageDataUrl = "data:$mimeType;base64," +
                                        Base64.encodeToString(snapshot.imageBytes, Base64.NO_WRAP)
                                    shouldRecordCost = true
                                    requestWithRetries(
                                        metadata = metadata,
                                        config = config,
                                        endpoint = endpoint,
                                        apiKey = apiKey,
                                        imageDataUrl = imageDataUrl!!,
                                        startedAt = startedAt
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: AiChatException) {
                failedResult(
                    metadata,
                    AiVisionFailureCode.AI_CONFIG_INVALID,
                    0,
                    null,
                    costConfig?.provider,
                    costConfig?.model,
                    startedAt
                )
            } catch (_: SchemaException) {
                failedResult(
                    metadata,
                    AiVisionFailureCode.JSON_SCHEMA_INVALID,
                    0,
                    null,
                    costConfig?.provider,
                    costConfig?.model,
                    startedAt
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedResult(
                    metadata,
                    AiVisionFailureCode.REQUEST_FAILED,
                    0,
                    null,
                    costConfig?.provider,
                    costConfig?.model,
                    startedAt
                )
            }
            result = finalResult
            return finalResult
        } finally {
            imageDataUrl = null
            activeRequestKeys.remove(requestKey)
            cancelledRequestKeys.remove(requestKey)
            snapshot.release()
            val recordConfig = costConfig
            if (shouldRecordCost && recordConfig != null) {
                val completed = result as? AiVisionResult.Succeeded
                withContext(NonCancellable) {
                    recordCostSafely(
                        config = recordConfig,
                        success = completed != null,
                        tokensIn = completed?.tokensIn,
                        tokensOut = completed?.tokensOut,
                        requestType = TAG_REQUEST_TYPE
                    )
                }
            }
        }
    }

    data class PaymentResultVisionFields(
        val amountMinor: Long,
        val merchant: String?,
        val orderId: String?,
        val occurredAt: String?,
        /** PERSONAL_TRANSFER（向好友转账）或 MERCHANT_PAYMENT（默认消费）。 */
        val transferType: String = TRANSFER_TYPE_MERCHANT_PAYMENT
    )

    sealed class PaymentResultVisionResult {
        data class Succeeded(
            val fields: PaymentResultVisionFields,
            val requestId: String,
            val provider: String,
            val model: String,
            val attempts: Int,
            val tokensIn: Int?,
            val tokensOut: Int?,
            val latencyMillis: Long
        ) : PaymentResultVisionResult()

        data class Failed(
            val code: AiVisionFailureCode,
            val attempts: Int,
            val requestId: String?,
            val provider: String?,
            val model: String?,
            val lastFailureCode: AiVisionFailureCode? = null,
            val httpStatus: Int? = null,
            val retryable: Boolean = false,
            val latencyMillis: Long
        ) : PaymentResultVisionResult()
    }

    private data class PaymentAttemptResult(
        val fields: PaymentResultVisionFields?,
        val code: AiVisionFailureCode?,
        val httpStatus: Int? = null,
        val tokensIn: Int? = null,
        val tokensOut: Int? = null
    )

    suspend fun recognizePaymentResult(
        imageBytes: ByteArray,
        sourceEventKey: String
    ): PaymentResultVisionResult {
        val startedAt = System.currentTimeMillis()
        val requestKey = UUID.randomUUID().toString()
        activeRequestKeys.add(requestKey)
        var imageDataUrl: String? = null
        var costConfig: AiProviderConfigEntity? = null
        var shouldRecordCost = false
        var finalResult: PaymentResultVisionResult? = null

        try {
            val outcome = try {
                when {
                    closed -> paymentFailed(AiVisionFailureCode.CLIENT_CLOSED, 0, null, null, null, startedAt)
                    imageBytes.isEmpty() -> paymentFailed(AiVisionFailureCode.SNAPSHOT_INVALID, 0, null, null, null, startedAt)
                    imageBytes.size > MAX_IMAGE_BYTES -> paymentFailed(AiVisionFailureCode.IMAGE_TOO_LARGE, 0, null, null, null, startedAt)
                    else -> {
                        val mimeType = imageMimeType(imageBytes) ?: return paymentFailed(
                            AiVisionFailureCode.SNAPSHOT_INVALID,
                            0,
                            null,
                            null,
                            null,
                            startedAt
                        )
                        val config = resolveProvider() ?: return paymentFailed(
                            AiVisionFailureCode.AI_CONFIG_MISSING,
                            0,
                            null,
                            null,
                            null,
                            startedAt
                        )
                        if (!config.supportsVision) {
                            return paymentFailed(
                                AiVisionFailureCode.AI_VISION_UNSUPPORTED,
                                0,
                                null,
                                config.provider,
                                config.model,
                                startedAt
                            )
                        }
                        costConfig = config
                        val endpoint = AiEndpointResolver.chatCompletions(config)
                        val apiKey = decryptApiKey(config)
                        imageDataUrl = "data:$mimeType;base64," +
                            Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        shouldRecordCost = true
                        paymentRequestWithRetries(
                            requestKey = requestKey,
                            sourceEventKey = sourceEventKey,
                            config = config,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            imageDataUrl = imageDataUrl!!,
                            startedAt = startedAt
                        )
                    }
                }
            } catch (_: AiChatException) {
                paymentFailed(AiVisionFailureCode.AI_CONFIG_INVALID, 0, null, costConfig?.provider, costConfig?.model, startedAt)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                paymentFailed(AiVisionFailureCode.REQUEST_FAILED, 0, null, costConfig?.provider, costConfig?.model, startedAt)
            }
            finalResult = outcome
            return outcome
        } finally {
            imageDataUrl = null
            activeRequestKeys.remove(requestKey)
            cancelledRequestKeys.remove(requestKey)
            imageBytes.fill(0)
            val config = costConfig
            if (shouldRecordCost && config != null) {
                val succeeded = finalResult as? PaymentResultVisionResult.Succeeded
                withContext(NonCancellable) {
                    recordCostSafely(
                        config = config,
                        success = succeeded != null,
                        tokensIn = succeeded?.tokensIn,
                        tokensOut = succeeded?.tokensOut,
                        requestType = "payment_result_vision"
                    )
                }
            }
        }
    }

    private suspend fun paymentRequestWithRetries(
        requestKey: String,
        sourceEventKey: String,
        config: AiProviderConfigEntity,
        endpoint: String,
        apiKey: String?,
        imageDataUrl: String,
        startedAt: Long
    ): PaymentResultVisionResult {
        var attempts = 0
        var lastFailure = PaymentAttemptResult(null, AiVisionFailureCode.REQUEST_FAILED)
        var lastRequestId: String? = null

        return try {
            withTimeout(TOTAL_TIMEOUT_MILLIS) {
                while (attempts < MAX_ATTEMPTS) {
                    attempts += 1
                    val requestId = UUID.randomUUID().toString()
                    lastRequestId = requestId
                    if (cancelledRequestKeys.contains(requestKey)) {
                        return@withTimeout paymentFailed(
                            AiVisionFailureCode.CANCELLED,
                            attempts,
                            requestId,
                            config.provider,
                            config.model,
                            startedAt
                        )
                    }

                    val outcome = try {
                        val request = buildPaymentRequest(
                            config = config,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            requestId = requestId,
                            sourceEventKey = sourceEventKey,
                            imageDataUrl = imageDataUrl
                        )
                        withTimeout(ATTEMPT_TIMEOUT_MILLIS) {
                            executePaymentAttempt(requestKey, request)
                        }
                    } catch (_: TimeoutCancellationException) {
                        PaymentAttemptResult(null, AiVisionFailureCode.TIMEOUT)
                    }

                    val fields = outcome.fields
                    if (fields != null) {
                        return@withTimeout PaymentResultVisionResult.Succeeded(
                            fields = fields,
                            requestId = requestId,
                            provider = config.provider,
                            model = config.model,
                            attempts = attempts,
                            tokensIn = outcome.tokensIn,
                            tokensOut = outcome.tokensOut,
                            latencyMillis = System.currentTimeMillis() - startedAt
                        )
                    }

                    val code = outcome.code ?: AiVisionFailureCode.PROTOCOL_ERROR
                    lastFailure = outcome
                    if (code == AiVisionFailureCode.CANCELLED) {
                        return@withTimeout paymentFailed(
                            code,
                            attempts,
                            requestId,
                            config.provider,
                            config.model,
                            startedAt
                        )
                    }
                    val retryable = code == AiVisionFailureCode.REQUEST_FAILED ||
                        code == AiVisionFailureCode.TIMEOUT ||
                        (code == AiVisionFailureCode.HTTP_ERROR &&
                            (outcome.httpStatus == 429 || (outcome.httpStatus ?: 0) >= 500))
                    if (!retryable || attempts >= MAX_ATTEMPTS) {
                        return@withTimeout paymentFailed(
                            if (retryable) AiVisionFailureCode.RETRY_EXHAUSTED else code,
                            attempts,
                            requestId,
                            config.provider,
                            config.model,
                            startedAt,
                            lastFailureCode = if (retryable) code else null,
                            httpStatus = outcome.httpStatus,
                            retryable = retryable
                        )
                    }
                    delay(RETRY_BACKOFF_MILLIS)
                }
                paymentFailed(
                    AiVisionFailureCode.RETRY_EXHAUSTED,
                    attempts,
                    lastRequestId,
                    config.provider,
                    config.model,
                    startedAt,
                    lastFailureCode = lastFailure.code,
                    httpStatus = lastFailure.httpStatus
                )
            }
        } catch (_: TimeoutCancellationException) {
            paymentFailed(
                AiVisionFailureCode.TIMEOUT,
                attempts,
                lastRequestId,
                config.provider,
                config.model,
                startedAt,
                lastFailureCode = lastFailure.code,
                httpStatus = lastFailure.httpStatus,
                retryable = true
            )
        }
    }

    private fun paymentFailed(
        code: AiVisionFailureCode,
        attempts: Int,
        requestId: String?,
        provider: String?,
        model: String?,
        startedAt: Long,
        lastFailureCode: AiVisionFailureCode? = null,
        httpStatus: Int? = null,
        retryable: Boolean = false
    ): PaymentResultVisionResult.Failed = PaymentResultVisionResult.Failed(
        code = code,
        attempts = attempts,
        requestId = requestId,
        provider = provider,
        model = model,
        lastFailureCode = lastFailureCode,
        httpStatus = httpStatus,
        retryable = retryable,
        latencyMillis = System.currentTimeMillis() - startedAt
    )

    private fun buildPaymentRequest(
        config: AiProviderConfigEntity,
        endpoint: String,
        apiKey: String?,
        requestId: String,
        sourceEventKey: String,
        imageDataUrl: String
    ): Request {
        val prompt = buildPaymentPrompt(requestId, sourceEventKey)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageDataUrl))
            )
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("stream", false)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))

        return Request.Builder()
            .url(endpoint)
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildPaymentPrompt(requestId: String, sourceEventKey: String): String = """
        你是 JotBill 的支付结果页识别器。只分析截图中的微信/支付宝支付成功页事实，不推断、不创建账单。
        只返回一个 JSON 对象，禁止 Markdown、解释文字和额外字段。
        promptVersion=payment-result-v2；requestId=$requestId；sourceEventKey=$sourceEventKey。
        必须返回字段：screenType、paymentStatus、amount、merchant、orderId、occurredAt、confidence、transferType。
        screenType 必须为 payment_result；paymentStatus 只能是 SUCCESS 或 FAILED；
        amount 必须是正数十进制字符串，最多两位小数；缺失的 merchant、orderId、occurredAt 使用 null，不要使用空字符串；
        occurredAt 存在时必须是 ISO-8601 时间字符串；confidence 必须是 0 到 1 的数字。
        transferType 判定：页面是向个人（好友/联系人）转账（出现“转账给/已转账给”及收款人姓名，且无商户名与订单号）时为 PERSONAL_TRANSFER，此时 merchant 填收款人姓名；其余（扫码/商户消费/电商支付）一律为 MERCHANT_PAYMENT。
        只把“支付成功/付款成功/支付完成/转账成功”旁边的实付金额作为 amount；原价、优惠金额、单号数字都不要作为 amount。
        重要：只把“支付应用当前展示的支付收银结果页”（页面顶部大标题即支付结果）判为 payment_result；若截图是账单详情页、交易列表、聊天消息等历史支付信息页面，screenType 返回 not_payment_result，其余字段按实际内容填写。
    """.trimIndent()

    private suspend fun executePaymentAttempt(
        requestKey: String,
        request: Request
    ): PaymentAttemptResult = suspendCancellableCoroutine { continuation ->
        if (closed || cancelledRequestKeys.contains(requestKey)) {
            runCatching { continuation.resume(PaymentAttemptResult(null, AiVisionFailureCode.CANCELLED)) }
            return@suspendCancellableCoroutine
        }
        val call = httpClient.newCall(request)
        activeCalls[requestKey] = call
        continuation.invokeOnCancellation {
            activeCalls.remove(requestKey, call)
            call.cancel()
        }
        if (closed || cancelledRequestKeys.contains(requestKey)) {
            activeCalls.remove(requestKey, call)
            call.cancel()
            runCatching { continuation.resume(PaymentAttemptResult(null, AiVisionFailureCode.CANCELLED)) }
            return@suspendCancellableCoroutine
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                activeCalls.remove(requestKey, call)
                if (!continuation.isActive) return
                val code = if (
                    call.isCanceled() || error is SocketTimeoutException ||
                        error.message.orEmpty().contains("timeout", ignoreCase = true)
                ) {
                    if (call.isCanceled()) {
                        AiVisionFailureCode.CANCELLED
                    } else {
                        AiVisionFailureCode.TIMEOUT
                    }
                } else {
                    AiVisionFailureCode.REQUEST_FAILED
                }
                runCatching { continuation.resume(PaymentAttemptResult(null, code)) }
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(requestKey, call)
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                val result = try {
                    response.use(::parsePaymentResponse)
                } catch (_: ResponseTooLargeException) {
                    PaymentAttemptResult(null, AiVisionFailureCode.RESPONSE_TOO_LARGE)
                } catch (_: SchemaException) {
                    PaymentAttemptResult(null, AiVisionFailureCode.JSON_SCHEMA_INVALID)
                } catch (_: JSONException) {
                    PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
                } catch (_: IOException) {
                    PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
                } catch (_: Exception) {
                    PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
                }
                if (continuation.isActive) {
                    runCatching { continuation.resume(result) }
                }
            }
        })
    }

    private fun parsePaymentResponse(response: Response): PaymentAttemptResult {
        if (!response.isSuccessful) {
            return PaymentAttemptResult(
                fields = null,
                code = AiVisionFailureCode.HTTP_ERROR,
                httpStatus = response.code
            )
        }
        val body = response.body ?: return PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
        val bodyText = readLimitedBody(body)
        val root = try {
            JSONObject(bodyText)
        } catch (_: JSONException) {
            return PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
        }
        val content = extractContent(root)
            ?: return PaymentAttemptResult(null, AiVisionFailureCode.PROTOCOL_ERROR)
        val payload = try {
            parsePaymentPayload(JSONObject(content))
        } catch (error: JSONException) {
            logSchemaFailure("payment_result", content, "payload_not_json:${error.message.orEmpty()}")
            return PaymentAttemptResult(null, AiVisionFailureCode.JSON_SCHEMA_INVALID)
        } catch (error: SchemaException) {
            logSchemaFailure("payment_result", content, error.message.orEmpty())
            return PaymentAttemptResult(null, AiVisionFailureCode.JSON_SCHEMA_INVALID)
        }
        val usage = root.optJSONObject("usage")?.let(::parseUsage)
            ?: ResponseUsage(tokensIn = null, tokensOut = null)
        return PaymentAttemptResult(payload, null, tokensIn = usage.tokensIn, tokensOut = usage.tokensOut)
    }

    private fun parsePaymentPayload(root: JSONObject): PaymentResultVisionFields {
        val screenType = requiredString(root, "screenType")
        if (screenType != "payment_result") {
            throw SchemaException("field=screenType, expected=payment_result, actual=$screenType")
        }
        val paymentStatus = requiredString(root, "paymentStatus")
        if (paymentStatus != "SUCCESS") {
            throw SchemaException("field=paymentStatus, expected=SUCCESS, actual=$paymentStatus")
        }
        val amountText = requiredString(root, "amount")
        if (!AMOUNT_PATTERN.matches(amountText)) {
            throw SchemaException("field=amount, expected=positive_decimal_with_at_most_2_fraction_digits")
        }
        val amountMinor = try {
            BigDecimal(amountText).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw SchemaException("field=amount, reason=minor_unit_conversion_failed")
        }
        if (amountMinor <= 0) throw SchemaException("field=amount, expected=positive")
        // 商户名同样只用于展示与匹配辅助，模型偶发返回结构化对象；
        // 扁平化提取而非整单作废，避免丢掉已识别出的金额。
        val merchant = descriptiveField(root, "merchant", MAX_MERCHANT_LENGTH, relaxedSchema = false)
        val orderId = requiredNullableString(root, "orderId", MAX_ORDER_ID_LENGTH)
        val occurredAt = requiredNullableString(root, "occurredAt", MAX_TIMESTAMP_LENGTH)?.also {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                throw SchemaException("field=occurredAt, expected=iso_8601_or_null")
            }
        }
        requiredNumber(root, "confidence")
        // transferType 宽松解析：未知或缺失一律按商户消费处理，不因模型
        // 漏返回新字段而整单失败（v1 输出兼容）。
        val transferType = root.optString(
            "transferType",
            TRANSFER_TYPE_MERCHANT_PAYMENT
        ).takeIf { it == TRANSFER_TYPE_PERSONAL } ?: TRANSFER_TYPE_MERCHANT_PAYMENT
        return PaymentResultVisionFields(
            amountMinor = amountMinor,
            merchant = merchant,
            orderId = orderId,
            occurredAt = occurredAt,
            transferType = transferType
        )
    }

    /** 取消指定请求；取消后仍由 recognize 的 finally 释放 Ready.snapshot。 */
    fun cancel(requestKey: CheckoutRequestKey): Boolean {
        val key = requestKey.value
        cancelledRequestKeys.add(key)
        return activeCalls.remove(key)?.let {
            it.cancel()
            true
        } ?: false
    }

    /** 关闭客户端并取消所有 HTTP 调用；已交付的 Ready 仍由对应 recognize finally 释放。 */
    fun close() {
        if (closed) return
        closed = true
        activeCalls.keys.forEach(cancelledRequestKeys::add)
        activeCalls.values.forEach(Call::cancel)
        activeCalls.clear()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun resolveProvider(): AiProviderConfigEntity? {
        val dao = database.aiProviderConfigDao()
        return dao.getDefault() ?: dao.getAll().firstOrNull()
    }

    private fun decryptApiKey(config: AiProviderConfigEntity): String? {
        val encrypted = config.apiKeyEncrypted?.trim().orEmpty()
        if (encrypted.isEmpty()) return null
        return secretStore.decryptSecret(encrypted).trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun requestWithRetries(
        metadata: AiVisionRequestMetadata,
        config: AiProviderConfigEntity,
        endpoint: String,
        apiKey: String?,
        imageDataUrl: String,
        startedAt: Long
    ): AiVisionResult {
        var attempts = 0
        var lastRequestId: String? = null
        var lastFailure = AttemptResult.Failure(AiVisionFailureCode.REQUEST_FAILED)

        return try {
            withTimeout(TOTAL_TIMEOUT_MILLIS) {
                while (attempts < MAX_ATTEMPTS) {
                    attempts += 1
                    val requestId = UUID.randomUUID().toString()
                    lastRequestId = requestId
                    if (cancelledRequestKeys.contains(metadata.requestKey.value)) {
                        return@withTimeout failedResult(
                            metadata,
                            AiVisionFailureCode.CANCELLED,
                            attempts,
                            requestId,
                            config.provider,
                            config.model,
                            startedAt
                        )
                    }

                    val relaxedSchema = metadata.intent == PurchaseIntent.USER_RETRY
                    val outcome = try {
                        val request = buildRequest(
                            config = config,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            requestId = requestId,
                            metadata = metadata,
                            imageDataUrl = imageDataUrl,
                            relaxedSchema = relaxedSchema
                        )
                        withTimeout(ATTEMPT_TIMEOUT_MILLIS) {
                            executeAttempt(metadata.requestKey.value, request, relaxedSchema)
                        }
                    } catch (_: TimeoutCancellationException) {
                        AttemptResult.Failure(AiVisionFailureCode.TIMEOUT)
                    }

                    when (outcome) {
                        is AttemptResult.Success -> {
                            return@withTimeout AiVisionResult.Succeeded(
                                metadata = metadata,
                                fields = outcome.fields,
                                requestId = requestId,
                                provider = config.provider,
                                model = config.model,
                                attempts = attempts,
                                tokensIn = outcome.tokensIn,
                                tokensOut = outcome.tokensOut,
                                latencyMillis = System.currentTimeMillis() - startedAt
                            )
                        }

                        is AttemptResult.Failure -> {
                            lastFailure = outcome
                            if (outcome.code == AiVisionFailureCode.CANCELLED) {
                                return@withTimeout failedResult(
                                    metadata,
                                    AiVisionFailureCode.CANCELLED,
                                    attempts,
                                    requestId,
                                    config.provider,
                                    config.model,
                                    startedAt
                                )
                            }
                            if (!isRetryable(outcome) || attempts >= MAX_ATTEMPTS) {
                                val exhausted = attempts >= MAX_ATTEMPTS && isRetryable(outcome)
                                return@withTimeout failedResult(
                                    metadata,
                                    if (exhausted) AiVisionFailureCode.RETRY_EXHAUSTED else outcome.code,
                                    attempts,
                                    requestId,
                                    config.provider,
                                    config.model,
                                    startedAt,
                                    lastFailureCode = if (exhausted) outcome.code else null,
                                    httpStatus = outcome.httpStatus,
                                    retryable = isRetryable(outcome)
                                )
                            }
                            delay(RETRY_BACKOFF_MILLIS)
                        }
                    }
                }
                failedResult(
                    metadata,
                    AiVisionFailureCode.RETRY_EXHAUSTED,
                    attempts,
                    lastRequestId,
                    config.provider,
                    config.model,
                    startedAt,
                    lastFailureCode = lastFailure.code,
                    httpStatus = lastFailure.httpStatus
                )
            }
        } catch (_: TimeoutCancellationException) {
            failedResult(
                metadata,
                AiVisionFailureCode.TIMEOUT,
                attempts,
                lastRequestId,
                config.provider,
                config.model,
                startedAt,
                lastFailureCode = lastFailure.code,
                httpStatus = lastFailure.httpStatus,
                retryable = true
            )
        }
    }

    private fun isRetryable(failure: AttemptResult.Failure): Boolean = when (failure.code) {
        AiVisionFailureCode.REQUEST_FAILED,
        AiVisionFailureCode.TIMEOUT -> true
        AiVisionFailureCode.HTTP_ERROR -> failure.httpStatus == 429 ||
            failure.httpStatus?.let { it >= 500 } == true
        else -> false
    }

    private fun buildRequest(
        config: AiProviderConfigEntity,
        endpoint: String,
        apiKey: String?,
        requestId: String,
        metadata: AiVisionRequestMetadata,
        imageDataUrl: String,
        relaxedSchema: Boolean
    ): Request {
        val prompt = buildPrompt(requestId, metadata, relaxedSchema)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageDataUrl))
            )
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("stream", false)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))

        return Request.Builder()
            .url(endpoint)
            .header("Content-Type", JSON_MEDIA_TYPE_VALUE)
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildPrompt(
        requestId: String,
        metadata: AiVisionRequestMetadata,
        relaxedSchema: Boolean
    ): String = if (relaxedSchema) """
        你是 JotBill 的购物结算页视觉识别器。用户刚在购物 App 手动点击截图，图片可能来自确认订单页。
        只返回一个 JSON 对象，禁止 Markdown、解释文字和额外字段。
        promptVersion=checkout-manual-v1；requestId=$requestId；sessionId=${metadata.sessionId}。
        必须返回字段：screenType、amount；screenType 可以是 checkout 或 order_confirm。
        推荐返回：productDescription、orderId，缺失时使用 null。
        productDescription 必须是单行纯字符串，禁止返回对象或数组。
        platform 与 merchant 已由客户端根据购物应用包名确定，可以直接填 null。
        可选返回：amountSemantic、currency、occurredAt、confidence、fieldConfidence。
        amount 必须是正数十进制字符串或数字，最多两位小数；只取最终需要支付的金额，
        原价、单价、优惠金额、运费、积分抵扣都不要作为 amount。
    """.trimIndent() else """
        你是 JotBill 的结算页视觉识别器。只分析图片中的结算页事实，不推断支付成功，不创建账单。
        只返回一个 JSON 对象，禁止 Markdown、解释文字和额外字段。
        promptVersion=$PROMPT_VERSION；requestId=$requestId；sessionId=${metadata.sessionId}。
        必须返回字段：screenType、platform、merchant、productDescription、amount、amountSemantic、currency、
        orderId、paymentMethod、occurredAt、confidence、fieldConfidence。
        screenType 必须为 checkout；缺失的文本字段使用 null，不要使用空字符串。
        productDescription 必须是单行纯字符串，禁止返回对象或数组；
        商品有多个规格或多件时，在同一个字符串里用空格连接。
        platform 与 merchant 已由客户端根据购物应用包名确定，你返回的值不会被采用，
        可以直接填 null；请把精力放在 amount 与 productDescription 的准确性上。
        amount 必须是正数十进制字符串，最多两位小数；currency 必须为 CNY。
        amountSemantic 只能是 FINAL_PAYABLE、ACTUAL_PAYABLE、DISCOUNTED_TOTAL 或 ORDER_TOTAL；
        原价、单价、优惠金额、运费、积分抵扣和未知金额语义都不能作为 amount，遇到这些情况返回不确定且不要猜测。
        occurredAt 必须是 ISO-8601 时间字符串或 null。confidence 和 fieldConfidence 中的值必须是 0 到 1 的数字。
        fieldConfidence 建议包含 platform、merchant、productDescription、amount、orderId 这五个字段；
        无法评估时可以省略对应键或整体省略 fieldConfidence，不要编造数值。
    """.trimIndent()

    private suspend fun executeAttempt(
        requestKey: String,
        request: Request,
        relaxedSchema: Boolean
    ): AttemptResult =
        suspendCancellableCoroutine { continuation ->
            if (closed || cancelledRequestKeys.contains(requestKey)) {
                runCatching {
                    continuation.resume(AttemptResult.Failure(AiVisionFailureCode.CANCELLED))
                }
                return@suspendCancellableCoroutine
            }
            val call = httpClient.newCall(request)
            activeCalls[requestKey] = call
            continuation.invokeOnCancellation {
                activeCalls.remove(requestKey, call)
                call.cancel()
            }
            if (closed || cancelledRequestKeys.contains(requestKey)) {
                activeCalls.remove(requestKey, call)
                call.cancel()
                runCatching {
                    continuation.resume(AttemptResult.Failure(AiVisionFailureCode.CANCELLED))
                }
                return@suspendCancellableCoroutine
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    activeCalls.remove(requestKey, call)
                    if (!continuation.isActive) return
                    val code = if (
                        call.isCanceled() || error is SocketTimeoutException ||
                            error.message.orEmpty().contains("timeout", ignoreCase = true)
                    ) {
                        if (call.isCanceled()) {
                            AiVisionFailureCode.CANCELLED
                        } else {
                            AiVisionFailureCode.TIMEOUT
                        }
                    } else {
                        AiVisionFailureCode.REQUEST_FAILED
                    }
                    runCatching { continuation.resume(AttemptResult.Failure(code)) }
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCalls.remove(requestKey, call)
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        response.use { parseResponse(it, relaxedSchema) }
                    } catch (_: ResponseTooLargeException) {
                        AttemptResult.Failure(AiVisionFailureCode.RESPONSE_TOO_LARGE)
                    } catch (_: SchemaException) {
                        AttemptResult.Failure(AiVisionFailureCode.JSON_SCHEMA_INVALID)
                    } catch (_: JSONException) {
                        AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
                    } catch (_: IOException) {
                        AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
                    } catch (_: Exception) {
                        AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
                    }
                    if (continuation.isActive) {
                        runCatching { continuation.resume(result) }
                    }
                }
            })
        }

    private fun parseResponse(response: Response, relaxedSchema: Boolean): AttemptResult {
        if (!response.isSuccessful) {
            return AttemptResult.Failure(
                code = AiVisionFailureCode.HTTP_ERROR,
                httpStatus = response.code
            )
        }
        val body = response.body ?: return AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ResponseTooLargeException()
        }
        val bodyText = readLimitedBody(body)
        val root = try {
            JSONObject(bodyText)
        } catch (_: JSONException) {
            return AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
        }
        val content = extractContent(root)
            ?: return AttemptResult.Failure(AiVisionFailureCode.PROTOCOL_ERROR)
        val payload = try {
            parseVisionPayload(JSONObject(content), relaxedSchema)
        } catch (error: JSONException) {
            logSchemaFailure("checkout", content, "payload_not_json:${error.message.orEmpty()}")
            return AttemptResult.Failure(AiVisionFailureCode.JSON_SCHEMA_INVALID)
        } catch (error: SchemaException) {
            logSchemaFailure("checkout", content, error.message.orEmpty())
            return AttemptResult.Failure(AiVisionFailureCode.JSON_SCHEMA_INVALID)
        }
        val usage = root.optJSONObject("usage")?.let(::parseUsage)
            ?: ResponseUsage(tokensIn = null, tokensOut = null)
        return AttemptResult.Success(payload, usage.tokensIn, usage.tokensOut)
    }

    private fun readLimitedBody(body: okhttp3.ResponseBody): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        try {
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            return try {
                String(bytes, StandardCharsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        } finally {
            buffer.fill(0)
            output.reset()
        }
    }

    private fun extractContent(root: JSONObject): String? {
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val message = choice.optJSONObject("message") ?: return null
        val content = message.opt("content") ?: return null
        val raw = when (content) {
            is String -> content.trim().takeIf { it.isNotEmpty() }
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val text = content.optJSONObject(index)?.optString("text")?.trim().orEmpty()
                    if (text.isNotEmpty()) append(text)
                }
            }.takeIf { it.isNotEmpty() }
            else -> null
        } ?: return null
        // 模型偶发用 Markdown 代码围栏包裹 JSON（```json ... ```），先剥离再交给
        // JSONObject 解析，避免整段被误判 JSON_SCHEMA_INVALID。
        return CODE_FENCE_PATTERN.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: raw
    }

    /**
     * 记录 schema 校验失败的模型原始输出（截断 + 长数字串脱敏），便于定位
     * 是提示词漂移、字段命名变化还是围栏/格式问题；不记录 API Key 等请求侧信息。
     */
    private fun logSchemaFailure(stage: String, content: String, reason: String) {
        val sanitized = content
            .replace(LONG_DIGITS_PATTERN, "[digits]")
            .take(200)
        val keys = runCatching {
            JSONObject(content).let { payload ->
                buildSet {
                    val iterator = payload.keys()
                    while (iterator.hasNext()) add(iterator.next())
                }.sorted().joinToString(",")
            }
        }.getOrDefault("unavailable")
        android.util.Log.w(
            "CheckoutAiVisionClient",
            "AI 输出未通过 schema 校验: stage=$stage, reason=$reason, keys=[$keys], content=$sanitized"
        )
    }

    private fun parseVisionPayload(
        root: JSONObject,
        relaxedSchema: Boolean
    ): AiVisionFields {
        if (!relaxedSchema) {
            // 只强制核心字段齐备：多返回的额外键与缺失的非核心键都不应否决整份
            // 结果。此前用 requireExactKeys 精确比对全部 12 个键，模型少返回一个
            // paymentMethod 或多返回一个说明字段就会使已正确识别的金额一起作废。
            requireRequiredKeys(root, REQUIRED_RESULT_KEYS, RESULT_KEYS, "root")
        }
        val screenType = requiredString(root, "screenType")
        val screenTypeAccepted = if (relaxedSchema) {
            screenType == "checkout" || screenType == "order_confirm"
        } else {
            screenType == "checkout"
        }
        if (!screenTypeAccepted) {
            throw SchemaException("field=screenType, expected=checkout, actual=$screenType")
        }
        val platform = nullableString(root, "platform", MAX_PLATFORM_LENGTH, relaxedSchema)
        val merchant = descriptiveField(root, "merchant", MAX_MERCHANT_LENGTH, relaxedSchema)
        // productDescription 是纯展示字段，模型偶发返回结构化对象或数组。
        // 它不参与金额匹配与自动入账判定，因此类型不符时扁平化提取文本即可，
        // 不能因为它而丢弃整份已识别出金额与商户的结果。
        val productDescription = descriptiveField(
            root,
            "productDescription",
            MAX_PRODUCT_LENGTH,
            relaxedSchema
        )
        val amountValue = root.opt("amount")
            ?: throw SchemaException("field=amount, reason=missing")
        val amountText = when (amountValue) {
            is String -> amountValue.trim()
            is Number -> amountValue.toString()
            else -> throw SchemaException("field=amount, expected=string_or_number, actual=${valueType(amountValue)}")
        }
        if (!AMOUNT_PATTERN.matches(amountText)) {
            throw SchemaException("field=amount, expected=positive_decimal_with_at_most_2_fraction_digits")
        }
        val amountMinor = try {
            BigDecimal(amountText).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw SchemaException("field=amount, reason=minor_unit_conversion_failed")
        }
        if (amountMinor <= 0) throw SchemaException("field=amount, expected=positive")
        // amountSemantic 决定金额语义，必须落在白名单内；但"未返回该键"与
        // "返回了未知语义"性质不同：前者按最终应付处理即可，后者说明模型对
        // 金额含义没有把握，必须拒收以免把原价或优惠额记成实付。
        val amountSemantic = if (!root.has("amountSemantic") || root.isNull("amountSemantic")) {
            "FINAL_PAYABLE"
        } else if (relaxedSchema) {
            root.optString("amountSemantic", "FINAL_PAYABLE")
                .takeIf { it in SAFE_AMOUNT_SEMANTICS }
                ?: "FINAL_PAYABLE"
        } else {
            requiredString(root, "amountSemantic").also {
                if (it !in SAFE_AMOUNT_SEMANTICS) {
                    throw SchemaException("field=amountSemantic, expected=known_semantic, actual=$it")
                }
            }
        }
        // currency 缺失按 CNY 处理；显式返回非 CNY 时仍拒收，避免币种错记。
        val currency = if (!root.has("currency") || root.isNull("currency")) {
            "CNY"
        } else if (relaxedSchema) {
            root.optString("currency", "CNY").takeIf { it == "CNY" } ?: "CNY"
        } else {
            val currency = requiredString(root, "currency")
            if (currency != "CNY") {
                throw SchemaException("field=currency, expected=CNY, actual=$currency")
            }
            "CNY"
        }
        val orderId = nullableString(root, "orderId", MAX_ORDER_ID_LENGTH, relaxedSchema)
        val paymentMethod = nullableString(
            root,
            "paymentMethod",
            MAX_PAYMENT_METHOD_LENGTH,
            relaxedSchema
        )
        val occurredAt = nullableString(
            root,
            "occurredAt",
            MAX_TIMESTAMP_LENGTH,
            relaxedSchema
        )?.also {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                throw SchemaException("field=occurredAt, expected=iso_8601_or_null")
            }
        }
        // confidence 与 fieldConfidence 都只作诊断参考，不参与任何入账判定：
        // 缺失或非法时按 0 与空表处理，不再否决整份结果。
        val confidence = optionalNumber(root, "confidence")?.takeIf { it in 0.0..1.0 } ?: 0.0
        val fieldConfidence = parseFieldConfidence(root.optJSONObject("fieldConfidence"))
        return AiVisionFields(
            screenType = screenType,
            platform = platform,
            merchant = merchant,
            productDescription = productDescription,
            amountMinor = amountMinor,
            amountSemantic = amountSemantic,
            currency = "CNY",
            orderId = orderId,
            paymentMethod = paymentMethod,
            occurredAt = occurredAt,
            confidence = confidence,
            fieldConfidence = fieldConfidence
        )
    }

    /**
     * 解析各字段置信度，缺项与非法值一律忽略而不否决整份结果。
     *
     * fieldConfidence 是诊断用的辅助信息，不参与金额校验、匹配与自动入账判定。
     * 此前要求精确包含 platform/merchant/productDescription/amount/orderId
     * 五个键，模型少返回任意一个（例如 orderId 为 null 时省略其置信度）就会
     * 使整份已正确识别的结果作废，真机上表现为 JSON_SCHEMA_INVALID 且会话落
     * FAILED。这里改为只收集实际提供且落在 0..1 区间内的数值。
     *
     * @param source fieldConfidence 对象；为 null 时返回空表。
     * @return 已知字段的置信度映射，可能为空。
     */
    private fun parseFieldConfidence(source: JSONObject?): Map<String, Double> {
        if (source == null) {
            return emptyMap()
        }
        val parsed = extractFieldConfidence(source, FIELD_CONFIDENCE_KEYS)
        val missing = FIELD_CONFIDENCE_KEYS - parsed.keys
        if (missing.isNotEmpty()) {
            android.util.Log.i(
                "CheckoutAiVisionClient",
                "fieldConfidence 缺少可用置信度，按缺省处理: missing=[${missing.sorted().joinToString(",")}]"
            )
        }
        return parsed
    }

    private fun parseUsage(root: JSONObject): ResponseUsage = ResponseUsage(
        tokensIn = optionalPositiveInt(root, "prompt_tokens"),
        tokensOut = optionalPositiveInt(root, "completion_tokens")
    )

    private fun optionalPositiveInt(root: JSONObject, key: String): Int? {
        if (!root.has(key) || root.isNull(key)) return null
        val value = root.get(key) as? Number ?: return null
        return value.toLong().takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }

    private fun requiredString(root: JSONObject, key: String): String {
        if (!root.has(key)) throw SchemaException("field=$key, reason=missing")
        if (root.isNull(key)) throw SchemaException("field=$key, expected=non_null_string, actual=null")
        val rawValue = root.get(key)
        val value = rawValue as? String
            ?: throw SchemaException("field=$key, expected=string, actual=${valueType(rawValue)}")
        return value.trim().takeIf { it.isNotEmpty() }
            ?: throw SchemaException("field=$key, expected=non_blank_string")
    }

    private fun nullableString(
        root: JSONObject,
        key: String,
        maxLength: Int,
        relaxedSchema: Boolean
    ): String? {
        // 非核心可空字段缺失时按 null 处理：模型少返回一个 platform 或
        // paymentMethod 不应使整份已识别结果作废。字段存在但类型非法时，
        // 严格模式仍报错以暴露真实协议问题。
        if (!root.has(key) || root.isNull(key)) {
            return null
        }
        if (!relaxedSchema) {
            return requiredNullableString(root, key, maxLength)
        }
        val value = root.get(key) as? String ?: return null
        return value.trim().takeIf { it.isNotEmpty() && it.length <= maxLength }
    }

    /**
     * 读取描述型字段（商户名、商品描述）并容忍非字符串结构。
     *
     * 与 [nullableString] 的区别：严格模式下遇到对象或数组不再抛
     * [SchemaException]，而是扁平化提取可读文本。这类字段只用于展示，
     * 不参与金额校验与自动入账判定，不应因格式差异使整份结果作废。
     * 字段缺失仍按协议要求在严格模式下报错。
     *
     * @param root AI 返回的 JSON 根对象。
     * @param key 字段名。
     * @param maxLength 最大长度，超出即截断而非报错。
     * @param relaxedSchema 是否为宽松模式（手动截图路径）。
     * @return 归一化文本；无可用内容时返回 null。
     */
    private fun descriptiveField(
        root: JSONObject,
        key: String,
        maxLength: Int,
        relaxedSchema: Boolean
    ): String? {
        if (!root.has(key)) {
            // 描述字段缺失一律按 null 处理，不区分宽松与严格模式：它只用于展示。
            return null
        }
        if (root.isNull(key)) {
            return null
        }
        val rawValue = root.get(key)
        val flattened = flattenDescriptiveValue(rawValue, maxLength)
        if (flattened == null && rawValue !is String) {
            android.util.Log.i(
                "CheckoutAiVisionClient",
                "描述字段类型非字符串且无法提取文本，按 null 处理: " +
                    "field=$key, actual=${valueType(rawValue)}"
            )
        }
        return flattened
    }

    private fun optionalNumber(root: JSONObject, key: String): Double? {
        if (!root.has(key) || root.isNull(key)) return null
        return (root.get(key) as? Number)?.toDouble()
    }

    private fun requiredNullableString(root: JSONObject, key: String, maxLength: Int): String? {
        if (!root.has(key)) throw SchemaException("field=$key, reason=missing")
        if (root.isNull(key)) return null
        val rawValue = root.get(key)
        val value = rawValue as? String
            ?: throw SchemaException("field=$key, expected=string_or_null, actual=${valueType(rawValue)}")
        val normalized = value.trim()
        if (normalized.isEmpty()) throw SchemaException("field=$key, expected=non_blank_string_or_null")
        if (normalized.length > maxLength) {
            throw SchemaException("field=$key, reason=length_exceeds_$maxLength")
        }
        return normalized
    }

    private fun requiredNumber(root: JSONObject, key: String): Double {
        if (!root.has(key)) throw SchemaException("field=$key, reason=missing")
        if (root.isNull(key)) throw SchemaException("field=$key, expected=number_0_to_1, actual=null")
        val rawValue = root.get(key)
        val value = (rawValue as? Number)?.toDouble()
            ?: throw SchemaException("field=$key, expected=number_0_to_1, actual=${valueType(rawValue)}")
        if (!value.isFinite() || value !in 0.0..1.0) {
            throw SchemaException("field=$key, expected=number_0_to_1")
        }
        return value
    }

    /**
     * 校验核心键齐备，并对额外键与缺失的非核心键只做诊断记录。
     *
     * 与 [requireExactKeys] 的区别：不要求键集合完全相等。模型输出的键集合
     * 天然存在波动（少返回 paymentMethod、多返回说明性字段），这类差异不应
     * 使已正确识别的金额与商品信息整份作废。
     *
     * @param root 待校验对象。
     * @param required 必须存在且非空的键；缺失即抛 [SchemaException]。
     * @param known 协议已知键，用于识别额外键并记录诊断日志。
     * @param scope 诊断用作用域名称。
     */
    private fun requireRequiredKeys(
        root: JSONObject,
        required: Set<String>,
        known: Set<String>,
        scope: String
    ) {
        val actual = buildSet {
            val keys = root.keys()
            while (keys.hasNext()) add(keys.next())
        }
        val missingRequired = required - actual
        if (missingRequired.isNotEmpty()) {
            throw SchemaException(
                "scope=$scope, missingRequired=[${missingRequired.sorted().joinToString(",")}]"
            )
        }
        val unexpected = actual - known
        val missingOptional = known - actual
        if (unexpected.isNotEmpty() || missingOptional.isNotEmpty()) {
            android.util.Log.i(
                "CheckoutAiVisionClient",
                "AI 输出键集合与协议存在差异但核心字段齐备: scope=$scope, " +
                    "unexpected=[${unexpected.sorted().joinToString(",")}], " +
                    "missingOptional=[${missingOptional.sorted().joinToString(",")}]"
            )
        }
    }

    private fun requireExactKeys(root: JSONObject, allowed: Set<String>, scope: String) {
        val actual = buildSet {
            val keys = root.keys()
            while (keys.hasNext()) add(keys.next())
        }
        if (actual != allowed) {
            val missing = (allowed - actual).sorted().joinToString(",")
            val unexpected = (actual - allowed).sorted().joinToString(",")
            throw SchemaException("scope=$scope, missing=[$missing], unexpected=[$unexpected]")
        }
    }

    private fun valueType(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        else -> value::class.java.simpleName
    }

    private fun imageMimeType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            )
        ) -> "image/png"

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> null
    }

    private fun metadataOf(ready: CheckoutAiTriggerResult.Ready): AiVisionRequestMetadata {
        val snapshot = ready.snapshot
        return AiVisionRequestMetadata(
            requestKey = ready.requestKey,
            intent = ready.intent,
            sessionId = snapshot.sessionId,
            snapshotId = snapshot.snapshotId,
            slotId = snapshot.slotId,
            shoppingPackage = snapshot.shoppingPackage,
            pageFingerprint = snapshot.pageFingerprint,
            candidateRevision = snapshot.candidateRevision,
            capturedAt = snapshot.capturedAt,
            snapshotExpiresAt = snapshot.expiresAt
        )
    }

    private fun failedResult(
        metadata: AiVisionRequestMetadata,
        code: AiVisionFailureCode,
        attempts: Int,
        requestId: String?,
        provider: String?,
        model: String?,
        startedAt: Long,
        lastFailureCode: AiVisionFailureCode? = null,
        httpStatus: Int? = null,
        retryable: Boolean = false
    ): AiVisionResult.Failed = AiVisionResult.Failed(
        metadata = metadata,
        code = code,
        attempts = attempts,
        requestId = requestId,
        provider = provider,
        model = model,
        lastFailureCode = lastFailureCode,
        httpStatus = httpStatus,
        retryable = retryable,
        latencyMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    )

    private suspend fun recordCostSafely(
        config: AiProviderConfigEntity,
        success: Boolean,
        tokensIn: Int?,
        tokensOut: Int?,
        requestType: String
    ) {
        runCatching {
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
    }
}
