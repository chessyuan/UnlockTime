package org.localwake.app

import com.phoneuse.R

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceInputFence
import org.localwake.app.ui.CredentialUiSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.localwake.app.background.BackgroundGuardService
import org.localwake.app.device.UnlockStateVerifier
import org.localwake.app.security.UnlockMethod
import org.localwake.app.ui.LocalWakeUi

internal enum class CredentialOperation { EDIT, DELETE }

class MainActivity : ComponentActivity() {
    private val credentialSession by lazy { ViewModelProvider(this)[CredentialUiSession::class.java] }
    internal var credentialDialog: CredentialOperation?
        get() = credentialSession.dialog
        private set(value) { credentialSession.dialog = value }
    internal var credentialNotice: String?
        get() = credentialSession.notice
        private set(value) { credentialSession.notice = value }
    internal var credentialBusy: Boolean
        get() = credentialSession.busy
        private set(value) { credentialSession.busy = value }
    internal var credentialRevision: Int
        get() = credentialSession.revision
        private set(value) { credentialSession.revision = value }
    private var pendingCredentialOperation: CredentialOperation?
        get() = credentialSession.pending
        set(value) { credentialSession.pending = value }
    private var verifiedCredentialOperation: CredentialOperation?
        get() = credentialSession.verified
        set(value) { credentialSession.verified = value }
    private var credentialMutation: Job?
        get() = credentialSession.mutation
        set(value) { credentialSession.mutation = value }
    private var revocationHook: AutoCloseable? = null
    internal var compatibilityExportNotice by mutableStateOf<String?>(null)
        private set
    internal var compatibilityExportBusy by mutableStateOf(false)
        private set
    private var pendingCompatibilityReport: String? = null
    private val saveCompatibilityReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pendingCompatibilityReport
        pendingCompatibilityReport = null
        compatibilityExportBusy = uri != null && report != null
        if (uri != null && report == null) compatibilityExportNotice = getString(R.string.compat_export_failed)
        if (uri != null && report != null) lifecycleScope.launch {
            try {
            compatibilityExportNotice = try {
                withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
                getString(R.string.compat_export_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { getString(R.string.compat_export_failed) }
            } finally { compatibilityExportBusy = false }
        }
    }

    internal fun exportCompatibility(method: UnlockMethod?, permissionsReady: Boolean) {
        if (!foreground || compatibilityExportBusy) return
        compatibilityExportBusy = true
        compatibilityExportNotice = null
        lifecycleScope.launch {
            try {
                pendingCompatibilityReport = withContext(Dispatchers.IO) {
                    localWake.compatibility.exportJson(method, permissionsReady)
                }
                if (!foreground) { pendingCompatibilityReport = null; compatibilityExportBusy = false; return@launch }
                val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(java.time.LocalDateTime.now())
                saveCompatibilityReport.launch("UnlockTime-compatibility-$stamp.json")
            } catch (cancelled: CancellationException) {
                pendingCompatibilityReport = null; compatibilityExportBusy = false; throw cancelled
            }
            catch (_: Exception) {
                pendingCompatibilityReport = null
                compatibilityExportBusy = false
                compatibilityExportNotice = getString(R.string.compat_export_failed)
            }
        }
    }

    @Volatile
    private var foreground = false

    private val confirmDeviceCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val operation = pendingCredentialOperation
        pendingCredentialOperation = null
        if (operation != null && result.resultCode == RESULT_OK && credentialSession.isValid()) {
            verifiedCredentialOperation = operation
            showVerifiedOperation()
        } else endCredentialSession()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.current(newBase).wrap(newBase))
    }

    internal fun changeLanguage(language: AppLanguage): Boolean {
        if (language == AppLanguage.current(this)) return true
        if (!language.persist(this)) return false
        recreate()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.applyPreference(AppLanguage.current(this).locale.toLanguageTag())
        observeCredentialRevocation()
        enableEdgeToEdge()
        setContent { LocalWakeUi(this) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        showVerifiedOperation()
        if (pendingCredentialOperation == null && verifiedCredentialOperation == null && credentialDialog == null) endCredentialSession()
        if (!BackgroundGuardService.running.value) BackgroundGuardService.restore(this)
        lifecycleScope.launch {
            try {
                localWake.scheduler.reconcile(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The home page reports storage errors.
            }
        }
    }

    override fun onPause() {
        foreground = false
        credentialDialog = null
        credentialMutation?.cancel()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && pendingCredentialOperation == null && verifiedCredentialOperation == null) endCredentialSession()
    }

    override fun onDestroy() {
        revocationHook?.close()
        revocationHook = null
        super.onDestroy()
    }

    private fun observeCredentialRevocation() {
        revocationHook?.close()
        revocationHook = credentialSession.lease?.onRevoked {
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                credentialDialog = null
                verifiedCredentialOperation = null
                credentialMutation?.cancel()
                // Keep ownership while system authentication is still open; its result closes it.
                if (pendingCredentialOperation == null && foreground) endCredentialSession()
            }
        }
    }

    private fun endCredentialSession() {
        revocationHook?.close()
        revocationHook = null
        credentialSession.end()
    }

    @Suppress("DEPRECATION")
    internal fun requestCredentialOperation(operation: CredentialOperation) {
        if (!foreground || pendingCredentialOperation != null || credentialDialog != null || credentialBusy) return
        credentialNotice = null
        try {
            val keyguard = getSystemService(KeyguardManager::class.java)
            val intent = if (keyguard.isDeviceSecure) {
                keyguard.createConfirmDeviceCredentialIntent(
                    getString(R.string.verify_screen_lock_title),
                    getString(R.string.verify_screen_lock_detail),
                )
            } else null
            if (intent == null) {
                credentialNotice = getString(R.string.verify_enable_screen_lock)
                return
            }
            if (!credentialSession.begin()) {
                credentialNotice = tr("Another protected operation is open. Finish it first.", "보호된 작업을 먼저 마쳐 주세요.")
                return
            }
            pendingCredentialOperation = operation
            observeCredentialRevocation()
            val requestedLease = credentialSession.lease
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var launched = false
                try {
                    // Job cancellation does not stop an accepted Android gesture. Keep the sensitive
                    // reservation while its real callback drains, and never expose authentication early.
                    val idle = DeviceInputFence.shared.awaitIdle()
                    if (!idle || !foreground || requestedLease == null || credentialSession.lease !== requestedLease ||
                        !credentialSession.isValid()
                    ) {
                        credentialNotice = getString(R.string.verify_open_failed)
                        return@launch
                    }
                    AndroidDeviceAccess.coordinator.publish(requestedLease) {
                        confirmDeviceCredential.launch(intent)
                    }
                    launched = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    credentialNotice = getString(R.string.verify_open_failed)
                } finally {
                    if (!launched && credentialSession.lease === requestedLease) {
                        pendingCredentialOperation = null
                        endCredentialSession()
                    }
                }
            }
        } catch (_: Exception) {
            pendingCredentialOperation = null
            endCredentialSession()
            credentialNotice = getString(R.string.verify_open_failed)
        }
    }

    private fun showVerifiedOperation() {
        if (!foreground) return
        val operation = verifiedCredentialOperation ?: return
        verifiedCredentialOperation = null
        if (credentialSession.isValid() && UnlockStateVerifier(this).fullyUnlocked()) credentialDialog = operation
        else endCredentialSession()
    }

    internal fun dismissCredentialOperation() {
        if (!credentialBusy) {
            credentialDialog = null
            credentialNotice = null
            endCredentialSession()
        }
    }

    internal fun saveCredential(pattern: IntArray) {
        mutateCredential(CredentialOperation.EDIT, { pattern.fill(0) }) {
            localWake.credentials.savePattern(pattern)
        }
    }

    internal fun saveCredential(method: UnlockMethod, value: CharArray) {
        mutateCredential(CredentialOperation.EDIT, { value.fill('\u0000') }) {
            localWake.credentials.saveText(method, value)
        }
    }

    internal fun deleteCredential() {
        mutateCredential(CredentialOperation.DELETE, {}) {
            localWake.credentials.clear()
        }
    }

    private fun mutateCredential(operation: CredentialOperation, wipe: () -> Unit, mutate: () -> Unit) {
        val lease = credentialSession.lease
        if (!foreground || credentialDialog != operation || credentialBusy || lease == null || !credentialSession.isValid()) {
            wipe()
            return
        }
        credentialBusy = true
        credentialNotice = null
        credentialMutation = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    localWake.scheduler.mutex.withLock {
                        ensureActive()
                        AndroidDeviceAccess.coordinator.requireValid(lease)
                        check(foreground && UnlockStateVerifier(this@MainActivity).fullyUnlocked())
                        localWake.compatibility.invalidate()
                        mutate()
                    }
                }
                credentialDialog = null
                credentialNotice = getString(
                    if (operation == CredentialOperation.EDIT) R.string.credential_saved else R.string.credential_deleted,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                credentialNotice = getString(
                    if (operation == CredentialOperation.EDIT) R.string.credential_save_failed else R.string.credential_delete_failed,
                )
            } finally {
                wipe()
                credentialBusy = false
                credentialRevision++
                if (credentialDialog == null) endCredentialSession()
            }
        }
    }
}
