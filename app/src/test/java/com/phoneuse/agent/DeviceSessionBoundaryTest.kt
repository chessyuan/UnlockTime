package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelClient
import com.phoneuse.model.ModelException
import com.phoneuse.model.ModelTurn
import com.phoneuse.model.ToolSpec
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.ChatMessage
import com.phoneuse.chat.HistoryBuilder
import com.phoneuse.chat.MessageRole
import com.phoneuse.files.AttachmentStore
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class DeviceSessionBoundaryTest {
    @Test fun aRevokedScreenSessionDoesNotRetryItsModelRequest() = runTest {
        var valid = true
        var requests = 0
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
                requests++
                valid = false // The phone locks while the first request is being handled.
                throw ModelException(503, "temporarily unavailable")
            }
        }
        val loop = AgentLoop(model, ToolExecutor(FakeDeviceController(listOf(screen)), FakeUserGate()), SteeringQueue(),
            validateOutbound = { if (!valid) throw CancellationException("revoked") })
        try {
            loop.run(emptyList(), "read this screen", observeFirst = true).toList()
            fail("revocation became a model retry")
        } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    @Test fun helperDoesNotSendAScreenWhoseSessionEndedAfterCapture() = runTest {
        var valid = true
        var requests = 0
        val device = FakeDeviceController(listOf(screen)).apply {
            snapshotHook = { valid = false; it }
        }
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
                requests++
                return ModelTurn("unexpected", emptyList(), null)
            }
        }
        val helper = Helper(model, ToolExecutor(device, FakeUserGate())) {
            if (!valid) throw CancellationException("revoked")
        }
        try {
            helper.run("read", null, 1, onAction = { _, _ -> }, onUsage = {})
            fail("stale screen reached the helper")
        } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }

    @Test fun approvedCardCannotResumeAnExpiredSession() = runTest {
        var valid = true
        val gate = ValidatedUserGate(object : UserGate {
            override suspend fun confirm(category: RiskCategory, summary: String): Boolean { valid = false; return true }
            override suspend fun ask(question: String, options: List<String>) = "yes"
            override suspend fun handToUser(reason: String) = Unit
        }) { if (!valid) throw CancellationException("revoked") }
        try {
            gate.confirm(RiskCategory.MONEY, "synthetic request")
            fail("old approval revived the session")
        } catch (_: CancellationException) { }
    }

    @Test fun manualReturnCannotTriggerFreshAuthenticationInTheOldSession() = runTest {
        var checks = 0
        val gate = ValidatedUserGate(FakeUserGate()) {
            checks++
            throw CancellationException("relocked")
        }
        try {
            gate.handToUser("finish manually")
            fail("hand-off bypassed validation")
        } catch (_: CancellationException) { }
        assertEquals(1, checks)
    }

    @Test fun validAnswersKeepTheirOriginalRiskGateSemantics() = runTest {
        val inner = FakeUserGate().apply { confirmResult = false }
        var checks = 0
        val gate = ValidatedUserGate(inner) { checks++ }
        assertTrue(!gate.confirm(RiskCategory.MONEY, "purchase"))
        assertEquals(1, inner.confirms.size)
        assertEquals(1, checks)
    }

    @Test fun historicalScreensDoNotTurnANewTextQuestionIntoDeviceUse() = runTest {
        val loaded = mutableListOf<String?>()
        val history = HistoryBuilder.build(listOf(ChatMessage(
            id = 1, conversationId = 1, role = MessageRole.USER, text = "earlier images", steps = emptyList(), createdAt = 1,
            attachments = listOf(Attachment.image("screen-synthetic-secret.jpg"), Attachment.image("ordinary-photo.jpg")),
        )), loadImage = AttachmentStore.historicalImageLoader { attachment ->
            loaded += attachment.file
            InputItem.UserImage("ordinary-photo-content", "image/jpeg")
        })
        val device = FakeDeviceController(listOf(screen))
        val model = object : ModelClient {
            override suspend fun next(instructions: String, input: List<InputItem>, tools: List<ToolSpec>, onTextDelta: (String) -> Unit): ModelTurn {
                assertFalse(input.toString().contains("synthetic-secret"))
                return ModelTurn("A plain text answer", emptyList(), null)
            }
        }
        val events = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue())
            .run(history, "What is two plus two?", observeFirst = false).toList()
        assertEquals(listOf("ordinary-photo.jpg"), loaded)
        assertTrue(device.calls.isEmpty())
        assertFalse(events.contains(AgentEvent.DeviceUse))
    }

    private val screen = ScreenSnapshot("synthetic.app", 1080, 2400, listOf(UiNode(
        windowId = 1, path = listOf(0), className = "TextView", text = "synthetic-screen-sentinel",
        contentDescription = null, hint = null, viewId = null, bounds = Bounds(0, 0, 500, 80),
    )))
}
