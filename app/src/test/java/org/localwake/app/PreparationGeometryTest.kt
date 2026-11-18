package org.localwake.app

import org.junit.Assert.*
import org.junit.Test
import org.localwake.app.device.*
import org.localwake.app.security.UnlockMethod

class PreparationGeometryTest {
    private val display = DisplayGeometry(ScreenBounds(0, 0, 1080, 2400), 3f,
        magnificationScale = 1f, magnificationActivated = false)
    private val root = KeyguardNode(0, null, "com.android.systemui", 7, null, "android.view.View", display.bounds)
    private val widget = KeyguardNode(1, 0, "example.widget", 7, null, "android.view.View",
        ScreenBounds(450, 950, 630, 1150), clickable = true)
    private val window = LockScreenWindow(7, 0, LockScreenWindowType.SYSTEM, true, display.bounds, "com.android.systemui", true, 1, true)
    private fun snapshot(vararg others: KeyguardNode, overlays: List<ScreenBounds> = emptyList(), owner: LockScreenWindow = window) = LockScreenSnapshot(
        DeviceFingerprint("samsung", "synthetic", 36, "test"), display, owner, listOf(root) + others, overlays,
    )

    @Test fun `unobstructed Samsung preparation keeps proven centre line`() {
        assertEquals(540f, snapshot().preparationX(1584f, 816f))
    }

    @Test fun `hosted widget stays untrusted while reveal avoids its complete bounds`() {
        val observed = snapshot(widget)
        assertTrue(TrustedLockScreenDetector.permitsPreparation(observed))
        val x = requireNotNull(observed.preparationX(1584f, 816f))
        assertTrue(x < widget.bounds.left || x > widget.bounds.right)
        assertFalse(observed.permitsCoordinateBounds(widget.bounds))
    }

    @Test fun `a foreign nonclickable container is also excluded`() {
        assertNotEquals(540f, snapshot(widget.copy(clickable = false)).preparationX(1584f, 816f))
    }

    @Test fun `unknown fields different windows and malformed widget bounds stop preparation`() {
        listOf(widget.copy(password = true), widget.copy(editable = true), widget.copy(setText = true),
            widget.copy(windowId = 9), widget.copy(packageName = null),
            widget.copy(bounds = ScreenBounds(-10, 900, 600, 1300)),
            widget.copy(bounds = ScreenBounds(500, 900, 500, 1300))).forEach {
            assertNull(snapshot(it).preparationX(1584f, 816f))
        }
    }

    @Test fun `no available strip stops instead of crossing an overlay`() {
        assertNull(snapshot(overlays = listOf(ScreenBounds(0, 900, 1080, 1500))).preparationX(1584f, 816f))
    }

    @Test fun `off path widget preserves centre but pattern overlapping it remains rejected`() {
        val grid = root.copy(index = 1, parent = 0, viewId = "com.android.systemui:id/lockPatternView",
            className = "com.android.internal.widget.LockPatternView", bounds = ScreenBounds(180, 900, 900, 1620))
        assertNull(LockScreenAdapterRegistry.select(UnlockMethod.PATTERN, snapshot(grid, widget.copy(index = 2))))
        assertNull(LockScreenAdapterRegistry.select(UnlockMethod.PATTERN,
            snapshot(grid, widget.copy(index = 2, bounds = ScreenBounds(500, 100, 500, 200)))))
        assertEquals(540f, snapshot(widget.copy(bounds = ScreenBounds(80, 100, 280, 300))).preparationX(1584f, 816f))
    }

    @Test fun `displaced owner window does not invent an alternative swipe`() {
        assertNull(snapshot(owner = window.copy(bounds = ScreenBounds(650, 0, 1080, 2400))).preparationX(1584f, 816f))
    }
}
