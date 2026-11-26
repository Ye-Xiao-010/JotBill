package com.jotbill.app.utils

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.MessageDigest.getInstance

class InstallApkModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        /** 签名读不到时的哨兵值。绝不可用任何"看起来像指纹"的常量，否则两侧同时
         * 读不到签名时会互相相等 ⇒ "是否同一签名"的检查恒通过。 */
        internal const val UNREADABLE_SIGNATURE = "__unreadable_signature__"

        internal fun signingFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int {
            return if (sdkInt >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        }
    }

    override fun getName(): String = "InstallApk"

    @ReactMethod
    fun install(filePath: String, promise: Promise) {
        try {
            val file = File(filePath)
            if (!file.isFile || file.length() <= 0L) {
                promise.reject("INVALID_ARGUMENT", "APK 文件不存在或为空")
                return
            }
            val context = reactApplicationContext
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val apkUri: Uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            context.startActivity(intent)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("INSTALL_APK_ERROR", error.message, error)
        }
    }

    /**
     * 阶段七 T5：安装前校验。
     * 包名/版本来自 PackageManager，SHA-256 流式计算，签名与当前应用同包名签名比较。
     */
    @ReactMethod
    fun verifyApk(filePath: String, expectedSha256: String?, promise: Promise) {
        try {
            val file = File(filePath)
            if (!file.isFile || file.length() <= 0L) {
                promise.reject("INVALID_ARGUMENT", "APK 文件不存在或为空")
                return
            }
            val context = reactApplicationContext
            val packageManager = context.packageManager
            val archive = getArchivePackageInfo(packageManager, filePath) ?: run {
                promise.reject("INVALID_APK", "无法读取 APK 包信息")
                return
            }
            val sha256 = sha256(file)
            val expected = expectedSha256?.trim()?.lowercase()
            val current = runCatching {
                getInstalledPackageInfo(packageManager, context.packageName)
            }.getOrNull()
            val signatureConsistent = if (current == null) {
                // ⚠️ 读不到当前包信息时**必须判为不一致**（fail-closed）。
                // 此前这里是 `true` —— 任何 APK 都会被判"签名一致"，而 JS 侧
                // useAppUpdate.ts:151-153 正是拿这个字段当安装闸门。
                false
            } else {
                archive.packageName == context.packageName &&
                    signatureHash(archive) != UNREADABLE_SIGNATURE &&
                    signatureHash(archive) == signatureHash(current)
            }
            val result = JSONObject().apply {
                put("packageName", archive.packageName)
                put("versionCode", archive.versionCode)
                put("versionName", archive.versionName ?: JSONObject.NULL)
                put("sha256", sha256)
                put("sha256Matched", expected == null || expected == sha256)
                put("signatureConsistent", signatureConsistent)
            }
            promise.resolve(result.toString())
        } catch (error: Exception) {
            promise.reject("VERIFY_APK_ERROR", error.message, error)
        }
    }

    /**
     * 取签名指纹摘要；**读不到签名时返回哨兵值而非某个真实指纹**。
     *
     * 此前返回常量 `"unsigned"` 有两个致命问题：
     * ① `GET_SIGNATURES` 自 API 28 已弃用，归档包在部分系统版本上 signatures 为 null；
     * ② 两侧都退化成 `"unsigned"` 时 `"unsigned" == "unsigned"` **恒成立**，
     * 于是"是否同一签名"这项检查对任何一对都通过。
     * 哨兵值使"读不到"无法与"指纹相同"混淆，配合上方的 fail-closed 判据，
     * 读不到签名一律判为不一致。
     */
    internal fun signatureHash(
        packageInfo: PackageInfo,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): String {
        val signatures = if (sdkInt >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.let { info ->
                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory
                }
            } ?: @Suppress("DEPRECATION") packageInfo.signatures
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (signatures.isNullOrEmpty()) return UNREADABLE_SIGNATURE
        return signatures.map { signature -> sha256Bytes(signature.toByteArray()) }
            .sorted()
            .joinToString(",")
    }

    private fun getArchivePackageInfo(
        packageManager: PackageManager,
        filePath: String
    ): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                filePath,
                PackageManager.PackageInfoFlags.of(signingFlags(Build.VERSION.SDK_INT).toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(filePath, signingFlags(Build.VERSION.SDK_INT))
        }
    }

    private fun getInstalledPackageInfo(
        packageManager: PackageManager,
        packageName: String
    ): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(signingFlags(Build.VERSION.SDK_INT).toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, signingFlags(Build.VERSION.SDK_INT))
        }
    }

    private fun sha256(file: File): String {
        val digest = getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return sha256Bytes(digest.digest())
    }

    private fun sha256Bytes(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
