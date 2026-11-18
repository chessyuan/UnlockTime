package com.phoneuse.unified

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.phoneuse.overlay.OverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.execution.WakeActivity
import org.localwake.app.execution.WakeSignals
import org.localwake.app.localWake
import org.localwake.app.security.CredentialState
import org.localwake.app.unlock.LocalUnlockEngine
import java.util.UUID

/**
 * Trusted native preflight, called before acquiring an AGENT/ROUTINE lease. The caller must acquire
 * its own lease and freshly check the unlocked state afterwards; this result is not a screen token.
 */
class AndroidLocalUnlockProvider(
    context: Context,
    private val coordinator: DeviceExecutionCoordinator,
    private val settings: GlobalUnlockSettings = GlobalUnlockSettings(context),
    private val service: () -> LocalWakeAccessibilityService? = { LocalWakeAccessibilityService.instance.value },
) : LocalUnlockProvider {
    private val context = context.applicationContext
    private val runtime = this.context.localWake
    private val verifier = UnlockStateVerifier(this.context)

    override suspend fun isReady(): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            if (coordinator.owner.value != DeviceOwner.NONE) return@withContext false
            val activeService = service()
            if (verifier.fullyUnlocked()) {
                return@withContext context.getSystemService(PowerManager::class.java).isInteractive || activeService?.isReady() == true
            }
            val result = unlockPreflight(preflight(activeService))
            result == null || result.succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun ensureUnlocked(): UnlockResult = withContext(Dispatchers.Main.immediate) {
        // Do this before acquiring local storage, inspecting credentials, or changing overlays.
        if (coordinator.owner.value != DeviceOwner.NONE) return@withContext UnlockResult.BUSY
        if (verifier.fullyUnlocked() && context.getSystemService(PowerManager::class.java).isInteractive) {
            return@withContext UnlockResult.ALREADY_UNLOCKED
        }
        val lease = coordinator.tryAcquire(DeviceOwner.UNLOCK) ?: return@withContext UnlockResult.BUSY
        if (!runtime.executionMutex.tryLock()) {
            coordinator.release(lease)
            return@withContext UnlockResult.BUSY
        }
        var revocationHook: AutoCloseable? = null
        var wakeLock: PowerManager.WakeLock? = null
        var connectedService: LocalWakeAccessibilityService? = null
        var removedOverlay: OverlayManager? = null
        var overlaySuspended = false
        try {
            withTimeout(EXECUTION_TIMEOUT_MS) {
                coroutineScope {
                    val attemptJob = currentCoroutineContext()[Job]!!
                    revocationHook = lease.onRevoked { attemptJob.cancel(DeviceSessionRevokedException(it)) }
                    if (!DeviceInputFence.shared.awaitIdle()) return@coroutineScope UnlockResult.TIMEOUT
                    val activeService = service()
                    connectedService = activeService
                    val preflightResult = unlockPreflight(preflight(activeService))
                    val wakeOnly = preflightResult == UnlockResult.ALREADY_UNLOCKED
                    if (preflightResult != null && !wakeOnly) return@coroutineScope preflightResult
                    if (activeService?.isReady() != true) return@coroutineScope UnlockResult.SERVICE_UNAVAILABLE

                    // An unlocked sleeping phone needs only waking, even if automatic credential input
                    // is disabled. A relock during that branch must never turn into an unlock attempt.
                    fun stillCurrent(): Boolean = coordinator.isValid(lease) && activeService.isReady() &&
                        (if (wakeOnly) verifier.fullyUnlocked() else settings.enabled)
                    if (!stillCurrent()) return@coroutineScope unavailable(lease, activeService)

                    val power = context.getSystemService(PowerManager::class.java)
                    if (verifier.fullyUnlocked() && power.isInteractive) return@coroutineScope UnlockResult.ALREADY_UNLOCKED
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalWake:execution").also {
                        it.acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
                    // destroy/hide removes the actual windows on Main; alpha-only capture hiding is
                    // insufficient. The original trusted-tree checks still verify every obstruction.
                    removedOverlay = activeService.overlay
                    overlaySuspended = true
                    removedOverlay?.destroy()
                    if (!LocalInputSession.withLease(lease) { wake(activeService, ::stillCurrent) }) return@coroutineScope unavailable(lease, activeService)
                    if (!stillCurrent()) return@coroutineScope unavailable(lease, activeService)
                    if (verifier.fullyUnlocked()) {
                        return@coroutineScope if (wakeOnly) UnlockResult.ALREADY_UNLOCKED else UnlockResult.UNLOCKED
                    }
                    if (wakeOnly) return@coroutineScope UnlockResult.MANUAL_UNLOCK_REQUIRED

                    // No trace callback: neither the path nor coordinates reach the Agent or its logs.
                    val code = LocalInputSession.withLease(lease) {
                        LocalUnlockEngine(activeService).unlock(
                            beginAttempt = {
                                if (!stillCurrent()) false else {
                                    val allowed = runtime.repository.beginCredentialAttempt()
                                    // A setting/revocation change during the durable write must stop input;
                                    // the persisted latch stays set if this run was already admitted.
                                    allowed && stillCurrent()
                                }
                            },
                            stillCurrent = { stillCurrent() },
                        )
                    }
                    if (!stillCurrent()) return@coroutineScope unavailable(lease, activeService)
                    unlockEngineResult(code, verifier.fullyUnlocked())
                }
            }
        } catch (_: TimeoutCancellationException) {
            UnlockResult.TIMEOUT
        } catch (revoked: DeviceSessionRevokedException) {
            revokedResult(revoked.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UnlockResult.UNAVAILABLE
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                revocationHook?.close()
                try {
                    val activeService = connectedService
                    // Recreate a hidden manager only. Do not restore a stale card or make a lock-screen
                    // window visible. A newer service/overlay generation owns its own cleanup.
                    if (overlaySuspended && activeService != null && activeService.isReady() &&
                        coordinator.isValid(lease) && verifier.fullyUnlocked() &&
                        activeService.overlay === removedOverlay
                    ) {
                        runCatching { activeService.rebuildOverlay() }
                    }
                } finally {
                    try { wakeLock?.let { if (it.isHeld) it.release() } }
                    finally {
                        runtime.executionMutex.unlock()
                        coordinator.release(lease)
                    }
                }
            }
        }
    }

    private fun preflight(activeService: LocalWakeAccessibilityService?) = object : UnlockPreflightState {
        override fun fullyUnlocked() = verifier.fullyUnlocked()
        override fun unlockedSinceBoot() = verifier.userUnlockedSinceBoot()
        override fun automaticUnlockEnabled() = settings.enabled
        override fun serviceReady() = activeService?.isReady() == true
        override suspend fun attemptPaused() = runtime.repository.snapshot().patternBlocked
        override suspend fun credentialAvailability(): LocalCredentialAvailability = withContext(Dispatchers.IO) {
            // A manual unlock while the store check was being scheduled also needs no credential read.
            if (verifier.fullyUnlocked()) LocalCredentialAvailability.READY else when (runtime.credentials.status()) {
                CredentialState.READY -> LocalCredentialAvailability.READY
                CredentialState.EMPTY -> LocalCredentialAvailability.EMPTY
                CredentialState.USER_LOCKED -> LocalCredentialAvailability.USER_LOCKED
                else -> LocalCredentialAvailability.UNAVAILABLE
            }
        }
    }

    /** Preserve the proven WakeActivity handshake and its settling interval. */
    private suspend fun wake(activeService: LocalWakeAccessibilityService, stillCurrent: () -> Boolean): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (!stillCurrent()) return false
        if (power.isInteractive && verifier.fullyUnlocked()) return true
        val token = UUID.randomUUID().toString()
        val signal = WakeSignals.Signal()
        WakeSignals.pending[token] = signal
        return try {
            if (!stillCurrent()) return false
            currentCoroutineContext().ensureActive()
            LocalInputSession.input {
                activeService.startActivity(Intent(activeService, WakeActivity::class.java).putExtra("wake_token", token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            }
            val observed = withTimeoutOrNull(4_000) { signal.resumed.await(); signal.finished.await(); true } ?: false
            delay(500)
            observed && power.isInteractive && stillCurrent()
        } catch (_: SecurityException) {
            false
        } finally {
            WakeSignals.pending.remove(token)
        }
    }

    private fun unavailable(lease: DeviceSessionLease, activeService: LocalWakeAccessibilityService): UnlockResult = when {
        !coordinator.isValid(lease) -> revokedResult(lease.revocationReason)
        !activeService.isReady() -> UnlockResult.SERVICE_UNAVAILABLE
        !settings.enabled -> UnlockResult.MANUAL_UNLOCK_REQUIRED
        else -> UnlockResult.UNAVAILABLE
    }

    private fun revokedResult(reason: DeviceRevocationReason?): UnlockResult = when (reason) {
        DeviceRevocationReason.SERVICE_DISCONNECTED -> UnlockResult.SERVICE_UNAVAILABLE
        DeviceRevocationReason.SETTINGS_CHANGED -> UnlockResult.MANUAL_UNLOCK_REQUIRED
        else -> UnlockResult.UNAVAILABLE
    }

    private companion object {
        const val EXECUTION_TIMEOUT_MS = 90_000L
        const val WAKE_LOCK_TIMEOUT_MS = 100_000L
    }
}
