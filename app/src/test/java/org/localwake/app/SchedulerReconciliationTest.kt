package org.localwake.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.scheduler.reconcileByAuthority

class SchedulerReconciliationTest {
    @Test fun unifiedDispatchCanAcquireCredentialGuardMutexWithoutSelfDeadlock() = runTest {
        val mutex = Mutex()
        val result = withTimeout(1_000) {
            reconcileByAuthority(mutex, { false }, legacy = { error("legacy must not arm") }, unified = {
                // Models resync -> tick -> native executor -> beginPatternAttempt.
                mutex.withLock { "NATIVE_EXECUTED" }
            })
        }
        assertEquals("NATIVE_EXECUTED", result)
    }

    @Test fun cutoverWhileWaitingRechecksOwnershipAndDispatchesAfterReleasingMutex() = runTest {
        val mutex = Mutex()
        mutex.lock()
        val firstCheck = CompletableDeferred<Unit>()
        var owns = true
        var legacyCalls = 0
        val pending = async {
            reconcileByAuthority(mutex, { firstCheck.complete(Unit); owns }, legacy = {
                legacyCalls++; "LEGACY_ARMED"
            }, unified = { mutex.withLock { "ROUTINE_DISPATCHED" } })
        }
        firstCheck.await()
        owns = false
        mutex.unlock()
        assertEquals("ROUTINE_DISPATCHED", withTimeout(1_000) { pending.await() })
        assertEquals(0, legacyCalls)
    }

    @Test fun legacyArmingRemainsSerializedAndDoesNotCallUnifiedScheduler() = runTest {
        val mutex = Mutex()
        assertEquals("ARMED", reconcileByAuthority(mutex, { true }, legacy = {
            assertTrue(mutex.isLocked)
            "ARMED"
        }, unified = { error("unexpected unified dispatch") }))
        assertFalse(mutex.isLocked)
    }

    @Test fun recoveryFenceDuringLegacyWorkReturnsOutsideLockedSection() = runTest {
        val mutex = Mutex()
        var owns = true
        val result = reconcileByAuthority(mutex, { owns }, legacy = {
            owns = false
            "ARMED"
        }, unified = {
            assertFalse(mutex.isLocked)
            "MIGRATION_RECOVERY_REQUIRED"
        })
        assertEquals("MIGRATION_RECOVERY_REQUIRED", result)
    }
}
