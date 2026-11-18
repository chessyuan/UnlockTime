package com.phoneuse.routine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.localwake.app.execution.AppLaunchResult
import org.localwake.app.execution.ExecutionRecord
import org.localwake.app.scheduler.LocalState
import org.localwake.app.scheduler.ScheduleModel
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The sole transactional definition, occurrence, history and credential-guard authority. */
class RoutineStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    // Initialized BEFORE load: corruption must never be reset by property initialization.
    private var readOnly = false
    var recoveryError: String? = null
        private set
    var brokenAside: File? = null
        private set
    val ready: Boolean get() = synchronized(lock) { !readOnly }
    private data class Data(
        val nextId: Long = 1, val routines: List<Routine> = emptyList(),
        val reminders: List<Reminder> = emptyList(), val inside: Set<Long> = emptySet(),
        val checkedAt: Long? = null, val fires: List<EventFire> = emptyList(),
        val calendarAt: Long? = null, val deferred: List<DeferredRun> = emptyList(),
        val fenceKey: String? = null, val fencesAt: Long? = null, val placesPaused: Boolean = false,
        val occurrences: List<RoutineOccurrence> = emptyList(),
        val history: List<ExecutionRecord> = emptyList(), val patternBlocked: Boolean = false,
        val migrationId: String? = null,
    )
    private var data = load()
    private var transaction: Data? = null
    private var userEditTime: java.time.ZonedDateTime? = null
    private val _routines = MutableStateFlow(data.routines)
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()
    private val _paused = MutableStateFlow(data.placesPaused)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _localStates = MutableStateFlow(project(data))
    val localStates: StateFlow<LocalState> = _localStates.asStateFlow()
    private fun current() = transaction ?: data
    fun all(): List<Routine> = synchronized(lock) { current().routines }
    fun get(id: Long): Routine? = synchronized(lock) { current().routines.firstOrNull { it.id == id } }
    fun occurrences(): List<RoutineOccurrence> = synchronized(lock) { current().occurrences }
    fun localSnapshot(): LocalState = synchronized(lock) { project(current()) }
    val migrationId: String? get() = synchronized(lock) { current().migrationId }
    val patternBlocked: Boolean get() = synchronized(lock) { current().patternBlocked }
    private fun project(d: Data) = LocalState(d.routines.mapNotNull { r -> r.localTask(d.occurrences.firstOrNull { it.routineId == r.id && it.status == OccurrenceStatus.PENDING }) }, d.history, d.patternBlocked)

    /** Create requires id=0. Updating a deleted/stale draft never re-creates it. */
    fun save(routine: Routine): Routine = edit { d ->
        val old = d.routines.firstOrNull { it.id == routine.id }
        if (routine.id == 0L) {
            require(d.routines.size < Routine.MAX_ROUTINES) { "MAX_ROUTINES" }
            val r = routine.copy(id = d.nextId, definitionRevision = UUID.randomUUID().toString(), legacyId = null, lastClaimedLocalDate = null)
            validate(r)
            d.copy(nextId = d.nextId + 1, routines = d.routines + r) to r
        } else {
            require(old != null && old.definitionRevision == routine.definitionRevision) { "STALE_DRAFT" }
            val rearmToday = userEditTime?.let {
                RoutineTimeEditPolicy.canRearmToday(old, routine, it, d.checkedAt, d.occurrences, d.history)
            } == true
            val r = routine.copy(definitionRevision = UUID.randomUUID().toString(), enabled = old.enabled, lastRunAt = old.lastRunAt,
                cooldownFrom = old.cooldownFrom, lastResult = old.lastResult, conversationId = old.conversationId,
                lastClaimedLocalDate = if (rearmToday) null else old.lastClaimedLocalDate, legacyId = old.legacyId)
            validate(r)
            revoke(d.copy(routines = d.routines.map { if (it.id == r.id) r else it }), r.id) to r
        }
    }
    fun update(id: Long, change: (Routine) -> Routine): Routine? = edit { d ->
        val old = d.routines.firstOrNull { it.id == id } ?: return@edit d to null
        var r = change(old).copy(id = id)
        val definitionChanged = definition(old) != definition(r)
        if (definitionChanged) r = r.copy(definitionRevision = UUID.randomUUID().toString(), lastClaimedLocalDate = old.lastClaimedLocalDate)
        validate(r)
        val next = d.copy(routines = d.routines.map { if (it.id == id) r else it })
        (if (definitionChanged) revoke(next, id) else next) to r
    }
    private fun definition(r: Routine) = r.copy(id = 0, definitionRevision = "", lastRunAt = null, cooldownFrom = null,
        lastResult = null, conversationId = null, lastClaimedLocalDate = null)
    private fun revoke(d: Data, id: Long) = d.copy(
        occurrences = d.occurrences.map { if (it.routineId == id && it.status in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.CLAIMED)) it.copy(status = OccurrenceStatus.REVOKED) else it },
        deferred = d.deferred.filterNot { it.routineId == id })
    fun delete(id: Long) = edit { d -> revoke(d.copy(routines = d.routines.filterNot { it.id == id }, inside = d.inside - id, fires = d.fires.filterNot { it.routineId == id }), id) to Unit }
    fun reminders(): List<Reminder> = synchronized(lock) { current().reminders }
    fun addReminder(at: Long, text: String): Reminder = edit { d -> val r = Reminder(d.nextId, at, text); d.copy(nextId = d.nextId + 1, reminders = d.reminders + r) to r }
    fun removeReminders(ids: Set<Long>) = edit { d -> d.copy(reminders = d.reminders.filterNot { it.id in ids }) to Unit }
    var inside: Set<Long>
        get() = synchronized(lock) { current().inside }
        set(v) { edit { it.copy(inside = v) to Unit } }
    var checkedAt: Long?
        get() = synchronized(lock) { current().checkedAt }
        // Preserve the observed wall-clock high-water mark; a tick must not hide a known rollback.
        set(v) { edit { it.copy(checkedAt = listOfNotNull(it.checkedAt, v).maxOrNull()) to Unit } }
    var fires: List<EventFire>
        get() = synchronized(lock) { current().fires }
        set(v) { edit { it.copy(fires = v) to Unit } }
    var calendarAt: Long?
        get() = synchronized(lock) { current().calendarAt }
        set(v) { edit { it.copy(calendarAt = v) to Unit } }
    var deferred: List<DeferredRun>
        get() = synchronized(lock) { current().deferred }
        set(v) { edit { it.copy(deferred = v) to Unit } }
    fun <T> editDeferred(change: (List<DeferredRun>) -> Pair<List<DeferredRun>, T>): T = edit { d -> val (q, r) = change(d.deferred); d.copy(deferred = q) to r }
    var fenceKey: String?
        get() = synchronized(lock) { current().fenceKey }
        set(v) { edit { it.copy(fenceKey = v) to Unit } }
    var fencesAt: Long?
        get() = synchronized(lock) { current().fencesAt }
        set(v) { edit { it.copy(fencesAt = v) to Unit } }
    var placesPaused: Boolean
        get() = synchronized(lock) { current().placesPaused }
        set(v) { edit { it.copy(placesPaused = v) to Unit } }

    /** Single write for the entire user operation, with no partially published state. */
    fun userEdit(block: RoutineStore.() -> Unit): Boolean = synchronized(lock) {
        if (readOnly || transaction != null) return@synchronized false
        transaction = data
        try {
            userEditTime = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault())
            block()
            val next = requireNotNull(transaction)
            if (next != data) { write(next); publish(next) }
            true
        } catch (_: IOException) { false }
        catch (_: IllegalArgumentException) { false }
        finally { transaction = null; userEditTime = null }
    }
    private fun <T> edit(change: (Data) -> Pair<Data, T>): T = synchronized(lock) {
        if (readOnly) throw IOException("ROUTINE_RECOVERY_REQUIRED")
        val base = current()
        val (next, result) = change(base)
        if (next != base) {
            if (transaction != null) transaction = next else { write(next); publish(next) }
        }
        result
    }
    private fun publish(d: Data) { data = d; _routines.value = d.routines; _paused.value = d.placesPaused; _localStates.value = project(d) }

    /** Persist time occurrences before AlarmManager registration; retain due tokens across reconnection. */
    fun armTimes(now: Instant, zone: ZoneId, epoch: Long): List<RoutineOccurrence> = edit { d ->
        var occurrences = d.occurrences
        for (r in d.routines) {
            if (!r.enabled || (r.trigger !is Trigger.Time && r.trigger !is Trigger.Once)) continue
            val existing = occurrences.firstOrNull { it.routineId == r.id && it.revision == r.definitionRevision && it.authorityEpoch == epoch && it.status == OccurrenceStatus.PENDING && (it.event == null || it.event.event is RoutineEvent.TimeDue) }
            if (existing != null) {
                if (r.trigger !is Trigger.Once && existing.zoneAtArm != zone.id && existing.dueAt > now.toEpochMilli()) {
                    occurrences = occurrences.map { if (it.token == existing.token) it.copy(status = OccurrenceStatus.REVOKED) else it }
                } else continue
            }
            val at = NextRun.compute(r, now.atZone(zone))?.toInstant() ?: continue
            occurrences = occurrences + RoutineOccurrence(UUID.randomUUID().toString(), r.id, r.definitionRevision, at.toEpochMilli(), at.atZone(zone).toLocalDate().toString(), zone.id, epoch)
        }
        d.copy(occurrences = compact(occurrences)) to occurrences.filter { it.status == OccurrenceStatus.PENDING && it.authorityEpoch == epoch }
    }
    fun pending(epoch: Long): List<RoutineOccurrence> = occurrences().filter { it.status == OccurrenceStatus.PENDING && it.authorityEpoch == epoch }
    fun enqueue(r: Routine, event: RoutineEvent, now: Long, epoch: Long, zone: ZoneId): RoutineOccurrence? = edit { d ->
        val current = d.routines.firstOrNull { it.id == r.id && it.enabled && it.definitionRevision == r.definitionRevision } ?: return@edit d to null
        d.occurrences.firstOrNull { it.routineId == r.id && it.revision == r.definitionRevision && it.authorityEpoch == epoch && it.status == OccurrenceStatus.PENDING &&
            ((event == RoutineEvent.Manual) == (it.event?.event == RoutineEvent.Manual)) }?.let { return@edit d to it }
        val o = RoutineOccurrence(UUID.randomUUID().toString(), r.id, r.definitionRevision, now, Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(), zone.id, epoch,
            event = DeferredRun(current.id, event, now))
        d.copy(occurrences = compact(d.occurrences + o)) to o
    }
    /** Claims and IN_PROGRESS history commit together; one-shot consumption does not revoke this lease. */
    fun claim(token: String, now: Long, epoch: Long): Pair<Routine, RoutineOccurrence>? = edit { d ->
        val o = d.occurrences.firstOrNull { it.token == token && it.authorityEpoch == epoch && it.status == OccurrenceStatus.PENDING } ?: return@edit d to null
        val r = d.routines.firstOrNull { it.id == o.routineId && it.enabled && it.definitionRevision == o.revision } ?: return@edit d to null
        if (now < o.dueAt) return@edit d to null
        if (now - o.dueAt > Routine.MAX_LATENESS_MS) {
            val missed = ExecutionRecord(o.token, r.title, now, "MISSED_WINDOW")
            return@edit d.copy(occurrences = d.occurrences.map { if (it.token == token) it.copy(status = OccurrenceStatus.MISSED_WINDOW) else it },
                history = (listOf(missed) + d.history).take(100)) to null
        }
        val timed = o.event == null || o.event.event is RoutineEvent.TimeDue
        if (timed && r.lastClaimedLocalDate?.let { o.localDate <= it } == true) return@edit revoke(d, r.id) to null
        val claimed = o.copy(status = OccurrenceStatus.CLAIMED)
        val updated = r.copy(enabled = if (r.trigger is Trigger.Once && timed) false else r.enabled,
            lastClaimedLocalDate = if (timed) o.localDate else r.lastClaimedLocalDate)
        d.copy(routines = d.routines.map { if (it.id == r.id) updated else it },
            occurrences = d.occurrences.map { if (it.token == token) claimed else it },
            history = (listOf(ExecutionRecord(token, r.title, now, "IN_PROGRESS")) + d.history).take(100)) to (updated to claimed)
    }
    fun current(r: Routine, occurrence: RoutineOccurrence, epoch: Long): Boolean = synchronized(lock) {
        !readOnly && epoch == occurrence.authorityEpoch && current().routines.any { it.id == r.id && it.definitionRevision == occurrence.revision && (it.enabled || it.trigger is Trigger.Once) } &&
            current().occurrences.any { it.token == occurrence.token && it.status == OccurrenceStatus.CLAIMED && it.authorityEpoch == epoch }
    }
    fun stillCurrent(task: ScheduleModel, epoch: Long): Boolean = synchronized(lock) {
        val r = current().routines.firstOrNull { (it.legacyId ?: "routine-${it.id}") == task.id } ?: return@synchronized false
        val o = current().occurrences.firstOrNull { it.token == task.armedToken } ?: return@synchronized false
        task.revision == r.definitionRevision && current(r, o, epoch)
    }
    fun beginCredentialAttempt(task: ScheduleModel? = null, epoch: Long? = null): Boolean = edit { d ->
        if (d.patternBlocked || (task != null && (epoch == null || !stillCurrent(task, epoch)))) d to false
        else d.copy(patternBlocked = true) to true
    }
    fun setPatternBlocked(value: Boolean) = edit { it.copy(patternBlocked = value) to Unit }
    fun record(record: ExecutionRecord) = edit { d ->
        d.copy(history = (listOf(record.copy(trace = record.trace.take(80))) + d.history.filterNot { it.id == record.id }).take(100),
            occurrences = d.occurrences.map { if (it.token == record.id && it.status == OccurrenceStatus.CLAIMED) it.copy(status = OccurrenceStatus.FINISHED) else it }) to Unit
    }
    fun clearHistory() = edit { it.copy(history = it.history.filter { record -> record.code == "IN_PROGRESS" }) to Unit }
    /** Recovery may re-fence pending work, but never turns CLAIMED/unknown work back into pending. */
    internal fun rebindPendingAuthority(epoch: Long) = edit { d ->
        d.copy(occurrences = d.occurrences.map { if (it.status == OccurrenceStatus.PENDING) it.copy(authorityEpoch = epoch) else it }) to Unit
    }
    /** Inactive target only. Import is one transaction, and an idempotent migration id is required. */
    internal fun importLegacy(state: LocalState, id: String, epoch: Long, zone: ZoneId): Map<String, Long> = edit { d ->
        if (d.migrationId == id) return@edit d to d.routines.mapNotNull { r -> r.legacyId?.let { it to r.id } }.toMap()
        require(d.migrationId == null && state.schedules.size + d.routines.size <= Routine.MAX_ROUTINES)
        require(d.routines.none { it.legacyId != null })
        val imported = state.schedules.mapIndexed { index, task -> task.asRoutine(d.nextId + index) }
        imported.forEach(::validate)
        val occurrences = state.schedules.zip(imported).mapNotNull { (task, r) ->
            if (task.armedToken == null) null else RoutineOccurrence(task.armedToken, r.id, task.revision, requireNotNull(task.armedAt), requireNotNull(task.armedDate), zone.id, epoch)
        }
        d.copy(nextId = d.nextId + imported.size, routines = d.routines + imported, occurrences = d.occurrences + occurrences,
            history = state.history, patternBlocked = state.patternBlocked, migrationId = id) to imported.associate { requireNotNull(it.legacyId) to it.id }
    }
    private fun compact(items: List<RoutineOccurrence>) = items.filter { it.status in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.CLAIMED) } + items.filter { it.status !in setOf(OccurrenceStatus.PENDING, OccurrenceStatus.CLAIMED) }.takeLast(100)
    fun contentHash(): String = synchronized(lock) { if (readOnly) throw IOException("ROUTINE_RECOVERY_REQUIRED"); DurableRoutineFile.hash(file.readBytes()) }
    fun verifyReopened(): Boolean = synchronized(lock) { val other = RoutineStore(file, clock); other.ready && other.data == data }

    private fun validate(r: Routine) {
        require(r.id > 0 && r.definitionRevision.isNotBlank())
        require(RoutineJson.parse(RoutineJson.encode(r), false) is RoutineParse.Ok)
        r.lastClaimedLocalDate?.let(java.time.LocalDate::parse)
    }
    private fun load(): Data {
        if (!file.exists()) return Data()
        return try { decode(Json.parseToJsonElement(file.readText()).jsonObject) }
        catch (_: Exception) {
            // Original bytes remain exactly where they were. Never recover by starting an executable empty table.
            readOnly = true; recoveryError = "ROUTINE_RECOVERY_REQUIRED"; brokenAside = file
            Data(patternBlocked = true)
        }
    }
    private fun decode(o: JsonObject): Data {
        val version = o.getValue("version").jsonPrimitive.int
        require(version == 1 || version == 2)
        fun array(k: String) = o.getValue(k).jsonArray
        fun number(k: String) = o[k]?.jsonPrimitive?.longOrNull
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val routines = array("routines").map { requireNotNull((RoutineJson.parse(it.jsonObject, false) as? RoutineParse.Ok)?.routine) }
        require(routines.size <= Routine.MAX_ROUTINES && routines.map { it.id }.distinct().size == routines.size)
        require(routines.mapNotNull { it.legacyId }.let { it.distinct().size == it.size })
        routines.forEachIndexed { index, r ->
            if (version == 2) require(array("routines")[index].jsonObject.keys.containsAll(setOf("id", "title", "trigger", "conditions", "action", "enabled", "definition_revision", "last_claimed_date", "legacy_id")))
            validate(r)
        }
        val reminders = array("reminders").map { e -> val r = e.jsonObject; Reminder(r.getValue("id").jsonPrimitive.long, r.getValue("at").jsonPrimitive.long, r.getValue("text").jsonPrimitive.content) }
        require((routines.map { it.id } + reminders.map { it.id }).distinct().size == routines.size + reminders.size)
        val occurrences = if (version == 2) array("occurrences").map { RoutineOccurrence.read(it.jsonObject) } else emptyList()
        require(occurrences.map { it.token }.distinct().size == occurrences.size)
        for (p in occurrences.filter { it.status == OccurrenceStatus.PENDING || it.status == OccurrenceStatus.CLAIMED }) require(routines.any { it.id == p.routineId && it.definitionRevision == p.revision })
        val history = if (version == 2) array("history").map { readRecord(it.jsonObject) } else emptyList()
        require(history.size <= 100)
        val nextId = o.getValue("next_id").jsonPrimitive.long
        require(nextId > ((routines.map { it.id } + reminders.map { it.id }).maxOrNull() ?: 0))
        return Data(nextId, routines, reminders, array("inside").map { it.jsonPrimitive.long }.toSet(), number("checked_at"),
            array("fires").map { e -> val f = e.jsonObject; EventFire(f.getValue("routine_id").jsonPrimitive.long, f.getValue("at").jsonPrimitive.long, f.getValue("title").jsonPrimitive.content, f.getValue("location").jsonPrimitive.content, f.getValue("starts_at").jsonPrimitive.long) },
            number("calendar_at"), array("deferred").map { requireNotNull(DeferredRun.read(it.jsonObject)) }, str("fence_key"), number("fences_at"),
            o.getValue("places_paused").jsonPrimitive.boolean, occurrences, history,
            if (version == 2) o.getValue("pattern_blocked").jsonPrimitive.boolean else true, str("migration_id"))
    }
    private fun readRecord(o: JsonObject) = ExecutionRecord(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
        o.getValue("at").jsonPrimitive.long, o.getValue("code").jsonPrimitive.content,
        o.getValue("apps").jsonArray.map { val a = it.jsonObject; AppLaunchResult(a.getValue("package").jsonPrimitive.content, a.getValue("code").jsonPrimitive.content) },
        o.getValue("trace").jsonArray.map { it.jsonPrimitive.content }.also { require(it.size <= 80) })
    private fun write(d: Data) {
        val json = buildJsonObject {
            put("version", 2); put("next_id", d.nextId); put("routines", JsonArray(d.routines.map { RoutineJson.encode(it) }))
            put("reminders", JsonArray(d.reminders.map { buildJsonObject { put("id", it.id); put("at", it.at); put("text", it.text) } }))
            put("inside", JsonArray(d.inside.sorted().map { JsonPrimitive(it) }))
            d.checkedAt?.let { put("checked_at", it) }; d.calendarAt?.let { put("calendar_at", it) }
            put("fires", JsonArray(d.fires.map { buildJsonObject { put("routine_id", it.routineId); put("at", it.at); put("title", it.title); put("location", it.location); put("starts_at", it.startsAt) } }))
            put("deferred", JsonArray(d.deferred.map { it.json() })); d.fenceKey?.let { put("fence_key", it) }; d.fencesAt?.let { put("fences_at", it) }
            put("places_paused", d.placesPaused); put("occurrences", JsonArray(d.occurrences.map { it.json() })); put("pattern_blocked", d.patternBlocked)
            d.migrationId?.let { put("migration_id", it) }
            put("history", JsonArray(d.history.map { r -> buildJsonObject {
                put("id", r.id); put("name", r.name); put("at", r.at); put("code", r.code)
                put("apps", JsonArray(r.apps.map { a -> buildJsonObject { put("package", a.packageName); put("code", a.code) } }))
                put("trace", JsonArray(r.trace.map { JsonPrimitive(it) }))
            } }))
        }
        try { DurableRoutineFile.write(file, json.toString().toByteArray(Charsets.UTF_8)) }
        catch (e: DurableRoutineFile.CommitUncertain) {
            // The disk may already contain the new claim. Never overwrite it from the older cache.
            readOnly = true; recoveryError = "ROUTINE_COMMIT_UNCERTAIN"
            throw e
        }
    }
    companion object { const val FILE_NAME = "routines.json" }
}
