package com.phoneuse.service

import com.phoneuse.agent.TapSequenceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TapGestureChunkTest {
    @Test fun theStrokeDurationIsPartOfTheOneSecondInterval() = runTest {
        val starts = mutableListOf<Long>()
        val result = runTapGestureChunk(3, 1000, { currentTime }, { null }, { it() }) {
            starts += currentTime
            delay(60)
            GestureDispatch.Completed(currentTime, durationMs = 60)
        }
        assertEquals(listOf(0L, 1000L, 2000L), starts)
        assertEquals(TapSequenceResult(3), result)
    }

    private class Overlay {
        var entries = 0
        var exits = 0

        suspend fun pass(block: suspend () -> TapSequenceResult): TapSequenceResult {
            entries++
            return try { block() } finally { exits++ }
        }
    }

    private suspend fun TestScope.chunk(
        count: Int,
        intervalMs: Long,
        overlay: Overlay,
        guard: () -> String? = { null },
        dispatch: suspend () -> GestureDispatch,
    ) = runTapGestureChunk(count, intervalMs, { currentTime }, guard, overlay::pass, dispatch)

    @Test fun delayedDownAndLateCallbackCannotShortenTheMinimumStartGap() = runTest {
        val starts = mutableListOf<Long>()
        val overlay = Overlay()
        val result = chunk(3, 120, overlay) {
            if (starts.isEmpty()) delay(75) // Android delivers the first DOWN after accepting submission
            starts.add(currentTime)
            delay(if (starts.size == 2) 190 else 30)
            GestureDispatch.Completed(currentTime)
        }
        assertEquals(listOf(75L, 225L, 535L), starts)
        assertEquals(TapSequenceResult(3), result)
        assertEquals(1, overlay.entries)
        assertEquals(1, overlay.exits)
    }

    @Test fun anUncompletedGestureStopsTheChunkAtTheExactCallbackCount() = runTest {
        for (reason in listOf("GESTURE_CANCELLED", "DISPATCH_REJECTED", "DISPATCH_TIMEOUT")) {
            var attempts = 0
            val overlay = Overlay()
            val result = chunk(10, 1, overlay) {
                attempts++
                if (attempts == 3) GestureDispatch.Stopped(reason) else GestureDispatch.Completed(currentTime)
            }
            assertEquals(TapSequenceResult(2, reason), result)
            assertEquals(3, attempts)
            assertEquals(1, overlay.exits)
        }
    }

    @Test fun pageChangeDuringARealGestureCountsItsCallbackAndEndsTheGroup() = runTest {
        var reason: String? = null
        val result = chunk(10, 1, Overlay(), { reason }) {
            delay(50)
            reason = "PAGE_CHANGED"
            GestureDispatch.Completed(currentTime)
        }
        assertEquals(TapSequenceResult(1, "PAGE_CHANGED"), result)
    }

    @Test fun anInterruptionReleasesTheOverlayEvenWhenTheNextTapIsHoursAway() = runTest {
        val overlay = Overlay()
        var reason: String? = null
        var submitted = 0
        val result = async {
            chunk(2, Long.MAX_VALUE, overlay, { reason }) {
                submitted++
                GestureDispatch.Completed(currentTime)
            }
        }
        advanceTimeBy(150)
        reason = "INTERRUPTED"
        advanceTimeBy(100)
        assertTrue(result.isCompleted)
        assertEquals(TapSequenceResult(1, "INTERRUPTED"), result.await())
        assertEquals(1, submitted)
        assertEquals(1, overlay.exits)
    }

    @Test fun cancellationWhileAwaitingAndroidUnwindsAndALateCallbackCannotDispatchAgain() = runTest {
        val overlay = Overlay()
        val callback = CompletableDeferred<GestureDispatch>()
        var submitted = 0
        val task = launch {
            chunk(10, 100, overlay) { submitted++; callback.await() }
        }
        runCurrent()
        assertEquals(1, submitted)
        assertEquals(0, overlay.exits)
        task.cancelAndJoin()
        callback.complete(GestureDispatch.Completed(currentTime))
        runCurrent()
        assertEquals(1, overlay.exits)
        assertEquals(1, submitted)
    }

    @Test fun aStaleTargetOrInvalidGroupNeverOpensTheOverlay() = runTest {
        val overlay = Overlay()
        val stop = chunk(10, 1, overlay, { "PAGE_CHANGED" }) { error("stale gesture") }
        assertEquals(TapSequenceResult(0, "PAGE_CHANGED"), stop)
        for ((count, ms) in listOf(0 to 1L, 11 to 1L, 1 to 0L)) {
            assertEquals(TapSequenceResult(0, "INVALID_ARGUMENT"), chunk(count, ms, overlay) { error("invalid gesture") })
        }
        assertEquals(0, overlay.entries)
    }
}
