package org.localwake.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceOwner
import com.phoneuse.unified.DeviceSessionRevokedException
import android.content.Intent
import android.os.Handler
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.localwake.app.execution.*
import org.localwake.app.localWake
import org.localwake.app.scheduler.ScheduleModel

class LocalWakeAccessibilityService : PhoneUseAccessibilityService(), UnlockAccessibilityHost {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val context: Context get() = this
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("LocalWake", "service.created")
        // Preserve OpenClaw's Samsung workaround: some builds omit onServiceConnected.
        // Register lifecycle identity here; isReady still requires the system connection.
        registerInstance()
    }
    override fun onServiceConnected() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        super.onServiceConnected()
        android.util.Log.i("LocalWake", "service.connected")
        registerInstance()
    }
    private fun registerInstance() {
        if (instance.value === this) return
        instance.value = this
        scope.launch {
            try { localWake.scheduler.reconcile(false) }
            catch (_: Exception) { /* Dashboard reads the same storage and reports failures. */ }
        }
    }
    // This callback interrupts feedback, not the service connection. OpenClaw also
    // leaves execution intact here. Actual unbind/destroy below still cancels work.
    override fun onInterrupt() {
        super.onInterrupt()
        android.util.Log.i("LocalWake", "service.feedbackInterrupted")
    }
    override fun isReady() = instance.value === this && scope.isActive && isConnectionLive()
    override fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow
    override fun interactiveWindows(): List<AccessibilityWindowInfo> = windows
    @android.annotation.TargetApi(33)
    override fun clearNodeCache(): Boolean = clearCache()
    override fun magnification(): AccessibilityService.MagnificationController = magnificationController
    override fun dispatchUnlockGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?,
    ): Boolean {
        val lease = com.phoneuse.unified.LocalInputSession.currentLease() ?: return false
        if (!isReady() || lease.owner != DeviceOwner.UNLOCK) return false
        val input = AndroidDeviceAccess.coordinator.publish(lease) {
            com.phoneuse.unified.DeviceInputFence.shared.tryBegin()
        } ?: return false
        return try {
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    input.complete()
                    callback?.onCompleted(description)
                }
                override fun onCancelled(description: GestureDescription?) {
                    input.complete()
                    callback?.onCancelled(description)
                }
            }, handler).also { if (!it) input.complete() }
        } catch (failure: Throwable) {
            input.complete()
            throw failure
        }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.i("LocalWake", "service.unbound")
        if (instance.value === this) instance.value = null
        scope.cancel()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        android.util.Log.i("LocalWake", "service.destroyed")
        if (instance.value === this) instance.value = null
        scope.cancel()
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    override fun foregroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }
    /** Restore the upstream service's previous event scope after the local run. */
    private fun restrictPackages(packages: List<String>): () -> Unit {
        val info = serviceInfo ?: error("Accessibility service disconnected")
        val previous = info.packageNames?.clone()
        info.packageNames = (listOf("com.android.systemui", packageName) + packages).distinct().toTypedArray()
        serviceInfo = info
        return {
            if (instance.value === this && isReady()) {
                serviceInfo?.let { current ->
                    current.packageNames = previous
                    serviceInfo = current
                }
            }
        }
    }
    // Called on Main. No external IPC or command surface; only a persisted local alarm may submit.
    fun submit(task: ScheduleModel, runId: String): Boolean {
        if (!isReady()) return false
        val coordinator = AndroidDeviceAccess.coordinator
        val lease = coordinator.tryAcquire(DeviceOwner.UNLOCK) ?: return false
        if (!localWake.executionMutex.tryLock()) { coordinator.release(lease); return false }
        val wakeLock = try {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalWake:execution")
        } catch (_: RuntimeException) { localWake.executionMutex.unlock(); coordinator.release(lease); return false }
        try { wakeLock.acquire(100_000) } catch (_: RuntimeException) { localWake.executionMutex.unlock(); coordinator.release(lease); return false }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val runJob = currentCoroutineContext()[Job]!!
            val revoke = lease.onRevoked { runJob.cancel(DeviceSessionRevokedException(it)) }
            val previousOverlay = overlay
            var result = ExecutionResult("EXECUTION_INTERRUPTED")
            var restorePackages: (() -> Unit)? = null
            val started = android.os.SystemClock.elapsedRealtime()
            val steps = mutableListOf<String>()
            val trace: (String) -> Unit = { event ->
                val line = "${android.os.SystemClock.elapsedRealtime() - started}ms $event"
                if (steps.size < 80) steps += line
                android.util.Log.i("LocalWake", line)
            }
            try {
                previousOverlay?.destroy()
                trace("execution.start mode=${task.mode}")
                restorePackages = restrictPackages(task.apps.map { it.packageName })
                result = withTimeout(90_000) {
                    if (!com.phoneuse.unified.DeviceInputFence.shared.awaitIdle()) {
                        throw com.phoneuse.unified.DeviceUnavailableException("INPUT_STILL_RUNNING")
                    }
                    com.phoneuse.unified.LocalInputSession.withLease(lease) {
                        LocalExecutionCoordinator(this@LocalWakeAccessibilityService, trace) { coordinator.isValid(lease) }.execute(task)
                    }
                }
            } catch (_: TimeoutCancellationException) { result = ExecutionResult("EXECUTION_TIMEOUT") }
            catch (_: CancellationException) { result = ExecutionResult("SERVICE_DISCONNECTED") }
            catch (e: Exception) { trace("exception=${e.javaClass.simpleName}"); result = ExecutionResult("EXECUTION_ERROR") }
            finally {
                trace("execution.end code=${result.code}")
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.IO) {
                            localWake.repository.record(ExecutionRecord(runId, task.name, System.currentTimeMillis(), result.code, result.apps, steps.toList()))
                        }
                    } catch (_: Exception) {
                        // Preserve IN_PROGRESS as an unknown outcome if durable logging fails.
                        android.util.Log.e("LocalWake", "EXECUTION_RESULT_STORAGE_FAILED")
                    } finally {
                        try {
                            if (wakeLock.isHeld) wakeLock.release()
                        } finally {
                            // Restoring service configuration must never leave local execution locked.
                            try { restorePackages?.invoke() }
                            finally {
                                revoke.close()
                                if (coordinator.isValid(lease) && isReady() && overlay === previousOverlay &&
                                    org.localwake.app.device.UnlockStateVerifier(this@LocalWakeAccessibilityService).fullyUnlocked()) {
                                    runCatching { rebuildOverlay() }
                                }
                                coordinator.release(lease)
                                localWake.executionMutex.unlock()
                            }
                        }
                    }
                }
            }
        }
        return true
    }
    companion object { val instance = MutableStateFlow<LocalWakeAccessibilityService?>(null) }
}
