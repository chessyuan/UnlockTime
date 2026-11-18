// Adapted from OpenClaw LocalPinUnlocker. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.device

internal object PatternGridProvider {
    private val ids = setOf("lockpatternview", "lock_pattern_view")

    fun identifies(node: KeyguardNode): Boolean = node.resourceEntry in ids || node.simpleClass == "lockpatternview"

    fun select(geometry: DisplayGeometry, matches: List<KeyguardNode>): KeyguardSurface.Pattern? {
        val node = matches.singleOrNull() ?: return null
        val bounds = node.bounds
        if (!node.enabled || !geometry.bounds.contains(bounds)) return null
        val screenSide = minOf(geometry.bounds.width, geometry.bounds.height).toFloat()
        val minimum = (120f * geometry.density).coerceIn(screenSide * 0.18f, screenSide * 0.35f)
        if (minOf(bounds.width, bounds.height) < minimum || bounds.width.toFloat() / bounds.height !in 0.75f..1.33f) {
            return null
        }
        return KeyguardSurface.Pattern(node.index)
    }
}
