package com.phoneuse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.localwake.app.security.CredentialState

class DeviceReadinessTest {
    @Test fun migrationFailureCannotLookReadyOrLikeAnEmptyTaskList() {
        assertEquals(DeviceReadinessStatus.TASKS_UNAVAILABLE,
            deviceReadiness(true, true, CredentialState.READY, false, true, tasksAvailable = false))
    }
    @Test fun disconnectedServiceCannotBeReportedReady() {
        assertEquals(DeviceReadinessStatus.SERVICE_REQUIRED,
            deviceReadiness(false, true, CredentialState.READY, false, true))
    }

    @Test fun disabledAutomaticUnlockDoesNotRequestAPasswordSetup() {
        assertEquals(DeviceReadinessStatus.AUTO_UNLOCK_OFF,
            deviceReadiness(true, false, CredentialState.EMPTY, false, true))
    }

    @Test fun failedUnlockLatchTakesPriorityOverReadyCredential() {
        assertEquals(DeviceReadinessStatus.UNLOCK_PAUSED,
            deviceReadiness(true, true, CredentialState.READY, true, true))
    }

    @Test fun unknownAndCorruptCredentialsAreNeverReady() {
        assertEquals(DeviceReadinessStatus.CHECKING, deviceReadiness(true, true, null, false, true))
        assertEquals(DeviceReadinessStatus.CREDENTIAL_UNAVAILABLE,
            deviceReadiness(true, true, CredentialState.CORRUPT, false, true))
        assertEquals(DeviceReadinessStatus.FIRST_UNLOCK_REQUIRED,
            deviceReadiness(true, true, CredentialState.USER_LOCKED, false, true))
    }

    @Test fun scheduledTasksRequireAlarmPermissionEvenWithValidCredentials() {
        assertEquals(DeviceReadinessStatus.EXACT_ALARMS_REQUIRED,
            deviceReadiness(true, true, CredentialState.READY, false, false))
        assertEquals(DeviceReadinessStatus.READY,
            deviceReadiness(true, true, CredentialState.READY, false, true))
    }
}
