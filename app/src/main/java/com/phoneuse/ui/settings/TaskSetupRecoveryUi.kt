package com.phoneuse.ui.settings

import android.content.Context
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.unified.UnifiedStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** UI-only process state: a committed local recovery keeps running when its success panel disappears. */
internal object TaskSetupRecoveryUi {
    private val controller = TaskSetupRetryController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    val state = controller.state

    fun retry(context: Context): Boolean {
        val app = context.applicationContext
        return controller.retry {
            withContext(Dispatchers.IO) {
                val runtime = UnifiedRoutineRuntime.get(app)
                when (taskSetupAction(runtime.authority.snapshot(), runtime.store.ready)) {
                    TaskSetupAction.READY -> true
                    TaskSetupAction.REPAIR_REQUIRED -> false
                    TaskSetupAction.RETRY -> UnifiedStartup.recoverScheduling(app)
                }
            }
        }
    }
}
