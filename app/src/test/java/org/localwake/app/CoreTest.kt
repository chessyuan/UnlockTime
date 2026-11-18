package org.localwake.app

import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.security.PatternValidator
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.scheduler.AlarmEligibility
import org.localwake.app.execution.AppLaunchConfiguration
import java.time.Instant
import java.time.ZoneId

class CoreTest {
    @Test fun horizontalJumpInsertsUnvisitedMiddle() {
        assertArrayEquals(intArrayOf(1, 2, 3), PatternValidator.append(intArrayOf(1), 3))
    }
    @Test fun diagonalJumpInsertsCenter() {
        assertArrayEquals(intArrayOf(1, 5, 9), PatternValidator.append(intArrayOf(1), 9))
    }
    @Test fun visitedMiddleIsNotInsertedAgain() {
        assertArrayEquals(intArrayOf(2, 1, 3), PatternValidator.append(intArrayOf(2, 1), 3))
    }
    @Test fun duplicatePointDoesNotChangePattern() {
        assertArrayEquals(intArrayOf(1, 2), PatternValidator.append(intArrayOf(1, 2), 1))
    }
    @Test fun invalidOrNonCanonicalPatternsAreRejected() {
        listOf(intArrayOf(), intArrayOf(1, 2, 3), intArrayOf(1, 2, 3, 3),
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 3, 5, 8)).forEach { assertFalse(PatternValidator.valid(it)) }
        assertTrue(PatternValidator.valid(intArrayOf(1, 2, 3, 6)))
    }
    @Test fun dailyUsesLocalClockAndRollsPastExactInstant() {
        val s = ScheduleModel(id = "one", hour = 8, minute = 0)
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), s.nextExecution(Instant.parse("2026-11-15T00:00:00Z"), zone))
    }
    @Test fun selectedWeekdaySkipsOtherDays() {
        val s = ScheduleModel(id = "one", hour = 8, minute = 0, weekdays = setOf(1))
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), s.nextExecution(Instant.parse("2026-11-15T09:00:00Z"), ZoneId.of("UTC")))
    }
    @Test fun springGapShiftsForward() {
        val s = ScheduleModel(id = "one", hour = 2, minute = 30)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), s.nextExecution(Instant.parse("2026-03-08T00:00:00Z"), ZoneId.of("America/New_York")))
    }
    @Test fun autumnOverlapRunsOnlyEarlierOffset() {
        val s = ScheduleModel(id = "one", hour = 1, minute = 30)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), s.nextExecution(Instant.parse("2026-11-01T05:30:00Z"), ZoneId.of("America/New_York")))
    }
    @Test fun disabledScheduleHasNoNextOccurrence() {
        assertNull(ScheduleModel(id = "one", enabled = false).nextExecution(Instant.EPOCH, ZoneId.of("UTC")))
    }
    @Test fun staleOrRepeatedOrEarlyOccurrenceIsRejected() {
        assertEquals("STALE_ALARM", AlarmEligibility.check(true, "new", "old", 1000, 1000, "2026-11-15", null))
        assertEquals("DUPLICATE", AlarmEligibility.check(true, "t", "t", 1000, 1000, "2026-11-15", "2026-11-15"))
        assertEquals("DUPLICATE", AlarmEligibility.check(true, "t", "t", 1000, 1000, "2026-11-14", "2026-11-15"))
        assertEquals("EARLY_ALARM", AlarmEligibility.check(true, "t", "t", 10000, 1000, "2026-11-15", null))
        assertEquals("DISABLED", AlarmEligibility.check(false, "t", "t", 1000, 1000, "2026-11-15", null))
        assertNull(AlarmEligibility.check(true, "t", "t", 1000, 1000, "2026-11-15", null))
    }
    @Test fun obsoleteAlarmIsNotExecutedHoursLate() {
        assertEquals("MISSED_WINDOW", AlarmEligibility.check(true, "t", "t", 1000, 601001, "2026-11-15", null))
    }
    @Test fun invalidScheduleCannotBePersisted() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleModel(id = "x", hour = 24) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleModel(id = "x", weekdays = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { AppLaunchConfiguration("", 0) }
        assertThrows(IllegalArgumentException::class.java) { AppLaunchConfiguration("com.example.app", 30001) }
    }
}
