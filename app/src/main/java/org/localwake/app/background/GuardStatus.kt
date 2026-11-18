package org.localwake.app.background

import androidx.annotation.StringRes
import com.phoneuse.R

object GuardStatus {
    @StringRes
    fun messageResource(connected: Boolean, exact: Boolean, credential: Boolean, blocked: Boolean,
                        executing: Boolean, next: Boolean): Int = when {
        executing -> R.string.guard_executing
        !connected -> R.string.guard_accessibility_required
        !exact -> R.string.guard_exact_alarm_required
        !credential -> R.string.guard_credential_required
        blocked -> R.string.guard_unlock_blocked
        next -> R.string.guard_next_task
        else -> R.string.guard_no_tasks
    }
}
