package com.phoneuse.routine

import com.phoneuse.app.RunOutcome
import com.phoneuse.i18n.Lang
import com.phoneuse.unified.DeviceRevocationReason
import com.phoneuse.unified.DeviceSessionRevokedException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

class RoutineResultTest {
    @get:Rule val temp = TemporaryFolder()
    private var previousLanguage = "en"
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val zone = ZoneId.of("UTC")

    @Before fun english() { previousLanguage = Lang.tag; Lang.applyPreference("en") }
    @After fun restoreLanguage() {
        if (previousLanguage == "ko") Lang.korean = true else Lang.applyPreference(previousLanguage)
    }

    @Test fun cancellationAfterStepsDoesNotBecomeCompletedOrNoResult() {
        val result = RoutineResult.fromAgent(RunOutcome.FINISHED, cancelled = true, serviceLost = false, reply = "Stopped")
        assertEquals(RunOutcome.CANCELLED, result.outcome)
        assertEquals("AGENT_STOPPED", result.historyCode)
        assertEquals("Stopped", result.summary())
    }

    @Test fun serviceLossIsDistinctEvenWhenCancellationOrFinishedFlagAlsoExists() {
        val result = RoutineResult.fromAgent(RunOutcome.FINISHED, cancelled = true, serviceLost = true, reply = "Stopped")
        assertEquals(RunOutcome.SERVICE_LOST, result.outcome)
        assertEquals("AGENT_SERVICE_DISCONNECTED", result.historyCode)
        assertEquals("Accessibility service disconnected", result.summary())
    }

    @Test fun leaseServiceDisconnectBeforeWatcherIsStillClassifiedAsServiceLoss() {
        val disconnected = DeviceSessionRevokedException(DeviceRevocationReason.SERVICE_DISCONNECTED)
        val wrapped = java.util.concurrent.CancellationException("cancelled").apply { initCause(disconnected) }
        assertTrue(RoutineResult.serviceWasLost(false, disconnected))
        assertTrue(RoutineResult.serviceWasLost(false, wrapped))
        assertFalse(RoutineResult.serviceWasLost(false, DeviceSessionRevokedException(DeviceRevocationReason.SENSITIVE_SESSION)))
        assertFalse(RoutineResult.serviceWasLost(false, DeviceSessionRevokedException(DeviceRevocationReason.USER_CANCELLED)))
    }

    @Test fun modelFinishIsRecordedWithoutClaimingBusinessSuccess() {
        val result = RoutineResult.fromAgent(RunOutcome.FINISHED, false, false, "Already signed in; no new check-in")
        assertEquals("AGENT_COMPLETED", result.historyCode)
        assertEquals("Agent finished · Already signed in; no new check-in", result.summary())
        assertNotEquals("SUCCESS", result.historyCode)
    }

    @Test fun missingReplyIsStillAnExplicitFailedResult() {
        val result = RoutineResult.fromAgent(RunOutcome.FAILED, false, false, null)
        assertEquals("AGENT_FAILED", result.historyCode)
        assertEquals("Execution failed", result.summary())
    }

    @Test fun typedCallbackAndLegacyConstructorBothReceiveTheirSupportedResult() {
        var legacyCalls = 0
        var legacy: String? = "initial"
        val old = RoutineRun(1, "r", false, false, onDone = { legacyCalls++; legacy = it })
        old.complete(RoutineResult(RunOutcome.FINISHED, "answer"))
        assertEquals("answer", legacy)
        old.complete(RoutineResult(RunOutcome.CANCELLED, "Stopped"))
        assertNull(legacy)
        var typed: RoutineResult? = null
        val result = RoutineResult(RunOutcome.SERVICE_LOST, "service lost")
        old.copy(onResult = { typed = it }).complete(result)
        assertSame(result, typed)
        assertEquals(2, legacyCalls)
    }

    @Test fun everyTerminalResultPersistsConsistentSummaryHistoryAndConsumedOnceWithoutReplay() {
        val cases = listOf(
            Triple(RunOutcome.FINISHED, "AGENT_COMPLETED", "Agent finished · detail"),
            Triple(RunOutcome.CANCELLED, "AGENT_STOPPED", "Stopped"),
            Triple(RunOutcome.SERVICE_LOST, "AGENT_SERVICE_DISCONNECTED", "Accessibility service disconnected"),
            Triple(RunOutcome.FAILED, "AGENT_FAILED", "Execution failed · detail"),
        )
        for ((outcome, code, summary) in cases) {
            val file = temp.newFolder().resolve("routines.json")
            val store = RoutineStore(file)
            val routine = store.save(Routine(title = "Once", trigger = Trigger.Once(now.plusSeconds(30).toEpochMilli()),
                action = RoutineAction.Ask("Check the displayed state", needsDevice = true)))
            val pending = store.armTimes(now, zone, 2).single()
            val (claimedRoutine, claimed) = store.claim(pending.token, pending.dueAt, 2)!!
            store.setPatternBlocked(true)
            val run = RoutineRun.of(routine, RoutineEvent.TimeDue(routine.id)).copy(onResult = {
                assertTrue(store.recordAgentResult(claimedRoutine, claimed, it, pending.dueAt + 1))
            })
            run.complete(RoutineResult(outcome, "detail"))
            val reopened = RoutineStore(file)
            assertEquals(summary, reopened.get(routine.id)!!.lastResult)
            assertEquals(code, reopened.localSnapshot().history.single().code)
            assertEquals(pending.token, reopened.localSnapshot().history.single().id)
            assertEquals(OccurrenceStatus.FINISHED, reopened.occurrences().single().status)
            assertFalse(reopened.get(routine.id)!!.enabled)
            assertTrue(reopened.patternBlocked)
            assertNull(reopened.claim(pending.token, pending.dueAt + 2, 2))
            assertTrue(reopened.armTimes(Instant.ofEpochMilli(pending.dueAt + 2), zone, 2).isEmpty())
            run.complete(RoutineResult(RunOutcome.FINISHED, "late duplicate"))
            assertEquals(summary, store.get(routine.id)!!.lastResult)
            assertEquals(code, store.localSnapshot().history.single().code)
        }
    }

    @Test fun oldDefinitionCompletionCannotOverwriteNewDefinitionSummary() {
        val store = RoutineStore(temp.newFolder().resolve("routines.json"))
        val routine = store.save(Routine(title = "Before", trigger = Trigger.Once(now.plusSeconds(30).toEpochMilli()),
            action = RoutineAction.Ask("inspect", needsDevice = true)))
        val pending = store.armTimes(now, zone, 2).single()
        val (claimedRoutine, claimed) = store.claim(pending.token, pending.dueAt, 2)!!
        store.update(routine.id) { it.copy(title = "Edited", lastResult = "new definition") }
        assertTrue(store.recordAgentResult(claimedRoutine, claimed, RoutineResult(RunOutcome.CANCELLED, "Stopped"), pending.dueAt + 1))
        assertEquals("new definition", store.get(routine.id)!!.lastResult)
        assertEquals("AGENT_STOPPED", store.localSnapshot().history.single().code)
        assertEquals(OccurrenceStatus.REVOKED, store.occurrences().single().status)
        assertTrue(store.recordAgentResult(claimedRoutine, claimed, RoutineResult(RunOutcome.FINISHED, "late duplicate"), pending.dueAt + 2))
        assertEquals("AGENT_STOPPED", store.localSnapshot().history.single().code)
    }

    @Test fun failedResultCommitPublishesNeitherSummaryNorTerminalHistory() {
        val file = temp.newFolder().resolve("routines.json")
        val store = RoutineStore(file)
        store.save(Routine(title = "Once", trigger = Trigger.Once(now.plusSeconds(30).toEpochMilli()),
            action = RoutineAction.Ask("inspect", needsDevice = true)))
        val pending = store.armTimes(now, zone, 2).single()
        val (claimedRoutine, claimed) = store.claim(pending.token, pending.dueAt, 2)!!
        store.setPatternBlocked(true)
        val before = file.readBytes()
        // A real filesystem failure before rename, with the original authoritative file untouched.
        assertTrue(java.io.File(file.path + ".new").mkdir())
        assertFalse(store.recordAgentResult(claimedRoutine, claimed, RoutineResult(RunOutcome.CANCELLED, "Stopped"), pending.dueAt + 1))
        assertArrayEquals(before, file.readBytes())
        assertNull(store.get(claimedRoutine.id)!!.lastResult)
        assertEquals("IN_PROGRESS", store.localSnapshot().history.single().code)
        val reopened = RoutineStore(file)
        assertTrue(reopened.patternBlocked)
        assertFalse(reopened.get(claimedRoutine.id)!!.enabled)
        assertNull(reopened.claim(pending.token, pending.dueAt + 2, 2))
    }

    @Test fun chineseStatusAlwaysDistinguishesStoppedServiceFailureAndModelFinish() {
        Lang.applyPreference("zh-CN")
        assertEquals("已停止", RoutineResult(RunOutcome.CANCELLED, "Stopped").summary())
        assertEquals("无障碍服务已断开", RoutineResult(RunOutcome.SERVICE_LOST, null).summary())
        assertEquals("执行失败", RoutineResult(RunOutcome.FAILED, null).summary())
        assertEquals("执行结束 · 未进行新的签到", RoutineResult(RunOutcome.FINISHED, "未进行新的签到").summary())
    }
}
