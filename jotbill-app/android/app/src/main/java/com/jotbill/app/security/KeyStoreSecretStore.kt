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
 * Keeps the database passphrase encrypted by a key held in Android Keystore.
 * The raw SQLCipher passphrase is never persisted in preferences.
 */
class KeyStoreSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val stored = preferences.getString(DATABASE_PASSPHRASE_KEY, null)
        if (stored != null) {
            return decrypt(Base64.decode(stored, Base64.NO_WRAP))
        }

        val passphrase = ByteArray(PASSPHRASE_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = encrypt(passphrase)
        check(
            preferences.edit()
                .putString(DATABASE_PASSPHRASE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist the encrypted database passphrase" }
        return passphrase
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
    }
}
