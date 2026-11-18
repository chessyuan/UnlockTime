package com.phoneuse.unified

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks input accepted by the platform, whose lifetime can exceed its initiating coroutine.
 * Cancelling a Job or releasing an owner never clears this fence. Only a real platform completion,
 * cancellation callback, synchronous rejection, or dispatch exception may complete its ticket.
 */
class DeviceInputFence {
    private val lock = Any()
    private var active: Ticket? = null
    private val idle = MutableStateFlow(true)

    val isIdle: Boolean get() = synchronized(lock) { active == null }

    /** Reserve before dispatch; a second gesture cannot replace an input still running in Android. */
    fun tryBegin(): Ticket? = synchronized(lock) {
        if (active != null) return null
        Ticket(this).also {
            active = it
            idle.value = false
        }
    }

    /** A timeout fails closed and leaves the platform input recorded as running. */
    suspend fun awaitIdle(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        require(timeoutMs >= 0)
        if (timeoutMs == 0L) return isIdle
        return withTimeoutOrNull(timeoutMs) { idle.first { it }; true } ?: false
    }

    private fun complete(ticket: Ticket) = synchronized(lock) {
        if (active === ticket) {
            active = null
            idle.value = true
        }
    }

    class Ticket internal constructor(private val fence: DeviceInputFence) {
        /** Idempotent: a late/duplicate old callback cannot release a newer gesture. */
        fun complete() { fence.complete(this) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        val shared = DeviceInputFence()
    }
}
