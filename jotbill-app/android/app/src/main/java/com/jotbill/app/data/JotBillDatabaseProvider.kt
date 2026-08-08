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
    private const val DATABASE_NAME = "jotbill.db"

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
        val passphrase = KeyStoreSecretStore(context).getOrCreateDatabasePassphrase()
        val factory = SupportFactory(passphrase)

        val database = Room.databaseBuilder(context, JotBillDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(*DatabaseMigrations.all)
            .build()

        // 等待迁移完成后再初始化默认数据，确保数据库交给调用方时已经可用。
        runBlocking(Dispatchers.IO) {
            DefaultDataInitializer(database).initializeIfFirstRun()
        }
        return database
    }
}
