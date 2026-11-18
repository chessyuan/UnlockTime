package org.localwake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localwake.app.device.DeviceFingerprint
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.device.LockScreenAdapterRegistry
import org.localwake.app.device.LockScreenSnapshot
import org.localwake.app.device.LockScreenWindow
import org.localwake.app.device.LockScreenWindowType
import org.localwake.app.device.ScreenBounds
import org.localwake.app.device.TrustedLockScreenDetector
import org.localwake.app.security.UnlockMethod

class LockScreenSnapshotTest {
    private val screen = DisplayGeometry(ScreenBounds(0, 0, 480, 900), 1.5f, magnificationScale = 1f)
    private val device = DeviceFingerprint("samsung", "test-model", 35, "test-build")
    private val window = LockScreenWindow(11, 0, LockScreenWindowType.SYSTEM, true, screen.bounds, SYSTEM_UI, true, 1,
        touchableRectangular = true)
    private val root = KeyguardNode(0, null, SYSTEM_UI, 11, null, "android.view.View", screen.bounds)
    private val grid = root.copy(index = 1, parent = 0, className = "com.android.internal.widget.LockPatternView",
        bounds = ScreenBounds(54, 377, 294, 617))

    @Test fun `window acceptance needs observed main display active SystemUI and system installation`() {
        assertNotNull(select(snapshot()))
        val unavailableWindows = listOf(
            window.copy(id = -1), window.copy(layer = -1), window.copy(displayId = -1), window.copy(displayId = 1),
            window.copy(active = false), window.copy(type = LockScreenWindowType.OTHER),
            window.copy(systemInstalled = false), window.copy(packageName = "com.android.systemui.copy"),
        )
        unavailableWindows.forEach { assertNull(select(snapshot(window = it))) }
        assertNull(select(snapshot(nodes = listOf(root, grid.copy(parent = 4)))))
        assertNull(select(snapshot(display = screen.copy(rotation = -1))))
    }

    @Test fun `manufacturer only labels a trusted selection for matching structure`() {
        assertEquals("samsung-one-ui", select(snapshot())?.adapterId)
        assertEquals("aosp", select(snapshot(hardware = device.copy(manufacturer = "another")))?.adapterId)
        assertNull(select(snapshot(nodes = listOf(root))))
        assertTrue(LockScreenAdapterRegistry.isCurrentRevision("samsung-one-ui", 1))
        assertFalse(LockScreenAdapterRegistry.isCurrentRevision("aosp", 99))
    }

    @Test fun `only Samsung adapter accepts its exact password container class`() {
        val container = grid.copy(className = "com.android.keyguard.SecKeyguardPasswordView")
        val field = container.copy(index = 2, parent = 1, className = "android.widget.EditText",
            password = true, editable = true, textPassword = true, setText = true, imeEnter = true)
        val owner = snapshot(nodes = listOf(root, container, field))
        val wrongManufacturer = snapshot(hardware = device.copy(manufacturer = "another"),
            nodes = listOf(root, container, field))
        val similarName = snapshot(nodes = listOf(root, container.copy(className = container.className + "Wrapper"), field))

        assertEquals("samsung-one-ui", LockScreenAdapterRegistry.select(UnlockMethod.PASSWORD, owner)?.adapterId)
        assertNull(LockScreenAdapterRegistry.select(UnlockMethod.PASSWORD, wrongManufacturer))
        assertNull(LockScreenAdapterRegistry.select(UnlockMethod.PASSWORD, similarName))
    }

    @Test fun `selection continuity ignores unrelated nodes and rejects moved controls or rotation`() {
        val initial = snapshot()
        val initialSelection = requireNotNull(select(initial))
        val unrelated = grid.copy(index = 2, className = "android.view.View", bounds = ScreenBounds(320, 720, 400, 780))
        val next = listOf(
            snapshot(nodes = listOf(root, grid, unrelated)),
            snapshot(nodes = listOf(root, grid.copy(bounds = ScreenBounds(64, 377, 304, 617)))),
            snapshot(display = screen.copy(rotation = 1)),
        )
        assertEquals(listOf(true, false, false), next.map {
            it.matchesSelectedStructure(initial, initialSelection, requireNotNull(select(it)))
        })
    }

    @Test fun `only higher windows touching selected coordinates obstruct the gesture`() {
        val statusBar = ScreenBounds(0, 0, 480, 30)
        val overlay = ScreenBounds(240, 500, 320, 650)
        assertNotNull(select(snapshot(occlusions = listOf(statusBar))))
        assertNull(select(snapshot(occlusions = listOf(statusBar, overlay))))
        assertFalse(snapshot(occlusions = listOf(overlay)).permitsCoordinateBounds(ScreenBounds(240, 250, 241, 730)))
    }

    @Test fun `known magnification prevents coordinate input and preparation swipes`() {
        val magnified = screen.copy(magnificationScale = 2f)
        assertNull(select(snapshot(display = magnified)))
        assertFalse(TrustedLockScreenDetector.permitsPreparation(snapshot(display = magnified, nodes = listOf(root))))
        assertTrue(TrustedLockScreenDetector.permitsPreparation(snapshot(nodes = listOf(root))))
    }

    @Test fun `unknown magnification is not evidence of unmagnified coordinates`() {
        val unknown = screen.copy(magnificationScale = null)
        assertNull(select(snapshot(display = unknown)))
        assertFalse(TrustedLockScreenDetector.permitsPreparation(snapshot(display = unknown, nodes = listOf(root))))
    }

    @Test fun `active magnification rejects coordinates even when the scale equals one`() {
        val active = screen.copy(magnificationActivated = true)
        assertNull(select(snapshot(display = active)))
        assertFalse(TrustedLockScreenDetector.permitsPreparation(snapshot(display = active, nodes = listOf(root))))
    }

    @Test fun `nonrectangular touch region blocks gestures and permits node actions`() {
        val nonrectangular = window.copy(touchableRectangular = false)
        assertNull(select(snapshot(window = nonrectangular)))
        assertFalse(TrustedLockScreenDetector.permitsPreparation(snapshot(window = nonrectangular, nodes = listOf(root))))

        val field = grid.copy(viewId = "$SYSTEM_UI:id/passwordEntry", className = "android.widget.EditText",
            password = true, editable = true, textPassword = true, setText = true, imeEnter = true)
        val nodeSurface = snapshot(window = nonrectangular, nodes = listOf(root, field))
        assertNotNull(LockScreenAdapterRegistry.select(UnlockMethod.PASSWORD, nodeSurface))
    }

    private fun select(snapshot: LockScreenSnapshot) = LockScreenAdapterRegistry.select(UnlockMethod.PATTERN, snapshot)

    private fun snapshot(
        hardware: DeviceFingerprint = device,
        display: DisplayGeometry = screen,
        window: LockScreenWindow = this.window,
        nodes: List<KeyguardNode> = listOf(root, grid),
        occlusions: List<ScreenBounds> = emptyList(),
    ) = LockScreenSnapshot(hardware, display, window, nodes, occlusions)

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
