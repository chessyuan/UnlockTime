package org.localwake.app

import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.security.PatternCipher
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException

class PatternCipherTest {
    @Test fun partialOrCompleteRecordNeverAllowsSilentKeyReplacement() {
        assertFalse(PatternCipher.mayCreateKey(hasCiphertext = false, hasIv = true))
        assertFalse(PatternCipher.mayCreateKey(hasCiphertext = true, hasIv = false))
        assertFalse(PatternCipher.mayCreateKey(hasCiphertext = true, hasIv = true))
        assertTrue(PatternCipher.mayCreateKey(hasCiphertext = false, hasIv = false))
    }
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripAndFreshIv() {
        val k = key()
        val p = intArrayOf(1, 2, 3, 6)
        val a = PatternCipher.encrypt(p, k)
        val b = PatternCipher.encrypt(p, k)
        assertFalse(a.iv.contentEquals(b.iv))
        assertArrayEquals(p, PatternCipher.decrypt(a, k))
        assertFalse(a.ciphertext.contentEquals(byteArrayOf(1, 2, 3, 6)))
    }
    @Test fun tamperedCiphertextIsRejected() {
        val k = key()
        val a = PatternCipher.encrypt(intArrayOf(1, 2, 3, 6), k)
        a.ciphertext[0] = (a.ciphertext[0].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { PatternCipher.decrypt(a, k) }
    }
    @Test fun wrongKeyDoesNotReturnPattern() {
        val a = PatternCipher.encrypt(intArrayOf(1, 2, 3, 6), key())
        assertThrows(AEADBadTagException::class.java) { PatternCipher.decrypt(a, key()) }
    }
}
