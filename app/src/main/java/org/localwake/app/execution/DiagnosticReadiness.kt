package org.localwake.app.execution

object DiagnosticReadiness {
    fun blocker(connected: Boolean, exact: Boolean, credentialReady: Boolean, patternBlocked: Boolean, pending: Boolean): String? = when {
        !connected -> "ACCESSIBILITY_DISCONNECTED"
        !exact -> "EXACT_ALARM_DENIED"
        !credentialReady -> "CREDENTIAL_NOT_READY"
        patternBlocked -> "MANUAL_RESET_REQUIRED"
        pending -> "TEST_ALREADY_PENDING"
        else -> null
    }
}
