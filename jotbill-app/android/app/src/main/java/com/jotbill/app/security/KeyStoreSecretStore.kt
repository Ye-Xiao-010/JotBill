package com.jotbill.app.security

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 使用 Android Keystore 保护数据库口令；SharedPreferences 只保存加密后的密文。
 * 原始 SQLCipher 口令不会写入偏好设置。
 */
class KeyStoreSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getOrCreateDatabasePassphrase(): ByteArray = synchronized(SECRET_LOCK) {
        val stored = preferences.getString(DATABASE_PASSPHRASE_KEY, null)
        val existingKey = getExistingKeyOrNull()
        if (stored != null) {
            val key = existingKey ?: error("Android Keystore database key is unavailable")
            val passphrase = decrypt(Base64.decode(stored, Base64.NO_WRAP), key)
            require(passphrase.size == PASSPHRASE_SIZE_BYTES) {
                "Stored database passphrase has invalid length"
            }
            return@synchronized passphrase
        }

        // 已有 Keystore 密钥却没有对应密文时，必须失败，不能生成新口令破坏原数据库的可用性。
        check(existingKey == null) { "Stored database passphrase is unavailable" }

        val passphrase = ByteArray(PASSPHRASE_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = encrypt(passphrase, getOrCreateKey())
        check(
            preferences.edit()
                .putString(DATABASE_PASSPHRASE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist the encrypted database passphrase" }
        passphrase
    }

    /** 使用 Android Keystore 加密需要持久化的敏感字符串。 */
    fun encryptSecret(value: String): String = synchronized(SECRET_LOCK) {
        Base64.encodeToString(
            encrypt(value.toByteArray(Charsets.UTF_8), getOrCreateKey()),
            Base64.NO_WRAP
        )
    }

    /** 解密由 encryptSecret 保存的敏感字符串。 */
    fun decryptSecret(value: String): String = synchronized(SECRET_LOCK) {
        val key = getExistingKeyOrNull() ?: error("Android Keystore database key is unavailable")
        String(
            decrypt(Base64.decode(value, Base64.NO_WRAP), key),
            Charsets.UTF_8
        )
    }

    private fun encrypt(plainText: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText)
        return iv + encrypted
    }

    private fun decrypt(payload: ByteArray, key: SecretKey): ByteArray {
        require(payload.size > GCM_IV_SIZE_BYTES) { "Stored database passphrase is invalid" }
        val iv = payload.copyOfRange(0, GCM_IV_SIZE_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_SIZE_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }

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
        // 直接使用生成结果，避免依赖生成前加载的 KeyStore 快照读取新密钥。
        return generator.generateKey()
    }

    private fun getExistingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return null
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: error("Android Keystore database key is unavailable")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jotbill_database_key"
        const val PREFERENCES_NAME = "jotbill_secure_secrets"
        const val DATABASE_PASSPHRASE_KEY = "database_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val PASSPHRASE_SIZE_BYTES = 32
        const val GCM_IV_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BITS = 128
        val SECRET_LOCK = Any()
    }
}
