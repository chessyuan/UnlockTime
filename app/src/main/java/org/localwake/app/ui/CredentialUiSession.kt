package org.localwake.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceSessionLease
import kotlinx.coroutines.Job
import org.localwake.app.CredentialOperation

/** In-memory only: neither the lease nor successful authentication is restored from a Bundle. */
internal class CredentialUiSession : ViewModel() {
    var dialog by mutableStateOf<CredentialOperation?>(null)
    var notice by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    var pending: CredentialOperation? = null
    var verified: CredentialOperation? = null
    var mutation: Job? = null
    var lease: DeviceSessionLease? = null
        private set

    fun begin(): Boolean {
        if (lease != null) return false
        lease = AndroidDeviceAccess.beginSensitive() ?: return false
        return true
    }

    fun isValid(): Boolean = lease?.let(AndroidDeviceAccess.coordinator::isValid) == true

    /** A cancelled disk mutation must finish before sensitive ownership can leave. */
    fun end() {
        val held = lease ?: return
        lease = null
        pending = null
        verified = null
        dialog = null
        val job = mutation
        if (job == null || job.isCompleted) AndroidDeviceAccess.endSensitive(held)
        else job.invokeOnCompletion { AndroidDeviceAccess.endSensitive(held) }
    }

    override fun onCleared() {
        mutation?.cancel()
        end()
    }
}
