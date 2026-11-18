package org.localwake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.localwake.app.execution.AppLaunchConfiguration
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.ui.ScheduleDraft
import org.localwake.app.ui.mergeTaskEdits

class ScheduleDraftTest {
    @Test fun committingReorderedDuplicateAppsRetainsMillisecondDelays() {
        val original = ScheduleModel(apps = listOf(
            AppLaunchConfiguration("org.example.first", 1_001),
            AppLaunchConfiguration("org.example.second", 800),
            AppLaunchConfiguration("org.example.first", 2_005),
        ))
        val draft = ScheduleDraft.from(original)
        val edited = draft.copy(apps = listOf(draft.apps[2], draft.apps[0], draft.apps[1].copy(delaySeconds = "1,25")))
            .editedTask(original)

        assertEquals(listOf("org.example.first", "org.example.first", "org.example.second"),
            edited?.apps?.map { it.packageName })
        assertEquals(listOf(2_005L, 1_001L, 1_250L), edited?.apps?.map { it.delayMs })
    }

    @Test fun incompleteOrOutOfRangeDraftCannotBeSaved() {
        val original = ScheduleModel(apps = listOf(
            AppLaunchConfiguration("org.example.first", 15_000),
            AppLaunchConfiguration("org.example.second", 15_000),
        ))
        val draft = ScheduleDraft.from(original)

        assertNotNull(draft.editedTask(original))
        assertNull(draft.copy(name = " ").editedTask(original))
        assertNull(draft.copy(name = "x".repeat(81)).editedTask(original))
        assertNull(draft.copy(hour = "24").editedTask(original))
        assertNull(draft.copy(minute = "").editedTask(original))
        assertNull(draft.copy(weekdays = emptySet()).editedTask(original))
        assertNull(draft.copy(weekdays = setOf(8)).editedTask(original))
        assertNull(draft.copy(apps = List(6) { draft.apps[0].copy(key = it, delaySeconds = "0") }).editedTask(original))
        assertNull(draft.copy(apps = draft.apps.take(1) + draft.apps[1].copy(delaySeconds = "15.001")).editedTask(original))
        assertNull(draft.copy(apps = listOf(draft.apps[0].copy(delaySeconds = "0.0009"))).editedTask(original))
    }

    @Test fun committingTimeAndAppsKeepsLatestUneditedFieldsAndRunState() {
        val original = ScheduleModel(name = "Morning", hour = 7, weekdays = setOf(1))
        val current = original.copy(name = "New title", weekdays = setOf(1, 2), enabled = false,
            mode = ExecutionMode.WAKE_ONLY, revision = "new-revision", lastDate = "2026-11-14",
            armedToken = "new-token", armedAt = 123_456L, armedDate = "2026-11-17")
        val changedApps = listOf(AppLaunchConfiguration("org.example.first", 4_500L))
        val edited = original.copy(hour = 8, minute = 5, apps = changedApps)

        assertEquals(current.copy(hour = 8, minute = 5, apps = changedApps), mergeTaskEdits(original, current, edited))
    }
}
