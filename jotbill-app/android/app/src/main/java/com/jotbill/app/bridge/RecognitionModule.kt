package com.jotbill.app.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.recognition.PurchaseIntentCoordinationResult
import com.jotbill.app.recognition.PurchaseIntentResultCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 识别链路桥接层。
 *
 * 仅暴露会话、候选、证据和重试入口的只读查询与有限操作，所有图片或敏感字段都不离开原生层。
 * 重试入口复用无障碍服务的 USER_RETRY 通路，桥接层不直接接触截图或 AI 客户端。
 */
class RecognitionModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    companion object {
        private const val MODULE_NAME = "Recognition"
        private const val RETRY_TIMEOUT_MILLIS = 30_000L
        private val TERMINAL_SESSION_STATUSES = setOf(
            "COMPLETED",
            "DISMISSED",
            "CANCELLED",
            "FAILED",
            "EXPIRED"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database: JotBillDatabase by lazy {
        JotBillDatabaseProvider.get(reactContext)
    }

    override fun getName(): String = MODULE_NAME

    /** 查询结算会话列表；可选 status 过滤，按 createdAt 倒序返回。 */
    @ReactMethod
    fun getCheckoutSessions(filterJson: String?, promise: Promise) = launch(promise) {
        val filter = parseJson(filterJson)
        val statuses = parseStatusFilter(filter)
        val now = System.currentTimeMillis()
        val sessions = if (statuses.isNullOrEmpty()) {
            database.checkoutSessionDao().getActiveSessions(now)
        } else {
            database.checkoutSessionDao().getByStatuses(statuses)
        }
        JSONArray().also { array ->
            sessions.forEach { array.put(BridgeDtoMapper.session(it)) }
        }.toString()
    }

    /** 查询单个结算会话；不存在时返回 NOT_FOUND。 */
    @ReactMethod
    fun getCheckoutSession(sessionId: String, promise: Promise) = launch(promise) {
        val session = database.checkoutSessionDao().getById(requireId(sessionId, "session"))
            ?: throw BridgeException("NOT_FOUND", "Checkout session $sessionId was not found")
        BridgeDtoMapper.session(session).toString()
    }

    /** 查询交易候选；不存在时返回 NOT_FOUND。 */
    @ReactMethod
    fun getTransactionCandidate(candidateId: String, promise: Promise) = launch(promise) {
        val candidate = database.transactionCandidateDao()
            .getById(requireId(candidateId, "candidate"))
            ?: throw BridgeException("NOT_FOUND", "Transaction candidate $candidateId was not found")
        BridgeDtoMapper.candidate(candidate).toString()
    }

    /** 查询会话下的支付证据列表，按 capturedAt 升序返回。 */
    @ReactMethod
    fun getPaymentEvidence(sessionId: String, promise: Promise) = launch(promise) {
        val id = requireId(sessionId, "session")
        JSONArray().also { array ->
            database.paymentEvidenceDao().getBySession(id)
                .forEach { array.put(BridgeDtoMapper.evidence(it)) }
        }.toString()
    }

    /**
     * 触发候选对应的 AI 识别重试。
     *
     * 通过候选找到关联会话的页面指纹，再交给无障碍服务的 USER_RETRY 通路。
     * 服务未连接、会话已过期或页面已失效时，回调会返回 Rejected，桥接层转换为对应错误码。
     */
    @ReactMethod
    fun retryAiRecognition(candidateId: String, promise: Promise) = launch(promise) {
        val id = requireId(candidateId, "candidate")
        val candidate = database.transactionCandidateDao().getById(id)
            ?: throw BridgeException("NOT_FOUND", "Transaction candidate $id was not found")
        val session = database.checkoutSessionDao().getById(candidate.sessionId)
            ?: throw BridgeException("NOT_FOUND", "Session ${candidate.sessionId} was not found")
        if (session.status in TERMINAL_SESSION_STATUSES) {
            throw BridgeException("SESSION_EXPIRED", "Session ${session.id} has reached terminal state")
        }
        val pageFingerprint = session.pageFingerprint
            ?: throw BridgeException(
                "SESSION_EXPIRED",
                "Session ${session.id} has no page fingerprint to retry"
            )

        val result = withTimeoutOrNull(RETRY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<PurchaseIntentCoordinationResult> { cont ->
                val callback = PurchaseIntentResultCallback { value ->
                    if (cont.isActive) {
                        cont.resume(value)
                    }
                }
                CheckoutAccessibilityService.requestUserRetry(pageFingerprint, callback)
                cont.invokeOnCancellation {
                    // 协程取消时不主动中断无障碍服务流程，回调可能仍会触发但结果已被丢弃。
                }
            }
        } ?: throw BridgeException(
            "AI_TIMEOUT",
            "Retry for candidate $candidateId timed out after $RETRY_TIMEOUT_MILLIS ms"
        )

        when (result) {
            is PurchaseIntentCoordinationResult.AiDispatched,
            is PurchaseIntentCoordinationResult.Accepted -> null

            is PurchaseIntentCoordinationResult.ReleasedWithoutConsumer ->
                throw BridgeException(
                    "SERVICE_UNAVAILABLE",
                    "Retry dispatcher unavailable: ${result.reasonCode}"
                )

            is PurchaseIntentCoordinationResult.Rejected -> {
                val code = mapRejectionCode(result.reasonCode)
                throw BridgeException(code, "Retry rejected: ${result.reasonCode}")
            }
        }
    }

    /** 报告识别链路当前状态：活跃会话数、待确认数、AI 是否在识别中。 */
    @ReactMethod
    fun getRecognitionStatus(promise: Promise) = launch(promise) {
        val now = System.currentTimeMillis()
        val activeSessions = database.checkoutSessionDao().getActiveSessions(now).size
        val aiRequestingCount = database.checkoutSessionDao().countAiRequesting(now)
        val pendingCount = database.pendingTransactionDao().count()
        JSONObject().apply {
            put("activeSessions", activeSessions)
            put("pendingCount", pendingCount)
            put("aiRequesting", aiRequestingCount > 0)
        }.toString()
    }

    /** React Native 新版事件发射器要求模块提供这两个订阅计数方法。 */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun parseStatusFilter(filter: JSONObject): List<String>? {
        if (!filter.has("status")) return null
        val array = filter.optJSONArray("status") ?: return null
        val statuses = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                statuses.add(value)
            }
        }
        return statuses.takeIf { it.isNotEmpty() }
    }

    private fun requireId(id: String?, resource: String): String {
        val normalized = id?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw BridgeException("INVALID_ARGUMENT", "$resource id must not be empty")
        }
        return normalized
    }

    private fun parseJson(json: String?): JSONObject {
        if (json.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(json)
        } catch (error: Exception) {
            throw BridgeException("INVALID_ARGUMENT", "Invalid JSON input", error)
        }
    }

    /** 把无障碍服务的拒绝原因映射为 RN 契约可识别的错误码。 */
    private fun mapRejectionCode(reasonCode: String): String = when (reasonCode) {
        "SERVICE_NOT_CONNECTED", "COORDINATOR_CLOSED" -> "SERVICE_UNAVAILABLE"
        "NO_CHECKOUT_CONTEXT", "NOT_CHECKOUT_PAGE", "SENSITIVE_PAGE",
        "BUFFER_UNAVAILABLE", "PAYMENT_HANDOFF_ALREADY_ATTEMPTED" -> "SESSION_EXPIRED"
        "REQUEST_BUDGET_EXCEEDED" -> "AI_BUDGET_EXCEEDED"
        "INVALID_PAYMENT_NOTIFICATION", "PAYMENT_NOTIFICATION_ALREADY_ATTEMPTED" ->
            "DUPLICATE_TRANSACTION"
        else -> "UNKNOWN"
    }

    private fun launch(promise: Promise, action: suspend () -> Any?) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                promise.resolve(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BridgeException) {
                promise.reject(error.code, error.message, error)
            } catch (error: Exception) {
                promise.reject(
                    "UNKNOWN",
                    error.message ?: "Recognition operation failed",
                    error
                )
            }
        }
    }
}
