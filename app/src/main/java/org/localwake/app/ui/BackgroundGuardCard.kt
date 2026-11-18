package org.localwake.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.phoneuse.R
import org.localwake.app.background.BackgroundGuardService

@Composable internal fun BackgroundGuardCard() {
    val context = LocalContext.current
    val running by BackgroundGuardService.running.collectAsState()
    var message by remember { mutableStateOf<Int?>(null) }
    val switchDescription = stringResource(R.string.guard_switch)
    fun start() {
        message = if (BackgroundGuardService.startFromUser(context)) null else R.string.guard_start_failed
    }
    fun openNotificationSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: Exception) {
            message = R.string.guard_open_failed
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) start() else message = R.string.guard_notification_permission
    }
    Section {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guard_channel_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Detail(stringResource(if (running) R.string.guard_enabled else R.string.guard_disabled))
            }
            Switch(modifier = Modifier.semantics { contentDescription = switchDescription }, checked = running, onCheckedChange = { enabled ->
                if (!enabled) {
                    try { BackgroundGuardService.stopFromUser(context); message = R.string.guard_stopped }
                    catch (_: Exception) { message = R.string.guard_stop_failed }
                } else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                    openNotificationSettings()
                } else start()
            })
        }
        message?.let { Detail(stringResource(it)) }
        if (message == R.string.guard_notification_permission) {
            TextButton(onClick = ::openNotificationSettings) { Text(stringResource(R.string.guard_notification_settings)) }
        }
    }
}
