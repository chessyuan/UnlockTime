package org.localwake.app

import com.phoneuse.R

import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.background.GuardStatus

class GuardStatusTest {
    @Test fun missingServiceNeverClaimsReady() {
        assertEquals(R.string.guard_accessibility_required, GuardStatus.messageResource(false, true, true, false, false, false))
    }
    @Test fun blockedPatternNeverClaimsWaitingNormally() {
        assertEquals(R.string.guard_unlock_blocked, GuardStatus.messageResource(true, true, true, true, false, true))
    }
    @Test fun reportsMissingPermissionOrPattern() {
        assertEquals(R.string.guard_exact_alarm_required, GuardStatus.messageResource(true, false, true, false, false, false))
        assertEquals(R.string.guard_credential_required, GuardStatus.messageResource(true, true, false, false, false, false))
    }
    @Test fun distinguishesExecutingWaitingAndNoSchedule() {
        assertEquals(R.string.guard_executing, GuardStatus.messageResource(true, true, true, true, true, false))
        assertEquals(R.string.guard_next_task, GuardStatus.messageResource(true, true, true, false, false, true))
        assertEquals(R.string.guard_no_tasks, GuardStatus.messageResource(true, true, true, false, false, false))
    }
}
