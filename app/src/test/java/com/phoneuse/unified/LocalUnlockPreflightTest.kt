package com.phoneuse.unified

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUnlockPreflightTest {
    @Test fun alreadyUnlockedDoesNotInspectAnyCredentialOrSetting() = runTest {
        val state = FakeState().apply { unlocked = true; enabled = false; paused = true }
        assertEquals(UnlockResult.ALREADY_UNLOCKED, unlockPreflight(state))
        assertEquals(listOf("unlocked"), state.calls)
    }

    @Test fun firstBootRequiresManualUnlockWithoutOpeningCredentialStorage() = runTest {
        val state = FakeState().apply { bootUnlocked = false }
        assertEquals(UnlockResult.FIRST_MANUAL_UNLOCK_REQUIRED, unlockPreflight(state))
        assertEquals(listOf("unlocked", "boot"), state.calls)
    }

    @Test fun disabledGlobalSettingDoesNotInspectServiceOrCredential() = runTest {
        val state = FakeState().apply { enabled = false }
        assertEquals(UnlockResult.MANUAL_UNLOCK_REQUIRED, unlockPreflight(state))
        assertEquals(listOf("unlocked", "boot", "enabled"), state.calls)
    }

    @Test fun serviceMustBeActuallyReadyBeforeReadingCredentials() = runTest {
        val state = FakeState().apply { service = false }
        assertEquals(UnlockResult.SERVICE_UNAVAILABLE, unlockPreflight(state))
        assertFalse(state.calls.contains("credential"))
    }

    @Test fun failureLatchBlocksAnotherAttemptBeforeCredentialRead() = runTest {
        val state = FakeState().apply { paused = true }
        repeat(2) { assertEquals(UnlockResult.PAUSED, unlockPreflight(state)) }
        assertFalse(state.calls.contains("credential"))
        assertTrue(state.paused)
    }

    @Test fun availableCredentialOnlyPermitsAnAttemptNotAnUnlockedClaim() = runTest {
        val state = FakeState()
        assertNull(unlockPreflight(state))
        assertEquals(listOf("unlocked", "boot", "enabled", "service", "paused", "credential"), state.calls)
        assertEquals(UnlockResult.UNAVAILABLE, unlockEngineResult(null, false))
        assertEquals(UnlockResult.UNLOCKED, unlockEngineResult(null, true))
    }

    @Test fun absentAndDamagedCredentialsRemainDistinctSafeStates() = runTest {
        val state = FakeState()
        state.credential = LocalCredentialAvailability.EMPTY
        assertEquals(UnlockResult.CREDENTIAL_MISSING, unlockPreflight(state))
        state.credential = LocalCredentialAvailability.UNAVAILABLE
        assertEquals(UnlockResult.CREDENTIAL_UNAVAILABLE, unlockPreflight(state))
    }

    @Test fun completedGestureOrLateManualUnlockCannotMaskAttemptFailure() {
        assertEquals(UnlockResult.REJECTED, unlockEngineResult("AUTHENTICATION_REJECTED", true))
        assertEquals(UnlockResult.PAUSED, unlockEngineResult("MANUAL_RESET_REQUIRED", true))
        assertEquals(UnlockResult.UNAVAILABLE, unlockEngineResult("UNLOCK_SURFACE_CHANGED", true))
        assertEquals(UnlockResult.TIMEOUT, unlockEngineResult("PATTERN_GESTURE_TIMEOUT", false))
        assertEquals(UnlockResult.UNAVAILABLE, unlockEngineResult("synthetic-sensitive-sentinel", false))
    }

    private class FakeState : UnlockPreflightState {
        val calls = mutableListOf<String>()
        var unlocked = false
        var bootUnlocked = true
        var enabled = true
        var service = true
        var paused = false
        var credential = LocalCredentialAvailability.READY
        override fun fullyUnlocked() = unlocked.also { calls += "unlocked" }
        override fun unlockedSinceBoot() = bootUnlocked.also { calls += "boot" }
        override fun automaticUnlockEnabled() = enabled.also { calls += "enabled" }
        override fun serviceReady() = service.also { calls += "service" }
        override suspend fun attemptPaused() = paused.also { calls += "paused" }
        override suspend fun credentialAvailability() = credential.also { calls += "credential" }
    }
}
