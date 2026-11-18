package com.phoneuse.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.phoneuse.i18n.tr
import com.phoneuse.routine.RoutineAuthorityState

/** Used by both the AI readiness card and native device settings, without exposing recovery to tools. */
@Composable
internal fun TaskSetupRecoveryAction(
    authority: RoutineAuthorityState,
    storeReady: Boolean,
    modifier: Modifier = Modifier,
    onRechecked: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val action = taskSetupAction(authority, storeReady)
    val retryState by TaskSetupRecoveryUi.state.collectAsState()
    val busy = retryState == TaskSetupRetryState.CHECKING
    LaunchedEffect(retryState) {
        if (retryState == TaskSetupRetryState.SUCCEEDED || retryState == TaskSetupRetryState.FAILED) onRechecked()
    }
    if (action == TaskSetupAction.READY) return

    Column(modifier) {
        if (action == TaskSetupAction.REPAIR_REQUIRED) {
            Text(tr("Task records need repair. Your data has been kept; automatic retry is unavailable.",
                "작업 기록을 복구해야 해요. 데이터는 보관돼 있으며 자동 재시도는 사용할 수 없어요."), fontSize = 12.sp)
        } else {
            Text(tr("Your saved tasks are kept. Check setup again to resume scheduling.",
                "저장된 작업은 보관돼 있어요. 설정을 다시 확인해 예약을 재개하세요."), fontSize = 12.sp)
            TextButton(enabled = !busy, onClick = {
                TaskSetupRecoveryUi.retry(context)
            }) {
                Text(if (busy) tr("Checking task setup…", "작업 설정 확인 중…")
                    else tr("Retry task setup", "작업 설정 다시 확인"))
            }
            if (retryState == TaskSetupRetryState.FAILED) Text(tr("Task setup is still paused. Your saved data has been kept.",
                "작업 설정이 아직 일시 중지돼 있어요. 저장된 데이터는 보관돼 있어요."), fontSize = 12.sp)
        }
    }
}
