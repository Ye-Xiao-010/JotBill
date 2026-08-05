package com.jotbill.app.data

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import androidx.room.Room
import com.jotbill.app.security.KeyStoreSecretStore

/**
 * Process-wide database holder. The application context prevents Activity leaks.
 */
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

        return Room.databaseBuilder(context, JotBillDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations(*DatabaseMigrations.all)
            .build()
    }
}
