package com.jotbill.app.utils

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
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
            val archive = packageManager.getPackageArchiveInfo(
                filePath,
                PackageManager.GET_SIGNATURES
            ) ?: run {
                promise.reject("INVALID_APK", "无法读取 APK 包信息")
                return
            }
            val sha256 = sha256(file)
            val expected = expectedSha256?.trim()?.lowercase()
            val current = runCatching {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }.getOrNull()
            val signatureConsistent = if (current == null) {
                true
            } else {
                archive.packageName == context.packageName &&
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

    private fun signatureHash(packageInfo: PackageInfo): String {
        val signatures = packageInfo.signatures ?: return "unsigned"
        return signatures.map { signature -> sha256Bytes(signature.toByteArray()) }
            .sorted()
            .joinToString(",")
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
