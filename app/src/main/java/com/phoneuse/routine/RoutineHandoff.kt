package com.phoneuse.routine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reserve BEFORE durable claim, then transfer to the application scope. A broadcast or reconnect caller
 * can finish immediately. The reservation also covers the short period before the native bridge owns
 * the device, so another occurrence is not consumed just to discover that the bridge is busy.
 */
internal class RoutineHandoff(
    private val scope: CoroutineScope,
    private val acquireWake: () -> AutoCloseable,
    private val onAvailable: () -> Unit,
) {
    class Reservation internal constructor(val routineId: Long, internal val wake: AutoCloseable) {
        internal var job: Job? = null
        internal var closed = false
        internal var cancelled = false
    }
    private val lock = Any()
    private var active: Reservation? = null
    val isBusy: Boolean get() = synchronized(lock) { active != null }

    /** A failed wake acquisition never authorizes a claim. */
    fun reserve(routineId: Long): Reservation? = synchronized(lock) {
        if (active != null) return@synchronized null
        val wake = try { acquireWake() } catch (_: Exception) { return@synchronized null }
        Reservation(routineId, wake).also { active = it }
    }

    fun submit(reservation: Reservation, work: suspend () -> Unit): Boolean {
        val job = synchronized(lock) {
            if (active !== reservation || reservation.closed || reservation.cancelled || reservation.job != null) return false
            scope.launch(start = CoroutineStart.LAZY) { work() }.also {
                reservation.job = it
                // Completion also runs if cancelled before a lazy coroutine enters its body.
                it.invokeOnCompletion { release(reservation) }
            }
        }
        return job.start()
    }

    /** Used when claim fails; also idempotent for cancellation/completion races. */
    fun release(reservation: Reservation) {
        val notify = synchronized(lock) {
            if (reservation.closed) return
            reservation.closed = true
            val wasActive = active === reservation
            if (wasActive) active = null
            wasActive
        }
        try { reservation.wake.close() } finally { if (notify) onAvailable() }
    }

    fun cancel(routineId: Long) {
        val (reservation, job) = synchronized(lock) {
            val reservation = active?.takeIf { it.routineId == routineId } ?: return
            reservation.cancelled = true
            reservation to reservation.job
        }
        if (job == null) release(reservation) else job.cancel()
    }
}
