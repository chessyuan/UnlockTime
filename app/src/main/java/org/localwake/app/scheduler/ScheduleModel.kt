package org.localwake.app.scheduler

import org.localwake.app.execution.AppLaunchConfiguration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class ExecutionMode { FULL, WAKE_ONLY, INSPECT_GRID }

data class ScheduleModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "本地定时",
    val hour: Int = 7,
    val minute: Int = 0,
    val weekdays: Set<Int> = (1..7).toSet(),
    val enabled: Boolean = true,
    val apps: List<AppLaunchConfiguration> = emptyList(),
    val mode: ExecutionMode = ExecutionMode.FULL,
    val oneShotAt: Long? = null,
    val revision: String = UUID.randomUUID().toString(),
    val armedToken: String? = null,
    val armedAt: Long? = null,
    val armedDate: String? = null,
    val lastDate: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 80)
        require(name.isNotBlank() && name.length <= 80)
        require(hour in 0..23 && minute in 0..59)
        require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 })
        require(apps.size <= 5 && apps.sumOf { it.delayMs } <= 30_000)
    }

    fun nextExecution(after: Instant, zone: ZoneId): Instant? {
        if (!enabled) return null
        oneShotAt?.let { return Instant.ofEpochMilli(it).takeIf { date -> date > after } }
        val today = after.atZone(zone).toLocalDate()
        // atZone resolves a gap forward and chooses the earlier offset in an overlap.
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek.value !in weekdays || (lastDate != null && date.toString() <= lastDate)) continue
            val candidate = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
            if (candidate > after) return candidate
        }
        // A backwards clock jump beyond a week must not silently strand the task.
        if (lastDate != null && today.toString() <= lastDate) {
            val resume = java.time.LocalDate.parse(lastDate).plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
            return nextExecution(resume, zone)
        }
        return null
    }
}

object AlarmEligibility {
    fun check(enabled: Boolean, expected: String?, received: String, due: Long, now: Long,
              date: String, lastDate: String?): String? = when {
        !enabled -> "DISABLED"
        expected == null || expected != received -> "STALE_ALARM"
        now < due -> "EARLY_ALARM"
        lastDate != null && date <= lastDate -> "DUPLICATE"
        now - due > 600_000 -> "MISSED_WINDOW"
        else -> null
    }
}
