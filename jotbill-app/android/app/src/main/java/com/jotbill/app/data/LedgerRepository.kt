package com.jotbill.app.data

import androidx.room.withTransaction
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity

/**
 * Coordinates the first write path that must update both a bill and balances.
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
