package org.localwake.app.ui

import org.localwake.app.execution.AppLaunchConfiguration
import org.localwake.app.scheduler.ScheduleModel
import java.math.BigDecimal

internal const val MAX_SCHEDULE_APPS = 5
internal const val MAX_SCHEDULE_WAIT_MS = 30_000L

private val delaySecondsPattern = Regex("(?:[0-9]{1,2}(?:[.,][0-9]{0,3})?|[.,][0-9]{1,3})")

internal fun formatDelaySeconds(delayMs: Long): String =
    BigDecimal.valueOf(delayMs, 3).stripTrailingZeros().toPlainString()

internal fun parseDelayMs(seconds: String): Long? {
    val input = seconds.trim()
    if (!delaySecondsPattern.matches(input)) return null
    return input.replace(',', '.').toBigDecimal().movePointRight(3).toLong()
        .takeIf { it in 0..MAX_SCHEDULE_WAIT_MS }
}

internal data class DraftLaunchApp(
    val key: Int,
    val configuration: AppLaunchConfiguration,
    val delaySeconds: String = formatDelaySeconds(configuration.delayMs),
)

internal data class ScheduleDraft(
    val name: String,
    val hour: String,
    val minute: String,
    val weekdays: Set<Int>,
    val apps: List<DraftLaunchApp>,
) {
    val validName: Boolean get() = name.isNotBlank() && name.length <= 80
    val validHour: Boolean get() = hour.length in 1..2 && hour.all { it in '0'..'9' } && hour.toInt() in 0..23
    val validMinute: Boolean get() = minute.length in 1..2 && minute.all { it in '0'..'9' } && minute.toInt() in 0..59
    val validWeekdays: Boolean get() = weekdays.isNotEmpty() && weekdays.all { it in 1..7 }

    fun editedTask(original: ScheduleModel): ScheduleModel? {
        if (!validName || !validHour || !validMinute || !validWeekdays || apps.size > MAX_SCHEDULE_APPS) return null
        val configurations = apps.map { app ->
            val delayMs = parseDelayMs(app.delaySeconds) ?: return null
            app.configuration.copy(delayMs = delayMs)
        }
        if (configurations.sumOf { it.delayMs } > MAX_SCHEDULE_WAIT_MS) return null
        return original.copy(
            name = if (name == original.name) name else name.trim(),
            hour = hour.toInt(),
            minute = minute.toInt(),
            weekdays = weekdays,
            apps = configurations,
        )
    }

    companion object {
        fun from(task: ScheduleModel) = ScheduleDraft(
            name = task.name,
            hour = task.hour.toString().padStart(2, '0'),
            minute = task.minute.toString().padStart(2, '0'),
            weekdays = task.weekdays,
            apps = task.apps.mapIndexed { index, configuration -> DraftLaunchApp(index, configuration) },
        )
    }
}

internal fun mergeTaskEdits(original: ScheduleModel, current: ScheduleModel, edited: ScheduleModel): ScheduleModel {
    val timeChanged = edited.hour != original.hour || edited.minute != original.minute
    return current.copy(
        name = if (edited.name != original.name) edited.name else current.name,
        hour = if (timeChanged) edited.hour else current.hour,
        minute = if (timeChanged) edited.minute else current.minute,
        weekdays = if (edited.weekdays != original.weekdays) edited.weekdays else current.weekdays,
        apps = if (edited.apps != original.apps) edited.apps else current.apps,
    )
}
