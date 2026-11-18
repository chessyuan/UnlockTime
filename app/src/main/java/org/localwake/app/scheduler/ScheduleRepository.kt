package org.localwake.app.scheduler

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.phoneuse.routine.RoutineOwner
import com.phoneuse.routine.UnifiedRoutineRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.localwake.app.execution.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private val Context.localWakeData by preferencesDataStore("localwake")
data class LocalState(val schedules: List<ScheduleModel> = emptyList(),
                      val history: List<ExecutionRecord> = emptyList(), val patternBlocked: Boolean = false)
data class Claim(val task: ScheduleModel?, val reason: String)

class ScheduleRepository internal constructor(private val store: DataStore<Preferences>, private val runtime: UnifiedRoutineRuntime? = null) {
    constructor(context: Context) : this(context.applicationContext.localWakeData, UnifiedRoutineRuntime.get(context))
    private val key = stringPreferencesKey("state_v1")
    val states = if (runtime == null) store.data.map { decode(it[key]) } else
        combine(store.data, runtime.authority.state, runtime.store.localStates) { legacy, _, unified ->
            when (runtime.authority.snapshot().owner) {
                RoutineOwner.ROUTINE -> unified
                RoutineOwner.LEGACY -> decode(legacy[key])
                RoutineOwner.NONE -> unified.copy(patternBlocked = true)
            }
        }
    /** Only the controlled migrator uses the unchanged source snapshot. */
    suspend fun legacySnapshot(): LocalState = decode(store.data.first()[key])
    fun encodeLegacySnapshot(state: LocalState): String = encode(state)
    private fun routineStore(): com.phoneuse.routine.RoutineStore? = runtime?.let {
        when (it.authority.snapshot().owner) {
            RoutineOwner.ROUTINE -> it.store.also { target ->
                check(target.ready && target.migrationId == it.authority.snapshot().migrationId) { "ROUTINE_RECOVERY_REQUIRED" }
            }
            RoutineOwner.LEGACY -> null
            RoutineOwner.NONE -> throw java.io.IOException("MIGRATION_RECOVERY_REQUIRED")
        }
    }
    fun legacyAuthority(epoch: Long? = null): Boolean = runtime?.authority?.allows(RoutineOwner.LEGACY, epoch) ?: true
    fun authorityEpoch(): Long = runtime?.authority?.snapshot()?.epoch ?: 1L
    suspend fun snapshot(): LocalState = states.first()
    private suspend fun update(block: (LocalState) -> LocalState) {
        check(legacyAuthority()) { "LEGACY_WRITES_FENCED" }
        store.edit { check(legacyAuthority()) { "LEGACY_WRITES_FENCED" }; it[key] = encode(block(decode(it[key]))) }
    }
    suspend fun save(task: ScheduleModel) {
        require(routineStore() == null) { "USE_ROUTINE_EDITOR" }
        update { state ->
        val old = state.schedules.find { it.id == task.id }
        require(old != null || state.schedules.size < 32) { "最多 32 个任务" }
        val edited = task.copy(revision = UUID.randomUUID().toString(), armedToken = null, armedAt = null,
            armedDate = null, lastDate = old?.lastDate)
        state.copy(schedules = state.schedules.filterNot { it.id == edited.id } + edited)
        }
    }
    suspend fun delete(id: String) {
        routineStore()?.let { target -> target.all().firstOrNull { (it.legacyId ?: "routine-${it.id}") == id }?.let { target.delete(it.id) }; return }
        update { it.copy(schedules = it.schedules.filterNot { s -> s.id == id }) }
    }
    /** Apply only the requested fields to the latest task in the DataStore transaction. */
    suspend fun edit(id: String, transform: (ScheduleModel) -> ScheduleModel): Boolean {
        require(routineStore() == null) { "USE_ROUTINE_EDITOR" }
        var found = false
        update { state ->
            val current = state.schedules.find { it.id == id } ?: return@update state
            val changed = transform(current)
            require(changed.id == current.id)
            val edited = changed.copy(revision = UUID.randomUUID().toString(), armedToken = null, armedAt = null,
                armedDate = null, lastDate = current.lastDate)
            found = true
            state.copy(schedules = state.schedules.map { if (it.id == id) edited else it })
        }
        return found
    }
    suspend fun disarm(id: String) {
        if (routineStore() != null) return
        update { state -> state.copy(schedules = state.schedules.map {
        if (it.id == id) it.copy(armedToken = null, armedAt = null, armedDate = null) else it
    }) }
    }
    suspend fun arm(id: String, now: Instant, zone: ZoneId, force: Boolean): ScheduleModel? {
        if (!legacyAuthority()) return null
        var armed: ScheduleModel? = null
        update { state -> state.copy(schedules = state.schedules.map { task ->
            if (task.id != id) task else {
                val existing = task.armedAt?.takeIf { task.enabled && !force &&
                    now.toEpochMilli() - it <= 600_000 && task.armedToken != null && task.armedDate != null }
                val at = existing ?: task.nextExecution(now, zone)?.toEpochMilli()
                val updated = if (at == null) task.copy(armedAt = null, armedToken = null, armedDate = null)
                    else task.copy(armedAt = at, armedToken = if (existing != null) task.armedToken else UUID.randomUUID().toString(),
                        armedDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString())
                if (at != null) armed = updated
                updated
            }
        }) }
        return armed
    }
    suspend fun claim(id: String, token: String, now: Long): Claim {
        if (!legacyAuthority()) return Claim(null, "AUTHORITY_CHANGED")
        var result = Claim(null, "TASK_MISSING")
        update { state ->
            val task = state.schedules.find { it.id == id } ?: return@update state
            val reason = AlarmEligibility.check(task.enabled, task.armedToken, token, task.armedAt ?: Long.MAX_VALUE,
                now, task.armedDate.orEmpty(), task.lastDate)
            if (reason != null) { result = Claim(null, reason); return@update state }
            result = Claim(task, "CLAIMED")
            state.copy(schedules = state.schedules.map {
                if (it.id != id) it else it.copy(lastDate = task.armedDate, armedToken = null, armedAt = null,
                    armedDate = null, enabled = task.oneShotAt == null)
            }, history = (listOf(ExecutionRecord(token, task.name, now, "IN_PROGRESS")) + state.history).take(100))
        }
        return result
    }
    suspend fun stillCurrent(task: ScheduleModel): Boolean {
        val target = routineStore()
        if (target != null) return target.stillCurrent(task, authorityEpoch())
        return snapshot().schedules.any { it.id == task.id && it.revision == task.revision && (it.enabled || it.oneShotAt != null) }
    }
    suspend fun record(record: ExecutionRecord) {
        routineStore()?.let { it.record(record); return }
        update { it.copy(history = (listOf(record) + it.history.filterNot { item -> item.id == record.id }).take(100)) }
    }
    suspend fun clearHistory() {
        routineStore()?.let { it.clearHistory(); return }
        update { it.copy(history = emptyList()) }
    }
    suspend fun setPatternBlocked(value: Boolean) {
        routineStore()?.let { it.setPatternBlocked(value); return }
        update { it.copy(patternBlocked = value) }
    }
    /** Generic device access uses the same durable, single-attempt guard without inventing a task. */
    suspend fun beginCredentialAttempt(): Boolean {
        routineStore()?.let { return it.beginCredentialAttempt() }
        var allowed = false
        update { state ->
            if (state.patternBlocked) state else {
                allowed = true
                state.copy(patternBlocked = true)
            }
        }
        return allowed
    }
    suspend fun beginPatternAttempt(task: ScheduleModel): Boolean {
        routineStore()?.let { return it.beginCredentialAttempt(task, authorityEpoch()) }
        var allowed = false
        update {
            val current = it.schedules.any { stored -> stored.id == task.id && stored.revision == task.revision &&
                (stored.enabled || stored.oneShotAt != null) }
            if (it.patternBlocked || !current) it else { allowed = true; it.copy(patternBlocked = true) }
        }
        return allowed
    }

    // Fail closed on corrupt data: do not silently erase claims or retry credentials.
    private fun decode(raw: String?): LocalState {
        if (raw == null) return LocalState()
        val obj = JSONObject(raw)
        require(obj.getInt("version") == 1)
        val tasks = obj.getJSONArray("schedules").objects().map { o -> ScheduleModel(
            id = o.getString("id"), name = o.getString("name"), hour = o.getInt("hour"), minute = o.getInt("minute"),
            weekdays = o.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
            enabled = o.getBoolean("enabled"), apps = o.getJSONArray("apps").objects().map {
                AppLaunchConfiguration(it.getString("package"), it.getLong("delay")) },
            mode = ExecutionMode.valueOf(o.getString("mode")), oneShotAt = o.nullLong("once"),
            revision = o.getString("revision"), armedToken = o.nullString("token"), armedAt = o.nullLong("at"),
            armedDate = o.nullString("date"), lastDate = o.nullString("last")) }
        require(tasks.size <= 32 && tasks.map { it.id }.distinct().size == tasks.size)
        for (task in tasks) {
            require(task.revision.isNotBlank())
            require(listOf(task.armedToken, task.armedAt, task.armedDate).count { it != null } in setOf(0, 3))
            task.armedDate?.let(java.time.LocalDate::parse); task.lastDate?.let(java.time.LocalDate::parse)
        }
        require(obj.getJSONArray("history").length() <= 100)
        return LocalState(tasks, obj.getJSONArray("history").objects().map { o -> ExecutionRecord(
            o.getString("id"), o.getString("name"), o.getLong("at"), o.getString("code"),
            o.getJSONArray("apps").objects().map { AppLaunchResult(it.getString("package"), it.getString("code")) },
            o.optJSONArray("trace")?.let { a -> require(a.length() <= 80); (0 until a.length()).map { a.getString(it) } } ?: emptyList()) }, obj.getBoolean("blocked"))
    }
    private fun encode(state: LocalState): String = JSONObject().put("version", 1).put("blocked", state.patternBlocked)
        .put("schedules", JSONArray(state.schedules.map { s -> JSONObject()
            .put("id", s.id).put("name", s.name).put("hour", s.hour).put("minute", s.minute)
            .put("days", JSONArray(s.weekdays.sorted())).put("enabled", s.enabled).put("revision", s.revision)
            .put("mode", s.mode.name).put("once", s.oneShotAt).put("token", s.armedToken).put("at", s.armedAt)
            .put("date", s.armedDate).put("last", s.lastDate)
            .put("apps", JSONArray(s.apps.map { JSONObject().put("package", it.packageName).put("delay", it.delayMs) })) }))
        .put("history", JSONArray(state.history.map { r -> JSONObject().put("id", r.id).put("name", r.name)
            .put("at", r.at).put("code", r.code).put("trace", JSONArray(r.trace.take(80))).put("apps", JSONArray(r.apps.map {
                JSONObject().put("package", it.packageName).put("code", it.code) })) })).toString()
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.nullString(name: String) = if (isNull(name) || !has(name)) null else getString(name)
    private fun JSONObject.nullLong(name: String) = if (isNull(name) || !has(name)) null else getLong(name)
}
