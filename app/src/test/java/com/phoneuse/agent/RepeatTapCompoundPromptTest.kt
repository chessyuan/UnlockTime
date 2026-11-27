package com.phoneuse.agent

import com.phoneuse.model.InputItem
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Full compound-task structure that caused rejection; identifiers are synthetic for public tests. */
class RepeatTapCompoundPromptTest {
    private val task = requireNotNull(javaClass.getResource("/repeat_tap/compound_fixed_point_zh.txt")).readText()
    private val repeat = InputItem.FunctionCall("repeat", "repeat_tap",
        """{"mode":"point","x":315,"y":2160,"count":60,"interval_ms":1000,"check_every":10,"stop_on_page_change":false}""")

    @Test fun theCompleteFixedPointTaskReachesTheLocalTool() {
        val rejection = RepeatTapRouting(task, standalone = false).rejection(repeat, null)
        assertNull(rejection, rejection)
    }

    @Test fun theSameTaskWithoutItsRoutineHeaderAlsoReachesTheLocalTool() {
        val rejection = RepeatTapRouting(task.substringAfter('\n')).rejection(repeat, null)
        assertNull(rejection, rejection)
    }

    @Test fun negatingAnOptionDoesNotTurnAnExecutionRequestIntoAQuestion() {
        for (message in listOf(
            "连续点击60次，每次间隔1000毫秒，不要设置stop_on_page_change=true。",
            "Tap the pad 60 times at 1000 ms intervals. Do not stop when the page changes.",
        )) {
            val rejection = RepeatTapRouting(message).rejection(repeat, null)
            assertNull(rejection, rejection)
        }
    }

    @Test fun explicitNoClickOrdersAndQuestionsStillCannotStartInput() {
        for (message in listOf("不要连续点击60次", "不要实际点击60次", "请解释连续点击60次的代码",
            "Don't click the pad 60 times", "Do not run repeated clicking", "How do I implement auto-clicking?")) {
            assertNotNull(message, RepeatTapRouting(message).rejection(repeat, null))
        }
    }
}
