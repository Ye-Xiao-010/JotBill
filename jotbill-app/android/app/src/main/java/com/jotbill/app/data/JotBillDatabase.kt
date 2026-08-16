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
import com.jotbill.app.data.dao.ImportBatchDao
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
import com.jotbill.app.data.entity.ImportBatchEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity

const val JOT_BILL_DATABASE_VERSION = 10

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
        ImportBatchEntity::class,
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
    abstract fun importBatchDao(): ImportBatchDao
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

    /**
     * 阶段四 T1：分离公开 SessionStatus 和内部 CheckoutJourneyState。
     *
     * 1. 新增 journey_state 列，先把旧 status 值（内部旅程状态）拷贝过去；
     * 2. 再把 status 列映射为 Native.d.ts 公开的 8 个 SessionStatus 值。
     */
    private val migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `checkout_sessions` ADD COLUMN `journey_state` TEXT"
            )
            // 把旧 status 值（内部旅程状态）拷贝到 journey_state
            db.execSQL(
                "UPDATE `checkout_sessions` SET `journey_state` = `status`"
            )
            // 按映射表把 status 列更新为公开 SessionStatus 值
            db.execSQL(
                """
                UPDATE `checkout_sessions` SET `status` = CASE
                    WHEN `status` IN ('CHECKOUT_DETECTED', 'OBSERVING') THEN 'OBSERVING'
                    WHEN `status` IN ('SNAPSHOT_BUFFERED', 'AI_TRIGGER_ARMED') THEN 'SNAPSHOT_BUFFERED'
                    WHEN `status` IN ('AI_PENDING', 'AI_REQUESTING') THEN 'AI_REQUESTING'
                    WHEN `status` IN (
                        'AI_COMPLETED', 'WAITING_PAYMENT',
                        'PAYMENT_CONFIRMED', 'WAITING_ENRICHMENT', 'NEEDS_CONFIRMATION'
                    ) THEN 'WAITING_ENRICHMENT'
                    WHEN `status` = 'AI_FAILED' THEN 'FAILED'
                    WHEN `status` IN ('AUTO_COMMITTED', 'COMPLETED') THEN 'COMPLETED'
                    WHEN `status` = 'DISMISSED' THEN 'CANCELLED'
                    WHEN `status` = 'CANCELLED' THEN 'CANCELLED'
                    WHEN `status` = 'FAILED' THEN 'FAILED'
                    WHEN `status` = 'EXPIRED' THEN 'EXPIRED'
                    ELSE 'WAITING_ENRICHMENT'
                END
                """
            )
        }
    }

    /**
     * 阶段四 T6：待确认交易新增 status 列，区分 pending / ignored 流转状态。
     * 忽略的记录保留供审计，不物理删除。
     */
    private val migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `pending_transactions` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'pending'"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_transactions_status` " +
                    "ON `pending_transactions` (`status`)"
            )
        }
    }

    /**
     * 阶段五 T1：规则引擎落地。
     *
     * 1. rules 表新增 hit_count（命中次数，默认 0）与 source（创建来源，默认 manual）；
     * 2. pending_transactions 新增 applied_rules（纯通知路径的规则命中记录，默认空对象）。
     * 已有行均取默认值，向前兼容。
     */
    private val migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `rules` ADD COLUMN `hit_count` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `rules` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'"
            )
            db.execSQL(
                "ALTER TABLE `pending_transactions` ADD COLUMN `applied_rules` TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }

    /**
     * 阶段五 T2：退款待确认关联原交易。
     * pending_transactions 新增 original_bill_id（可空，默认 NULL），向前兼容。
     */
    private val migration7To8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `pending_transactions` ADD COLUMN `original_bill_id` TEXT"
            )
        }
    }

    /**
     * 阶段五 T5：账单字段锁定存储。
     * bills 新增 field_locks（JSON 数组字符串，默认空数组），向前兼容：
     * 已有账单锁定集合为空，行为与锁定功能引入前完全一致。
     */
    private val migration8To9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `bills` ADD COLUMN `field_locks` TEXT NOT NULL DEFAULT '[]'"
            )
        }
    }

    /**
     * 阶段六 T1：导入数据层。
     *
     * 1. bills 新增 import_record_id（导入文件记录标识，可空，旧数据保持 NULL）；
     * 2. bills 建 (source_type, import_record_id) 索引，第 3 级去重查询使用；
     * 3. 新建 import_batches 表，file_hash 唯一索引承担文件级重复检测。
     */
    private val migration9To10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `bills` ADD COLUMN `import_record_id` TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bills_source_type_import_record_id` " +
                    "ON `bills` (`source_type`, `import_record_id`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `import_batches` (
                    `id` TEXT NOT NULL,
                    `file_hash` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `format` TEXT NOT NULL,
                    `format_version` TEXT NOT NULL,
                    `importer_version` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `total_records` INTEGER NOT NULL,
                    `valid_records` INTEGER NOT NULL,
                    `imported_count` INTEGER NOT NULL,
                    `merged_count` INTEGER NOT NULL,
                    `skipped_count` INTEGER NOT NULL,
                    `pending_count` INTEGER NOT NULL,
                    `failed_count` INTEGER NOT NULL,
                    `suspicious_count` INTEGER NOT NULL,
                    `account_unmatched_count` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `completed_at` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_import_batches_file_hash` " +
                    "ON `import_batches` (`file_hash`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_import_batches_status` " +
                    "ON `import_batches` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_import_batches_created_at` " +
                    "ON `import_batches` (`created_at`)"
            )
        }
    }

    val all: Array<Migration> = arrayOf(
        migration1To2,
        migration2To3,
        migration3To4,
        migration4To5,
        migration5To6,
        migration6To7,
        migration7To8,
        migration8To9,
        migration9To10
    )
}
