package org.localwake.app.execution

data class AppLaunchConfiguration(val packageName: String, val delayMs: Long = 0) {
    init {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")))
        require(delayMs in 0..30_000)
    }
}
