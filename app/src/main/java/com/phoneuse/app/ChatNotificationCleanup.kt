package com.phoneuse.app

/** Stable notification identities let AI history cleanup preserve device tasks on shared channels. */
internal data class ChatCleanupNotification(
    val tag: String?,
    val id: Int,
    val channel: String?,
    val group: String? = null,
    val isGroupSummary: Boolean = false,
)

internal object ChatNotificationCleanup {
    private val aiChannels = setOf(
        "phoneuse_results", "phoneuse_needs_ok", "phoneuse_progress",
        "phoneuse_daily_summary", "phoneuse_nudges",
    )
    private val deviceTags = setOf("routine", "routine_result", "reminder")
    private val quietAiTags = setOf("result", "needs_ok", "nudge")

    fun select(active: List<ChatCleanupNotification>): List<ChatCleanupNotification> {
        // Routine results use the AI results channel, and quiet hours share one channel for both kinds.
        fun isAi(notification: ChatCleanupNotification): Boolean = when {
            notification.tag in deviceTags -> false
            notification.channel in aiChannels -> true
            notification.channel == "phoneuse_quiet" -> notification.tag in quietAiTags ||
                (notification.tag == null && notification.id == 3) // DailySummary's stable notification id.
            else -> false
        }
        val preservedGroups = active.filterNot(::isAi).mapNotNull { it.group }.toSet()
        return active.filter { isAi(it) && !(it.isGroupSummary && it.group in preservedGroups) }
    }
}
