package com.phoneuse.app

import com.phoneuse.routine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRunDeliveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cancelledPublishedLazyJobFinishesClaimWithoutStartingOrReplaying() = runTest {
        val now = Instant.parse("2026-11-16T00:00:00Z")
        val file = temp.newFolder().resolve("routines.json")
        val store = RoutineStore(file)
        store.save(Routine(title = "Once", trigger = Trigger.Once(now.plusSeconds(30).toEpochMilli()),
            action = RoutineAction.Ask("inspect", needsDevice = true)))
        val pending = store.armTimes(now, ZoneId.of("UTC"), 2).single()
        val (routine, occurrence) = store.claim(pending.token, pending.dueAt, 2)!!
        store.setPatternBlocked(true)
        var callbacks = 0
        var sideEffects = 0
        val delivery = RoutineRunDelivery {
            callbacks++
            assertTrue(store.recordAgentResult(routine, occurrence, it, pending.dueAt + 1))
        }
        val slot = RunSlot()
        val job = slot.claim {
            launch(start = CoroutineStart.LAZY) {
                if (delivery.markStarted()) sideEffects++
            }
        }!!
        // stop() may win even before send() has registered invokeOnCompletion.
        job.cancel()
        job.invokeOnCompletion { cause -> delivery.completeBeforeStart(cause); slot.release(job) }
        assertFalse(job.start())
        job.join()
        assertEquals(0, sideEffects)
        assertEquals(1, callbacks)
        assertFalse(slot.running.value)
        val reopened = RoutineStore(file)
        assertEquals("AGENT_STOPPED", reopened.localSnapshot().history.single().code)
        assertFalse(reopened.get(routine.id)!!.enabled)
        assertTrue(reopened.patternBlocked)
        assertNull(reopened.claim(pending.token, pending.dueAt + 2, 2))
        assertTrue(reopened.pending(2).isEmpty())
    }

    @Test fun cancellationAfterStartCallButBeforeDispatchStillDeliversStoppedOnce() = runTest {
        val results = mutableListOf<RoutineResult>()
        var entered = false
        val delivery = RoutineRunDelivery { results += it }
        val job = launch(start = CoroutineStart.LAZY) {
            if (delivery.markStarted()) entered = true
        }
        job.invokeOnCompletion { delivery.completeBeforeStart(it) }
        assertTrue(job.start())
        job.cancelAndJoin()
        assertFalse(entered)
        assertEquals(listOf(RunOutcome.CANCELLED), results.map { it.outcome })
    }

    @Test fun startedCancellationUsesBodyFinalizationWithoutDuplicateFallback() = runTest {
        val results = mutableListOf<RoutineResult>()
        val delivery = RoutineRunDelivery { results += it }
        val job = launch(start = CoroutineStart.LAZY) {
            if (delivery.markStarted()) {
                try { awaitCancellation() }
                finally { delivery.complete(RoutineResult(RunOutcome.CANCELLED, "Stopped")) }
            }
        }
        job.invokeOnCompletion { delivery.completeBeforeStart(it) }
        job.start()
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, results.size)
        assertEquals("Stopped", results.single().reply)
    }

    @Test fun normalBodyResultWinsOverCompletionHandlerAndLateDuplicates() = runTest {
        val results = mutableListOf<RoutineResult>()
        val delivery = RoutineRunDelivery { results += it }
        val job = launch(start = CoroutineStart.LAZY) {
            if (delivery.markStarted()) delivery.complete(RoutineResult(RunOutcome.FINISHED, "flow finished"))
        }
        job.invokeOnCompletion { delivery.completeBeforeStart(it) }
        job.start()
        job.join()
        assertFalse(delivery.complete(RoutineResult(RunOutcome.CANCELLED, null)))
        assertFalse(delivery.completeBeforeStart(CancellationException()))
        assertEquals(listOf(RunOutcome.FINISHED), results.map { it.outcome })
    }

    @Test fun concurrentEntryAndPreEntryCancellationHaveOneWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val callbacks = AtomicInteger()
                val entered = AtomicInteger()
                val outcome = AtomicReference<RunOutcome>()
                val delivery = RoutineRunDelivery { callbacks.incrementAndGet(); outcome.set(it.outcome) }
                val start = CountDownLatch(1)
                val body = pool.submit {
                    start.await()
                    if (delivery.markStarted()) {
                        entered.incrementAndGet()
                        delivery.complete(RoutineResult(RunOutcome.FINISHED, null))
                    }
                }
                val cancel = pool.submit { start.await(); delivery.completeBeforeStart(CancellationException()) }
                start.countDown()
                body.get(5, TimeUnit.SECONDS)
                cancel.get(5, TimeUnit.SECONDS)
                assertEquals(1, callbacks.get())
                assertEquals(if (entered.get() == 1) RunOutcome.FINISHED else RunOutcome.CANCELLED, outcome.get())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun failedPersistenceIsNotRetriedByASecondTerminalPath() {
        var callbacks = 0
        val delivery = RoutineRunDelivery { callbacks++; throw java.io.IOException("test write failure") }
        assertTrue(delivery.markStarted())
        assertTrue(runCatching { delivery.complete(RoutineResult(RunOutcome.FAILED, null)) }.isFailure)
        assertFalse(delivery.completeBeforeStart(CancellationException()))
        assertFalse(delivery.complete(RoutineResult(RunOutcome.FAILED, null)))
        assertEquals(1, callbacks)
    }
}
