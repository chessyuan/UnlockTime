package com.phoneuse.unified

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NormalSurfacePolicyTest {
    @Test fun missingApplicationOrPackageIsIncompleteAndOwnPaneAlwaysWins() {
        assertEquals(NormalSurfaceState.INCOMPLETE, NormalSurfacePolicy.classify(emptyList(), "own"))
        assertEquals(NormalSurfaceState.INCOMPLETE, NormalSurfacePolicy.classify(listOf("settings", null), "own"))
        assertEquals(NormalSurfaceState.INCOMPLETE, NormalSurfacePolicy.classify(listOf(""), "own"))
        assertEquals(NormalSurfaceState.OWN, NormalSurfacePolicy.classify(listOf(null, "own", "settings"), "own"))
        assertEquals(NormalSurfaceState.SAFE, NormalSurfacePolicy.classify(listOf("settings", "other"), "own"))
    }

    @Test fun incompleteWindowNeverReadsUntilACompleteWindowArrives() = runTest {
        var state = NormalSurfaceState.INCOMPLETE
        var reads = 0
        val pending = async {
            NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                NormalSurfacePolicy.require(state) { error("own") }
            }) { reads++; "safe tree" }
        }
        runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(100)
        assertEquals(0, reads)
        state = NormalSurfaceState.SAFE
        assertEquals("safe tree", pending.await())
        assertEquals(1, reads)
    }

    @Test fun persistentUnknownTimesOutWithoutPublishingOrExecutingRead() = runTest {
        var reads = 0
        val failure = runCatching {
            NormalSurfacePolicy.read(200, { testScheduler.currentTime }, {
                throw NormalSurfaceIncompleteException()
            }) { reads++ }
        }.exceptionOrNull()
        assertTrue(failure is NormalSurfaceTimeoutException)
        assertEquals(200L, testScheduler.currentTime)
        assertEquals(0, reads)
    }

    @Test fun rootDisappearingAfterGuardRetriesTheActualRead() = runTest {
        var reads = 0
        val value = NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {}) {
            reads++
            if (reads == 1) throw NormalSurfaceIncompleteException()
            "complete tree"
        }
        assertEquals("complete tree", value)
        assertEquals(2, reads)
        assertTrue(testScheduler.currentTime in 1..499)
    }

    @Test fun knownOwnWindowRevokesImmediatelyWithoutWaiting() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val failure = runCatching {
            NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                NormalSurfacePolicy.require(NormalSurfaceState.OWN) {
                    coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SENSITIVE_SESSION)
                    coordinator.requireValid(lease)
                }
            }) { error("must not read own surface") }
        }.exceptionOrNull()
        assertEquals(DeviceRevocationReason.SENSITIVE_SESSION, (failure as DeviceSessionRevokedException).reason)
        assertFalse(coordinator.isValid(lease))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun relockDuringUnknownWaitStopsBeforeNextRead() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val pending = async {
            runCatching {
                NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                    coordinator.requireValid(lease)
                    throw NormalSurfaceIncompleteException()
                }) { error("locked read") }
            }.exceptionOrNull()
        }
        runCurrent()
        coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SCREEN_LOCKED)
        assertEquals(DeviceRevocationReason.SCREEN_LOCKED, (pending.await() as DeviceSessionRevokedException).reason)
        assertTrue(testScheduler.currentTime < 500)
    }

    @Test fun credentialReservationDuringWaitKeepsSensitiveOwnerAndRejectsOldTask() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val pending = async {
            runCatching {
                NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                    coordinator.requireValid(lease)
                    throw NormalSurfaceIncompleteException()
                }) { error("credential read") }
            }.exceptionOrNull()
        }
        runCurrent()
        val sensitive = coordinator.beginCredentialEdit()!!
        assertEquals(DeviceRevocationReason.SENSITIVE_SESSION, (pending.await() as DeviceSessionRevokedException).reason)
        assertEquals(DeviceOwner.CREDENTIAL_EDIT, coordinator.owner.value)
        assertTrue(coordinator.isValid(sensitive))
    }

    @Test fun cancelledWaitCannotResumeWhenSafeWindowAppears() = runTest {
        var state = NormalSurfaceState.INCOMPLETE
        var reads = 0
        val pending = launch {
            NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                NormalSurfacePolicy.require(state) { error("own") }
            }) { reads++ }
        }
        runCurrent()
        pending.cancelAndJoin()
        state = NormalSurfaceState.SAFE
        advanceTimeBy(500)
        assertTrue(pending.isCancelled)
        assertEquals(0, reads)
    }

    @Test fun windowBecomingIncompleteDuringReadDiscardsThatResultAndReadsAgain() = runTest {
        var state = NormalSurfaceState.SAFE
        var reads = 0
        val discarded = mutableListOf<String>()
        launch { kotlinx.coroutines.delay(100); state = NormalSurfaceState.SAFE }
        val value = NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
            NormalSurfacePolicy.require(state) { error("own") }
        }, discard = { discarded += it }) {
            reads++
            if (reads == 1) { state = NormalSurfaceState.INCOMPLETE; "discarded pixels" } else "fresh pixels"
        }
        assertEquals("fresh pixels", value)
        assertEquals(listOf("discarded pixels"), discarded)
        assertEquals(2, reads)
    }

    @Test fun leaseRevokedDuringReadDiscardsResultWithoutRetry() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        var discarded = false
        var reads = 0
        val failure = runCatching {
            NormalSurfacePolicy.read(500, { testScheduler.currentTime }, { coordinator.requireValid(lease) },
                discard = { _: String -> discarded = true }) {
                reads++
                coordinator.revokeIfCurrent(lease, DeviceRevocationReason.TASK_CHANGED)
                "must not publish"
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(discarded)
        assertEquals(1, reads)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun ownActivityAppearingAndLeavingDuringCaptureCannotReviveItsLatePixels() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        var replacement: DeviceSessionLease? = null
        var reads = 0
        val latePixels = byteArrayOf(1, 2, 3)
        val failure = runCatching {
            NormalSurfacePolicy.read(500, { testScheduler.currentTime }, {
                coordinator.requireValid(old)
                // The ordinary app is visible again when the delayed capture completes.
                NormalSurfacePolicy.require(NormalSurfaceState.SAFE) { error("unexpected own surface") }
            }, discard = { bytes: ByteArray -> bytes.fill(0) }) {
                reads++
                // Trusted Activity lifecycle notification, independent of callback-time window metadata.
                assertTrue(coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))
                replacement = coordinator.tryAcquire(DeviceOwner.AGENT)
                latePixels
            }
        }.exceptionOrNull()

        assertEquals(DeviceRevocationReason.SENSITIVE_SESSION, (failure as DeviceSessionRevokedException).reason)
        assertArrayEquals(byteArrayOf(0, 0, 0), latePixels)
        assertEquals(1, reads)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(coordinator.isValid(requireNotNull(replacement)))
        assertFalse(coordinator.release(old))
    }
}
