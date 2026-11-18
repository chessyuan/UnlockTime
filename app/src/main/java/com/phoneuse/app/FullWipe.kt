package com.phoneuse.app

import com.phoneuse.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.phoneuse.chat.ChatDb
import com.phoneuse.chat.ChatDbSchema
import com.phoneuse.phone.NotificationStore
import com.phoneuse.proactive.ProactiveSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings "Delete all chats and memory": clears AI conversations, notification history, nudges and typed
 * asks, usage counts, memories and app tips, "About me", AI notifications and temporary camera photos.
 * The chat database is compacted after deletion. Routine definitions and execution records, reminders,
 * scheduling authority, background protection, local unlock credentials and account sign-ins stay intact.
 * Each part fails independently; the result names the parts that failed. Work runs off the main thread.
 */
object FullWipe {
    private val LOG: java.util.logging.Logger = java.util.logging.Logger.getLogger("FullWipe")

    /** Runs every part; returns the names of those that failed. */
    suspend fun run(context: Context): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runParts(
            listOf(
                tr("Chats", "대화") to {
                    ReadAloud.stop()
                    for (c in AppGraph.chats.conversations().first()) AppGraph.chats.delete(c.id)
                },
                tr("Notification history", "알림 기록") to { NotificationStore.get(app).clear() },
                tr("Proactive help", "알아서 챙기기") to { ProactiveSystem.get(app).clearNow() },
                tr("Usage", "사용량") to { AppGraph.usage.clear() },
                tr("Memories", "기억") to {
                    AppGraph.memories.deleteAll()
                    AppGraph.memories.deleteAllTips()
                },
                tr("About me", "나에 대해") to {
                    val p = AppGraph.settings.personalization.first()
                    if (p.about.isNotEmpty()) AppGraph.settings.setPersonalization(p.copy(about = ""))
                },
                tr("Notifications", "알림") to {
                    val manager = app.getSystemService(NotificationManager::class.java)
                    if (manager != null) {
                        val active = manager.activeNotifications.map {
                            ChatCleanupNotification(it.tag, it.id, it.notification.channelId, it.notification.group,
                                it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
                        }
                        ChatNotificationCleanup.select(active).forEach { manager.cancel(it.tag, it.id) }
                    }
                },
                tr("Temporary photos", "사진 임시 파일") to { File(app.cacheDir, "camera").deleteRecursively() },
                // Last, after every delete: deleted text leaves the full-text segments and the free pages.
                tr("Storage cleanup", "저장 공간 정리") to {
                    val db = ChatDb.get(app).writable
                    ChatDbSchema.compact.forEach(db::execSQL)
                },
            ),
        )
    }

    /** Runs each part in order whatever the others do; the names of the parts that threw. */
    suspend fun runParts(parts: List<Pair<String, suspend () -> Unit>>): List<String> =
        parts.mapNotNull { (name, block) ->
            try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warning("wiping $name failed: ${e.javaClass.simpleName}")
                name
            }
        }

    /** What the toast says after [run]. */
    fun resultText(failed: List<String>): String =
        if (failed.isEmpty()) tr("Erased all chats and memories", "모든 대화와 기억을 지웠어요")
        else tr("Couldn't erase some items: ${failed.joinToString(", ")}", "일부를 지우지 못했어요: ${failed.joinToString(", ")}")
}
