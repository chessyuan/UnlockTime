package com.phoneuse.service

import com.phoneuse.unified.DeviceRevocationReason
import com.phoneuse.unified.DeviceSessionRevokedException
import com.phoneuse.unified.NormalSurfaceIncompleteException
import com.phoneuse.unified.NormalSurfacePolicy
import com.phoneuse.unified.NormalSurfaceTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceSampleTest {
    @Test fun shortSampleReachesSettleCapThenFullReadCanRecover() = runTest {
        var complete = false
        var actualReads = 0
        val guard = { if (!complete) throw NormalSurfaceIncompleteException() }
        val waiter = IdleWaiter { testScheduler.currentTime }
        waiter.onEvent("other", IdleWaiter.TYPE_WINDOW_STATE_CHANGED)
        launch { delay(150); complete = true }
        val how = waiter.settle(SettlePolicy(capMs = 100), waitForChange = false) { deadline ->
            val sample = readSurfaceSample {
                NormalSurfacePolicy.read(deadline - testScheduler.currentTime, { testScheduler.currentTime }, guard) {
                    actualReads++
                    ScreenSample(1, busy = false)
                }
            }
            assertTrue(sample.busy)
            assertTrue(sample.truncated)
            assertEquals(0, actualReads)
            sample
        }
        assertEquals(Settled.CAP, how)
        val full = NormalSurfacePolicy.read(500, { testScheduler.currentTime }, guard) {
            actualReads++
            "complete tree"
        }
        assertEquals("complete tree", full)
        assertEquals(1, actualReads)
    }

    @Test fun persistentUnknownStillStopsTheFinalFullRead() = runTest {
        var reads = 0
        val guard = { throw NormalSurfaceIncompleteException() }
        val sample = readSurfaceSample {
            NormalSurfacePolicy.read(50, { testScheduler.currentTime }, guard) {
                reads++
                ScreenSample(1, busy = false)
            }
        }
        assertEquals(ScreenSample(0, busy = true, truncated = true), sample)
        val failure = runCatching {
            NormalSurfacePolicy.read(200, { testScheduler.currentTime }, guard) { reads++ }
        }.exceptionOrNull()
        assertTrue(failure is NormalSurfaceTimeoutException)
        assertEquals(0, reads)
        assertEquals(250L, testScheduler.currentTime)
    }

    @Test fun ownRelockAndTaskRevocationsAreNotConvertedIntoSamples() = runTest {
        for (reason in listOf(DeviceRevocationReason.SENSITIVE_SESSION, DeviceRevocationReason.SCREEN_LOCKED,
            DeviceRevocationReason.TASK_CHANGED, DeviceRevocationReason.SERVICE_DISCONNECTED)) {
            val revoked = DeviceSessionRevokedException(reason)
            val failure = runCatching { readSurfaceSample { throw revoked } }.exceptionOrNull()
            assertSame(revoked, failure)
        }
    }

    @Test fun ordinaryTaskCancellationDoesNotReturnASample() = runTest {
        var returned = false
        val job = launch {
            readSurfaceSample { awaitCancellation() }
            returned = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(returned)
        assertTrue(job.isCancelled)
    }

    @Test fun cancellationRacingTheDedicatedTimeoutIsNotSwallowed() = runTest {
        var returned = false
        val job = launch {
            readSurfaceSample {
                currentCoroutineContext().cancel()
                throw NormalSurfaceTimeoutException()
            }
            returned = true
        }
        job.join()
        assertFalse(returned)
        assertTrue(job.isCancelled)
    }
}
