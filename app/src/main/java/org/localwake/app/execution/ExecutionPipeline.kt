package org.localwake.app.execution

import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.scheduler.ScheduleModel

interface ExecutionDevice {
    suspend fun stillEnabled(): Boolean
    suspend fun wake(): Boolean
    fun unlocked(): Boolean
    suspend fun unlock(): String?
    suspend fun inspectGrid(): String
    fun isInstalled(packageName: String): Boolean
    suspend fun launchAndObserve(packageName: String): String?
    suspend fun pause(ms: Long)
}

class ExecutionPipeline(private val device: ExecutionDevice) {
    suspend fun run(task: ScheduleModel): ExecutionResult {
        if (!device.stillEnabled()) return ExecutionResult("TASK_CHANGED")
        if (!device.wake()) return ExecutionResult("WAKE_BLOCKED")
        if (task.mode == ExecutionMode.WAKE_ONLY) return ExecutionResult("WAKE_CONFIRMED")
        if (!device.stillEnabled()) return ExecutionResult("TASK_CHANGED")
        if (task.mode == ExecutionMode.INSPECT_GRID) return ExecutionResult(device.inspectGrid())
        if (!device.unlocked()) device.unlock()?.let { return ExecutionResult(it) }
        if (!device.unlocked()) return ExecutionResult("KEYGUARD_STILL_LOCKED")
        val results = mutableListOf<AppLaunchResult>()
        for (app in task.apps) {
            device.pause(app.delayMs)
            if (!device.stillEnabled()) return ExecutionResult("TASK_CHANGED", results)
            if (!device.unlocked()) return ExecutionResult("DEVICE_RELOCKED", results)
            val error = if (!device.isInstalled(app.packageName)) "APP_NOT_INSTALLED"
                        else device.launchAndObserve(app.packageName)
            results += AppLaunchResult(app.packageName, error ?: "FOREGROUND_CONFIRMED")
            if (error != null) return ExecutionResult(error, results)
        }
        return ExecutionResult("SUCCESS", results)
    }
}
