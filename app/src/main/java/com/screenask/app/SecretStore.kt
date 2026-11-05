package com.screenask.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key encryption backed by the Android Keystore (AES/GCM, randomized IV).
 * Plain values never touch SharedPreferences and are excluded from backup.
 */
object SecretStore {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "screenask_api_key_v2"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(STORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            require(packed.size > 12) { "invalid payload" }
            val iv = packed.copyOfRange(0, 12)
            val body = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
