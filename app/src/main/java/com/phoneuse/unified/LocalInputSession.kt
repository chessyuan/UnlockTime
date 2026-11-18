package com.phoneuse.unified

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Carries the actual local owner's capability across suspensions; an owner enum is never sufficient. */
object LocalInputSession {
    /** Covers synchronous node/global actions until their platform call has returned. */
    fun <T> input(block: () -> T): T {
        val lease = currentLease() ?: throw DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION)
        val coordinator = AndroidDeviceAccess.coordinator
        val ticket = coordinator.publish(lease) { DeviceInputFence.shared.tryBegin() }
            ?: throw DeviceUnavailableException("INPUT_STILL_RUNNING")
        return try { coordinator.requireValid(lease); block() } finally { ticket.complete() }
    }
    private val current = ThreadLocal<DeviceSessionLease?>()

    fun currentLease(): DeviceSessionLease? = current.get()

    suspend fun <T> withLease(lease: DeviceSessionLease, block: suspend () -> T): T =
        withContext(current.asContextElement(lease)) { block() }
}
