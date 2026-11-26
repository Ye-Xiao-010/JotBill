package com.jotbill.app.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Keeps the database passphrase encrypted by a key held in Android Keystore.
 * The raw SQLCipher passphrase is never persisted in preferences.
 */
class KeyStoreSecretStore(context: Context) {
    data class DatabasePassphraseResult(
        val passphrase: ByteArray,
        /** True when the stored passphrase could not be decrypted because the Keystore key was lost/mismatched and a fresh passphrase had to be provisioned. */
        val regeneratedDueToKeyLoss: Boolean,
        /** True when no stored passphrase was reused (fresh install or recovery after preference reset). */
        val freshlyProvisioned: Boolean
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getOrCreateDatabasePassphrase(): ByteArray = resolveDatabasePassphrase().passphrase

    fun resolveDatabasePassphrase(): DatabasePassphraseResult {
        val stored = preferences.getString(DATABASE_PASSPHRASE_KEY, null)
        if (stored != null) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                Log.w(
                    TAG,
                    "Android Keystore 中已无 $KEY_ALIAS，但本地仍有数据库口令密文" +
                        "（典型成因：换机恢复 / 系统重置 Keystore）。将清除不可解密密文并重新生成口令。"
                )
                preferences.edit().clear().commit()
                generateKey(keyStore)
                return DatabasePassphraseResult(
                    passphrase = provisionAndPersistPassphrase(),
                    regeneratedDueToKeyLoss = true,
                    freshlyProvisioned = true
                )
            }
            return try {
                val decoded = Base64.decode(stored, Base64.NO_WRAP)
                DatabasePassphraseResult(
                    passphrase = decrypt(decoded),
                    regeneratedDueToKeyLoss = false,
                    freshlyProvisioned = false
                )
            } catch (error: AEADBadTagException) {
                Log.w(
                    TAG,
                    "本地数据库口令密文与当前 Keystore 密钥不匹配（AEADBadTagException），重新生成口令。",
                    error
                )
                preferences.edit().clear().commit()
                DatabasePassphraseResult(
                    passphrase = provisionAndPersistPassphrase(),
                    regeneratedDueToKeyLoss = true,
                    freshlyProvisioned = true
                )
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "本地数据库口令密文格式损坏，重新生成口令。", error)
                preferences.edit().clear().commit()
                DatabasePassphraseResult(
                    passphrase = provisionAndPersistPassphrase(),
                    regeneratedDueToKeyLoss = true,
                    freshlyProvisioned = true
                )
            }
        }

        return DatabasePassphraseResult(
            passphrase = provisionAndPersistPassphrase(),
            regeneratedDueToKeyLoss = false,
            freshlyProvisioned = true
        )
    }

    private fun provisionAndPersistPassphrase(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = encrypt(passphrase)
        check(
            preferences.edit()
                .putString(DATABASE_PASSPHRASE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist the encrypted database passphrase" }
        return passphrase
    }

    /** 使用 Android Keystore 加密需要持久化的敏感字符串。 */
    fun encryptSecret(value: String): String {
        return Base64.encodeToString(
            encrypt(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    /** 解密由 encryptSecret 保存的敏感字符串。 */
    fun decryptSecret(value: String): String {
        return String(
            decrypt(Base64.decode(value, Base64.NO_WRAP)),
            Charsets.UTF_8
        )
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText)
        return iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > GCM_IV_SIZE_BYTES) { "Stored database passphrase is invalid" }
        val iv = payload.copyOfRange(0, GCM_IV_SIZE_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // ⚠️ 这里此前**静默**生成新密钥。Keystore 密钥不随 Android 自动备份迁移
            // （只有 SharedPreferences 会），所以"换机恢复数据"是真实触发路径：
            // jotbill_secure_secrets 里的密文还在、密钥没了 → 静默生成新密钥 →
            // 后续 decrypt 必然抛 AEADBadTagException，而 decrypt /
            // getOrCreateDatabasePassphrase 都没有捕获，异常一路抛到
            // JotBillDatabaseProvider:36（该行在 try 之外，迁移回滚也不执行）
            // ⇒ 每次冷启动都崩，无任何自愈路径，用户只能卸载重装（丢数据）。
            //
            // 修：密钥缺失/不可用时**清掉坏密文并重新 provision**，让下次调用走
            // "没有密文 ⇒ 生成全新 passphrase"的正常分支。代价是旧库无法解密
            // （KeyStore 密钥本就无法恢复），但至少不会变成永久崩溃循环 ——
            // 抛一次的"密钥不可用"由上层决定如何提示，比无解的崩溃循环可用。
            val staleSecretsPresent = preferences.all.isNotEmpty()
            if (staleSecretsPresent) {
                Log.w(
                    "KeyStoreSecretStore",
                    "Android Keystore 中已无 $KEY_ALIAS，但本地仍有密文" +
                        "（典型成因：换机恢复 / 应用数据被系统迁移）。" +
                        "已清除无法解密的密文并重新生成密钥；" +
                        "旧加密数据（含加密库口令）将不可恢复。"
                )
                preferences.edit().clear().commit()
            }
            generateKey(keyStore)
        }

        return runCatching {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull() ?: error(
            "Android Keystore database key is unavailable"
        )
    }

    private fun generateKey(keyStore: KeyStore) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKey()
        // 生成后立刻回读确认可用，避免把"建了但取不出"的密钥写进偏好。
        require(keyStore.containsAlias(KEY_ALIAS)) {
            "Android Keystore key was generated but is not readable"
        }
    }

    private companion object {
        const val TAG = "KeyStoreSecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jotbill_database_key"
        const val PREFERENCES_NAME = "jotbill_secure_secrets"
        const val DATABASE_PASSPHRASE_KEY = "database_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val PASSPHRASE_SIZE_BYTES = 32
        const val GCM_IV_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BITS = 128
    }
}
