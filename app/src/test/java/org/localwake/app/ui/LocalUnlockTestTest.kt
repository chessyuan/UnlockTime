package org.localwake.app.ui

import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localwake.app.scheduler.ExecutionMode

class LocalUnlockTestTest {
    @Test fun diagnosticIsANativeUnlockOnlyTaskDueInTenSeconds() {
        val routine = localUnlockTestRoutine("Local test", 1_000_000L)
        assertEquals(Trigger.Once(1_010_000L), routine.trigger)
        val action = routine.action as RoutineAction.OpenAppsSequence
        assertTrue(action.apps.isEmpty())
        assertEquals(ExecutionMode.FULL, action.mode)
        assertEquals(0, routine.conditions.cooldownMin)
        assertTrue(routine.enabled)
        assertEquals(0L, routine.id)
    }
}
