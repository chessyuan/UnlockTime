package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Chrome can expose the whole page as one clickable View whose label includes every button. */
class PageScrollRiskTest {
    private fun button(path: Int, text: String, bounds: Bounds) = UiNode(1, listOf(path), "Button", text, null, null, null, bounds, clickable = true)
    private val page = ScreenSnapshot("com.android.chrome", 1440, 3120, listOf(
        UiNode(1, emptyList(), "android.view.View", "图书馆助手 运行状态 签到成功 蓝牙参数 已配置 退出登录", null, null, null,
            Bounds(0, 127, 1440, 3000), clickable = true, children = listOf(
                button(0, "退出登录", Bounds(1200, 200, 1360, 350)),
                button(1, "立即签到", Bounds(1100, 2400, 1300, 2540)),
                button(2, "打开快捷操作", Bounds(1100, 2640, 1350, 2780)),
            )),
    ))

    @Test fun refreshingThePageDoesNotAskToChangeAccountSecurity() = runTest {
        val device = FakeDeviceController(listOf(page))
        val gate = FakeUserGate().apply { confirmResult = false }
        val executor = ToolExecutor(device, gate)
        val result = executor.execute(InputItem.FunctionCall("refresh", "swipe",
            """{"x1":720,"y1":900,"x2":720,"y2":1800,"duration_ms":500}"""), executor.observe().screen)
        assertTrue(result.output, result.output.startsWith("OK"))
        assertTrue(gate.confirms.isEmpty())
        assertTrue(device.calls.any { it.startsWith("swipe") })
    }

    @Test fun securityButtonAndTapLikeSwipeStillRequireConfirmation() = runTest {
        for (args in listOf(
            """{"x1":1280,"y1":270,"x2":1280,"y2":1700}""",
            """{"x1":720,"y1":900,"x2":725,"y2":905}""",
            """{"x1":720,"y1":900,"x2":1200,"y2":900}""",
        )) {
            val device = FakeDeviceController(listOf(page))
            val gate = FakeUserGate().apply { confirmResult = false }
            val executor = ToolExecutor(device, gate)
            executor.execute(InputItem.FunctionCall("gesture", "swipe", args), executor.observe().screen)
            assertEquals(1, gate.confirms.size)
            assertFalse(device.calls.any { it.startsWith("swipe") })
        }
    }
}
