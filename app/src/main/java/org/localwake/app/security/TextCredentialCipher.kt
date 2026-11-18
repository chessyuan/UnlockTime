package org.localwake.app.security

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class UnlockMethod(internal val wireCode: Int) { PATTERN(1), PIN(2), PASSWORD(3) }

object TextCredentialValidator {
    fun valid(method: UnlockMethod, value: CharArray): Boolean = when (method) {
        UnlockMethod.PATTERN -> false
        UnlockMethod.PIN -> value.size in 4..16 && value.all { it in '0'..'9' }
        UnlockMethod.PASSWORD -> value.size in 4..64 && value.none {
            it.isISOControl() || it == '\u2028' || it == '\u2029'
        }
    }
}

internal object TextCredentialCipher {
    fun encrypt(method: UnlockMethod, value: CharArray, key: SecretKey): PatternCipher.Payload {
        require(TextCredentialValidator.valid(method, value))
        val plaintext = ByteBuffer.allocate(value.size * Char.SIZE_BYTES)
        try {
            for (character in value) plaintext.putChar(character)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData(method))
            return PatternCipher.Payload(cipher.iv, cipher.doFinal(plaintext.array()))
        } finally {
            plaintext.array().fill(0)
        }
    }

    fun decrypt(method: UnlockMethod, payload: PatternCipher.Payload, key: SecretKey): CharArray {
        require(method != UnlockMethod.PATTERN)
        require(payload.iv.size == 12 && payload.ciphertext.size in 24..144)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
        cipher.updateAAD(associatedData(method))
        val plaintext = cipher.doFinal(payload.ciphertext)
        try {
            require(plaintext.size % Char.SIZE_BYTES == 0)
            val chars = ByteBuffer.wrap(plaintext).asCharBuffer()
            val value = CharArray(chars.remaining())
            chars.get(value)
            if (!TextCredentialValidator.valid(method, value)) {
                value.fill('\u0000')
                throw IllegalArgumentException("Invalid stored text credential")
            }
            return value
        } finally {
            plaintext.fill(0)
        }
    }

    private fun associatedData(method: UnlockMethod): ByteArray =
        byteArrayOf(CREDENTIAL_RECORD_VERSION.toByte(), method.wireCode.toByte())
}
