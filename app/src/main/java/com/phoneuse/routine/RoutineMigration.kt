package com.phoneuse.routine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.localwake.app.scheduler.LocalState
import org.localwake.app.scheduler.ScheduleRepository
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class RoutineOwner { LEGACY, NONE, ROUTINE }
enum class MigrationPhase { LEGACY_ACTIVE, SHADOW_VALIDATION, PREPARING, BACKED_UP, TARGET_STAGED, VERIFIED, ROUTINE_ACTIVE, MIGRATION_COMPLETE, RECOVERY_REQUIRED }
data class RoutineAuthorityState(
    val phase: MigrationPhase = MigrationPhase.LEGACY_ACTIVE, val owner: RoutineOwner = RoutineOwner.LEGACY,
    val epoch: Long = 1, val migrationId: String? = null, val sourceHash: String? = null,
    val backupHash: String? = null, val targetHash: String? = null, val idMap: Map<String, Long> = emptyMap(),
    val cutoverAt: Long? = null, val cutoverZone: String? = null, val failureCode: String? = null,
    val interruptedPhase: MigrationPhase? = null,
)

/** Sole durable execution-right journal. Corrupt/contradictory journals fence BOTH schedulers. */
class RoutineAuthority(private val file: File, private val targetAlreadyMigrated: () -> Boolean = { false }) {
    private val lock = Any()
    private var frozenInProcess = false
    private val _state = MutableStateFlow(read())
    val state: StateFlow<RoutineAuthorityState> = _state
    fun snapshot(): RoutineAuthorityState = synchronized(lock) {
        if (frozenInProcess) return@synchronized _state.value
        // Read through: a committed file, never an engine's stale cache, is the authority.
        read().also { _state.value = it }
    }
    fun allows(owner: RoutineOwner, epoch: Long? = null): Boolean {
        val s = snapshot()
        return owner != RoutineOwner.NONE && s.owner == owner && (epoch == null || epoch == s.epoch)
    }
    internal fun commit(next: RoutineAuthorityState) = synchronized(lock) {
        validate(next)
        DurableRoutineFile.write(file, encode(next).toString().toByteArray(Charsets.UTF_8))
        _state.value = next
        frozenInProcess = false
    }
    /** A storage failure must not leave an engine using its previous in-memory execution right. */
    fun freezeForRecovery(code: String = "MIGRATION_FAILED"): RoutineAuthorityState = synchronized(lock) {
        val previous = snapshot()
        if (previous.phase == MigrationPhase.RECOVERY_REQUIRED) return@synchronized previous
        val blocked = previous.copy(phase = MigrationPhase.RECOVERY_REQUIRED, owner = RoutineOwner.NONE,
            epoch = previous.epoch + 1, failureCode = code, interruptedPhase = previous.phase)
        try { commit(blocked) } catch (_: IOException) {
            // No durable claim is issued while a failed disk prevents saving the fence.
            _state.value = blocked; frozenInProcess = true
        }
        blocked
    }
    private fun read(): RoutineAuthorityState {
        if (!file.exists()) return if (targetAlreadyMigrated()) blocked() else RoutineAuthorityState()
        return try {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o.getValue("version").jsonPrimitive.int == 1)
            require(o.getValue("source_version").jsonPrimitive.int == 1 && o.getValue("target_version").jsonPrimitive.int == 2)
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val s = RoutineAuthorityState(MigrationPhase.valueOf(str("phase")!!), RoutineOwner.valueOf(str("owner")!!),
                o.getValue("epoch").jsonPrimitive.long, str("migration_id"), str("source_hash"), str("backup_hash"), str("target_hash"),
                o.getValue("id_map").jsonObject.mapValues { it.value.jsonPrimitive.long }, o["cutover_at"]?.jsonPrimitive?.long,
                str("cutover_zone"), str("failure_code"), str("interrupted_phase")?.let(MigrationPhase::valueOf))
            validate(s); s
        } catch (_: Exception) { blocked() }
    }
    private fun blocked() = RoutineAuthorityState(MigrationPhase.RECOVERY_REQUIRED, RoutineOwner.NONE, failureCode = "AUTHORITY_RECOVERY_REQUIRED")
    private fun validate(s: RoutineAuthorityState) {
        require(s.epoch > 0)
        require(when (s.phase) {
            MigrationPhase.LEGACY_ACTIVE, MigrationPhase.SHADOW_VALIDATION -> s.owner == RoutineOwner.LEGACY
            MigrationPhase.ROUTINE_ACTIVE, MigrationPhase.MIGRATION_COMPLETE -> s.owner == RoutineOwner.ROUTINE && s.migrationId != null && s.targetHash != null && s.sourceHash != null && s.backupHash != null
            else -> s.owner == RoutineOwner.NONE
        })
        require(s.idMap.values.distinct().size == s.idMap.size)
        require(s.idMap.values.all { it > 0 })
        s.migrationId?.let { require(UUID.fromString(it).toString() == it) }
        require(listOfNotNull(s.sourceHash, s.backupHash, s.targetHash).all { it.matches(Regex("[0-9a-f]{64}")) })
    }
    private fun encode(s: RoutineAuthorityState) = buildJsonObject {
        put("version", 1); put("source_version", 1); put("target_version", 2)
        put("phase", s.phase.name); put("owner", s.owner.name); put("epoch", s.epoch)
        s.migrationId?.let { put("migration_id", it) }; s.sourceHash?.let { put("source_hash", it) }
        s.backupHash?.let { put("backup_hash", it) }; s.targetHash?.let { put("target_hash", it) }
        put("id_map", buildJsonObject { s.idMap.forEach { (k, v) -> put(k, v) } })
        s.cutoverAt?.let { put("cutover_at", it) }; s.cutoverZone?.let { put("cutover_zone", it) }; s.failureCode?.let { put("failure_code", it) }
        s.interruptedPhase?.let { put("interrupted_phase", it.name) }
    }
}

/** No alarms, credentials or side effects are accessed during shadow comparison. */
object RoutineMigrationVerifier {
    fun compare(source: LocalState, target: RoutineStore, idMap: Map<String, Long>, now: Instant, zone: ZoneId) {
        require(source.schedules.size == idMap.size && idMap.keys == source.schedules.map { it.id }.toSet())
        val projection = target.localSnapshot()
        require(source.history == projection.history && source.patternBlocked == projection.patternBlocked)
        for (task in source.schedules) {
            val r = requireNotNull(target.get(requireNotNull(idMap[task.id])))
            val occurrence = target.occurrences().firstOrNull { it.routineId == r.id && it.status == OccurrenceStatus.PENDING }
            require(r.localTask(occurrence) == task) { "MIGRATION_FIELD_MISMATCH" }
            var after = now
            repeat(16) {
                val oldNext = task.nextExecution(after, zone)
                val newNext = NextRun.compute(r, after.atZone(zone))?.toInstant()
                require(oldNext == newNext) { "MIGRATION_FUTURE_TIME_MISMATCH" }
                if (oldNext != null) after = oldNext
            }
        }
    }
}

/** Caller must hold the shared device-operation arbiter and legacy scheduler mutex. */
class RoutineMigrator(
    private val directory: File, private val authority: RoutineAuthority, private val target: RoutineStore,
    private val checkpoint: (MigrationPhase) -> Unit = {},
) {
    /** Explicit recovery under the same device/scheduler locks. Never restores a stale legacy backup. */
    suspend fun recover(repository: ScheduleRepository, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), cancelLegacy: suspend () -> Unit): RoutineAuthorityState {
        val stopped = authority.snapshot()
        require(stopped.owner == RoutineOwner.NONE && stopped.phase == MigrationPhase.RECOVERY_REQUIRED && target.ready)
        // A malformed journal has no verifiable provenance and needs external repair, not guessed ownership.
        require(stopped.failureCode != "AUTHORITY_RECOVERY_REQUIRED")
        try {
            val epoch = stopped.epoch + 1
            if (stopped.cutoverAt != null) {
                require(target.migrationId == stopped.migrationId && target.verifyReopened())
                target.rebindPendingAuthority(epoch)
                cancelLegacy()
                val active = stopped.copy(owner = RoutineOwner.ROUTINE, phase = MigrationPhase.ROUTINE_ACTIVE,
                    epoch = epoch, targetHash = target.contentHash(), failureCode = null, interruptedPhase = null)
                authority.commit(active)
                return active
            }
            val source = repository.legacySnapshot()
            val hash = DurableRoutineFile.hash(repository.encodeLegacySnapshot(source).toByteArray(Charsets.UTF_8))
            require(stopped.sourceHash == null || stopped.sourceHash == hash) { "MIGRATION_SOURCE_CHANGED" }
            val id = stopped.migrationId ?: UUID.randomUUID().toString()
            if (stopped.backupHash != null) {
                val backup = File(File(directory, id), "legacy-state-v1.json")
                require(backup.isFile && DurableRoutineFile.hash(backup.readBytes()) == stopped.backupHash)
            }
            if (target.migrationId != null) {
                require(target.migrationId == id)
                RoutineMigrationVerifier.compare(source, target, stopped.idMap.ifEmpty {
                    target.all().mapNotNull { r -> r.legacyId?.let { it to r.id } }.toMap()
                }, now, zone)
                target.rebindPendingAuthority(epoch)
            }
            authority.commit(stopped.copy(phase = MigrationPhase.PREPARING, owner = RoutineOwner.NONE, epoch = epoch,
                migrationId = id, sourceHash = hash, failureCode = null, interruptedPhase = null))
            return migrateChecked(repository, now, zone, cancelLegacy)
        } catch (e: Exception) {
            authority.freezeForRecovery("MIGRATION_RECOVERY_FAILED")
            throw e
        }
    }
    suspend fun migrate(repository: ScheduleRepository, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(), cancelLegacy: suspend () -> Unit): RoutineAuthorityState {
        return try { migrateChecked(repository, now, zone, cancelLegacy) } catch (e: Exception) {
            authority.freezeForRecovery("MIGRATION_VALIDATION_OR_WRITE_FAILED")
            throw e
        }
    }
    private suspend fun migrateChecked(repository: ScheduleRepository, now: Instant, zone: ZoneId, cancelLegacy: suspend () -> Unit): RoutineAuthorityState {
        var journal = authority.snapshot()
        if (journal.owner == RoutineOwner.ROUTINE) { require(target.ready && target.migrationId == journal.migrationId); return journal }
        require(journal.phase != MigrationPhase.RECOVERY_REQUIRED && target.ready) { "MIGRATION_RECOVERY_REQUIRED" }
        // The raw snapshot always reads the original DataStore; no source key is written by migration.
        val source = repository.legacySnapshot()
        val bytes = repository.encodeLegacySnapshot(source).toByteArray(Charsets.UTF_8)
        val hash = DurableRoutineFile.hash(bytes)
        if (journal.phase == MigrationPhase.SHADOW_VALIDATION && journal.sourceHash != hash && target.migrationId == null) {
            // Legacy was still authoritative: its edits/claims invalidate only the offline candidate.
            // Keep that candidate as evidence and start a new migration id from the latest snapshot.
            journal = RoutineAuthorityState(epoch = journal.epoch)
            authority.commit(journal)
        }
        require(journal.sourceHash == null || journal.sourceHash == hash) { "MIGRATION_SOURCE_CHANGED" }
        val id = journal.migrationId ?: UUID.randomUUID().toString()
        val epoch = if (journal.owner == RoutineOwner.LEGACY) journal.epoch + 1 else journal.epoch
        fun advance(phase: MigrationPhase, owner: RoutineOwner = RoutineOwner.NONE, transform: (RoutineAuthorityState) -> RoutineAuthorityState = { it }) {
            journal = transform(journal.copy(phase = phase, owner = owner,
                epoch = if (phase == MigrationPhase.SHADOW_VALIDATION) journal.epoch else epoch, migrationId = id, sourceHash = hash))
            authority.commit(journal); checkpoint(phase)
        }
        if (journal.owner == RoutineOwner.LEGACY) {
            advance(MigrationPhase.SHADOW_VALIDATION, RoutineOwner.LEGACY)
            // An offline temporary store in noBackupDir is not visible to any receiver.
            val shadow = RoutineStore(File(File(directory, id), "shadow.json"))
            val map = shadow.importLegacy(source, id, epoch, zone)
            RoutineMigrationVerifier.compare(source, shadow, map, now, zone)
            require(shadow.verifyReopened())
        }
        advance(MigrationPhase.PREPARING)
        val backup = File(File(directory, id), "legacy-state-v1.json")
        if (backup.exists()) require(DurableRoutineFile.hash(backup.readBytes()) == hash) else DurableRoutineFile.write(backup, bytes)
        require(DurableRoutineFile.hash(backup.readBytes()) == hash)
        advance(MigrationPhase.BACKED_UP) { it.copy(backupHash = hash) }
        val idMap = target.importLegacy(source, id, epoch, zone)
        advance(MigrationPhase.TARGET_STAGED) { it.copy(idMap = idMap, targetHash = target.contentHash()) }
        RoutineMigrationVerifier.compare(source, target, idMap, now, zone)
        require(target.verifyReopened()) { "MIGRATION_READBACK_MISMATCH" }
        require(DurableRoutineFile.hash(repository.encodeLegacySnapshot(repository.legacySnapshot()).toByteArray(Charsets.UTF_8)) == hash)
        advance(MigrationPhase.VERIFIED)
        cancelLegacy()
        // Only this durable write hands execution to RoutineSystem. If it fails, neither side executes.
        advance(MigrationPhase.ROUTINE_ACTIVE, RoutineOwner.ROUTINE) { it.copy(cutoverAt = now.toEpochMilli(), cutoverZone = zone.id) }
        return journal
    }
}

/** One cached store per process; all old/new receivers and repositories share this exact authority. */
class UnifiedRoutineRuntime private constructor(context: android.content.Context) {
    val store = RoutineStore(File(context.filesDir, RoutineStore.FILE_NAME))
    val directory = File(context.noBackupFilesDir, "routine-migration")
    val authority = RoutineAuthority(File(directory, "authority-v1.json")) { store.migrationId != null || !store.ready }
    init {
        val journal = authority.snapshot()
        if (journal.owner == RoutineOwner.ROUTINE) {
            val backup = journal.migrationId?.let { File(File(directory, it), "legacy-state-v1.json") }
            val valid = store.ready && store.migrationId == journal.migrationId && backup?.isFile == true &&
                runCatching { DurableRoutineFile.hash(backup.readBytes()) == journal.backupHash }.getOrDefault(false)
            if (!valid) authority.freezeForRecovery("MIGRATION_BACKUP_OR_TARGET_INVALID")
        }
    }
    val migrationMutex = Mutex()
    suspend fun migrate(repository: ScheduleRepository, cancelLegacy: suspend () -> Unit): RoutineAuthorityState = migrationMutex.withLock {
        RoutineMigrator(directory, authority, store).migrate(repository, cancelLegacy = cancelLegacy)
    }
    suspend fun recover(repository: ScheduleRepository, cancelLegacy: suspend () -> Unit): RoutineAuthorityState = migrationMutex.withLock {
        RoutineMigrator(directory, authority, store).recover(repository, cancelLegacy = cancelLegacy)
    }
    companion object {
        @Volatile private var instance: UnifiedRoutineRuntime? = null
        fun get(context: android.content.Context): UnifiedRoutineRuntime = instance ?: synchronized(this) {
            instance ?: UnifiedRoutineRuntime(context.applicationContext).also { instance = it }
        }
    }
}
