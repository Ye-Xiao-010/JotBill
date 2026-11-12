package com.jotbill.app.data

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import androidx.room.Room
import com.jotbill.app.security.KeyStoreSecretStore
import com.jotbill.app.data.seed.DefaultDataInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 按进程复用数据库实例，并使用应用上下文避免持有 Activity。 */
object JotBillDatabaseProvider {
    const val DATABASE_NAME = "jotbill.db"

    @Volatile
    private var instance: JotBillDatabase? = null

    fun get(context: Context): JotBillDatabase {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    fun close() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }

    private fun build(context: Context): JotBillDatabase {
        SQLiteDatabase.loadLibs(context)
        val migrationGuard = DatabaseMigrationGuard(context)
        val backup = migrationGuard.backupCurrentDatabase()
        val passphrase = KeyStoreSecretStore(context).getOrCreateDatabasePassphrase()
        val factory = SupportFactory(passphrase)

        var database: JotBillDatabase? = null
        return try {
            database = Room.databaseBuilder(context, JotBillDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(factory)
                .addMigrations(*DatabaseMigrations.all)
                .build()

            // 等待迁移完成后再初始化默认数据，确保数据库交给调用方时已经可用。
            runBlocking(Dispatchers.IO) {
                val initializer = DefaultDataInitializer(database!!)
                initializer.initializeIfFirstRun()
                // 批 6:预设子分类单次播种(老库升级也生效,用户删除后不复活)
                initializer.ensurePresetSubCategories()
                // 2026-10-27:老库默认分类图标 emoji→lucide 一次性迁移(自定义分类不触碰)
                initializer.syncDefaultCategoryIcons()
            }
            database!!
        } catch (error: Exception) {
            runCatching { database?.close() }
            val restored = migrationGuard.restoreOnMigrationFailure(backup)
            throw IllegalStateException(
                if (restored) {
                    "数据库迁移失败，已恢复迁移前数据库，请先导出数据"
                } else {
                    "数据库迁移失败，且迁移前备份恢复未成功"
                },
                error
            )
        }
    }
}
