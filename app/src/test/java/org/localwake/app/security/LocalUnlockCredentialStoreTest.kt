package org.localwake.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class LocalUnlockCredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun pinValidationUsesAsciiDigitsAndLengthLimits() {
        assertTrue(TextCredentialValidator.valid(UnlockMethod.PIN, "0123".toCharArray()))
        assertTrue(TextCredentialValidator.valid(UnlockMethod.PIN, CharArray(16) { '9' }))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PIN, "123".toCharArray()))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PIN, CharArray(17) { '0' }))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PIN, "1a23".toCharArray()))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PIN, "１２３４".toCharArray()))
    }

    @Test
    fun passwordValidationKeepsSpacesAndRejectsLineBreaksAndControls() {
        assertTrue(TextCredentialValidator.valid(UnlockMethod.PASSWORD, " A文 ".toCharArray()))
        assertTrue(TextCredentialValidator.valid(UnlockMethod.PASSWORD, CharArray(64) { 'A' }))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PASSWORD, CharArray(65) { 'A' }))
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PASSWORD, "abc".toCharArray()))
        for (invalid in listOf('\n', '\r', '\t', '\u0000', '\u0085', '\u2028', '\u2029')) {
            assertFalse(TextCredentialValidator.valid(UnlockMethod.PASSWORD, charArrayOf('a', 'b', invalid, 'c')))
        }
        assertFalse(TextCredentialValidator.valid(UnlockMethod.PATTERN, "1234".toCharArray()))
    }

    @Test
    fun pinPayloadCannotBeRelabeledAsPasswordEvenWhenItSatisfiesBothValidators() {
        val payload = TextCredentialCipher.encrypt(UnlockMethod.PIN, "0123".toCharArray(), key)
        assertThrows(AEADBadTagException::class.java) {
            TextCredentialCipher.decrypt(UnlockMethod.PASSWORD, payload, key)
        }
        assertArrayEquals("0123".toCharArray(), TextCredentialCipher.decrypt(UnlockMethod.PIN, payload, key))
    }

    @Test
    fun selectionKeepsMethodAndCiphertextTogetherAndDistinguishesExplicitClear() {
        val selection = UnlockCredentialFile(folder.root.resolve("credential"))
        assertNull(selection.read())

        val password = " Aa文🔑 ".toCharArray()
        selection.write(UnlockMethod.PASSWORD, TextCredentialCipher.encrypt(UnlockMethod.PASSWORD, password, key))
        val saved = requireNotNull(selection.read())
        assertEquals(UnlockMethod.PASSWORD, saved.method)
        assertArrayEquals(password, TextCredentialCipher.decrypt(saved.method, requireNotNull(saved.payload), key))

        selection.write(UnlockMethod.PATTERN, PatternCipher.encrypt(intArrayOf(1, 2, 3, 6), key))
        val changed = requireNotNull(selection.read())
        assertEquals(UnlockMethod.PATTERN, changed.method)
        assertArrayEquals(intArrayOf(1, 2, 3, 6), PatternCipher.decrypt(requireNotNull(changed.payload), key))

        selection.write(UnlockMethod.PATTERN, null)
        val cleared = requireNotNull(selection.read())
        assertEquals(UnlockMethod.PATTERN, cleared.method)
        assertNull(cleared.payload)
    }

    @Test
    fun failedReplacementKeepsPreviousSelection() {
        val file = folder.root.resolve("credential")
        val selection = UnlockCredentialFile(file)
        selection.write(UnlockMethod.PIN, TextCredentialCipher.encrypt(UnlockMethod.PIN, "0123".toCharArray(), key))
        val original = file.readBytes()
        val invalidPayload = PatternCipher.Payload(ByteArray(12), ByteArray(256))
        assertThrows(IllegalArgumentException::class.java) { selection.write(UnlockMethod.PASSWORD, invalidPayload) }
        assertArrayEquals(original, file.readBytes())
        assertEquals(UnlockMethod.PIN, selection.read()?.method)
    }

    @Test
    fun truncatedRecordIsCorruptRatherThanMissing() {
        val file = folder.root.resolve("credential")
        val selection = UnlockCredentialFile(file)
        selection.write(UnlockMethod.PIN, TextCredentialCipher.encrypt(UnlockMethod.PIN, "0123".toCharArray(), key))
        file.writeBytes(file.readBytes().dropLast(1).toByteArray())

        val error = assertThrows(CredentialFailure::class.java) { selection.read() }
        assertEquals(CredentialState.CORRUPT, error.state)
    }
}
