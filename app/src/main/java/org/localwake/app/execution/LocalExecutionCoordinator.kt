package org.localwake.app.execution

import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.phoneuse.unified.LocalInputSession
import kotlinx.coroutines.withTimeoutOrNull
import org.localwake.app.accessibility.UnlockAccessibilityHost
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.localWake
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.unlock.LocalUnlockEngine
import java.util.UUID

class LocalExecutionCoordinator(private val service: UnlockAccessibilityHost, private val trace: (String) -> Unit = {}, private val sessionValid: () -> Boolean = { true }) {
    suspend fun execute(task: ScheduleModel): ExecutionResult {
        val verifier = UnlockStateVerifier(service.context)
        if (!verifier.userUnlockedSinceBoot()) return ExecutionResult("FIRST_MANUAL_UNLOCK_REQUIRED")
        val engine = LocalUnlockEngine(service, trace)
        return ExecutionPipeline(object : ExecutionDevice {
            override suspend fun stillEnabled() = sessionValid() && service.isReady() && service.context.localWake.repository.stillCurrent(task)
            override suspend fun wake(): Boolean {
                val power = service.context.getSystemService(PowerManager::class.java)
                trace("wake.start interactive=${power.isInteractive} credentialRequired=${verifier.credentialRequired()} fullyUnlocked=${verifier.fullyUnlocked()}")
                // Inspection observes the bouncer exposed by the owner; a show-when-locked
                // activity would dismiss that bouncer and change the very surface being checked.
                if (task.mode == ExecutionMode.INSPECT_GRID) return power.isInteractive
                if (power.isInteractive && verifier.fullyUnlocked()) return true
                val token = UUID.randomUUID().toString()
                val signal = WakeSignals.Signal()
                WakeSignals.pending[token] = signal
                return try {
                    if (!stillEnabled()) return false
                    currentCoroutineContext().ensureActive()
                    LocalInputSession.input {
                        service.context.startActivity(Intent(service.context, WakeActivity::class.java).putExtra("wake_token", token)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                    }
                    val observed = withTimeoutOrNull(4_000) { signal.resumed.await(); signal.finished.await(); true } ?: false
                    delay(500)
                    trace("wake.observed=$observed interactive=${power.isInteractive} credentialRequired=${verifier.credentialRequired()}")
                    observed && power.isInteractive
                } catch (_: SecurityException) { false }
                finally { WakeSignals.pending.remove(token) }
            }
            override fun unlocked() = verifier.fullyUnlocked()
            override suspend fun unlock() = engine.unlock(task) { stillEnabled() }
            override suspend fun inspectGrid() = engine.inspect { stillEnabled() }
            override fun isInstalled(packageName: String) = service.context.packageManager.getLaunchIntentForPackage(packageName) != null
            override suspend fun launchAndObserve(packageName: String): String? {
                if (!verifier.fullyUnlocked()) return "DEVICE_RELOCKED"
                val intent = service.context.packageManager.getLaunchIntentForPackage(packageName) ?: return "APP_NOT_INSTALLED"
                if (!stillEnabled()) return "TASK_CHANGED"
                currentCoroutineContext().ensureActive()
                if (!verifier.fullyUnlocked()) return "DEVICE_RELOCKED"
                try { LocalInputSession.input { service.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                catch (_: Exception) { return "APP_LAUNCH_REJECTED" }
                repeat(20) {
                    if (!verifier.fullyUnlocked()) return "DEVICE_RELOCKED"
                    if (service.foregroundPackage() == packageName) { trace("app.foregroundConfirmed"); return null }
                    delay(250)
                }
                // startActivity can fail silently under BAL restrictions.
                return "APP_LAUNCH_UNOBSERVED"
            }
            override suspend fun pause(ms: Long) { delay(ms) }
        }).run(task)
    }
}
