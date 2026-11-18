package com.phoneuse.app

import com.phoneuse.routine.RoutineResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** One terminal callback for an accepted routine, including cancellation before a LAZY body is entered. */
internal class RoutineRunDelivery(private val deliver: (RoutineResult) -> Unit) {
    private enum class State { PUBLISHED, STARTED, TERMINAL }
    private val state = AtomicReference(State.PUBLISHED)

    fun markStarted(): Boolean = state.compareAndSet(State.PUBLISHED, State.STARTED)

    fun complete(result: RoutineResult): Boolean {
        if (state.getAndSet(State.TERMINAL) == State.TERMINAL) return false
        // Mark terminal before invoking storage: a failed write stays unknown, never duplicates/replays.
        deliver(result)
        return true
    }

    /** A started body owns its finally callback; the Job handler supplies only the missing pre-entry result. */
    fun completeBeforeStart(cause: Throwable?): Boolean {
        if (!state.compareAndSet(State.PUBLISHED, State.TERMINAL)) return false
        val outcome = when {
            cause != null && RoutineResult.serviceWasLost(false, cause) -> RunOutcome.SERVICE_LOST
            cause is CancellationException -> RunOutcome.CANCELLED
            else -> RunOutcome.FAILED
        }
        deliver(RoutineResult(outcome, null))
        return true
    }
}
