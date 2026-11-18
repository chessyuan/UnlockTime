package com.phoneuse.unified

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single device owner shared by local unlock, agent, routine, and trusted sensitive UI lifecycles.
 * All ownership decisions are synchronous and serialized. This class does not perform I/O, inspect
 * lock state, or grant unlock authorization; those checks belong to the trusted local preflight.
 * A valid lease is necessary, but is not by itself proof that the screen is safe to access.
 */
class DeviceExecutionCoordinator {
    private val lock = Any()
    private var active: DeviceSessionLease? = null
    private var epoch = 0L
    private val mutableOwner = MutableStateFlow(DeviceOwner.NONE)

    /** Includes a revoked sensitive reservation until its owning UI explicitly releases it. */
    val owner: StateFlow<DeviceOwner> = mutableOwner.asStateFlow()
    val currentEpoch: Long get() = synchronized(lock) { epoch }

    /** Never takes over another lease, even when the requested owner is the same. */
    fun tryAcquire(owner: DeviceOwner): DeviceSessionLease? {
        require(owner != DeviceOwner.NONE) { "NONE cannot own the device" }
        return synchronized(lock) {
            if (active != null) return null
            DeviceSessionLease(owner, advanceEpoch()).also {
                active = it
                mutableOwner.value = owner
            }
        }
    }

    fun isValid(lease: DeviceSessionLease): Boolean = synchronized(lock) {
        active === lease && !lease.isRevoked
    }

    /** Call again after asynchronous waits and immediately before a device access or result publication. */
    fun requireValid(lease: DeviceSessionLease) {
        synchronized(lock) { requireValidLocked(lease) }
    }

    /** Linearizes a small non-suspending publication against ownership revocation. No I/O in block. */
    fun <T> publish(lease: DeviceSessionLease, block: () -> T): T = synchronized(lock) {
        requireValidLocked(lease)
        block()
    }

    /**
     * Replaces a valid lease with a new epoch without ever exposing NONE to another claimant.
     * The old task's cancellation/release cannot free the new lease. A sensitive reservation requires
     * explicit release; transferring out would otherwise bypass the sensitive UI lifetime.
     */
    fun transfer(lease: DeviceSessionLease, newOwner: DeviceOwner): DeviceSessionLease {
        require(newOwner != DeviceOwner.NONE) { "Use release to relinquish ownership" }
        val (replacement, notify) = synchronized(lock) {
            requireValidLocked(lease)
            check(!lease.owner.isSensitive) { "Sensitive sessions require explicit release" }
            val next = DeviceSessionLease(newOwner, advanceEpoch())
            val notifications = lease.invalidate(DeviceRevocationReason.TRANSFERRED)
            active = next
            mutableOwner.value = newOwner
            next to notifications
        }
        notify?.invoke()
        return replacement
    }

    /**
     * Only trusted native credential UI may call this, before launching system authentication.
     * Atomically blocks new work and revokes the previous ordinary owner, then delivers cancellation
     * callbacks before returning. An existing credential/migration reservation is never stolen.
     * The caller still has to drain any non-cancellable device I/O before showing credentials.
     */
    fun beginCredentialEdit(): DeviceSessionLease? {
        val (lease, notify) = synchronized(lock) {
            if (active?.owner?.isSensitive == true) return null
            val next = DeviceSessionLease(DeviceOwner.CREDENTIAL_EDIT, advanceEpoch())
            val notifications = active?.invalidate(DeviceRevocationReason.SENSITIVE_SESSION)
            active = next
            mutableOwner.value = DeviceOwner.CREDENTIAL_EDIT
            next to notifications
        }
        notify?.invoke()
        return lease
    }

    /**
     * Invalidates the current epoch and notifies its cancellation listeners. Ordinary ownership is
     * freed. Sensitive ownership remains reserved, including after service loss, until release.
     * Use this for current device state changes, not a stale task's completion (which uses release).
     */
    fun revoke(reason: DeviceRevocationReason): Boolean {
        return revokeMatching(null, reason)
    }

    /** A late device callback may invalidate only the lease that initiated it. */
    fun revokeIfCurrent(lease: DeviceSessionLease, reason: DeviceRevocationReason): Boolean =
        revokeMatching(lease, reason)

    /**
     * Trusted own-Activity entry invalidates only ordinary AI screen work. The owner check and revoke
     * share one monitor, so a concurrent UNLOCK or sensitive reservation cannot be revoked by mistake.
     */
    fun revokeScreenOwner(reason: DeviceRevocationReason): Boolean =
        revokeMatching(null, reason, screenOwnerOnly = true)

    private fun revokeMatching(
        expected: DeviceSessionLease?,
        reason: DeviceRevocationReason,
        screenOwnerOnly: Boolean = false,
    ): Boolean {
        val notify = synchronized(lock) {
            val lease = active ?: return false
            if (expected != null && active !== expected) return false
            if (screenOwnerOnly && lease.owner != DeviceOwner.AGENT && lease.owner != DeviceOwner.ROUTINE) return false
            if (lease.isRevoked) return false
            advanceEpoch()
            val notifications = lease.invalidate(reason)
            if (!lease.owner.isSensitive) {
                active = null
                mutableOwner.value = DeviceOwner.NONE
            }
            notifications
        }
        notify?.invoke()
        return true
    }

    /** A stale or foreign lease does nothing; a revoked sensitive lease can release its own reservation. */
    fun release(lease: DeviceSessionLease): Boolean {
        val notify = synchronized(lock) {
            if (active !== lease) return false
            advanceEpoch()
            val notifications = lease.invalidate(DeviceRevocationReason.RELEASED)
            active = null
            mutableOwner.value = DeviceOwner.NONE
            notifications
        }
        notify?.invoke()
        return true
    }

    private fun requireValidLocked(lease: DeviceSessionLease) {
        if (active !== lease || lease.isRevoked) {
            throw DeviceSessionRevokedException(lease.revocationReason ?: DeviceRevocationReason.INVALID_SESSION)
        }
    }

    private fun advanceEpoch(): Long {
        check(epoch < Long.MAX_VALUE) { "Device session epoch exhausted" }
        return ++epoch
    }
}
