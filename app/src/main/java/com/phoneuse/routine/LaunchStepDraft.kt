package com.phoneuse.routine

import org.localwake.app.execution.AppLaunchConfiguration
import java.math.BigDecimal
import java.util.UUID

/** Identity belongs to the row, never the package: repeated app launches and reordering are intentional. */
data class LaunchStepDraft(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val label: String = packageName,
    val delaySeconds: String = "0",
) {
    fun toConfiguration(): AppLaunchConfiguration {
        require(delaySeconds.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?")))
        return AppLaunchConfiguration(packageName, BigDecimal(delaySeconds).movePointRight(3).longValueExact())
    }
}
