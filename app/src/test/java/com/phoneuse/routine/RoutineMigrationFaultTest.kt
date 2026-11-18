package com.phoneuse.routine

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.localwake.app.scheduler.ScheduleModel
import org.localwake.app.scheduler.ScheduleRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class RoutineMigrationFaultTest {
    private class SimulatedProcessDeath : Error()
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val zone = ZoneId.of("UTC")

    private suspend fun source(block: suspend (ScheduleRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temp.newFolder().resolve("legacy.preferences_pb").absolutePath.toPath()
        val datastore = PreferenceDataStoreFactory.create(scope = scope, storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file }))
        try { block(ScheduleRepository(datastore)) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun crashAfterEveryDurablePhaseResumesIdempotentlyWithOneAuthority() = runTest {
        for (phase in listOf(MigrationPhase.SHADOW_VALIDATION, MigrationPhase.PREPARING, MigrationPhase.BACKED_UP,
            MigrationPhase.TARGET_STAGED, MigrationPhase.VERIFIED, MigrationPhase.ROUTINE_ACTIVE)) source { repository ->
            repository.save(ScheduleModel(id = "once", name = "Original", oneShotAt = now.plusSeconds(60).toEpochMilli()))
            repository.arm("once", now, zone, false)
            repository.setPatternBlocked(true)
            val original = repository.encodeLegacySnapshot(repository.legacySnapshot())
            val directory = temp.newFolder()
            val targetFile = directory.resolve("routines.json")
            val journalFile = directory.resolve("journal.json")
            val target = RoutineStore(targetFile)
            val authority = RoutineAuthority(journalFile) { target.migrationId != null || !target.ready }
            var cancellations = 0
            val migrator = RoutineMigrator(directory, authority, target) { if (it == phase) throw SimulatedProcessDeath() }
            assertThrows(SimulatedProcessDeath::class.java) {
                kotlinx.coroutines.runBlocking { migrator.migrate(repository, now, zone) { cancellations++ } }
            }
            val interrupted = authority.snapshot()
            if (phase in listOf(MigrationPhase.PREPARING, MigrationPhase.BACKED_UP, MigrationPhase.TARGET_STAGED, MigrationPhase.VERIFIED)) {
                assertEquals(RoutineOwner.NONE, interrupted.owner)
                assertFalse(authority.allows(RoutineOwner.LEGACY)); assertFalse(authority.allows(RoutineOwner.ROUTINE))
            }
            val reopened = RoutineStore(targetFile)
            val recoveredAuthority = RoutineAuthority(journalFile) { reopened.migrationId != null || !reopened.ready }
            val result = RoutineMigrator(directory, recoveredAuthority, reopened).migrate(repository, now, zone) { cancellations++ }
            assertEquals(RoutineOwner.ROUTINE, result.owner)
            assertFalse(recoveredAuthority.allows(RoutineOwner.LEGACY, 1))
            assertFalse(recoveredAuthority.allows(RoutineOwner.ROUTINE, 1))
            assertTrue(recoveredAuthority.allows(RoutineOwner.ROUTINE, result.epoch))
            assertEquals(1, reopened.all().size)
            assertTrue(reopened.patternBlocked)
            assertEquals(original, repository.encodeLegacySnapshot(repository.legacySnapshot()))
            val ids = result.idMap
            assertEquals(ids, RoutineMigrator(directory, recoveredAuthority, reopened).migrate(repository, now, zone) {}.idMap)
            assertTrue(cancellations in 1..2)
        }
    }

    @Test fun corruptedOrMissingJournalWithMigratedTargetDisablesBothOwners() {
        val file = temp.newFolder().resolve("authority.json")
        file.writeText("{bad")
        val authority = RoutineAuthority(file)
        assertEquals(MigrationPhase.RECOVERY_REQUIRED, authority.snapshot().phase)
        assertFalse(authority.allows(RoutineOwner.LEGACY)); assertFalse(authority.allows(RoutineOwner.ROUTINE))
        assertEquals("{bad", file.readText())
        val missing = RoutineAuthority(temp.newFolder().resolve("missing.json")) { true }
        assertFalse(missing.allows(RoutineOwner.LEGACY)); assertFalse(missing.allows(RoutineOwner.ROUTINE))
    }

    @Test fun sourceChangeAfterFreezeIsNotSilentlyImportedOrActivated() = runTest { source { repository ->
        repository.save(ScheduleModel(id = "first"))
        val directory = temp.newFolder(); val target = RoutineStore(directory.resolve("r.json")); val authority = RoutineAuthority(directory.resolve("j.json"))
        assertThrows(SimulatedProcessDeath::class.java) { kotlinx.coroutines.runBlocking {
            RoutineMigrator(directory, authority, target) { if (it == MigrationPhase.PREPARING) throw SimulatedProcessDeath() }.migrate(repository, now, zone) {}
        } }
        // Independent legacy fixture represents unsupported external mutation while the app was not running.
        repository.save(ScheduleModel(id = "second"))
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking {
            RoutineMigrator(directory, authority, target).migrate(repository, now, zone) {}
        } }
        assertEquals(RoutineOwner.NONE, authority.snapshot().owner)
        assertTrue(target.all().isEmpty())
    } }

    @Test fun failedAuthorityWriteCannotTransferOwnership() = runTest { source { repository ->
        val directory = temp.newFolder(); val target = RoutineStore(directory.resolve("r.json")); val journal = directory.resolve("authority.json")
        val authority = RoutineAuthority(journal)
        java.io.File(journal.path + ".new").mkdirs()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { RoutineMigrator(directory, authority, target).migrate(repository, now, zone) {} } }
        assertFalse(authority.allows(RoutineOwner.LEGACY)); assertFalse(authority.allows(RoutineOwner.ROUTINE)); assertTrue(target.all().isEmpty())
    } }

    @Test fun explicitPostCommitRecoveryKeepsUnknownClaimAndBlockedGuard() = runTest { source { repository ->
        repository.save(ScheduleModel(id = "once", oneShotAt = now.plusSeconds(10).toEpochMilli()))
        repository.arm("once", now, zone, false)
        val directory = temp.newFolder(); val target = RoutineStore(directory.resolve("r.json"))
        val authority = RoutineAuthority(directory.resolve("authority.json")) { target.migrationId != null }
        val migrator = RoutineMigrator(directory, authority, target)
        val committed = migrator.migrate(repository, now, zone) {}
        val o = target.pending(committed.epoch).single()
        val (r, claimed) = target.claim(o.token, o.dueAt, committed.epoch)!!
        assertTrue(target.beginCredentialAttempt(r.localTask(claimed), committed.epoch))
        authority.freezeForRecovery("INJECTED_TARGET_FAILURE")
        val recovered = migrator.recover(repository, Instant.ofEpochMilli(o.dueAt + 1), zone) {}
        assertTrue(recovered.epoch > committed.epoch)
        assertTrue(target.patternBlocked)
        assertEquals("IN_PROGRESS", target.localSnapshot().history.single().code)
        assertNull(target.claim(o.token, o.dueAt + 2, recovered.epoch))
        assertTrue(target.pending(recovered.epoch).isEmpty())
    } }

    @Test fun changedSourceDuringShadowCreatesNewCandidateWithoutRewritingSource() = runTest { source { repository ->
        repository.save(ScheduleModel(id = "first"))
        val directory = temp.newFolder(); val target = RoutineStore(directory.resolve("r.json")); val authority = RoutineAuthority(directory.resolve("j.json"))
        assertThrows(SimulatedProcessDeath::class.java) { kotlinx.coroutines.runBlocking {
            RoutineMigrator(directory, authority, target) { if (it == MigrationPhase.SHADOW_VALIDATION) throw SimulatedProcessDeath() }.migrate(repository, now, zone) {}
        } }
        val oldId = authority.snapshot().migrationId
        repository.save(ScheduleModel(id = "second"))
        val result = RoutineMigrator(directory, authority, target).migrate(repository, now, zone) {}
        assertNotEquals(oldId, result.migrationId)
        assertEquals(2, target.all().size)
        assertEquals(2, repository.legacySnapshot().schedules.size)
    } }
}
