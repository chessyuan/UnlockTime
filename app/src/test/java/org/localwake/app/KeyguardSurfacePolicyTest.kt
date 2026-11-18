package org.localwake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.device.KeyguardSurface
import org.localwake.app.device.KeyguardSurfacePolicy
import org.localwake.app.device.ScreenBounds
import org.localwake.app.security.UnlockMethod

class KeyguardSurfacePolicyTest {
    private val screen = DisplayGeometry(ScreenBounds(0, 0, 480, 900), 1.5f)
    private val root = node(0, "keyguard_root", screen.bounds, parent = null)
    private val pattern = node(1, "lockPatternView", ScreenBounds(54, 377, 294, 617))
    private val pinContainer = node(1, "keyguard_pin_view", ScreenBounds(0, 200, 480, 780))
    private val pinEntry = node(2, "pinEntry", ScreenBounds(80, 250, 400, 300), parent = 1)
        .copy(password = true, editable = true, numericPassword = true)
    private val confirm = node(13, "key_enter", ScreenBounds(360, 650, 434, 720), parent = 1)
        .copy(clickable = true)
    private val passwordEntry = node(1, "passwordEntry", ScreenBounds(80, 300, 400, 350))
        .copy(password = true, editable = true, setText = true, imeEnter = true)

    @Test fun `pattern grid uses node bounds on a low density screen`() {
        assertEquals(KeyguardSurface.Pattern(1), select(UnlockMethod.PATTERN, pattern))
    }

    @Test fun `pattern container and invalid or conflicting grids cannot supply bounds`() {
        assertNull(select(UnlockMethod.PATTERN, pattern.copy(viewId = id("keyguard_pattern_view"))))
        assertNull(select(UnlockMethod.PATTERN, pattern.copy(bounds = ScreenBounds(-1, 220, 239, 460))))
        assertNull(select(UnlockMethod.PATTERN, pattern.copy(bounds = ScreenBounds(10, 220, 40, 250))))
        assertNull(select(UnlockMethod.PATTERN, pattern, pattern.copy(index = 2)))
    }

    @Test fun `exact lock pattern class identifies a grid without a resource id`() {
        val classOnly = pattern.copy(viewId = null, className = "com.android.internal.widget.LockPatternView")
        assertEquals(KeyguardSurface.Pattern(1), select(UnlockMethod.PATTERN, classOnly))
        assertNull(select(UnlockMethod.PATTERN, classOnly.copy(className = "com.android.internal.widget.LockPatternViewWrapper")))
    }

    @Test fun `shuffled keypad resolves digit from semantics independent of bounds`() {
        val keys = digits().mapIndexed { digit, key ->
            key.copy(viewId = null, descriptionDigit = digit, bounds = digits()[9 - digit].bounds)
        }
        assertEquals(KeyguardSurface.Pin(2, (0..9).map { it + 3 }, 13),
            select(UnlockMethod.PIN, pinContainer, pinEntry, *keys.toTypedArray(), confirm))
    }

    @Test fun `hidden PIN confirm can identify an auto submit keypad`() {
        val pin = select(UnlockMethod.PIN, pinContainer, pinEntry, *digits().toTypedArray(),
            confirm.copy(visible = false)) as? KeyguardSurface.Pin
        assertNotNull(pin)
        assertNull(pin?.confirm)
    }

    @Test fun `numeric password field can identify PIN when its container is omitted`() {
        val pin = select(UnlockMethod.PIN, pinEntry.copy(parent = 0),
            *digits().map { it.copy(parent = 0) }.toTypedArray(), confirm.copy(parent = 0))
        assertEquals(KeyguardSurface.Pin(2, (0..9).map { it + 3 }, 13), pin)
    }

    @Test fun `missing duplicate disabled mislabeled or out of scope digits reject the PIN`() {
        val keys = digits()
        val invalid = listOf(
            keys.dropLast(1), keys + keys.first().copy(index = 14),
            keys.dropLast(1) + keys.last().copy(enabled = false),
            keys.dropLast(1) + keys.last().copy(textDigit = 1),
            keys.dropLast(1) + keys.last().copy(parent = 0),
            keys.dropLast(1) + keys.last().copy(packageName = "other.app")
        )
        invalid.forEach { nodes ->
            assertNull(select(UnlockMethod.PIN, pinContainer, pinEntry, *nodes.toTypedArray(), confirm))
        }
    }

    @Test fun `duplicate or disabled confirmation rejects the PIN before dispatch`() {
        assertNull(select(UnlockMethod.PIN, pinContainer, pinEntry, *digits().toTypedArray(),
            confirm, confirm.copy(index = 14)))
        assertNull(select(UnlockMethod.PIN, pinContainer, pinEntry, *digits().toTypedArray(),
            confirm.copy(enabled = false)))
    }

    @Test fun `SIM PUK and emergency screens never accept a stored device credential`() {
        listOf("keyguard_sim_puk_view", "simPinEntry", "emergency_dialer", "keyguard_sim").forEach { blockedId ->
            val blocked = node(14, blockedId, ScreenBounds(0, 20, 480, 80))
            assertNull(select(UnlockMethod.PIN, pinContainer, pinEntry, *digits().toTypedArray(), confirm, blocked))
            assertTrue(KeyguardSurfacePolicy.recognizesEntry(listOf(root, blocked)))
        }
        val emergencyButton = node(14, "emergency_call_button", ScreenBounds(80, 750, 400, 800))
        assertNotNull(select(UnlockMethod.PIN, pinContainer, pinEntry, *digits().toTypedArray(), confirm, emergencyButton))
    }

    @Test fun `malformed generic and competing password fields reject the password`() {
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(password = false)))
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(editable = false)))
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(setText = false)))
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(imeEnter = false)))
        val generic = passwordEntry.copy(viewId = id("generic_password_field"))
        assertNull(select(UnlockMethod.PASSWORD, generic))
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry, generic.copy(index = 2)))
    }

    @Test fun `password uses confirmed IME action or an exact dedicated button`() {
        assertEquals(KeyguardSurface.Password(1, null), select(UnlockMethod.PASSWORD, passwordEntry))
        val enter = confirm.copy(index = 2, viewId = id("password_confirm"), parent = 0)
        assertEquals(KeyguardSurface.Password(1, 2),
            select(UnlockMethod.PASSWORD, passwordEntry.copy(imeEnter = false), enter))
        assertNotNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(imeEnter = false), enter))
        assertNull(select(UnlockMethod.PASSWORD, passwordEntry.copy(imeEnter = false),
            enter.copy(viewId = id("unrelated_okay"))))
    }

    @Test fun `root must be trusted and match requested device unlock method`() {
        assertNull(KeyguardSurfacePolicy.select(UnlockMethod.PATTERN, screen,
            listOf(root.copy(packageName = "other.app"), pattern)))
        assertNull(select(UnlockMethod.PATTERN, pattern.copy(windowId = 20)))
        assertNull(select(UnlockMethod.PATTERN, passwordEntry))
        assertNull(select(UnlockMethod.PASSWORD, pattern, passwordEntry.copy(index = 2)))
        assertNull(select(UnlockMethod.PIN, passwordEntry))
        assertTrue(KeyguardSurfacePolicy.recognizesEntry(listOf(root, passwordEntry)))
        assertTrue(KeyguardSurfacePolicy.recognizesEntry(listOf(root, pinContainer)))
    }

    private fun digits() = (0..9).map { digit ->
        val left = 30 + digit % 3 * 105
        val top = 330 + digit / 3 * 76
        node(digit + 3, "key$digit", ScreenBounds(left, top, left + 74, top + 60), parent = 1)
            .copy(clickable = true)
    }

    private fun select(method: UnlockMethod, vararg nodes: KeyguardNode) =
        KeyguardSurfacePolicy.select(method, screen, listOf(root) + nodes)

    private fun node(index: Int, entry: String, bounds: ScreenBounds, parent: Int? = 0) = KeyguardNode(
        index, parent, "com.android.systemui", 11, id(entry), "android.view.View", bounds
    )

    private fun id(entry: String) = "com.android.systemui:id/$entry"
}
