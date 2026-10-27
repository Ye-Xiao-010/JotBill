package com.jotbill.app.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断日志应用内导出 —— 让拿不到 adb 的用户也能把日志发给开发者。
 *
 * ## 为什么存在
 *
 * [JotBillLog] 落盘在 `Android/data/com.jotbill.app/files/`，Android 11+ 的
 * 文件管理器进不去该目录，取日志只有 adb 一条路 —— 而"远程用户弹窗不出现"
 * 类问题（2026-10-27 排查）恰恰拿不到 adb，排查只能靠猜。本类提供
 * "打包 → 系统分享面板"的应用内出口，用户可在微信/QQ 直接转发。
 *
 * ## 打包内容
 *
 * - `jotbill-diag.log`（主文件）与 `jotbill-diag.log.1`（轮转文件，存在才收）；
 * - `meta.txt`：设备厂商/机型/安卓版本/APP 版本/导出时间。门禁日志虽带
 *   `device=` 现场值，但 [JotBillLog.init] 入口行只有版本号没有机型，
 *   meta 让收到 zip 的人一眼看清来源设备。
 *
 * ## 隐私边界
 *
 * 日志内容 = logcat 原文（[JotBillLog] 不脱敏）。本项目硬不变式保证敏感页
 * （密码键盘/付款码）零提取、零落盘，故 zip 内**不含密码**，但含金额/商户等
 * 记账数据 —— RN 侧入口的确认弹窗须明示这一点。
 *
 * ## 时序边界
 *
 * 导出瞬间 [JotBillLog] 队列里可能还有未落盘的尾巴（每行即刷、延迟为毫秒级，
 * 不为此给写盘核心加 flush 机制）。用户是排查问题导出，关键日志早已落盘，
 * 接受该缺口。
 */
object DiagLogExporter {

    /** 打包产物目录（cacheDir 下，FileProvider 的 cache-path 已覆盖）。 */
    private const val EXPORT_DIR = "diag-export"

    private val timeFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 打包诊断日志为 zip，返回产物文件；一个日志文件都不存在时返回 null。
     *
     * 阻塞调用（磁盘 I/O），请勿在主线程直接调。
     */
    fun buildExportZip(context: Context): File? {
        val logsDir = context.getExternalFilesDir(null) ?: return null
        return buildExportZip(
            logsDir = logsDir,
            cacheDir = context.cacheDir,
            meta = deviceMeta(),
            manufacturer = Build.MANUFACTURER
        )
    }

    /** meta.txt 行（真机专用：读取 Build 与 BuildConfig，JVM 下 Build 字段为 null）。 */
    private fun deviceMeta(): List<String> = metaLines(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        versionName = com.jotbill.app.BuildConfig.VERSION_NAME,
        versionCode = com.jotbill.app.BuildConfig.VERSION_CODE,
        exportedAt = timeFmt.format(Date())
    )

    /**
     * 显式参数版：Context 版本的委托目标，JVM 单测经此测 zip 收包
     * （Context 与 Build 均不可在 JVM 构造，Build 字段在 JVM 下为 null）。
     *
     * @param logsDir 诊断日志所在目录（真机上为 `getExternalFilesDir(null)`）。
     * @param cacheDir 打包产物输出目录（真机上为 `cacheDir`）。
     * @param meta meta.txt 的行内容（真机走 [deviceMeta]）。
     * @param manufacturer zip 文件名中的厂商段（真机为 `Build.MANUFACTURER`）。
     */
    internal fun buildExportZip(
        logsDir: File,
        cacheDir: File,
        meta: List<String>,
        manufacturer: String
    ): File? {
        val exportDir = File(cacheDir, EXPORT_DIR)
        exportDir.deleteRecursively()
        exportDir.mkdirs()

        val sources = listOf(
            File(logsDir, JotBillLog.FILE_NAME),
            File(logsDir, "${JotBillLog.FILE_NAME}.1")
        ).filter { it.isFile && it.length() > 0L }
        if (sources.isEmpty()) return null

        val fileName = "jotbill-diag-$manufacturer-${timeFmt.format(Date())}.zip"
        val target = File(exportDir, fileName)
        ZipOutputStream(target.outputStream()).use { zip ->
            writeMeta(zip, meta)
            for (source in sources) {
                zip.putNextEntry(ZipEntry(source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    /** meta.txt 内容（可见于单测）。 */
    internal fun metaLines(
        manufacturer: String,
        model: String,
        sdkInt: Int,
        versionName: String,
        versionCode: Int,
        exportedAt: String
    ): List<String> = listOf(
        "exportedAt=$exportedAt",
        "device=$manufacturer $model",
        "sdk=$sdkInt",
        "app=${versionName}($versionCode)"
    )

    /** meta 先写：收到 zip 的人解压第一眼看到设备与版本。 */
    private fun writeMeta(zip: ZipOutputStream, meta: List<String>) {
        zip.putNextEntry(ZipEntry("meta.txt"))
        meta.forEach { zip.write((it + "\n").toByteArray(Charsets.UTF_8)) }
        zip.closeEntry()
    }

    /**
     * 拉起系统分享面板。MIME 用通配类型：zip 在部分 ROM 上被微信挑剔
     * （备份导入 `BackupModule.openBackupIntent` 同款规避写法）。
     */
    fun shareZip(context: Context, zip: File) {
        // authority 动态拼：与 `InstallApkModule` 一致。硬编码包名在未来加
        // `applicationIdSuffix`（如 debug 后缀）时会运行时抛 IllegalArgumentException。
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zip)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(share, "分享诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
