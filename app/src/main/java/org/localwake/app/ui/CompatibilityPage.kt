package org.localwake.app.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.localwake.app.MainActivity
import com.phoneuse.R
import org.localwake.app.compatibility.CompatibilityStatus
import org.localwake.app.localWake
import org.localwake.app.security.UnlockMethod
import org.localwake.app.scheduler.ExecutionMode
import org.localwake.app.scheduler.ScheduleModel

internal fun compatibilityLabel(status: CompatibilityStatus) = when (status) {
    CompatibilityStatus.VERIFIED -> R.string.compat_verified
    CompatibilityStatus.SUPPORTED_BY_PROFILE -> R.string.compat_profile
    CompatibilityStatus.DETECTED_UNTESTED -> R.string.compat_detected
    CompatibilityStatus.UNSUPPORTED -> R.string.compat_unsupported
    CompatibilityStatus.UNKNOWN -> R.string.compat_unknown
    CompatibilityStatus.PERMISSION_BLOCKED -> R.string.compat_permissions
}

@Composable internal fun CompatibilityPage(activity: MainActivity, method: UnlockMethod?, permissionsReady: Boolean) {
    var status by remember { mutableStateOf(CompatibilityStatus.UNKNOWN) }
    var checkedAt by remember { mutableStateOf<Long?>(null) }
    var emulator by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(method, permissionsReady) {
        while (true) {
            val summary = withContext(Dispatchers.IO) { activity.localWake.compatibility.summary(method, permissionsReady) }
            status = summary.status
            checkedAt = summary.lastCheckedAt
            emulator = summary.evidence == "EMULATOR"
            delay(2_000)
        }
    }
    Section(stringResource(compatibilityLabel(status))) {
        Detail("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        Detail(stringResource(R.string.compat_detail))
        checkedAt?.let { Detail(stringResource(R.string.compat_last_checked, displayTime(it))) }
        if (emulator) Detail(stringResource(R.string.compat_emulator))
        Detail(stringResource(R.string.compat_inspect_detail))
        OutlinedButton(enabled = method != null && permissionsReady && !checking, onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    try {
                        val app = activity.localWake
                        val pending = app.repository.snapshot().schedules.any { it.enabled && it.oneShotAt != null }
                        val result = if (pending) "TEST_ALREADY_PENDING" else app.scheduler.save(
                            ScheduleModel(name = activity.getString(R.string.compat_inspect_name),
                                mode = ExecutionMode.INSPECT_GRID, oneShotAt = System.currentTimeMillis() + 10_000))
                        checkMessage = if (result == "ARMED") activity.getString(R.string.compat_inspect_prompt)
                            else resultLabel(activity, result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { checkMessage = activity.getString(R.string.action_failed) }
                    finally { checking = false }
                }
            }
        }) { Text(stringResource(R.string.compat_inspect)) }
        checkMessage?.let { Detail(it) }
    }
    Section {
        Detail(stringResource(R.string.compat_export_detail))
        OutlinedButton(enabled = !activity.compatibilityExportBusy, onClick = { activity.exportCompatibility(method, permissionsReady) }) {
            Text(stringResource(R.string.compat_export))
        }
        activity.compatibilityExportNotice?.let { Detail(it) }
    }
}
