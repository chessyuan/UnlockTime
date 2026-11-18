package org.localwake.app

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.phoneuse.agent.DeviceController
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceInputFence
import com.phoneuse.unified.DeviceOwner
import com.phoneuse.unified.DeviceRevocationReason
import com.phoneuse.unified.DeviceSessionLease
import com.phoneuse.unified.DeviceSessionRevokedException
import com.phoneuse.unified.DeviceUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.security.CredentialState

internal class UnifiedAgentFixtureFailure(val code: String) : RuntimeException(code)

/**
 * Android-test APK only. Called only AFTER EmulatorCompatibilityInstrumentation has checked the exact
 * emulator serial, SDK fingerprint, AVD identity, isolated user, empty app and absence of a secure lock.
 * Uses the production device boundary/controller. No model, network, dump, screenshot file or credential.
 */
internal object UnifiedAgentFixture {
    private const val SETTINGS = "com.android.settings"

    suspend fun run(context: Context, automation: UiAutomation, result: Bundle) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val app = context.localWake
        val unified = UnifiedRoutineRuntime.get(context)
        checkFixture(Build.HARDWARE in setOf("ranchu", "goldfish"), "AGENT_EMULATOR_REQUIRED")
        checkFixture(!keyguard.isDeviceSecure && !keyguard.isDeviceLocked, "AGENT_EXISTING_DEVICE_LOCK")
        checkFixture(app.credentials.status() == CredentialState.EMPTY && unified.store.all().isEmpty(), "AGENT_APP_NOT_CLEAN")
        checkFixture(AndroidDeviceAccess.coordinator.owner.value == DeviceOwner.NONE, "AGENT_DEVICE_ALREADY_BUSY")
        val service = LocalWakeAccessibilityService.instance.value ?: fail("AGENT_SERVICE_UNAVAILABLE")
        checkFixture(service.isReady(), "AGENT_SERVICE_UNAVAILABLE")
        val controller: DeviceController = service.controller
        var stage = "AGENT_WAKE_PRECONDITION_FAILED"
        try {
            // Dismiss only the already-verified insecure AVD keyguard; never set/change a lock type.
            wakeAndDismiss(automation, power, keyguard, result, "agentWake", "AGENT_WAKE")
            val originalGuard = app.repository.snapshot().patternBlocked
            checkFixture(!originalGuard, "AGENT_GUARD_NOT_CLEAN")
            var oldLease: DeviceSessionLease? = null

            stage = "AGENT_POSITIVE_CONTROLLER_FAILED"
            inTask {
                stage = "AGENT_LAUNCH_BOUNDARY_ERROR"
                oldLease = AndroidDeviceAccess.ensureScreenAccess()
                result.putBoolean("agentPreflightConfirmed", true)
                val windows = service.windows
                result.putInt("agentWindowCount", windows.size)
                result.putString("agentWindowClasses", windows.joinToString("|") { w ->
                    val root = w.root
                    val kind = try {
                        when (root?.packageName?.toString()) {
                            null -> "UNKNOWN"
                            context.packageName -> "OWN"
                            SETTINGS -> "SETTINGS"
                            else -> "OTHER"
                        }
                    } finally { @Suppress("DEPRECATION") root?.recycle() }
                    "${w.type}:$kind:${w.isActive}"
                })
                checkFixture(controller.launchApp(SETTINGS, fresh = true), "AGENT_LAUNCH_REJECTED")
                result.putBoolean("agentLaunchAccepted", true)
                checkFixture(waitFor { service.foregroundPackage() == SETTINGS }, "AGENT_LAUNCH_UNOBSERVED")
                stage = "AGENT_IDLE_BOUNDARY_ERROR"
                controller.waitForIdle(2_000)
                stage = "AGENT_SNAPSHOT_BOUNDARY_ERROR"
                val before = controller.snapshot()
                assertSafeSettings(before)
                result.putBoolean("agentSnapshotConfirmed", true)
                stage = "AGENT_SCREENSHOT_BOUNDARY_ERROR"
                val jpeg = controller.screenshot() ?: fail("AGENT_SCREENSHOT_UNAVAILABLE")
                try { checkFixture(jpeg.size > 100, "AGENT_SCREENSHOT_EMPTY") }
                finally { jpeg.fill(0) }
                result.putBoolean("agentScreenshotConfirmed", true)

                // Tap an observed non-actionable Settings heading, never a guessed toggle or preference.
                stage = "AGENT_TAP_BOUNDARY_ERROR"
                val nodes = flatten(before.appRoots)
                val tap = nodes.firstOrNull { candidate ->
                    candidate.visibleToUser && !candidate.covered && !candidate.bounds.isEmpty &&
                        !candidate.clickable && !candidate.longClickable && !candidate.checkable && !candidate.editable &&
                        candidate.text?.trim() in setOf("Settings", "设置", "設定") &&
                        nodes.none { other -> other.visibleToUser && !other.covered &&
                            (other.clickable || other.longClickable || other.checkable || other.editable) &&
                            other.bounds.containsPoint(candidate.bounds.centerX, candidate.bounds.centerY) }
                } ?: fail("AGENT_SAFE_TAP_TARGET_UNAVAILABLE")
                checkFixture(controller.tap(tap.bounds.centerX, tap.bounds.centerY), "AGENT_TAP_REJECTED")
                checkFixture(DeviceInputFence.shared.awaitIdle(), "AGENT_TAP_INPUT_NOT_IDLE")
                assertSafeSettings(controller.snapshot())
                result.putBoolean("agentTapConfirmed", true)

                stage = "AGENT_SCROLL_BOUNDARY_ERROR"
                val scrollStart = controller.snapshot()
                assertSafeSettings(scrollStart)
                val scrollable = flatten(scrollStart.appRoots).firstOrNull { it.scrollable && !it.horizontal &&
                    it.visibleToUser && !it.covered && !it.bounds.isEmpty && it.moreAfter }
                    ?: fail("AGENT_SCROLL_TARGET_UNAVAILABLE")
                val beforeScroll = TreeCompressor.stabilityHash(scrollStart)
                checkFixture(controller.scroll(scrollable, "down"), "AGENT_SCROLL_REJECTED")
                controller.waitForIdle(2_000)
                val scrolled = controller.snapshot()
                assertSafeSettings(scrolled)
                checkFixture(TreeCompressor.stabilityHash(scrolled) != beforeScroll, "AGENT_SCROLL_UNOBSERVED")
                result.putBoolean("agentScrollConfirmed", true)

                stage = "AGENT_SWIPE_BOUNDARY_ERROR"
                val viewport = flatten(scrolled.appRoots).firstOrNull { it.scrollable && !it.horizontal &&
                    it.visibleToUser && !it.covered && !it.bounds.isEmpty }
                    ?: fail("AGENT_SWIPE_TARGET_UNAVAILABLE")
                val b = viewport.bounds.clippedTo(scrolled.displayWidth, scrolled.displayHeight)
                val y1 = b.top + (b.bottom - b.top) / 4
                val y2 = b.top + (b.bottom - b.top) * 3 / 4
                checkFixture(y2 - y1 > 60, "AGENT_SWIPE_VIEWPORT_TOO_SMALL")
                val beforeSwipe = TreeCompressor.stabilityHash(scrolled)
                checkFixture(controller.swipe(b.centerX, y1, b.centerX, y2, 350), "AGENT_SWIPE_REJECTED")
                checkFixture(DeviceInputFence.shared.awaitIdle(), "AGENT_SWIPE_INPUT_NOT_IDLE")
                controller.waitForIdle(2_000)
                val swiped = controller.snapshot()
                assertSafeSettings(swiped)
                checkFixture(TreeCompressor.stabilityHash(swiped) != beforeSwipe, "AGENT_SWIPE_UNOBSERVED")
                result.putBoolean("agentSwipeConfirmed", true)
            }
            checkFixture(app.credentials.status() == CredentialState.EMPTY && !app.repository.snapshot().patternBlocked,
                "AGENT_UNLOCKED_CREDENTIAL_STATE_CHANGED")
            result.putBoolean("agentUnlockedCredentialStateStayedEmpty", true)

            stage = "AGENT_STALE_EPOCH_NOT_REJECTED"
            inTask {
                val fresh = AndroidDeviceAccess.ensureScreenAccess()
                val previous = oldLease ?: fail("AGENT_LEASE_NOT_CAPTURED")
                checkFixture(fresh.epoch > previous.epoch, "AGENT_EPOCH_NOT_ADVANCED")
                checkFixture(revoked { AndroidDeviceAccess.coordinator.requireValid(previous) }, stage)
                checkFixture(AndroidDeviceAccess.coordinator.isValid(fresh), "AGENT_STALE_CHECK_REVOKED_NEW_OWNER")
                assertSafeSettings(controller.snapshot())
            }
            result.putBoolean("agentStaleEpochRejected", true)

            stage = "AGENT_SENSITIVE_RESERVATION_NOT_ENFORCED"
            val sensitive = withContext(Dispatchers.Main.immediate) { AndroidDeviceAccess.beginSensitive() }
                ?: fail("AGENT_SENSITIVE_RESERVATION_BUSY")
            try {
                checkFixture(DeviceInputFence.shared.awaitIdle(), "AGENT_SENSITIVE_INPUT_NOT_IDLE")
                checkFixture(expectDenied(unavailable = setOf("BUSY")) { inTask { controller.snapshot() } }, "AGENT_SENSITIVE_SNAPSHOT_ALLOWED")
                checkFixture(expectDenied(unavailable = setOf("BUSY")) { inTask { controller.screenshot()?.fill(0) } }, "AGENT_SENSITIVE_SCREENSHOT_ALLOWED")
                checkFixture(AndroidDeviceAccess.coordinator.owner.value == DeviceOwner.CREDENTIAL_EDIT,
                    "AGENT_SENSITIVE_RESERVATION_LOST")
            } finally {
                // Even a negative assertion must wait for the production delayed close before outer cleanup.
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main.immediate) { AndroidDeviceAccess.endSensitive(sensitive) }
                    checkFixture(waitFor { AndroidDeviceAccess.coordinator.owner.value == DeviceOwner.NONE },
                        "AGENT_SENSITIVE_RELEASE_TIMEOUT")
                }
            }
            inTask { assertSafeSettings(controller.snapshot()) }
            result.putBoolean("agentSensitiveReadsDenied", true)
            result.putBoolean("agentFreshTaskAfterSensitiveConfirmed", true)

            stage = "AGENT_CANCELLED_TASK_NOT_REJECTED"
            supervisorScope {
                val captured = CompletableDeferred<Pair<DeviceSessionLease, ThreadContextElement<*>>>()
                val task = launch(Dispatchers.Main.immediate) {
                    AndroidDeviceAccess.task(DeviceOwner.AGENT, allowAutoUnlock = false) {
                        val lease = AndroidDeviceAccess.ensureScreenAccess()
                        assertSafeSettings(controller.snapshot())
                        captured.complete(lease to AndroidDeviceAccess.contextElement())
                        awaitCancellation()
                    }
                }
                val (lease, contextElement) = withTimeoutOrNull(8_000) { captured.await() }
                    ?: fail("AGENT_CANCEL_TASK_NOT_READY")
                task.cancelAndJoin()
                checkFixture(revoked { AndroidDeviceAccess.coordinator.requireValid(lease) }, "AGENT_CANCELLED_LEASE_VALID")
                // Simulate an SDK/background callback retaining the old task context after cancellation.
                checkFixture(expectDenied(reasons = setOf(DeviceRevocationReason.INVALID_SESSION, DeviceRevocationReason.TASK_CHANGED)) {
                    withContext(Dispatchers.Main.immediate + contextElement) { controller.snapshot() }
                }, "AGENT_CANCELLED_CONTEXT_READ_ALLOWED")
            }
            inTask { assertSafeSettings(controller.snapshot()) }
            result.putBoolean("agentCancelledContextReadDenied", true)
            result.putBoolean("agentFreshTaskAfterCancelConfirmed", true)

            stage = "AGENT_OWN_ACTIVITY_LIFECYCLE_FAILED"
            supervisorScope {
                val captured = CompletableDeferred<Pair<DeviceSessionLease, ThreadContextElement<*>>>()
                val task = launch(Dispatchers.Main.immediate) {
                    AndroidDeviceAccess.task(DeviceOwner.AGENT, allowAutoUnlock = false) {
                        val lease = AndroidDeviceAccess.ensureScreenAccess()
                        assertSafeSettings(controller.snapshot())
                        captured.complete(lease to AndroidDeviceAccess.contextElement())
                        awaitCancellation()
                    }
                }
                var previousContext: ThreadContextElement<*>? = null
                try {
                    val (lease, contextElement) = withTimeoutOrNull(8_000) { captured.await() }
                        ?: fail("AGENT_OWN_ACTIVITY_TASK_NOT_READY")
                    previousContext = contextElement
                    checkFixture(AndroidDeviceAccess.coordinator.isValid(lease) && task.isActive,
                        "AGENT_OWN_ACTIVITY_PRECONDITION_FAILED")
                    // Test-side user launch, never an AI tool. Do not read or capture the product UI.
                    withContext(Dispatchers.Main.immediate) {
                        context.startActivity(Intent(context, com.phoneuse.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                    checkFixture(waitFor { lease.isRevoked && !AndroidDeviceAccess.coordinator.isValid(lease) },
                        "AGENT_OWN_ACTIVITY_LEASE_NOT_REVOKED")
                    checkFixture(lease.revocationReason == DeviceRevocationReason.SENSITIVE_SESSION,
                        "AGENT_OWN_ACTIVITY_WRONG_REVOCATION")
                    checkFixture(waitFor { task.isCompleted && task.isCancelled },
                        "AGENT_OWN_ACTIVITY_TASK_NOT_CANCELLED")
                    result.putBoolean("agentOwnActivityLeaseRevoked", true)
                    result.putBoolean("agentOwnActivityTaskCancelled", true)
                } finally {
                    withContext(NonCancellable) {
                        // Cleanup only: this cannot satisfy the automatic-cancellation assertions above.
                        task.cancelAndJoin()
                        automation.shellFixed("input keyevent KEYCODE_HOME")
                        withContext(Dispatchers.Main.immediate) {
                            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).setPackage(SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        }
                        checkFixture(waitFor { service.foregroundPackage() == SETTINGS },
                            "AGENT_OWN_ACTIVITY_SETTINGS_NOT_RESTORED")
                    }
                }
                // Test the late callback only AFTER leaving our UI, so an own-window guard cannot hide
                // a stale-context bug and the test never requests the product's tree or pixels.
                val stale = previousContext ?: fail("AGENT_OWN_ACTIVITY_CONTEXT_MISSING")
                checkFixture(expectDenied(reasons = setOf(DeviceRevocationReason.INVALID_SESSION,
                    DeviceRevocationReason.TASK_CHANGED, DeviceRevocationReason.SENSITIVE_SESSION)) {
                    withContext(Dispatchers.Main.immediate + stale) { controller.snapshot() }
                }, "AGENT_OWN_ACTIVITY_LATE_READ_ALLOWED")
            }
            result.putBoolean("agentOwnActivityLateReadDenied", true)
            inTask { assertSafeSettings(controller.snapshot()) }
            result.putBoolean("agentFreshTaskAfterOwnActivityConfirmed", true)

            stage = "AGENT_SCREEN_OFF_READ_NOT_REJECTED"
            automation.shellFixed("input keyevent KEYCODE_SLEEP")
            checkFixture(waitFor { !power.isInteractive }, "AGENT_SCREEN_DID_NOT_SLEEP")
            try {
                checkFixture(expectDenied(reasons = setOf(DeviceRevocationReason.SCREEN_LOCKED)) {
                    inTask { controller.snapshot() }
                }, "AGENT_SCREEN_OFF_SNAPSHOT_ALLOWED")
                checkFixture(expectDenied(reasons = setOf(DeviceRevocationReason.SCREEN_LOCKED)) {
                    inTask { controller.screenshot()?.fill(0) }
                }, "AGENT_SCREEN_OFF_SCREENSHOT_ALLOWED")
            } finally {
                withContext(NonCancellable) {
                    wakeAndDismiss(automation, power, keyguard, result, "agentRestore", "AGENT_RESTORE")
                }
            }
            checkFixture(waitFor { power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked }, "AGENT_WAKE_RESTORE_FAILED")
            checkFixture(!keyguard.isDeviceSecure && app.credentials.status() == CredentialState.EMPTY &&
                !app.repository.snapshot().patternBlocked && unified.store.all().isEmpty(), "AGENT_FINAL_STATE_CHANGED")
            result.putBoolean("agentScreenOffReadsDenied", true)
            result.putBoolean("agentNoSecureLockCreated", true)
            result.putBoolean("agentCredentialStateStayedEmpty", true)
        } catch (e: UnifiedAgentFixtureFailure) {
            result.putString("agentFailureStage", stage)
            result.putString("agentFailureClass", safeClass(e))
            throw e
        }
        catch (e: Exception) {
            result.putString("agentFailureStage", stage)
            result.putString("agentFailureClass", safeClass(e))
            if (e is DeviceSessionRevokedException) result.putString("agentRevocationReason", e.reason.name)
            if (e is DeviceUnavailableException) result.putString("agentUnavailableReason", e.code)
            result.putString("agentFailureLocation", e.stackTrace.filter { it.className.startsWith("com.phoneuse.") }
                .take(4).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
            fail(stage)
        }
        finally {
            // Insecure screen state only; the outer fixture restores its original service settings.
            if (!keyguard.isDeviceSecure) {
                try {
                    withContext(NonCancellable) {
                        wakeAndDismiss(automation, power, keyguard, result, "agentCleanup", "AGENT_CLEANUP")
                    }
                    result.putBoolean("agentCleanupWakeRestored", true)
                } catch (e: Exception) {
                    // Keep the primary assertion's fixed code; report cleanup separately without exception text.
                    result.putBoolean("agentCleanupWakeRestored", false)
                    result.putString("agentCleanupFailureClass", safeClass(e))
                }
            }
        }
    }

    /** Wake delivery is asynchronous. Never ask dismiss-keyguard until the display is stably interactive. */
    private suspend fun wakeAndDismiss(
        automation: UiAutomation,
        power: PowerManager,
        keyguard: KeyguardManager,
        result: Bundle,
        key: String,
        code: String,
    ) {
        fun report() {
            result.putBoolean("${key}Interactive", power.isInteractive)
            result.putBoolean("${key}DeviceSecure", keyguard.isDeviceSecure)
            result.putBoolean("${key}DeviceLocked", keyguard.isDeviceLocked)
            result.putBoolean("${key}KeyguardLocked", keyguard.isKeyguardLocked)
        }
        try {
            checkFixture(!keyguard.isDeviceSecure, "${code}_SECURE_LOCK_CHANGED")
            automation.shellFixed("input keyevent KEYCODE_WAKEUP")
            checkFixture(waitFor { power.isInteractive }, "${code}_INTERACTIVE_TIMEOUT")
            delay(250)
            checkFixture(power.isInteractive, "${code}_INTERACTIVE_LOST")
            repeat(3) {
                checkFixture(!keyguard.isDeviceSecure, "${code}_SECURE_LOCK_CHANGED")
                checkFixture(power.isInteractive, "${code}_INTERACTIVE_LOST")
                if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return
                automation.shellFixed("wm dismiss-keyguard")
                if (waitFor(2_000) { power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked }) return
                delay(200)
            }
            fail("${code}_DISMISS_TIMEOUT")
        } finally { report() }
    }

    private fun safeClass(error: Throwable): String = error.javaClass.simpleName
        .takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_\\$]{0,79}")) } ?: "UNKNOWN_EXCEPTION"

    private suspend fun <T> inTask(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        AndroidDeviceAccess.task(DeviceOwner.AGENT, allowAutoUnlock = false, block = block)
    }

    private fun assertSafeSettings(snapshot: ScreenSnapshot) {
        checkFixture(snapshot.packageName == SETTINGS && !snapshot.ownAppInFront && !snapshot.activeIsSystem &&
            snapshot.appRoots.isNotEmpty() && snapshot.displayWidth > 0 && snapshot.displayHeight > 0,
            "AGENT_SETTINGS_SNAPSHOT_NOT_CONFIRMED")
        checkFixture(flatten(snapshot.roots).none { it.password }, "AGENT_PASSWORD_NODE_EXPOSED")
    }

    private fun flatten(roots: List<UiNode>): List<UiNode> = buildList {
        fun visit(node: UiNode) { add(node); node.children.forEach(::visit) }
        roots.forEach(::visit)
    }

    private suspend fun expectDenied(
        reasons: Set<DeviceRevocationReason> = emptySet(),
        unavailable: Set<String> = emptySet(),
        operation: suspend () -> Unit,
    ): Boolean = supervisorScope {
        fun expected(error: Throwable): Boolean = when (error) {
            is DeviceSessionRevokedException -> error.reason in reasons
            is DeviceUnavailableException -> error.code in unavailable
            else -> false
        }
        val request = async {
            try { operation(); false } catch (error: Exception) { expected(error) }
        }
        try { request.await() } catch (error: Exception) { expected(error) }
    }

    private fun revoked(operation: () -> Unit): Boolean = try {
        operation(); false
    } catch (_: DeviceSessionRevokedException) { true }

    private suspend fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(100)
        true
    } == true

    private fun UiAutomation.shellFixed(command: String) {
        checkFixture(command in setOf("input keyevent KEYCODE_WAKEUP", "input keyevent KEYCODE_SLEEP",
            "input keyevent KEYCODE_HOME", "wm dismiss-keyguard"),
            "AGENT_UNEXPECTED_SHELL_COMMAND")
        ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { input ->
            // Discard the result; no shell output is copied into instrumentation results.
            val buffer = ByteArray(1024)
            while (input.read(buffer) >= 0) { /* Discard guest output. */ }
            buffer.fill(0)
        }
    }

    private fun checkFixture(condition: Boolean, code: String) { if (!condition) fail(code) }
    private fun fail(code: String): Nothing = throw UnifiedAgentFixtureFailure(code)
}
