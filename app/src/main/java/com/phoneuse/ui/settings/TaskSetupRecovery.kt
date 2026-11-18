package com.phoneuse.ui.settings

import com.phoneuse.routine.MigrationPhase
import com.phoneuse.routine.RoutineAuthorityState
import com.phoneuse.routine.RoutineOwner

internal enum class TaskSetupAction { READY, RETRY, REPAIR_REQUIRED }

/** Presentation policy only; the recovery operation independently validates the durable journal. */
internal fun taskSetupAction(authority: RoutineAuthorityState, storeReady: Boolean): TaskSetupAction = when {
    authority.failureCode == "AUTHORITY_RECOVERY_REQUIRED" || !storeReady -> TaskSetupAction.REPAIR_REQUIRED
    authority.owner == RoutineOwner.ROUTINE && authority.phase != MigrationPhase.RECOVERY_REQUIRED -> TaskSetupAction.READY
    else -> TaskSetupAction.RETRY
}
