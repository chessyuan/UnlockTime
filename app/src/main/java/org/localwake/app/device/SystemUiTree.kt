@file:Suppress("DEPRECATION")

package org.localwake.app.device

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.localwake.app.accessibility.UnlockAccessibilityHost
import org.localwake.app.security.UnlockMethod

internal class SystemUiTree private constructor(
    val snapshot: LockScreenSnapshot,
    private val nodes: List<AccessibilityNodeInfo>,
) : AutoCloseable {
    private val records get() = snapshot.nodes

    fun canPrepare() = TrustedLockScreenDetector.permitsPreparation(snapshot)
    fun selection(method: UnlockMethod) = LockScreenAdapterRegistry.select(method, snapshot)
    fun select(method: UnlockMethod) = selection(method)?.surface
    fun bounds(index: Int) = records[index].bounds.toRect()

    fun matches(previous: SystemUiTree, initial: AdapterSelection, current: AdapterSelection): Boolean {
        if (!snapshot.matchesSelectedStructure(previous.snapshot, initial, current)) return false
        val expected = initial.surface.controls()
        val observed = current.surface.controls()
        return sameNode(0, previous, 0) && observed.indices.all { sameNode(observed[it], previous, expected[it]) }
    }

    fun mismatchKind(previous: SystemUiTree, initial: AdapterSelection, current: AdapterSelection): String = when {
        snapshot.geometry != previous.snapshot.geometry -> "DISPLAY_CHANGED"
        snapshot.window != previous.snapshot.window -> "WINDOW_CHANGED"
        !snapshot.matchesSelectedStructure(previous.snapshot, initial, current) -> "CONTROL_STRUCTURE_CHANGED"
        nodes[0] != previous.nodes[0] -> "ROOT_IDENTITY_CHANGED"
        else -> "NODE_IDENTITY_OR_PROPERTIES_CHANGED"
    }

    private fun sameNode(index: Int, previous: SystemUiTree, previousIndex: Int): Boolean {
        val before = previous.records[previousIndex]
        val current = records[index]
        return nodes[index] == previous.nodes[previousIndex] && before == current.copy(index = before.index, parent = before.parent)
    }

    fun rootStillActive(service: UnlockAccessibilityHost, geometry: ScreenGeometry): Boolean {
        if (!service.isReady() || (Build.VERSION.SDK_INT >= 33 && !service.clearNodeCache())) return false
        val active = service.activeRoot() ?: return false
        return try {
            active == nodes[0] && active.refresh() && active.describe(0, null) == records[0] &&
                active.captureWindow(service.context.packageManager) == snapshot.window &&
                service.higherWindowBounds(snapshot.window) == snapshot.higherWindowBounds &&
                geometry.current() == snapshot.geometry
        } finally {
            active.recycle()
        }
    }

    fun refreshControl(index: Int): AccessibilityNodeInfo? {
        val node = nodes[index]
        val expected = records[index]
        return node.takeIf { it.refresh() && it.describe(index, expected.parent) == expected }
    }

    override fun close() = nodes.forEach { it.recycle() }

    companion object {
        private const val MAX_NODES = 512

        private val device = DeviceFingerprint(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, Build.FINGERPRINT)

        fun capture(service: UnlockAccessibilityHost, geometry: ScreenGeometry): SystemUiTree? {
            if (!service.isReady() || (Build.VERSION.SDK_INT >= 33 && !service.clearNodeCache())) return null
            val before = geometry.current() ?: return null
            val root = service.activeRoot() ?: return null
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val records = mutableListOf<KeyguardNode>()
            val seen = mutableSetOf<AccessibilityNodeInfo>()
            queue.add(root to null)
            var retained = false
            try {
                if (root.packageName?.toString() != KeyguardSurfacePolicy.SYSTEM_UI || !root.refresh() ||
                    !root.isVisibleToUser) return null
                val window = root.captureWindow(service.context.packageManager) ?: return null
                if (root.windowId != window.id || !TrustedLockScreenDetector.trustsWindow(before, window)) return null
                val higherWindows = service.higherWindowBounds(window) ?: return null
                while (queue.isNotEmpty()) {
                    val (node, parent) = queue.removeFirst()
                    val index = nodes.size
                    nodes += node
                    if (index != 0 && Build.VERSION.SDK_INT < 33 && !node.refresh()) return null
                    if (!seen.add(node)) return null
                    records += node.describe(index, parent)
                    val childCount = node.childCount
                    if (childCount !in 0..(MAX_NODES - nodes.size - queue.size)) return null
                    for (childIndex in 0 until childCount) {
                        val child = node.getChild(childIndex) ?: return null
                        queue.add(child to index)
                    }
                }
                val tree = SystemUiTree(LockScreenSnapshot(device, before, window, records.toList(), higherWindows), nodes)
                if (!tree.rootStillActive(service, geometry)) return null
                retained = true
                return tree
            } finally {
                queue.forEach { (node, _) -> node.recycle() }
                if (!retained) nodes.forEach { it.recycle() }
            }
        }
    }
}

private fun AccessibilityNodeInfo.captureWindow(packages: PackageManager): LockScreenWindow? {
    val name = packageName?.toString()
    if (name != KeyguardSurfacePolicy.SYSTEM_UI) return null
    val window = window ?: return null
    return try {
        val flags = packages.getApplicationInfo(name, 0).flags
        val type = when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> LockScreenWindowType.APPLICATION
            AccessibilityWindowInfo.TYPE_SYSTEM -> LockScreenWindowType.SYSTEM
            else -> LockScreenWindowType.OTHER
        }
        val region = Region().also(window::getRegionInScreen)
        LockScreenWindow(
            window.id, window.displayId, type, window.isActive,
            region.bounds.toScreenBounds(), name,
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, window.layer,
            touchableRectangular = region.isRect,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } finally {
        window.recycle()
    }
}

private fun UnlockAccessibilityHost.higherWindowBounds(active: LockScreenWindow): List<ScreenBounds>? {
    val interactive = interactiveWindows()
    return try {
        if (interactive.any { it.id < 0 || it.layer < 0 || it.displayId != active.displayId }) return null
        val owner = interactive.singleOrNull { it.id == active.id } ?: return null
        val ownerRegion = Region().also(owner::getRegionInScreen)
        if (!owner.isActive || owner.layer != active.layer ||
            ownerRegion.bounds.toScreenBounds() != active.bounds || ownerRegion.isRect != active.touchableRectangular) return null
        // Other windows at the same layer have no proven ordering relative to the owner.
        interactive.filter { it.id != active.id && it.layer >= active.layer }
            .sortedWith(compareBy({ it.layer }, { it.id }))
            .map { other ->
                if (other.isActive) return null
                val bounds = Rect().also(other::getBoundsInScreen).toScreenBounds()
                if (bounds.width <= 0 || bounds.height <= 0) return null
                bounds
            }
    } finally {
        interactive.forEach { it.recycle() }
    }
}

private fun AccessibilityNodeInfo.describe(index: Int, parent: Int?): KeyguardNode {
    val actions = actionList.map { it.id }
    val click = !isPassword && !isEditable && isClickable && AccessibilityNodeInfo.ACTION_CLICK in actions
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return KeyguardNode(
        index, parent, packageName?.toString(), windowId, viewIdResourceName, className?.toString(),
        Rect().also(::getBoundsInScreen).toScreenBounds(), visible = isVisibleToUser, enabled = isEnabled,
        password = isPassword, editable = isEditable, clickable = click,
        // Older AOSP PasswordTextView reports the variation alone.
        numericPassword = isPassword && inputClass in setOf(0, InputType.TYPE_CLASS_NUMBER) &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        textPassword = isPassword && inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ),
        setText = AccessibilityNodeInfo.ACTION_SET_TEXT in actions,
        imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id in actions,
        textDigit = if (click && parent != null && packageName?.toString() == KeyguardSurfacePolicy.SYSTEM_UI) text.singleDigit() else null,
        descriptionDigit = if (click && parent != null && packageName?.toString() == KeyguardSurfacePolicy.SYSTEM_UI) contentDescription.singleDigit() else null,
    )
}

private fun CharSequence?.singleDigit(): Int? = this?.trim()?.singleOrNull()?.digitToIntOrNull()
