package com.phoneuse.routine

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.phoneuse.R
import com.phoneuse.app.AppGraph
import com.phoneuse.app.QuietHours
import com.phoneuse.app.Source
import com.phoneuse.cards.PermissionCard
import com.phoneuse.i18n.tr
import com.phoneuse.notify.AppScreen
import com.phoneuse.notify.Notifier
import com.phoneuse.notify.NotifyText
import com.phoneuse.notify.Tap
import com.phoneuse.notify.Taps
import com.phoneuse.phone.AndroidCalendarSource
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.service.PhoneUseAccessibilityService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The calendar as BeforeEvent planning reads it; null when it may not be read. */
fun interface UpcomingEvents {
    fun between(from: Long, to: Long): List<UpcomingEvent>?
}

/**
 * Routines on Android: the store, the one exact alarm, geofences, and what happens when a trigger fires
 * ([RoutineEngine.shouldRun], then run, defer, or the "잠금 해제 후 실행" notification). It also delivers
 * remind_at reminders (ReminderScheduler.oneShot comes here) so they survive a reboot.
 * One per process ([get]); the device watcher, notification listener, alarm, boot and geofence receivers all
 * reach it through [RoutineEngine]'s entry points or [get].
 */
class RoutineSystem private constructor(private val app: Context) : RoutineHost {
    private val runtime = UnifiedRoutineRuntime.get(app)
    val store = runtime.store
    private val authority = runtime.authority
    private fun active(epoch: Long? = null) = store.ready && authority.allows(RoutineOwner.ROUTINE, epoch) && store.migrationId == authority.snapshot().migrationId
    val alarms = RoutineScheduler(app)
    val geofences = RoutineGeofences(app)
    private val calendar = UpcomingEvents { from, to ->
        val source = AndroidCalendarSource(app)
        if (!source.canRead()) return@UpcomingEvents null
        val zone = ZoneId.systemDefault()
        source.events(LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone), LocalDateTime.ofInstant(Instant.ofEpochMilli(to), zone)).map {
            UpcomingEvent(it.title, it.start.atZone(zone).toInstant().toEpochMilli(), it.location, it.allDay)
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "routine error", e) },
    )
    private val nativeHandoff: RoutineHandoff = RoutineHandoff(scope, acquireWake = {
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UnlockTime:routine-handoff")
        wake.acquire(100_000)
        AutoCloseable { if (wake.isHeld) wake.release() }
    }, onAvailable = { scope.launch { drainDeferred() } })
    private val eventLock = Mutex()
    private val tickLock = Mutex()
    private val drainLock = Mutex()

    // Pending notices and deferred work reference durable occurrences, including original event taint.

    /** Settings "방해하지 않는 시간": inside it, routine notifications arrive silently (QuietChannel). */
    @Volatile private var quietHours = QuietHours()

    private fun quietNow(): Boolean = quietHours.contains(java.time.LocalTime.now().let { it.hour * 60 + it.minute })

    private fun startWatching() {
        scope.launch {
            try {
                AppGraph.settings.quietHours.collect { quietHours = it }
            } catch (e: Exception) {
                Log.w(TAG, "reading quiet hours failed: ${e.javaClass.simpleName}")
            }
        }
        // Deferred runs go once the runner is free (persisted in the store, so they survive a process death).
        scope.launch {
            AppGraph.runner.running.collect { running -> if (!running) drainDeferred() }
        }
        // Not forced: geofences survive a process death, and re-adding them here would make the crossing that
        // started this process look like Play services' "already inside" report.
        scope.launch { resync() }
        store.brokenAside?.let { postBroken(brokenText(it)) }
    }

    /** What the user is told when routines.json could not be read. */
    fun brokenText(aside: File): String = tr(
        "Routines are paused because their saved state could not be verified. The original file ${aside.name} is preserved.",
        "루틴 파일을 읽지 못해 빈 목록으로 시작했어요. 예전 파일은 ${aside.name}로 남겨 뒀어요.",
    )

    // ---- scheduling ----------------------------------------------------------------------------

    /** remind_at: kept in the store, delivered by the one alarm. True when exact alarms are allowed. */
    fun oneShot(at: Instant, text: String): Boolean {
        if (!active()) return false
        store.addReminder(at.toEpochMilli(), text)
        setNextAlarm()
        return alarms.exactAllowed()
    }

    /**
     * Handles everything due, reads the calendar, registers geofences when the place routines changed (or
     * [force]: after a reboot, an update, or location coming back) and sets the next alarm.
     */
    suspend fun resync(force: Boolean = false) {
        if (!active()) return
        syncFences(force)
        refreshCalendar()
        tick()
    }

    private fun syncFences(force: Boolean) {
        if (!active()) return
        val routines = store.all()
        val key = RoutineGeofences.key(routines)
        if (!force && key == store.fenceKey) return
        // Paused with location still off: adding would only fail again; the retry tick and the broadcast come back.
        if (store.placesPaused && key.isNotEmpty() && !locationOn()) return
        if (key.isEmpty()) {
            geofences.clear()
            store.fenceKey = key
            store.placesPaused = false
            return
        }
        // Their initial "already inside" reports come right after: they only set the inside state (see onPlace).
        // A real arrival within that minute after an edit re-registered them is taken as one too (rare; accepted).
        store.fencesAt = System.currentTimeMillis()
        geofences.sync(
            routines,
            onAdded = {
                store.fenceKey = key
                store.placesPaused = false
            },
            onUnavailable = { store.placesPaused = true },
        )
    }

    /** Play services dropped every geofence (location was turned off): paused until location is back. */
    fun placesLost() {
        // Off the caller's (main) thread: both writes are synced to disk.
        scope.launch {
            store.placesPaused = true
            store.fenceKey = null
            // The retry alarm (every PLACES_RETRY_MS while paused): the PROVIDERS_CHANGED listener lives in the
            // accessibility service and is gone when that is off.
            setNextAlarm()
        }
    }

    /** Location providers changed (broadcast from the device watcher): if place routines were paused, register again. */
    fun locationChanged() {
        scope.launch {
            if (RoutinePlan.retryPlaces(store.placesPaused, locationOn())) resync(force = true)
        }
    }

    private fun locationOn(): Boolean = try {
        app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (e: Exception) {
        false
    }

    /**
     * A routine added, changed or removed on a screen: written off the main thread, then re-synced. False when
     * routines.json could not be written (the screen says "저장하지 못했어요").
     */
    suspend fun edit(block: RoutineStore.() -> Unit): Boolean = withContext(Dispatchers.IO) {
        runtime.migrationMutex.withLock {
            if (!active()) return@withLock false
            // Revoke persisted definition first. No due work under the old definition is dispatched here.
            val before = store.all()
            store.userEdit(block).also { saved -> if (saved) {
                before.filter { old -> store.get(old.id)?.definitionRevision != old.definitionRevision }.forEach {
                    nativeHandoff.cancel(it.id)
                    RoutineExecutionBridge.revokeRun(it.id)
                }
                changed()
            } }
        }
    }

    fun changed() { scope.launch { resync() } }

    suspend fun tick() = tickLock.withLock {
        if (!active()) return@withLock
        val epoch = authority.snapshot().epoch
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val dueReminders = store.reminders().filter { it.at <= nowMs }
        val shown = dueReminders.filter { active(epoch) && (postReminder(app, it.id.toInt(), it.text) || nowMs - it.at > REMINDER_KEEP_MS) }
        if (shown.isNotEmpty()) store.removeReminders(shown.mapTo(HashSet()) { it.id })
        // Definition time is only used to arm; durable occurrence identity decides whether to run.
        store.armTimes(now.toInstant(), now.zone, epoch)
        val (dueFires, rest) = store.fires.partition { it.at <= nowMs }
        // Persist an occurrence before removing a planned calendar fire.
        for (f in dueFires) if (nowMs - f.at <= Routine.MAX_LATENESS_MS && f.startsAt > nowMs) {
            store.get(f.routineId)?.let { r -> store.enqueue(r, RoutineEvent.EventSoon(f.routineId, f.title, f.location, f.startsAt), f.at, epoch, now.zone) }
        }
        if (dueFires.isNotEmpty()) store.fires = rest
        if (store.calendarAt?.let { it <= nowMs } == true) refreshCalendarLocked()
        for (o in store.pending(epoch).filter { it.dueAt <= nowMs }) eventLock.withLock { dispatch(o) }
        store.armTimes(Instant.now(), now.zone, epoch)
        store.checkedAt = nowMs // Diagnostic only; never evidence of claim or completion.
        if (RoutinePlan.retryPlaces(store.placesPaused, locationOn())) syncFences(force = true)
        setNextAlarm()
    }

    private fun setNextAlarm() {
        if (!active()) { alarms.set(null); return }
        val epoch = authority.snapshot().epoch
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val times = store.armTimes(now.toInstant(), now.zone, epoch).map {
            // A locked/busy pending run waits for a real event or expiry, never a hot alarm loop.
            if (it.dueAt > nowMs) it.dueAt else it.dueAt + Routine.MAX_LATENESS_MS + 1
        } + store.reminders().map { if (it.at > nowMs) it.at else nowMs + 60_000 } +
            store.fires.map { it.at } + listOfNotNull(store.calendarAt, RoutinePlan.placesRetryAt(store.placesPaused, nowMs))
        alarms.set(times.minOrNull()?.let(Instant::ofEpochMilli))
    }

    /** Reads the calendar for BeforeEvent routines (daily, on calendar changes and on resync). */
    suspend fun refreshCalendar() = tickLock.withLock { refreshCalendarLocked() }

    /** The calendar changed (debounced by the device watcher). */
    fun calendarChanged() {
        scope.launch { refreshCalendar() }
    }

    /** Under [tickLock], so a tick never writes back fires a refresh just replaced. */
    private fun refreshCalendarLocked() {
        if (!active()) return
        val routines = store.all()
        if (!RoutinePlan.needsCalendar(routines)) {
            store.fires = emptyList()
            store.calendarAt = null
            return
        }
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val events = try {
            calendar.between(nowMs, nowMs + RoutinePlan.EVENT_HORIZON_MS)
        } catch (e: Exception) {
            Log.w(TAG, "calendar read failed: ${e.javaClass.simpleName}")
            null
        }
        store.fires = if (events == null) emptyList() else RoutinePlan.eventFires(routines, events, nowMs)
        store.calendarAt = RoutinePlan.nextCalendarRefresh(now).toInstant().toEpochMilli()
        setNextAlarm()
    }

    // ---- triggers ------------------------------------------------------------------------------

    override fun onEvent(event: RoutineEvent) {
        if (!active()) return
        scope.launch { handle(event) }
    }

    /** A geofence report. */
    suspend fun onPlace(routineId: Long, edge: PlaceEdge) {
        if (!active()) return
        // Only an ENTER right after registering can be Play services' initial report.
        val initial = edge == PlaceEdge.ENTER && System.currentTimeMillis() - (store.fencesAt ?: 0L) < INITIAL_REPORT_MS
        if (initial) {
            // Registering reports "already inside": remember it, but do not treat it as an arrival.
            eventLock.withLock { store.inside = RoutineEngine.afterCrossing(store.inside, routineId, edge) }
        } else {
            handle(RoutineEvent.PlaceCrossed(routineId, edge))
        }
    }

    /**
     * "지금 실행" ([fromLockedNotice] false: a plain manual run), and the "잠금 해제 후 실행" notification (the run
     * keeps what triggered it, so a notification-started run keeps its limits).
     */
    fun runNow(routineId: Long, fromLockedNotice: Boolean = false, noticeToken: String? = null, noticeEpoch: Long? = null) {
        scope.launch {
            eventLock.withLock {
                if (!active()) return@withLock
                val r = store.get(routineId)?.takeIf { it.enabled } ?: return@withLock
                if (fromLockedNotice) {
                    if (noticeToken == null || noticeEpoch == null || !active(noticeEpoch)) return@withLock
                    val original = store.pending(noticeEpoch).firstOrNull { it.routineId == r.id && it.token == noticeToken } ?: return@withLock
                    dispatch(original) // Never convert missing/expired notice context into Manual.
                } else decide(r, RoutineEvent.Manual)
            }
            setNextAlarm()
        }
    }

    /** Drafts must be saved before they can create durable device-operation leases. */
    fun tryOnce(routine: Routine): Boolean {
        val current = store.get(routine.id)
        if (current == null || current.definitionRevision != routine.definitionRevision ||
            RoutineJson.encode(current, true) != RoutineJson.encode(routine, true)) {
            return false
        }
        runNow(current.id)
        return true
    }

    private suspend fun handle(event: RoutineEvent) = eventLock.withLock {
        if (!active()) return@withLock
        val stateBefore = store.inside
        for (r in store.all()) decide(r, event)
        if (event is RoutineEvent.PlaceCrossed) store.inside = RoutineEngine.afterCrossing(stateBefore, event.routineId, event.edge)
        setNextAlarm()
    }

    private fun state() = EngineState(
        runnerBusy = AppGraph.runner.running.value || RoutineExecutionBridge.busy() || nativeHandoff.isBusy,
        locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
        insidePlaces = store.inside, ownPackage = app.packageName,
        autoUnlockEnabled = RoutineExecutionBridge.autoUnlockEnabled(),
    )

    private suspend fun decide(r: Routine, event: RoutineEvent) {
        if (!active()) return
        val decision = RoutineEngine.shouldRun(r, event, ZonedDateTime.now(), state())
        if (decision == Decision.SKIP_COOLDOWN || decision == Decision.SKIP_CONDITION) return
        val o = store.enqueue(r, event, System.currentTimeMillis(), authority.snapshot().epoch, ZoneId.systemDefault()) ?: return
        dispatch(o)
    }

    private suspend fun dispatch(o: RoutineOccurrence) {
        if (!active(o.authorityEpoch)) return
        if (o.event == null && !alarms.exactAllowed()) return
        val r = store.get(o.routineId)?.takeIf { it.enabled && it.definitionRevision == o.revision } ?: return
        val now = System.currentTimeMillis()
        if (now < o.dueAt) return
        if (now - o.dueAt > Routine.MAX_LATENESS_MS) { store.claim(o.token, now, o.authorityEpoch); return }
        val event = o.event?.event ?: RoutineEvent.TimeDue(r.id)
        when (RoutineEngine.shouldRun(r, event, ZonedDateTime.now(), state())) {
            Decision.RUN -> {
                val native = r.action is RoutineAction.OpenAppsSequence || r.action is RoutineAction.OpenApp
                val reservation = if (native) nativeHandoff.reserve(r.id) ?: return else null
                var handedOff = false
                try {
                    val claimed = runtime.migrationMutex.withLock {
                        if (active(o.authorityEpoch)) store.claim(o.token, now, o.authorityEpoch) else null
                    } ?: return
                    val (claimedRoutine, claim) = claimed
                    if (!active(o.authorityEpoch)) return
                    if (reservation != null) {
                        handedOff = nativeHandoff.submit(reservation) { execute(claimedRoutine, event, claim) }
                    } else execute(claimedRoutine, event, claim)
                } finally {
                    // A committed-but-unstarted claim remains IN_PROGRESS on abrupt process death.
                    if (reservation != null && !handedOff) nativeHandoff.release(reservation)
                }
            }
            Decision.NOTIFY_LOCKED -> {
                notifyLocked(r, o)
                record(r, if (canPost()) "WAITING_FOR_MANUAL_UNLOCK" else "NOTIFICATION_PERMISSION_REQUIRED")
            }
            Decision.DEFER -> Unit // Still PENDING with original due/expiry/revision/event.
            Decision.SKIP_COOLDOWN, Decision.SKIP_CONDITION -> Unit
        }
    }

    private suspend fun drainDeferred() = drainLock.withLock {
        if (!active()) return@withLock
        val epoch = authority.snapshot().epoch
        for (o in store.pending(epoch).filter { it.dueAt <= System.currentTimeMillis() }) {
            if (AppGraph.runner.running.value || RoutineExecutionBridge.busy() || nativeHandoff.isBusy) break
            eventLock.withLock { dispatch(o) }
        }
        setNextAlarm()
    }

    private suspend fun execute(r: Routine, event: RoutineEvent, occurrence: RoutineOccurrence) {
        fun current() = active(occurrence.authorityEpoch) && store.current(r, occurrence, occurrence.authorityEpoch)
        if (!current()) return
        val now = System.currentTimeMillis()
        val triggered = event != RoutineEvent.Manual
        store.update(r.id) { if (triggered) it.copy(lastRunAt = now, cooldownFrom = now) else it.copy(lastRunAt = now) }
        if (r.action is RoutineAction.Ask && triggered && AppGraph.runner.planPaused()) {
            skipPaused(r)
            store.record(org.localwake.app.execution.ExecutionRecord(occurrence.token, r.title, now, "PLAN_PAUSED"))
            return
        }
        try {
            when (val a = r.action) {
                is RoutineAction.OpenAppsSequence -> {
                    val task = requireNotNull(r.localTask(occurrence))
                    val result = RoutineExecutionBridge.executeNative?.invoke(r.id, task, occurrence.token, ::current)
                        ?: org.localwake.app.execution.ExecutionRecord(occurrence.token, r.title, now, "EXECUTOR_UNAVAILABLE")
                    store.record(result.copy(id = occurrence.token, name = r.title))
                    record(r, result.code)
                }
                is RoutineAction.Notify -> {
                    if (current()) post(r, a.text)
                    record(r, a.text)
                    store.record(org.localwake.app.execution.ExecutionRecord(occurrence.token, r.title, now, if (canPost()) "SUCCESS" else "NOTIFICATION_PERMISSION_REQUIRED"))
                }
                is RoutineAction.OpenApp -> {
                    val opened = RoutineExecutionBridge.executeOpenApp?.invoke(a.packageName, ::current) == true
                    val result = if (opened) "SUCCESS" else "APP_NOT_OBSERVED_FOREGROUND"
                    record(r, result)
                    store.record(org.localwake.app.execution.ExecutionRecord(occurrence.token, r.title, now, result))
                }
                is RoutineAction.Ask -> ask(r, a, event, occurrence, ::current)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            // A write failure leaves the original IN_PROGRESS intact. Never requeue an unknown side effect.
            runCatching { store.record(org.localwake.app.execution.ExecutionRecord(occurrence.token, r.title, now, "EXECUTION_FAILED")) }
        }
    }

    private suspend fun ask(r: Routine, a: RoutineAction.Ask, event: RoutineEvent, occurrence: RoutineOccurrence, current: () -> Boolean) {
        if (!current()) return
        val chats = AppGraph.chats
        val existing = r.conversationId?.takeIf { id -> chats.conversations().first().any { it.id == id } }
        val conversation = existing ?: chats.createConversation(tr("Routine · ${r.title}", "루틴 · ${r.title}").take(TITLE_CHARS))
        if (existing == null) store.update(r.id) { it.copy(conversationId = conversation) }
        val run = RoutineRun.of(r, event).copy(stillCurrent = current, onResult = { result ->
            check(store.recordAgentResult(r, occurrence, result, System.currentTimeMillis())) { "ROUTINE_RESULT_WRITE_FAILED" }
        })
        val text = RoutinePrompt.build(r, a.prompt, event, fixedSend = run.fixedSend)
        if (!current() || !AppGraph.runner.send(conversation, text, Source.ROUTINE, routine = run)) {
            check(store.recordAgentResult(r, occurrence,
                RoutineResult(com.phoneuse.app.RunOutcome.FAILED, tr("Task did not start", "작업이 시작되지 않았어요")),
                System.currentTimeMillis())) { "ROUTINE_RESULT_WRITE_FAILED" }
        }
    }

    /** Skipped while the plan is paused after a usage limit. Told once: a routine that keeps firing doesn't keep notifying. */
    private fun skipPaused(r: Routine) {
        val text = tr(
            "Didn't run: paused after reaching the usage limit for your ChatGPT plan or this app",
            "실행하지 않았어요: ChatGPT 요금제 또는 이 앱의 사용량 한도에 도달해서 잠시 멈췄어요",
        )
        if (r.lastResult == text) return
        post(r, text, manageUsage = true)
        record(r, text)
    }

    private fun record(r: Routine, result: String) {
        if (r.id == 0L) return
        store.update(r.id) { it.copy(lastResult = result.lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()?.take(RESULT_CHARS) ?: result.take(RESULT_CHARS)) }
    }

    // ---- notifications -------------------------------------------------------------------------

    private fun canPost(): Boolean = Notifier.canPost(app)

    /**
     * A routine's own notification (Notify action, a skipped run). Its message may be Markdown the model wrote;
     * tapping it opens the routine's conversation, or the routines list before it has one.
     */
    private fun post(r: Routine, text: String, manageUsage: Boolean = false) {
        if (!canPost()) return
        val plain = NotifyText.plain(text).ifBlank { text }
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(NotifyText.title(r.title, tr("Routine", "루틴")))
            .setContentText(NotifyText.firstLine(plain))
            .setStyle(Notification.BigTextStyle().bigText(NotifyText.clip(plain, NotifyText.BIG_CHARS)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(Notifier.open(app, Taps.routine(r.conversationId)))
            .setAutoCancel(true)
            .apply { if (manageUsage) addAction(Notifier.manageUsageAction(app)) }
            .build()
        Notifier.post(app, TAG_ROUTINE, r.id.toInt(), n)
    }

    /** routines.json was unreadable and moved aside; tapping it opens the routines list. */
    private fun postBroken(text: String) {
        if (!canPost()) return
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(tr("Routine", "루틴"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(Notifier.open(app, Tap.Screen(AppScreen.ROUTINES)))
            .setAutoCancel(true)
            .build()
        Notifier.post(app, TAG_ROUTINE, 0, n)
    }

    /** "잠금 해제 후 실행": tapping it (after unlocking) runs the routine. */
    private fun notifyLocked(r: Routine, occurrence: RoutineOccurrence) {
        if (!canPost() || r.id == 0L) return
        val run = PendingIntent.getActivity(
            app, r.id.toInt(), RoutineRunActivity.intent(app, r.id, occurrence.token, occurrence.authorityEpoch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notifier.builder(app, Notifier.Channel.ROUTINES, quietNow())
            .setContentTitle(tr("Runs after unlock · ${r.title}", "잠금 해제 후 실행 · ${r.title}"))
            .setOnlyAlertOnce(true)
            .setContentText(tr("This routine operates the phone, so it will run once you unlock. Tap to run", "폰을 조작하는 루틴이라 잠금을 풀면 실행할게요. 눌러서 실행"))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(run)
            .addAction(Notification.Action.Builder(null as Icon?, tr("Run", "실행"), run).build())
            .setAutoCancel(true)
            .build()
        Notifier.post(app, TAG_ROUTINE, r.id.toInt(), n)
    }

    /** The permission card a routine still needs, if any. */
    fun missingPermission(r: Routine): PermissionCard? =
        RoutinePermissions.firstMissing(r, Build.VERSION.SDK_INT) {
            if (it == RoutinePermissions.LISTENER) {
                PhoneUseNotificationListener.enabled(app)
            } else {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** Why [r] cannot work even with its permissions (accessibility service off, location off), if anything. */
    fun warning(r: Routine): String? =
        if (!active()) "Routines paused: migration or recovery required" else if (!alarms.exactAllowed() && (r.trigger is Trigger.Time || r.trigger is Trigger.Once)) "Allow exact alarms to run this routine on time" else RoutinePermissions.warning(r, accessibilityOn = PhoneUseAccessibilityService.instance != null, placesPaused = store.placesPaused)

    companion object {
        private const val TAG = "Routines"
        const val CHANNEL_ID = "phoneuse_routines"
        private const val TAG_ROUTINE = "routine"
        private const val TITLE_CHARS = 30
        private const val RESULT_CHARS = 120
        private const val INITIAL_REPORT_MS = 60_000L
        private const val NOTICE_REPEAT_MS = 10 * 60_000L
        private const val REMINDER_KEEP_MS = 24 * 60 * 60_000L

        @Volatile private var instance: RoutineSystem? = null

        /** Shared device lease released (including settings and standalone tools, not only AgentRunner). */
        fun deviceAvailable() {
            instance?.let { system -> system.scope.launch { system.drainDeferred() } }
        }

        /** The process's routine system; the first call starts it (alarms, geofences, deferred runs). */
        fun get(context: Context): RoutineSystem =
            instance ?: synchronized(this) {
                instance ?: RoutineSystem(context.applicationContext).also {
                    instance = it
                    RoutineEngine.host = it
                    it.startWatching()
                }
            }

        /** Posts a remind_at reminder on the "알림" channel (with a bell, not the app's mark); tapping it opens the app. */
        fun postReminder(context: Context, id: Int, text: String): Boolean {
            if (!AlarmReminderScheduler.notificationsOn(context)) return false
            val notification = Notifier.builder(context, Notifier.Channel.REMINDERS)
                .setSmallIcon(R.drawable.ic_unlocktime)
                .setContentTitle(tr("Reminder", "알림"))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(Notifier.open(context, Tap.Screen(AppScreen.HOME)))
                .setAutoCancel(true)
                .build()
            Notifier.post(context, "reminder", id, notification)
            return true
        }
    }
}
