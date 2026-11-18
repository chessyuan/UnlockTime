package org.localwake.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.execution.*
import org.localwake.app.scheduler.*

class ExecutionPipelineTest {
    private class Device : ExecutionDevice {
        var locked = true
        var wakeWorks = true
        var unlockError: String? = null
        var launchError: String? = null
        var installed = true
        var active = true
        var relockDuringDelay = false
        var unlockClears = true
        var disableAfterWake = false
        var attempts = 0
        val opened = mutableListOf<String>()
        val waited = mutableListOf<Long>()
        override suspend fun stillEnabled() = active
        override suspend fun wake(): Boolean { if (disableAfterWake) active = false; return wakeWorks }
        override fun unlocked() = !locked
        override suspend fun unlock(): String? { attempts++; if (unlockError == null && unlockClears) locked = false; return unlockError }
        override suspend fun inspectGrid() = "GRID_DYNAMIC_FOUND"
        override fun isInstalled(packageName: String) = installed
        override suspend fun launchAndObserve(packageName: String): String? { opened += packageName; return launchError }
        override suspend fun pause(ms: Long) { waited += ms; if (relockDuringDelay) locked = true }
    }
    private val task = ScheduleModel(id = "t", apps = listOf(AppLaunchConfiguration("com.example.first", 100), AppLaunchConfiguration("com.example.second", 200)))
    @Test fun failedWakeNeverInputsPatternOrLaunches() = runTest {
        val d = Device().apply { wakeWorks = false }
        assertEquals("WAKE_BLOCKED", ExecutionPipeline(d).run(task).code)
        assertEquals(0, d.attempts); assertTrue(d.opened.isEmpty())
    }
    @Test fun failedUnlockIsAttemptedOnlyOnceAndStopsAllApps() = runTest {
        val d = Device().apply { unlockError = "AUTHENTICATION_REJECTED" }
        assertEquals("AUTHENTICATION_REJECTED", ExecutionPipeline(d).run(task).code)
        assertEquals(1, d.attempts); assertTrue(d.opened.isEmpty())
    }
    @Test fun actualUnlockPrecedesOrderedAppsAndDelays() = runTest {
        val d = Device()
        assertEquals("SUCCESS", ExecutionPipeline(d).run(task).code)
        assertEquals(listOf("com.example.first", "com.example.second"), d.opened)
        assertEquals(listOf(100L, 200L), d.waited)
        assertEquals(1, d.attempts)
    }
    @Test fun alreadyUnlockedDoesNotReadCredential() = runTest {
        val d = Device().apply { locked = false }
        assertEquals("SUCCESS", ExecutionPipeline(d).run(task).code); assertEquals(0, d.attempts)
    }
    @Test fun relockDuringDelayStopsBeforeLaunch() = runTest {
        val d = Device().apply { relockDuringDelay = true }
        assertEquals("DEVICE_RELOCKED", ExecutionPipeline(d).run(task).code); assertTrue(d.opened.isEmpty())
    }
    @Test fun unobservedLaunchIsFailureAndStopsSequence() = runTest {
        val d = Device().apply { launchError = "APP_LAUNCH_UNOBSERVED" }
        assertEquals("APP_LAUNCH_UNOBSERVED", ExecutionPipeline(d).run(task).code); assertEquals(1, d.opened.size)
    }
    @Test fun removedAppIsReportedWithoutLaunch() = runTest {
        val d = Device().apply { installed = false }
        assertEquals("APP_NOT_INSTALLED", ExecutionPipeline(d).run(task).code); assertTrue(d.opened.isEmpty())
    }
    @Test fun disabledTaskStopsBeforeWake() = runTest {
        val d = Device().apply { active = false }
        assertEquals("TASK_CHANGED", ExecutionPipeline(d).run(task).code); assertEquals(0, d.attempts)
    }
    @Test fun disableAfterWakeStopsBeforeCredentialInput() = runTest {
        val d = Device().apply { disableAfterWake = true }
        assertEquals("TASK_CHANGED", ExecutionPipeline(d).run(task).code); assertEquals(0, d.attempts)
    }
    @Test fun successfulGestureWithoutActualUnlockCannotLaunch() = runTest {
        val d = Device().apply { unlockClears = false }
        assertEquals("KEYGUARD_STILL_LOCKED", ExecutionPipeline(d).run(task).code); assertTrue(d.opened.isEmpty())
    }
    @Test fun diagnosticsNeverSubmitPattern() = runTest {
        for (mode in listOf(ExecutionMode.WAKE_ONLY, ExecutionMode.INSPECT_GRID)) {
            val d = Device()
            assertTrue(ExecutionPipeline(d).run(task.copy(mode = mode)).successful)
            assertEquals(0, d.attempts); assertTrue(d.opened.isEmpty())
        }
    }
}
