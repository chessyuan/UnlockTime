// AES-GCM representation adapted from OpenClaw. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object PatternCipher {
    fun mayCreateKey(hasCiphertext: Boolean, hasIv: Boolean): Boolean = !hasCiphertext && !hasIv
    class Payload(val iv: ByteArray, val ciphertext: ByteArray)
    fun encrypt(pattern: IntArray, key: SecretKey): Payload {
        require(PatternValidator.valid(pattern))
        val plaintext = ByteArray(pattern.size) { pattern[it].toByte() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Payload(cipher.iv, cipher.doFinal(plaintext))
        } finally { plaintext.fill(0) }
    }
    fun decrypt(payload: Payload, key: SecretKey): IntArray {
        require(payload.iv.size == 12 && payload.ciphertext.size in 20..25)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
        val plaintext = cipher.doFinal(payload.ciphertext)
        try {
            val pattern = IntArray(plaintext.size) { plaintext[it].toInt() }
            if (!PatternValidator.valid(pattern)) {
                pattern.fill(0)
                throw IllegalArgumentException("Invalid stored pattern")
            }
            return pattern
        } finally { plaintext.fill(0) }
    }
}
