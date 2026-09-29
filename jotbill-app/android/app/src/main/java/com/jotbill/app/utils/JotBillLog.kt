package com.jotbill.app.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 诊断日志落盘 —— 让真机回归不必再抓几十 MB 的 logcat。
 *
 * ## 为什么存在
 *
 * `docs/qa/device/` 现有证据累计 **153 MB**，单文件最大 85.6 MB；
 * `INCIDENT_2026-09-15_git_corruption.md` 记录的安全软件盯仓事故即源于此。
 * 根因不是日志太多，而是 **采集时没做 tag 过滤** —— 把整个 logcat 倒进了仓库。
 *
 * 本类的做法：**在 App 内把日志直接写成文件**，一次回归产出一个可直读的小文件。
 *
 * ## 用法
 *
 * 调用点替换：`Log.i(...)` → `JotBillLog.i(...)`（签名一致，行为不变）。
 * 内部**仍然先调 `android.util.Log`**，因此 logcat 行为、崩溃栈、性能特征全部不变 ——
 * 本类只是**加了一个并行的落盘出口**。
 *
 * ## 取文件
 *
 * ```
 * adb pull /sdcard/Android/data/com.jotbill.app/files/jotbill-diag.log
 * ```
 * 或直接在手机文件管理器里进 `Android/data/com.jotbill.app/files/` 复制。
 * 无需任何权限、无需 UI 入口。
 *
 * ## 边界（明确不做的事）
 *
 * - **不按 tag / 级别过滤** —— 调用点自己决定记什么。
 * - **不脱敏** —— 写进去就是 logcat 里的原文。
 * - **只在 debug 构建落盘**（`BuildConfig.DEBUG`）—— 避免给正式包引入一个持续增长的文件。
 * - **logcat 仍然保留** —— 崩溃 / ANR / 系统侧拒绝（如 MIUI `Reject RestartService`）
 *   只有 logcat 拿得到，那是本文件的**补充**而非替代。
 */
object JotBillLog {

    /** 落盘文件名（外部私有目录，无需权限）。 */
    const val FILE_NAME = "jotbill-diag.log"

    /** 单文件上限，超过后重命名为 `.1` 重新开始 —— 只为不让文件无限增长，不丢弃任何一轮。 */
    private const val MAX_BYTES = 10L * 1024 * 1024

    private val queue = LinkedBlockingQueue<String>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var writer: FileWriter? = null

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var written = 0L

    @Volatile
    private var enabled = false

    /**
     * 在 [MainApplication.onCreate] 最早期调用。
     *
     * 启动线程与写盘线程分离：日志写入**永不阻塞**调用方 —— 无障碍回调在主线程，
     * 在那里做文件 I/O 会造成卡顿甚至 ANR。
     */
    fun init(context: Context) {
        if (!com.jotbill.app.BuildConfig.DEBUG) return
        val dir = context.getExternalFilesDir(null) ?: return
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        try {
            writer = FileWriter(file, /* append = */ true)
            logFile = file
            written = file.length()
        } catch (e: Exception) {
            Log.w(TAG, "诊断日志文件不可写：${e.message}")
            return
        }
        enabled = true

        Thread({ drainLoop() }, "jotbill-diag").apply { isDaemon = true }.start()

        i(TAG, "=== 诊断日志开始 ${timeFmt.format(Date())} " +
            "versionName=${com.jotbill.app.BuildConfig.VERSION_NAME} " +
            "build=${com.jotbill.app.BuildConfig.VERSION_CODE} ===")
        Log.i(TAG, "诊断日志落盘已启用：${file.absolutePath}")
    }

    /** 当前日志文件绝对路径；未启用时返回 null。 */
    fun currentFilePath(): String? = logFile?.absolutePath

    // ---- 与 android.util.Log 同签名的转发口 ----

    fun v(tag: String, message: String) { Log.v(tag, message); sink("V", tag, message, null) }
    fun d(tag: String, message: String) { Log.d(tag, message); sink("D", tag, message, null) }
    fun i(tag: String, message: String) { Log.i(tag, message); sink("I", tag, message, null) }
    fun w(tag: String, message: String) { Log.w(tag, message); sink("W", tag, message, null) }
    fun w(tag: String, message: String, tr: Throwable?) {
        Log.w(tag, message, tr); sink("W", tag, message, tr)
    }
    fun e(tag: String, message: String) { Log.e(tag, message); sink("E", tag, message, null) }
    fun e(tag: String, message: String, tr: Throwable?) {
        Log.e(tag, message, tr); sink("E", tag, message, tr)
    }

    /** 在文件里插一条分隔标记，便于分析时定位"这一轮从哪开始"。 */
    fun mark(note: String) {
        i(TAG, "---------------- $note ----------------")
    }

    // ---- 内部 ----

    private fun sink(level: String, tag: String, message: String, tr: Throwable?) {
        if (!enabled) return
        val line = buildString {
            append(timeFmt.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (tr != null) {
                append('\n')
                append(StringWriter().also { tr.printStackTrace(PrintWriter(it)) }.toString().trimEnd())
            }
        }
        queue.offer(line)
    }

    private fun drainLoop() {
        while (true) {
            val line = queue.take()
            synchronized(this) {
                val w = writer ?: return
                try {
                    if (written > MAX_BYTES) rotate()
                    w.write(line)
                    w.write("\n")
                    // 每行即刷：诊断日志的价值就在于崩溃/断连前的最后几行不能丢。
                    w.flush()
                    written += line.length + 1
                } catch (e: Exception) {
                    Log.w(TAG, "诊断日志写入失败：${e.message}")
                }
            }
        }
    }

    private fun rotate() {
        try {
            writer?.close()
            val f = logFile ?: return
            val old = File(f.parentFile, "$FILE_NAME.1")
            old.delete()
            f.renameTo(old)
            writer = FileWriter(f, /* append = */ false)
            written = 0
        } catch (e: Exception) {
            Log.w(TAG, "诊断日志轮转失败：${e.message}")
        }
    }

    private const val TAG = "JotBillDiag"
}
