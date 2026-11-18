package org.localwake.app.execution

data class AppLaunchResult(val packageName: String, val code: String)
data class ExecutionResult(val code: String, val apps: List<AppLaunchResult> = emptyList()) {
    val successful: Boolean get() = code in setOf("SUCCESS", "WAKE_CONFIRMED", "GRID_DYNAMIC_FOUND", "GRID_CALIBRATED_FOUND")
}
data class ExecutionRecord(val id: String, val name: String, val at: Long, val code: String,
                           val apps: List<AppLaunchResult> = emptyList(), val trace: List<String> = emptyList())
