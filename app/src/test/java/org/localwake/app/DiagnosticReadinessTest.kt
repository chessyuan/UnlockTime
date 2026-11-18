package org.localwake.app

import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.execution.DiagnosticReadiness

class DiagnosticReadinessTest {
    @Test fun disconnectedServiceCannotCreateMisleadingUnlockTest() {
        assertEquals("ACCESSIBILITY_DISCONNECTED", DiagnosticReadiness.blocker(false, true, true, false, false))
    }
    @Test fun wakePermissionAloneIsNotEnoughForFullUnlock() {
        assertEquals("CREDENTIAL_NOT_READY", DiagnosticReadiness.blocker(true, true, false, false, false))
        assertEquals("EXACT_ALARM_DENIED", DiagnosticReadiness.blocker(true, false, true, false, false))
    }
    @Test fun repeatedTapsCannotQueueSeveralPatternAttempts() {
        assertEquals("TEST_ALREADY_PENDING", DiagnosticReadiness.blocker(true, true, true, false, true))
        assertEquals("MANUAL_RESET_REQUIRED", DiagnosticReadiness.blocker(true, true, true, true, false))
    }
    @Test fun readyFullTestIsAllowed() {
        assertNull(DiagnosticReadiness.blocker(true, true, true, false, false))
    }
}
