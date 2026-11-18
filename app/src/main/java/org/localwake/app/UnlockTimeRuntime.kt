package org.localwake.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import org.localwake.app.scheduler.LocalAlarmScheduler
import org.localwake.app.scheduler.ScheduleRepository
import org.localwake.app.security.LocalUnlockCredentialStore

/**
 * The original local dependencies, owned by the unified application's process.
 * Keep the application Context and storage names unchanged so an in-place upgrade retains its data.
 * This runtime is not an Agent tool and never supplies credentials to the network/Agent graph.
 */
class UnlockTimeRuntime private constructor(private val context: Context) {
    val repository by lazy { ScheduleRepository(context) }
    val scheduler by lazy { LocalAlarmScheduler(context, repository) }
    val credentials by lazy { LocalUnlockCredentialStore(context) }
    internal val compatibility by lazy { org.localwake.app.compatibility.CompatibilityStore(context) }
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val executionMutex = Mutex()

    companion object {
        @Volatile private var instance: UnlockTimeRuntime? = null

        /** Called before AppGraph; the fallback also supports early Android component creation. */
        fun initialize(context: Context): UnlockTimeRuntime = instance ?: synchronized(this) {
            instance ?: UnlockTimeRuntime(context.applicationContext).also { instance = it }
        }
    }
}

val Context.localWake: UnlockTimeRuntime get() = UnlockTimeRuntime.initialize(this)
