package org.localwake.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.device.KeyguardSurfacePolicy
import org.localwake.app.device.ScreenBounds
import org.localwake.app.security.UnlockMethod

class KeyguardSafetyRegressionTest {
    private val screen = DisplayGeometry(ScreenBounds(0, 0, 480, 900), 1.5f)
    private val root = node(0, null, "keyguard_root", screen.bounds)
    private val pattern = node(1, 0, "lockPatternView", ScreenBounds(54, 377, 294, 617))

    @Test fun `OEM authentication markers stop preparation without selecting a grid`() {
        val markers = listOf(
            node(2, 0, "colorLockPatternView", pattern.bounds),
            node(2, 0, "oplusos_kgd_security_pattern_view", screen.bounds),
            node(2, 0, null, pattern.bounds).copy(className = "com.vendor.widget.ColorLockPatternView"),
        )
        for (marker in markers) {
            assertTrue(KeyguardSurfacePolicy.recognizesEntry(listOf(root, marker)))
            assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PATTERN, screen, listOf(root, pattern, marker)))
        }
    }

    @Test fun `pattern under a missing or unrelated ancestor cannot be selected`() {
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PATTERN, screen, listOf(root, pattern.copy(parent = 33))))
        val foreignParent = node(2, 0, null, screen.bounds).copy(packageName = "other.app")
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PATTERN, screen,
            listOf(root, foreignParent, pattern.copy(parent = 2))))
    }

    @Test fun `overlapping PIN buttons are ambiguous even when their rectangles differ`() {
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PIN, screen, pinTree(horizontalSpacing = 60)))
    }

    @Test fun `an additional editable field disqualifies every credential surface`() {
        val extra = node(14, 0, "unrelated_editable", ScreenBounds(60, 100, 420, 150)).copy(editable = true)
        val password = node(1, 0, "passwordEntry", ScreenBounds(80, 250, 400, 300))
            .copy(password = true, editable = true, setText = true, imeEnter = true)

        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PATTERN, screen, listOf(root, pattern, extra)))
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PIN, screen, pinTree(horizontalSpacing = 105) + extra))
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PASSWORD, screen, listOf(root, password, extra)))
    }

    private fun pinTree(horizontalSpacing: Int): List<KeyguardNode> {
        val entry = node(1, 0, "keyguard_pin_view", ScreenBounds(0, 200, 480, 780))
        val pin = node(2, 1, "pinEntry", ScreenBounds(80, 250, 400, 300))
            .copy(password = true, editable = true, numericPassword = true)
        val keys = (0..9).map { digit ->
            val left = 30 + digit % 3 * horizontalSpacing
            val top = 330 + digit / 3 * 76
            node(digit + 3, 1, "key$digit", ScreenBounds(left, top, left + 74, top + 60))
                .copy(clickable = true)
        }
        return listOf(root, entry, pin) + keys
    }

    private fun node(index: Int, parent: Int?, id: String?, bounds: ScreenBounds) = KeyguardNode(
        index, parent, "com.android.systemui", 11, id?.let { "com.android.systemui:id/$it" }, "android.view.View", bounds,
    )
}
