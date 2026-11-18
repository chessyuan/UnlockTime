package org.localwake.app

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.view.KeyEvent
import com.phoneuse.app.AppGraph
import com.phoneuse.routine.Conditions
import com.phoneuse.routine.NextRun
import com.phoneuse.routine.OccurrenceStatus
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.RoutineOwner
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.Trigger
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.routine.localTask
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceInputFence
import com.phoneuse.unified.DeviceOwner
import com.phoneuse.unified.GlobalUnlockSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.execution.AppLaunchConfiguration
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.security.CredentialState
import org.localwake.app.security.UnlockMethod
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit real-device upgrade fixture. Packaged ONLY in the removable androidTest APK.
 *
 * Modes are exactly `status` (aggregate reads) and `pattern` (one production alarm attempt).
 * There is no default mode, credential loader, credential writer, shell command, permission grant,
 * network toggle, screenshot, lock-screen tree reader, or guard-reset operation in this fixture.
 * Production status() verifies the stored credential internally and returns only CredentialState.
 * The production native executor, not this fixture, reads/draws the credential for the one alarm.
 */
class SamsungUpgradeInstrumentation : Instrumentation() {
    private var mode: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode")?.takeIf { it == "status" || it == "pattern" }
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val outcome = try {
            runBlocking {
                requireFixture(mode != null, "EXPLICIT_MODE_REQUIRED")
                requireDevice()
                result.putString("mode", requireNotNull(mode))
                val app = targetContext.localWake
                val unified = UnifiedRoutineRuntime.get(targetContext)
                // The normal application startup owns migration; this fixture never retries/repairs it.
                waitFor(15_000) { unified.authority.allows(RoutineOwner.ROUTINE) && unified.store.ready }
                reportStatus(app, unified, result)
                if (mode == "pattern") runPattern(app, unified, result)
            }
            if (mode == "status") "STATUS_REPORTED" else "PASS"
        } catch (abort: FixtureAbort) {
            abort.code
        } catch (_: Exception) {
            // Never export exception messages, task names, filesystem paths, or framework objects.
            "FIXTURE_ERROR"
        }
        result.putString("status", outcome)
        finish(if (outcome == "PASS" || outcome == "STATUS_REPORTED") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun requireDevice() {
        requireFixture(Build.MODEL == "SM-S9260" && Build.VERSION.SDK_INT == 36, "SUPPORTED_SAMSUNG_REQUIRED")
        requireFixture(targetContext.packageName == TARGET_PACKAGE, "TARGET_PACKAGE_MISMATCH")
        val users = targetContext.getSystemService(UserManager::class.java)
        requireFixture(users.isSystemUser && !users.isManagedProfile, "PRIMARY_USER_REQUIRED")
        requireFixture(users.isUserUnlocked, "FIRST_MANUAL_UNLOCK_REQUIRED")
    }

    private suspend fun reportStatus(app: UnlockTimeRuntime, unified: UnifiedRoutineRuntime, result: Bundle) {
        val credential = app.credentials.status() // Internal decrypt-and-zero check, never returns a credential.
        val method = runCatching { app.credentials.method() }.getOrNull()
        result.putString("credentialState", credential.name)
        result.putBoolean("credentialReady", credential == CredentialState.READY)
        result.putString("method", method?.name ?: "UNAVAILABLE")
        val info = targetContext.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
        result.putString("versionName", info.versionName ?: "UNAVAILABLE")
        result.putLong("versionCode", info.longVersionCode)
        result.putInt("androidApi", Build.VERSION.SDK_INT)
        result.putBoolean("primaryUser", true)
        result.putBoolean("unlockedSinceBoot", true)
        result.putBoolean("serviceReady", LocalWakeAccessibilityService.instance.value?.isReady() == true)
        result.putBoolean("globalAutoUnlock", GlobalUnlockSettings(targetContext).enabled)
        reportKeyguard(result)

        // Task/history data is compared in memory only. No names, IDs, times, apps, traces or hashes leave.
        unified.migrationMutex.withLock {
            val source = app.repository.legacySnapshot()
            val journal = unified.authority.snapshot()
            val targets = unified.store.all()
            val pending = unified.store.pending(journal.epoch)
            val local = unified.store.localSnapshot()
            val identity = unified.store.ready && journal.owner == RoutineOwner.ROUTINE &&
                journal.migrationId != null && unified.store.migrationId == journal.migrationId
            val mapPreserved = identity && journal.idMap.keys == source.schedules.map { it.id }.toSet() &&
                journal.idMap.values.toSet().size == journal.idMap.size && source.schedules.all { old ->
                    targets.singleOrNull { it.id == journal.idMap[old.id] }?.legacyId == old.id
                }
            fun projected(old: ScheduleModel): ScheduleModel? {
                val target = targets.singleOrNull { it.id == journal.idMap[old.id] } ?: return null
                return target.localTask(pending.singleOrNull { it.routineId == target.id })
            }
            result.putInt("taskCount", targets.size)
            result.putInt("legacyCount", source.schedules.size)
            result.putBoolean("storeReady", unified.store.ready)
            result.putString("authorityOwner", journal.owner.name)
            result.putString("authorityPhase", journal.phase.name)
            result.putLong("authorityEpoch", journal.epoch)
            result.putBoolean("migrationIdentityMatches", identity)
            result.putBoolean("migrationMapPreserved", mapPreserved)
            // Strict equality includes the old armed token/date; legitimate later execution can change it.
            result.putBoolean("legacyTaskFieldsPreserved", mapPreserved && source.schedules.all { projected(it) == it })
            result.putBoolean("legacyTaskDefinitionsPreserved", mapPreserved && source.schedules.all { old ->
                projected(old)?.withoutRuntimeFields() == old.withoutRuntimeFields()
            })
            result.putBoolean("legacyHistoryPreserved", identity && source.history.all { it in local.history })
            result.putBoolean("failureGuard", local.patternBlocked)
            result.putBoolean("legacyFailureGuard", source.patternBlocked)
            result.putBoolean("guardNotWeakened", identity && (!source.patternBlocked || local.patternBlocked))
        }
    }

    private suspend fun runPattern(app: UnlockTimeRuntime, unified: UnifiedRoutineRuntime, result: Bundle) {
        requireFixture(patternRunning.compareAndSet(false, true), "FIXTURE_ALREADY_RUNNING")
        var saved: Routine? = null
        var routines: RoutineSystem? = null
        var cleanupComplete = true
        try {
            requireDevice()
            requireFixture(waitFor(10_000) { LocalWakeAccessibilityService.instance.value?.isReady() == true },
                "ACCESSIBILITY_NOT_READY")
            val system = RoutineSystem.get(targetContext)
            routines = system
            requirePatternReady(app, unified, system)
            val before = unified.store.all()
            val beforeHistory = unified.store.localSnapshot().history
            requireFixture(before.none { it.title.startsWith(FIXTURE_PREFIX) }, "EXISTING_FIXTURE_REQUIRES_REVIEW")
            requireFixture(before.size < Routine.MAX_ROUTINES, "TASK_CAPACITY_REQUIRED")
            // One new record must not evict an old record from the production 100-entry history cap.
            requireFixture(beforeHistory.size < 100, "HISTORY_CAPACITY_REQUIRED")
            requireFixture(beforeHistory.none { it.code == "IN_PROGRESS" }, "EXISTING_UNKNOWN_EXECUTION")
            requireFixture(noImminentUserTask(unified, before), "OTHER_TASK_DUE_DURING_FIXTURE")
            requireFixture(targetContext.packageManager.getLaunchIntentForPackage(OBSERVED_APP) != null,
                "CALCULATOR_NOT_INSTALLED")

            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                ?: fail("UI_AUTOMATION_UNAVAILABLE")
            val dueAt = System.currentTimeMillis() + ALARM_DELAY_MS
            val fixture = Routine(
                title = FIXTURE_PREFIX + UUID.randomUUID().toString(),
                trigger = Trigger.Once(dueAt),
                conditions = Conditions(cooldownMin = 0),
                action = RoutineAction.OpenAppsSequence(listOf(AppLaunchConfiguration(OBSERVED_APP)), ExecutionMode.FULL),
            )
            requireFixture(system.edit { saved = save(fixture) }, "FIXTURE_SAVE_FAILED")
            val owned = saved ?: fail("FIXTURE_SAVE_FAILED")
            // Before the due instant only: arm the actual production AlarmManager path, never runNow/tick.
            system.resync()
            val occurrence = unified.store.pending(unified.authority.snapshot().epoch)
                .singleOrNull { it.routineId == owned.id && it.revision == owned.definitionRevision }
                ?: fail("OCCURRENCE_NOT_ARMED")
            requireFixture(occurrence.dueAt == dueAt && occurrence.event == null, "OCCURRENCE_MISMATCH")
            requireFixture(System.currentTimeMillis() + 8_000 < dueAt, "ALARM_ARMING_TOO_SLOW")
            requirePatternReady(app, unified, system)
            result.putBoolean("fixtureArmed", true)
            result.putBoolean("fixtureDelayTwentySeconds", true)

            sleepWithKeyEvent(automation)
            val power = targetContext.getSystemService(PowerManager::class.java)
            val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
            val locked = waitFor(6_000) { !power.isInteractive && keyguard.isDeviceLocked && keyguard.isKeyguardLocked }
            result.putBoolean("lockedBeforeAlarm", locked)
            requireFixture(locked && System.currentTimeMillis() < dueAt, "LOCK_PRECONDITION_FAILED")

            // No synthetic broadcast, direct unlock call, credential load, wake key, or shell polling.
            val execution = awaitExecution(unified, occurrence.token) ?: fail("ALARM_EXECUTION_TIMEOUT")
            result.putString("executionResult", execution.code.takeIf { it in OUTCOMES } ?: "OTHER_EXECUTION_FAILURE")
            val idle = DeviceInputFence.shared.awaitIdle(5_000)
            val fullyUnlocked = !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked && power.isInteractive
            result.putBoolean("fullyUnlocked", fullyUnlocked)
            // Only normal-screen package metadata, after confirmed Keyguard exit; no lock-screen tree query.
            val foreground = if (fullyUnlocked && idle) withContext(Dispatchers.Main.immediate) {
                !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
                    LocalWakeAccessibilityService.instance.value?.foregroundPackage() == OBSERVED_APP
            } else false
            val appConfirmed = execution.apps.singleOrNull()?.let {
                it.packageName == OBSERVED_APP && it.code == "FOREGROUND_CONFIRMED"
            } == true
            val engineBeganLocked = execution.trace.any { it.endsWith(" unlock.start credentialRequired=true fullyUnlocked=false") }
            val onePattern = execution.trace.count { it.endsWith(" pattern.dispatch") } == 1 &&
                execution.trace.count { it.endsWith(" pattern.result=COMPLETED") } == 1
            val engineConfirmed = execution.trace.any { it.endsWith(" keyguard.fullyUnlocked") }
            val ownOccurrences = unified.store.occurrences().filter { it.routineId == owned.id }
            val once = ownOccurrences.singleOrNull()?.let {
                it.token == occurrence.token && it.status == OccurrenceStatus.FINISHED
            } == true && unified.store.get(owned.id)?.enabled == false &&
                unified.store.localSnapshot().history.count { it.id == occurrence.token && it.code != "IN_PROGRESS" } == 1
            val guard = unified.store.patternBlocked
            val userTasksPreserved = unified.store.all().filterNot { it.id == owned.id } == before
            val historyPreserved = beforeHistory.all { it in unified.store.localSnapshot().history }
            result.putBoolean("foregroundConfirmed", foreground && appConfirmed)
            result.putBoolean("engineBeganLocked", engineBeganLocked)
            result.putBoolean("onePatternCompleted", onePattern)
            result.putBoolean("engineConfirmedUnlocked", engineConfirmed)
            result.putBoolean("oneShotCompletedOnce", once)
            result.putBoolean("failureGuardAfter", guard)
            result.putBoolean("existingTasksUnchanged", userTasksPreserved)
            result.putBoolean("existingHistoryPreserved", historyPreserved)
            requireFixture(execution.code == "SUCCESS" && fullyUnlocked && foreground && appConfirmed &&
                engineBeganLocked && onePattern && engineConfirmed && once && !guard && idle &&
                userTasksPreserved && historyPreserved, "PATTERN_NOT_CONFIRMED")
        } finally {
            try {
                cleanupComplete = withContext(NonCancellable) {
                    val owned = saved
                    if (owned == null) true else cleanupOwnRoutine(requireNotNull(routines), unified, owned)
                }
            } catch (_: Exception) {
                cleanupComplete = false
            } finally {
                result.putBoolean("cleanupComplete", cleanupComplete)
                // Never reset the latch, restore a snapshot, delete history or clean any other fixture ID.
                result.putBoolean("failureGuardAfter", unified.store.patternBlocked)
                patternRunning.set(false)
            }
            requireFixture(cleanupComplete, "FIXTURE_CLEANUP_FAILED")
        }
    }

    private suspend fun requirePatternReady(app: UnlockTimeRuntime, unified: UnifiedRoutineRuntime, system: RoutineSystem) {
        requireDevice()
        requireFixture(app.credentials.status() == CredentialState.READY, "CREDENTIAL_NOT_READY")
        requireFixture(app.credentials.method() == UnlockMethod.PATTERN, "PATTERN_METHOD_REQUIRED")
        val journal = unified.authority.snapshot()
        requireFixture(unified.store.ready && journal.owner == RoutineOwner.ROUTINE &&
            journal.migrationId != null && unified.store.migrationId == journal.migrationId, "ROUTINE_AUTHORITY_REQUIRED")
        requireFixture(!app.repository.snapshot().patternBlocked, "FAILURE_GUARD_SET")
        requireFixture(LocalWakeAccessibilityService.instance.value?.isReady() == true, "ACCESSIBILITY_NOT_READY")
        requireFixture(system.alarms.exactAllowed(), "EXACT_ALARM_REQUIRED")
        requireFixture(GlobalUnlockSettings(targetContext).enabled, "GLOBAL_AUTO_UNLOCK_REQUIRED")
        requireFixture(AndroidDeviceAccess.coordinator.owner.value == DeviceOwner.NONE && DeviceInputFence.shared.isIdle &&
            !AppGraph.runner.running.value, "DEVICE_BUSY")
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        requireFixture(keyguard.isDeviceSecure, "SECURE_DEVICE_REQUIRED")
        requireFixture(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            targetContext.getSystemService(PowerManager::class.java).isInteractive, "START_UNLOCKED_REQUIRED")
    }

    private fun noImminentUserTask(unified: UnifiedRoutineRuntime, existing: List<Routine>): Boolean {
        val now = ZonedDateTime.now()
        val end = now.toInstant().toEpochMilli() + ALARM_DELAY_MS + EXECUTION_WAIT_MS
        if (unified.store.occurrences().any {
                it.status == OccurrenceStatus.CLAIMED || (it.status == OccurrenceStatus.PENDING && it.dueAt <= end)
            }) return false
        return existing.none { r -> r.enabled && NextRun.compute(r, now)?.toInstant()?.toEpochMilli()?.let { it <= end } == true }
    }

    private fun sleepWithKeyEvent(automation: UiAutomation) {
        val at = SystemClock.uptimeMillis()
        val down = automation.injectInputEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLEEP, 0), true)
        val up = automation.injectInputEvent(KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SLEEP, 0), true)
        requireFixture(down && up, "SLEEP_KEY_REJECTED")
    }

    private suspend fun awaitExecution(unified: UnifiedRoutineRuntime, token: String): ExecutionRecord? =
        withTimeoutOrNull(EXECUTION_WAIT_MS) {
            unified.store.localStates.first { state -> state.history.any { it.id == token && it.code != "IN_PROGRESS" } }
                .history.single { it.id == token }
        }

    private suspend fun cleanupOwnRoutine(system: RoutineSystem, unified: UnifiedRoutineRuntime, owned: Routine): Boolean {
        var identityMatches = false
        val committed = system.edit {
            val found = get(owned.id)
            identityMatches = found == null || (found.title == owned.title &&
                found.definitionRevision == owned.definitionRevision && found.trigger == owned.trigger && found.action == owned.action)
            if (found != null && identityMatches) delete(owned.id)
        }
        return committed && identityMatches && unified.store.get(owned.id) == null
    }

    private fun reportKeyguard(result: Bundle) {
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        result.putBoolean("deviceSecure", keyguard.isDeviceSecure)
        result.putBoolean("deviceLocked", keyguard.isDeviceLocked)
        result.putBoolean("keyguardLocked", keyguard.isKeyguardLocked)
        result.putBoolean("interactive", targetContext.getSystemService(PowerManager::class.java).isInteractive)
    }

    private fun ScheduleModel.withoutRuntimeFields() = copy(armedToken = null, armedAt = null, armedDate = null, lastDate = null)

    private suspend fun waitFor(timeout: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) {
            while (!condition()) delay(100)
            true
        } ?: false

    private fun requireFixture(condition: Boolean, code: String) { if (!condition) fail(code) }
    private fun fail(code: String): Nothing = throw FixtureAbort(code)
    private class FixtureAbort(val code: String) : Exception()

    private companion object {
        const val TARGET_PACKAGE = "org.localwake.app"
        const val OBSERVED_APP = "com.sec.android.app.popupcalculator"
        const val FIXTURE_PREFIX = "Samsung upgrade fixture "
        const val ALARM_DELAY_MS = 20_000L
        const val EXECUTION_WAIT_MS = 125_000L
        val patternRunning = AtomicBoolean(false)
        val OUTCOMES = setOf(
            "SUCCESS", "WAKE_BLOCKED", "FIRST_MANUAL_UNLOCK_REQUIRED", "TASK_CHANGED", "DEVICE_RELOCKED",
            "ACCESSIBILITY_DISCONNECTED", "SERVICE_DISCONNECTED", "EXECUTION_BUSY", "EXECUTION_TIMEOUT",
            "EXECUTION_INTERRUPTED", "EXECUTION_ERROR", "EXECUTION_FAILED", "EXECUTOR_UNAVAILABLE",
            "MANUAL_RESET_REQUIRED", "MANUAL_UNLOCK_REQUIRED", "CREDENTIAL_EMPTY", "CREDENTIAL_INPUT_REJECTED",
            "UNLOCK_METHOD_CHANGED", "UNLOCK_SURFACE_CHANGED", "LOCK_SCREEN_UNAVAILABLE", "AUTHENTICATION_REJECTED",
            "KEYGUARD_STILL_LOCKED", "PATTERN_GRID_UNAVAILABLE", "PATTERN_GRID_CHANGED",
            "PATTERN_GESTURE_CANCELLED", "PATTERN_GESTURE_REJECTED", "PATTERN_GESTURE_TIMEOUT",
            "APP_NOT_INSTALLED", "APP_LAUNCH_REJECTED", "APP_LAUNCH_UNOBSERVED", "MISSED_WINDOW",
        )
    }
}
