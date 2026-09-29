package com.github.kr328.clash.service.cfoptimizer.settings

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure AES/GCM encryption core — no Android dependencies, JVM-testable.
 *
 * Blob layout: 12-byte random IV || ciphertext (with GCM tag appended by Cipher).
 *
 * ponytail: no AAD, no key rotation, single-version blob | ceiling: key invalidation /
 * algorithm migration needed | upgrade trigger: device transfer restore fails repeatedly
 */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        return iv + cipher.doFinal(plain)
    }

    /**
     * Returns plaintext, or null when the blob cannot be authenticated/decrypted
     * (tampered, wrong key, truncated). Fails closed — never returns garbage.
     */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? {
        if (blob.size <= IV_LENGTH) return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val encrypted = blob.copyOfRange(IV_LENGTH, blob.size)

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))

            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            null
        }
    }
}
