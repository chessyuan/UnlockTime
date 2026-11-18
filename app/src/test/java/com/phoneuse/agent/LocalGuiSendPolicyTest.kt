package com.phoneuse.agent

import com.phoneuse.app.Source
import com.phoneuse.model.InputItem
import com.phoneuse.model.ToolSpec
import com.phoneuse.risk.RiskCategory
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineAction
import com.phoneuse.routine.RoutineEvent
import com.phoneuse.routine.RoutineRun
import com.phoneuse.routine.Trigger
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** Local settings authorize GUI submission independently of model claims and screen-content taint. */
class LocalGuiSendPolicyTest {
    private val routine = Routine(id = 1, title = "Saved task", trigger = Trigger.Time(8, 0),
        action = RoutineAction.Ask("Complete the check-in", needsDevice = true))
    private val timed = RoutineRun.of(routine, RoutineEvent.TimeDue(routine.id))
    private val external = listOf(
        RoutineRun.of(routine, RoutineEvent.NotificationPosted("example.app", "title", "text", 1)),
        RoutineRun.of(routine, RoutineEvent.EventSoon(routine.id, "event", "place", 1)),
    )

    private fun screen(label: String = "보내기", editable: Boolean = false) = ScreenSnapshot(
        "com.app", 1080, 2400, listOf(UiNode(
            windowId = 0, path = listOf(0), className = "android.widget.Button", text = label,
            contentDescription = null, hint = null, viewId = null,
            bounds = Bounds(100, 100, 300, 160), clickable = true, editable = editable, password = false,
        )),
    )
    private fun tap() = InputItem.FunctionCall("tap", "tap", """{"index":1,"user_authorized":false}""")

    @Test fun onlyDirectRequestsAndNonThirdPartySavedRoutinesInheritTheLocalSetting() {
        assertTrue(Source.APP.allowsLocalGuiSend(null, suggested = false))
        assertTrue(Source.ASSIST.allowsLocalGuiSend(null, suggested = false))
        assertTrue(Source.ROUTINE.allowsLocalGuiSend(timed, suggested = false))
        assertFalse(timed.trustSend) // No per-routine recipient/message grant is needed.
        assertFalse(Source.ROUTINE.allowsLocalGuiSend(null, suggested = false))
        assertFalse(Source.CARD.allowsLocalGuiSend(null, suggested = false))
        external.forEach { assertFalse(Source.ROUTINE.allowsLocalGuiSend(it, suggested = false)) }
        Source.entries.forEach { assertFalse(it.allowsLocalGuiSend(timed, suggested = true)) }
    }

    @Test fun offAllowsTrustedRoutineSubmissionAfterScreenAndHistoryTaintWithoutModelAuthorization() = runTest {
        val device = FakeDeviceController(listOf(screen()))
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(device, gate, confirmSend = false,
            trustUserAuthorized = Source.ROUTINE.trustsUserAuthorized(timed), historyTainted = true,
            localGuiSendSource = Source.ROUTINE.allowsLocalGuiSend(timed, suggested = false))
        val observed = executor.observe()
        assertTrue(executor.thirdPartySeen)
        assertTrue(executor.execute(tap(), observed.screen).output.startsWith("OK"))
        assertTrue(device.calls.any { it.startsWith("tap(") })
        assertTrue(gate.confirms.isEmpty())
        assertTrue(executor.thirdPartySeen) // Preauthorization never clears taint.
    }

    @Test fun onStillAsksForTrustedRoutineSubmissionAfterReadingTheScreen() = runTest {
        val device = FakeDeviceController(listOf(screen()))
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(device, gate, confirmSend = true, trustUserAuthorized = false,
            localGuiSendSource = Source.ROUTINE.allowsLocalGuiSend(timed, suggested = false))
        val observed = executor.observe()
        assertEquals("CANCELLED by user", executor.execute(tap(), observed.screen).output)
        assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
        assertTrue(device.calls.none { it.startsWith("tap(") })
    }

    @Test fun offAppliesToDirectAppAndAssistRequestsAfterScreenRead() = runTest {
        for (source in listOf(Source.APP, Source.ASSIST)) {
            val device = FakeDeviceController(listOf(screen()))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(device, gate, confirmSend = false,
                localGuiSendSource = source.allowsLocalGuiSend(null, suggested = false))
            assertTrue(executor.execute(tap(), executor.observe().screen).output.startsWith("OK"))
            assertTrue(gate.confirms.isEmpty())
        }
    }

    @Test fun offDoesNotAuthorizeExternalTriggersCardsOrSuggestedRemoteRequests() = runTest {
        val allowed = external.map { Source.ROUTINE.allowsLocalGuiSend(it, suggested = false) } +
            listOf(Source.CARD.allowsLocalGuiSend(null, suggested = false),
                Source.APP.allowsLocalGuiSend(null, suggested = true))
        for (localSource in allowed) {
            val device = FakeDeviceController(listOf(screen()))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(device, gate, confirmSend = false,
                trustUserAuthorized = false, localGuiSendSource = localSource)
            val call = InputItem.FunctionCall("tap", "tap", """{"index":1,"user_authorized":true}""")
            assertEquals("CANCELLED by user", executor.execute(call, executor.observe().screen).output)
            assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
            assertTrue(device.calls.none { it.startsWith("tap(") })
        }
    }

    @Test fun offAlsoAllowsImeSubmitForTrustedRoutine() = runTest {
        val device = FakeDeviceController(listOf(screen("입력", editable = true)))
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(device, gate, confirmSend = false, trustUserAuthorized = false,
            localGuiSendSource = Source.ROUTINE.allowsLocalGuiSend(timed, suggested = false))
        val call = InputItem.FunctionCall("type", "type_text", """{"index":1,"text":"check in","submit":true}""")
        assertTrue(executor.execute(call, executor.observe().screen).output.startsWith("OK"))
        assertTrue(device.calls.contains("setText(check in)"))
        assertTrue(device.calls.contains("imeEnter()"))
        assertTrue(gate.confirms.isEmpty())
    }

    @Test fun inlineSafetyCheckStillClassifiesBookingAndCheckInWhileLocalOffAllowsSubmission() = runTest {
        for (label in listOf("立即预约", "立即签到")) for (confirm in listOf(true, false)) {
            val device = FakeDeviceController(listOf(screen(label)))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(device, gate, confirmSend = confirm, trustUserAuthorized = false,
                localGuiSendSource = Source.ROUTINE.allowsLocalGuiSend(timed, suggested = false), inlineRisk = true)
            val call = InputItem.FunctionCall("submit", "tap", """{"index":1,"risk":"send","user_authorized":false}""")
            val result = executor.execute(call, executor.observe().screen)
            if (confirm) {
                assertEquals("CANCELLED by user", result.output)
                assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
                assertTrue(device.calls.none { it.startsWith("tap(") })
            } else {
                assertTrue(result.output, result.output.startsWith("OK"))
                assertTrue(device.calls.any { it.startsWith("tap(") })
                assertTrue(gate.confirms.isEmpty())
            }
        }
    }

    @Test fun localSendPreauthorizationDoesNotSkipMoneySecurityOrDelete() = runTest {
        for ((label, category) in listOf("결제하기" to RiskCategory.MONEY, "비밀번호 변경" to RiskCategory.SECURITY, "삭제" to RiskCategory.DELETE)) {
            val device = FakeDeviceController(listOf(screen(label)))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(device, gate, confirmSend = false, localGuiSendSource = true, inlineRisk = true)
            assertEquals("CANCELLED by user", executor.execute(tap(), executor.observe().screen).output)
            assertEquals(listOf(category), gate.confirms.map { it.first })
            assertTrue(device.calls.none { it.startsWith("tap(") })
        }
    }

    @Test fun helperDoesNotInheritLocalGuiSendPreauthorization() = runTest {
        val device = FakeDeviceController(listOf(screen()))
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(device, gate, confirmSend = false, localGuiSendSource = true)
        assertEquals("CANCELLED by user", executor.execute(tap(), executor.observe().screen, fromHelper = true).output)
        assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
        assertTrue(device.calls.none { it.startsWith("tap(") })
    }

    @Test fun connectorSendDoesNotInheritLocalGuiSendPreauthorizationEvenWithModelClaim() = runTest {
        var sent = false
        val connector = object : ConnectorTools {
            override val specs = listOf(ToolSpec("mcp__example__send", "Send", JsonObject(emptyMap())))
            override fun handles(name: String) = name == "mcp__example__send"
            override suspend fun call(name: String, args: JsonObject, gate: UserGate): ToolResult {
                sent = gate.confirm(RiskCategory.SEND, "Send remotely")
                return ToolResult(if (sent) "OK" else "CANCELLED", null, null, null)
            }
        }
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(FakeDeviceController(listOf(screen())), gate, connectors = connector,
            confirmSend = false, localGuiSendSource = true)
        executor.observe()
        executor.execute(InputItem.FunctionCall("remote", "mcp__example__send", """{"user_authorized":true}"""), null)
        assertFalse(sent)
        assertEquals(listOf(RiskCategory.SEND), gate.confirms.map { it.first })
    }
}
