package com.jotbill.app.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import java.util.UUID

/**
 * 阶段六 T2：导入文件预处理组件（docs/tdd/10_phase6_tasks.md 第 3.5/3.8 节）。
 *
 * 职责：
 * 1. 把 SAF 选择结果复制到应用私有缓存目录下的单次导入子目录；
 * 2. 流式计算文件内容 SHA-256（不整读大文件入内存）；
 * 3. 探测文件类型与 CSV 编码（BOM → UTF-8 严格试解码 → GBK）；
 * 4. 解压 ZIP（含 zip4j 加密 ZIP），候选账单文件全部返回，由上层让用户选择。
 *
 * 安全边界：
 * - 密码只通过 [prepare] 参数进入本次调用栈，不写数据库、日志、剪贴板或崩溃上报；
 * - 解压产物只写应用私有缓存目录；调用方必须在完成/取消/失败/异常路径调用 [cleanup]；
 * - 本组件不写数据库、不弹窗、不保存文件内容。
 */
internal class ImportFilePreparer(
    private val context: Context
) {
    internal class ImportPreparationException(
        val code: String,
        override val message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    /** 单个候选账单文件：ZIP 可能解出多个候选，全部返回供上层选择。 */
    data class Candidate(
        val file: File,
        val fileName: String,
        val format: String,
        val encoding: String?
    )

    /** 预处理结果。 */
    data class PreparedImport(
        val originalPath: String,
        val originalDisplayName: String?,
        val fileHash: String,
        val format: String,
        val encrypted: Boolean,
        val tempDirectory: File,
        val candidates: List<Candidate>
    ) {
        val primaryCandidate: Candidate
            get() = candidates.first()
    }

    /**
     * 预处理导入文件。
     *
     * @param filePath SAF content/file URI 或本地文件路径；空路径直接报参数错误
     * @param password 加密 ZIP 密码；非加密文件忽略。调用结束后本类不保留该值。
     */
    fun prepare(filePath: String, password: String?): PreparedImport {
        if (filePath.isBlank()) {
            throw ImportPreparationException("INVALID_ARGUMENT", "导入文件路径不能为空")
        }
        val originalDisplayName = resolveDisplayName(filePath)
        val tempDirectory = createImportDirectory()
        return try {
            val originalName = originalDisplayName ?: File(filePath).name.takeIf { it.isNotBlank() }
            val copiedFile = File(tempDirectory, "source${extensionOf(originalName).takeIf { it.isNotBlank() } ?: ".bin"}")
            val digest = MessageDigest.getInstance("SHA-256")
            copyToTemp(filePath, copiedFile, digest)
            val fileHash = digest.digest().joinToString("") { "%02x".format(it) }
            val detectedFormat = detectFormat(copiedFile, originalName)
            val (candidates, encrypted) = when (detectedFormat) {
                FORMAT_ZIP -> unpackZip(copiedFile, tempDirectory, password)
                else -> listOf(prepareSingle(copiedFile, originalName, detectedFormat)) to false
            }
            PreparedImport(
                originalPath = filePath,
                originalDisplayName = originalName,
                fileHash = fileHash,
                format = detectedFormat,
                encrypted = encrypted,
                tempDirectory = tempDirectory,
                candidates = candidates
            )
        } catch (error: ImportPreparationException) {
            tempDirectory.deleteRecursively()
            throw error
        } catch (error: FileNotFoundException) {
            tempDirectory.deleteRecursively()
            throw ImportPreparationException("NOT_FOUND", "找不到导入文件", error)
        } catch (error: SecurityException) {
            tempDirectory.deleteRecursively()
            throw ImportPreparationException("PERMISSION_DENIED", "没有读取导入文件的权限", error)
        } catch (error: IOException) {
            tempDirectory.deleteRecursively()
            throw ImportPreparationException("IMPORT_IO_ERROR", "读取导入文件失败", error)
        } catch (error: NoSuchAlgorithmException) {
            tempDirectory.deleteRecursively()
            throw ImportPreparationException("IMPORT_IO_ERROR", "当前设备不支持 SHA-256", error)
        } catch (error: Exception) {
            tempDirectory.deleteRecursively()
            throw ImportPreparationException("IMPORT_IO_ERROR", "导入文件预处理失败", error)
        }
    }

    /** 递归清理预处理临时目录；幂等。 */
    fun cleanup(prepared: PreparedImport) {
        cleanup(prepared.tempDirectory)
    }

    fun cleanup(tempDirectory: File) {
        runCatching { tempDirectory.deleteRecursively() }
    }

    /** 候选清单摘要（供旧 importData 桥接与交接物展示；不暴露临时文件绝对路径）。 */
    fun candidateSummary(prepared: PreparedImport): JSONObject = JSONObject().apply {
        put("fileHash", prepared.fileHash)
        put("originalDisplayName", prepared.originalDisplayName ?: JSONObject.NULL)
        put("format", prepared.format)
        put("encrypted", prepared.encrypted)
        put(
            "candidates",
            JSONArray().apply {
                prepared.candidates.forEach { candidate ->
                    put(
                        JSONObject()
                            .put("fileName", candidate.fileName)
                            .put("format", candidate.format)
                            .put("encoding", candidate.encoding ?: JSONObject.NULL)
                    )
                }
            }
        )
    }

    private fun prepareSingle(file: File, originalName: String?, format: String): Candidate {
        val encoding = if (format == FORMAT_CSV) detectCsvEncoding(file) else null
        return Candidate(
            file = file,
            fileName = originalName?.takeIf { it.isNotBlank() } ?: file.name,
            format = format,
            encoding = encoding
        )
    }

    private fun unpackZip(
        zipFile: File,
        tempDirectory: File,
        password: String?
    ): Pair<List<Candidate>, Boolean> {
        val zip = try {
            ZipFile(zipFile)
        } catch (error: Exception) {
            throw ImportPreparationException("UNSUPPORTED_FORMAT", "压缩包无法打开或不是有效 ZIP 文件", error)
        }
        return try {
            val encrypted = zip.isEncrypted ||
                runCatching { zip.fileHeaders.any { it.isEncrypted } }.getOrDefault(false)
            if (encrypted) {
                if (password.isNullOrEmpty()) {
                    throw ImportPreparationException(
                        "IMPORT_PASSWORD_REQUIRED",
                        "该账单压缩包已加密，请输入解压密码"
                    )
                }
                zip.setPassword(password.toCharArray())
            }
            val outputDirectory = File(tempDirectory, "extracted").apply { mkdirs() }
            try {
                zip.extractAll(outputDirectory.absolutePath)
            } catch (error: ZipException) {
                if (encrypted) {
                    throw ImportPreparationException("IMPORT_PASSWORD_INVALID", "解压密码不正确", error)
                }
                throw ImportPreparationException("IMPORT_ZIP_INVALID", "压缩包解压失败", error)
            }
            val candidates = outputDirectory.walkTopDown()
                .filter { it.isFile && isCandidateFileName(it.name) }
                .map { candidateFile ->
                    val candidateFormat = detectFormat(candidateFile, candidateFile.name)
                    prepareSingle(candidateFile, candidateFile.name, candidateFormat)
                }
                .sortedBy { it.fileName.lowercase(Locale.ROOT) }
                .toList()
            if (candidates.isEmpty()) {
                throw ImportPreparationException(
                    "UNSUPPORTED_FORMAT",
                    "压缩包内没有可导入的微信/支付宝账单文件（CSV/XLSX）"
                )
            }
            candidates to encrypted
        } finally {
            runCatching { zip.close() }
        }
    }

    private fun createImportDirectory(): File {
        val root = File(context.cacheDir, IMPORT_CACHE_DIR)
        val directory = File(root, "import-${UUID.randomUUID()}")
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw ImportPreparationException("IMPORT_IO_ERROR", "无法创建导入临时目录")
        }
        return directory
    }

    private fun resolveDisplayName(filePath: String): String? {
        if (!filePath.startsWith("content:", ignoreCase = true)) return null
        return try {
            context.contentResolver.query(
                Uri.parse(filePath),
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToTemp(filePath: String, target: File, digest: MessageDigest) {
        val input = openInput(filePath)
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun openInput(filePath: String): InputStream {
        return if (filePath.startsWith("content:", ignoreCase = true) ||
            filePath.startsWith("file:", ignoreCase = true)
        ) {
            context.contentResolver.openInputStream(Uri.parse(filePath))
                ?: throw FileNotFoundException(filePath)
        } else {
            File(filePath).inputStream()
        }
    }

    private fun detectFormat(file: File, displayName: String?): String {
        val extension = extensionOf(displayName ?: file.name).lowercase(Locale.ROOT)
        val magic = readMagic(file)
        val zipMagic = magic != null && magic.size >= 4 &&
            magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            (magic[2] == 0x03.toByte() || magic[2] == 0x05.toByte() || magic[2] == 0x07.toByte())
        return when {
            extension == ".xlsx" -> FORMAT_XLSX
            extension == ".csv" -> FORMAT_CSV
            extension == ".zip" -> FORMAT_ZIP
            zipMagic && isXlsxArchive(file) -> FORMAT_XLSX
            zipMagic -> FORMAT_ZIP
            else -> throw ImportPreparationException(
                "UNSUPPORTED_FORMAT",
                "不支持的文件格式，仅支持微信/支付宝 CSV、XLSX 或 ZIP 压缩包"
            )
        }
    }

    private fun isXlsxArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.fileHeaders.any { it.fileName.equals("[Content_Types].xml", ignoreCase = true) }
        }
    }.getOrDefault(false)

    private fun isCandidateFileName(name: String): Boolean {
        if (name.startsWith("__MACOSX") || name.startsWith("~$")) return false
        val normalized = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return normalized.endsWith(".csv") || normalized.endsWith(".xlsx")
    }

    private fun extensionOf(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = fileName.lastIndexOf('.')
        return if (dot in 1 until fileName.lastIndex) fileName.substring(dot).lowercase(Locale.ROOT) else ""
    }

    private fun readMagic(file: File): ByteArray? {
        return try {
            FileInputStream(file).use { input ->
                val bytes = ByteArray(8)
                val read = input.read(bytes)
                if (read <= 0) null else bytes.copyOf(read)
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * 编码探测：BOM → UTF-8 严格试解码 → GBK。
     * 严格解码按流式分块执行，不把大文件整读进内存；跨块多字节由 decoder 状态承接。
     */
    private fun detectCsvEncoding(file: File): String {
        val bom = readMagic(file)
        if (bom != null && bom.size >= 3 &&
            bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()
        ) {
            return ENCODING_UTF8
        }
        return if (isStrictValidUtf8(file)) ENCODING_UTF8 else ENCODING_GBK
    }

    private fun isStrictValidUtf8(file: File): Boolean {
        val decoder = Charset.forName(ENCODING_UTF8).newDecoder().apply {
            onMalformedInput(CodingErrorAction.REPORT)
            onUnmappableCharacter(CodingErrorAction.REPORT)
        }
        return try {
            FileInputStream(file).use { input ->
                val reader = InputStreamReader(input, decoder)
                val chars = CharArray(ENCODING_PROBE_BUFFER_SIZE)
                while (reader.read(chars) >= 0) {
                    // 只读不存：仅验证整段字节都能被 UTF-8 严格解码。
                }
                true
            }
        } catch (_: CharacterCodingException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        const val FORMAT_CSV = "csv"
        const val FORMAT_XLSX = "xlsx"
        const val FORMAT_ZIP = "zip"
        const val ENCODING_UTF8 = "UTF-8"
        const val ENCODING_GBK = "GBK"

        private const val IMPORT_CACHE_DIR = "imports"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val ENCODING_PROBE_BUFFER_SIZE = 64 * 1024
    }
}
