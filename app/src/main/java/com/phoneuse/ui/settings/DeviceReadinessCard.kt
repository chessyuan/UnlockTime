package com.phoneuse.ui.settings

import android.app.AlarmManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.tr
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.routine.RoutineOwner
import com.phoneuse.service.PhoneUseAccessibilityService
import com.phoneuse.ui.theme.pu
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceOwner
import com.phoneuse.unified.GlobalUnlockSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.localwake.app.localWake
import org.localwake.app.security.CredentialState

@Composable
internal fun DeviceReadinessCard(resumeTick: Int, accountLine: String, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = pu
    val runtime = remember(context) { UnifiedRoutineRuntime.get(context) }
    val authority by runtime.authority.state.collectAsState()
    val connected by PhoneUseAccessibilityService.connected.collectAsState()
    val running by AppGraph.runner.running.collectAsState()
    val owner by AndroidDeviceAccess.coordinator.owner.collectAsState()
    var recheckVersion by remember { mutableIntStateOf(0) }
    val automaticUnlock = remember(resumeTick) { GlobalUnlockSettings(context).enabled }
    val local by produceState<Pair<CredentialState, Boolean>?>(null, resumeTick, connected, recheckVersion) {
        value = withContext(Dispatchers.IO) {
            try { context.localWake.credentials.status() to context.localWake.repository.snapshot().patternBlocked }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CredentialState.UNAVAILABLE to false }
        }
    }
    val exact = remember(resumeTick) {
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    val status = deviceReadiness(connected, automaticUnlock, local?.first, local?.second == true, exact,
        tasksAvailable = authority.owner == RoutineOwner.ROUTINE && runtime.store.ready)
    val summary = when (status) {
        DeviceReadinessStatus.CHECKING -> tr("Checking local readiness…", "기기 상태 확인 중…")
        DeviceReadinessStatus.SERVICE_REQUIRED -> tr("Accessibility is not connected", "접근성이 연결되지 않았어요")
        DeviceReadinessStatus.AUTO_UNLOCK_OFF -> tr("Automatic unlock is off", "자동 잠금 해제가 꺼져 있어요")
        DeviceReadinessStatus.FIRST_UNLOCK_REQUIRED -> tr("Unlock the phone once after restart", "다시 시작한 후 한 번 직접 잠금을 풀어 주세요")
        DeviceReadinessStatus.CREDENTIAL_REQUIRED -> tr("Set up a local unlock credential", "로컬 잠금 해제 정보를 설정해 주세요")
        DeviceReadinessStatus.CREDENTIAL_UNAVAILABLE -> tr("Local unlock needs attention", "로컬 잠금 해제 설정을 확인해 주세요")
        DeviceReadinessStatus.UNLOCK_PAUSED -> tr("Automatic unlock is paused after a failed attempt", "실패한 시도 후 자동 잠금 해제가 일시 중지됐어요")
        DeviceReadinessStatus.EXACT_ALARMS_REQUIRED -> tr("Allow exact alarms for scheduled tasks", "예약 작업에 정확한 알람을 허용해 주세요")
        DeviceReadinessStatus.READY -> tr("Device controls ready", "기기 조작 준비 완료")
        DeviceReadinessStatus.TASKS_UNAVAILABLE -> tr("Scheduled tasks are paused; check device settings", "예약 작업이 일시 중지됐어요. 기기 설정을 확인해 주세요")
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        .border(1.dp, c.line, RoundedCornerShape(16.dp)).background(c.surface, RoundedCornerShape(16.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Device readiness", "기기 준비 상태"), color = c.textSub, fontSize = 11.sp)
                Text(summary, color = c.text, fontSize = 13.sp)
            }
            TextButton(onClick = onSettings) { Text(tr("Settings", "설정"), color = c.text) }
            if (running || owner in setOf(DeviceOwner.AGENT, DeviceOwner.ROUTINE, DeviceOwner.UNLOCK)) {
                TextButton(onClick = { AppGraph.runner.stop(); AndroidDeviceAccess.cancelAll() }) {
                    Text(tr("Stop", "중지"), color = c.danger)
                }
            }
        }
        if (status == DeviceReadinessStatus.TASKS_UNAVAILABLE) {
            TaskSetupRecoveryAction(authority, runtime.store.ready, onRechecked = { recheckVersion++ })
        }
        Text(accountLine, color = c.textSub, fontSize = 11.sp)
    }
}
