package com.jotbill.app.utils

import android.content.Context
import com.jotbill.app.data.JotBillDatabaseProvider
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

/**
 * 开发期数据库导出 —— 把 SQLCipher 加密库导出一份**明文副本**到外部目录，
 * 以便用本地 `sqlite3` 直接查（无需知道 passphrase）。
 *
 * ## 为什么需要
 *
 * `jotbill.db` 是 SQLCipher 加密的，passphrase 由 Android Keystore 派生、
 * **不可导出**（`KeyStoreSecretStore`），因此 App 外部无法用 sqlite3 打开。
 * 但开发期经常需要审计表内容（待确认条数、去重是否重复、规则是否生效…），
 * 没有这条路就只能靠肉眼点 UI 或临时加日志。
 *
 * ## 用法
 *
 * ```bash
 * adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-dump.db
 * sqlite3 jotbill-dump.db "SELECT * FROM pending_transactions;"
 * ```
 *
 * ## 安全边界（务必看清）
 *
 * - **仅在 `BuildConfig.DEBUG` 下运行**，release 构建直接 return。
 * - 导出的是**明文**数据库，落在 app 外部私有目录（`Android/data/<包名>/files`）。
 *   该目录需要 adb 或 root / 文件管理器才能访问，但**它不加密**——
 *   debug 包不要发布，不要把导出文件外传。
 * - 每次 App 启动覆盖一次，保证是最新快照。
 *
 * ## 实现要点
 *
 * 复用 `KeyStoreSecretStore` 拿到的同一个 passphrase，另开一条 SQLCipher 连接执行
 * `sqlcipher_export`。导出前先 `wal_checkpoint(TRUNCATE)`，否则 WAL 里的最新数据
 * 不会进导出文件（Room 默认开 WAL，这是最容易踩的坑）。
 */
object DiagDbDump {

    const val FILE_NAME = "jotbill-dump.db"

    private const val TAG = "JotBillDiag"

    /**
     * 导出明文副本，返回文件路径；未启用或失败返回 null。
     *
     * 阻塞调用（内部开线程），请勿在主线程直接调。
     */
    fun dumpBlocking(context: Context): String? {
        if (!com.jotbill.app.BuildConfig.DEBUG) return null
        val outDir = context.getExternalFilesDir(null) ?: return null
        outDir.mkdirs()
        val source = context.getDatabasePath(JotBillDatabaseProvider.DATABASE_NAME)
        if (!source.exists()) {
            JotBillLog.i(TAG, "数据库导出跳过：源库不存在 ${source.absolutePath}")
            return null
        }
        val target = File(outDir, FILE_NAME)
        return try {
            // 重开一个同名文件前必须删除，否则残留页会被当作已有库
            if (target.exists()) target.delete()
            File(target.absolutePath + "-journal").delete()

            SQLiteDatabase.loadLibs(context)
            // 复用 Room 已打开的连接（它持的就是同一个 passphrase），不另开连接 ——
            // SupportFactory 在本版 sqlcipher-android 上没有按路径取连接的公开 API，
            // 且另开连接要自行处理 WAL 可见性，易出错。
            val db = JotBillDatabaseProvider.get(context)
                .openHelper.writableDatabase as SQLiteDatabase

            // ★ WAL 收口：不 checkpoint 的话，最新数据留在 -wal 里，导出的是旧快照
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }

            db.execSQL("ATTACH DATABASE '${target.absolutePath}' AS plain KEY ''")
            try {
                db.rawQuery("SELECT sqlcipher_export('plain')", null).use { it.moveToFirst() }
            } finally {
                runCatching { db.execSQL("DETACH DATABASE plain") }
            }

            JotBillLog.i(
                TAG,
                "数据库已导出（明文，仅 debug）: ${target.absolutePath}, " +
                    "size=${target.length()} bytes"
            )
            target.absolutePath
        } catch (e: Exception) {
            JotBillLog.w(TAG, "数据库导出失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** App 启动时异步导出一次，避免拖慢冷启动。 */
    fun dumpAsync(context: Context) {
        if (!com.jotbill.app.BuildConfig.DEBUG) return
        val app = context.applicationContext
        Thread({ dumpBlocking(app) }, "jotbill-db-dump").apply { isDaemon = true }.start()
    }
}
