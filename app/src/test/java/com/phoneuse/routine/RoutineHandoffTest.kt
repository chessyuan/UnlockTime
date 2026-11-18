package com.phoneuse.routine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoutineHandoffTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun broadcastFinishesAndCancelsWithoutCancellingNativeWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val application = CoroutineScope(SupervisorJob() + dispatcher)
        val receiver = CoroutineScope(Job() + dispatcher)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var wakeHeld = false
        var released = 0
        var available = 0
        val handoff = RoutineHandoff(application, {
            wakeHeld = true
            AutoCloseable { wakeHeld = false; released++ }
        }, { available++ })
        try {
            val broadcast = receiver.launch {
                val ticket = handoff.reserve(1)!!
                assertTrue(wakeHeld) // Held before the broadcast may commit and return.
                assertTrue(handoff.submit(ticket) { started.complete(Unit); finish.await() })
            }
            runCurrent()
            assertTrue(broadcast.isCompleted)
            assertTrue(started.isCompleted)
            receiver.cancel()
            runCurrent()
            assertTrue(handoff.isBusy); assertTrue(wakeHeld); assertEquals(0, released)
            assertNull(handoff.reserve(2))
            finish.complete(Unit)
            advanceUntilIdle()
            assertFalse(handoff.isBusy); assertFalse(wakeHeld)
            assertEquals(1, released); assertEquals(1, available)
        } finally { application.cancel(); receiver.cancel() }
    }

    @Test fun reservationPreventsSecondOccurrenceClaimUntilFirstHandoffCompletes() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = RoutineStore(temp.newFolder().resolve("routines.json"))
        val start = Instant.parse("2026-11-16T00:00:00Z")
        repeat(2) { store.save(Routine(title = "Task $it", trigger = Trigger.Once(start.plusSeconds(10).toEpochMilli()),
            action = RoutineAction.OpenAppsSequence(emptyList()))) }
        val pending = store.armTimes(start, ZoneId.of("UTC"), 2)
        val finish = CompletableDeferred<Unit>()
        val handoff = RoutineHandoff(application, { AutoCloseable {} }, {})
        try {
            val first = handoff.reserve(pending[0].routineId)!!
            assertNotNull(store.claim(pending[0].token, pending[0].dueAt, 2))
            assertTrue(handoff.submit(first) { finish.await() })
            assertNull(handoff.reserve(pending[1].routineId))
            assertEquals(listOf(pending[1].token), store.pending(2).map { it.token })
            finish.complete(Unit)
            advanceUntilIdle()
            val second = handoff.reserve(pending[1].routineId)!!
            assertNotNull(store.claim(pending[1].token, pending[1].dueAt + 1, 2))
            handoff.release(second)
        } finally { application.cancel() }
    }

    @Test fun cancelledBeforeEntryReleasesWakeAndNeverRunsSideEffect() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var effects = 0
        var closed = 0
        val handoff = RoutineHandoff(application, { AutoCloseable { closed++ } }, {})
        try {
            val ticket = handoff.reserve(7)!!
            assertTrue(handoff.submit(ticket) { effects++ })
            handoff.cancel(7)
            runCurrent()
            assertEquals(0, effects); assertEquals(1, closed); assertFalse(handoff.isBusy)
            handoff.release(ticket)
            assertEquals(1, closed)
        } finally { application.cancel() }
    }

    @Test fun cancellationBeforeSubmissionCannotStartLaterAndOtherRoutineCannotCancelOwner() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var closed = 0
        val handoff = RoutineHandoff(application, { AutoCloseable { closed++ } }, {})
        try {
            val ticket = handoff.reserve(8)!!
            handoff.cancel(9)
            assertTrue(handoff.isBusy)
            handoff.cancel(8)
            assertFalse(handoff.submit(ticket) { error("revoked draft must not execute") })
            assertFalse(handoff.isBusy); assertEquals(1, closed)
        } finally { application.cancel() }
    }

    @Test fun wakeFailureDoesNotReserveOrAuthorizeClaim() = runTest {
        val handoff = RoutineHandoff(this, { throw SecurityException("denied") }, {})
        assertNull(handoff.reserve(1)); assertFalse(handoff.isBusy)
    }
}
