package org.localwake.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.device.KeyguardSurfacePolicy
import org.localwake.app.device.ScreenBounds
import org.localwake.app.device.ScreenGeometry
import org.localwake.app.device.SystemUiTree
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.scheduler.ExecutionMode
import com.phoneuse.routine.Conditions
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.RoutineOwner
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.Trigger
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceInputFence
import com.phoneuse.unified.DeviceOwner
import com.phoneuse.unified.DeviceSessionLease
import com.phoneuse.unified.GlobalUnlockSettings
import org.localwake.app.execution.AppLaunchConfiguration
import org.localwake.app.security.CredentialState
import org.localwake.app.security.UnlockMethod
import java.security.SecureRandom
import java.util.Locale

/** Entry point for a disposable AOSP AVD. The shipped APK does not contain this class. */
class EmulatorCompatibilityInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val status = try {
            runBlocking { runScenario(result) }
            "PASS"
        } catch (abort: FixtureAbort) {
            abort.code
        } catch (abort: UnifiedAgentFixtureFailure) {
            abort.code
        } catch (_: Exception) {
            "FIXTURE_ERROR"
        }
        result.putString("status", status)
        finish(if (status == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private suspend fun runScenario(result: Bundle) {
        val scenario = Scenario.parse(arguments.getString("scenario")) ?: fail("SCENARIO_REQUIRED")
        result.putString("scenario", scenario.argument)
        requireEmulatorArguments()

        val users = targetContext.getSystemService(UserManager::class.java)
        requireFixture(users.isSystemUser && !users.isManagedProfile && users.isUserUnlocked &&
            users.userProfiles == listOf(Process.myUserHandle()), "ISOLATED_PRIMARY_USER_REQUIRED")
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        requireFixture(!keyguard.isDeviceSecure && !keyguard.isDeviceLocked, "EXISTING_DEVICE_LOCK")

        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ?: fail("UI_AUTOMATION_UNAVAILABLE")
        val guestAvd = automation.shell("getprop ro.boot.qemu.avd_name")
            .ifEmpty { automation.shell("getprop ro.kernel.qemu.avd_name") }
        requireFixture(guestAvd == arguments.getString("avdName"), "AVD_NAME_MISMATCH")
        result.putString("environment", "EMULATOR")
        result.putInt("androidApi", Build.VERSION.SDK_INT)
        if (scenario.method != null && Build.VERSION.SDK_INT < 34) fail("API_REQUIRES_34")

        val app = targetContext.localWake
        val unified = UnifiedRoutineRuntime.get(targetContext)
        requireFixture(waitFor(20_000) { unified.authority.allows(RoutineOwner.ROUTINE) && unified.store.ready },
            "UNIFIED_MIGRATION_NOT_READY")
        val routines = RoutineSystem.get(targetContext)
        requireFixture(unified.store.all().isEmpty(), "EXISTING_ROUTINES")
        requireFixture(app.credentials.status() == CredentialState.EMPTY, "EXISTING_APP_CREDENTIAL")
        val original = app.repository.snapshot()
        requireFixture(original.schedules.isEmpty() && !original.patternBlocked, "APP_NOT_CLEAN")
        requireFixture(routines.alarms.exactAllowed(), "EXACT_ALARM_PERMISSION_REQUIRED")
        requireFixture(scenario.mode != ExecutionMode.FULL || GlobalUnlockSettings(targetContext).enabled,
            "AUTOMATIC_UNLOCK_SETTING_REQUIRED")
        val launchApps = if (scenario.mode == ExecutionMode.FULL) {
            requireFixture(targetContext.packageManager.getLaunchIntentForPackage(OBSERVED_APP) != null,
                "OBSERVATION_APP_NOT_INSTALLED")
            // Repeated launches are intentional. Both delays apply before their respective launch.
            listOf(AppLaunchConfiguration(OBSERVED_APP, 1), AppLaunchConfiguration(OBSERVED_APP, 499))
        } else emptyList()

        val enabledServices = automation.readSecureSetting("enabled_accessibility_services")
        val service = ComponentName(TARGET_PACKAGE, LocalWakeAccessibilityService::class.java.name)
        requireFixture(enabledServices == null || enabledServices.isEmpty() ||
            ComponentName.unflattenFromString(enabledServices) == service, "EXISTING_ACCESSIBILITY_SERVICE")
        val accessibilityEnabled = automation.readSecureSetting("accessibility_enabled")
        requireFixture(accessibilityEnabled in setOf(null, "0", "1"), "UNEXPECTED_ACCESSIBILITY_SETTING")

        val timeout = automation.readSecureSetting("lock_screen_lock_after_timeout")
        requireFixture(timeout == null || timeout.matches(Regex("[0-9]{1,12}")), "UNEXPECTED_LOCK_TIMEOUT")
        val credential = scenario.method?.let(::makeCredential)
        var routineId: Long? = null
        var setupLease: DeviceSessionLease? = null
        var systemCredentialChanged = false
        var appCredentialChanged = false
        var screenSettingsChanged = false
        try {
            result.putBoolean("accessibilityWasEnabled", isOwnAccessibilityService(enabledServices, service))
            bindAccessibilityService(automation, service, result)
            if (scenario == Scenario.AGENT) {
                // This branch is reached only after every emulator/AVD/user/credential/task guard above.
                UnifiedAgentFixture.run(targetContext, automation, result)
                return
            }
            if (credential != null) {
                setupLease = AndroidDeviceAccess.coordinator.beginCredentialEdit() ?: fail("SENSITIVE_SESSION_BUSY")
                requireFixture(DeviceInputFence.shared.awaitIdle(), "DEVICE_INPUT_NOT_IDLE")
                val method = requireNotNull(scenario.method)
                screenSettingsChanged = true
                automation.writeSecureSetting("lock_screen_lock_after_timeout", "0")
                systemCredentialChanged = true
                val command = if (method == UnlockMethod.PIN) "set-pin" else "set-password"
                requireFixture(automation.changeSyntheticLock(command, credential) && waitFor(4_000) { keyguard.isDeviceSecure },
                    "SYNTHETIC_DEVICE_SETUP_FAILED")
                appCredentialChanged = true
                app.scheduler.mutex.withLock { app.credentials.saveText(method, credential.copyOf()) }
                requireFixture(app.credentials.status() == CredentialState.READY, "SYNTHETIC_APP_SETUP_FAILED")
                AndroidDeviceAccess.coordinator.release(requireNotNull(setupLease))
                setupLease = null
            }

            val power = targetContext.getSystemService(PowerManager::class.java)
            automation.shell("input keyevent KEYCODE_SLEEP")
            requireFixture(waitFor(8_000) { !power.isInteractive && (credential == null || keyguard.isDeviceLocked) },
                "TEST_PRECONDITION_LOCK_FAILED")
            result.putBoolean("lockedBeforeAlarm", keyguard.isDeviceLocked)
            if (scenario == Scenario.INSPECT) {
                // Reproduce the observer opening the bouncer. This gesture carries no credential.
                automation.shell("input keyevent KEYCODE_WAKEUP")
                requireFixture(waitFor(4_000) { power.isInteractive }, "INSPECTION_SCREEN_UNAVAILABLE")
                delay(1_000) // Owner reveals the bouncer after the wake animation finishes.
                val metrics = targetContext.resources.displayMetrics
                val x = metrics.widthPixels / 2
                automation.shell("input swipe $x ${metrics.heightPixels * 4 / 5} $x ${metrics.heightPixels / 5} 350")
                requireFixture(keyguard.isDeviceLocked, "INSPECTION_PRECONDITION_UNLOCKED")
            }

            val alarm = Routine(title = "Emulator compatibility test", conditions = Conditions(cooldownMin = 0),
                trigger = Trigger.Once(System.currentTimeMillis() + 10_000),
                action = RoutineAction.OpenAppsSequence(launchApps, scenario.mode))
            requireFixture(routines.edit { routineId = save(alarm).id }, "ROUTINE_NOT_SAVED")
            // Force a deterministic resync, then wait for the actual durable occurrence. No old scheduler entrypoint.
            routines.resync()
            requireFixture(waitFor(4_000) {
                unified.store.pending(unified.authority.snapshot().epoch).any { it.routineId == routineId }
            }, "OCCURRENCE_NOT_ARMED")
            val occurrence = unified.store.pending(unified.authority.snapshot().epoch)
                .singleOrNull { it.routineId == routineId }?.token ?: fail("OCCURRENCE_NOT_ARMED")
            requireFixture(System.currentTimeMillis() < (alarm.trigger as Trigger.Once).atEpochMs, "ALARM_ARMING_TOO_SLOW")
            val execution = awaitExecution(app, occurrence) ?: fail("ALARM_EXECUTION_TIMEOUT")
            result.putString("executionResult", execution.code.takeIf { it.matches(OUTCOME_CODE) } ?: "UNKNOWN")
            result.putBoolean("failureGuard", app.repository.snapshot().patternBlocked)
            val fullyUnlocked = !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
            result.putBoolean("fullyUnlocked", fullyUnlocked)
            result.putBoolean("oneShotConsumed", unified.store.get(requireNotNull(routineId))?.enabled == false)
            requireFixture(unified.store.get(requireNotNull(routineId))?.enabled == false, "ONCE_NOT_CONSUMED")
            var foregroundObserved = scenario.mode != ExecutionMode.FULL
            if (scenario.mode == ExecutionMode.FULL) {
                val observed = execution.apps.map { it.packageName } == launchApps.map { it.packageName } &&
                    execution.apps.all { it.code == "FOREGROUND_CONFIRMED" } &&
                    LocalWakeAccessibilityService.instance.value?.foregroundPackage() == OBSERVED_APP
                result.putBoolean("foregroundObserved", observed)
                result.putInt("confirmedAppLaunches", execution.apps.count { it.code == "FOREGROUND_CONFIRMED" })
                foregroundObserved = observed
            }

            when (scenario) {
                Scenario.AGENT -> fail("AGENT_SCENARIO_FLOW_ERROR")
                Scenario.WAKE -> requireFixture(execution.code == "WAKE_CONFIRMED" && power.isInteractive,
                    "WAKE_NOT_CONFIRMED")
                Scenario.INSPECT -> {
                    if (execution.code != "LOCK_SCREEN_DETECTED") recordAuthenticationFailure(result, execution)
                    requireFixture(execution.code == "LOCK_SCREEN_DETECTED" &&
                        keyguard.isDeviceLocked && !app.repository.snapshot().patternBlocked, "INSPECTION_NOT_CONFIRMED")
                }
                Scenario.SEQUENCE -> requireFixture(execution.code == "SUCCESS" && fullyUnlocked && foregroundObserved &&
                    !app.repository.snapshot().patternBlocked, "NATIVE_SEQUENCE_NOT_CONFIRMED")
                Scenario.PIN, Scenario.PASSWORD -> {
                    val beganLocked = execution.trace.any {
                        it.endsWith("unlock.start credentialRequired=true fullyUnlocked=false")
                    }
                    val confirmed = execution.trace.any { it.endsWith("keyguard.fullyUnlocked") }
                    result.putBoolean("engineBeganLocked", beganLocked)
                    result.putBoolean("engineConfirmedUnlocked", confirmed)
                    val success = execution.code == "SUCCESS" && beganLocked && confirmed && fullyUnlocked && foregroundObserved &&
                        !app.repository.snapshot().patternBlocked
                    if (!success) recordAuthenticationFailure(result, execution)
                    requireFixture(success, "AUTHENTICATION_NOT_CONFIRMED")
                }
            }
        } finally {
            try {
                setupLease?.let { AndroidDeviceAccess.coordinator.release(it) }
                setupLease = null
                val cleanup = cleanup(app, routines, automation, routineId, credential, appCredentialChanged,
                    systemCredentialChanged, screenSettingsChanged, timeout, enabledServices, accessibilityEnabled, keyguard)
                result.putBoolean("cleanupComplete", cleanup)
                requireFixture(cleanup, "EMULATOR_CLEANUP_FAILED")
            } finally {
                credential?.fill('\u0000')
            }
        }
    }

    private fun recordAuthenticationFailure(result: Bundle, execution: ExecutionRecord) {
        val stages = execution.trace.filter { line -> TRACE_STAGES.any { it.matches(line) } }.take(24)
        result.putString("executionTrace", stages.joinToString(" | "))
        runOnMainSync {
            val metadata = runCatching { observeAuthenticationMetadata() }
                .getOrElse { JSONObject().put("capture", "ERROR") }
            result.putString("authenticationMetadata", metadata.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun observeAuthenticationMetadata(): JSONObject {
        val service = LocalWakeAccessibilityService.instance.value
            ?: return JSONObject().put("capture", "SERVICE_UNAVAILABLE")
        val report = JSONObject()
        val tree = SystemUiTree.capture(service, ScreenGeometry(service))
        if (tree == null) {
            report.put("capture", "REJECTED")
            val root = service.rootInActiveWindow ?: return report.put("rootPresent", false)
            try {
                report.put("rootPresent", true)
                    .put("rootIsSystemUi", root.packageName?.toString() == KeyguardSurfacePolicy.SYSTEM_UI)
                val window = root.window ?: return report
                try {
                    report.put("nativeWindowType", window.type).put("nativeWindowId", window.id)
                } finally {
                    window.recycle()
                }
            } finally {
                root.recycle()
            }
            return report
        }
        tree.use {
            val snapshot = tree.snapshot
            val geometry = snapshot.geometry
            val window = snapshot.window
            report.put("capture", "TRUSTED")
                .put("canPrepare", tree.canPrepare())
                .put("recognizesEntry", KeyguardSurfacePolicy.recognizesEntry(snapshot.nodes))
                .put("nodeCount", snapshot.nodes.size)
                .put("foreignCount", snapshot.nodes.count { it.visible && it.packageName != KeyguardSurfacePolicy.SYSTEM_UI })
                .put("resourceIds", JSONArray(snapshot.nodes.filter { it.visible }.mapNotNull { it.resourceEntry }
                    .filter { it.matches(Regex("[a-z0-9_]{1,80}")) }.distinct().take(40)))
                .put("screen", JSONObject()
                    .put("bounds", geometry.bounds.asArray())
                    .put("density", geometry.density.toDouble())
                    .put("displayId", geometry.displayId)
                    .put("rotation", geometry.rotation)
                    .put("magnificationScale", geometry.magnificationScale?.toDouble())
                    .put("magnificationActivated", geometry.magnificationActivated)
                    .put("permitsCoordinateInput", geometry.permitsCoordinateInput))
                .put("window", JSONObject()
                    .put("id", window.id)
                    .put("displayId", window.displayId)
                    .put("type", window.type.name)
                    .put("active", window.active)
                    .put("bounds", window.bounds.asArray())
                    .put("layer", window.layer)
                    .put("touchableRectangular", window.touchableRectangular))
                .put("higherWindowBounds", JSONArray().apply {
                    snapshot.higherWindowBounds.take(8).forEach { put(it.asArray()) }
                })
            val controls = JSONArray()
            snapshot.nodes.asSequence()
                .filter { node -> node.packageName == KeyguardSurfacePolicy.SYSTEM_UI && node.windowId == window.id }
                .filter { node ->
                    node.password || node.editable || node.numericPassword || node.textPassword ||
                        node.setText || node.imeEnter || node.resourceEntry in AUTH_CONTROL_IDS ||
                        node.simpleClass in AUTH_CONTROL_CLASSES
                }
                .take(16)
                .forEach { node ->
                    val resourceId = node.viewId?.takeIf { node.resourceEntry in AUTH_CONTROL_IDS }
                    val className = node.className?.takeIf { it.matches(CLASS_NAME) }
                    controls.put(JSONObject()
                        .put("resourceId", resourceId)
                        .put("class", className)
                        .put("bounds", node.bounds.asArray())
                        .put("visible", node.visible)
                        .put("enabled", node.enabled)
                        .put("password", node.password)
                        .put("editable", node.editable)
                        .put("numericPassword", node.numericPassword)
                        .put("textPassword", node.textPassword)
                        .put("setText", node.setText)
                        .put("imeEnter", node.imeEnter))
                }
            report.put("authenticationControls", controls)
        }
        return report
    }

    private fun ScreenBounds.asArray(): JSONArray = JSONArray().put(left).put(top).put(right).put(bottom)

    private suspend fun bindAccessibilityService(automation: UiAutomation, service: ComponentName, result: Bundle) {
        // Starting instrumentation force-stops its target on Android 14. The manager only
        // rebinds after the parsed enabled-component set changes. No other service was enabled.
        automation.writeSecureSetting("enabled_accessibility_services", null)
        requireFixture(automation.readSecureSetting("enabled_accessibility_services").isNullOrEmpty(),
            "ACCESSIBILITY_DISABLE_NOT_APPLIED")
        requireFixture(waitFor(4_000) { LocalWakeAccessibilityService.instance.value == null },
            "ACCESSIBILITY_UNBIND_TIMEOUT")
        delay(500)

        automation.writeSecureSetting("accessibility_enabled", "1")
        automation.writeSecureSetting("enabled_accessibility_services", service.flattenToString())
        val settingApplied = isOwnAccessibilityService(automation.readSecureSetting("enabled_accessibility_services"), service)
        result.putBoolean("accessibilitySettingApplied", settingApplied)
        requireFixture(settingApplied, "ACCESSIBILITY_ENABLE_NOT_APPLIED")

        val ready = waitFor(12_000) { LocalWakeAccessibilityService.instance.value?.isReady() == true }
        val manager = targetContext.getSystemService(AccessibilityManager::class.java)
        val frameworkEnabled = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { isOwnAccessibilityService(it.id, service) } == true
        result.putBoolean("accessibilityFrameworkEnabled", frameworkEnabled)
        result.putBoolean("accessibilityInstanceRegistered", LocalWakeAccessibilityService.instance.value != null)
        result.putBoolean("accessibilityReady", ready)
        requireFixture(ready, "ACCESSIBILITY_SERVICE_UNAVAILABLE")
    }

    private fun isOwnAccessibilityService(value: String?, own: ComponentName): Boolean =
        value != null && ComponentName.unflattenFromString(value) == own

    private fun requireEmulatorArguments() {
        requireFixture(targetContext.packageName == TARGET_PACKAGE && context.packageName == TEST_PACKAGE,
            "PACKAGE_MISMATCH")
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT).substringBefore(':').split('/')
        val emulatorFingerprint = fingerprint.any { it == "generic" || it.startsWith("generic_") || it.startsWith("sdk_") }
        requireFixture(Build.HARDWARE in setOf("ranchu", "goldfish") && emulatorFingerprint, "EMULATOR_HARDWARE_REQUIRED")
        val serial = arguments.getString("testSerial")
        requireFixture(serial != null && serial.matches(Regex("emulator-[0-9]{4,5}")) &&
            serial.substringAfter('-').toInt() % 2 == 0, "EXPLICIT_EMULATOR_SERIAL_REQUIRED")
        requireFixture(arguments.getString("avdName")?.matches(Regex("[A-Za-z0-9_.-]{1,80}")) == true,
            "EXPLICIT_AVD_NAME_REQUIRED")
    }

    private suspend fun awaitExecution(app: UnlockTimeRuntime, token: String): ExecutionRecord? =
        withTimeoutOrNull(115_000) {
            app.repository.states.first { state -> state.history.any { it.id == token && it.code != "IN_PROGRESS" } }
                .history.single { it.id == token }
        }

    private suspend fun cleanup(
        app: UnlockTimeRuntime,
        routines: RoutineSystem,
        automation: UiAutomation,
        routineId: Long?,
        credential: CharArray?,
        appCredentialChanged: Boolean,
        systemCredentialChanged: Boolean,
        screenSettingsChanged: Boolean,
        timeout: String?,
        enabledServices: String?,
        accessibilityEnabled: String?,
        keyguard: KeyguardManager,
    ): Boolean {
        val cleanupLease = AndroidDeviceAccess.coordinator.beginCredentialEdit() ?: return false
        return try {
        val inputIdle = DeviceInputFence.shared.awaitIdle()
        if (!inputIdle) return false
        val schedulesRestored = runCatching {
            if (routineId != null) {
                requireFixture(routines.edit { delete(routineId) }, "TEST_ROUTINE_CLEANUP_FAILED")
                routines.resync()
            }
            routines.store.ready && (routineId == null || routines.store.get(routineId) == null)
        }.getOrDefault(false)
        val credentialsRestored = runCatching {
            if (appCredentialChanged) app.executionMutex.withLock {
                app.scheduler.mutex.withLock { app.credentials.clear() }
            }
            !appCredentialChanged || app.credentials.status() == CredentialState.EMPTY
        }.getOrDefault(false)
        val deviceLockRestored = runCatching {
            if (systemCredentialChanged && keyguard.isDeviceSecure) {
                requireFixture(automation.changeSyntheticLock("clear", requireNotNull(credential)), "LOCK_CLEANUP_FAILED")
            }
            !keyguard.isDeviceSecure
        }.getOrDefault(false)
        val settingsRestored = runCatching {
            if (screenSettingsChanged) automation.writeSecureSetting("lock_screen_lock_after_timeout", timeout)
            automation.writeSecureSetting("enabled_accessibility_services", enabledServices)
            automation.writeSecureSetting("accessibility_enabled", accessibilityEnabled)
            automation.shell("input keyevent KEYCODE_WAKEUP")
            automation.readSecureSetting("enabled_accessibility_services").orEmpty() == enabledServices.orEmpty() &&
                automation.readSecureSetting("accessibility_enabled") == accessibilityEnabled &&
                (!screenSettingsChanged || automation.readSecureSetting("lock_screen_lock_after_timeout") == timeout)
        }.getOrDefault(false)
        // Keep the application's failed-attempt guard and its execution record for the tester.
        schedulesRestored && credentialsRestored && deviceLockRestored && settingsRestored
        } finally { AndroidDeviceAccess.coordinator.release(cleanupLease) }
    }

    private fun makeCredential(method: UnlockMethod): CharArray {
        val random = SecureRandom()
        return if (method == UnlockMethod.PIN) {
            CharArray(6) { ('0'.code + random.nextInt(10)).toChar() }
        } else {
            CharArray(10) { index ->
                when (index) {
                    0 -> ('A'.code + random.nextInt(26)).toChar()
                    9 -> ('0'.code + random.nextInt(10)).toChar()
                    else -> ('a'.code + random.nextInt(26)).toChar()
                }
            }
        }
    }

    private fun UiAutomation.changeSyntheticLock(command: String, value: CharArray): Boolean {
        val args = when (command) {
            "set-pin", "set-password" -> "$command --user 0 \"\$value\""
            "clear" -> "clear --user 0 --old \"\$value\""
            else -> fail("UNEXPECTED_LOCK_SETUP_COMMAND")
        }
        // Only pipe generated ASCII into the guest shell. The instrumentation invocation,
        // command string, result bundle and output all omit the temporary credential.
        val pipes = executeShellCommandRw("sh -s")
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                input.write("value='".toByteArray(Charsets.US_ASCII))
                value.forEach { input.write(it.code) }
                input.write("'\nif locksettings $args >/dev/null 2>&1; then echo OK; fi\n".toByteArray(Charsets.US_ASCII))
            }
            output.readText().trim() == "OK"
        }
    }

    private fun UiAutomation.shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).bufferedReader()
            .use { it.readText().trim() }

    private fun UiAutomation.readSecureSetting(key: String): String? =
        shell("settings get secure $key").takeUnless { it == "null" }

    private fun UiAutomation.writeSecureSetting(key: String, value: String?) {
        if (value.isNullOrEmpty()) shell("settings delete secure $key")
        else shell("settings put secure $key $value")
    }

    private suspend fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        } == true

    private enum class Scenario(val argument: String, val mode: ExecutionMode, val method: UnlockMethod?) {
        PIN("pin", ExecutionMode.FULL, UnlockMethod.PIN),
        PASSWORD("password", ExecutionMode.FULL, UnlockMethod.PASSWORD),
        WAKE("wake", ExecutionMode.WAKE_ONLY, null),
        SEQUENCE("sequence", ExecutionMode.FULL, null),
        AGENT("agent", ExecutionMode.WAKE_ONLY, null),
        INSPECT("inspect", ExecutionMode.INSPECT_GRID, UnlockMethod.PIN);

        companion object {
            fun parse(value: String?) = entries.singleOrNull { it.argument == value }
        }
    }

    private class FixtureAbort(val code: String) : RuntimeException()

    private fun fail(code: String): Nothing = throw FixtureAbort(code)

    private fun requireFixture(condition: Boolean, code: String) {
        if (!condition) fail(code)
    }

    private companion object {
        const val TARGET_PACKAGE = "org.localwake.app"
        const val TEST_PACKAGE = "org.localwake.app.test"
        const val OBSERVED_APP = "com.android.settings"
        val OUTCOME_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        val TRACE_STAGES = listOf(
            "execution.start mode=(FULL|WAKE_ONLY|INSPECT_GRID)",
            "wake.start interactive=(true|false) credentialRequired=(true|false) fullyUnlocked=(true|false)",
            "wake.observed=(true|false) interactive=(true|false) credentialRequired=(true|false)",
            "unlock.start credentialRequired=(true|false) fullyUnlocked=(true|false)",
            "surface.centerSwipe=[1-3]",
            "surface.revealSwipe",
            "surface.gesture=(COMPLETED|CANCELLED|REJECTED|TIMEOUT)",
            "surface.check=(GEOMETRY_OR_OCCLUSION|PREVIEW_CHANGED|NOT_STABLE)",
            "surface.changed=(DISPLAY_CHANGED|WINDOW_CHANGED|CONTROL_STRUCTURE_CHANGED|ROOT_IDENTITY_CHANGED|NODE_IDENTITY_OR_PROPERTIES_CHANGED|LIVE_WINDOW_CHANGED)",
            "password.textSet",
            "keyguard.fullyUnlocked",
            "execution.end code=[A-Z][A-Z0-9_]{0,63}",
        ).map { Regex("[0-9]{1,6}ms ${it.replace(".", "\\.")}") }
        val AUTH_CONTROL_IDS = setOf(
            "pinentry", "pin_entry", "keyguard_pin_view", "pin_container", "keyguard_pin_motion_layout",
            "passwordentry", "password_entry", "keyguard_password_view", "keyguard_password_motion_layout",
            "key_enter", "pin_confirm", "pin_enter", "password_confirm", "password_enter",
        )
        val AUTH_CONTROL_CLASSES = setOf(
            "edittext", "passwordtextview", "secpasswordtextview",
            "keyguardpinview", "seckeyguardpinview", "keyguardpasswordview", "seckeyguardpasswordview",
        )
        val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_.\\$]{0,159}")
    }
}
