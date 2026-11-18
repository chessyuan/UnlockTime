package com.phoneuse.routine

import kotlinx.serialization.json.*
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.execution.ExecutionRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

enum class OccurrenceStatus { PENDING, CLAIMED, FINISHED, REVOKED, MISSED_WINDOW }

/** CLAIMED is never replayed after process death; its IN_PROGRESS history remains truthful. */
data class RoutineOccurrence(
    val token: String, val routineId: Long, val revision: String, val dueAt: Long,
    val localDate: String, val zoneAtArm: String, val authorityEpoch: Long,
    val status: OccurrenceStatus = OccurrenceStatus.PENDING,
    val event: DeferredRun? = null,
) {
    fun json() = buildJsonObject {
        put("token", token); put("routine_id", routineId); put("revision", revision)
        put("due_at", dueAt); put("local_date", localDate); put("zone", zoneAtArm)
        put("epoch", authorityEpoch); put("status", status.name)
        event?.let { put("event", it.json()) }
    }
    companion object {
        fun read(o: JsonObject): RoutineOccurrence {
            fun str(k: String) = o.getValue(k).jsonPrimitive.content
            fun number(k: String) = o.getValue(k).jsonPrimitive.long
            val result = RoutineOccurrence(str("token"), number("routine_id"), str("revision"), number("due_at"),
                str("local_date"), str("zone"), number("epoch"), OccurrenceStatus.valueOf(str("status")),
                o["event"]?.let { requireNotNull(DeferredRun.read(it.jsonObject)) })
            require(result.token.isNotBlank() && result.revision.isNotBlank() && result.routineId > 0 && result.authorityEpoch > 0)
            require(result.event == null || (result.event.routineId == result.routineId && result.event.queuedAt == result.dueAt))
            java.time.LocalDate.parse(result.localDate); ZoneId.of(result.zoneAtArm)
            return result
        }
    }
}

/** Native local task projection. It is not a second persisted task authority. */
fun Routine.localTask(occurrence: RoutineOccurrence? = null): ScheduleModel? {
    val a = action as? RoutineAction.OpenAppsSequence ?: return null
    val time = trigger as? Trigger.Time ?: legacyClock ?: Trigger.Time(0, 0, DayOfWeek.entries.toSet())
    return ScheduleModel(id = legacyId ?: "routine-$id", name = title, hour = time.hour, minute = time.minute,
        weekdays = time.days.ifEmpty { DayOfWeek.entries.toSet() }.map { it.value }.toSet(), enabled = enabled,
        apps = a.apps, mode = a.mode, oneShotAt = (trigger as? Trigger.Once)?.atEpochMs,
        revision = definitionRevision, armedToken = occurrence?.token, armedAt = occurrence?.dueAt,
        armedDate = occurrence?.localDate, lastDate = lastClaimedLocalDate)
}

fun ScheduleModel.asRoutine(id: Long): Routine = Routine(id = id, title = name,
    trigger = oneShotAt?.let { Trigger.Once(it) } ?: Trigger.Time(hour, minute, weekdays.map(DayOfWeek::of).toSet()),
    conditions = Conditions(cooldownMin = 0), action = RoutineAction.OpenAppsSequence(apps, mode),
    enabled = enabled, definitionRevision = revision, legacyId = this.id, lastClaimedLocalDate = lastDate,
    legacyClock = Trigger.Time(hour, minute, weekdays.map(DayOfWeek::of).toSet()))

/** Set by the application. This boundary cannot grant permissions or change the global setting. */
object RoutineExecutionBridge {
    @Volatile var revokeRun: (Long) -> Unit = {}
    @Volatile var autoUnlockEnabled: () -> Boolean = { false }
    @Volatile var busy: () -> Boolean = { false }
    @Volatile var executeNative: (suspend (Long, ScheduleModel, String, () -> Boolean) -> ExecutionRecord)? = null
    /** Single-app and agent entry points share the same device arbiter in the host. */
    @Volatile var executeOpenApp: (suspend (String, () -> Boolean) -> Boolean)? = null
}
