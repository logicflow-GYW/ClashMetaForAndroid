package com.github.kr328.clash.service.cfoptimizer.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.kr328.clash.common.log.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Worker password storage. The plaintext never touches generic preference files —
 * it is encrypted with a non-exportable Android Keystore AES/GCM key and the
 * ciphertext is kept in a dedicated preferences file excluded from backup.
 */
interface CfOptimizerSecretStore {
    /** Whether a password ciphertext exists (does not reveal the value). */
    fun passwordConfigured(): Boolean

    fun setPassword(plain: String)

    fun clearPassword()

    /**
     * Opaque accessor for the Worker client (module B/D consumers only — never the
     * settings UI). Returns plaintext or null when unset; on unrecoverable ciphertext
     * the blob is cleared (fail closed, user must re-enter) and null returned.
     */
    fun readPassword(): String?
}

/**
 * Keystore-backed implementation. The Keystore key is device-bound and non-exportable;
 * losing it (user clears credentials, device transfer) invalidates the ciphertext,
 * which is detected at decryption time and cleared instead of falling back to plaintext.
 */
class KeystoreCfOptimizerSecretStore(context: Context) : CfOptimizerSecretStore {
    private val preferences =
        context.getSharedPreferences(SECRET_FILE, Context.MODE_PRIVATE)

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    override fun passwordConfigured(): Boolean =
        preferences.contains(KEY_CIPHERTEXT)

    override fun setPassword(plain: String) {
        val encrypted = AesGcm.encrypt(loadKey(), plain.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        preferences.edit().putString(KEY_CIPHERTEXT, encoded).apply()
    }

    override fun clearPassword() {
        preferences.edit().remove(KEY_CIPHERTEXT).apply()
    }

    override fun readPassword(): String? {
        val encoded = preferences.getString(KEY_CIPHERTEXT, null) ?: return null

        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            clearPassword()
            return null
        }

        val plain = try {
            AesGcm.decrypt(loadKey(), blob)
        } catch (e: Exception) {
            // Keystore unavailable or key lost — treat as unrecoverable.
            null
        }

        if (plain == null) {
            Log.w("CF optimizer secret unrecoverable, cleared (re-entry required)")

            clearPassword()

            return null
        }

        return String(plain, Charsets.UTF_8)
    }

    companion object {
        /**
         * Dedicated file so it can be excluded from full backup / device transfer as a whole.
         */
        const val SECRET_FILE = "cfoptimizer_secret"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "cfoptimizer_secret_key"
        private const val KEY_CIPHERTEXT = "password_cipher"
    }
}
