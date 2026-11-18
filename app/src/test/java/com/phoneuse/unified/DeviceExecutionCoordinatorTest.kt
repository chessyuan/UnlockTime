package com.phoneuse.unified

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceExecutionCoordinatorTest {
    @Test fun concurrentAcquisitionAdmitsOnlyOneOwner() {
        val coordinator = DeviceExecutionCoordinator()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val claims = (1..16).map {
                pool.submit<DeviceSessionLease?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    coordinator.tryAcquire(DeviceOwner.AGENT)
                }
            }
            start.countDown()
            val winners = claims.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            assertTrue(coordinator.isValid(winners.single()))
            assertEquals(DeviceOwner.AGENT, coordinator.owner.value)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun staleCompletionCannotReleaseOrTransferANewerSession() {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        assertTrue(coordinator.release(old))
        val current = coordinator.tryAcquire(DeviceOwner.ROUTINE)!!

        assertFalse(coordinator.release(old))
        assertThrows(DeviceSessionRevokedException::class.java) {
            coordinator.transfer(old, DeviceOwner.UNLOCK)
        }
        assertTrue(coordinator.isValid(current))
        assertNull(coordinator.tryAcquire(DeviceOwner.AGENT))
        assertTrue(current.epoch > old.epoch)
    }

    @Test fun anotherCoordinatorsMatchingEpochAndOwnerAreNotACapability() {
        val first = DeviceExecutionCoordinator()
        val second = DeviceExecutionCoordinator()
        val valid = first.tryAcquire(DeviceOwner.AGENT)!!
        val foreign = second.tryAcquire(DeviceOwner.AGENT)!!
        assertEquals(valid.epoch, foreign.epoch)
        assertEquals(valid.owner, foreign.owner)

        assertFalse(first.isValid(foreign))
        assertFalse(first.release(foreign))
        val failure = assertThrows(DeviceSessionRevokedException::class.java) {
            first.requireValid(foreign)
        }
        assertEquals(DeviceRevocationReason.INVALID_SESSION, failure.reason)
        assertTrue(first.isValid(valid))
    }

    @Test fun transferRevokesOldEpochWithoutOpeningAnAcquisitionGap() {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.UNLOCK)!!
        val callbackReason = AtomicReference<DeviceRevocationReason>()
        val steal = AtomicReference<DeviceSessionLease?>()
        old.onRevoked { reason ->
            callbackReason.set(reason)
            steal.set(coordinator.tryAcquire(DeviceOwner.ROUTINE))
            coordinator.release(old) // Delayed completion from the old task.
        }

        val current = coordinator.transfer(old, DeviceOwner.AGENT)

        assertNotSame(old, current)
        assertFalse(coordinator.isValid(old))
        assertTrue(coordinator.isValid(current))
        assertTrue(current.epoch > old.epoch)
        assertEquals(DeviceRevocationReason.TRANSFERRED, callbackReason.get())
        assertNull(steal.get())
        assertEquals(DeviceOwner.AGENT, coordinator.owner.value)
    }

    @Test fun revokeInvalidatesBeforeNotifyingAndLateResultIsRejected() {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val cancelled = AtomicInteger()
        lease.onRevoked {
            assertFalse(coordinator.isValid(lease))
            cancelled.incrementAndGet()
        }

        assertTrue(coordinator.revoke(DeviceRevocationReason.SCREEN_LOCKED))
        assertFalse(coordinator.revoke(DeviceRevocationReason.SCREEN_OFF))
        assertEquals(1, cancelled.get())
        val failure = assertThrows(DeviceSessionRevokedException::class.java) {
            coordinator.requireValid(lease)
        }
        assertEquals(DeviceRevocationReason.SCREEN_LOCKED, failure.reason)
        assertEquals(DeviceOwner.NONE, coordinator.owner.value)
        assertTrue(coordinator.tryAcquire(DeviceOwner.UNLOCK)!!.epoch > lease.epoch)
    }

    @Test fun credentialEditRevokesAgentAndBlocksNewTasksBeforeItsCallback() {
        val coordinator = DeviceExecutionCoordinator()
        val agent = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val cancelled = AtomicInteger()
        val callbackOwner = AtomicReference<DeviceOwner>()
        agent.onRevoked {
            cancelled.incrementAndGet()
            callbackOwner.set(coordinator.owner.value)
            assertNull(coordinator.tryAcquire(DeviceOwner.AGENT))
            assertFalse(coordinator.release(agent))
        }

        val sensitive = coordinator.beginCredentialEdit()!!

        assertFalse(coordinator.isValid(agent))
        assertTrue(coordinator.isValid(sensitive))
        assertEquals(1, cancelled.get())
        assertEquals(DeviceOwner.CREDENTIAL_EDIT, callbackOwner.get())
        assertNull(coordinator.beginCredentialEdit())
        DeviceOwner.entries.filter { it != DeviceOwner.NONE }.forEach {
            assertNull(coordinator.tryAcquire(it))
        }
        assertTrue(coordinator.release(sensitive))
        assertTrue(coordinator.tryAcquire(DeviceOwner.AGENT) != null)
    }

    @Test fun revokedSensitiveSessionKeepsReservationUntilExplicitRelease() {
        for (owner in listOf(DeviceOwner.CREDENTIAL_EDIT, DeviceOwner.MIGRATION)) {
            val coordinator = DeviceExecutionCoordinator()
            val lease = coordinator.tryAcquire(owner)!!
            val cancelled = AtomicInteger()
            lease.onRevoked { cancelled.incrementAndGet() }

            assertTrue(coordinator.revoke(DeviceRevocationReason.SERVICE_DISCONNECTED))

            assertFalse(coordinator.isValid(lease))
            assertEquals(owner, coordinator.owner.value)
            assertNull(coordinator.tryAcquire(DeviceOwner.AGENT))
            assertNull(coordinator.beginCredentialEdit())
            assertEquals(1, cancelled.get())
            assertTrue(coordinator.release(lease))
            assertEquals(1, cancelled.get())
            assertTrue(coordinator.tryAcquire(DeviceOwner.AGENT) != null)
        }
    }

    @Test fun sensitiveSessionCannotBeTransferredOutWithoutExplicitRelease() {
        val coordinator = DeviceExecutionCoordinator()
        val sensitive = coordinator.beginCredentialEdit()!!
        assertThrows(IllegalStateException::class.java) {
            coordinator.transfer(sensitive, DeviceOwner.AGENT)
        }
        assertTrue(coordinator.isValid(sensitive))
        assertNull(coordinator.tryAcquire(DeviceOwner.AGENT))
    }

    @Test fun ordinaryAcquisitionNeverTakesOverAnyOwner() {
        for (owner in DeviceOwner.entries.filter { it != DeviceOwner.NONE }) {
            val coordinator = DeviceExecutionCoordinator()
            val lease = coordinator.tryAcquire(owner)!!
            assertNull(coordinator.tryAcquire(DeviceOwner.UNLOCK))
            assertNull(coordinator.tryAcquire(DeviceOwner.ROUTINE))
            assertNull(coordinator.tryAcquire(DeviceOwner.CREDENTIAL_EDIT))
            assertTrue(coordinator.isValid(lease))
        }
    }

    @Test fun revocationCallbacksDoNotHoldCoordinatorOrLeaseMonitor() {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val worker = Executors.newSingleThreadExecutor()
        val failure = AtomicReference<Throwable>()
        try {
            lease.onRevoked {
                try {
                    worker.submit {
                        // Acquires both monitors from a different thread; deadlocks if callback holds either.
                        lease.onRevoked { }
                        coordinator.tryAcquire(DeviceOwner.ROUTINE)
                    }.get(3, TimeUnit.SECONDS)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            coordinator.revoke(DeviceRevocationReason.USER_CANCELLED)
            assertNull(failure.get())
            assertEquals(DeviceOwner.ROUTINE, coordinator.owner.value)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun listenerRegistrationRacingWithRevocationNotifiesExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val coordinator = DeviceExecutionCoordinator()
                val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
                val start = CountDownLatch(1)
                val notified = AtomicInteger()
                val register = pool.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    lease.onRevoked { notified.incrementAndGet() }
                }
                val revoke = pool.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    coordinator.revoke(DeviceRevocationReason.SCREEN_LOCKED)
                }
                start.countDown()
                register.get(5, TimeUnit.SECONDS)
                revoke.get(5, TimeUnit.SECONDS)
                assertEquals(1, notified.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun disposingAListenerPreventsNotificationAndOneFailureDoesNotSkipOtherListeners() {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val disposed = AtomicInteger()
        val delivered = AtomicInteger()
        val registration = lease.onRevoked { disposed.incrementAndGet() }
        registration.close()
        registration.close()
        lease.onRevoked { error("Cancellation hook failed") }
        lease.onRevoked { delivered.incrementAndGet() }

        coordinator.release(lease)

        assertEquals(0, disposed.get())
        assertEquals(1, delivered.get())
        val lateReason = AtomicReference<DeviceRevocationReason>()
        lease.onRevoked { lateReason.set(it) }
        assertEquals(DeviceRevocationReason.RELEASED, lateReason.get())
    }

    @Test fun lateRevocationCannotCancelReplacementOwner() {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        coordinator.release(old)
        val replacement = coordinator.tryAcquire(DeviceOwner.ROUTINE)!!
        assertFalse(coordinator.revokeIfCurrent(old, DeviceRevocationReason.SERVICE_DISCONNECTED))
        assertTrue(coordinator.isValid(replacement))
    }

    @Test fun revokedResultCannotBePublishedIntoReplacementEpoch() {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        coordinator.release(old)
        val current = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        var published = false
        assertThrows(DeviceSessionRevokedException::class.java) {
            coordinator.publish(old) { published = true }
        }
        assertFalse(published)
        coordinator.publish(current) { published = true }
        assertTrue(published)
    }

    @Test fun noneCannotBeAcquiredOrTransferredTo() {
        val coordinator = DeviceExecutionCoordinator()
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.tryAcquire(DeviceOwner.NONE)
        }
        val lease = coordinator.tryAcquire(DeviceOwner.ROUTINE)!!
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.transfer(lease, DeviceOwner.NONE)
        }
        assertTrue(coordinator.isValid(lease))
    }

    @Test fun ownActivityStartRevokesOnlyAgentAndRoutineOwners() {
        for (owner in DeviceOwner.entries.filter { it != DeviceOwner.NONE }) {
            val coordinator = DeviceExecutionCoordinator()
            val lease = coordinator.tryAcquire(owner)!!
            val epoch = coordinator.currentEpoch
            val notifications = AtomicInteger()
            lease.onRevoked { notifications.incrementAndGet() }
            val screenOwner = owner == DeviceOwner.AGENT || owner == DeviceOwner.ROUTINE

            assertEquals(screenOwner, coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))
            assertEquals(!screenOwner, coordinator.isValid(lease))
            assertEquals(if (screenOwner) 1 else 0, notifications.get())
            if (screenOwner) {
                assertEquals(DeviceRevocationReason.SENSITIVE_SESSION, lease.revocationReason)
                assertEquals(DeviceOwner.NONE, coordinator.owner.value)
                assertTrue(coordinator.currentEpoch > epoch)
            } else {
                assertEquals(owner, coordinator.owner.value)
                assertEquals(epoch, coordinator.currentEpoch)
            }
        }
    }

    @Test fun ownActivityStartWithoutScreenLeaseDoesNotChangePureChatEpoch() {
        val coordinator = DeviceExecutionCoordinator()
        val epoch = coordinator.currentEpoch
        assertFalse(coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))
        assertEquals(epoch, coordinator.currentEpoch)
        assertEquals(DeviceOwner.NONE, coordinator.owner.value)
    }

    @Test fun ownActivityRevocationDoesNotCancelAReplacementStartedByItsCallback() {
        val coordinator = DeviceExecutionCoordinator()
        val old = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val replacement = AtomicReference<DeviceSessionLease>()
        old.onRevoked { replacement.set(coordinator.beginCredentialEdit()) }

        assertTrue(coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))

        val sensitive = replacement.get()!!
        assertEquals(DeviceOwner.CREDENTIAL_EDIT, coordinator.owner.value)
        assertTrue(coordinator.isValid(sensitive))
        assertFalse(coordinator.release(old))
        assertFalse(coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))
        assertTrue(coordinator.isValid(sensitive))
    }

    @Test fun ownActivityRevocationDoesNotPretendAcceptedAndroidInputHasFinished() {
        val coordinator = DeviceExecutionCoordinator()
        val lease = coordinator.tryAcquire(DeviceOwner.AGENT)!!
        val fence = DeviceInputFence()
        val ticket = coordinator.publish(lease) { fence.tryBegin() }!!

        assertTrue(coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION))
        assertFalse(fence.isIdle)
        assertNull(fence.tryBegin())
        ticket.complete() // Only the real platform callback/rejection completes the ticket.
        assertTrue(fence.isIdle)
    }
}
