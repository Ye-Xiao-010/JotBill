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
 * - **所有构建一律落盘**（2026-10-25 起）—— 原先只在 debug 落盘，结果 release 包
 *   出现"弹窗未出"时完全无日志可查（当日真机实miss）。文件增长由 10MB×2 旋转兜底。
 * - **logcat 仍然保留** —— 崩溃 / ANR / 系统侧拒绝（如 MIUI `Reject RestartService`）
 *   只有 logcat 拿得到，那是本文件的**补充**而非替代。
 */
object JotBillLog {

    /** 落盘文件名（外部私有目录，无需权限）。 */
    const val FILE_NAME = "jotbill-diag.log"

    /** 单文件上限，超过后重命名为 `.1` 重新开始 —— 只为不让文件无限增长，不丢弃任何一轮。 */
    /** 单文件上限，按**落盘字节数**计（见 drainLoop 内的 UTF-8 字节计数）。 */
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
        // 所有构建一律落盘（2026-10-25）：release 出现"弹窗未出"类问题时必须有日志可查
        // （当日 clean 安装后微信/支付宝双 miss，因 debug-only 落盘完全无证据）。
        // 文件增长由 MAX_BYTES 旋转兜底（10MB × 2）。
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

    // ⚠️ 2026-10-12 删除 `currentFilePath()`：零调用点（诊断日志路径由
// `verify-lean-l1.ps1` 硬编码 `Android/data/com.jotbill.app/files/` 推得，
// 没有任何运行时代码需要问"文件在哪"）。

    // ---- 与 android.util.Log 同签名的转发口 ----

    // ⚠️ 2026-10-12 删除 `v()`：零调用点。同族的 d/i/w/e 有 200+ 处调用，
    // 唯独 verbose 一处未用 —— 它从一开始就没被接进任何链路。

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

    /** ⚠️ 2026-10-12 删除 `mark()`：零调用点。分隔标记的实际用法是直接在日志里写
 * `================` 文案，没有调用方需要这个转发。 */

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
                if (writer == null) return
                try {
                    // ⚠️ 轮转必须在写之前，且**不得**把 writer 缓存进局部变量后再轮转。
                    // 旧实现 `val w = writer ?: return` 先取实例、`rotate()` 内部 close 并
                    // 换新实例、随后仍写那个已关闭的旧实例 ⇒ 抛 Stream Closed；
                    // 而 written 已被 rotate() 置 0 且因异常不再累加 ⇒
                    // 永远不再满足轮转条件 ⇒ **本进程诊断日志永久失效**。
                    // 真机已见 `jotbill-diag.log.1` 达 11.5MB（worklog/2026-09-30），
                    // 轮转确实发生过，之后的 .log 就是空的 —— 而验收的唯一证据出口
                    // 就是这个文件，等于整轮验收拿不到任何证据。
                    if (written > MAX_BYTES) rotate()
                    val w = writer ?: return
                    w.write(line)
                    w.write("\n")
                    // 每行即刷：诊断日志的价值就在于崩溃/断连前的最后几行不能丢。
                    w.flush()
                    // 按 UTF-8 字节数计，而非 String.length（UTF-16 码元）。
                    // 中文日志实际按 3 字节/字落盘，用 length 会让文件达到标称的
                    // 2~3 倍（实测 .log.1 已 11.5MB，而上限标称 10MB）。
                    written += line.toByteArray(Charsets.UTF_8).size + 1
                } catch (e: Exception) {
                    Log.w(TAG, "诊断日志写入失败：${e.message}")
                    // 写入失败后尝试重开一次：句柄可能已因轮转/外部删文件而失效。
                    // 不重开的话，一次失败就永久静音（见上方注释的历史故障）。
                    reopenWriterQuietly()
                }
            }
        }
    }

    /**
     * 重新打开日志文件句柄（失败仅记 logcat，绝不抛出）。
     *
     * 覆盖两种真实故障：① 轮转后旧句柄已关闭；② 用户在进程运行中删除了日志文件
     * （回归脚本指导"先删旧文件再跑"，若在进程启动后删，句柄指向已删除的 inode，
     * 写入静默消失）。
     */
    private fun reopenWriterQuietly() {
        try {
            writer?.let { runCatching { it.close() } }
            writer = FileWriter(requireNotNull(logFile), /* append = */ true)
            written = 0L
        } catch (e: Exception) {
            Log.w(TAG, "诊断日志句柄重建失败：${e.message}")
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
            // 轮转中途失败时 writer 可能已被 close，此处必须重建，
            // 否则本进程再无任何可写的句柄。
            reopenWriterQuietly()
        }
    }

    private const val TAG = "JotBillDiag"
}
