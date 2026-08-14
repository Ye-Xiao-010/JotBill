package com.jotbill.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jotbill.app.data.dao.AccountDao
import com.jotbill.app.data.dao.AiCostRecordDao
import com.jotbill.app.data.dao.AiProviderConfigDao
import com.jotbill.app.data.dao.AppSettingDao
import com.jotbill.app.data.dao.AuditEntryDao
import com.jotbill.app.data.dao.BalanceCalibrationDao
import com.jotbill.app.data.dao.BillDao
import com.jotbill.app.data.dao.BudgetDao
import com.jotbill.app.data.dao.CategoryDao
import com.jotbill.app.data.dao.ChatDao
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
import com.jotbill.app.data.entity.ChatMessageEntity
import com.jotbill.app.data.entity.ChatSessionEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.FinancialGoalEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity

const val JOT_BILL_DATABASE_VERSION = 4

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
        AppSettingEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class
    ],
    version = JOT_BILL_DATABASE_VERSION,
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
    abstract fun chatDao(): ChatDao
}

object DatabaseMigrations {
    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chat_sessions` (
                    `id` TEXT NOT NULL,
                    `title` TEXT,
                    `ai_provider_id` TEXT,
                    `is_active` INTEGER NOT NULL,
                    `is_pinned` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`ai_provider_id`) REFERENCES `ai_provider_configs`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_sessions_updated_at` " +
                    "ON `chat_sessions` (`updated_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_sessions_is_pinned` " +
                    "ON `chat_sessions` (`is_pinned`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_sessions_ai_provider_id` " +
                    "ON `chat_sessions` (`ai_provider_id`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chat_messages` (
                    `id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `seq_num` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT,
                    `image_data_json` TEXT,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`session_id`) REFERENCES `chat_sessions`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_messages_session_id_seq_num` " +
                    "ON `chat_messages` (`session_id`, `seq_num`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_messages_created_at` " +
                    "ON `chat_messages` (`created_at`)"
            )
        }
    }

    private val migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 将历史智谱模型配置迁移到当前默认模型。
            db.execSQL(
                """
                UPDATE `ai_provider_configs`
                SET `model` = 'glm-4.6v-flash'
                WHERE `provider` = 'glm'
                  AND lower(`model`) IN ('glm-4-flash', 'glm-4-plus')
                """.trimIndent()
            )
        }
    }

    private val migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 外键策略从 SET NULL 改为 RESTRICT，需要重建账单表才能保留 SQLite 约束。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bills_new` (
                    `id` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `occurred_at` INTEGER NOT NULL,
                    `description` TEXT,
                    `category_id` TEXT,
                    `account_id` TEXT,
                    `target_account_id` TEXT,
                    `platform` TEXT,
                    `merchant` TEXT,
                    `product_description` TEXT,
                    `source_type` TEXT NOT NULL,
                    `original_transaction_id` TEXT,
                    `order_id` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `version` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`account_id`) REFERENCES `accounts`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`target_account_id`) REFERENCES `accounts`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `bills_new` (
                    `id`, `amount_minor`, `type`, `occurred_at`, `description`,
                    `category_id`, `account_id`, `target_account_id`, `platform`,
                    `merchant`, `product_description`, `source_type`,
                    `original_transaction_id`, `order_id`, `created_at`,
                    `updated_at`, `version`
                )
                SELECT
                    `id`, `amount_minor`, `type`, `occurred_at`, `description`,
                    `category_id`, `account_id`, `target_account_id`, `platform`,
                    `merchant`, `product_description`, `source_type`,
                    `original_transaction_id`, `order_id`, `created_at`,
                    `updated_at`, `version`
                FROM `bills`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `bills`")
            db.execSQL("ALTER TABLE `bills_new` RENAME TO `bills`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_occurred_at` ON `bills` (`occurred_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_category_id` ON `bills` (`category_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_account_id` ON `bills` (`account_id`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bills_target_account_id` " +
                    "ON `bills` (`target_account_id`)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_platform` ON `bills` (`platform`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_order_id` ON `bills` (`order_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_source_type` ON `bills` (`source_type`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bills_original_transaction_id` " +
                    "ON `bills` (`original_transaction_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bills_occurred_at_type` " +
                    "ON `bills` (`occurred_at`, `type`)"
            )
            // 识别链路：扩展 transaction_candidates 和 payment_evidence 列
            db.execSQL(
                "ALTER TABLE `transaction_candidates` ADD COLUMN `ai_order_id` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `transaction_candidates` ADD COLUMN `ai_occurred_at` INTEGER"
            )
            db.execSQL(
                "ALTER TABLE `payment_evidence` ADD COLUMN `source_event_key` TEXT"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_evidence_source_event_key` " +
                    "ON `payment_evidence` (`source_event_key`)"
            )
        }
    }

    val all: Array<Migration> = arrayOf(migration1To2, migration2To3, migration3To4)
}
