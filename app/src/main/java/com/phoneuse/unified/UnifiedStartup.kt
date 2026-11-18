package com.phoneuse.unified

import android.content.Context
import android.os.PowerManager
import com.phoneuse.app.AppGraph
import com.phoneuse.routine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.execution.ExecutionResult
import org.localwake.app.execution.LocalExecutionCoordinator
import org.localwake.app.localWake
import org.localwake.app.scheduler.ScheduleModel

/** Installs in-process execution adapters before any upstream scheduler/watcher is initialized. */
object UnifiedStartup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeRuns = java.util.concurrent.ConcurrentHashMap<Long, DeviceSessionLease>()

    fun install(context: Context) {
        val app = context.applicationContext
        // Loading the authority is mandatory even if it is corrupt: both schedulers then fail closed.
        UnifiedRoutineRuntime.get(app)
        RoutineExecutionBridge.autoUnlockEnabled = { GlobalUnlockSettings(app).enabled }
        RoutineExecutionBridge.busy = { AndroidDeviceAccess.coordinator.owner.value != DeviceOwner.NONE }
        scope.launch {
            AndroidDeviceAccess.coordinator.owner.collect { owner ->
                if (owner == DeviceOwner.NONE) RoutineSystem.deviceAvailable()
            }
        }
        RoutineExecutionBridge.revokeRun = { id ->
            AppGraph.runner.cancelRoutine(id)
            nativeRuns[id]?.let { AndroidDeviceAccess.coordinator.revokeIfCurrent(it, DeviceRevocationReason.TASK_CHANGED) }
        }
        RoutineExecutionBridge.executeNative = { id, task, token, valid -> executeNative(app, id, task, token, valid) }
        RoutineExecutionBridge.executeOpenApp = { pkg, valid ->
            AndroidDeviceAccess.task(DeviceOwner.ROUTINE, stillCurrent = valid) {
                AndroidDeviceAccess.ensureScreenAccess()
                val service = LocalWakeAccessibilityService.instance.value
                if (service == null || !valid() || !service.controller.launchApp(pkg, false)) false
                else withTimeoutOrNull(5_000) {
                    while (true) {
                        AndroidDeviceAccess.requireAccess()
                        if (!valid()) return@withTimeoutOrNull false
                        if (service.foregroundPackage() == pkg) return@withTimeoutOrNull true
                        delay(150)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } == true
            }
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val local = app.localWake
            val unified = UnifiedRoutineRuntime.get(app)
            if (unified.authority.allows(RoutineOwner.ROUTINE)) {
                RoutineSystem.get(app).resync()
                return@launch
            }
            // No preemption of an old task. New AI device access remains fenced until cutover.
            var lease: DeviceSessionLease? = null
            while (lease == null) {
                lease = AndroidDeviceAccess.coordinator.tryAcquire(DeviceOwner.MIGRATION)
                if (lease == null) delay(250)
            }
            try {
                local.executionMutex.withLock {
                    local.scheduler.mutex.withLock {
                        unified.migrate(local.repository) { local.scheduler.cancelLegacyAlarms() }
                    }
                }
            } catch (_: Exception) {
                // The journal and source/target snapshots remain intact for recovery. Never reset guard.
                unified.authority.freezeForRecovery("STARTUP_MIGRATION_FAILED")
                android.util.Log.e("UnlockTime", "ROUTINE_MIGRATION_UNAVAILABLE")
            } finally {
                AndroidDeviceAccess.coordinator.release(lease)
            }
            if (unified.authority.allows(RoutineOwner.ROUTINE)) RoutineSystem.get(app).resync()
        }
    }

    /** User-requested retry only; verifies the journal/backup and never restores old task outcomes. */
    suspend fun recoverScheduling(context: Context): Boolean {
        val app = context.applicationContext
        val runtime = UnifiedRoutineRuntime.get(app)
        if (runtime.authority.allows(RoutineOwner.ROUTINE)) return true
        val lease = AndroidDeviceAccess.coordinator.tryAcquire(DeviceOwner.MIGRATION) ?: return false
        val succeeded = try {
            app.localWake.executionMutex.withLock {
                app.localWake.scheduler.mutex.withLock {
                    val current = runtime.authority.snapshot()
                    if (current.phase == MigrationPhase.RECOVERY_REQUIRED) {
                        runtime.recover(app.localWake.repository) { app.localWake.scheduler.cancelLegacyAlarms() }
                    } else {
                        runtime.migrate(app.localWake.repository) { app.localWake.scheduler.cancelLegacyAlarms() }
                    }
                }
            }
            runtime.authority.allows(RoutineOwner.ROUTINE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runtime.authority.freezeForRecovery("USER_RECOVERY_FAILED")
            false
        } finally {
            AndroidDeviceAccess.coordinator.release(lease)
        }
        if (succeeded) RoutineSystem.get(app).resync()
        return succeeded
    }

    private suspend fun executeNative(context: Context, routineId: Long, task: ScheduleModel, token: String, stillCurrent: () -> Boolean): ExecutionRecord =
        withContext(Dispatchers.Main.immediate) {
            fun record(code: String) = ExecutionRecord(token, task.name, System.currentTimeMillis(), code)
            val service = LocalWakeAccessibilityService.instance.value ?: return@withContext record("ACCESSIBILITY_DISCONNECTED")
            val coordinator = AndroidDeviceAccess.coordinator
            val lease = coordinator.tryAcquire(DeviceOwner.UNLOCK) ?: return@withContext record("EXECUTION_BUSY")
            val runtime = context.localWake
            if (!runtime.executionMutex.tryLock()) { coordinator.release(lease); return@withContext record("EXECUTION_BUSY") }
            val job = currentCoroutineContext()[Job]!!
            val cancel = lease.onRevoked { job.cancel(DeviceSessionRevokedException(it)) }
            nativeRuns[routineId] = lease
            val overlay = service.overlay
            val trace = mutableListOf<String>()
            val began = android.os.SystemClock.elapsedRealtime()
            var wake: PowerManager.WakeLock? = null
            try {
                wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UnlockTime:routine")
                wake.acquire(100_000)
                overlay?.destroy()
                val result = withTimeout(90_000) {
                    if (!DeviceInputFence.shared.awaitIdle()) throw DeviceUnavailableException("INPUT_STILL_RUNNING")
                    if (!stillCurrent()) throw DeviceUnavailableException("TASK_CHANGED")
                    LocalInputSession.withLease(lease) {
                        LocalExecutionCoordinator(service, trace = { event ->
                            if (trace.size < 80) trace += "${android.os.SystemClock.elapsedRealtime() - began}ms $event"
                        }, sessionValid = { coordinator.isValid(lease) && stillCurrent() }).execute(task)
                    }
                }
                ExecutionRecord(token, task.name, System.currentTimeMillis(), result.code, result.apps, trace.toList())
            } catch (_: TimeoutCancellationException) { record("EXECUTION_TIMEOUT") }
            catch (_: CancellationException) { record("EXECUTION_INTERRUPTED") }
            catch (_: Exception) { record("EXECUTION_ERROR") }
            finally {
                try {
                    cancel.close()
                    if (wake?.isHeld == true) runCatching { wake.release() }
                    if (coordinator.isValid(lease) && service.isReady() && service.overlay === overlay && UnlockStateVerifier(context).fullyUnlocked()) {
                        runCatching { service.rebuildOverlay() }
                    }
                } finally {
                    nativeRuns.remove(routineId, lease)
                    coordinator.release(lease)
                    runtime.executionMutex.unlock()
                }
            }
        }
}
