package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNotificationCleanupTest {
    @Test fun aiHistoryCleanupPreservesDeviceTasksAndUnknownChannels() {
        val chat = ChatCleanupNotification("result", 41, "phoneuse_results")
        val progress = ChatCleanupNotification("progress", 41, "phoneuse_progress")
        val active = listOf(chat, progress,
            ChatCleanupNotification("routine_result", 41, "phoneuse_results"),
            ChatCleanupNotification("routine", 7, "phoneuse_routines"),
            ChatCleanupNotification("reminder", 9, "phoneuse_reminders"),
            ChatCleanupNotification(null, 10, "localwake_background_guard"),
            ChatCleanupNotification(null, 11, "future_device_channel"))
        assertEquals(listOf(chat, progress), ChatNotificationCleanup.select(active))
    }

    @Test fun sharedQuietChannelClearsOnlyKnownAiNotifications() {
        val chat = ChatCleanupNotification("result", 8, "phoneuse_quiet")
        val summary = ChatCleanupNotification(null, 3, "phoneuse_quiet")
        val approval = ChatCleanupNotification("needs_ok", 5, "phoneuse_quiet")
        val active = listOf(chat, summary, approval,
            ChatCleanupNotification("routine", 3, "phoneuse_quiet"),
            ChatCleanupNotification("routine_result", 8, "phoneuse_quiet"),
            ChatCleanupNotification(null, 99, "phoneuse_quiet"))
        assertEquals(listOf(chat, summary, approval), ChatNotificationCleanup.select(active))
    }

    @Test fun mixedResultGroupKeepsItsSummaryAndRoutineChild() {
        val chat = ChatCleanupNotification("result", 2, "phoneuse_results", "results")
        val summary = ChatCleanupNotification("result", 0, "phoneuse_results", "results", true)
        val routine = ChatCleanupNotification("routine_result", 4, "phoneuse_results", "results")
        assertEquals(listOf(chat), ChatNotificationCleanup.select(listOf(summary, chat, routine)))
        assertEquals(listOf(summary, chat), ChatNotificationCleanup.select(listOf(summary, chat)))
    }
}
