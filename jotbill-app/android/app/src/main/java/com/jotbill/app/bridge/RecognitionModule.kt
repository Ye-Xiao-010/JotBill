package com.jotbill.app.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识别数据只读桥接层。
 *
 * ⚠️ 本模块原为"识别链路桥接"，9 个方法里有 3 个直接驱动无障碍服务
 * （retryAiRecognition / isAccessibilityEnabled / openAccessibilitySettings）。
 * 精简版移除整条自动识别链路后，这 3 个方法及其支撑代码
 * （TERMINAL_SESSION_STATUSES / mapRejectionCode / isAccessibilityServiceEnabled）
 * 已全部删除。
 *
 * **保留本模块的理由**：剩下 6 个方法全是**纯 Room 只读查询**
 * （checkout_sessions / transaction_candidates / payment_evidence /
 * pending_transactions），读的是 Room schema 里的表，**不依赖 recognition 包**。
 * 待确认中心（ReviewScreen）的状态卡与"支付证据"区块依赖它们，
 * 而这些数据在识别链路删除后**依然存在**（Room schema 18 张表按约束不可动）。
 * 删掉本模块等于连带删掉对已有数据的只读展示能力。
 *
 * 所有图片与敏感字段仍不离开原生层。
 */
class RecognitionModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    companion object {
        private const val MODULE_NAME = "Recognition"
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
            sessions.forEach { session ->
                val evidenceIds = database.paymentEvidenceDao()
                    .getBySession(session.id)
                    .map { evidence -> evidence.id }
                array.put(BridgeDtoMapper.session(session, evidenceIds))
            }
        }.toString()
    }

    /** 查询单个结算会话；不存在时返回 NOT_FOUND。 */
    @ReactMethod
    fun getCheckoutSession(sessionId: String, promise: Promise) = launch(promise) {
        val session = database.checkoutSessionDao().getById(requireId(sessionId, "session"))
            ?: throw BridgeException("NOT_FOUND", "Checkout session $sessionId was not found")
        val evidenceIds = database.paymentEvidenceDao()
            .getBySession(session.id)
            .map { evidence -> evidence.id }
        BridgeDtoMapper.session(session, evidenceIds).toString()
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
