// Adapted from OpenClaw LocalPinCredentialStore. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.security

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

enum class CredentialState { EMPTY, READY, USER_LOCKED, KEY_MISSING, KEY_INVALIDATED, CORRUPT, UNAVAILABLE }
class CredentialFailure(val state: CredentialState) : Exception(state.name)

class LocalPatternCredentialStore(context: Context) {
    private val context = context.applicationContext
    private val alias = "localwake_pattern_v1"
    private fun prefs() = context.getSharedPreferences("pattern_cipher", Context.MODE_PRIVATE)
    private fun requireUserUnlocked() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) throw CredentialFailure(CredentialState.USER_LOCKED)
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey(): SecretKey = keyStore().getKey(alias, null) as? SecretKey
        ?: throw CredentialFailure(CredentialState.KEY_MISSING)

    fun status(): CredentialState = try {
        load().also { it.fill(0) }; CredentialState.READY
    } catch (e: CredentialFailure) { e.state }

    @Synchronized fun savePattern(pattern: IntArray) {
        try {
            requireUserUnlocked()
            require(PatternValidator.valid(pattern))
            val store = keyStore()
            val key = if (store.containsAlias(alias)) existingKey() else {
                // Never replace a missing key behind an existing ciphertext.
                if (!PatternCipher.mayCreateKey(prefs().contains("cipher"), prefs().contains("iv"))) throw CredentialFailure(CredentialState.KEY_MISSING)
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false).build())
                }.generateKey()
            }
            val encrypted = PatternCipher.encrypt(pattern, key)
            if (!prefs().edit().putString("iv", Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
                    .putString("cipher", Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)).commit()) {
                throw CredentialFailure(CredentialState.UNAVAILABLE)
            }
        } catch (e: Exception) { throw classify(e) }
        finally { pattern.fill(0) }
    }

    internal fun load(): IntArray = try {
        requireUserUnlocked()
        val p = prefs()
        val cipher = p.getString("cipher", null)
        val iv = p.getString("iv", null)
        if (cipher == null && iv == null) throw CredentialFailure(CredentialState.EMPTY)
        if (cipher == null || iv == null) throw CredentialFailure(CredentialState.CORRUPT)
        PatternCipher.decrypt(PatternCipher.Payload(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(cipher, Base64.NO_WRAP)), existingKey())
    } catch (e: Exception) { throw classify(e) }

    suspend fun <T> withPattern(block: suspend (IntArray) -> T): T {
        val pattern = load()
        return try { block(pattern) } finally { pattern.fill(0) }
    }

    @Synchronized fun clear() {
        requireUserUnlocked()
        keyStore().deleteEntry(alias)
        if (!prefs().edit().clear().commit()) throw CredentialFailure(CredentialState.UNAVAILABLE)
    }

    private fun classify(e: Exception): CredentialFailure = when (e) {
        is CredentialFailure -> e
        is KeyPermanentlyInvalidatedException -> CredentialFailure(CredentialState.KEY_INVALIDATED)
        is AEADBadTagException, is IllegalArgumentException -> CredentialFailure(CredentialState.CORRUPT)
        else -> CredentialFailure(CredentialState.UNAVAILABLE)
    }
}
