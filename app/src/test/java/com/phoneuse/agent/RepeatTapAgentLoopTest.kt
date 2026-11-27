package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.model.ModelTurn
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model replies are scripted; the tool list, loop, parser, risk gate and executor are production code. */
class RepeatTapAgentLoopTest {
    private fun button(name: String, left: Int) = UiNode(
        1, listOf(left), "Button", name, null, null, null, Bounds(left, 100, left + 80, 160), clickable = true,
    )

    private val screen = ScreenSnapshot("org.example.practice", 480, 800,
        listOf(button("菜单", 0), button("练习区", 100), button("关闭", 200)))

    private data class Group(val x: Int, val y: Int, val count: Int, val interval: Long, val version: Long?)

    private class TestDevice(
        val raw: FakeDeviceController,
        val outcome: ((Int) -> TapSequenceResult)? = null,
    ) : DeviceController by raw {
        val groups = mutableListOf<Group>()
        override fun pageChangeVersion() = 7L
        override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
            groups += Group(x, y, count, intervalMs, expectedVersion)
            return outcome?.invoke(count) ?: TapSequenceResult(count)
        }
    }

    private var seq = 0
    private fun call(name: String, args: String = "{}") = InputItem.FunctionCall("r${seq++}", name, args)
    private fun turn(call: InputItem.FunctionCall) = ModelTurn(null, listOf(call), null)
    private fun finish() = turn(call("finish", """{"summary":"结束"}"""))
    private fun outputs(model: FakeModelClient) = model.inputs.last().filterIsInstance<InputItem.FunctionOutput>()
        .associate { it.callId to it.output }

    @Test fun namedChineseOrderDispatchesOneRealToolWithDefaultsWithOrWithoutAHelper() = runTest {
        for (useHelper in listOf(false, true)) {
            val device = TestDevice(FakeDeviceController(listOf(screen)))
            val inspect = call("get_screen")
            val repeat = call("repeat_tap", """{"mode":"point","x":140,"y":130}""")
            val model = FakeModelClient(listOf(turn(inspect), turn(repeat), finish()))
            val helper = if (useHelper) FakeModelClient(emptyList()) else null
            val loop = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue(), helperModel = helper)

            val events = loop.run(emptyList(), "请自动连点当前屏幕上的练习区", observeFirst = false).toList()
            assertTrue(model.toolNames.all { "repeat_tap" in it })
            assertEquals(useHelper, "delegate" in model.toolNames.first())
            assertTrue("repeat_tap" !in Helper.TOOLS)
            val observed = model.inputs[1].filterIsInstance<InputItem.FunctionOutput>().single().output
            assertTrue(observed, observed.lineSequence().any { "\"练习区\"" in it && "(140,130)" in it })
            assertEquals(listOf(Group(140, 130, 10, 1000, null)), device.groups)
            assertTrue(outputs(model).getValue(repeat.callId).startsWith("repeat_tap requested=10 completed=10 stop_reason=COMPLETED"))
            assertTrue(events.last() is AgentEvent.Finished)
            assertEquals(0, helper?.calls ?: 0)
        }
    }

    @Test fun rejectsManualBatchAndDelegateSubstitutesAndDoesNotReplayAPartialResult() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen))) { TapSequenceResult(2, "PAGE_CHANGED") }
        val tap = call("tap", """{"index":2}""")
        val batch = call("batch", """{"actions":[{"tool":"tap","args":{"index":2}},{"tool":"tap","args":{"index":2}}]}""")
        val delegate = call("delegate", """{"task":"Tap the button six times"}""")
        val repeat = call("repeat_tap", """{"mode":"rect","left":100,"top":100,"right":180,"bottom":160,"count":6,"interval_ms":125}""")
        val replay = call("repeat_tap", repeat.argumentsJson)
        val model = FakeModelClient(listOf(turn(call("get_screen")), turn(tap), turn(batch), turn(delegate), turn(repeat), turn(replay), finish()))
        val helper = FakeModelClient(emptyList())
        val loop = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue(), helperModel = helper)
        val events = loop.run(emptyList(), "请在矩形左上角(100,100)、右下角(180,160)内连续点击6次，每隔125毫秒点击", false).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        val outputs = outputs(model)
        for (substitute in listOf(tap, batch, delegate)) {
            val output = outputs.getValue(substitute.callId)
            assertTrue(output, output.startsWith("NOT RUN") && "repeat_tap" in output)
        }
        assertEquals("repeat_tap requested=6 completed=2 stop_reason=PAGE_CHANGED", outputs.getValue(repeat.callId).lineSequence().first())
        assertTrue(outputs.getValue(replay.callId).startsWith("NOT RUN"))
        assertEquals(listOf(Group(140, 130, 6, 125, null)), device.groups)
        assertFalse(device.raw.calls.any { it.startsWith("tap(") })
        assertEquals(0, helper.calls)
    }

    @Test fun ambiguousTargetIsClarifiedBeforeInput() = runTest {
        val both = screen.copy(roots = listOf(button("开始", 0), button("开始", 100)))
        val device = TestDevice(FakeDeviceController(listOf(both)))
        val gate = FakeUserGate().apply { askResult = "取消" }
        val guess = call("repeat_tap", """{"mode":"point","x":40,"y":130}""")
        val ask = call("ask_user", """{"question":"需要点击哪个开始按钮？","options":["左侧","右侧","取消"]}""")
        val model = FakeModelClient(listOf(turn(call("get_screen")), turn(guess), turn(ask), finish()))

        AgentLoop(model, ToolExecutor(device, gate), SteeringQueue()).run(emptyList(), "重复点击「开始」", false).toList()

        assertTrue(outputs(model).getValue(guess.callId).contains("several visible controls"))
        assertEquals(1, gate.asks.size)
        assertTrue(outputs(model).getValue(ask.callId).contains("取消"))
        assertTrue(device.groups.isEmpty())
        assertFalse(device.raw.calls.any { it.startsWith("tap(") })
    }

    @Test fun aStoppedSequenceCannotBeReissuedDuringACompoundTask() {
        val route = RepeatTapRouting("先打开练习应用，然后点击“开始”6次，再返回。")
        val repeat = call("repeat_tap", """{"mode":"point","x":40,"y":130,"count":6}""")
        val stopped = RepeatTapReceipt(6, 2, "PAGE_CHANGED")
        route.record(repeat, ToolResult(stopped.line, null, null, null, repeatReceipt = stopped))
        for (retry in listOf(repeat, call("tap", """{"x":40,"y":130}"""), call("delegate"))) {
            assertTrue(route.rejection(retry, null).orEmpty().contains("completed=2"))
        }
        assertNull(route.rejection(call("finish"), null))
        val chat = RepeatTapRouting("请解释什么是自动连点")
        assertTrue(chat.rejection(call("tap", """{"x":40,"y":130}"""), null).orEmpty().startsWith("NOT RUN"))
    }

    @Test fun explicitUserGeometryCanBeTheFirstModelCallAndStillChecksTheScreen() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen)))
        val repeat = call("repeat_tap", """{"mode":"rect","left":100,"top":100,"right":180,"bottom":160,"x":135,"y":128,"count":4,"interval_ms":180}""")
        val model = FakeModelClient(listOf(turn(repeat), finish()))
        val loop = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue())
        loop.run(emptyList(), "在矩形 (100,100,180,160) 内连续点击4次，每次间隔180毫秒，固定点 (135,128)。", false).toList()
        assertEquals(listOf(Group(135, 128, 4, 180, null)), device.groups)
        assertTrue(outputs(model).getValue(repeat.callId).contains("completed=4 stop_reason=COMPLETED"))
    }

    @Test fun theUserCanDisambiguateMatchingButtonsByPosition() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen.copy(roots = listOf(button("开始", 0), button("开始", 100))))))
        val gate = FakeUserGate().apply { askResult = "右侧" }
        val guess = call("repeat_tap", """{"mode":"point","x":40,"y":130}""")
        val wrongSide = call("repeat_tap", guess.argumentsJson)
        val right = call("repeat_tap", """{"mode":"point","x":140,"y":130}""")
        val ask = call("ask_user", """{"question":"哪个开始按钮？","options":["左侧","右侧"]}""")
        val model = FakeModelClient(listOf(turn(call("get_screen")), turn(guess), turn(ask), turn(wrongSide), turn(right), finish()))
        AgentLoop(model, ToolExecutor(device, gate), SteeringQueue()).run(emptyList(), "请在“开始”上自动连点", false).toList()
        assertTrue(outputs(model).getValue(guess.callId).startsWith("NOT RUN"))
        assertTrue(outputs(model).getValue(wrongSide.callId).startsWith("NOT RUN"))
        assertEquals(listOf(Group(140, 130, 10, 1000, null)), device.groups)
    }

    @Test fun modelMustPreserveCountIntervalChecksAndExplicitPixelsBeforeTheExecutorGetsTheOrder() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen)))
        val original = """{"mode":"point","x":140,"y":130,"count":12,"interval_ms":200,"check_every":4}"""
        val guesses = listOf(
            original.replace("\"x\":140", "\"x\":141"),
            original.replace("\"count\":12", "\"count\":3"),
            original.replace("\"interval_ms\":200", "\"interval_ms\":1000"),
            original.replace("\"check_every\":4", "\"check_every\":10"),
        ).map { call("repeat_tap", it) }
        val right = call("repeat_tap", original)
        val model = FakeModelClient(listOf(turn(call("get_screen"))) + guesses.map(::turn) + listOf(turn(right), finish()))
        val events = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue())
            .run(emptyList(), "请在坐标(140,130)连续点击12次，每隔200毫秒一次，每4次检查页面", false).toList()

        assertTrue(events.last() is AgentEvent.Finished)
        for (guess in guesses) assertTrue(outputs(model).getValue(guess.callId).startsWith("NOT RUN"))
        assertEquals(List(3) { Group(140, 130, 4, 200, null) }, device.groups)
        assertTrue(outputs(model).getValue(right.callId).startsWith("repeat_tap requested=12 completed=12"))
    }

    @Test fun defaultValuesAndTheExactRegionCannotBeReplacedByTheModel() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen)))
        val original = """{"mode":"rect","left":100,"top":100,"right":180,"bottom":160}"""
        val proposals = listOf(
            original.replace("\"left\":100", "\"left\":101"),
            original.dropLast(1) + ""","x":141,"y":130}""",
            original.dropLast(1) + ""","count":5}""",
            original.dropLast(1) + ""","interval_ms":5}""",
        ).map { call("repeat_tap", it) }
        val exact = call("repeat_tap", original)
        val model = FakeModelClient(listOf(turn(call("get_screen"))) + proposals.map(::turn) + listOf(turn(exact), finish()))
        AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue())
            .run(emptyList(), "在矩形[100,100,180,160]内自动连点", false).toList()

        for (proposal in proposals) assertTrue(outputs(model).getValue(proposal.callId).startsWith("NOT RUN"))
        assertEquals(listOf(Group(140, 130, 10, 1000, null)), device.groups)
    }

    @Test fun explicitPhrasingsUseRepeatAndUnresolvedTimingDoesNotImplyADefault() {
        val visible = TreeCompressor.compress(screen)
        val tap = call("tap", """{"index":2}""")
        for (text in listOf("自动连点", "请自动点击", "快速连续点击", "连续点击", "重复点击", "批量点击", "连点7次",
            "点击练习区7次", "每秒点击", "每隔250毫秒点击", "点击练习区，间隔250毫秒", "tap the pad six times")) {
            assertTrue(text, RepeatTapRouting(text).rejection(tap, visible)?.startsWith("NOT RUN") == true)
        }
        val previous = RepeatTapRouting("按上次的节奏连续点击练习区，持续一会儿")
        assertNull(previous.rejection(call("repeat_tap", """{"mode":"point","x":140,"y":130,"count":6,"interval_ms":250}"""), visible))
        val seconds = RepeatTapRouting("在练习区连点十二次，间隔0.2秒")
        assertNull(seconds.rejection(call("repeat_tap", """{"mode":"point","x":140,"y":130,"count":12,"interval_ms":200}"""), visible))
        val rate = RepeatTapRouting("在练习区每秒点击两次")
        assertNull(rate.rejection(call("repeat_tap", """{"mode":"point","x":140,"y":130,"count":10,"interval_ms":500}"""), visible))
    }

    @Test fun discussingTheToolAndAnOrdinarySingleTapHaveSeparateRoutes() = runTest {
        val device = TestDevice(FakeDeviceController(listOf(screen)))
        val proposed = call("repeat_tap", """{"mode":"point","x":140,"y":130}""")
        val model = FakeModelClient(listOf(turn(proposed), ModelTurn("可以用一次工具调用指定次数和间隔。", emptyList(), null)))
        val discussion = AgentLoop(model, ToolExecutor(device, FakeUserGate()), SteeringQueue())
            .run(emptyList(), "解释自动连点的代码怎么写", false).toList()
        assertTrue(outputs(model).getValue(proposed.callId).startsWith("NOT RUN"))
        assertTrue(discussion.none { it == AgentEvent.DeviceUse })
        assertTrue(device.raw.calls.isEmpty())

        val once = FakeModelClient(listOf(turn(call("get_screen")), turn(call("tap", """{"index":2}""")), finish()))
        AgentLoop(once, ToolExecutor(device, FakeUserGate()), SteeringQueue()).run(emptyList(), "请点击练习区一次", false).toList()
        assertEquals(listOf("tap(140,130)"), device.raw.calls.filter { it.startsWith("tap(") })
        assertTrue(device.groups.isEmpty())
    }

    @Test fun unrelatedNavigationIsStillAvailableInACompoundOrder() {
        val route = RepeatTapRouting("先点击菜单，然后连续点击练习区五次，再点关闭")
        val visible = TreeCompressor.compress(screen)
        assertNull(route.rejection(call("tap", """{"index":1}"""), visible))
        assertTrue(route.rejection(call("tap", """{"index":2}"""), visible)?.startsWith("NOT RUN") == true)
        assertNull(route.rejection(call("tap", """{"index":3}"""), visible))
        assertNull(route.rejection(call("delegate", """{"task":"Read the visible labels."}"""), visible))
    }
}
