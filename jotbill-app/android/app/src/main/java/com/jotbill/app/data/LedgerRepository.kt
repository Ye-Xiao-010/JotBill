package com.jotbill.app.data

import androidx.room.withTransaction
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import org.json.JSONObject
import java.util.UUID

internal const val MAX_SAFE_MINOR_UNIT = 9_007_199_254_740_991L

internal class LedgerValidationException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

private val TERMINAL_SESSION_STATUSES = setOf("COMPLETED", "CANCELLED", "FAILED", "EXPIRED")

/**
 * 负责账单、账户余额及余额校准的本地事务。
 */
class LedgerRepository(private val database: JotBillDatabase) {
    /**
     * 创建账单并按交易类型联动更新账户余额。
     *
     * @param bill 待写入的账单实体
     * @param operator 审计操作者标记：手动路径为 "system"，阶段四 T5 自动入账为 "auto_commit"
     */
    suspend fun createBillWithBalanceUpdate(bill: BillEntity, operator: String = "system") {
        validateSafeAmount(bill.amountMinor)
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.billDao().insert(bill)
            applyBalanceDelta(
                bill,
                multiplier = 1L,
                updatedAt = now,
                requireActiveAccounts = operator == "auto_commit"
            )

            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = bill.id + ":create",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(bill),
                    operator = operator,
                    createdAt = now
                )
            )
        }
    }

    suspend fun updateBillWithBalanceUpdate(existing: BillEntity, updated: BillEntity): Boolean {
        require(existing.id == updated.id) {
            "Bill ids must match for update"
        }
        requireMutableBill(existing)
        requireMutableBill(updated)
        validateSafeAmount(existing.amountMinor)
        validateSafeAmount(updated.amountMinor)
        require(updated.version == existing.version + 1) {
            "Bill version must increase by one"
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = database.billDao().getById(existing.id)
                ?: return@withTransaction false
            requireMutableBill(current)
            if (current.version != existing.version) {
                return@withTransaction false
            }
            val updatedRows = database.billDao().updateIfVersion(
                id = updated.id,
                expectedVersion = current.version,
                amountMinor = updated.amountMinor,
                type = updated.type,
                occurredAt = updated.occurredAt,
                description = updated.description,
                categoryId = updated.categoryId,
                accountId = updated.accountId,
                targetAccountId = updated.targetAccountId,
                platform = updated.platform,
                merchant = updated.merchant,
                productDescription = updated.productDescription,
                sourceType = updated.sourceType,
                originalTransactionId = updated.originalTransactionId,
                orderId = updated.orderId,
                updatedAt = updated.updatedAt,
                newVersion = updated.version
            )
            if (updatedRows != 1) {
                return@withTransaction false
            }
            applyBalanceDelta(current, multiplier = -1L, updatedAt = now)
            applyBalanceDelta(updated, multiplier = 1L, updatedAt = now)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${updated.id}:update:${updated.version}",
                    entityType = "bill",
                    entityId = updated.id,
                    action = "update",
                    fieldName = null,
                    oldValue = billAuditValue(current),
                    newValue = billAuditValue(updated),
                    operator = "system",
                    createdAt = now
                )
            )
            true
        }
    }

    suspend fun deleteBillWithBalanceUpdate(bill: BillEntity): Boolean {
        requireMutableBill(bill)
        validateSafeAmount(bill.amountMinor)
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = database.billDao().getById(bill.id)
                ?: return@withTransaction false
            requireMutableBill(current)
            if (database.billDao().deleteById(current.id) != 1) {
                return@withTransaction false
            }
            applyBalanceDelta(current, multiplier = -1L, updatedAt = now)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${current.id}:delete",
                    entityType = "bill",
                    entityId = current.id,
                    action = "delete",
                    fieldName = null,
                    oldValue = billAuditValue(current),
                    newValue = null,
                    operator = "system",
                    createdAt = now
                )
            )
            true
        }
    }

    /**
     * 在同一 Room 事务内完成待确认交易的确认、入账和全部关联终态更新。
     *
     * 事务内重新读取 pending 并校验版本、账户和关联关系；任何一步失败都会整体回滚。
     */
    suspend fun confirmPendingTransaction(
        pending: PendingTransactionEntity,
        bill: BillEntity
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val currentPending = database.pendingTransactionDao().getById(pending.id)
                ?: return@withTransaction false
            if (
                currentPending.status != PendingTransactionEntity.STATUS_PENDING ||
                currentPending.version != pending.version
            ) {
                return@withTransaction false
            }

            if (bill.originalTransactionId != currentPending.sessionId) {
                throw LedgerValidationException("Pending transaction source binding is immutable")
            }
            validateConfirmationBill(bill)
            val relations = loadPendingRelations(currentPending)
            if (relations.session?.let { it.status in TERMINAL_SESSION_STATUSES } == true) {
                throw LedgerValidationException("Checkout session is already terminal")
            }
            if (currentPending.sessionId != null &&
                database.billDao().findByOriginalTransactionId(currentPending.sessionId) != null
            ) {
                throw LedgerValidationException("Checkout session has already been committed")
            }

            database.billDao().insert(bill)
            applyBalanceDelta(
                bill,
                multiplier = 1L,
                updatedAt = now,
                requireActiveAccounts = true
            )
            relations.candidate?.let { candidate ->
                val resolved = database.transactionCandidateDao().resolveIfVersion(
                    id = candidate.id,
                    sessionId = candidate.sessionId,
                    expectedVersion = candidate.version,
                    matched = true,
                    conflictReason = null,
                    appliedRules = decisionRules(candidate.appliedRules, "USER_CONFIRMED", bill.id)
                )
                if (resolved != 1) {
                    throw LedgerValidationException("Transaction candidate has changed")
                }
            }
            relations.session?.let { session ->
                if (database.checkoutSessionDao().updateTerminalStateIfActive(
                        id = session.id,
                        status = "COMPLETED",
                        journeyState = "COMPLETED",
                        updatedAt = now
                    ) != 1
                ) {
                    throw LedgerValidationException("Checkout session has changed")
                }
            }
            if (database.pendingTransactionDao()
                    .deleteByIdIfVersion(currentPending.id, currentPending.version) != 1
            ) {
                throw LedgerValidationException("Pending transaction has changed")
            }
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${bill.id}:create",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(bill),
                    operator = "system",
                    createdAt = now
                )
            )
            true
        }
    }

    /** 在同一事务内标记忽略、收口候选并关闭其会话。 */
    suspend fun ignorePendingTransaction(
        pending: PendingTransactionEntity,
        expectedVersion: Int
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val currentPending = database.pendingTransactionDao().getById(pending.id)
                ?: return@withTransaction false
            if (
                currentPending.status != PendingTransactionEntity.STATUS_PENDING ||
                currentPending.version != expectedVersion
            ) {
                return@withTransaction false
            }
            val relations = loadPendingRelations(currentPending)
            if (database.pendingTransactionDao()
                    .markIgnoredIfVersion(currentPending.id, currentPending.version) != 1
            ) {
                return@withTransaction false
            }
            relations.candidate?.let { candidate ->
                val resolved = database.transactionCandidateDao().resolveIfVersion(
                    id = candidate.id,
                    sessionId = candidate.sessionId,
                    expectedVersion = candidate.version,
                    matched = false,
                    conflictReason = "USER_IGNORED",
                    appliedRules = decisionRules(candidate.appliedRules, "USER_IGNORED")
                )
                if (resolved != 1) {
                    throw LedgerValidationException("Transaction candidate has changed")
                }
            }
            relations.session?.let { session ->
                if (session.status !in TERMINAL_SESSION_STATUSES &&
                    database.checkoutSessionDao().updateTerminalStateIfActive(
                        id = session.id,
                        status = "CANCELLED",
                        journeyState = "DISMISSED",
                        updatedAt = now
                    ) != 1
                ) {
                    throw LedgerValidationException("Checkout session has changed")
                }
            }
            true
        }
    }

    suspend fun calibrateAccountBalance(
        accountId: String,
        actualBalanceMinor: Long,
        note: String?
    ): BalanceCalibrationEntity {
        if (actualBalanceMinor !in -MAX_SAFE_MINOR_UNIT..MAX_SAFE_MINOR_UNIT) {
            throw LedgerValidationException("Balance must be a safe integer minor unit")
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val account = requireNotNull(database.accountDao().getById(accountId)) {
                "Account $accountId does not exist"
            }
            val diffMinor = try {
                Math.subtractExact(actualBalanceMinor, account.currentBalanceMinor)
            } catch (error: ArithmeticException) {
                throw LedgerValidationException("Balance calibration difference is out of range", error)
            }
            val calibration = BalanceCalibrationEntity(
                id = UUID.randomUUID().toString(),
                accountId = account.id,
                beforeBalanceMinor = account.currentBalanceMinor,
                afterBalanceMinor = actualBalanceMinor,
                diffMinor = diffMinor,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now
            )
            val adjustmentAmountMinor = try {
                if (diffMinor < 0L) Math.negateExact(diffMinor) else diffMinor
            } catch (error: ArithmeticException) {
                throw LedgerValidationException("Balance adjustment is out of range", error)
            }
            validateSafeAmount(adjustmentAmountMinor)
            val adjustmentBill = BillEntity(
                id = calibration.id,
                amountMinor = adjustmentAmountMinor,
                type = "adjustment",
                occurredAt = now,
                description = calibration.note ?: "余额校准",
                categoryId = null,
                accountId = account.id,
                targetAccountId = null,
                platform = null,
                merchant = null,
                productDescription = null,
                sourceType = "manual",
                originalTransactionId = null,
                orderId = null,
                createdAt = now,
                updatedAt = now,
                version = 1
            )

            database.billDao().insert(adjustmentBill)
            database.accountDao().update(
                account.copy(
                    currentBalanceMinor = actualBalanceMinor,
                    updatedAt = now
                )
            )
            database.balanceCalibrationDao().insert(calibration)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${calibration.id}:adjustment",
                    entityType = "balance_calibration",
                    entityId = calibration.id,
                    action = "adjustment",
                    fieldName = "current_balance_minor",
                    oldValue = account.currentBalanceMinor.toString(),
                    newValue = JSONObject().apply {
                        put("accountId", account.id)
                        put("beforeBalanceMinor", calibration.beforeBalanceMinor)
                        put("afterBalanceMinor", calibration.afterBalanceMinor)
                        put("diffMinor", calibration.diffMinor)
                        put("adjustmentBillId", adjustmentBill.id)
                    }.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${adjustmentBill.id}:create",
                    entityType = "bill",
                    entityId = adjustmentBill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(adjustmentBill),
                    operator = "system",
                    createdAt = now
                )
            )
            calibration
        }
    }

    private data class PendingRelations(
        val session: CheckoutSessionEntity?,
        val candidate: TransactionCandidateEntity?
    )

    private suspend fun loadPendingRelations(
        pending: PendingTransactionEntity
    ): PendingRelations {
        if (pending.candidateId != null && pending.sessionId == null) {
            throw LedgerValidationException("Pending candidate is missing its session binding")
        }
        val session = pending.sessionId?.let { sessionId ->
            database.checkoutSessionDao().getById(sessionId)
                ?: throw LedgerValidationException("Checkout session $sessionId does not exist")
        }
        val candidateId = pending.candidateId ?: session?.candidateId
        val candidate = candidateId?.let { id ->
            database.transactionCandidateDao().getById(id)
                ?: throw LedgerValidationException("Transaction candidate $id does not exist")
        }
        if (candidate != null && session?.id != candidate.sessionId) {
            throw LedgerValidationException("Pending candidate and session are not bound")
        }
        if (pending.candidateId != null &&
            session?.candidateId != null &&
            session.candidateId != pending.candidateId
        ) {
            throw LedgerValidationException("Pending candidate does not match its session")
        }
        return PendingRelations(session = session, candidate = candidate)
    }

    private suspend fun validateConfirmationBill(bill: BillEntity) {
        validateSafeAmount(bill.amountMinor, requirePositive = true)
        val accountId = bill.accountId?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException("Confirmation requires an account")
        val account = database.accountDao().getById(accountId)
            ?: throw LedgerValidationException("Account $accountId does not exist")
        if (!account.isActive) {
            throw LedgerValidationException("Account $accountId is inactive")
        }

        if (bill.type == "transfer" || bill.type == "repayment") {
            val targetAccountId = bill.targetAccountId?.takeIf { it.isNotBlank() }
                ?: throw LedgerValidationException("${bill.type} requires a target account")
            if (targetAccountId == accountId) {
                throw LedgerValidationException("Source and target accounts must differ")
            }
            val target = database.accountDao().getById(targetAccountId)
                ?: throw LedgerValidationException("Target account $targetAccountId does not exist")
            if (!target.isActive) {
                throw LedgerValidationException("Target account $targetAccountId is inactive")
            }
        }
    }

    private fun validateSafeAmount(amountMinor: Long, requirePositive: Boolean = false) {
        val lowerBound = if (requirePositive) 1L else 0L
        if (amountMinor < lowerBound || amountMinor > MAX_SAFE_MINOR_UNIT) {
            throw LedgerValidationException(
                if (requirePositive) {
                    "Amount must be a positive safe integer minor unit"
                } else {
                    "Amount must be a safe integer minor unit"
                }
            )
        }
    }

    private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw LedgerValidationException(message, error)
    }

    private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw LedgerValidationException(message, error)
    }

    private fun decisionRules(existing: String, decision: String, billId: String? = null): String {
        val rules = runCatching { JSONObject(existing) }.getOrElse { JSONObject() }
        rules.put("decision", decision)
        billId?.let { rules.put("billId", it) }
        return rules.toString()
    }

    private suspend fun applyBalanceDelta(
        bill: BillEntity,
        multiplier: Long,
        updatedAt: Long,
        requireActiveAccounts: Boolean = false
    ) {
        validateSafeAmount(bill.amountMinor)
        val sourceDelta = checkedMultiply(
            BalanceCalculator.delta(bill.amountMinor, bill.type),
            multiplier,
            "Account balance delta is out of range"
        )
        bill.accountId?.let { accountId ->
            val account = database.accountDao().getById(accountId)
                ?: throw LedgerValidationException("Account $accountId does not exist")
            if (requireActiveAccounts && !account.isActive) {
                throw LedgerValidationException("Account $accountId is inactive")
            }
            database.accountDao().updateBalance(
                id = account.id,
                newBalance = checkedAdd(
                    account.currentBalanceMinor,
                    sourceDelta,
                    "Account balance is out of range"
                ),
                updatedAt = updatedAt
            )
        }

        if (bill.type == "transfer" || bill.type == "repayment") {
            bill.targetAccountId?.let { targetAccountId ->
                val target = database.accountDao().getById(targetAccountId)
                    ?: throw LedgerValidationException("Target account $targetAccountId does not exist")
                if (requireActiveAccounts && !target.isActive) {
                    throw LedgerValidationException("Target account $targetAccountId is inactive")
                }
                val targetDelta = checkedMultiply(
                    bill.amountMinor,
                    multiplier,
                    "Target account balance delta is out of range"
                )
                database.accountDao().updateBalance(
                    id = target.id,
                    newBalance = checkedAdd(
                        target.currentBalanceMinor,
                        targetDelta,
                        "Target account balance is out of range"
                    ),
                    updatedAt = updatedAt
                )
            }
        }
    }

    private fun requireMutableBill(bill: BillEntity) {
        require(bill.type != "adjustment") {
            "Adjustment bills are immutable"
        }
    }

    private fun billAuditValue(bill: BillEntity): String = JSONObject().apply {
        put("id", bill.id)
        put("amountMinor", bill.amountMinor)
        put("type", bill.type)
        put("occurredAt", bill.occurredAt)
        putNullable("description", bill.description)
        putNullable("categoryId", bill.categoryId)
        putNullable("accountId", bill.accountId)
        putNullable("targetAccountId", bill.targetAccountId)
        putNullable("platform", bill.platform)
        putNullable("merchant", bill.merchant)
        putNullable("productDescription", bill.productDescription)
        put("sourceType", bill.sourceType)
        putNullable("originalTransactionId", bill.originalTransactionId)
        putNullable("orderId", bill.orderId)
        put("createdAt", bill.createdAt)
        put("updatedAt", bill.updatedAt)
        put("version", bill.version)
    }.toString()

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }
}

object BalanceCalculator {
    fun calculate(currentBalance: Long, amount: Long, type: String): Long =
        Math.addExact(currentBalance, delta(amount, type))

    fun delta(amount: Long, type: String): Long = when (type) {
        "income", "refund" -> amount
        "expense", "fee" -> Math.negateExact(amount)
        "transfer", "repayment", "adjustment" -> 0L
        else -> error("Unsupported transaction type: $type")
    }
}
