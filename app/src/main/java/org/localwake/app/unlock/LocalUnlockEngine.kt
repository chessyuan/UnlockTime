package org.localwake.app.unlock

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import org.localwake.app.accessibility.UnlockAccessibilityHost
import org.localwake.app.device.AdapterSelection
import org.localwake.app.device.KeyguardSurface
import org.localwake.app.device.ScreenGeometry
import org.localwake.app.device.SystemUiTree
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.device.controls
import org.localwake.app.compatibility.ObservationToken
import org.localwake.app.localWake
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.security.CredentialFailure
import org.localwake.app.security.UnlockMethod
import java.nio.CharBuffer

internal class LocalUnlockEngine(
    private val service: UnlockAccessibilityHost,
    private val trace: (String) -> Unit = {},
) {
    private val app = service.context.localWake
    private val verifier = UnlockStateVerifier(service.context)
    private val keyguard = service.context.getSystemService(KeyguardManager::class.java)
    private val geometry = ScreenGeometry(service)
    private val patternUnlocker = LocalPatternUnlocker(service, geometry, trace)

    suspend fun unlock(task: ScheduleModel, stillCurrent: suspend () -> Boolean): String? =
        unlock({ app.repository.beginPatternAttempt(task) }, stillCurrent)

    /** The caller supplies the durable guard; credential input never requires a synthetic schedule. */
    suspend fun unlock(beginAttempt: suspend () -> Boolean, stillCurrent: suspend () -> Boolean): String? {
        trace("unlock.start credentialRequired=${verifier.credentialRequired()} fullyUnlocked=${verifier.fullyUnlocked()}")
        if (verifier.fullyUnlocked()) return null
        if (!verifier.userUnlockedSinceBoot()) return "FIRST_MANUAL_UNLOCK_REQUIRED"
        if (!com.phoneuse.unified.GlobalUnlockSettings(service.context).enabled) return "MANUAL_UNLOCK_REQUIRED"
        if (app.repository.snapshot().patternBlocked) return "MANUAL_RESET_REQUIRED"
        return try {
            val method = app.credentials.method()
            prepareSurface(stillCurrent)
            environmentError(method)?.let { return if (it == ALREADY_UNLOCKED) null else it }
            val initial = waitForSurface(method, stillCurrent) ?: run {
                if (!stillCurrent()) return "TASK_CHANGED"
                environmentError(method)?.let { return if (it == ALREADY_UNLOCKED) null else it }
                val failure = when (method) {
                    UnlockMethod.PATTERN -> "PATTERN_GRID_UNAVAILABLE"
                    UnlockMethod.PIN -> "PIN_KEYPAD_UNAVAILABLE"
                    UnlockMethod.PASSWORD -> "PASSWORD_FIELD_UNAVAILABLE"
                }
                val screen = geometry.current()
                if (screen != null) {
                    runCatching {
                        val token = app.compatibility.observe(method, screen, emptyList(), emptyList(), "", 0)
                        app.compatibility.finish(token, failure, false, false)
                    }
                } else runCatching { app.compatibility.recordUnavailable(method, failure) }
                return failure
            }
            initial.first.use { tree ->
                val surface = initial.second.surface
                if (surface is KeyguardSurface.Pattern) {
                    trace("grid.found source=DYNAMIC bounds=${tree.bounds(surface.grid).toShortString()}")
                }
                app.scheduler.mutex.withLock {
                    if (!stillCurrent()) return@withLock "TASK_CHANGED"
                    environmentError(method)?.let { return@withLock if (it == ALREADY_UNLOCKED) null else it }
                    val token = runCatching {
                        app.compatibility.observe(method, tree.snapshot.geometry, tree.snapshot.nodes,
                            surface.controls(), initial.second.adapterId, initial.second.adapterVersion)
                    }.getOrNull()
                    execute(Attempt(beginAttempt, method, tree, initial.second, stillCurrent), token)
                }
            }
        } catch (e: CredentialFailure) {
            "CREDENTIAL_${e.state.name}"
        }
    }

    suspend fun inspect(stillCurrent: suspend () -> Boolean): String {
        if (!stillCurrent()) return "TASK_CHANGED"
        val method = try { app.credentials.method() } catch (_: CredentialFailure) { return "CREDENTIAL_EMPTY" }
        // The owner exposes the lock screen. This path never swipes, clicks or reads a credential.
        val found = waitForSurface(method, stillCurrent)
        return found?.first?.use { tree ->
            if (!stillCurrent()) return@use "TASK_CHANGED"
            runCatching {
                val token = app.compatibility.observe(method, tree.snapshot.geometry, tree.snapshot.nodes,
                    found.second.surface.controls(), found.second.adapterId, found.second.adapterVersion)
                app.compatibility.finish(token, "INSPECTION_COMPLETE", false, false)
            }
            "LOCK_SCREEN_DETECTED"
        } ?: run {
            runCatching { app.compatibility.recordUnavailable(method, "LOCK_SCREEN_UNAVAILABLE") }
            "LOCK_SCREEN_UNAVAILABLE"
        }
    }

    private suspend fun prepareSurface(stillCurrent: suspend () -> Boolean) {
        patternUnlocker.prepareSurface {
            val deadline = SystemClock.elapsedRealtime() + 1_500
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!service.isReady() || !stillCurrent() || verifier.fullyUnlocked() || !keyguard.isKeyguardLocked) {
                    return@prepareSurface null
                }
                val observed = capture()?.use { tree ->
                    if (!tree.rootStillActive(service, geometry)) null else tree.snapshot to tree.canPrepare()
                }
                // A known credential/unsupported surface is a stopping condition. A temporarily
                // unavailable window during WakeActivity's exit gets a bounded read-only wait.
                if (observed != null) return@prepareSurface observed.first.takeIf { observed.second }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
            null
        }
    }

    private fun capture() = SystemUiTree.capture(service, geometry)

    private suspend fun waitForSurface(
        method: UnlockMethod, stillCurrent: suspend () -> Boolean,
    ): Pair<SystemUiTree, AdapterSelection>? {
        val deadline = SystemClock.elapsedRealtime() + SURFACE_WAIT_MILLIS
        var candidate: Pair<SystemUiTree, AdapterSelection>? = null
        var stableSince: Long? = null
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (verifier.fullyUnlocked() || !service.isReady() || !stillCurrent()) return null
                val previous = candidate
                candidate = null
                val stable = previous?.first.use {
                    val tree = capture()
                    if (tree == null) return@use false
                    val selected = tree.selection(method)
                    if (selected == null) {
                        tree.close()
                        return@use false
                    }
                    candidate = tree to selected
                    previous != null && tree.matches(previous.first, previous.second, selected)
                }
                val now = SystemClock.elapsedRealtime()
                if (!stable) stableSince = if (candidate != null) now else null
                if (stable && stableSince != null && now - stableSince >= SURFACE_QUIET_MILLIS && now < deadline) {
                    return candidate.also { candidate = null }
                }
                if (deadline - SystemClock.elapsedRealtime() < SAMPLE_INTERVAL_MILLIS) return null
                delay(SAMPLE_INTERVAL_MILLIS)
            }
            return null
        } finally {
            candidate?.first?.close()
        }
    }

    private fun environmentError(method: UnlockMethod): String? = when {
        !service.isReady() -> "SERVICE_DISCONNECTED"
        verifier.fullyUnlocked() -> ALREADY_UNLOCKED
        !com.phoneuse.unified.GlobalUnlockSettings(service.context).enabled -> "MANUAL_UNLOCK_REQUIRED"
        app.credentials.method() != method -> "UNLOCK_METHOD_CHANGED"
        !keyguard.isDeviceSecure || !keyguard.isKeyguardLocked || !verifier.credentialRequired() -> "KEYGUARD_STILL_LOCKED"
        else -> null
    }

    // The scheduler mutex protects the credential snapshot, the durable guard, and the whole input sequence.
    private suspend fun execute(attempt: Attempt, observation: ObservationToken?): String? {
        var sequenceCompleted = false
        var outcome = "INTERRUPTED"
        try {
            val result = executeObserved(attempt) { sequenceCompleted = true }
            outcome = result ?: "SUCCESS"
            return result
        } finally {
            observation?.let { token ->
                // Diagnostic failure never authorizes input or clears the durable failure guard.
                runCatching { app.compatibility.finish(token, outcome, sequenceCompleted, verifier.fullyUnlocked()) }
            }
        }
    }

    private suspend fun executeObserved(attempt: Attempt, onSequenceCompleted: () -> Unit): String? {
        val error = when (val surface = attempt.initial) {
            is KeyguardSurface.Pattern -> app.credentials.withPattern { pattern ->
                attempt.input({ (it as KeyguardSurface.Pattern).grid }) { _, bounds ->
                    patternUnlocker.draw(bounds, pattern, attempt.display)
                }
            }
            is KeyguardSurface.Pin -> app.credentials.withText(UnlockMethod.PIN) { pin ->
                enterPin(attempt, surface, pin)
            }
            is KeyguardSurface.Password -> app.credentials.withText(UnlockMethod.PASSWORD) { password ->
                enterPassword(attempt, surface, password)
            }
        }
        if (attempt.started && error == null) onSequenceCompleted()
        if (!attempt.started) return if (error == ALREADY_UNLOCKED) null else error
        // An interrupted input stays paused, even if the owner subsequently unlocks manually.
        if (error != null) return if (error == ALREADY_UNLOCKED) "UNLOCK_SURFACE_CHANGED" else error
        repeat(32) {
            if (verifier.fullyUnlocked()) {
                trace("keyguard.fullyUnlocked")
                app.repository.setPatternBlocked(false)
                return null
            }
            delay(125)
        }
        return "AUTHENTICATION_REJECTED"
    }

    private suspend fun enterPin(attempt: Attempt, initial: KeyguardSurface.Pin, pin: CharArray): String? {
        for (index in pin.indices) {
            val digit = pin[index].digitToInt()
            attempt.input({ (it as KeyguardSurface.Pin).digits[digit] }, pinLength = index) { node, _ ->
                click(node)
            }?.let { return it }
            delay(100)
        }
        // Auto-confirm layouts may unlock on the final digit. A hidden enter control stays untouched.
        if (initial.confirm == null || verifier.fullyUnlocked()) return null
        val confirmation = attempt.input({ requireNotNull((it as KeyguardSurface.Pin).confirm) }, pinLength = pin.size) { node, _ ->
            click(node)
        }
        // Every digit was accepted. Auto-confirm may complete before the optional enter action.
        return if (confirmation == ALREADY_UNLOCKED && verifier.fullyUnlocked()) null else confirmation
    }

    private suspend fun enterPassword(attempt: Attempt, initial: KeyguardSurface.Password, password: CharArray): String? {
        attempt.input({ (it as KeyguardSurface.Password).field }) { node, _ ->
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, CharBuffer.wrap(password))
            }
            try {
                com.phoneuse.unified.LocalInputSession.input { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.inputResult()
            } finally {
                args.clear()
            }
        }?.let { return it }
        trace("password.textSet")
        delay(100)
        return attempt.input({ selected ->
            val surface = selected as KeyguardSurface.Password
            surface.confirm ?: surface.field
        }) { node, _ ->
            if (initial.confirm != null) click(node) else
                com.phoneuse.unified.LocalInputSession.input { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }.inputResult()
        }
    }

    private fun click(node: AccessibilityNodeInfo) = com.phoneuse.unified.LocalInputSession.input {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.inputResult()
    private fun Boolean.inputResult() = if (this) null else "CREDENTIAL_INPUT_REJECTED"

    private inner class Attempt(
        private val beginAttempt: suspend () -> Boolean,
        private val method: UnlockMethod,
        private val initialTree: SystemUiTree,
        private val initialSelection: AdapterSelection,
        private val stillCurrent: suspend () -> Boolean,
    ) {
        val initial get() = initialSelection.surface
        val display get() = initialTree.snapshot.geometry

        var started = false
            private set
        private val changed = if (method == UnlockMethod.PATTERN) "PATTERN_GRID_CHANGED" else "UNLOCK_SURFACE_CHANGED"

        suspend fun input(
            control: (KeyguardSurface) -> Int,
            pinLength: Int? = null,
            action: suspend (AccessibilityNodeInfo, Rect) -> String?,
        ): String? {
            if (!service.isReady()) return "SERVICE_DISCONNECTED"
            if (!stillCurrent()) return "TASK_CHANGED"
            if (!started) {
                // Confirm the surface before latching, then validate again after the durable write suspends.
                withFreshControl(control, pinLength) { _, _ -> null }?.let { return it }
                if (!beginAttempt()) return "MANUAL_RESET_REQUIRED"
                started = true
            }
            return withFreshControl(control, pinLength, action)
        }

        private suspend fun withFreshControl(
            control: (KeyguardSurface) -> Int,
            pinLength: Int?,
            action: suspend (AccessibilityNodeInfo, Rect) -> String?,
        ): String? {
            environmentError(method)?.let { return it }
            val fresh = capture() ?: return if (verifier.fullyUnlocked()) ALREADY_UNLOCKED else changed
            return fresh.use { live ->
                val selection = live.selection(method) ?: return@use changed
                if (!live.matches(initialTree, initialSelection, selection)) {
                    trace("surface.changed=${live.mismatchKind(initialTree, initialSelection, selection)}")
                    return@use changed
                }
                val current = selection.surface
                if (pinLength != null) {
                    val entry = live.refreshControl((current as KeyguardSurface.Pin).entry) ?: return@use changed
                    // Only check the length of the selected PIN entry. Password field text is never queried.
                    val length = if (entry.isShowingHintText) 0 else entry.text?.length
                    if (length == null) return@use "PIN_ENTRY_UNVERIFIED"
                    if (length != pinLength) return@use if (started) changed else "PIN_ENTRY_NOT_EMPTY"
                }
                val index = control(current)
                val node = live.refreshControl(index) ?: return@use changed
                if (!live.rootStillActive(service, geometry)) { trace("surface.changed=LIVE_WINDOW_CHANGED"); return@use changed }
                environmentError(method)?.let { return@use it }
                if (!stillCurrent()) return@use "TASK_CHANGED"
                currentCoroutineContext().ensureActive()
                action(node, live.bounds(index))
            }
        }
    }

    private companion object {
        const val ALREADY_UNLOCKED = "ALREADY_UNLOCKED"
        const val SURFACE_WAIT_MILLIS = 3_600L
        const val SAMPLE_INTERVAL_MILLIS = 150L
        // IME/bouncer animations can briefly pause before relaying out the credential field.
        const val SURFACE_QUIET_MILLIS = 750L
    }
}
