package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.risk.RiskCategory
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RepeatTapSafetyTest {
    private val button = UiNode(1, listOf(0), "Button", "Practice", null, null, "pad",
        Bounds(20, 100, 200, 200), clickable = true)
    private val initial = ScreenSnapshot("com.example.testpad", 480, 800, listOf(button))
    private fun call(extra: String = "") = InputItem.FunctionCall("r", "repeat_tap",
        """{"stop_on_page_change":true,"mode":"point","x":55,"y":122,"count":23,"interval_ms":1$extra}""")

    private class Pad(val fake: FakeDeviceController, var tree: ScreenSnapshot) : DeviceController by fake {
        val groups = mutableListOf<Int>()
        var version = 5L
        var checks = 0
        var probes = 0
        var visible = true
        var afterGroup: () -> Unit = {}
        var partial: TapSequenceResult? = null
        override fun pageChangeVersion() = version
        override suspend fun freshSnapshot() = tree.also { checks++ }
        override suspend fun canCaptureScreen() = visible.also { probes++ }
        override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
            if (expectedVersion != null) assertEquals(version, expectedVersion)
            assertEquals(55 to 122, x to y)
            groups += count
            afterGroup()
            return partial ?: TapSequenceResult(count)
        }
    }
    private fun pad(tree: ScreenSnapshot = initial) = Pad(FakeDeviceController(listOf(tree)), tree)

    @Test fun fixedPointCompletesSixtyTapsDespiteNormalPageChangesWithoutAnotherModelCall() = runTest {
        val pad = pad()
        pad.afterGroup = {
            pad.version++
            pad.tree = initial.copy(roots = listOf(button.copy(text = "Updated page ${pad.version}")))
        }
        val executor = ToolExecutor(pad, FakeUserGate(), screenshots = false)
        val fixed = InputItem.FunctionCall("fixed", "repeat_tap",
            """{"mode":"point","x":55,"y":122,"count":60,"interval_ms":1000}""")
        val result = executor.execute(fixed, executor.observe().screen)
        assertEquals(List(6) { 10 }, pad.groups)
        assertEquals(RepeatTapReceipt(60, 60, "COMPLETED"), result.repeatReceipt)
        assertFalse(pad.fake.calls.any { it.startsWith("tap(") })
    }

    @Test fun fixedPointStillStopsIfTheAppChangesOrCapturePermissionIsLost() = runTest {
        for (secure in listOf(false, true)) {
            val pad = pad()
            pad.afterGroup = {
                if (secure) pad.visible = false else pad.tree = initial.copy(packageName = "other.app")
            }
            val executor = ToolExecutor(pad, FakeUserGate(), screenshots = false)
            val fixed = InputItem.FunctionCall("fixed", "repeat_tap",
                """{"mode":"point","x":55,"y":122,"count":60,"interval_ms":1000}""")
            val result = executor.execute(fixed, executor.observe().screen)
            assertEquals(listOf(10), pad.groups)
            assertEquals(10, result.repeatReceipt?.completed)
            assertNotEquals("COMPLETED", result.repeatReceipt?.reason)
        }
    }

    @Test fun longSeriesChecksAfterEveryChunkIncludingTheLast() = runTest {
        for ((args, expected) in listOf("" to listOf(10,10,3), ""","check_every":6""" to listOf(6,6,6,5))) {
            val pad = pad()
            val executor = ToolExecutor(pad, FakeUserGate(), screenshots = false)
            val seen = executor.observe().screen
            pad.probes = 0
            val result = executor.execute(call(args), seen)
            assertTrue(result.output, result.output.contains("completed=23 stop_reason=COMPLETED"))
            assertTrue(result.deviceTool)
            assertEquals(expected, pad.groups)
            assertEquals(expected.size + 1, pad.probes)
            assertEquals(expected.size + 1, pad.checks)
            assertFalse(pad.fake.calls.any { it.startsWith("tap(") })
        }
    }

    @Test fun changedLayoutAndSameAppSecureTransitionStopBeforeNextGroup() = runTest {
        for (protected in listOf(false,true)) {
            val pad = pad()
            val executor = ToolExecutor(pad, FakeUserGate(), screenshots = false)
            val seen = executor.observe().screen
            pad.afterGroup = {
                pad.tree = initial.copy(roots = listOf(button.copy(text = "Other control", bounds = Bounds(21,100,201,200))))
                pad.visible = !protected
            }
            val result = executor.execute(call(), seen)
            assertEquals(listOf(10), pad.groups)
            assertTrue(result.output, result.output.contains("completed=10"))
            assertTrue(result.output, result.output.contains(if (protected) "CAPTURE_FAILED" else "PAGE_CHANGED"))
            assertFalse(result.output.contains("Other control"))
            assertNull(result.screenshot)
            if (protected) assertTrue(requireNotNull(result.screen).captureBlocked)
        }
    }

    @Test fun cancellationCallbackIsNeverCountedAsACompletedTap() = runTest {
        val pad = pad().apply { partial = TapSequenceResult(3, "GESTURE_CANCELLED") }
        val executor = ToolExecutor(pad, FakeUserGate())
        val result = executor.execute(call(), executor.observe().screen)
        assertEquals(listOf(10), pad.groups)
        assertTrue(result.output, result.output.contains("completed=3 stop_reason=GESTURE_CANCELLED"))
    }

    @Test fun rejectedUndispatchedGestureCanContinueOnlyTheRemainderOnTheSamePage() = runTest {
        for (completed in listOf(0, 3)) {
            val pad = pad()
            pad.afterGroup = {
                pad.partial = if (pad.groups.size == 1) TapSequenceResult(completed, "DISPATCH_REJECTED") else null
            }
            val executor = ToolExecutor(pad, FakeUserGate())
            val seen = executor.observe().screen
            val result = executor.execute(call(), seen)
            assertTrue(result.output, "requested=23 completed=23 stop_reason=COMPLETED" in result.output)
            assertEquals(23 - completed, pad.groups.drop(1).sum())
        }
    }

    @Test fun repeatedlyRejectedSubmissionAndUncertainGestureOutcomesCannotLoop() = runTest {
        for (reason in listOf("DISPATCH_REJECTED", "DISPATCH_TIMEOUT", "GESTURE_CANCELLED", "PAGE_CHANGED")) {
            val pad = pad()
            pad.afterGroup = { pad.partial = TapSequenceResult(if (pad.groups.size == 1) 2 else 0, reason) }
            val executor = ToolExecutor(pad, FakeUserGate())
            val result = executor.execute(call(), executor.observe().screen)
            assertEquals(if (reason == "DISPATCH_REJECTED") 2 else 1, pad.groups.size)
            assertTrue(result.output, "completed=2 stop_reason=$reason" in result.output)
        }
    }

    @Test fun changedPageAtSameCoordinatesOrTruncatedTreeNeverStartsAGroup() = runTest {
        for (newTree in listOf(initial.copy(packageName = "other.app"), initial.copy(truncated = true),
            initial.copy(roots = listOf(button.copy(windowId = 2))), initial.copy(roots = emptyList()))) {
            val pad = pad()
            val executor = ToolExecutor(pad, FakeUserGate())
            val seen = executor.observe().screen
            pad.tree = newTree
            val result = executor.execute(call(), seen)
            assertTrue(result.output, result.output.contains("completed=0 stop_reason=PAGE_CHANGED"))
            assertTrue(pad.groups.isEmpty())
        }
    }

    @Test fun coordinatesBehindTheKeyboardOrASystemWindowCannotStartATap() {
        val obscured = initial.copy(roots = listOf(button.copy(uncovered = Bounds(100, 100, 200, 200))),
            keyboardVisible = true)
        assertFalse(RepeatTapPage(obscured, 55, 122).matches(obscured))
        assertTrue(RepeatTapPage(obscured, 110, 122).matches(obscured))
        val covered = initial.copy(roots = listOf(button, button.copy(windowId = 9)), systemWindowIds = setOf(9))
        assertFalse(RepeatTapPage(covered, 55, 122).matches(covered))
    }

    @Test fun bothVisibleRiskAndModelDeclaredRiskUseTheExistingConfirmation() = runTest {
        for ((label, extra) in listOf("Pay now" to "", "Continue" to ""","risk":"money","effect":"Buy tickets" """)) {
            val pad = pad(initial.copy(roots = listOf(button.copy(text = label))))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(pad, gate, inlineRisk = true)
            val result = executor.execute(call(extra), executor.observe().screen)
            assertEquals(RiskCategory.MONEY, gate.confirms.single().first)
            assertTrue(gate.confirms.single().second.contains("23"))
            assertTrue(result.output, result.output.contains("completed=0 stop_reason=CONFIRMATION_DECLINED"))
            assertTrue(pad.groups.isEmpty())
        }
    }

    @Test fun batchAndUnseenCoordinatesCannotStartASequence() = runTest {
        val pad = pad()
        val executor = ToolExecutor(pad, FakeUserGate())
        val first = executor.execute(call(), null)
        assertTrue(first.output, first.output.contains("not seen"))
        val nested = InputItem.FunctionCall("b", "batch",
            """{"actions":[{"tool":"repeat_tap","args":{"stop_on_page_change":true,"mode":"point","x":55,"y":122}}]}""")
        assertTrue(executor.execute(nested, first.screen).output.contains("not allowed in batch"))
        assertTrue(pad.groups.isEmpty())
    }
}
