package org.localwake.app.device

import org.localwake.app.security.UnlockMethod

internal data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(other: ScreenBounds): Boolean = other.width > 0 && other.height > 0 &&
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    fun overlaps(other: ScreenBounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class DisplayGeometry(
    val bounds: ScreenBounds,
    val density: Float,
    val displayId: Int = 0,
    val rotation: Int = 0,
    val magnificationScale: Float? = null,
    val magnificationActivated: Boolean? = null,
) {
    val permitsCoordinateInput: Boolean get() = magnificationScale == 1f && magnificationActivated != true
}

internal data class KeyguardNode(
    val index: Int,
    val parent: Int?,
    val packageName: String?,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val bounds: ScreenBounds,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val editable: Boolean = false,
    val numericPassword: Boolean = false,
    val textPassword: Boolean = false,
    val clickable: Boolean = false,
    val setText: Boolean = false,
    val imeEnter: Boolean = false,
    val textDigit: Int? = null,
    val descriptionDigit: Int? = null,
) {
    val resourceEntry: String? get() = viewId?.takeIf { it.startsWith("${KeyguardSurfacePolicy.SYSTEM_UI}:id/") }
        ?.substringAfter('/')?.lowercase()
    val simpleClass: String? get() = className?.substringAfterLast('.')?.lowercase()
}

internal sealed interface KeyguardSurface {
    data class Pattern(val grid: Int) : KeyguardSurface
    data class Pin(val entry: Int, val digits: List<Int>, val confirm: Int?) : KeyguardSurface
    data class Password(val field: Int, val confirm: Int?) : KeyguardSurface
}

internal object KeyguardSurfacePolicy {
    const val SYSTEM_UI = "com.android.systemui"
    private val pinContainers = setOf("keyguard_pin_view", "pin_container", "keyguard_pin_motion_layout")
    private val pinEntries = setOf("pinentry", "pin_entry")
    private val passwordContainers = setOf("keyguard_password_view", "keyguard_password_motion_layout")
    private val passwordEntries = setOf("passwordentry", "password_entry")
    private val pinConfirmIds = setOf("key_enter", "pin_confirm", "pin_enter")
    private val passwordConfirmIds = setOf("password_confirm", "password_enter")
    private val unsupportedPatternIds = setOf("colorlockpatternview", "oplusos_kgd_security_pattern_view")
    private val forbiddenIds = setOf("keyguard_sim", "sim_pin", "sim_puk", "simpinentry", "simpukentry", "sim_pin_entry",
        "sim_puk_entry", "sim_pin_view", "sim_puk_view", "emergency_dialer", "emergency_dialer_view",
        "emergency_keypad", "emergency_call_dialer", "emergency_call_screen")

    fun recognizesEntry(nodes: List<KeyguardNode>): Boolean = trustedVisible(nodes).any {
        it.forbidden() || it.unsupportedPattern() || it.resourceEntry == "keyguard_pattern_view" ||
            it.simpleClass == "seckeyguardpatternview" || PatternGridProvider.identifies(it) ||
            it.pinContainer(samsung = true) || it.resourceEntry in pinEntries || it.passwordContainer(samsung = true) ||
            it.resourceEntry in passwordEntries || it.password || it.editable || it.setText
    }

    fun select(
        method: UnlockMethod,
        geometry: DisplayGeometry,
        nodes: List<KeyguardNode>,
        samsung: Boolean = false,
    ): KeyguardSurface? {
        val visible = trustedVisible(nodes)
        if (visible.any { it.forbidden() || it.unsupportedPattern() }) return null
        val patterns = visible.filter(PatternGridProvider::identifies)
        val secrets = visible.filter { it.password }
        val pin = visible.any { it.pinContainer(samsung = true) || it.resourceEntry in pinEntries || it.numericPassword }
        val password = visible.any {
            it.passwordContainer(samsung = true) || it.resourceEntry in passwordEntries || it.textPassword
        }
        val surface = when (method) {
            UnlockMethod.PATTERN -> if (pin || password || secrets.isNotEmpty()) null else
                PatternGridProvider.select(geometry, patterns)
            UnlockMethod.PIN -> if (!pin || password || patterns.isNotEmpty()) null else
                selectPin(visible, nodes, secrets, geometry, samsung)
            UnlockMethod.PASSWORD -> if (!password || pin || patterns.isNotEmpty()) null else
                selectPassword(visible, nodes, secrets, geometry, samsung)
        } ?: return null
        val field = when (surface) {
            is KeyguardSurface.Pattern -> null
            is KeyguardSurface.Pin -> surface.entry
            is KeyguardSurface.Password -> surface.field
        }
        return surface.takeUnless { nodes.any { it.visible && (it.editable || it.setText) && it.index != field } }
    }

    private fun selectPin(visible: List<KeyguardNode>, nodes: List<KeyguardNode>, secrets: List<KeyguardNode>,
                          geometry: DisplayGeometry, samsung: Boolean): KeyguardSurface.Pin? {
        val byIndex = nodes.associateBy { it.index }
        val entry = visible.filter { it.resourceEntry in pinEntries || it.numericPassword }.singleOrNull() ?: return null
        if (!entry.usable(geometry) || secrets.any { it != entry } ||
            (!entry.password && !entry.editable && entry.simpleClass != "passwordtextview")) return null
        val scope = ancestors(entry, byIndex).firstOrNull { it in visible && it.pinContainer(samsung) }
        if (scope == null && !entry.numericPassword) return null
        if (scope != null && !scope.usable(geometry)) return null

        val scoped = visible.filter { scope == null || scope in ancestors(it, byIndex) }
        val keys = mutableMapOf<Int, KeyguardNode>()
        for (candidate in scoped) {
            if (candidate.password || candidate.editable) continue
            val byId = candidate.resourceEntry?.takeIf { it.length == 4 && it.startsWith("key") }
                ?.last()?.let { if (it in '0'..'9') it - '0' else null }
            if (byId == null && !candidate.clickable) continue
            val hints = listOfNotNull(byId, candidate.textDigit, candidate.descriptionDigit).distinct()
            if (hints.isEmpty()) continue
            val digit = hints.singleOrNull()?.takeIf { it in 0..9 } ?: return null
            if (!candidate.clickable || !candidate.usable(geometry) || keys.put(digit, candidate) != null) return null
        }
        if (keys.size != 10) return null

        val confirms = scoped.filter { it.resourceEntry in pinConfirmIds }
        val confirm = confirms.singleOrNull()
        if (confirms.isNotEmpty() && (confirm == null || !confirm.clickable || !confirm.usable(geometry))) return null
        val buttonBounds = keys.values.map { it.bounds } + listOfNotNull(confirm?.bounds)
        if (buttonBounds.indices.any { i -> (0 until i).any { j -> buttonBounds[i].overlaps(buttonBounds[j]) } }) return null
        // A fully identified stock-style PIN bouncer may hide its enter control in auto-confirm mode.
        if (confirm == null && scope == null) return null
        return KeyguardSurface.Pin(entry.index, (0..9).map { keys.getValue(it).index }, confirm?.index)
    }

    private fun selectPassword(visible: List<KeyguardNode>, nodes: List<KeyguardNode>, secrets: List<KeyguardNode>,
                               geometry: DisplayGeometry, samsung: Boolean): KeyguardSurface.Password? {
        val field = secrets.singleOrNull() ?: return null
        val byIndex = nodes.associateBy { it.index }
        val scope = ancestors(field, byIndex).firstOrNull { it in visible && it.passwordContainer(samsung) }
        if (field.resourceEntry !in passwordEntries && (scope == null || !field.textPassword)) return null
        if (!field.editable || !field.setText || !field.usable(geometry) ||
            (scope != null && !scope.usable(geometry))) return null
        val confirms = visible.filter { candidate ->
            if (scope == null) candidate.resourceEntry in passwordConfirmIds else
                (candidate.resourceEntry in passwordConfirmIds || candidate.resourceEntry == "key_enter") &&
                    scope in ancestors(candidate, byIndex)
        }
        val confirm = confirms.singleOrNull()
        if (confirms.isNotEmpty() && (confirm == null || !confirm.clickable || !confirm.usable(geometry))) return null
        if (confirm == null && !field.imeEnter) return null
        return KeyguardSurface.Password(field.index, confirm?.index)
    }

    private fun trustedVisible(nodes: List<KeyguardNode>): List<KeyguardNode> {
        val root = nodes.firstOrNull() ?: return emptyList()
        if (root.index != 0 || root.parent != null || root.packageName != SYSTEM_UI ||
            !root.visible || root.windowId < 0) return emptyList()
        val seen = mutableSetOf<Int>()
        val trusted = mutableSetOf<Int>()
        val result = mutableListOf<KeyguardNode>()
        for (node in nodes) {
            if (node.index < 0 || !seen.add(node.index) || (node !== root && node.parent !in seen) ||
                node.parent == node.index) return emptyList()
            if (node.visible && node.packageName == SYSTEM_UI && node.windowId == root.windowId &&
                (node === root || node.parent in trusted)) {
                trusted += node.index
                result += node
            }
        }
        return result
    }

    private fun ancestors(node: KeyguardNode, all: Map<Int, KeyguardNode>): Sequence<KeyguardNode> =
        generateSequence(node.parent?.let(all::get)) { it.parent?.let(all::get) }.take(all.size)

    private fun KeyguardNode.pinContainer(samsung: Boolean) = resourceEntry in pinContainers ||
        simpleClass == "keyguardpinview" || (samsung && simpleClass == "seckeyguardpinview")
    private fun KeyguardNode.passwordContainer(samsung: Boolean) = resourceEntry in passwordContainers ||
        simpleClass == "keyguardpasswordview" || (samsung && simpleClass == "seckeyguardpasswordview")
    private fun KeyguardNode.unsupportedPattern() =
        resourceEntry in unsupportedPatternIds || simpleClass == "colorlockpatternview"
    private fun KeyguardNode.forbidden() = resourceEntry in forbiddenIds || resourceEntry?.startsWith("keyguard_sim_") == true ||
        simpleClass?.startsWith("keyguardsim") == true || simpleClass?.startsWith("seckeyguardsim") == true ||
        simpleClass in setOf("emergencydialer", "emergencydialerview", "emergencycalldialer")
    private fun KeyguardNode.usable(geometry: DisplayGeometry) = enabled && geometry.bounds.contains(bounds)
}
