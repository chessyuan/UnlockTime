package com.phoneuse.unified

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.UserManager
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-wide boundary for AI screen access. Local authentication never returns a screen. */
object AndroidDeviceAccess {
    val coordinator = DeviceExecutionCoordinator()
    private lateinit var app: Context
    private lateinit var unlock: LocalUnlockProvider
    // Model-only JVM callers may validate an absent screen session without an Android dispatcher.
    // Main is needed only when a real sensitive Android session starts its deferred cleanup.
    private val cleanup by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val current = ThreadLocal<Task?>()
    private val tasks = java.util.concurrent.ConcurrentHashMap<DeviceSessionLease, Task>()
    @Volatile private var initialized = false

    private class Task(val owner: DeviceOwner, val allowAutoUnlock: Boolean, val stillCurrent: () -> Boolean) {
        val entry = Mutex()
        var attempted = false
        @Volatile var lease: DeviceSessionLease? = null
        @Volatile var failed = false
        @Volatile var closed = false
        var cancellation: AutoCloseable? = null
        var job: Job? = null
        var service: PhoneUseAccessibilityService? = null
        var connection: Int = -1
    }

    fun initialize(context: Context) {
        if (initialized) return
        app = context.applicationContext
        unlock = AndroidLocalUnlockProvider(app, coordinator)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { screenStateChanged() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
        initialized = true
    }

    /** Pure conversation acquires no lease. The first actual screen operation performs preflight once. */
    suspend fun <T> task(
        owner: DeviceOwner = DeviceOwner.AGENT,
        allowAutoUnlock: Boolean = true,
        stillCurrent: () -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        if (current.get() != null) return block()
        check(initialized) { "Device runtime unavailable" }
        val task = Task(owner, allowAutoUnlock, stillCurrent)
        return withContext(current.asContextElement(task)) {
            task.job = currentCoroutineContext()[Job]
            try { block() }
            finally {
                task.closed = true
                task.cancellation?.close()
                task.lease?.let { coordinator.release(it); tasks.remove(it) }
                task.lease = null
            }
        }
    }

    suspend fun ensureScreenAccess(): DeviceSessionLease {
        val task = current.get() ?: throw DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION)
        return task.entry.withLock {
            task.lease?.let { validate(it); return@withLock it }
            if (task.failed || task.closed || task.attempted || !task.stillCurrent()) throw DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION)
            if (!com.phoneuse.routine.UnifiedRoutineRuntime.get(app).authority.allows(com.phoneuse.routine.RoutineOwner.ROUTINE)) {
                throw DeviceUnavailableException("MIGRATION_NOT_READY")
            }
            task.attempted = true
            if (!deviceUnlocked()) {
                if (!task.allowAutoUnlock) {
                    task.failed = true
                    throw DeviceSessionRevokedException(DeviceRevocationReason.SCREEN_LOCKED)
                }
                val result = unlock.ensureUnlocked()
                if (result != UnlockResult.ALREADY_UNLOCKED && result != UnlockResult.UNLOCKED) {
                    task.failed = true
                    throw DeviceUnavailableException(result.name)
                }
            }
            currentCoroutineContext().ensureActive()
            val lease = coordinator.tryAcquire(task.owner) ?: run {
                task.failed = true
                throw DeviceUnavailableException("BUSY")
            }
            task.lease = lease
            task.service = PhoneUseAccessibilityService.instance
            task.connection = PhoneUseAccessibilityService.connections.get()
            tasks[lease] = task
            task.cancellation = lease.onRevoked { reason ->
                task.failed = true
                invalidateScreens()
                task.job?.cancel(DeviceSessionRevokedException(reason))
            }
            validate(lease)
            if (!DeviceInputFence.shared.awaitIdle()) throw DeviceUnavailableException("INPUT_STILL_RUNNING")
            validate(lease)
            // A run started from our chat must leave our own UI before AI observation. This local
            // HOME action is never available during sensitive editing (ownership already excludes it).
            withContext(Dispatchers.Main.immediate) {
                val service = PhoneUseAccessibilityService.instance
                @Suppress("DEPRECATION")
                val root = service?.rootInActiveWindow
                val own = try { root?.packageName?.toString() == app.packageName } finally { root?.recycle() }
                if (own) {
                    val input = coordinator.publish(lease) { DeviceInputFence.shared.tryBegin() }
                        ?: throw DeviceUnavailableException("INPUT_STILL_RUNNING")
                    try {
                        validate(lease)
                        currentCoroutineContext().ensureActive()
                        service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    } finally { input.complete() }
                    delay(450)
                }
            }
            validate(lease)
            lease
        }
    }

    /** Synchronous guard for tree APIs and callbacks; it never starts authentication. */
    fun requireAccess(): DeviceSessionLease {
        val lease = requireSession()
        validateNormalSurface(lease)
        return lease
    }

    /** Select a service/read job without authorizing any tree, pixels or input. */
    internal fun requireSession(): DeviceSessionLease {
        val lease = current.get()?.lease ?: throw DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION)
        validate(lease)
        return lease
    }

    /** Reuses the existing task lease; a rebuilding Activity never triggers another unlock attempt. */
    internal suspend fun <T> readNormalSurface(
        timeoutMs: Long = 2_000,
        discard: (T) -> Unit = {},
        read: suspend () -> T,
    ): T {
        val lease = requireSession()
        return NormalSurfacePolicy.read(timeoutMs, android.os.SystemClock::uptimeMillis,
            { validateNormalSurface(lease) }, discard, read)
    }

    /** The model cannot operate this product's own credential, global policy or model-secret UI. */
    fun validateNormalSurface(lease: DeviceSessionLease) {
        validate(lease)
        val service = PhoneUseAccessibilityService.instance ?: throw DeviceUnavailableException("SERVICE_UNAVAILABLE")
        val windows = service.windows
        val applicationPackages = ArrayList<String?>()
        // A full-display image or coordinate gesture can reach a non-focused split-screen pane.
        // Accessibility overlays are our agent controls; actual application windows have no exemption.
        for (window in windows) {
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            @Suppress("DEPRECATION") val root = window.root
            try {
                val pkg = root?.packageName?.toString()
                if (pkg == app.packageName) rejectOwnSurface(lease)
                applicationPackages.add(pkg)
            } finally { @Suppress("DEPRECATION") root?.recycle() }
        }
        // Recheck the non-surface guards after the Binder window query too.
        validate(lease)
        NormalSurfacePolicy.require(NormalSurfacePolicy.classify(applicationPackages, app.packageName)) {
            rejectOwnSurface(lease)
        }
    }

    internal fun rejectOwnSurface(lease: DeviceSessionLease): Nothing {
        coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SENSITIVE_SESSION)
        throw DeviceSessionRevokedException(DeviceRevocationReason.SENSITIVE_SESSION)
    }

    fun validate(lease: DeviceSessionLease) {
        coordinator.requireValid(lease)
        val task = tasks[lease]
        if (task != null && (task.service !== PhoneUseAccessibilityService.instance ||
                task.connection != PhoneUseAccessibilityService.connections.get())) {
            coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SERVICE_DISCONNECTED)
            throw DeviceSessionRevokedException(DeviceRevocationReason.SERVICE_DISCONNECTED)
        }
        if (task != null && (task.closed || task.job?.isActive != true || !task.stillCurrent())) {
            coordinator.revokeIfCurrent(lease, DeviceRevocationReason.TASK_CHANGED)
            throw DeviceSessionRevokedException(DeviceRevocationReason.TASK_CHANGED)
        }
        if (!deviceUnlocked()) {
            coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SCREEN_LOCKED)
            throw DeviceSessionRevokedException(DeviceRevocationReason.SCREEN_LOCKED)
        }
        if (PhoneUseAccessibilityService.instance?.isConnectionLive() != true) {
            coordinator.revokeIfCurrent(lease, DeviceRevocationReason.SERVICE_DISCONNECTED)
            throw DeviceSessionRevokedException(DeviceRevocationReason.SERVICE_DISCONNECTED)
        }
    }

    /** Bind independent controller read jobs to the originating task, not whichever run is now active. */
    fun contextElement(): ThreadContextElement<*> {
        requireAccess()
        return current.asContextElement(current.get())
    }

    fun isCurrent(lease: DeviceSessionLease): Boolean =
        current.get()?.lease === lease && runCatching { validate(lease) }.isSuccess

    fun <T> platformInput(block: () -> T): T {
        val lease = requireAccess()
        val ticket = coordinator.publish(lease) { DeviceInputFence.shared.tryBegin() }
            ?: throw DeviceUnavailableException("INPUT_STILL_RUNNING")
        return try { validateNormalSurface(lease); block() } finally { ticket.complete() }
    }

    /** Called before any model send; no lease means this is still a screen-free conversation. */
    fun checkOutbound() {
        outboundGuard().invoke()
    }

    /** Capture the task before handing callbacks to an SDK's independent executor. */
    fun outboundGuard(): () -> Unit {
        val task = current.get() ?: return {}
        return {
            if (task.failed || task.closed || task.job?.isActive != true || !task.stillCurrent()) throw DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION)
            task.lease?.let { validate(it) }
        }
    }

    fun deviceUnlocked(): Boolean = initialized && runCatching {
        app.getSystemService(UserManager::class.java).isUserUnlocked &&
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            app.getSystemService(PowerManager::class.java).isInteractive
    }.getOrDefault(false)

    fun beginSensitive(): DeviceSessionLease? {
        val lease = coordinator.beginCredentialEdit() ?: return null
        runCatching { com.phoneuse.app.AppGraph.runner.stop() }
        invalidateScreens()
        PhoneUseAccessibilityService.instance?.overlay?.destroy()
        return lease
    }

    /** Includes the closing window/animation; a stale close cannot release a newer owner. */
    fun endSensitive(lease: DeviceSessionLease) {
        cleanup.launch {
            delay(600)
            while (!DeviceInputFence.shared.awaitIdle()) delay(250)
            val released = coordinator.release(lease)
            if (released && coordinator.owner.value == DeviceOwner.NONE && deviceUnlocked() &&
                PhoneUseAccessibilityService.instance?.isConnectionLive() == true) {
                PhoneUseAccessibilityService.instance?.rebuildOverlay()
            }
        }
    }

    fun cancelAll() {
        coordinator.revoke(DeviceRevocationReason.USER_CANCELLED)
        invalidateScreens()
    }

    fun serviceDisconnected() {
        coordinator.revoke(DeviceRevocationReason.SERVICE_DISCONNECTED)
        invalidateScreens()
    }

    /**
     * Called before any own Activity starts, not inferred from a later accessibility window query.
     * Even if the user immediately leaves that Activity, its in-flight screenshot lease stays revoked.
     * Pure chat has no lease; native wake/unlock and trusted sensitive sessions retain their owners.
     */
    fun ownActivityStarting() {
        if (coordinator.revokeScreenOwner(DeviceRevocationReason.SENSITIVE_SESSION)) invalidateScreens()
    }

    fun screenStateChanged() {
        if (coordinator.owner.value in setOf(DeviceOwner.AGENT, DeviceOwner.ROUTINE) && !deviceUnlocked()) {
            coordinator.revoke(DeviceRevocationReason.SCREEN_LOCKED)
            invalidateScreens()
        }
    }

    private fun invalidateScreens() { PhoneUseAccessibilityService.instance?.controller?.invalidateScreens() }
}

/** Safe fixed code only; not an exception containing a platform tree, provider response or credential. */
class DeviceUnavailableException(val code: String) : CancellationException("Device unavailable: $code")
