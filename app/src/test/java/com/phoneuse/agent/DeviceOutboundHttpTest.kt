package com.phoneuse.agent

import com.phoneuse.model.ModelAuth
import com.phoneuse.model.ModelOptions
import com.phoneuse.model.OpenAiResponsesClient
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Local HTTP only, synthetic screen markers, no account, device credential or external endpoint. */
class DeviceOutboundHttpTest {
    @Test fun validOrdinaryScreenActuallyReachesTheLocalModelServer() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(completed())
            val device = FakeDeviceController(listOf(screen("ordinary-screen-positive-control")))
            val loop = AgentLoop(client(server), ToolExecutor(device, FakeUserGate()), SteeringQueue())
            withTimeout(10_000) { loop.run(emptyList(), "Read the page", observeFirst = true).toList() }
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("/v1/responses", request!!.path)
            assertTrue(request.body.readUtf8().contains("ordinary-screen-positive-control"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun revokedCaptureNeverSendsItsSyntheticSensitiveMarkerOverHttp() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(completed()) // A bypass would complete, rather than hanging the test.
            var valid = true
            val device = FakeDeviceController(listOf(screen("synthetic-auth-content-must-not-leave"))).apply {
                snapshotHook = { valid = false; it }
            }
            val loop = AgentLoop(client(server), ToolExecutor(device, FakeUserGate()), SteeringQueue(),
                validateOutbound = { if (!valid) throw CancellationException("revoked") })
            val failure = runCatching {
                withTimeout(10_000) { loop.run(emptyList(), "Read the page", observeFirst = true).toList() }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private fun client(server: MockWebServer) = OpenAiResponsesClient(object : ModelAuth {
        override val baseUrl = server.url("/v1/").toString()
        override val unauthorizedMessage = "synthetic authentication failure"
        override suspend fun headers() = emptyMap<String, String>()
        override suspend fun onUnauthorized() = false
    }, ModelOptions("synthetic-test-model", fast = false, effort = "low"), OkHttpClient())

    private fun completed() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done.\"}]}]}}\n\n")

    private fun screen(marker: String) = ScreenSnapshot("synthetic.app", 1080, 2400, listOf(UiNode(
        windowId = 1, path = listOf(0), className = "TextView", text = marker,
        contentDescription = null, hint = null, viewId = null, bounds = Bounds(0, 0, 500, 80),
    )))
}
