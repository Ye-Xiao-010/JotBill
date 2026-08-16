package com.jotbill.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段七 T2：9→10 迁移结构验证。
 * 本测试必须运行在 Android instrumentation 环境；本地 JVM 不执行。
 */
@RunWith(AndroidJUnit4::class)
class Migration10Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JotBillDatabase::class.java
    )

    @Test
    fun migrate9To10AddsImportRecordIdAndImportBatches() {
        val databaseName = "migration-9-10.db"
        helper.createDatabase(databaseName, 9).close()
        val migration = DatabaseMigrations.all.single {
            it.startVersion == 9 && it.endVersion == 10
        }
        val database = helper.runMigrationsAndValidate(databaseName, 10, true, migration)
        database.query("PRAGMA table_info(`bills`)").use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(names.contains("import_record_id"))
        }
        database.query("PRAGMA index_list(`bills`)").use { cursor ->
            val indexes = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(indexes.contains("index_bills_source_type_import_record_id"))
        }
        database.query("SELECT COUNT(*) FROM `import_batches`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }
}
