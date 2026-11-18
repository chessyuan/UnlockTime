package org.localwake.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.localwake.app.AppLanguage
import com.phoneuse.BuildConfig
import com.phoneuse.R
import org.localwake.app.execution.ExecutionRecord

@Composable internal fun SettingsPage(
    connected: Boolean,
    exact: Boolean,
    language: AppLanguage,
    changeLanguage: (AppLanguage) -> Boolean,
    openPermissions: () -> Unit,
    openHistory: () -> Unit,
    openPrivacy: () -> Unit,
    openCompatibility: () -> Unit,
) {
    var languageChangeFailed by remember { mutableStateOf(false) }
    Section(stringResource(R.string.language_title)) {
        Column(Modifier.selectableGroup()) {
            AppLanguage.entries.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = option == language,
                        role = Role.RadioButton,
                        onClick = { languageChangeFailed = !changeLanguage(option) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = option == language, onClick = null)
                    Text(stringResource(option.label))
                }
            }
        }
        if (languageChangeFailed) {
            Text(stringResource(R.string.language_change_failed), color = MaterialTheme.colorScheme.error)
        }
    }
    Section {
        SettingRow(
            stringResource(R.string.permissions_title),
            stringResource(if (connected && exact) R.string.settings_permissions_ready else R.string.settings_permissions_check),
            openPermissions,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(stringResource(R.string.compat_title), stringResource(R.string.compat_settings_detail), openCompatibility)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(stringResource(R.string.history_title), stringResource(R.string.settings_history_detail), openHistory)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(stringResource(R.string.privacy_title), stringResource(R.string.settings_privacy_detail), openPrivacy)
    }
    Detail(stringResource(R.string.app_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
}

@Composable internal fun PermissionPage(exact: Boolean, connected: Boolean) {
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }
    fun open(intent: Intent) {
        error = try {
            context.startActivity(intent)
            false
        } catch (_: Exception) {
            true
        }
    }
    val appUri = Uri.parse("package:${context.packageName}")
    Section(stringResource(R.string.accessibility_service_title)) {
        Detail(if (connected) stringResource(R.string.permission_enabled)
            else stringResource(R.string.accessibility_enable_detail, stringResource(R.string.app_name)))
        if (!connected) {
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(stringResource(R.string.accessibility_open_settings))
            }
        }
    }
    Section(stringResource(R.string.exact_alarms_title)) {
        Detail(stringResource(if (exact) R.string.permission_allowed else R.string.exact_alarms_detail))
        if (!exact) {
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri)) }) {
                Text(stringResource(R.string.exact_alarms_allow))
            }
        }
    }
    Section(stringResource(R.string.system_app_settings_title)) {
        Detail(stringResource(R.string.restricted_settings_detail))
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)) }) {
            Text(stringResource(R.string.app_info_open))
        }
    }
    if (error) Text(stringResource(R.string.system_settings_open_failed), color = MaterialTheme.colorScheme.error)
}

@Composable internal fun HistoryPage(history: List<ExecutionRecord>, clear: () -> Unit) {
    val context = LocalContext.current
    var confirmingClear by remember { mutableStateOf(false) }
    if (history.isEmpty()) {
        Section { Detail(stringResource(R.string.history_empty)) }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Detail(stringResource(R.string.history_limit)) }
            TextButton(onClick = { confirmingClear = true }) { Text(stringResource(R.string.history_clear)) }
        }
        history.forEach { record -> key(record.id) {
            val title = when (record.code) {
                "MISSED_WINDOW" -> stringResource(R.string.history_missed_task)
                "ALARM_DISPATCH_FAILED" -> stringResource(R.string.history_scheduled_task)
                else -> record.name
            }
            Section(title) {
                Detail(displayTime(record.at))
                Text(resultLabel(context, record.code))
                record.apps.forEach { Detail("${appDisplayName(context, it.packageName)} · ${resultLabel(context, it.code)}") }
            }
        } }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = { TextButton(onClick = { confirmingClear = false; clear() }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable internal fun PrivacyPage() {
    Section(com.phoneuse.i18n.tr("Screen content sent to the model", "모델에 보내는 화면 내용")) {
        Detail(com.phoneuse.i18n.tr("The model can receive permitted screen text and screenshots to carry out tasks.", "모델은 허용된 화면의 글자와 캡처를 받아 작업을 수행할 수 있어요."))
    }
    Section(stringResource(R.string.privacy_on_phone_title)) {
        Detail(com.phoneuse.i18n.tr("Your saved unlock credential stays encrypted on this phone. The model cannot read it or operate the lock screen or credential editor.", "잠금 해제 정보는 이 휴대전화에 암호화해 보관하며 모델은 읽거나 잠금 화면을 조작할 수 없어요."))
        Detail(stringResource(R.string.privacy_delete_detail))
    }
    Section(stringResource(R.string.automatic_unlock_title)) {
        Detail(stringResource(R.string.privacy_manual_unlock))
        Detail(com.phoneuse.i18n.tr("Payments, sending, deletion and security confirmations still apply after unlocking.", "잠금 해제 후에도 결제, 전송, 삭제, 보안 확인은 유지돼요."))
    }
}
