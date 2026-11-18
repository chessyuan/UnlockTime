package org.localwake.app.compatibility

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import com.phoneuse.BuildConfig
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.security.UnlockMethod
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal enum class CompatibilityStatus {
    // Profile support requires a verified profile source. No runtime profiles are bundled.
    VERIFIED, SUPPORTED_BY_PROFILE, DETECTED_UNTESTED, UNSUPPORTED, UNKNOWN, PERMISSION_BLOCKED,
}

internal data class CompatibilitySummary(
    val status: CompatibilityStatus,
    val lastCheckedAt: Long?,
    val adapterId: String?,
    val lastResult: String?,
    val evidence: String?,
)

internal class ObservationToken(internal val id: String) {
    internal var consumed = false
}

internal data class CompatibilityDevice(
    val brand: String,
    val model: String,
    val api: Int,
    val release: String,
    val oemBuild: String,
    val fingerprintDigest: String,
    val appVersionCode: Int,
    val isEmulator: Boolean,
    val appVersionName: String? = null,
    val buildType: String? = null,
    val apkSha256: String? = null,
) {
    val validApkSha256 = apkSha256?.takeIf(sha256Hex::matches)
    val binding: String? = validApkSha256?.let { apk ->
        sha256(listOf(fingerprintDigest, brand, model, api, release, oemBuild, appVersionCode, isEmulator, apk)
            .joinToString("\u0000"))
    }

    fun report(): JSONObject = JSONObject()
        .put("brand", safeBuildLabel(brand))
        .put("model", safeBuildLabel(model))
        .put("api", api)
        .put("androidRelease", safeBuildLabel(release))
        .put("oemBuild", safeBuildLabel(oemBuild))

    companion object {
        fun current(context: Context): CompatibilityDevice {
            val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
            val brand = Build.BRAND.lowercase(Locale.ROOT)
            val model = Build.MODEL.lowercase(Locale.ROOT)
            val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
            val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
            val product = Build.PRODUCT.lowercase(Locale.ROOT)
            val emulator = fingerprint.startsWith("generic") ||
                brand.startsWith("generic") || manufacturer == "genymotion" ||
                hardware in setOf("goldfish", "ranchu", "vbox86") ||
                product.startsWith("sdk") || product in setOf("google_sdk", "simulator", "vbox86p") ||
                "emulator" in model || "android sdk" in model
            return CompatibilityDevice(
                Build.BRAND, Build.MODEL, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.DISPLAY,
                sha256(Build.FINGERPRINT), BuildConfig.VERSION_CODE, emulator,
                BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, installedApkSha256(context.applicationInfo.sourceDir),
            )
        }
    }
}

internal class CompatibilityStore internal constructor(
    private val device: CompatibilityDevice,
    private val readLocal: () -> String?,
    private val writeLocal: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        CompatibilityDevice.current(context),
        AtomicFile(File(context.noBackupFilesDir, "compatibility-v1.json")),
    )

    private constructor(device: CompatibilityDevice, file: AtomicFile) : this(
        device,
        {
            file.openRead().use { stream ->
                val bytes = ByteArray(MAX_RECORD_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = stream.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size <= MAX_RECORD_BYTES) String(bytes, 0, size, Charsets.UTF_8) else null
            }
        },
        { json ->
            val stream = file.startWrite()
            try {
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        },
    )

    private var storageFailed = false

    fun observe(
        method: UnlockMethod,
        geometry: DisplayGeometry,
        nodes: List<KeyguardNode>,
        controlIndices: List<Int>,
        adapterId: String,
        adapterVersion: Int,
    ): ObservationToken = synchronized(lock) {
        val token = ObservationToken(UUID.randomUUID().toString())
        val screen = CompatibilityScreen.from(geometry)
        val adapter = CompatibilityAdapter.read(adapterId, adapterVersion)
        val allowsInput = method != UnlockMethod.PATTERN || geometry.permitsCoordinateInput
        val controls = if (screen != null && screen.displayId == 0 && adapter != null && allowsInput) {
            selectedControls(method, geometry, nodes, controlIndices)
        } else {
            null
        }
        writeRecord(CompatibilityRecord(
            method = method,
            checkedAt = now(),
            pendingToken = token.id,
            screen = screen,
            adapter = adapter.takeIf { controls != null },
            controls = controls.orEmpty(),
            structureDigest = controls?.let { structureDigest(method, screen, adapter, it) },
        ))
        token
    }

    fun finish(
        token: ObservationToken,
        code: String,
        credentialSequenceCompleted: Boolean,
        fullyUnlocked: Boolean,
    ): Unit = synchronized(lock) {
        if (token.consumed) return@synchronized
        token.consumed = true
        val current = readRecord() ?: return@synchronized
        if (current.pendingToken != token.id) return@synchronized
        val result = knownResult(code)
        writeRecord(current.copy(
            checkedAt = now(),
            pendingToken = null,
            verified = current.structureDigest != null && result in successCodes &&
                credentialSequenceCompleted && fullyUnlocked && !device.isEmulator,
            result = result,
        ))
    }

    fun invalidate() = synchronized(lock) {
        check(writeRecord(null)) { "COMPATIBILITY_INVALIDATION_FAILED" }
    }

    fun recordUnavailable(method: UnlockMethod, reason: String): Unit = synchronized(lock) {
        writeRecord(CompatibilityRecord(method, now(), result = knownResult(reason)))
    }

    fun summary(method: UnlockMethod?, permissionsReady: Boolean): CompatibilitySummary = synchronized(lock) {
        summarize(method?.let { readRecord(it) }, permissionsReady)
    }

    fun exportJson(method: UnlockMethod?, permissionsReady: Boolean): String = synchronized(lock) {
        val record = method?.let { readRecord(it) }
        val summary = summarize(record, permissionsReady)
        val report = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersionCode", device.appVersionCode)
            .put("appVersionName", safeBuildLabel(device.appVersionName))
            .put("buildType", safeBuildLabel(device.buildType))
            .put("apkSha256", device.validApkSha256)
            .put("device", device.report())
            .put("authMethod", method?.name)
            .put("status", summary.status.name)
            .put("stage", when {
                record?.result != null && record.structureDigest == null -> "UNAVAILABLE"
                record?.result != null -> "OUTCOME"
                record != null -> "OBSERVATION"
                else -> "NO_OBSERVATION"
            })
            .put("time", summary.lastCheckedAt)
            .put("adapterId", record?.adapter?.id)
            .put("adapterVersion", record?.adapter?.version)
            .put("screen", record?.screen?.json())
            .put("result", summary.lastResult)
            .put("evidence", summary.evidence)
        record?.let { report.put("controls", diagnosticsControls(it.controls)) }
        report.toString(2)
    }

    private fun summarize(record: CompatibilityRecord?, permissionsReady: Boolean): CompatibilitySummary {
        val status = when {
            !permissionsReady -> CompatibilityStatus.PERMISSION_BLOCKED
            record?.screen?.displayId?.let { it > 0 } == true -> CompatibilityStatus.UNSUPPORTED
            record?.structureDigest == null && record?.result in unsupportedReasons -> CompatibilityStatus.UNSUPPORTED
            record?.structureDigest == null -> CompatibilityStatus.UNKNOWN
            record.verified && !device.isEmulator -> CompatibilityStatus.VERIFIED
            else -> CompatibilityStatus.DETECTED_UNTESTED
        }
        return CompatibilitySummary(
            status, record?.checkedAt, record?.adapter?.id, record?.result,
            when {
                record?.structureDigest == null -> null
                device.isEmulator -> "EMULATOR"
                record.verified -> "PHYSICAL_UNLOCK"
                else -> "PHYSICAL_OBSERVATION"
            },
        )
    }

    private fun readRecord(method: UnlockMethod? = null): CompatibilityRecord? {
        if (storageFailed) return null
        val binding = device.binding ?: return null
        return try {
            val raw = readLocal()?.takeIf { it.length <= MAX_RECORD_BYTES } ?: return null
            val data = JSONObject(raw)
            if (data.opt("schemaVersion") != SCHEMA_VERSION || data.opt("deviceBinding") != binding) return null
            CompatibilityRecord.read(data).takeIf { method == null || it?.method == method }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRecord(record: CompatibilityRecord?): Boolean {
        try {
            val binding = device.binding
            val data = record?.takeIf { binding != null }?.json() ?: JSONObject()
            data.put("schemaVersion", SCHEMA_VERSION)
            data.put("deviceBinding", binding)
            val encoded = data.toString()
            writeLocal(encoded)
            storageFailed = readLocal() != encoded
        } catch (_: Exception) {
            storageFailed = true
        }
        return !storageFailed
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_RECORD_BYTES = 16_384
        val lock = Any()
    }
}

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()

private val sha256Hex = Regex("[0-9a-f]{64}")
private val buildLabel = Regex("[A-Za-z0-9][A-Za-z0-9 ._()\\-]{0,79}")

private fun safeBuildLabel(value: String?): String? = value?.trim()?.takeIf(buildLabel::matches)

private fun installedApkSha256(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHexString()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
