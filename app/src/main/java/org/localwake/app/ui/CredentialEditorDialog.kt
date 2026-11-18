package org.localwake.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.localwake.app.MainActivity
import com.phoneuse.R
import org.localwake.app.security.CredentialState
import org.localwake.app.security.PatternValidator
import org.localwake.app.security.TextCredentialValidator
import org.localwake.app.security.UnlockMethod

private val secureDialog = DialogProperties(
    dismissOnClickOutside = false,
    securePolicy = SecureFlagPolicy.SecureOn,
)

private fun UnlockMethod.labelResource(): Int = when (this) {
    UnlockMethod.PATTERN -> R.string.credential_pattern
    UnlockMethod.PIN -> R.string.credential_pin
    UnlockMethod.PASSWORD -> R.string.credential_password
}

@Composable internal fun CredentialSummary(
    method: UnlockMethod?,
    status: CredentialState?,
    message: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Section(stringResource(R.string.credential_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (status == CredentialState.EMPTY) R.string.credential_unset
                    else method?.labelResource() ?: R.string.credential_settings),
                    fontWeight = FontWeight.SemiBold)
                Detail(stringResource(when (status) {
                    null -> R.string.credential_checking
                    CredentialState.EMPTY -> R.string.credential_add_hint
                    CredentialState.READY -> R.string.credential_is_saved
                    CredentialState.USER_LOCKED -> R.string.credential_manual_unlock
                    CredentialState.UNAVAILABLE -> R.string.credential_temporarily_unavailable
                    else -> R.string.credential_unreadable
                }))
            }
            OutlinedButton(enabled = status != null, onClick = onEdit) {
                Text(stringResource(if (status == CredentialState.EMPTY) R.string.action_set_up else R.string.action_change))
            }
        }
        if (status != null && status != CredentialState.EMPTY) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.credential_delete_saved)) }
        }
        message?.let { Detail(it) }
    }
}

@Composable internal fun CredentialEditorDialog(
    activity: MainActivity,
    initialMethod: UnlockMethod,
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onPatternSaved: (IntArray) -> Unit,
    onTextSaved: (UnlockMethod, CharArray) -> Unit,
) {
    var method by remember { mutableStateOf(initialMethod) }
    key(method) {
        val accent = MaterialTheme.colorScheme.primary.toArgb()
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val muted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        var firstPattern by remember { mutableStateOf<IntArray?>(null) }
        var firstText by remember { mutableStateOf<CharArray?>(null) }
        var entryLength by remember { mutableIntStateOf(0) }
        var error by remember { mutableStateOf<Int?>(null) }

        val grid = remember {
            if (method == UnlockMethod.PATTERN) PatternGridView(activity, accent, muted).apply {
                onCountChanged = { entryLength = it }
            } else null
        }
        val input = remember {
            if (method != UnlockMethod.PATTERN) CredentialInput(activity, method).apply {
                setTextColor(textColor)
                setHintTextColor(muted)
                backgroundTintList = ColorStateList.valueOf(accent)
                onLengthChanged = { entryLength = it }
            } else null
        }
        val confirming = firstPattern != null || firstText != null

        fun clearEntry() {
            grid?.reset()
            input?.text?.clear()
        }

        fun reset() {
            firstPattern?.fill(0)
            firstPattern = null
            firstText?.fill('\u0000')
            firstText = null
            clearEntry()
        }

        DisposableEffect(activity) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) reset()
            }
            activity.lifecycle.addObserver(observer)
            onDispose {
                activity.lifecycle.removeObserver(observer)
                reset()
                grid?.onCountChanged = null
                input?.onLengthChanged = null
            }
        }

        fun submitPattern(current: IntArray) {
            val first = firstPattern
            when {
                !PatternValidator.valid(current) -> {
                    current.fill(0)
                    clearEntry()
                    error = R.string.credential_pattern_min_points
                }
                first == null -> {
                    firstPattern = current
                    clearEntry()
                }
                !first.contentEquals(current) -> {
                    current.fill(0)
                    reset()
                    error = R.string.credential_pattern_mismatch
                }
                else -> {
                    reset()
                    onPatternSaved(current)
                }
            }
        }

        fun submitText(current: CharArray) {
            val first = firstText
            when {
                !TextCredentialValidator.valid(method, current) -> {
                    current.fill('\u0000')
                    clearEntry()
                    error = if (method == UnlockMethod.PIN) R.string.credential_pin_invalid else R.string.credential_password_invalid
                }
                first == null -> {
                    firstText = current
                    clearEntry()
                }
                !first.contentEquals(current) -> {
                    current.fill('\u0000')
                    reset()
                    error = R.string.credential_text_mismatch
                }
                else -> {
                    reset()
                    onTextSaved(method, current)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!busy) { reset(); onDismiss() } },
            properties = secureDialog,
            title = { Text(stringResource(R.string.credential_setup_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Detail(stringResource(R.string.credential_pick_method))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UnlockMethod.entries.forEach { candidate ->
                            FilterChip(
                                selected = candidate == method,
                                enabled = !busy,
                                onClick = {
                                    if (candidate != method) {
                                        reset()
                                        method = candidate
                                    }
                                },
                                label = { Text(stringResource(candidate.labelResource()), maxLines = 1) },
                            )
                        }
                    }
                    val label = when (method) {
                        UnlockMethod.PATTERN -> if (confirming) R.string.credential_pattern_repeat else R.string.credential_pattern_enter
                        UnlockMethod.PIN -> if (confirming) R.string.credential_pin_repeat else R.string.credential_pin_enter
                        UnlockMethod.PASSWORD -> if (confirming) R.string.credential_password_repeat else R.string.credential_password_enter
                    }
                    Text(stringResource(label))
                    if (grid != null) {
                        AndroidView(
                            factory = { grid },
                            modifier = Modifier.fillMaxWidth().height(208.dp),
                            update = { it.isEnabled = !busy },
                        )
                    } else if (input != null) {
                        AndroidView(
                            factory = { input },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            update = {
                                it.setHint(if (method == UnlockMethod.PIN) R.string.credential_pin_hint else R.string.credential_password_hint)
                                it.isEnabled = !busy
                            },
                        )
                    }
                    (error?.let { stringResource(it) } ?: failure)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && entryLength >= 4, onClick = {
                    error = null
                    grid?.let { submitPattern(it.pattern()) }
                    input?.let { submitText(it.copyCredential()) }
                }) {
                    Text(stringResource(when {
                        busy -> R.string.credential_saving
                        confirming -> R.string.credential_confirm_save
                        else -> R.string.action_next
                    }))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { reset(); onDismiss() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable internal fun ConfirmCredentialDeletion(
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = secureDialog,
        title = { Text(stringResource(R.string.credential_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Detail(stringResource(R.string.credential_delete_detail))
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDelete) {
                Text(
                    stringResource(if (busy) R.string.credential_deleting else R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private class CredentialInput(context: Context, method: UnlockMethod) : EditText(context) {
    var onLengthChanged: ((Int) -> Unit)? = null

    init {
        setSingleLine(true)
        inputType = if (method == UnlockMethod.PIN) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(if (method == UnlockMethod.PIN) 16 else 64))
        filterTouchesWhenObscured = true
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isLongClickable = false
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        onLengthChanged?.invoke(text?.length ?: 0)
    }

    override fun onTextContextMenuItem(id: Int): Boolean = false

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean =
        event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0 && super.onFilterTouchEventForSecurity(event)

    override fun onDetachedFromWindow() {
        text?.clear()
        super.onDetachedFromWindow()
    }

    fun copyCredential(): CharArray {
        val entry = text ?: return charArrayOf()
        return CharArray(entry.length) { index -> entry[index] }
    }
}
