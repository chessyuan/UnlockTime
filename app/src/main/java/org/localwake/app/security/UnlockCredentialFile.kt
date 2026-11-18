package org.localwake.app.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

internal const val CREDENTIAL_RECORD_VERSION = 1

internal class UnlockCredentialFile(private val file: File) {
    data class Record(val method: UnlockMethod, val payload: PatternCipher.Payload?)

    // A missing file means an existing legacy pattern may still be in use.
    // An explicit cleared record keeps a failed legacy cleanup from reselecting it.
    fun read(): Record? = try {
        DataInputStream(Files.newInputStream(file.toPath())).use { input ->
            if (input.readUnsignedByte() != CREDENTIAL_RECORD_VERSION) corrupt()
            val code = input.readUnsignedByte()
            if (code == 0) {
                if (input.read() != -1) corrupt()
                return@use Record(UnlockMethod.PATTERN, null)
            }
            val method = UnlockMethod.entries.firstOrNull { it.wireCode == code } ?: corrupt()
            val iv = ByteArray(12).also(input::readFully)
            val length = input.readUnsignedByte()
            if (length !in ciphertextSize(method)) corrupt()
            val ciphertext = ByteArray(length).also(input::readFully)
            if (input.read() != -1) corrupt()
            Record(method, PatternCipher.Payload(iv, ciphertext))
        }
    } catch (_: NoSuchFileException) {
        null
    } catch (_: EOFException) {
        corrupt()
    }

    fun write(method: UnlockMethod, payload: PatternCipher.Payload?) {
        require(payload != null || method == UnlockMethod.PATTERN)
        require(payload == null || payload.iv.size == 12 && payload.ciphertext.size in ciphertextSize(method))
        val temporary = File.createTempFile("credential-", ".tmp", checkNotNull(file.parentFile))
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeByte(CREDENTIAL_RECORD_VERSION)
                output.writeByte(if (payload == null) 0 else method.wireCode)
                if (payload != null) {
                    output.write(payload.iv)
                    output.writeByte(payload.ciphertext.size)
                    output.write(payload.ciphertext)
                }
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun ciphertextSize(method: UnlockMethod) = when (method) {
        UnlockMethod.PATTERN -> 20..25
        UnlockMethod.PIN -> 24..48
        UnlockMethod.PASSWORD -> 24..144
    }

    private fun corrupt(): Nothing = throw CredentialFailure(CredentialState.CORRUPT)
}
