package org.localwake.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoneuse.R
import org.localwake.app.localWake
import org.localwake.app.scheduler.ScheduleModel

private val allWeekdays = (1..7).toSet()
private data class TaskBeingEdited(val task: ScheduleModel, val creating: Boolean)

@Composable internal fun SchedulePage(tasks: List<ScheduleModel>, run: RunAction) {
    val context = LocalContext.current
    val app = context.localWake
    var editing by remember { mutableStateOf<TaskBeingEdited?>(null) }
    var showFinished by remember { mutableStateOf(false) }
    val visible = tasks.filter { showFinished || it.oneShotAt == null || it.enabled }
        .sortedWith(compareBy<ScheduleModel>({ it.oneShotAt != null }, { it.hour }, { it.minute }, { it.name }))
    val defaultName = stringResource(R.string.schedule_default_name)
    val deleted = stringResource(R.string.schedule_deleted)
    val saved = stringResource(R.string.result_armed)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.schedule_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                enabled = tasks.size < 32,
                onClick = { editing = TaskBeingEdited(ScheduleModel(name = defaultName), creating = true) },
            ) { Text(stringResource(R.string.schedule_add_task)) }
        }
        if (tasks.size >= 32) Detail(stringResource(R.string.schedule_task_limit))
        if (visible.isEmpty()) Detail(stringResource(R.string.schedule_empty))
        visible.forEach { task ->
            key(task.id) {
                TaskCard(
                    task = task,
                    onToggle = { enabled -> run { app.scheduler.update(task.id) { it.copy(enabled = enabled) } } },
                    onEdit = { editing = TaskBeingEdited(task, creating = false) },
                    onDelete = {
                        run {
                            app.scheduler.delete(task.id)
                            deleted
                        }
                    },
                )
            }
        }
        if (tasks.any { it.oneShotAt != null && !it.enabled }) {
            TextButton(onClick = { showFinished = !showFinished }) {
                Text(stringResource(if (showFinished) R.string.schedule_hide_tests else R.string.schedule_show_tests))
            }
        }
    }

    editing?.let { (original, creating) ->
        ScheduleEditor(
            task = original,
            creating = creating,
            dismiss = { editing = null },
            save = { edited ->
                run {
                    val result = if (creating) {
                        app.scheduler.save(edited)
                    } else {
                        app.scheduler.update(original.id) { current -> mergeTaskEdits(original, current, edited) }
                    }
                    if (result == "ARMED" || result == "INACTIVE") saved else result
                }
                editing = null
            },
        )
    }
}

@Composable private fun TaskCard(
    task: ScheduleModel,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val days = stringArrayResource(R.array.schedule_weekdays)
    val daySeparator = stringResource(R.string.schedule_day_separator)
    val timing = task.oneShotAt?.let { stringResource(R.string.schedule_one_time, displayTime(it)) } ?: run {
        val time = stringResource(R.string.schedule_clock, task.hour, task.minute)
        val repeat = if (task.weekdays == allWeekdays) {
            stringResource(R.string.schedule_every_day)
        } else {
            task.weekdays.sorted().joinToString(daySeparator) { days[it - 1] }
        }
        stringResource(R.string.schedule_timing, time, repeat)
    }
    val toggleDescription = stringResource(R.string.schedule_toggle, task.name)
    Section {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Detail(timing)
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = toggleDescription },
            )
        }
        if (task.apps.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AppLaunchSummary(task.apps)
        } else if (task.oneShotAt == null) {
            Detail(stringResource(R.string.schedule_unlock_only))
        }
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            if (task.oneShotAt == null) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.schedule_edit)) }
            }
        }
    }
}

@Composable private fun ScheduleEditor(
    task: ScheduleModel,
    creating: Boolean,
    dismiss: () -> Unit,
    save: (ScheduleModel) -> Unit,
) {
    var draft by remember(task.id) { mutableStateOf(ScheduleDraft.from(task)) }
    var nextAppKey by remember(task.id) { mutableIntStateOf(task.apps.size) }
    var choosingApp by remember(task.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val edited = draft.editedTask(task)

    Dialog(
        onDismissRequest = { if (choosingApp) choosingApp = false else dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                .widthIn(max = 520.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                val title = when {
                    choosingApp -> R.string.schedule_choose_app
                    creating -> R.string.schedule_new_title
                    else -> R.string.schedule_edit_title
                }
                Text(
                    stringResource(title),
                    Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (choosingApp) {
                    AppLaunchPicker(
                        modifier = Modifier.weight(1f),
                        select = { configuration ->
                            draft = draft.copy(apps = draft.apps + DraftLaunchApp(nextAppKey, configuration))
                            nextAppKey++
                            choosingApp = false
                        },
                    )
                } else {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ScheduleFields(draft, onChange = { draft = it })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AppLaunchFields(
                            apps = draft.apps,
                            onChange = { draft = draft.copy(apps = it) },
                            onAdd = { choosingApp = true },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (choosingApp) {
                        TextButton(onClick = { choosingApp = false }) { Text(stringResource(R.string.action_cancel)) }
                    } else {
                        TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
                        Button(enabled = edited != null, onClick = { edited?.let(save) }) {
                            Text(stringResource(R.string.schedule_save))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ScheduleFields(draft: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    val labels = stringArrayResource(R.array.schedule_weekdays)
    OutlinedTextField(
        draft.name, { onChange(draft.copy(name = it)) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.schedule_name)) },
        singleLine = true,
        isError = !draft.validName,
        supportingText = if (!draft.validName) {
            { Text(stringResource(R.string.schedule_name_error)) }
        } else null,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
        OutlinedTextField(
            draft.hour, { onChange(draft.copy(hour = it)) }, Modifier.weight(1f),
            label = { Text(stringResource(R.string.schedule_hour)) },
            singleLine = true, keyboardOptions = numeric, isError = !draft.validHour,
        )
        OutlinedTextField(
            draft.minute, { onChange(draft.copy(minute = it)) }, Modifier.weight(1f),
            label = { Text(stringResource(R.string.schedule_minute)) },
            singleLine = true, keyboardOptions = numeric, isError = !draft.validMinute,
        )
    }
    if (!draft.validHour || !draft.validMinute) {
        Text(stringResource(R.string.schedule_time_error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.schedule_repeat), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(
                enabled = draft.weekdays != allWeekdays,
                onClick = { onChange(draft.copy(weekdays = allWeekdays)) },
            ) { Text(stringResource(R.string.schedule_every_day)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allWeekdays.forEach { day ->
                FilterChip(
                    selected = day in draft.weekdays,
                    onClick = {
                        val days = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
                        onChange(draft.copy(weekdays = days))
                    },
                    label = { Text(labels[day - 1]) },
                )
            }
        }
        if (!draft.validWeekdays) {
            Text(stringResource(R.string.schedule_repeat_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
