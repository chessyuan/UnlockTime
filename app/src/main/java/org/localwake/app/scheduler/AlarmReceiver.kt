package org.localwake.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.localWake

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalAlarmScheduler.ACTION_FIRE) return
        val authority = com.phoneuse.routine.UnifiedRoutineRuntime.get(context).authority
        if (!authority.allows(com.phoneuse.routine.RoutineOwner.LEGACY, intent.getLongExtra("authority_epoch", -1))) return
        val id = intent.data?.lastPathSegment ?: return
        val token = intent.getStringExtra("token") ?: return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val pending = goAsync()
        val app = context.localWake
        app.ioScope.launch {
            try {
                withTimeout(8_000) {
                    val claim = app.scheduler.mutex.withLock {
                        if (!authority.allows(com.phoneuse.routine.RoutineOwner.LEGACY, intent.getLongExtra("authority_epoch", -1))) return@withLock Claim(null, "AUTHORITY_CHANGED")
                        val c = app.repository.claim(id, token, System.currentTimeMillis())
                        // Rearm before handoff: execution/process failure cannot strand tomorrow's alarm.
                        if (c.task != null || c.reason == "MISSED_WINDOW") app.scheduler.armNext(id, true)
                        c
                    }
                    val task = claim.task
                    if (task == null) {
                        if (claim.reason == "MISSED_WINDOW") app.repository.record(ExecutionRecord(token, "错过执行窗口", System.currentTimeMillis(), claim.reason))
                        return@withTimeout
                    }
                    val service = withTimeoutOrNull(4_000) {
                        while (LocalWakeAccessibilityService.instance.value?.isReady() != true) delay(100)
                        LocalWakeAccessibilityService.instance.value
                    }
                    val accepted = withContext(Dispatchers.Main.immediate) { service?.submit(task, token) == true }
                    if (!accepted) app.repository.record(ExecutionRecord(token, task.name, System.currentTimeMillis(),
                        if (service == null) "ACCESSIBILITY_DISCONNECTED" else "EXECUTION_BUSY"))
                }
            } catch (_: Exception) {
                // Fixed code only; no exception messages or input content in logs.
                withContext(NonCancellable) {
                    withTimeoutOrNull(500) { app.repository.record(ExecutionRecord(token, "本地定时", System.currentTimeMillis(), "ALARM_DISPATCH_FAILED")) }
                }
            } finally { pending.finish() }
        }
    }
}
