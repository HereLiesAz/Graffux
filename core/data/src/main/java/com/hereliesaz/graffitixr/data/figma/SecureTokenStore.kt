package com.hereliesaz.graffitixr.data.figma

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores an API token encrypted with a hardware-backed AES key from the Android Keystore, keeping
 * the ciphertext in ordinary SharedPreferences. A Figma personal access token grants full access to
 * the user's Figma account, so it must not sit in plaintext prefs the way the app's other settings
 * legitimately do — and the key itself never leaves the Keystore, so a prefs file lifted off the
 * device is useless on its own.
 *
 * Deliberately not `androidx.security:security-crypto`: that library is deprecated, and this needs
 * so little of it that the Keystore primitives are the smaller dependency-free answer.
 */
class SecureTokenStore(private val context: Context, private val prefsName: String = PREFS) {

    private val prefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun save(key: String, value: String) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray())
        // The GCM IV is generated per encryption and is not secret, but IS required to decrypt, so
        // it's stored alongside the ciphertext rather than derived or reused.
        prefs.edit()
            .putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(key), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** Returns null when nothing is stored, or when the ciphertext can no longer be decrypted. */
    fun load(key: String): String? {
        val encrypted = prefs.getString(key, null) ?: return null
        val iv = prefs.getString(ivKey(key), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
        }.getOrElse {
            // The Keystore key is invalidated by events outside our control (restore to a new device,
            // some lock-screen changes). Treat that as "not signed in" and drop the dead ciphertext
            // rather than throwing on every read forever.
            clear(key)
            null
        }
    }

    fun clear(key: String) {
        prefs.edit().remove(key).remove(ivKey(key)).apply()
    }

    private fun ivKey(key: String) = "$key$IV_SUFFIX"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // No setUserAuthenticationRequired: the token is read on a background thread
                    // during import, where prompting for the lock screen isn't possible.
                    .build()
            )
        }.generateKey()
    }

    companion object {
        const val KEY_FIGMA_TOKEN = "figma_token"

        private const val PREFS = "secure_tokens"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "graffux_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_SUFFIX = "_iv"
    }
}
