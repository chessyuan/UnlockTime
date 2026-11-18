package com.phoneuse.ui.settings

import com.phoneuse.routine.MigrationPhase
import com.phoneuse.routine.RoutineAuthorityState
import com.phoneuse.routine.RoutineOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSetupRecoveryTest {
    @Test fun interruptedSetupOffersRetryWithoutSuggestingAReset() {
        val interrupted = RoutineAuthorityState(
            phase = MigrationPhase.RECOVERY_REQUIRED, owner = RoutineOwner.NONE,
            failureCode = "STARTUP_MIGRATION_FAILED", interruptedPhase = MigrationPhase.BACKED_UP,
        )
        assertEquals(TaskSetupAction.RETRY, taskSetupAction(interrupted, storeReady = true))
    }

    @Test fun corruptAuthorityNeverOffersAutomaticRetry() {
        val corrupt = RoutineAuthorityState(
            phase = MigrationPhase.RECOVERY_REQUIRED, owner = RoutineOwner.NONE,
            failureCode = "AUTHORITY_RECOVERY_REQUIRED",
        )
        assertEquals(TaskSetupAction.REPAIR_REQUIRED, taskSetupAction(corrupt, storeReady = true))
    }

    @Test fun unreadableTargetNeverLooksReadyOrOffersAutomaticRetry() {
        val active = RoutineAuthorityState(phase = MigrationPhase.MIGRATION_COMPLETE, owner = RoutineOwner.ROUTINE)
        assertEquals(TaskSetupAction.REPAIR_REQUIRED, taskSetupAction(active, storeReady = false))
    }

    @Test fun successfulCutoverRemovesTheRecoveryAction() {
        val active = RoutineAuthorityState(phase = MigrationPhase.MIGRATION_COMPLETE, owner = RoutineOwner.ROUTINE)
        assertEquals(TaskSetupAction.READY, taskSetupAction(active, storeReady = true))
    }

    @Test fun incompleteSetupRemainsRecoverableWithoutRunningTasks() {
        assertEquals(TaskSetupAction.RETRY, taskSetupAction(RoutineAuthorityState(), storeReady = true))
        assertEquals(TaskSetupAction.RETRY, taskSetupAction(RoutineAuthorityState(
            phase = MigrationPhase.PREPARING, owner = RoutineOwner.NONE,
        ), storeReady = true))
    }
}
