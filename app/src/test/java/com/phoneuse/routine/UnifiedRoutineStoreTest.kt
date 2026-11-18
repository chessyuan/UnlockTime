package com.phoneuse.routine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.localwake.app.execution.AppLaunchConfiguration
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.scheduler.LocalState
import org.localwake.app.scheduler.ScheduleModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class UnifiedRoutineStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val zone = ZoneId.of("UTC")
    private fun native(at: Long = now.plusSeconds(30).toEpochMilli()) = Routine(title = "Native", trigger = Trigger.Once(at),
        conditions = Conditions(cooldownMin = 0), action = RoutineAction.OpenAppsSequence(listOf(AppLaunchConfiguration("com.example.one", 1), AppLaunchConfiguration("com.example.one", 29999))))
    private fun store() = RoutineStore(temp.newFolder().resolve("routines.json"))

    @Test fun nativeRoundtripPreservesDuplicateAppsAndBeforeLaunchMilliseconds() {
        val r = native()
        val parsed = RoutineJson.parse(RoutineJson.encode(r), false) as RoutineParse.Ok
        assertEquals(r, parsed.routine)
        assertTrue(r.needsDevice)
        val d = RoutineDraft.from(r)
        assertEquals(listOf("0.001", "29.999"), d.sequence.map { it.delaySeconds })
        assertEquals(2, d.sequence.map { it.id }.distinct().size)
        assertEquals(r, (d.toRoutine() as RoutineParse.Ok).routine)
        assertTrue(d.copy(sequence = d.sequence.map { it.copy(delaySeconds = "30.001") }).toRoutine() is RoutineParse.Error)
        assertTrue(d.copy(sequence = d.sequence.map { it.copy(delaySeconds = "0.0001") }).toRoutine() is RoutineParse.Error)
    }

    @Test fun modelCannotInjectRevisionClaimGuardOrGlobalSetting() {
        val json = RoutineJson.encode(native()).toMutableMap().apply {
            put("id", kotlinx.serialization.json.JsonPrimitive(99))
            put("definition_revision", kotlinx.serialization.json.JsonPrimitive("forged"))
            put("legacy_id", kotlinx.serialization.json.JsonPrimitive("legacy"))
            put("auto_unlock_enabled", kotlinx.serialization.json.JsonPrimitive(true))
            put("pattern_blocked", kotlinx.serialization.json.JsonPrimitive(false))
        }
        val r = (RoutineJson.parse(kotlinx.serialization.json.JsonObject(json), true) as RoutineParse.Ok).routine
        assertEquals(0L, r.id); assertNotEquals("forged", r.definitionRevision); assertNull(r.legacyId)
    }

    @Test fun concurrentClaimsHaveOneWinnerAndOneShotLeaseSurvivesConsumption() {
        val s = store(); val r = s.save(native())
        val o = s.armTimes(now, zone, 7).single()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val claims = pool.invokeAll((1..12).map { Callable { s.claim(o.token, o.dueAt, 7) } }).map { it.get() }
            assertEquals(1, claims.count { it != null })
            val claimed = claims.filterNotNull().single()
            assertFalse(s.get(r.id)!!.enabled)
            assertTrue(s.current(claimed.first, claimed.second, 7))
            assertFalse(s.current(claimed.first, claimed.second, 6))
            assertEquals("IN_PROGRESS", s.localSnapshot().history.single().code)
            val task = claimed.first.localTask(claimed.second)!!
            assertTrue(s.beginCredentialAttempt(task, 7)); assertFalse(s.beginCredentialAttempt(task, 7))
            s.update(r.id) { it.copy(title = "Edited") }
            assertFalse(s.stillCurrent(task, 7))
            assertTrue(s.patternBlocked)
        } finally { pool.shutdownNow() }
    }

    @Test fun claimHonorsExactDueAndTenMinuteBoundaryWithoutRenewingWindow() {
        for ((offset, allowed) in listOf(-1L to false, 0L to true, 600000L to true, 600001L to false)) {
            val s = store(); s.save(native()); val o = s.armTimes(now, zone, 2).single()
            assertNull(s.claim(o.token, o.dueAt, 1))
            assertEquals(allowed, s.claim(o.token, o.dueAt + offset, 2) != null)
            if (offset == 600001L) assertEquals("MISSED_WINDOW", s.localSnapshot().history.single().code)
        }
    }

    @Test fun restartNeverReplaysClaimedUnknownAndDoesNotClearGuard() {
        val file = temp.newFolder().resolve("r.json"); val s = RoutineStore(file)
        s.save(native()); val o = s.armTimes(now, zone, 2).single()
        val (r, claimed) = s.claim(o.token, o.dueAt, 2)!!
        assertTrue(s.beginCredentialAttempt(r.localTask(claimed), 2))
        val reopened = RoutineStore(file)
        assertTrue(reopened.ready); assertTrue(reopened.patternBlocked)
        assertEquals("IN_PROGRESS", reopened.localSnapshot().history.single().code)
        assertNull(reopened.claim(o.token, o.dueAt + 1, 2))
        assertTrue(reopened.armTimes(Instant.ofEpochMilli(o.dueAt + 1), zone, 2).isEmpty())
    }

    @Test fun staleDraftCannotResurrectOrRestoreEnabledStateAndRuntimeResultIsMerged() {
        val s = store(); val r = s.save(native()); val draft = RoutineDraft.from(r)
        s.update(r.id) { it.copy(lastResult = "SUCCESS", lastRunAt = 42) }
        assertTrue(s.userEdit { save((draft.copy(title = "Renamed").toRoutine() as RoutineParse.Ok).routine) })
        assertEquals("SUCCESS", s.get(r.id)!!.lastResult)
        val stale = s.get(r.id)!!
        s.update(r.id) { it.copy(enabled = false) }
        assertFalse(s.userEdit { save(stale.copy(title = "old")) }); assertFalse(s.get(r.id)!!.enabled)
        s.delete(r.id)
        assertFalse(s.userEdit { save(stale) }); assertTrue(s.all().isEmpty())
    }

    @Test fun batchUserEditIsAllOrNothing() {
        val s = store(); val r = s.save(native())
        assertFalse(s.userEdit { update(r.id) { it.copy(title = "temporary") }; save(r.copy(id = 999)) })
        assertEquals("Native", s.get(r.id)!!.title)
    }

    @Test fun draftOpenedBeforeOneShotClaimCannotReenableConsumedTask() {
        val s = store(); val r = s.save(native()); val draft = RoutineDraft.from(r)
        val o = s.armTimes(now, zone, 2).single(); assertNotNull(s.claim(o.token, o.dueAt, 2))
        assertTrue(s.userEdit { save((draft.copy(title = "Reviewed").toRoutine() as RoutineParse.Ok).routine) })
        assertFalse(s.get(r.id)!!.enabled)
        assertEquals(o.localDate, s.get(r.id)!!.lastClaimedLocalDate)
    }

    @Test fun corruptedUnknownVersionMissingGuardAndDuplicateIdsFailClosedPreservingBytes() {
        val goodFile = temp.newFolder().resolve("r.json"); val s = RoutineStore(goodFile); s.save(native())
        val good = goodFile.readText()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(good) as kotlinx.serialization.json.JsonObject
        val badStates = listOf("{broken", good.replace("\"version\":2", "\"version\":999"),
            kotlinx.serialization.json.JsonObject(obj - "pattern_blocked").toString(),
            kotlinx.serialization.json.JsonObject(obj + ("routines" to kotlinx.serialization.json.JsonArray((obj["routines"] as kotlinx.serialization.json.JsonArray).let { it + it }))).toString())
        for (bad in badStates) {
            val file = temp.newFolder().resolve("r.json"); file.writeText(bad)
            val broken = RoutineStore(file)
            assertFalse(broken.ready); assertTrue(broken.patternBlocked)
            assertFalse(broken.userEdit { save(native()) }); assertEquals(bad, file.readText())
        }
    }

    @Test fun migrationPreservesThirtyTwoTasksEveryModeHistoryAndArmedIdentity() {
        val tasks = (0 until 32).map { i -> ScheduleModel(id = "old-$i", name = "n".repeat(80), hour = i % 24, minute = i % 60,
            weekdays = setOf(i % 7 + 1), mode = ExecutionMode.entries[i % 3], enabled = i % 4 != 0,
            apps = listOf(AppLaunchConfiguration("com.example.same", 1), AppLaunchConfiguration("com.example.same", 29999)),
            oneShotAt = if (i % 2 == 0) now.plusSeconds(60).toEpochMilli() else null,
            armedToken = if (i == 1) "keep-token" else null, armedAt = if (i == 1) now.plusSeconds(1).toEpochMilli() else null,
            armedDate = if (i == 1) "2026-11-15" else null, lastDate = if (i == 2) "2026-11-14" else null) }
        val state = LocalState(tasks, (0 until 100).map { ExecutionRecord("run-$it", "same", it.toLong(), if (it == 0) "IN_PROGRESS" else "SUCCESS", trace = listOf("SAFE_CODE")) }, true)
        val target = store(); val ids = target.importLegacy(state, "migration", 2, zone)
        RoutineMigrationVerifier.compare(state, target, ids, now, zone)
        assertEquals(ids, target.importLegacy(state, "migration", 2, zone)); assertEquals(32, target.all().size)
        assertEquals("keep-token", target.pending(2).single().token)
        assertTrue(target.verifyReopened())
    }

    @Test fun onceAbsoluteAndWeeklyDstLastDateBarriersMatchLegacyPlanner() {
        val zones = listOf("America/New_York", "Australia/Lord_Howe", "Pacific/Auckland").map(ZoneId::of)
        for (z in zones) for (instant in listOf("2026-03-08T05:00:00Z", "2026-11-01T04:00:00Z", "2026-10-03T12:00:00Z")) {
            val old = ScheduleModel(id = "dst", hour = 2, minute = 30)
            assertEquals(old.nextExecution(Instant.parse(instant), z), NextRun.compute(old.asRoutine(1), Instant.parse(instant).atZone(z))?.toInstant())
        }
        val old = ScheduleModel(id = "rollback", hour = 7, lastDate = "2027-01-01")
        assertEquals(old.nextExecution(now, zone), NextRun.compute(old.asRoutine(1), now.atZone(zone))?.toInstant())
        val once = native()
        for (z in zones) assertEquals((once.trigger as Trigger.Once).atEpochMs, NextRun.compute(once, now.atZone(z))!!.toInstant().toEpochMilli())
    }

    @Test fun pendingNotificationPreservesTaintExpiryAndRevisionAcrossReopen() {
        val file = temp.newFolder().resolve("r.json"); val s = RoutineStore(file)
        val r = s.save(Routine(title = "Notice", trigger = Trigger.Notification(keyword = "parcel"), action = RoutineAction.Ask("summarize")))
        val event = RoutineEvent.NotificationPosted("com.example.app", "Parcel", "third party", now.toEpochMilli())
        val o = s.enqueue(r, event, now.toEpochMilli(), 2, zone)!!
        val reopened = RoutineStore(file); val restored = reopened.pending(2).single()
        assertEquals(o, restored)
        assertTrue(RoutineRun.of(r, restored.event!!.event).thirdParty)
        reopened.update(r.id) { it.copy(action = RoutineAction.Notify("changed")) }
        assertNull(reopened.claim(o.token, o.dueAt + 1, 2))
    }

    @Test fun globalUnlockPolicyKeepsDiagnosticsNonAuthenticatingAndDisabledManualBlocked() {
        val r = native().copy(id = 1)
        val event = RoutineEvent.TimeDue(1)
        val at = now.atZone(zone)
        assertEquals(Decision.NOTIFY_LOCKED, RoutineEngine.shouldRun(r, event, at, EngineState(locked = true, autoUnlockEnabled = false)))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(r, event, at, EngineState(locked = true, autoUnlockEnabled = true)))
        for (mode in listOf(ExecutionMode.WAKE_ONLY, ExecutionMode.INSPECT_GRID)) {
            assertEquals(Decision.RUN, RoutineEngine.shouldRun(r.copy(action = RoutineAction.OpenAppsSequence(emptyList(), mode)), event, at,
                EngineState(locked = true, autoUnlockEnabled = false)))
        }
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(r.copy(enabled = false), RoutineEvent.Manual, at, EngineState()))
    }
}
