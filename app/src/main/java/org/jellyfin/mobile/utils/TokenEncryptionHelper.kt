package org.jellyfin.mobile.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TokenEncryptionHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jellyfin_auth_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val KEY_SIZE = 256
    private const val PREFIX = "enc:v1:"

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    fun encrypt(token: String?): String? {
        if (token.isNullOrEmpty()) return token
        return try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            val combined = ByteBuffer.allocate(iv.size + cipherText.size)
                .put(iv)
                .put(cipherText)
                .array()

            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "Failed to encrypt token, storing plaintext")
            token
        }
    }

    fun decrypt(token: String?): String? {
        if (token.isNullOrEmpty() || !token.startsWith(PREFIX)) return token
        return try {
            val base64Data = token.substring(PREFIX.length)
            val combined = Base64.decode(base64Data, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(combined)
            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)
            val cipherText = ByteArray(buffer.remaining())
            buffer.get(cipherText)

            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(cipherText)

            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt token")
            token
        }
    }
}
