package com.phoneuse.ui.settings

import org.localwake.app.security.CredentialState

internal enum class DeviceReadinessStatus {
    CHECKING, SERVICE_REQUIRED, AUTO_UNLOCK_OFF, FIRST_UNLOCK_REQUIRED, CREDENTIAL_REQUIRED,
    CREDENTIAL_UNAVAILABLE, UNLOCK_PAUSED, EXACT_ALARMS_REQUIRED, TASKS_UNAVAILABLE, READY,
}

/** Local status presentation only; never substitutes for the execution-time access gate. */
internal fun deviceReadiness(
    connected: Boolean,
    automaticUnlock: Boolean,
    credential: CredentialState?,
    unlockPaused: Boolean,
    exactAlarms: Boolean,
    tasksAvailable: Boolean = true,
): DeviceReadinessStatus = when {
    !tasksAvailable -> DeviceReadinessStatus.TASKS_UNAVAILABLE
    !connected -> DeviceReadinessStatus.SERVICE_REQUIRED
    !automaticUnlock -> DeviceReadinessStatus.AUTO_UNLOCK_OFF
    credential == null -> DeviceReadinessStatus.CHECKING
    credential == CredentialState.USER_LOCKED -> DeviceReadinessStatus.FIRST_UNLOCK_REQUIRED
    credential == CredentialState.EMPTY -> DeviceReadinessStatus.CREDENTIAL_REQUIRED
    credential != CredentialState.READY -> DeviceReadinessStatus.CREDENTIAL_UNAVAILABLE
    unlockPaused -> DeviceReadinessStatus.UNLOCK_PAUSED
    !exactAlarms -> DeviceReadinessStatus.EXACT_ALARMS_REQUIRED
    else -> DeviceReadinessStatus.READY
}
