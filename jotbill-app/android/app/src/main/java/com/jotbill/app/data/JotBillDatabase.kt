package com.jotbill.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.jotbill.app.data.dao.AccountDao
import com.jotbill.app.data.dao.AiCostRecordDao
import com.jotbill.app.data.dao.AiProviderConfigDao
import com.jotbill.app.data.dao.AppSettingDao
import com.jotbill.app.data.dao.AuditEntryDao
import com.jotbill.app.data.dao.BalanceCalibrationDao
import com.jotbill.app.data.dao.BillDao
import com.jotbill.app.data.dao.BudgetDao
import com.jotbill.app.data.dao.CategoryDao
import com.jotbill.app.data.dao.CheckoutSessionDao
import com.jotbill.app.data.dao.FinancialGoalDao
import com.jotbill.app.data.dao.PaymentEvidenceDao
import com.jotbill.app.data.dao.PendingTransactionDao
import com.jotbill.app.data.dao.RuleDao
import com.jotbill.app.data.dao.TransactionCandidateDao
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AiCostRecordEntity
import com.jotbill.app.data.entity.AiProviderConfigEntity
import com.jotbill.app.data.entity.AppSettingEntity
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity

@Database(
    entities = [
        BillEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        FinancialGoalEntity::class,
        RuleEntity::class,
        PendingTransactionEntity::class,
        CheckoutSessionEntity::class,
        TransactionCandidateEntity::class,
        PaymentEvidenceEntity::class,
        AiProviderConfigEntity::class,
        AiCostRecordEntity::class,
        BalanceCalibrationEntity::class,
        AuditEntryEntity::class,
        AppSettingEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class JotBillDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun financialGoalDao(): FinancialGoalDao
    abstract fun ruleDao(): RuleDao
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun checkoutSessionDao(): CheckoutSessionDao
    abstract fun transactionCandidateDao(): TransactionCandidateDao
    abstract fun paymentEvidenceDao(): PaymentEvidenceDao
    abstract fun aiProviderConfigDao(): AiProviderConfigDao
    abstract fun aiCostRecordDao(): AiCostRecordDao
    abstract fun balanceCalibrationDao(): BalanceCalibrationDao
    abstract fun auditEntryDao(): AuditEntryDao
    abstract fun appSettingDao(): AppSettingDao
}

object DatabaseMigrations {
    val all: Array<Migration> = emptyArray()
}
