package com.jotbill.app.bridge

import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal class BridgeException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/**
 * Keeps the React Native contract independent from Room column names and
 * converts persisted epoch milliseconds to the ISO-8601 values used by JS.
 */
internal object BridgeDtoMapper {
    private val supportedBillTypes = setOf(
        "income",
        "expense",
        "transfer",
        "refund",
        "repayment",
        "fee",
        "adjustment"
    )

    private val supportedSourceTypes = setOf(
        "ai_vision",
        "notification",
        "accessibility",
        "sms",
        "import",
        "manual",
        "ai_chat"
    )

    fun bill(entity: BillEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("amountMinor", entity.amountMinor)
        put("type", entity.type)
        put("occurredAt", formatTimestamp(entity.occurredAt))
        putOptional("description", entity.description)
        putOptional("categoryId", entity.categoryId)
        putOptional("accountId", entity.accountId)
        putOptional("targetAccountId", entity.targetAccountId)
        putOptional("platform", entity.platform)
        putOptional("merchant", entity.merchant)
        putOptional("productDescription", entity.productDescription)
        put("sourceType", entity.sourceType)
        putOptional("originalTransactionId", entity.originalTransactionId)
        putOptional("orderId", entity.orderId)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
        put("version", entity.version)
    }

    fun account(entity: AccountEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("subtype", entity.subtype)
        put("kind", entity.kind)
        put("initialBalanceMinor", entity.initialBalanceMinor)
        put("currentBalanceMinor", entity.currentBalanceMinor)
        put("currency", entity.currency)
        putOptional("icon", entity.icon)
        putOptional("color", entity.color)
        put("sortOrder", entity.sortOrder)
        put("isActive", entity.isActive)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
    }

    fun category(entity: CategoryEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("type", entity.type)
        putOptional("icon", entity.icon)
        putOptional("color", entity.color)
        put("isDefault", entity.isDefault)
        put("sortOrder", entity.sortOrder)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
    }

    fun budget(entity: BudgetEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("amountMinor", entity.amountMinor)
        put("period", entity.period)
        putOptional("categoryId", entity.categoryId)
        put("alertAt", entity.alertAt)
        put("isActive", entity.isActive)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
    }

    fun financialGoal(entity: FinancialGoalEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("targetAmountMinor", entity.targetAmountMinor)
        put("currentAmountMinor", entity.currentAmountMinor)
        putOptional("targetDate", entity.targetDate?.let(::formatTimestamp))
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
    }

    fun rule(entity: RuleEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("name", entity.name)
        put("condition", entity.condition)
        put("conditionValue", entity.conditionValue)
        put("action", entity.action)
        putOptional("actionValue", entity.actionValue)
        put("priority", entity.priority)
        put("isActive", entity.isActive)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("updatedAt", formatTimestamp(entity.updatedAt))
    }

    fun pending(entity: PendingTransactionEntity): JSONObject = JSONObject().apply {
        put("pendingId", entity.id)
        putOptional("candidateId", entity.candidateId)
        putOptional("sessionId", entity.sessionId)
        put("amountMinor", entity.amountMinor)
        put("type", entity.type)
        put("occurredAt", formatTimestamp(entity.occurredAt))
        putOptional("platform", entity.platform)
        putOptional("merchant", entity.merchant)
        putOptional("productDescription", entity.productDescription)
        putOptional("orderId", entity.orderId)
        putOptional("accountId", entity.accountId)
        putOptional("categoryId", entity.categoryId)
        putOptional("conflictReason", entity.conflictReason)
        put("sourceType", entity.sourceType)
        put("createdAt", formatTimestamp(entity.createdAt))
        put("version", entity.version)
    }

    fun calibration(entity: BalanceCalibrationEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("accountId", entity.accountId)
        put("beforeBalanceMinor", entity.beforeBalanceMinor)
        put("afterBalanceMinor", entity.afterBalanceMinor)
        put("diffMinor", entity.diffMinor)
        putOptional("note", entity.note)
        put("createdAt", formatTimestamp(entity.createdAt))
    }

    fun parseBillInput(json: JSONObject, now: Long, id: String): BillEntity {
        val type = requiredString(json, "type")
        validateBillType(type)

        val sourceType = requiredString(json, "sourceType")
        validateSourceType(sourceType)

        val amountMinor = requiredLong(json, "amountMinor")
        if (amountMinor < 0) {
            throw BridgeException("INVALID_ARGUMENT", "amountMinor must not be negative")
        }

        return BillEntity(
            id = id,
            amountMinor = amountMinor,
            type = type,
            occurredAt = requiredTimestamp(json, "occurredAt"),
            description = json.optionalString("description"),
            categoryId = json.optionalString("categoryId"),
            accountId = json.optionalString("accountId"),
            targetAccountId = json.optionalString("targetAccountId"),
            platform = json.optionalString("platform"),
            merchant = json.optionalString("merchant"),
            productDescription = json.optionalString("productDescription"),
            sourceType = sourceType,
            originalTransactionId = json.optionalString("originalTransactionId"),
            orderId = json.optionalString("orderId"),
            createdAt = now,
            updatedAt = now,
            version = 1
        )
    }

    fun validateBillType(type: String) {
        if (type !in supportedBillTypes) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported transaction type: $type")
        }
    }

    fun validateSourceType(sourceType: String) {
        if (sourceType !in supportedSourceTypes) {
            throw BridgeException("INVALID_ARGUMENT", "Unsupported source type: $sourceType")
        }
    }

    fun parseOptionalTimestamp(json: JSONObject, key: String): Long? {
        if (!json.hasValue(key)) return null
        return parseTimestampValue(json.get(key), key)
    }

    fun requiredString(json: JSONObject, key: String): String {
        return json.optionalString(key)
            ?: throw BridgeException("INVALID_ARGUMENT", "Missing required field: $key")
    }

    fun requiredLong(json: JSONObject, key: String): Long {
        if (!json.hasValue(key)) {
            throw BridgeException("INVALID_ARGUMENT", "Missing required field: $key")
        }
        return try {
            json.getLong(key)
        } catch (error: Exception) {
            throw BridgeException("INVALID_ARGUMENT", "Field $key must be an integer", error)
        }
    }

    fun requiredTimestamp(json: JSONObject, key: String): Long {
        if (!json.hasValue(key)) {
            throw BridgeException("INVALID_ARGUMENT", "Missing required field: $key")
        }
        return parseTimestampValue(json.get(key), key)
    }

    fun parseTimestampValue(value: Any, key: String): Long {
        if (value is Number) return value.toLong()
        val text = value as? String
            ?: throw BridgeException("INVALID_ARGUMENT", "Field $key must be an ISO-8601 timestamp")
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            throw BridgeException("INVALID_ARGUMENT", "Field $key must not be empty")
        }

        return try {
            Instant.parse(normalized).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(normalized).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (error: DateTimeParseException) {
                        throw BridgeException("INVALID_ARGUMENT", "Field $key must be an ISO-8601 timestamp", error)
                    }
                }
            }
        }
    }

    fun parseDateBoundary(value: String?, endOfDate: Boolean): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
        return try {
            val date = LocalDate.parse(normalized)
            val boundary = if (endOfDate) date.plusDays(1).atStartOfDay() else date.atStartOfDay()
            boundary.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - if (endOfDate) 1 else 0
        } catch (_: DateTimeParseException) {
            parseTimestampValue(normalized, if (endOfDate) "endDate" else "startDate")
        }
    }

    fun formatTimestamp(value: Long): String = Instant.ofEpochMilli(value).toString()

    private fun JSONObject.putOptional(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.hasValue(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.optionalString(key: String): String? {
        if (!hasValue(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
