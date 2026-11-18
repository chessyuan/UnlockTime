package org.localwake.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.localwake.app.scheduler.*
import java.time.Instant
import java.time.ZoneId

class ScheduleRepositoryTest {
    @Test fun genericCredentialAttemptSharesDurableAtomicGuardWithoutCreatingTask() = runTest { withRepository { repo ->
        val results = listOf(async { repo.beginCredentialAttempt() }, async { repo.beginCredentialAttempt() }).awaitAll()
        assertEquals(1, results.count { it })
        assertTrue(repo.snapshot().patternBlocked)
        assertTrue(repo.snapshot().schedules.isEmpty())
        assertFalse(repo.beginCredentialAttempt())
    } }

    @Test fun concurrentFieldEditsMergeCurrentTaskInsteadOfRestoringOldFields() = runTest { withRepository { repo ->
        val task = ScheduleModel(id = "edit", apps = listOf(org.localwake.app.execution.AppLaunchConfiguration("com.example.app")))
        repo.save(task)
        listOf(async { repo.edit(task.id) { it.copy(apps = emptyList()) } },
            async { repo.edit(task.id) { it.copy(enabled = false) } }).awaitAll()
        val current = repo.snapshot().schedules.single()
        assertTrue(current.apps.isEmpty()); assertFalse(current.enabled)
        assertNotEquals(task.revision, current.revision)
    } }
    @Test fun editOfDeletedTaskCannotResurrectIt() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "edit")); repo.delete("edit")
        var called = false
        assertFalse(repo.edit("edit") { called = true; it.copy(enabled = true) })
        assertFalse(called); assertTrue(repo.snapshot().schedules.isEmpty())
    } }
    @Test fun rejectedStaleAppEditLeavesCurrentTaskIntact() = runTest { withRepository { repo ->
        val a = org.localwake.app.execution.AppLaunchConfiguration("com.example.first")
        val b = org.localwake.app.execution.AppLaunchConfiguration("com.example.second")
        repo.save(ScheduleModel(id = "edit", apps = listOf(a, b)))
        repo.edit("edit") { it.copy(apps = listOf(b, a)) }
        try { repo.edit("edit") { require(it.apps[0] == a); it.copy(apps = it.apps.drop(1)) }; fail("stale edit accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(b, a), repo.snapshot().schedules.single().apps)
    } }
    @get:Rule val temp = TemporaryFolder()
    private suspend fun withRepository(test: suspend (ScheduleRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temp.newFolder().resolve("test.preferences_pb").absolutePath.toPath()
        // Android FileStorage uses File.renameTo, which cannot replace an existing file on Windows.
        // Exercise the same DataStore transactions with its supported cross-platform Okio storage.
        val store = PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file }))
        try { test(ScheduleRepository(store)) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun roundTripPersistsAppsAndWeekdays() = runTest { withRepository { repo ->
        val task = ScheduleModel(id = "t", hour = 9, minute = 17, weekdays = setOf(1, 5),
            apps = listOf(org.localwake.app.execution.AppLaunchConfiguration("com.example.app", 250)))
        repo.save(task)
        val restored = repo.snapshot().schedules.single()
        assertEquals(setOf(1, 5), restored.weekdays); assertEquals(250L, restored.apps.single().delayMs)
        assertEquals(9, restored.hour); assertEquals(17, restored.minute)
    } }
    @Test fun concurrentDeliveryHasExactlyOnePersistentClaim() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t"))
        val armed = repo.arm("t", Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("UTC"), true)!!
        val claims = (1..10).map { async { repo.claim("t", armed.armedToken!!, armed.armedAt!!) } }.awaitAll()
        assertEquals(1, claims.count { it.task != null })
        assertEquals("2026-11-15", repo.snapshot().schedules.single().lastDate)
        assertEquals(1, repo.snapshot().history.size)
    } }
    @Test fun editAndDeleteInvalidateAlreadyQueuedBroadcasts() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t"))
        val armed = repo.arm("t", Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("UTC"), true)!!
        repo.save(armed.copy(hour = 8))
        assertNull(repo.claim("t", armed.armedToken!!, armed.armedAt!!).task)
        repo.delete("t")
        assertEquals("TASK_MISSING", repo.claim("t", armed.armedToken, armed.armedAt).reason)
    } }
    @Test fun reconnectDoesNotInvalidateDueButQueuedDelivery() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t"))
        val armed = repo.arm("t", Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("UTC"), true)!!
        repo.arm("t", Instant.ofEpochMilli(armed.armedAt!! + 1000), ZoneId.of("UTC"), false)
        assertNotNull(repo.claim("t", armed.armedToken!!, armed.armedAt + 2000).task)
    } }
    @Test fun failureGuardCannotBeReenteredUntilExplicitReset() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t"))
        val task = repo.snapshot().schedules.single()
        assertTrue(repo.beginPatternAttempt(task)); assertFalse(repo.beginPatternAttempt(task))
        assertTrue(repo.snapshot().patternBlocked)
        repo.setPatternBlocked(false); assertTrue(repo.beginPatternAttempt(task))
    } }
    @Test fun changedTaskCannotObtainCredentialAttemptAuthorization() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t"))
        val task = repo.snapshot().schedules.single()
        repo.save(task.copy(enabled = false))
        assertFalse(repo.beginPatternAttempt(task))
        assertFalse(repo.snapshot().patternBlocked)
        repo.delete(task.id)
        assertFalse(repo.beginPatternAttempt(task))
    } }
    @Test fun oneShotClaimDisablesFutureSchedulingButRemainsCurrentUntilEdited() = runTest { withRepository { repo ->
        repo.save(ScheduleModel(id = "t", oneShotAt = 10000))
        val armed = repo.arm("t", Instant.ofEpochMilli(1000), ZoneId.of("UTC"), true)!!
        val claimed = repo.claim("t", armed.armedToken!!, 10000).task!!
        assertTrue(repo.stillCurrent(claimed)); assertFalse(repo.snapshot().schedules.single().enabled)
        assertNull(repo.arm("t", Instant.ofEpochMilli(11000), ZoneId.of("UTC"), true))
        repo.save(repo.snapshot().schedules.single().copy(enabled = false))
        assertFalse(repo.stillCurrent(claimed))
    } }
    @Test fun processRestartKeepsClaimAndFailureGuard() = runTest {
        val file = temp.newFolder().resolve("restart.preferences_pb").absolutePath.toPath()
        suspend fun open(block: suspend (ScheduleRepository) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = PreferenceDataStoreFactory.create(scope = scope,
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file }))
            try { block(ScheduleRepository(store)) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        var token = ""
        var due = 0L
        open { repo ->
            repo.save(ScheduleModel(id = "t"))
            val armed = repo.arm("t", Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("UTC"), true)!!
            token = armed.armedToken!!; due = armed.armedAt!!
            val claimed = repo.claim("t", token, due).task!!
            assertTrue(repo.beginPatternAttempt(claimed))
        }
        open { repo ->
            assertNull(repo.claim("t", token, due + 1).task)
            assertTrue(repo.snapshot().patternBlocked)
            assertEquals("2026-11-15", repo.snapshot().schedules.single().lastDate)
            assertFalse(repo.beginPatternAttempt(repo.snapshot().schedules.single()))
        }
    }
}
