package org.localwake.app.background

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.localwake.app.AppLanguage
import org.localwake.app.MainActivity
import com.phoneuse.R
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.localWake
import org.localwake.app.security.CredentialState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** User-visible local schedule supervision. No network, permanent wakelock or credential input. */
class BackgroundGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val languageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppLanguage.LANGUAGE_KEY) refresh.trySend(Unit)
    }
    private var observer: Job? = null
    private var language: AppLanguage? = null
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguage.preferences(this).registerOnSharedPreferenceChangeListener(languageListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val strings = localizedNotificationContext()
            startForeground(NOTIFICATION, notification(strings, lastText ?: strings.getString(R.string.guard_checking)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: RuntimeException) {
            running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        if (observer?.isActive != true) observer = scope.launch {
            while (isActive) {
                val strings = localizedNotificationContext()
                val text = try {
                    val state = localWake.repository.snapshot()
                    val unified = com.phoneuse.routine.UnifiedRoutineRuntime.get(this@BackgroundGuardService)
                    val authority = unified.authority.snapshot()
                    val next = if (authority.owner == com.phoneuse.routine.RoutineOwner.ROUTINE) {
                        unified.store.pending(authority.epoch).filter { occurrence ->
                            unified.store.get(occurrence.routineId)?.enabled == true
                        }.minOfOrNull { it.dueAt }
                    } else state.schedules.filter { it.enabled }.mapNotNull { it.armedAt }.minOrNull()
                    val message = GuardStatus.messageResource(LocalWakeAccessibilityService.instance.value?.isReady() == true,
                        localWake.scheduler.canSchedule(),
                        withContext(Dispatchers.IO) { localWake.credentials.status() } == CredentialState.READY,
                        state.patternBlocked, localWake.executionMutex.isLocked, next != null)
                    if (message == R.string.guard_next_task && next != null) {
                        strings.getString(message, timeFormat.format(Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())))
                    } else strings.getString(message)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { strings.getString(R.string.guard_check_failed) }
                if (text != lastText) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(strings, text))
                    lastText = text
                }
                // Suspends; no permanent wakelock. AlarmManager still owns every wake-up.
                withTimeoutOrNull(15_000) { refresh.receive() }
            }
        }
        return START_STICKY
    }

    private fun localizedNotificationContext(): Context {
        val selected = AppLanguage.current(this)
        val strings = selected.wrap(this)
        if (selected != language) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, strings.getString(R.string.guard_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = strings.getString(R.string.guard_channel_description, strings.getString(R.string.app_name))
                    setShowBadge(false)
                },
            )
            language = selected
            lastText = null
        }
        return strings
    }

    private fun notification(strings: Context, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_unlocktime)
            .setContentTitle(strings.getString(R.string.guard_notification_title, strings.getString(R.string.app_name)))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        AppLanguage.preferences(this).unregisterOnSharedPreferenceChangeListener(languageListener)
        running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "localwake_background_guard"
        private const val NOTIFICATION = 101
        private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val running = MutableStateFlow(false)
        private fun preferences(context: Context) = context.getSharedPreferences("background_guard", Context.MODE_PRIVATE)
        fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
        fun startFromUser(context: Context): Boolean {
            if (!preferences(context).edit().putBoolean("enabled", true).commit()) return false
            if (restore(context)) return true
            preferences(context).edit().putBoolean("enabled", false).apply()
            return false
        }
        fun restore(context: Context): Boolean {
            if (!enabled(context)) return false
            return try { context.startForegroundService(Intent(context, BackgroundGuardService::class.java)); true }
            catch (_: RuntimeException) { false }
        }
        fun stopFromUser(context: Context) {
            check(preferences(context).edit().putBoolean("enabled", false).commit())
            context.stopService(Intent(context, BackgroundGuardService::class.java))
        }
    }
}
