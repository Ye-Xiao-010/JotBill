package com.jotbill.app.data

import android.content.Context
import android.util.Log
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
        val passphraseResult = KeyStoreSecretStore(context).resolveDatabasePassphrase()
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val hadExistingDatabase = databaseFile.isFile && databaseFile.length() > 0L

        if (passphraseResult.regeneratedDueToKeyLoss && hadExistingDatabase) {
            val quarantineDir = migrationGuard.quarantineUnreadableDatabase()
            Log.w(
                TAG,
                "Keystore 密钥已丢失且存在无法解密的旧库，已隔离至 ${quarantineDir?.absolutePath} 并重建空库"
            )
            return openAndInitialize(context, passphraseResult.passphrase)
        }

        val backup = migrationGuard.backupCurrentDatabase()
        var database: JotBillDatabase? = null
        return try {
            openAndInitialize(context, passphraseResult.passphrase) { database = it }
        } catch (error: Exception) {
            runCatching { database?.close() }
            if (passphraseResult.freshlyProvisioned && hadExistingDatabase && isCipherOpenFailure(error)) {
                val quarantineDir = runCatching { migrationGuard.quarantineUnreadableDatabase() }.getOrNull()
                Log.w(
                    TAG,
                    "新生成口令无法解密既有加密库（多见于历史版本密钥失效残留），已隔离至 ${quarantineDir?.absolutePath} 并重建空库",
                    error
                )
                return openAndInitialize(context, passphraseResult.passphrase)
            }
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

    private fun openAndInitialize(
        context: Context,
        passphrase: ByteArray,
        onBuilt: (JotBillDatabase) -> Unit = {}
    ): JotBillDatabase {
        val factory = SupportFactory(passphrase)
        val database = Room.databaseBuilder(context, JotBillDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(*DatabaseMigrations.all)
            .build()
        onBuilt(database)

        // 等待迁移完成后再初始化默认数据，确保数据库交给调用方时已经可用。
        runBlocking(Dispatchers.IO) {
            val initializer = DefaultDataInitializer(database)
            initializer.initializeIfFirstRun()
            // 批 6:预设子分类单次播种(老库升级也生效,用户删除后不复活)
            initializer.ensurePresetSubCategories()
            // 2026-10-27:老库默认分类图标 emoji→lucide 一次性迁移(自定义分类不触碰)
            initializer.syncDefaultCategoryIcons()
            // 2026-11-13:预设子分类图标随父级升级(自定义/改名/自选图标不动)
            initializer.syncPresetSubCategoryIcons()
        }
        return database
    }

    internal fun isCipherOpenFailure(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            val message = cursor.message.orEmpty().lowercase()
            if (
                message.contains("file is not a database") ||
                message.contains("file is encrypted") ||
                message.contains("hmac check failed")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    private const val TAG = "JotBillDatabaseProvider"
}
