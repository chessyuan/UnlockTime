package org.localwake.app.scheduler

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.localwake.app.localWake

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val authority = com.phoneuse.routine.UnifiedRoutineRuntime.get(context).authority
        if (authority.snapshot().owner == com.phoneuse.routine.RoutineOwner.NONE) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            org.localwake.app.background.BackgroundGuardService.restore(context)
        val pending = goAsync()
        context.localWake.ioScope.launch {
            try { withTimeout(8_000) { context.localWake.scheduler.reconcile(true) } }
            catch (_: Exception) { /* No credential access; dashboard exposes unarmed schedules. */ }
            finally { pending.finish() }
        }
    }
}
