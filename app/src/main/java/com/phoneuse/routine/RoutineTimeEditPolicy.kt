package com.phoneuse.routine

import org.localwake.app.execution.ExecutionRecord
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/** An explicit future Time edit authorizes a new revision, never replay of an earlier occurrence. */
internal object RoutineTimeEditPolicy {
    fun canRearmToday(
        old: Routine,
        edited: Routine,
        now: ZonedDateTime,
        checkedAt: Long?,
        occurrences: List<RoutineOccurrence>,
        history: List<ExecutionRecord>,
    ): Boolean {
        if (!old.enabled || !edited.enabled || old.id != edited.id || old.definitionRevision != edited.definitionRevision) return false
        val before = old.trigger as? Trigger.Time ?: return false
        val after = edited.trigger as? Trigger.Time ?: return false
        fun days(time: Trigger.Time) = time.days.ifEmpty { DayOfWeek.entries.toSet() }
        if (before.hour == after.hour && before.minute == after.minute && days(before) == days(after)) return false
        val today = now.toLocalDate()
        if (old.lastClaimedLocalDate != today.toString()) return false
        if (today.dayOfWeek !in days(after) || edited.conditions.days?.let { today.dayOfWeek !in it } == true) return false

        val local = today.atTime(LocalTime.of(after.hour, after.minute))
        val offsets = now.zone.rules.getValidOffsets(local)
        // Never reinterpret a nonexistent local time, or select a second occurrence during a DST fold.
        if (offsets.size != 1) return false
        val nowMs = now.toInstant().toEpochMilli()
        if (!local.toInstant(offsets.single()).isAfter(now.toInstant())) return false
        if (listOfNotNull(checkedAt, old.lastRunAt, old.cooldownFrom).any { it > nowMs }) return false

        val records = history.associateBy { it.id }
        val claims = occurrences.filter {
            it.routineId == old.id && it.localDate == today.toString() &&
                it.status in setOf(OccurrenceStatus.CLAIMED, OccurrenceStatus.FINISHED, OccurrenceStatus.REVOKED) &&
                (it.event == null || it.event.event is RoutineEvent.TimeDue)
        }.mapNotNull { occurrence ->
            records[occurrence.token]?.takeIf { it.code != "MISSED_WINDOW" }?.let { occurrence to it }
        }
        // A legacy/imported date alone is not enough evidence to remove its replay protection.
        if (claims.isEmpty()) return false
        return claims.all { (occurrence, record) ->
            occurrence.zoneAtArm == now.zone.id && occurrence.dueAt <= nowMs && record.at <= nowMs
        }
    }
}
