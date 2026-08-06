package com.jotbill.app.ai

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.entity.AiProviderConfigEntity
import com.jotbill.app.security.KeyStoreSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** AI 服务商配置桥接，密钥只在原生层短暂解密。 */
class AiModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database: JotBillDatabase by lazy { JotBillDatabaseProvider.get(reactContext) }
    private val secretStore: KeyStoreSecretStore by lazy { KeyStoreSecretStore(reactContext) }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun getName(): String = "Ai"

    @ReactMethod
    fun getAiProviders(promise: Promise) = launch(promise) {
        JSONArray().also { array ->
            database.aiProviderConfigDao().getAll().forEach { array.put(toJson(it)) }
        }.toString()
    }

    @ReactMethod
    fun addAiProvider(inputJson: String, promise: Promise) = launch(promise) {
        val input = JSONObject(inputJson)
        val name = requiredString(input, "name")
        val provider = requiredProvider(input)
        val model = requiredString(input, "model")
        val apiKey = requiredString(input, "apiKey")
        val existing = database.aiProviderConfigDao().getAll()
        val now = System.currentTimeMillis()
        val entity = AiProviderConfigEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            provider = provider,
            model = model,
            apiKeyEncrypted = secretStore.encryptSecret(apiKey),
            apiBaseUrl = input.optionalString("apiBaseUrl"),
            isDefault = input.optBoolean("isDefault", existing.isEmpty()),
            supportsVision = input.optBoolean("supportsVision", false),
            createdAt = now,
            updatedAt = now
        )
        if (entity.isDefault) clearDefault()
        database.aiProviderConfigDao().insert(entity)
        toJson(entity).toString()
    }

    @ReactMethod
    fun updateAiProvider(id: String, inputJson: String, promise: Promise) = launch(promise) {
        val dao = database.aiProviderConfigDao()
        val existing = dao.getAll().firstOrNull { it.id == id }
            ?: throw AiException("NOT_FOUND", "AI provider $id was not found")
        val input = JSONObject(inputJson)
        val apiKey = input.optionalString("apiKey")
        val updated = existing.copy(
            name = input.optionalString("name") ?: existing.name,
            provider = input.optionalString("provider")?.also(::validateProvider) ?: existing.provider,
            model = input.optionalString("model") ?: existing.model,
            apiKeyEncrypted = if (apiKey.isNullOrBlank() || apiKey == MASKED_KEY) {
                existing.apiKeyEncrypted
            } else {
                secretStore.encryptSecret(apiKey)
            },
            apiBaseUrl = input.updatedNullableString("apiBaseUrl", existing.apiBaseUrl),
            isDefault = if (input.has("isDefault") && !input.isNull("isDefault")) {
                input.getBoolean("isDefault")
            } else {
                existing.isDefault
            },
            supportsVision = if (input.has("supportsVision")) {
                input.getBoolean("supportsVision")
            } else {
                existing.supportsVision
            },
            updatedAt = System.currentTimeMillis()
        )
        if (updated.isDefault) clearDefault()
        dao.update(updated)
        toJson(updated).toString()
    }

    @ReactMethod
    fun deleteAiProvider(id: String, promise: Promise) = launch(promise) {
        val dao = database.aiProviderConfigDao()
        val existing = dao.getAll().firstOrNull { it.id == id }
            ?: throw AiException("NOT_FOUND", "AI provider $id was not found")
        dao.delete(existing)
        if (existing.isDefault) {
            dao.getAll().firstOrNull()?.let { dao.update(it.copy(isDefault = true)) }
        }
        null
    }

    @ReactMethod
    fun setDefaultAiProvider(id: String, promise: Promise) = launch(promise) {
        val dao = database.aiProviderConfigDao()
        val selected = dao.getAll().firstOrNull { it.id == id }
            ?: throw AiException("NOT_FOUND", "AI provider $id was not found")
        clearDefault()
        val updated = selected.copy(isDefault = true, updatedAt = System.currentTimeMillis())
        dao.update(updated)
        toJson(updated).toString()
    }

    @ReactMethod
    fun testAiProvider(id: String, promise: Promise) = launch(promise) {
        val config = database.aiProviderConfigDao().getAll().firstOrNull { it.id == id }
            ?: throw AiException("NOT_FOUND", "AI provider $id was not found")
        val baseUrl = config.apiBaseUrl?.trim().takeUnless { it.isNullOrEmpty() }
            ?: defaultBaseUrl(config.provider)
        val apiKey = config.apiKeyEncrypted?.let(secretStore::decryptSecret)
        val start = System.currentTimeMillis()
        try {
            val requestBuilder = Request.Builder().url(baseUrl).get()
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val reachable = response.code !in setOf(401, 403) && response.code < 500
                JSONObject()
                    .put("success", reachable)
                    .put("message", if (reachable) "服务地址可访问" else "服务地址返回 HTTP ${response.code}")
                    .put("latencyMs", System.currentTimeMillis() - start)
                    .toString()
            }
        } catch (error: Exception) {
            JSONObject()
                .put("success", false)
                .put("message", error.message ?: "连接失败")
                .put("latencyMs", System.currentTimeMillis() - start)
                .toString()
        }
    }

    override fun invalidate() {
        scope.coroutineContext.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        super.invalidate()
    }

    private suspend fun clearDefault() {
        val dao = database.aiProviderConfigDao()
        dao.getAll().filter { it.isDefault }.forEach {
            dao.update(it.copy(isDefault = false, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun toJson(entity: AiProviderConfigEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("provider", entity.provider)
        put("model", entity.model)
        if (!entity.apiKeyEncrypted.isNullOrBlank()) put("apiKey", MASKED_KEY)
        entity.apiBaseUrl?.let { put("apiBaseUrl", it) }
        put("isDefault", entity.isDefault)
        put("supportsVision", entity.supportsVision)
        put("createdAt", java.time.Instant.ofEpochMilli(entity.createdAt).toString())
        put("updatedAt", java.time.Instant.ofEpochMilli(entity.updatedAt).toString())
    }

    private fun requiredString(input: JSONObject, key: String): String {
        val value = input.optString(key).trim()
        if (value.isEmpty()) throw AiException("INVALID_ARGUMENT", "缺少字段: $key")
        return value
    }

    private fun requiredProvider(input: JSONObject): String {
        val provider = requiredString(input, "provider")
        validateProvider(provider)
        return provider
    }

    private fun validateProvider(provider: String) {
        if (provider !in SUPPORTED_PROVIDERS) {
            throw AiException("INVALID_ARGUMENT", "不支持的 AI 服务商: $provider")
        }
    }

    private fun defaultBaseUrl(provider: String): String = when (provider) {
        "qwen" -> "https://dashscope.aliyuncs.com"
        "glm" -> "https://open.bigmodel.cn"
        "siliconflow" -> "https://api.siliconflow.cn"
        else -> throw AiException("INVALID_ARGUMENT", "自定义服务商必须填写 API 地址")
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.updatedNullableString(key: String, current: String?): String? {
        if (!has(key)) return current
        if (isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun launch(promise: Promise, block: suspend () -> String?) {
        scope.launch {
            try {
                val result = block()
                withContext(Dispatchers.Main) { promise.resolve(result) }
            } catch (error: AiException) {
                withContext(Dispatchers.Main) { promise.reject(error.code, error.message, error) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { promise.reject("AI_CONFIG_ERROR", error.message, error) }
            }
        }
    }

    private data class AiException(val code: String, override val message: String) : Exception(message)

    private companion object {
        const val MASKED_KEY = "********"
        val SUPPORTED_PROVIDERS = setOf("qwen", "glm", "siliconflow", "custom")
    }
}
