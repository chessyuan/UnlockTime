package org.localwake.app.security

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class LocalUnlockCredentialStore(context: Context) {
    private val context = context.applicationContext
    private val legacy = LocalPatternCredentialStore(this.context)
    private val selection by lazy {
        UnlockCredentialFile(File(checkNotNull(this.context.noBackupFilesDir), "unlock_credential_v1"))
    }
    private val alias = "localwake_unlock_credential_v1"

    @Synchronized
    fun method(): UnlockMethod = credentialOperation {
        selection.read()?.method ?: UnlockMethod.PATTERN
    }

    @Synchronized
    fun status(): CredentialState = try {
        credentialOperation {
            val current = selection.read() ?: return@credentialOperation legacy.status()
            val payload = current.payload ?: return@credentialOperation CredentialState.EMPTY
            val key = existingKey()
            when (current.method) {
                UnlockMethod.PATTERN -> PatternCipher.decrypt(payload, key).fill(0)
                UnlockMethod.PIN, UnlockMethod.PASSWORD ->
                    TextCredentialCipher.decrypt(current.method, payload, key).fill('\u0000')
            }
            CredentialState.READY
        }
    } catch (e: CredentialFailure) {
        e.state
    }

    @Synchronized
    fun savePattern(pattern: IntArray) {
        try {
            credentialOperation {
                require(PatternValidator.valid(pattern))
                val encrypted = PatternCipher.encrypt(pattern, keyForSave())
                selection.write(UnlockMethod.PATTERN, encrypted)
            }
        } finally {
            pattern.fill(0)
        }
    }

    @Synchronized
    fun saveText(method: UnlockMethod, value: CharArray) {
        try {
            credentialOperation {
                require(TextCredentialValidator.valid(method, value))
                val encrypted = TextCredentialCipher.encrypt(method, value, keyForSave())
                selection.write(method, encrypted)
            }
        } finally {
            value.fill('\u0000')
        }
    }

    suspend fun <T> withPattern(block: suspend (IntArray) -> T): T {
        val pattern = synchronized(this) {
            credentialOperation {
                val current = selection.read() ?: return@credentialOperation legacy.load()
                PatternCipher.decrypt(current.payloadFor(UnlockMethod.PATTERN), existingKey())
            }
        }
        return try {
            block(pattern)
        } finally {
            pattern.fill(0)
        }
    }

    suspend fun <T> withText(expectedMethod: UnlockMethod, block: suspend (CharArray) -> T): T {
        val value = synchronized(this) {
            credentialOperation {
                require(expectedMethod != UnlockMethod.PATTERN)
                val current = selection.read() ?: throw CredentialFailure(CredentialState.EMPTY)
                TextCredentialCipher.decrypt(expectedMethod, current.payloadFor(expectedMethod), existingKey())
            }
        }
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    @Synchronized
    fun clear() = credentialOperation {
        selection.write(UnlockMethod.PATTERN, null)
        try {
            legacy.clear()
        } finally {
            keyStore().deleteEntry(alias)
        }
    }

    private fun UnlockCredentialFile.Record.payloadFor(method: UnlockMethod): PatternCipher.Payload {
        if (this.method != method) throw CredentialFailure(CredentialState.UNAVAILABLE)
        return payload ?: throw CredentialFailure(CredentialState.EMPTY)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(store: KeyStore = keyStore()): SecretKey =
        store.getKey(alias, null) as? SecretKey ?: throw CredentialFailure(CredentialState.KEY_MISSING)

    private fun keyForSave(): SecretKey {
        val current = selection.read()
        val store = keyStore()
        if (store.containsAlias(alias)) return existingKey(store)
        if (current?.payload != null) throw CredentialFailure(CredentialState.KEY_MISSING)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec)
        }.generateKey()
    }

    private inline fun <T> credentialOperation(block: () -> T): T = try {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            throw CredentialFailure(CredentialState.USER_LOCKED)
        }
        block()
    } catch (e: CredentialFailure) {
        throw e
    } catch (_: KeyPermanentlyInvalidatedException) {
        throw CredentialFailure(CredentialState.KEY_INVALIDATED)
    } catch (_: AEADBadTagException) {
        throw CredentialFailure(CredentialState.CORRUPT)
    } catch (_: IllegalArgumentException) {
        throw CredentialFailure(CredentialState.CORRUPT)
    } catch (_: Exception) {
        throw CredentialFailure(CredentialState.UNAVAILABLE)
    }
}
