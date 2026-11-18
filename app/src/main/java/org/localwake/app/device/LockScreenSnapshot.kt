package org.localwake.app.device

internal data class DeviceFingerprint(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val buildFingerprint: String,
)

internal enum class LockScreenWindowType { APPLICATION, SYSTEM, OTHER }

internal data class LockScreenWindow(
    val id: Int,
    val displayId: Int,
    val type: LockScreenWindowType,
    val active: Boolean,
    val bounds: ScreenBounds,
    val packageName: String?,
    val systemInstalled: Boolean,
    val layer: Int,
    val touchableRectangular: Boolean = false,
)

internal class LockScreenSnapshot(
    val device: DeviceFingerprint,
    val geometry: DisplayGeometry,
    val window: LockScreenWindow,
    internal val nodes: List<KeyguardNode>,
    val higherWindowBounds: List<ScreenBounds>,
) {
    // A SystemUI window can host another package's widget. It never becomes a credential target.
    private val excludedBounds: List<ScreenBounds> get() = higherWindowBounds + nodes.filter {
        it.visible && (it.packageName != window.packageName || it.windowId != window.id)
    }.map { it.bounds }

    fun permitsControlBounds(bounds: ScreenBounds): Boolean =
        window.bounds.contains(bounds) && excludedBounds.none { it.overlaps(bounds) }

    fun permitsCoordinateBounds(bounds: ScreenBounds): Boolean =
        geometry.permitsCoordinateInput && window.touchableRectangular && nodes.none {
            it.visible && (it.packageName != window.packageName || it.windowId != window.id) &&
                (it.packageName.isNullOrBlank() || it.windowId != window.id || !window.bounds.contains(it.bounds))
        } && permitsControlBounds(bounds)

    /** Non-credential reveal swipe only. Keep the proven centre line when it is unobstructed. */
    fun preparationX(startY: Float, endY: Float): Float? {
        if (!TrustedLockScreenDetector.permitsPreparation(this)) return null
        val top = kotlin.math.floor(minOf(startY, endY)).toInt()
        val bottom = kotlin.math.ceil(maxOf(startY, endY)).toInt() + 1
        val centre = (geometry.bounds.left + geometry.bounds.right) / 2f
        fun stroke(x: Float) = ScreenBounds(kotlin.math.floor(x).toInt(), top, kotlin.math.floor(x).toInt() + 1, bottom)
        if (!window.bounds.contains(stroke(centre))) return null
        if (permitsCoordinateBounds(stroke(centre))) return centre
        val margin = (16f * geometry.density).coerceAtLeast(8f).toInt()
        var spans = listOf((window.bounds.left + margin) to (window.bounds.right - margin))
        for (block in excludedBounds.filter { it.top < bottom && top < it.bottom }) {
            spans = spans.flatMap { (left, right) ->
                if (block.right + margin <= left || block.left - margin >= right) listOf(left to right)
                else listOf(left to minOf(right, block.left - margin), maxOf(left, block.right + margin) to right)
                    .filter { (a, b) -> b - a >= margin * 2 }
            }
        }
        return spans.asSequence().filter { (a, b) -> b - a >= margin * 2 }
            .map { (a, b) -> (a + b) / 2f }.filter { permitsCoordinateBounds(stroke(it)) }
            .minByOrNull { kotlin.math.abs(it - centre) }
    }

    fun matchesSelectedStructure(
        previous: LockScreenSnapshot,
        initial: AdapterSelection,
        current: AdapterSelection,
    ): Boolean {
        if (device != previous.device || geometry != previous.geometry || window != previous.window ||
            initial.adapterId != current.adapterId || initial.adapterVersion != current.adapterVersion) return false
        val expected = previous.structure(initial.surface)
        return expected != null && expected == structure(current.surface)
    }

    private fun structure(surface: KeyguardSurface): List<KeyguardNode>? {
        val byIndex = nodes.associateBy { it.index }
        return (listOf(0) + surface.controls()).map { index ->
            byIndex[index]?.copy(index = 0, parent = null, textDigit = null, descriptionDigit = null) ?: return null
        }
    }
}

internal fun KeyguardSurface.controls(): List<Int> = when (this) {
    is KeyguardSurface.Pattern -> listOf(grid)
    is KeyguardSurface.Pin -> listOf(entry) + digits + listOfNotNull(confirm)
    is KeyguardSurface.Password -> listOfNotNull(field, confirm)
}
