package com.jotbill.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * [DiagLogExporter] 的 JVM 单测。
 *
 * Android 依赖（Context/Build）不可在 JVM 构造，故可测面收敛为两个纯逻辑点：
 * meta 行内容（契约锁定）与 zip 收包（`@TempDir` 真文件）。文件名格式由
 * [fileNameFormat] 用固定时间戳等价校验。
 */
class DiagLogExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun metaLinesCoverAllFields() {
        val lines = DiagLogExporter.metaLines(
            manufacturer = "Xiaomi",
            model = "2210132C",
            sdkInt = 34,
            versionName = "0.0.98",
            versionCode = 94,
            exportedAt = "2026-10-27 12:00:00"
        )
        assertEquals(
            listOf(
                "exportedAt=2026-10-27 12:00:00",
                "device=Xiaomi 2210132C",
                "sdk=34",
                "app=0.0.98(94)"
            ),
            lines
        )
    }

    @Test
    fun fileNameFormatMatchesPattern() {
        // 对 buildExportZip 的**真实产物名**断言（2026-10-27 审查修正：原版拿
        // 手写字面量匹配 regex，是同义反复 —— 实现改了命名测试仍绿）。
        // 命名形状被 RN toast 与人工核对依赖：前缀、厂商段、`yyyyMMdd-HHmmss`
        // 时间戳缺一不可，防止分隔符/前缀被无意改动。
        val filesDir = tempFolder.newFolder("files")
        File(filesDir, JotBillLog.FILE_NAME).writeText("log\n")
        val zip = DiagLogExporter.buildExportZip(
            logsDir = filesDir,
            cacheDir = tempFolder.root,
            meta = emptyList(),
            manufacturer = "Xiaomi"
        )
        assertNotNull(zip)
        assertTrue(
            "文件名须为 jotbill-diag-<厂商>-<yyyyMMdd-HHmmss>.zip，实际: ${zip!!.name}",
            zip.name.matches(Regex("jotbill-diag-Xiaomi-\\d{8}-\\d{6}\\.zip"))
        )
    }

    @Test
    fun buildExportZipUsesRotatedLogWhenMainMissing() {
        // 主日志缺失但轮转文件存在：.1 仍应被打包（旋转刚发生后主文件尚未
        // 写新内容的窗口期，丢掉 .1 就是丢掉全部证据）。
        val filesDir = tempFolder.newFolder("files")
        File(filesDir, "${JotBillLog.FILE_NAME}.1").writeText("rotated only\n")
        val zip = DiagLogExporter.buildExportZip(
            logsDir = filesDir,
            cacheDir = tempFolder.root,
            meta = emptyList(),
            manufacturer = "X"
        )
        assertNotNull(zip)
        ZipFile(zip!!).use { zf ->
            val main = zf.getInputStream(zf.getEntry("${JotBillLog.FILE_NAME}.1")).bufferedReader().readText()
            assertTrue(main.contains("rotated only"))
        }
    }

    @Test
    fun buildExportZipSkipsEmptyRotatedLog() {
        // 轮转文件 0 字节、主日志有效：应跳过 .1 只打包主文件。
        val filesDir = tempFolder.newFolder("files")
        File(filesDir, JotBillLog.FILE_NAME).writeText("main\n")
        File(filesDir, "${JotBillLog.FILE_NAME}.1").writeText("")
        val zip = DiagLogExporter.buildExportZip(
            logsDir = filesDir,
            cacheDir = tempFolder.root,
            meta = emptyList(),
            manufacturer = "X"
        )
        assertNotNull(zip)
        ZipFile(zip!!).use { zf ->
            assertEquals(
                listOf(JotBillLog.FILE_NAME, "meta.txt"),
                zf.entries().toList().map { it.name }.sorted()
            )
        }
    }

    @Test
    fun buildExportZipPacksLogAndMeta() {
        val filesDir = tempFolder.newFolder("files")
        File(filesDir, JotBillLog.FILE_NAME).writeText("I/JotBillDiag: main log\n")
        File(filesDir, "${JotBillLog.FILE_NAME}.1").writeText("I/JotBillDiag: rotated log\n")

        val zip = DiagLogExporter.buildExportZip(
            logsDir = filesDir,
            cacheDir = tempFolder.root,
            meta = listOf("exportedAt=x", "device=test"),
            manufacturer = "Xiaomi"
        )
        assertNotNull(zip)
        assertTrue(zip!!.name.startsWith("jotbill-diag-Xiaomi-"))
        ZipFile(zip).use { zf ->
            assertEquals(setOf("meta.txt", JotBillLog.FILE_NAME, "${JotBillLog.FILE_NAME}.1"), zf.entries().toList().map { it.name }.toSet())
            val meta = zf.getInputStream(zf.getEntry("meta.txt")).bufferedReader().readText()
            assertEquals(listOf("exportedAt=x", "device=test"), meta.lines().dropLast(1))
            val main = zf.getInputStream(zf.getEntry(JotBillLog.FILE_NAME)).bufferedReader().readText()
            assertTrue(main.contains("main log"))
        }
    }

    @Test
    fun buildExportZipReturnsNullWhenNoLog() {
        val filesDir = tempFolder.newFolder("files") // 目录存在但无日志文件
        assertNull(
            DiagLogExporter.buildExportZip(filesDir, tempFolder.root, meta = emptyList(), manufacturer = "X")
        )
    }

    @Test
    fun buildExportZipSkipsEmptyLog() {
        val filesDir = tempFolder.newFolder("files")
        // 0 字节日志不算有效日志：导出空 zip 只会浪费用户一轮"发了但没用"。
        File(filesDir, JotBillLog.FILE_NAME).writeText("")
        assertNull(
            DiagLogExporter.buildExportZip(filesDir, tempFolder.root, meta = emptyList(), manufacturer = "X")
        )
    }
}
