package org.localwake.app.ui

import com.phoneuse.routine.Conditions
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.Trigger
import org.localwake.app.scheduler.ExecutionMode

/** A saved native one-shot, visible in the same task list; never invokes a model. */
internal fun localUnlockTestRoutine(title: String, nowMs: Long): Routine = Routine(
    title = title,
    trigger = Trigger.Once(Math.addExact(nowMs, 10_000L)),
    conditions = Conditions(cooldownMin = 0),
    action = RoutineAction.OpenAppsSequence(emptyList(), ExecutionMode.FULL),
)
