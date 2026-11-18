package org.localwake.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.phoneuse.R
import org.localwake.app.execution.AppLaunchConfiguration
import java.text.Collator

private data class InstalledApp(val name: String, val configuration: AppLaunchConfiguration)

internal fun appDisplayName(context: Context, packageName: String): String = try {
    val manager = context.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        .ifBlank { packageName }
} catch (_: PackageManager.NameNotFoundException) {
    context.getString(R.string.schedule_app_unavailable)
}

@Composable internal fun AppLaunchSummary(apps: List<AppLaunchConfiguration>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(R.string.schedule_apps_title), style = MaterialTheme.typography.labelLarge)
        apps.forEachIndexed { index, app ->
            val name = remember(context, app.packageName) { appDisplayName(context, app.packageName) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.schedule_app_numbered, index + 1, name),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                Detail(if (app.delayMs == 0L) stringResource(R.string.schedule_no_wait) else {
                    stringResource(R.string.schedule_wait_display, formatDelaySeconds(app.delayMs))
                })
            }
        }
    }
}

@Composable internal fun AppLaunchFields(
    apps: List<DraftLaunchApp>,
    onChange: (List<DraftLaunchApp>) -> Unit,
    onAdd: () -> Unit,
) {
    Text(stringResource(R.string.schedule_apps_title), style = MaterialTheme.typography.titleMedium)
    Detail(stringResource(R.string.schedule_apps_help))

    fun move(index: Int, newIndex: Int) {
        onChange(apps.toMutableList().apply { add(newIndex, removeAt(index)) })
    }

    apps.forEachIndexed { index, app ->
        key(app.key) {
            AppLaunchField(
                index = index,
                app = app,
                onDelayChange = { seconds ->
                    onChange(apps.map { current ->
                        if (current.key == app.key) current.copy(delaySeconds = seconds) else current
                    })
                },
                moveUp = if (index > 0) ({ move(index, index - 1) }) else null,
                moveDown = if (index < apps.lastIndex) ({ move(index, index + 1) }) else null,
                remove = { onChange(apps.filter { it.key != app.key }) },
            )
        }
    }
    if (apps.isNotEmpty()) {
        val totalMs = apps.mapNotNull { parseDelayMs(it.delaySeconds) }
            .takeIf { it.size == apps.size }?.sum()
        if (totalMs != null && totalMs > MAX_SCHEDULE_WAIT_MS) {
            Text(stringResource(R.string.schedule_total_wait_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (apps.size < MAX_SCHEDULE_APPS) {
        OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.schedule_add_app)) }
    } else {
        Detail(stringResource(R.string.schedule_app_limit))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AppLaunchField(
    index: Int,
    app: DraftLaunchApp,
    onDelayChange: (String) -> Unit,
    moveUp: (() -> Unit)?,
    moveDown: (() -> Unit)?,
    remove: () -> Unit,
) {
    val context = LocalContext.current
    val packageName = app.configuration.packageName
    val name = remember(context, packageName) { appDisplayName(context, packageName) }
    val available = remember(context, packageName) { context.packageManager.getLaunchIntentForPackage(packageName) != null }
    val invalid = parseDelayMs(app.delaySeconds) == null
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.schedule_app_numbered, index + 1, name),
                style = MaterialTheme.typography.titleSmall)
            if (!available) Detail(stringResource(R.string.schedule_app_unavailable_package, packageName))
            OutlinedTextField(
                app.delaySeconds, onDelayChange, Modifier.fillMaxWidth().padding(top = 4.dp),
                label = { Text(stringResource(R.string.schedule_wait_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.schedule_wait_error)) }
                } else null,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                TextButton(enabled = moveUp != null, onClick = { moveUp?.invoke() }) {
                    Text(stringResource(R.string.schedule_move_up))
                }
                TextButton(enabled = moveDown != null, onClick = { moveDown?.invoke() }) {
                    Text(stringResource(R.string.schedule_move_down))
                }
                TextButton(onClick = remove) { Text(stringResource(R.string.schedule_remove_app)) }
            }
        }
    }
}

@Composable internal fun AppLaunchPicker(modifier: Modifier, select: (AppLaunchConfiguration) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    val installed by produceState<List<InstalledApp>?>(null, context) {
        value = withContext(Dispatchers.IO) { installedApps(context) }
    }
    val matches = remember(installed, query) {
        val term = query.trim()
        installed?.filter { item ->
            item.name.contains(term, ignoreCase = true) ||
                item.configuration.packageName.contains(term, ignoreCase = true)
        }
    }
    val repeatedNames = remember(installed) {
        installed.orEmpty().groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    }
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.schedule_search_apps)) },
        )
        when {
            matches == null -> Detail(stringResource(R.string.schedule_loading_apps))
            matches.isEmpty() -> Detail(stringResource(
                if (installed.orEmpty().isEmpty()) R.string.schedule_no_apps_available
                else R.string.schedule_no_app_matches,
            ))
            else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                items(matches, key = { it.configuration.packageName }) { item ->
                    AppPickerRow(item, showPackage = item.name in repeatedNames, onClick = {
                        focusManager.clearFocus()
                        select(item.configuration)
                    })
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun installedApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return manager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        .distinctBy { it.activityInfo.packageName }
        .mapNotNull { item ->
            val packageName = item.activityInfo.packageName
            if (packageName == context.packageName || manager.getLaunchIntentForPackage(packageName) == null) {
                return@mapNotNull null
            }
            val configuration = try {
                AppLaunchConfiguration(packageName)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            InstalledApp(item.loadLabel(manager).toString().ifBlank { packageName }, configuration)
        }
        .sortedWith { first, second -> collator.compare(first.name, second.name) }
}

@Composable private fun AppPickerRow(item: InstalledApp, showPackage: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val packageName = item.configuration.packageName
    val icon by produceState<ImageBitmap?>(null, context, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(64, 64).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp)) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            if (showPackage) Detail(packageName)
        }
    }
}
