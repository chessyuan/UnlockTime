package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelTurn
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Device tools report execution; they must not implement one library's booking workflow. */
class NormalTaskFlowTest {
    private val page = ScreenSnapshot("com.tencent.mm", 480, 800, listOf(
        UiNode(1, listOf(0), "TextView", "学习中", null, null, null, Bounds(40, 40, 440, 160)),
    ))
    private fun call(name: String, args: String = "{}") =
        ModelTurn(null, listOf(InputItem.FunctionCall(name, name, args)), null)

    @Test fun reservationWordsDoNotReplaceTheOrdinaryAgentResult() = runTest {
        val summary = "已查看指定记录；日期不清晰，预约状态待核实，本次点击0次。"
        val model = FakeModelClient(listOf(call("get_screen"), call("finish", """{"summary":"$summary","success":true}""")))
        val device = FakeDeviceController(listOf(page))
        val events = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue()).run(emptyList(),
            "打开微信预约「晨光楼7号」，未预约则连续点击5次，否则核对现有记录。", false).toList()
        val end = events.last() as AgentEvent.Finished
        assertEquals(summary, end.summary)
        assertTrue(end.success)
        assertEquals(2, model.calls)
        assertFalse(device.calls.any { it.startsWith("tap(") })
    }

    @Test fun anExplicitFailureIsStillShownAsFailure() = runTest {
        val model = FakeModelClient(listOf(call("finish", """{"summary":"应用无法打开","success":false}""")))
        val end = AgentLoop(model, ToolExecutor(FakeDeviceController(listOf(page)), FakeUserGate()), SteeringQueue())
            .run(emptyList(), "打开应用", false).toList().last() as AgentEvent.Finished
        assertFalse(end.success)
    }

    @Test fun aStoppedRepeatCallStillAllowsTheOriginalCleanupStep() {
        val route = RepeatTapRouting("打开练习页，连续点击「练习区」5次，最后点击「全部关闭」。")
        route.record(InputItem.FunctionCall("r", "repeat_tap", "{}"),
            ToolResult("repeat_tap requested=5 completed=2 stop_reason=PAGE_CHANGED", null, null, null,
                repeatReceipt = RepeatTapReceipt(5, 2, "PAGE_CHANGED")))
        assertNull(route.rejection(InputItem.FunctionCall("c", "tap", """{"target":"全部关闭"}"""), null))
        assertNotNull(route.rejection(InputItem.FunctionCall("r2", "repeat_tap", "{}"), null))
    }
}
