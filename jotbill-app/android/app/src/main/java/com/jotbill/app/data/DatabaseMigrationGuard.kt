package com.jotbill.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 阶段七 T3：数据库迁移失败回滚保护。
 *
 * 打开 Room 前对 SQLCipher 加密数据库文件做字节级备份；迁移/初始化异常时
 * 关闭半开实例并恢复旧文件，不读取或导出明文。备份只保留最近一份。
 */
internal class DatabaseMigrationGuard(
    context: Context
) {
    data class Backup(val directory: File)

    private val databaseFile = context.getDatabasePath(JotBillDatabaseProvider.DATABASE_NAME)
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val backupDirectory = File(context.filesDir, BACKUP_RELATIVE_PATH)

    /** 备份当前数据库及 WAL/SHM/JOURNAL 附属文件；文件不存在时跳过。 */
    fun backupCurrentDatabase(): Backup {
        val target = File(backupDirectory, CURRENT_DIRECTORY)
        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("无法清理旧的迁移前备份目录")
        }
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("无法创建迁移前备份目录")
        }
        for (fileName in DATABASE_FILE_NAMES) {
            val source = File(databaseFile.parentFile, fileName)
            if (!source.isFile) continue
            copyFile(source, File(target, fileName))
        }
        return Backup(target)
    }

    /** 迁移失败：删除半迁移文件并恢复旧文件；只有备份完整才执行替换。 */
    fun restoreOnMigrationFailure(backup: Backup): Boolean {
        if (!backup.directory.isDirectory) return false
        var restored = false
        try {
            for (fileName in DATABASE_FILE_NAMES) {
                val current = File(databaseFile.parentFile, fileName)
                val saved = File(backup.directory, fileName)
                if (saved.isFile) {
                    if (current.exists() && !current.delete()) {
                        throw IOException("无法删除半迁移数据库文件: $fileName")
                    }
                    copyFile(saved, current)
                    restored = true
                } else if (current.exists()) {
                    if (!current.delete()) {
                        throw IOException("无法清理半迁移附属文件: $fileName")
                    }
                }
            }
            if (restored) {
                preferences.edit()
                    .putLong(LAST_RECOVERED_AT_KEY, System.currentTimeMillis())
                    .apply()
            }
            return restored
        } catch (_: Exception) {
            return false
        }
    }

    fun lastRecoveredAt(): Long =
        preferences.getLong(LAST_RECOVERED_AT_KEY, 0L).takeIf { it > 0L } ?: 0L

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    companion object {
        const val PREFERENCES_NAME = "jotbill_database_migration"
        private const val LAST_RECOVERED_AT_KEY = "last_recovered_at"
        private const val BACKUP_RELATIVE_PATH = "database_migration_backup"
        private const val CURRENT_DIRECTORY = "last"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private val DATABASE_FILE_NAMES = listOf(
            "${JotBillDatabaseProvider.DATABASE_NAME}",
            "${JotBillDatabaseProvider.DATABASE_NAME}-wal",
            "${JotBillDatabaseProvider.DATABASE_NAME}-shm",
            "${JotBillDatabaseProvider.DATABASE_NAME}-journal"
        )
    }
}
