package com.phoneuse.unified

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class NormalSurfaceState { SAFE, INCOMPLETE, OWN }

/** An incomplete window grants no screen or input access, but need not revoke a still-valid task. */
internal class NormalSurfaceIncompleteException : RuntimeException("Screen surface incomplete")
internal class NormalSurfaceTimeoutException : CancellationException("Screen surface unavailable")

internal object NormalSurfacePolicy {
    fun classify(applicationPackages: List<String?>, ownPackage: String): NormalSurfaceState = when {
        applicationPackages.any { it == ownPackage } -> NormalSurfaceState.OWN
        applicationPackages.isEmpty() || applicationPackages.any { it.isNullOrBlank() } -> NormalSurfaceState.INCOMPLETE
        else -> NormalSurfaceState.SAFE
    }

    fun require(state: NormalSurfaceState, rejectOwn: () -> Unit) {
        when (state) {
            NormalSurfaceState.SAFE -> Unit
            NormalSurfaceState.INCOMPLETE -> throw NormalSurfaceIncompleteException()
            NormalSurfaceState.OWN -> {
                rejectOwn()
                // The callback must deny access even if its owner was concurrently replaced.
                throw DeviceSessionRevokedException(DeviceRevocationReason.SENSITIVE_SESSION)
            }
        }
    }

    /**
     * Read-only retry: check ownership/lock/service as well as the surface before and after every read.
     * No input or authentication belongs in [read]. An incomplete read is discarded, never published.
     * Only the incomplete-surface signal retries; revocation, cancellation and other failures escape.
     */
    suspend fun <T> read(
        timeoutMs: Long,
        clock: () -> Long,
        guard: () -> Unit,
        discard: (T) -> Unit = {},
        read: suspend () -> T,
    ): T {
        val deadline = clock() + timeoutMs.coerceAtLeast(0)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                guard()
                val value = read()
                try {
                    currentCoroutineContext().ensureActive()
                    guard()
                } catch (failure: Throwable) {
                    discard(value)
                    throw failure
                }
                return value
            } catch (_: NormalSurfaceIncompleteException) {
                val remaining = deadline - clock()
                if (remaining <= 0) throw NormalSurfaceTimeoutException()
                delay(minOf(50, remaining))
            }
        }
    }
}
