package com.phoneuse.routine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Sync bytes before rename. Failed writes never publish the candidate in memory. */
internal object DurableRoutineFile {
    class CommitUncertain(cause: Exception) : java.io.IOException("ROUTINE_COMMIT_UNCERTAIN", cause)
    fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val fresh = File(file.path + ".new")
        var replaced = false
        try {
            FileOutputStream(fresh).use { out -> out.write(bytes); out.fd.sync() }
            Files.move(fresh.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            replaced = true
            try {
                val directory = requireNotNull(file.absoluteFile.parentFile)
                java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            } catch (_: java.nio.file.AccessDeniedException) { /* Windows JVM test host cannot open directories. */ }
            catch (_: UnsupportedOperationException) { /* File bytes remain synced on this platform. */ }
        } catch (e: Exception) {
            if (fresh.isFile) fresh.delete()
            if (replaced) throw CommitUncertain(e)
            throw java.io.IOException("ROUTINE_WRITE_FAILED", e)
        }
    }
    fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
