package org.localwake.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class LocalAlarmScheduler(private val context: Context, private val repository: ScheduleRepository) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    val mutex = Mutex()
    fun canSchedule() = manager.canScheduleExactAlarms()
    private fun pending(id: String, token: String = ""): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.Builder().scheme("localwake").authority("alarm").appendPath(id).build())
            .putExtra("token", token).putExtra("authority_epoch", repository.authorityEpoch()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancel(id: String) { manager.cancel(pending(id)) }

    // Call only under mutex. Persist token before handing the operation to AlarmManager.
    suspend fun armNext(id: String, force: Boolean = false): String {
        if (!repository.legacyAuthority()) { cancel(id); return "AUTHORITY_CHANGED" }
        if (!canSchedule()) { cancel(id); repository.disarm(id); return "EXACT_ALARM_DENIED" }
        val task = repository.arm(id, Instant.now(), ZoneId.systemDefault(), force)
        if (task == null) { cancel(id); return "INACTIVE" }
        return try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, requireNotNull(task.armedAt),
                pending(task.id, requireNotNull(task.armedToken)))
            "ARMED"
        } catch (_: SecurityException) {
            cancel(id); repository.disarm(id); "EXACT_ALARM_DENIED"
        }
    }
    suspend fun reconcile(force: Boolean = true): String = reconcileByAuthority(
        legacyMutex = mutex,
        legacyOwns = { repository.legacyAuthority() },
        unified = {
            val runtime = com.phoneuse.routine.UnifiedRoutineRuntime.get(context)
            if (runtime.authority.allows(com.phoneuse.routine.RoutineOwner.ROUTINE)) {
                // No legacy mutex here: a due routine can enter the local unlock engine synchronously.
                com.phoneuse.routine.RoutineSystem.get(context).resync(force)
                "ROUTINE_AUTHORITY"
            } else "MIGRATION_RECOVERY_REQUIRED"
        },
        legacy = {
            var status = if (canSchedule()) "ARMED" else "EXACT_ALARM_DENIED"
            for (task in repository.snapshot().schedules) {
                if (armNext(task.id, force) == "EXACT_ALARM_DENIED") status = "EXACT_ALARM_DENIED"
            }
            status
        },
    )
    suspend fun save(task: ScheduleModel): String = mutex.withLock {
        if (!repository.legacyAuthority()) return@withLock "USE_ROUTINE_EDITOR"
        cancel(task.id)
        repository.save(task)
        armNext(task.id, true)
    }
    suspend fun delete(id: String) = mutex.withLock { cancel(id); repository.delete(id) }
    suspend fun update(id: String, transform: (ScheduleModel) -> ScheduleModel): String = mutex.withLock {
        if (!repository.legacyAuthority()) return@withLock "USE_ROUTINE_EDITOR"
        if (!repository.edit(id, transform)) return@withLock "TASK_MISSING"
        cancel(id)
        armNext(id, true)
    }
    /** Migration caller already holds [mutex]; cancellation alone is never the execution fence. */
    suspend fun cancelLegacyAlarms() { repository.legacySnapshot().schedules.forEach { cancel(it.id) } }
    companion object { const val ACTION_FIRE = "org.localwake.app.FIRE_LOCAL_ALARM" }
}
