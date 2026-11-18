package org.localwake.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.phoneuse.R
import com.phoneuse.i18n.tr
import com.phoneuse.routine.UnifiedRoutineRuntime
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.RoutineOwner
import com.phoneuse.routine.MigrationPhase
import com.phoneuse.unified.GlobalUnlockSettings
import com.phoneuse.ui.settings.TaskSetupRecoveryAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.localwake.app.AppLanguage
import org.localwake.app.CredentialOperation
import org.localwake.app.MainActivity
import org.localwake.app.accessibility.LocalWakeAccessibilityService
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.localWake
import org.localwake.app.scheduler.LocalState
import org.localwake.app.security.CredentialFailure
import org.localwake.app.security.CredentialState
import org.localwake.app.security.UnlockMethod
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

typealias RunAction = (suspend () -> String) -> Unit

internal fun displayTime(ms: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private enum class Screen { DEVICE, PERMISSIONS, HISTORY, PRIVACY, COMPATIBILITY }

/** Native device settings in the unified APK; scheduled tasks live in the AI task screen. */
@Composable internal fun LocalWakeUi(activity: MainActivity) {
    val app = activity.localWake
    val runtime = remember(activity) { UnifiedRoutineRuntime.get(activity) }
    val authority by runtime.authority.state.collectAsState()
    val routines by runtime.store.routines.collectAsState()
    val tasksReady = authority.owner == RoutineOwner.ROUTINE && runtime.store.ready
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.DEVICE) }
    var state by remember { mutableStateOf(LocalState()) }
    var credential by remember { mutableStateOf<CredentialState?>(null) }
    var method by remember { mutableStateOf<UnlockMethod?>(null) }
    var exact by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var automaticUnlock by remember { mutableStateOf(GlobalUnlockSettings(activity).enabled) }
    var testScheduling by remember { mutableStateOf(false) }
    var recheckVersion by remember { mutableIntStateOf(0) }
    var testRoutineId by rememberSaveable { mutableStateOf<Long?>(null) }
    val testPending = routines.any { it.id == testRoutineId && it.enabled }
    val connected by LocalWakeAccessibilityService.instance.collectAsState()
    val serviceConnected by com.phoneuse.service.PhoneUseAccessibilityService.connected.collectAsState()
    val serviceReady = serviceConnected && connected?.isReady() == true

    LaunchedEffect(activity.credentialRevision, recheckVersion) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    state = app.repository.snapshot()
                    exact = app.scheduler.canSchedule()
                    automaticUnlock = GlobalUnlockSettings(activity).enabled
                    val stored = withContext(Dispatchers.IO) {
                        app.scheduler.mutex.withLock {
                            app.credentials.status() to try { app.credentials.method() } catch (_: CredentialFailure) { null }
                        }
                    }
                    credential = stored.first
                    method = stored.second
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = activity.getString(R.string.home_load_failed) }
                delay(2_000)
            }
        }
    }

    fun navigate(destination: Screen) { screen = destination; message = null }
    fun back() { if (screen == Screen.DEVICE) activity.finish() else navigate(Screen.DEVICE) }
    BackHandler(onBack = ::back)

    UnlockphoneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::back) { Text(stringResource(R.string.action_back)) }
                        Icon(painterResource(R.drawable.ic_unlocktime), null, Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(when (screen) {
                            Screen.DEVICE -> tr("Device settings", "기기 설정")
                            Screen.PERMISSIONS -> stringResource(R.string.permissions_title)
                            Screen.HISTORY -> stringResource(R.string.history_title)
                            Screen.PRIVACY -> stringResource(R.string.privacy_title)
                            Screen.COMPATIBILITY -> stringResource(R.string.compat_title)
                        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    key(screen) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            message?.let { Detail(it) }
                            when (screen) {
                                Screen.DEVICE -> {
                                    if (!tasksReady) Section(tr("Scheduled tasks are paused", "예약 작업이 일시 중지됐어요")) {
                                        Detail(if (authority.phase == MigrationPhase.RECOVERY_REQUIRED || !runtime.store.ready)
                                            tr("Task data needs recovery before it can run. Existing data has been kept.", "기존 데이터는 보관돼 있어요. 작업을 실행하려면 데이터 복구가 필요해요.")
                                        else tr("Task preparation is not complete. Tasks cannot run yet.", "작업 준비가 완료되지 않아 아직 실행할 수 없어요."))
                                        TaskSetupRecoveryAction(authority, runtime.store.ready, onRechecked = { recheckVersion++ })
                                    }
                                    CredentialSummary(method, credential,
                                        activity.credentialNotice.takeIf { activity.credentialDialog == null },
                                        onEdit = { activity.requestCredentialOperation(CredentialOperation.EDIT) },
                                        onDelete = { activity.requestCredentialOperation(CredentialOperation.DELETE) })
                                    Section(tr("Test local unlock", "로컬 잠금 해제 테스트")) {
                                        Detail(stringResource(R.string.test_instructions))
                                        if (!automaticUnlock) Detail(tr("Enable automatic unlock in the main settings before testing.", "테스트하려면 메인 설정에서 자동 잠금 해제를 켜 주세요."))
                                        Button(onClick = {
                                            testScheduling = true
                                            scope.launch {
                                                try {
                                                    var savedId: Long? = null
                                                    val saved = RoutineSystem.get(activity).edit {
                                                        savedId = save(localUnlockTestRoutine(activity.getString(R.string.full_test_name), System.currentTimeMillis())).id
                                                    }
                                                    if (saved) {
                                                        testRoutineId = savedId
                                                        message = activity.getString(R.string.full_test_prompt)
                                                    } else error = tr("Couldn't save the test. Check task recovery status.", "테스트를 저장하지 못했어요. 작업 복구 상태를 확인해 주세요.")
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { error = activity.getString(R.string.action_failed) }
                                                finally { testScheduling = false }
                                            }
                                        }, enabled = tasksReady && automaticUnlock && serviceReady && exact && credential == CredentialState.READY &&
                                            !state.patternBlocked && !testScheduling && !testPending, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.test_start))
                                        }
                                        state.history.firstOrNull()?.code?.let { Detail(stringResource(R.string.test_recent_result, resultLabel(activity, it))) }
                                    }
                                    if (state.patternBlocked) Section(stringResource(R.string.unlock_paused)) {
                                        Detail(stringResource(R.string.unlock_paused_detail))
                                        TextButton(onClick = {
                                            scope.launch {
                                                try {
                                                    app.executionMutex.withLock {
                                                        app.scheduler.mutex.withLock {
                                                            check(UnlockStateVerifier(activity).fullyUnlocked())
                                                            app.repository.setPatternBlocked(false)
                                                        }
                                                    }
                                                    state = app.repository.snapshot()
                                                    message = activity.getString(R.string.unlock_resumed)
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { error = activity.getString(R.string.action_failed) }
                                            }
                                        }) { Text(stringResource(R.string.unlock_resume_action)) }
                                    }
                                    SettingsPage(serviceReady, exact,
                                        language = AppLanguage.current(activity), changeLanguage = activity::changeLanguage,
                                        openPermissions = { navigate(Screen.PERMISSIONS) },
                                        openHistory = { navigate(Screen.HISTORY) }, openPrivacy = { navigate(Screen.PRIVACY) },
                                        openCompatibility = { navigate(Screen.COMPATIBILITY) })
                                    Detail(tr("Manage scheduled tasks from Routines on the main screen.", "예약 작업은 메인 화면의 루틴에서 관리하세요."))
                                    BackgroundGuardCard()
                                }
                                Screen.PERMISSIONS -> PermissionPage(exact, serviceReady)
                                Screen.HISTORY -> HistoryPage(state.history, clear = {
                                    scope.launch {
                                        try {
                                            app.repository.clearHistory()
                                            state = app.repository.snapshot()
                                            message = activity.getString(R.string.history_cleared)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = activity.getString(R.string.action_failed) }
                                    }
                                })
                                Screen.PRIVACY -> PrivacyPage()
                                Screen.COMPATIBILITY -> CompatibilityPage(activity, method, serviceReady && exact)
                            }
                        }
                    }
                }
            }
        }
        when (activity.credentialDialog) {
            CredentialOperation.EDIT -> CredentialEditorDialog(activity, method ?: UnlockMethod.PATTERN,
                activity.credentialBusy, activity.credentialNotice, activity::dismissCredentialOperation,
                activity::saveCredential, activity::saveCredential)
            CredentialOperation.DELETE -> ConfirmCredentialDeletion(activity.credentialBusy, activity.credentialNotice,
                activity::dismissCredentialOperation, activity::deleteCredential)
            null -> Unit
        }
    }
}
