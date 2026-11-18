package com.phoneuse.unified

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceInputFenceTest {
    @Test fun coroutineCancellationDoesNotProvePlatformInputEnded() = runTest {
        val fence = DeviceInputFence()
        var callback: DeviceInputFence.Ticket? = null
        val initiatingJob = launch {
            callback = fence.tryBegin()
            awaitCancellation()
        }
        runCurrent()
        initiatingJob.cancelAndJoin()
        assertFalse(fence.isIdle)
        assertNull(fence.tryBegin())
        val sensitiveUi = async { fence.awaitIdle() }
        runCurrent()
        assertFalse(sensitiveUi.isCompleted)
        callback!!.complete() // Only the platform callback admits the sensitive UI.
        assertTrue(sensitiveUi.await())
    }

    @Test fun missingCallbackTimesOutWithoutPretendingTheInputStopped() = runTest {
        val fence = DeviceInputFence()
        val callback = fence.tryBegin()!!
        assertFalse(fence.awaitIdle(100))
        assertFalse(fence.isIdle)
        assertNull(fence.tryBegin())
        callback.complete()
        assertTrue(fence.awaitIdle(0))
    }

    @Test fun aLateDuplicateCallbackCannotReleaseAnotherInput() = runTest {
        val fence = DeviceInputFence()
        val old = fence.tryBegin()!!
        old.complete()
        val current = fence.tryBegin()!!
        old.complete()
        assertFalse(fence.awaitIdle(0))
        current.complete()
        assertTrue(fence.awaitIdle(0))
    }

    @Test fun synchronousRejectionOrDispatchExceptionCanReleaseItsOwnReservation() = runTest {
        val fence = DeviceInputFence()
        val rejected = fence.tryBegin()!!
        rejected.complete()
        assertTrue(fence.isIdle)
        val failed = fence.tryBegin()!!
        try { throw IllegalStateException("synthetic dispatch failure") }
        catch (_: IllegalStateException) { failed.complete() }
        assertNotNull(fence.tryBegin())
    }

    @Test fun cancellingAnIdleWaiterDoesNotReleaseAnAcceptedGesture() = runTest {
        val fence = DeviceInputFence()
        val callback = fence.tryBegin()!!
        val waitingOwner = launch { fence.awaitIdle() }
        runCurrent()
        waitingOwner.cancelAndJoin()
        assertFalse(fence.isIdle)
        callback.complete()
        assertTrue(fence.isIdle)
    }

    @Test fun anOldLocalContextCannotBorrowTheNextUnlockOwnersIdentity() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val fence = DeviceInputFence()
        val old = coordinator.tryAcquire(DeviceOwner.UNLOCK)!!
        coordinator.release(old)
        val replacement = coordinator.tryAcquire(DeviceOwner.UNLOCK)!!
        LocalInputSession.withLease(old) {
            try {
                coordinator.publish(LocalInputSession.currentLease()!!) { fence.tryBegin() }
                fail("stale local work used a newer UNLOCK owner")
            } catch (_: DeviceSessionRevokedException) { }
        }
        assertNull(LocalInputSession.currentLease())
        assertTrue(coordinator.isValid(replacement))
        assertTrue(fence.isIdle)
    }

    @Test fun sensitiveOwnershipSeesAnAtomicallyRegisteredPlatformInput() = runTest {
        val coordinator = DeviceExecutionCoordinator()
        val fence = DeviceInputFence()
        val run = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val platform = coordinator.publish(run) { fence.tryBegin()!! }
        val sensitive = coordinator.beginCredentialEdit()!!
        assertTrue(coordinator.isValid(sensitive))
        assertFalse(fence.awaitIdle(0))
        try {
            coordinator.publish(run) { fence.tryBegin() }
            fail("revoked input registered after sensitive UI reserved ownership")
        } catch (_: DeviceSessionRevokedException) { }
        platform.complete()
        assertTrue(fence.awaitIdle(0))
    }
}
