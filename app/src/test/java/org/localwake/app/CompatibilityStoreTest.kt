package org.localwake.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localwake.app.compatibility.CompatibilityDevice
import org.localwake.app.compatibility.CompatibilityStatus
import org.localwake.app.compatibility.CompatibilityStore
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.device.ScreenBounds
import org.localwake.app.security.UnlockMethod

class CompatibilityStoreTest {
    private val method = UnlockMethod.PATTERN
    private val geometry = DisplayGeometry(
        ScreenBounds(0, 0, 1080, 2400), 3f, magnificationScale = 1f, magnificationActivated = false,
    )
    private val root = KeyguardNode(0, null, "com.android.systemui", 7, null, null, geometry.bounds)
    private val grid = KeyguardNode(
        1, 0, "com.android.systemui", 7, "com.android.systemui:id/lockPatternView",
        "com.android.internal.widget.LockPatternView", ScreenBounds(180, 900, 900, 1620),
    )
    private val device = CompatibilityDevice(
        brand = "Example",
        model = "Test Phone",
        api = 36,
        release = "16",
        oemBuild = "ABC.123",
        fingerprintDigest = "a".repeat(64),
        appVersionCode = 10,
        isEmulator = false,
        appVersionName = "0.4.0",
        buildType = "debug",
        apkSha256 = "b".repeat(64),
    )
    private var disk: String? = null
    private var checkTime = 123L

    private fun store(device: CompatibilityDevice = this.device) =
        CompatibilityStore(device, { disk }, { disk = it }, { checkTime })

    private fun CompatibilityStore.detect(node: KeyguardNode = grid, screen: DisplayGeometry = geometry) =
        observe(method, screen, listOf(root, node), listOf(1), "aosp", 1)

    @Test fun onlyCompletedInputAndConfirmedSystemUnlockVerifyPhysicalDevice() {
        val store = store()
        val first = store.detect()
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, store.summary(method, true).status)

        store.finish(first, "UNLOCKED", credentialSequenceCompleted = false, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, store.summary(method, true).status)
        val second = store.detect()
        store.finish(second, "AUTHENTICATION_REJECTED", credentialSequenceCompleted = true, fullyUnlocked = false)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, store.summary(method, true).status)
        val third = store.detect()
        store.finish(third, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.VERIFIED, store().summary(method, true).status)
    }

    @Test fun emulatorUnlockPreservesOnlyDetectionEvidence() {
        val store = store(device.copy(isEmulator = true))
        val token = store.detect()
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        val summary = store.summary(method, true)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, summary.status)
        assertEquals("EMULATOR", summary.evidence)
    }

    @Test fun oldAndConsumedTokensCannotChangeLatestOutcome() {
        val firstStore = store()
        val first = firstStore.detect()
        val latestStore = store()
        val latest = latestStore.detect()
        firstStore.finish(first, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, latestStore.summary(method, true).status)

        latestStore.finish(latest, "AUTHENTICATION_REJECTED", credentialSequenceCompleted = true, fullyUnlocked = false)
        latestStore.finish(latest, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals("AUTHENTICATION_REJECTED", latestStore.summary(method, true).lastResult)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, latestStore.summary(method, true).status)
    }

    @Test fun newPendingObservationNeverInheritsPreviousVerifiedOutcome() {
        val store = store()
        val token = store.detect()
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.VERIFIED, store.summary(method, true).status)

        checkTime = 456L
        store.detect()
        val afterRestart = store().summary(method, true)
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, afterRestart.status)
        assertEquals(456L, afterRestart.lastCheckedAt)
        assertEquals("PHYSICAL_OBSERVATION", afterRestart.evidence)
        assertNull(afterRestart.lastResult)
    }

    @Test fun evidenceIsBoundToSystemAppMethodAndControlGeometry() {
        val store = store()
        val token = store.detect()
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, store.summary(UnlockMethod.PIN, true).status)
        assertEquals(CompatibilityStatus.UNKNOWN, store(device.copy(appVersionCode = 11)).summary(method, true).status)
        assertEquals(CompatibilityStatus.UNKNOWN,
            store(device.copy(fingerprintDigest = "b".repeat(64))).summary(method, true).status)
        assertEquals(CompatibilityStatus.PERMISSION_BLOCKED, store.summary(method, false).status)

        store.detect(grid.copy(bounds = ScreenBounds(180, 910, 900, 1630)))
        assertEquals(CompatibilityStatus.DETECTED_UNTESTED, store.summary(method, true).status)
    }

    @Test fun sameVersionDifferentApkCannotReuseEvidenceAndMissingApkCannotVerify() {
        val baseline = store()
        val first = baseline.detect()
        baseline.finish(first, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.VERIFIED, baseline.summary(method, true).status)
        val exported = JSONObject(baseline.exportJson(method, true))
        assertEquals(device.apkSha256, exported.getString("apkSha256"))
        assertEquals("0.4.0", exported.getString("appVersionName"))
        assertEquals("debug", exported.getString("buildType"))

        val changed = store(device.copy(apkSha256 = "c".repeat(64)))
        assertEquals(CompatibilityStatus.UNKNOWN, changed.summary(method, true).status)
        val missing = store(device.copy(apkSha256 = null))
        val second = missing.detect()
        missing.finish(second, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, missing.summary(method, true).status)
        assertFalse(JSONObject(missing.exportJson(method, true)).has("apkSha256"))
    }

    @Test fun unavailableDetectionClearsPendingProofAndRequiresAnExplicitUnsupportedReason() {
        val store = store()
        val permissionBlocked = store.summary(method, false)
        assertEquals(CompatibilityStatus.PERMISSION_BLOCKED, permissionBlocked.status)
        assertNull(permissionBlocked.lastCheckedAt)
        assertNull(permissionBlocked.evidence)

        val token = store.detect()
        store.recordUnavailable(method, "PATTERN_GRID_UNAVAILABLE")
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        val unavailable = store().summary(method, true)
        assertEquals(CompatibilityStatus.UNKNOWN, unavailable.status)
        assertEquals("PATTERN_GRID_UNAVAILABLE", unavailable.lastResult)
        assertNull(unavailable.evidence)
        assertEquals("UNAVAILABLE", JSONObject(store.exportJson(method, true)).getString("stage"))

        store.recordUnavailable(method, "MAGNIFICATION_UNSUPPORTED")
        assertEquals(CompatibilityStatus.UNSUPPORTED, store.summary(method, true).status)
    }

    @Test fun absentUntrustedAndCorruptObservationsStayUnknown() {
        val store = store()
        val token = store.detect(grid.copy(packageName = "com.example.fake"))
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, store.summary(method, true).status)

        disk = "{"
        assertEquals(CompatibilityStatus.UNKNOWN, store.summary(method, true).status)
        disk = """{"schemaVersion":999,"status":"VERIFIED"}"""
        assertEquals("UNKNOWN", JSONObject(store.exportJson(method, true)).getString("status"))
    }

    @Test fun credentialChangeClearsObservationAndRejectsInFlightToken() {
        val store = store()
        val token = store.detect()
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        val pending = store.detect()
        store.invalidate()
        store.finish(pending, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, store().summary(method, true).status)
    }

    @Test fun invalidationFailsWhenAWriteSilentlyKeepsTheOldVerifiedRecord() {
        val baseline = store()
        val token = baseline.detect()
        baseline.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.VERIFIED, baseline.summary(method, true).status)

        val storageThatDropsWrites = CompatibilityStore(device, { disk }, {})
        assertThrows(IllegalStateException::class.java) { storageThatDropsWrites.invalidate() }
        assertEquals(CompatibilityStatus.UNKNOWN, storageThatDropsWrites.summary(method, true).status)
        assertEquals(CompatibilityStatus.VERIFIED, store().summary(method, true).status)
    }

    @Test fun storageWriteExceptionsBlockInvalidationAndLeaveObservationsUnknown() {
        val storageThatThrows = CompatibilityStore(device, { disk }, { throw java.io.IOException("write failed") })
        val token = storageThatThrows.detect()
        storageThatThrows.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, storageThatThrows.summary(method, true).status)
        assertThrows(IllegalStateException::class.java) { storageThatThrows.invalidate() }
    }

    @Test fun unavailableControlStaysUnknownWhenAdapterRevisionDoesNotMatch() {
        val store = store()
        val token = store.observe(method, geometry, listOf(root, grid), listOf(1), "aosp", -1)
        store.finish(token, "UNLOCKED", credentialSequenceCompleted = true, fullyUnlocked = true)
        assertEquals(CompatibilityStatus.UNKNOWN, store.summary(method, true).status)
    }

    @Test fun nonDefaultDisplayIsExplicitlyUnsupported() {
        val store = store()
        store.detect(screen = geometry.copy(displayId = 1))
        assertEquals(CompatibilityStatus.UNSUPPORTED, store.summary(method, true).status)
    }

    @Test fun exportAllowsSelectedKnownMetadataAndDropsInjectedStrings() {
        val store = store(device.copy(oemBuild = "/private/value"))
        val token = store.detect(grid.copy(className = "com.example.HiddenMarker", textDigit = 7, descriptionDigit = 3))
        store.finish(token, "PRIVATE_RESULT_MARKER", credentialSequenceCompleted = false, fullyUnlocked = false)
        val json = store.exportJson(method, true)
        val report = JSONObject(json)

        assertEquals("PATTERN", report.getString("authMethod"))
        assertEquals("UNKNOWN_ERROR", report.getString("result"))
        assertEquals("com.android.systemui:id/lockPatternView", report.getJSONArray("controls")
            .getJSONObject(0).getString("resourceId"))
        assertTrue(report.has("screen"))
        for (needle in listOf("HiddenMarker", "textDigit", "descriptionDigit", "PRIVATE_RESULT_MARKER",
            "/private/value", device.fingerprintDigest, "fingerprintDigest", "structureDigest", "pendingToken")) {
            assertFalse("Leaked $needle", json.contains(needle))
        }
    }
}
