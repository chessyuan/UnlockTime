package com.phoneuse.routine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.localwake.app.execution.ExecutionRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineTimeEditTest {
    @get:Rule val temp = TemporaryFolder()
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 11, 16)
    private fun at(hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone)

    private inner class Fixture {
        var now = at(1, 0).toInstant().toEpochMilli()
        val file = temp.newFolder().resolve("routines.json")
        val store = RoutineStore(file) { now }
        val original = store.save(Routine(title = "Existing", trigger = Trigger.Time(6, 29),
            conditions = Conditions(cooldownMin = 60), action = RoutineAction.Ask("unchanged prompt", needsDevice = true)))
        val originalToken = store.armTimes(at(1, 0).toInstant(), zone, 2).single().token
        lateinit var claimed: RoutineOccurrence
        fun claimTemporary() {
            assertTrue(store.userEdit { save(get(original.id)!!.copy(trigger = Trigger.Time(1, 50))) })
            val pending = store.armTimes(at(1, 0).toInstant(), zone, 2).single()
            now = at(1, 50).toInstant().toEpochMilli()
            claimed = store.claim(pending.token, now, 2)!!.second
            store.update(original.id) { it.copy(lastRunAt = now, cooldownFrom = now) }
            store.record(ExecutionRecord(claimed.token, "Existing", now + 1, "AGENT_STOPPED"))
            // The scheduler normally has tomorrow's occurrence armed before the editor is opened again.
            store.armTimes(at(1, 51).toInstant(), zone, 2)
            now = at(1, 55).toInstant().toEpochMilli()
        }
        fun current() = store.get(original.id)!!
    }

    @Test fun restoringFutureTimeKeepsSameRoutineAndArmsFreshTodayTokenWithoutReplayingOldOnes() {
        val f = Fixture(); f.claimTemporary()
        f.store.setPatternBlocked(true)
        val before = f.current()
        val history = f.store.localSnapshot().history
        assertTrue(f.store.userEdit { save(f.current().copy(trigger = Trigger.Time(6, 29))) })
        val restored = f.current()
        assertEquals(f.original.id, restored.id)
        assertEquals(f.original.action, restored.action)
        assertNotEquals(before.definitionRevision, restored.definitionRevision)
        assertNull(restored.lastClaimedLocalDate)
        assertEquals(before.lastRunAt, restored.lastRunAt)
        assertEquals(before.cooldownFrom, restored.cooldownFrom)
        assertEquals(history, f.store.localSnapshot().history)
        assertTrue(f.store.patternBlocked)
        val pending = f.store.armTimes(at(1, 55).toInstant(), zone, 2).single()
        assertEquals(at(6, 29).toInstant().toEpochMilli(), pending.dueAt)
        assertNotEquals(f.originalToken, pending.token)
        assertNotEquals(f.claimed.token, pending.token)
        assertNull(f.store.claim(f.originalToken, pending.dueAt, 2))
        assertNull(f.store.claim(f.claimed.token, pending.dueAt, 2))
        val reopened = RoutineStore(f.file) { f.now }
        assertEquals(pending, reopened.pending(2).single())
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(restored, RoutineEvent.TimeDue(restored.id), at(6, 29), EngineState()))
        assertNotNull(reopened.claim(pending.token, pending.dueAt, 2))
        assertNull(reopened.claim(pending.token, pending.dueAt + 1, 2))
        assertEquals(date.toString(), reopened.get(restored.id)!!.lastClaimedLocalDate)
    }

    @Test fun directSaveAndRuntimeUpdateCannotClearTheDailyBarrier() {
        for (runtimeUpdate in listOf(false, true)) {
            val f = Fixture(); f.claimTemporary()
            if (runtimeUpdate) assertTrue(f.store.userEdit { update(f.original.id) { it.copy(trigger = Trigger.Time(6, 29)) } })
            else f.store.save(f.current().copy(trigger = Trigger.Time(6, 29)))
            assertEquals(date.toString(), f.current().lastClaimedLocalDate)
            assertEquals(date.plusDays(1), NextRun.compute(f.current(), at(1, 55))!!.toLocalDate())
        }
    }

    @Test fun titleOnlySaveAndDisableEnableRetainTheBarrier() {
        val f = Fixture(); f.claimTemporary()
        assertTrue(f.store.userEdit { save(f.current().copy(title = "Renamed")) })
        assertEquals(date.toString(), f.current().lastClaimedLocalDate)
        assertTrue(f.store.userEdit { update(f.original.id) { it.copy(enabled = false) } })
        assertTrue(f.store.userEdit { update(f.original.id) { it.copy(enabled = true) } })
        assertEquals(date.toString(), f.current().lastClaimedLocalDate)
    }

    @Test fun pastOrExactlyCurrentTargetNeverRearmsToday() {
        for (minute in listOf(49, 55)) {
            val f = Fixture(); f.claimTemporary()
            assertTrue(f.store.userEdit { save(f.current().copy(trigger = Trigger.Time(1, minute))) })
            assertEquals(date.toString(), f.current().lastClaimedLocalDate)
        }
    }

    @Test fun staleRevisionCannotRearmOrChangeSavedDefinition() {
        val f = Fixture(); f.claimTemporary()
        val stale = f.current()
        assertTrue(f.store.userEdit { save(stale.copy(title = "newer")) })
        val before = f.file.readBytes()
        assertFalse(f.store.userEdit { save(stale.copy(trigger = Trigger.Time(6, 29))) })
        assertArrayEquals(before, f.file.readBytes())
        assertEquals(date.toString(), f.current().lastClaimedLocalDate)
    }

    @Test fun failedBatchRollsBackRearmAndDoesNotAuthorizeALaterDirectSave() {
        val f = Fixture(); f.claimTemporary()
        val before = f.current()
        val bytes = f.file.readBytes()
        assertFalse(f.store.userEdit {
            save(before.copy(trigger = Trigger.Time(6, 29)))
            save(before.copy(id = 999))
        })
        assertArrayEquals(bytes, f.file.readBytes())
        assertEquals(date.toString(), f.current().lastClaimedLocalDate)
        f.store.save(before.copy(trigger = Trigger.Time(6, 29)))
        assertEquals(date.toString(), f.current().lastClaimedLocalDate)
    }

    @Test fun knownClockRollbackAndFutureDateBarrierNeverClear() {
        for (kind in 0..2) {
            val f = Fixture(); f.claimTemporary()
            when (kind) {
                0 -> f.now = at(1, 49).toInstant().toEpochMilli()
                1 -> {
                    f.store.checkedAt = at(2, 0).toInstant().toEpochMilli()
                    f.store.checkedAt = f.now // A subsequent tick must not erase the rollback evidence.
                }
                else -> f.store.update(f.original.id) { it.copy(lastClaimedLocalDate = date.plusDays(1).toString()) }
            }
            val barrier = f.current().lastClaimedLocalDate
            assertTrue(f.store.userEdit { save(f.current().copy(trigger = Trigger.Time(6, 29))) })
            assertEquals(barrier, f.current().lastClaimedLocalDate)
        }
    }

    @Test fun consumedOnceIsNotRevivedWhenConvertedToTime() {
        val now = at(1, 55).toInstant().toEpochMilli()
        val store = RoutineStore(temp.newFolder().resolve("routines.json")) { now }
        val once = store.save(Routine(title = "once", trigger = Trigger.Once(at(1, 50).toInstant().toEpochMilli()),
            action = RoutineAction.Ask("inspect", needsDevice = true)))
        val pending = store.armTimes(at(1, 0).toInstant(), zone, 2).single()
        assertNotNull(store.claim(pending.token, pending.dueAt, 2))
        assertTrue(store.userEdit { save(get(once.id)!!.copy(trigger = Trigger.Time(6, 29), enabled = true)) })
        assertFalse(store.get(once.id)!!.enabled)
        assertEquals(date.toString(), store.get(once.id)!!.lastClaimedLocalDate)
        assertTrue(store.armTimes(at(1, 55).toInstant(), zone, 2).isEmpty())
    }

    @Test fun cooldownAndGuardAreNotBypassedByNewTime() {
        val f = Fixture(); f.claimTemporary()
        assertTrue(f.store.userEdit { save(f.current().copy(trigger = Trigger.Time(2, 0))) })
        assertNull(f.current().lastClaimedLocalDate)
        assertEquals(Decision.SKIP_COOLDOWN, RoutineEngine.shouldRun(f.current(), RoutineEvent.TimeDue(f.original.id), at(2, 0), EngineState()))
    }

    @Test fun missingClaimEvidenceAndChangedTimezoneDenyRearm() {
        val f = Fixture(); f.claimTemporary()
        val old = f.current(); val edited = old.copy(trigger = Trigger.Time(6, 29))
        assertFalse(RoutineTimeEditPolicy.canRearmToday(old, edited, at(1, 55), null, emptyList(), emptyList()))
        val otherZone = if (zone.id == "UTC") "Asia/Tokyo" else "UTC"
        val changedZone = f.store.occurrences().map { it.copy(zoneAtArm = otherZone) }
        assertFalse(RoutineTimeEditPolicy.canRearmToday(old, edited, at(1, 55), null, changedZone, f.store.localSnapshot().history))
    }

    @Test fun repeatDayEligibilityAndEquivalentEveryDaySpellingDoNotCreateExtraRun() {
        val f = Fixture(); f.claimTemporary()
        val old = f.current(); val history = f.store.localSnapshot().history
        fun allowed(candidate: Routine) = RoutineTimeEditPolicy.canRearmToday(old, candidate, at(1, 55), null, f.store.occurrences(), history)
        assertFalse(allowed(old.copy(trigger = Trigger.Time(6, 29, setOf(DayOfWeek.TUESDAY)))))
        assertFalse(allowed(old.copy(trigger = Trigger.Time(6, 29), conditions = old.conditions.copy(days = setOf(DayOfWeek.TUESDAY)))))
        val futureOld = old.copy(trigger = Trigger.Time(6, 29))
        assertFalse(RoutineTimeEditPolicy.canRearmToday(futureOld, futureOld.copy(trigger = Trigger.Time(6, 29, DayOfWeek.entries.toSet())),
            at(1, 55), null, f.store.occurrences(), history))
        assertTrue(RoutineTimeEditPolicy.canRearmToday(futureOld.copy(trigger = Trigger.Time(6, 29, setOf(DayOfWeek.TUESDAY))), futureOld,
            at(1, 55), null, f.store.occurrences(), history))
    }

    @Test fun dstGapAndRepeatedLocalTimeCannotClearClaimBarrier() {
        val ny = ZoneId.of("America/New_York")
        for ((now, target) in listOf(
            ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, ny) to Trigger.Time(2, 30),
            ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ny) to Trigger.Time(1, 30),
            ZonedDateTime.of(2026, 11, 1, 1, 15, 0, 0, ny).withLaterOffsetAtOverlap() to Trigger.Time(1, 30),
        )) {
            val old = Routine(id = 1, title = "dst", trigger = Trigger.Time(0, 0), action = RoutineAction.Ask("inspect"),
                lastClaimedLocalDate = now.toLocalDate().toString())
            val due = now.toLocalDate().atStartOfDay(ny).toInstant().toEpochMilli()
            val occurrence = RoutineOccurrence("claimed", 1, old.definitionRevision, due, old.lastClaimedLocalDate!!, ny.id, 2,
                status = OccurrenceStatus.FINISHED)
            assertFalse(RoutineTimeEditPolicy.canRearmToday(old, old.copy(trigger = target), now, null,
                listOf(occurrence), listOf(ExecutionRecord("claimed", "dst", due + 1, "AGENT_COMPLETED"))))
        }
    }
}
