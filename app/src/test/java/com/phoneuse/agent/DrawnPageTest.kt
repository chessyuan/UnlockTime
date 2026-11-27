package com.phoneuse.agent

import com.phoneuse.screen.AppWindowRegion
import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode
import com.phoneuse.screen.TreeCompressor
import org.junit.Assert.*
import org.junit.Test
import com.phoneuse.model.InputItem
import kotlinx.coroutines.test.runTest

/** WeChat on the test phone exposes one empty root while its window and captured page remain visible. */
class DrawnPageTest {
    private val root = UiNode(11, emptyList(), null, null, null, null, null, Bounds(0, 0, 0, 0), visibleToUser = false)
    private val region = AppWindowRegion(11, "com.tencent.mm", Bounds(0, 100, 1440, 3000))
    private val page = ScreenSnapshot("com.tencent.mm", 1440, 3120, listOf(root), appWindowRegions = listOf(region))

    @Test fun anEmptyAppRootCanUseItsRealVisibleWindowGeometry() {
        assertTrue(RepeatTapPage(page, 315, 2160).matches(page))
        assertFalse(RepeatTapPage(page, 315, 60).matches(page))
        assertFalse(RepeatTapPage(page, 315, 3100).matches(page))
    }

    @Test fun missingAppNodesDoNotProveThatDifferentWebPagesAreTheSamePage() {
        val screen = TreeCompressor.compress(page)
        val history = ScreenJournal()
        assertNull(history.revisit(1, "open", screen))
        assertNull(history.revisit(2, "navigate to seat", screen))
    }

    @Test fun changedMissingCoveredAndForeignWindowsCannotAuthorizeThePoint() {
        val target = RepeatTapPage(page, 315, 2160)
        for (changed in listOf(
            page.copy(appWindowRegions = emptyList()),
            page.copy(appWindowRegions = listOf(region.copy(windowId = 12))),
            page.copy(appWindowRegions = listOf(region.copy(packageName = "other.app"))),
            page.copy(appWindowRegions = listOf(region.copy(bounds = Bounds(400, 100, 1440, 3000)))),
            page.copy(ownAppInFront = true), page.copy(activeIsSystem = true), page.copy(truncated = true),
            page.copy(roots = listOf(root, root.copy(windowId = 22, bounds = region.bounds, visibleToUser = true)), systemWindowIds = setOf(22)),
        )) assertFalse(target.matches(changed))
    }

    @Test fun samsungEdgeHandleLeavesOnlyTheContinuouslyVisibleInnerRectangle() {
        val strips = listOf(Bounds(60, 127, 1440, 401), Bounds(60, 401, 1377, 849), Bounds(60, 849, 1440, 2993))
        val drawn = page.copy(appWindowRegions = strips.map { region.copy(bounds = it) })
        assertEquals(Bounds(60, 127, 1377, 2993), drawn.drawnRegion)
        assertTrue(RepeatTapPage(drawn, 315, 2160).matches(drawn))
        assertFalse(RepeatTapPage(drawn, 1400, 500).matches(drawn))
        assertNull(drawn.copy(appWindowRegions = listOf(drawn.appWindowRegions.first(), drawn.appWindowRegions.last())).drawnRegion)
    }

    @Test fun drawnPageStopsAfterChangedPixelsEvenWithoutAnAccessibilityEvent() = runTest {
        for (change in listOf(false, true)) {
            val raw = FakeDeviceController(listOf(page))
            val groups = mutableListOf<Int>()
            var color = 0x111111
            val device = object : DeviceController by raw {
                override fun pageChangeVersion() = 5L
                override suspend fun screenshot() = byteArrayOf(color.toByte())
                override suspend fun markedScreenshot(marks: List<ScreenMark>) = screenshot()
                override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
                    groups += count
                    if (change) color = 0x888888
                    return TapSequenceResult(count)
                }
            }
            val executor = ToolExecutor(device, FakeUserGate(), pageAppearance = { bytes, _, _, _ ->
                intArrayOf((bytes[0].toInt() and 255) * 0x010101)
            })
            val result = executor.execute(InputItem.FunctionCall("repeat", "repeat_tap",
                """{"stop_on_page_change":true,"mode":"point","x":315,"y":2160,"count":12,"interval_ms":1}"""), executor.observe().screen)
            assertEquals(if (change) listOf(10) else listOf(10, 2), groups)
            assertEquals(if (change) RepeatTapReceipt(12, 10, "PAGE_CHANGED") else RepeatTapReceipt(12, 12, "COMPLETED"), result.repeatReceipt)
        }
    }

    @Test fun drawnPageBaselineAndCheckUseTheSameUnmarkedPixels() = runTest {
        val raw = FakeDeviceController(listOf(page))
        var completed = 0
        val device = object : DeviceController by raw {
            override fun pageChangeVersion() = 5L
            override suspend fun screenshot() = byteArrayOf(1)
            override suspend fun markedScreenshot(marks: List<ScreenMark>) = byteArrayOf(2)
            override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
                completed += count
                return TapSequenceResult(count)
            }
        }
        val executor = ToolExecutor(device, FakeUserGate(), pageAppearance = { bytes, _, _, _ ->
            intArrayOf(bytes[0].toInt() * 0x202020)
        })
        val observed = executor.observe()
        val result = executor.execute(InputItem.FunctionCall("r", "repeat_tap",
            """{"stop_on_page_change":true,"mode":"point","x":315,"y":2160,"count":3,"interval_ms":1}"""), observed.screen)
        assertEquals(3, completed)
        assertEquals(RepeatTapReceipt(3, 3, "COMPLETED"), result.repeatReceipt)
    }

    @Test fun noPixelsOrFailedFreshCaptureNeverAllowsBlindInput() = runTest {
        for (noPixels in listOf(false, true)) {
            val raw = FakeDeviceController(listOf(page))
            var permit = true
            var taps = 0
            val device = object : DeviceController by raw {
                override fun pageChangeVersion() = 5L
                override suspend fun screenshot(): ByteArray? = if (permit) byteArrayOf(1) else null
                override suspend fun markedScreenshot(marks: List<ScreenMark>) = screenshot()
                override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
                    taps += count; return TapSequenceResult(count)
                }
            }
            val executor = ToolExecutor(device, FakeUserGate(), pageAppearance = { _, _, _, _ -> if (noPixels) null else intArrayOf(1) })
            val seen = executor.observe().screen
            permit = false
            val result = executor.execute(InputItem.FunctionCall("repeat", "repeat_tap",
                """{"stop_on_page_change":true,"mode":"point","x":315,"y":2160,"count":12}"""), seen)
            assertEquals(0, taps)
            assertEquals(if (noPixels) "SCREEN_READ_REQUIRED" else "CAPTURE_FAILED", result.repeatReceipt?.reason)
        }
    }
}
