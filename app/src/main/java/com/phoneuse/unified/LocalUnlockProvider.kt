package com.phoneuse.unified

/** Local-only preflight. Neither method returns credentials, a screen, nor an arbitrary failure message. */
interface LocalUnlockProvider {
    suspend fun isReady(): Boolean
    suspend fun ensureUnlocked(): UnlockResult
}

enum class UnlockResult {
    ALREADY_UNLOCKED,
    UNLOCKED,
    MANUAL_UNLOCK_REQUIRED,
    FIRST_MANUAL_UNLOCK_REQUIRED,
    CREDENTIAL_MISSING,
    CREDENTIAL_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    UNSUPPORTED,
    REJECTED,
    TIMEOUT,
    PAUSED,
    BUSY,
    UNAVAILABLE;

    val succeeded: Boolean get() = this == ALREADY_UNLOCKED || this == UNLOCKED
}

internal enum class LocalCredentialAvailability { READY, EMPTY, USER_LOCKED, UNAVAILABLE }

/** Lazy checks make the no-credential-read ordering independently testable. */
internal interface UnlockPreflightState {
    fun fullyUnlocked(): Boolean
    fun unlockedSinceBoot(): Boolean
    fun automaticUnlockEnabled(): Boolean
    fun serviceReady(): Boolean
    suspend fun attemptPaused(): Boolean
    suspend fun credentialAvailability(): LocalCredentialAvailability
}

/** null means input may be considered; it is not proof that an unlock attempt succeeded. */
internal suspend fun unlockPreflight(state: UnlockPreflightState): UnlockResult? {
    if (state.fullyUnlocked()) return UnlockResult.ALREADY_UNLOCKED
    if (!state.unlockedSinceBoot()) return UnlockResult.FIRST_MANUAL_UNLOCK_REQUIRED
    if (!state.automaticUnlockEnabled()) return UnlockResult.MANUAL_UNLOCK_REQUIRED
    if (!state.serviceReady()) return UnlockResult.SERVICE_UNAVAILABLE
    if (state.attemptPaused()) return UnlockResult.PAUSED
    return when (state.credentialAvailability()) {
        LocalCredentialAvailability.READY -> null
        LocalCredentialAvailability.EMPTY -> UnlockResult.CREDENTIAL_MISSING
        LocalCredentialAvailability.USER_LOCKED -> UnlockResult.FIRST_MANUAL_UNLOCK_REQUIRED
        LocalCredentialAvailability.UNAVAILABLE -> UnlockResult.CREDENTIAL_UNAVAILABLE
    }
}

/** Only fixed safe statuses cross into the unified application; never forward engine text. */
internal fun unlockEngineResult(code: String?, fullyUnlocked: Boolean): UnlockResult = when {
    code == null -> if (fullyUnlocked) UnlockResult.UNLOCKED else UnlockResult.UNAVAILABLE
    code == "FIRST_MANUAL_UNLOCK_REQUIRED" || code == "CREDENTIAL_USER_LOCKED" -> UnlockResult.FIRST_MANUAL_UNLOCK_REQUIRED
    code == "MANUAL_RESET_REQUIRED" -> UnlockResult.PAUSED
    code == "CREDENTIAL_EMPTY" -> UnlockResult.CREDENTIAL_MISSING
    code.startsWith("CREDENTIAL_") && code != "CREDENTIAL_INPUT_REJECTED" -> UnlockResult.CREDENTIAL_UNAVAILABLE
    code == "SERVICE_DISCONNECTED" -> UnlockResult.SERVICE_UNAVAILABLE
    code in setOf("PATTERN_GRID_UNAVAILABLE", "PIN_KEYPAD_UNAVAILABLE", "PASSWORD_FIELD_UNAVAILABLE") -> UnlockResult.UNSUPPORTED
    code in setOf("AUTHENTICATION_REJECTED", "CREDENTIAL_INPUT_REJECTED", "PATTERN_GESTURE_REJECTED") -> UnlockResult.REJECTED
    code == "PATTERN_GESTURE_TIMEOUT" -> UnlockResult.TIMEOUT
    code == "KEYGUARD_STILL_LOCKED" -> UnlockResult.MANUAL_UNLOCK_REQUIRED
    else -> UnlockResult.UNAVAILABLE
}
