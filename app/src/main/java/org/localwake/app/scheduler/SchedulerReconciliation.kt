package org.localwake.app.scheduler

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The native executor may acquire [legacyMutex] to commit its credential guard.
 * Consequently the unified callback must never run inside that mutex, including a cutover
 * that happens while a legacy caller is waiting to acquire it.
 */
internal suspend fun reconcileByAuthority(
    legacyMutex: Mutex,
    legacyOwns: () -> Boolean,
    legacy: suspend () -> String,
    unified: suspend () -> String,
): String {
    if (!legacyOwns()) return unified()
    val legacyResult = legacyMutex.withLock {
        if (!legacyOwns()) null else {
            val result = legacy()
            result.takeIf { legacyOwns() }
        }
    }
    return legacyResult ?: unified()
}
