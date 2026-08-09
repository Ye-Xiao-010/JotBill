package com.jotbill.app.data

import androidx.room.withTransaction
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import org.json.JSONObject
import java.util.UUID

/**
 * 负责账单、账户余额及余额校准的本地事务。
 */
class LedgerRepository(private val database: JotBillDatabase) {
    suspend fun createBillWithBalanceUpdate(bill: BillEntity) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.billDao().insert(bill)
            applyBalanceDelta(bill, multiplier = 1L, updatedAt = now)

            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = bill.id + ":create",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = bill.amountMinor.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
        }
    }

    suspend fun updateBillWithBalanceUpdate(existing: BillEntity, updated: BillEntity) {
        require(existing.type != "adjustment" && updated.type != "adjustment") {
            "Adjustment bills are immutable"
        }
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyBalanceDelta(existing, multiplier = -1L, updatedAt = now)
            applyBalanceDelta(updated, multiplier = 1L, updatedAt = now)
            database.billDao().update(updated)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${updated.id}:update:${updated.version}",
                    entityType = "bill",
                    entityId = updated.id,
                    action = "update",
                    fieldName = null,
                    oldValue = existing.amountMinor.toString(),
                    newValue = updated.amountMinor.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
        }
    }

    suspend fun deleteBillWithBalanceUpdate(bill: BillEntity) {
        require(bill.type != "adjustment") {
            "Adjustment bills are immutable"
        }
        val now = System.currentTimeMillis()
        database.withTransaction {
            applyBalanceDelta(bill, multiplier = -1L, updatedAt = now)
            database.billDao().delete(bill)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${bill.id}:delete",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "delete",
                    fieldName = null,
                    oldValue = bill.amountMinor.toString(),
                    newValue = null,
                    operator = "system",
                    createdAt = now
                )
            )
        }
    }

    suspend fun confirmPendingTransaction(pending: PendingTransactionEntity, bill: BillEntity) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.billDao().insert(bill)
            applyBalanceDelta(bill, multiplier = 1L, updatedAt = now)
            database.pendingTransactionDao().delete(pending)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${bill.id}:create",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = bill.amountMinor.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
        }
    }

    suspend fun calibrateAccountBalance(
        accountId: String,
        actualBalanceMinor: Long,
        note: String?
    ): BalanceCalibrationEntity {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val account = requireNotNull(database.accountDao().getById(accountId)) {
                "Account $accountId does not exist"
            }
            val diffMinor = try {
                Math.subtractExact(actualBalanceMinor, account.currentBalanceMinor)
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Balance calibration difference is out of range", error)
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
            val adjustmentBill = BillEntity(
                id = calibration.id,
                amountMinor = if (diffMinor < 0L) -diffMinor else diffMinor,
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
                    newValue = adjustmentBill.amountMinor.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
            calibration
        }
    }

    private suspend fun applyBalanceDelta(bill: BillEntity, multiplier: Long, updatedAt: Long) {
        bill.accountId?.let { accountId ->
            val account = requireNotNull(database.accountDao().getById(accountId)) {
                "Account $accountId does not exist"
            }
            database.accountDao().updateBalance(
                id = account.id,
                newBalance = account.currentBalanceMinor +
                    BalanceCalculator.delta(bill.amountMinor, bill.type) * multiplier,
                updatedAt = updatedAt
            )
        }

        if (bill.type == "transfer" || bill.type == "repayment") {
            bill.targetAccountId?.let { targetAccountId ->
                val target = requireNotNull(database.accountDao().getById(targetAccountId)) {
                    "Target account $targetAccountId does not exist"
                }
                database.accountDao().updateBalance(
                    id = target.id,
                    newBalance = target.currentBalanceMinor + bill.amountMinor * multiplier,
                    updatedAt = updatedAt
                )
            }
        }
    }
}

object BalanceCalculator {
    fun calculate(currentBalance: Long, amount: Long, type: String): Long =
        currentBalance + delta(amount, type)

    fun delta(amount: Long, type: String): Long = when (type) {
        "income", "refund" -> amount
        "expense", "fee" -> -amount
        "transfer", "repayment", "adjustment" -> 0L
        else -> error("Unsupported transaction type: $type")
    }
}
