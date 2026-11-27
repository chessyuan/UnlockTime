package com.phoneuse.service

import com.phoneuse.agent.TapSequenceResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal sealed interface GestureDispatch {
    /** Android delivered onCompleted at this uptime. Its actual DOWN has necessarily happened. */
    data class Completed(val completedAt: Long, val durationMs: Long = 0) : GestureDispatch
    data class Stopped(val reason: String) : GestureDispatch
}

internal const val MAX_TAPS_PER_CHUNK = 10

/** One caller-owned pass through the overlay. The dispatch boundary rechecks the same guard on main. */
internal suspend fun runTapGestureChunk(
    count: Int,
    intervalMs: Long,
    clock: () -> Long,
    stopReason: () -> String?,
    passThrough: suspend (suspend () -> TapSequenceResult) -> TapSequenceResult,
    dispatch: suspend () -> GestureDispatch,
): TapSequenceResult {
    if (count !in 1..MAX_TAPS_PER_CHUNK || intervalMs <= 0) return TapSequenceResult(0, "INVALID_ARGUMENT")
    currentCoroutineContext().ensureActive()
    stopReason()?.let { return TapSequenceResult(0, it) }
    return passThrough {
        var completed = 0
        var previousStartBound: Long? = null
        while (completed < count) {
            currentCoroutineContext().ensureActive()
            stopReason()?.let { return@passThrough TapSequenceResult(completed, it) }
            val previous = previousStartBound
            if (previous != null) {
                val elapsed = clock() - previous
                if (elapsed < intervalMs) {
                    // Relative time handles even Long.MAX_VALUE intervals. Poll only inexpensive event/voice state.
                    delay(minOf(intervalMs - elapsed, 100L))
                    continue
                }
            }
            when (val gesture = dispatch()) {
                is GestureDispatch.Stopped -> return@passThrough TapSequenceResult(completed, gesture.reason)
                is GestureDispatch.Completed -> {
                    completed++
                    // Completion is after UP. Subtract only the known stroke duration; late delivery
                    // remains conservative, while the tap's own duration is not added to every interval.
                    previousStartBound = gesture.completedAt - gesture.durationMs
                    // Let already queued accessibility events and user cancellation run between submissions.
                    yield()
                }
            }
        }
        TapSequenceResult(completed, stopReason())
    }
}
