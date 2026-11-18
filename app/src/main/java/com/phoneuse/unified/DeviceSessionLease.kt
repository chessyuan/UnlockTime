package com.phoneuse.unified

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Internal ownership only; NONE is a state, never an acquirable capability. */
enum class DeviceOwner {
    NONE, UNLOCK, AGENT, ROUTINE, CREDENTIAL_EDIT, MIGRATION;

    internal val isSensitive: Boolean
        get() = this == CREDENTIAL_EDIT || this == MIGRATION
}

/** Deliberately carries no screen content, credential details, or caller-supplied error text. */
enum class DeviceRevocationReason {
    RELEASED,
    TRANSFERRED,
    SCREEN_LOCKED,
    SCREEN_OFF,
    SERVICE_DISCONNECTED,
    SENSITIVE_SESSION,
    USER_CANCELLED,
    TASK_CHANGED,
    SETTINGS_CHANGED,
    INVALID_SESSION,
}

class DeviceSessionRevokedException(val reason: DeviceRevocationReason) :
    CancellationException("Device session unavailable: ${reason.name}")

/**
 * An in-process capability, authenticated by reference identity at its issuing coordinator.
 * Neither [owner] nor [epoch] is sufficient to reconstruct it. It is not serializable or a data class.
 * Retain the same lease through asynchronous work, then validate it before using or publishing results.
 */
class DeviceSessionLease internal constructor(val owner: DeviceOwner, val epoch: Long) {
    private val lock = Any()
    private val listeners = mutableListOf<Listener>()
    @Volatile private var revokedBecause: DeviceRevocationReason? = null

    val revocationReason: DeviceRevocationReason? get() = revokedBecause
    val isRevoked: Boolean get() = revokedBecause != null

    /**
     * Called at most once, including when registered after revocation. Delivery is synchronous and
     * outside both lease and coordinator monitors. Hooks should cancel/clear work without blocking.
     * Closing unregisters a hook that has not started; it cannot undo a hook already executing.
     * One failing cancellation hook never prevents the remaining hooks from being notified.
     */
    fun onRevoked(callback: (DeviceRevocationReason) -> Unit): AutoCloseable {
        val listener = Listener(callback)
        val reason = synchronized(lock) {
            revokedBecause.also { if (it == null) listeners.add(listener) }
        }
        if (reason != null) listener.notify(reason)
        return AutoCloseable {
            listener.dispose()
            synchronized(lock) { listeners.remove(listener) }
        }
    }

    /** Marks invalid immediately; the coordinator invokes returned notifications after leaving its lock. */
    internal fun invalidate(reason: DeviceRevocationReason): (() -> Unit)? {
        val pending = synchronized(lock) {
            if (revokedBecause != null) return null
            revokedBecause = reason
            listeners.toList().also { listeners.clear() }
        }
        return { pending.forEach { it.notify(reason) } }
    }

    private class Listener(callback: (DeviceRevocationReason) -> Unit) {
        private val pending = AtomicReference<((DeviceRevocationReason) -> Unit)?>(callback)

        fun notify(reason: DeviceRevocationReason) {
            val callback = pending.getAndSet(null) ?: return
            runCatching { callback(reason) }
        }

        fun dispose() { pending.set(null) }
    }
}
