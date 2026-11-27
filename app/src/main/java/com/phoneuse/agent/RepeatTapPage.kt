package com.phoneuse.agent

import com.phoneuse.screen.Bounds
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.UiNode

/** The original application windows and their controls. Focus feedback alone does not move a target. */
internal class RepeatTapPage(private val original: ScreenSnapshot, private val x: Int, private val y: Int) {
    fun fixedPointAvailable(now: ScreenSnapshot): Boolean = usable(now) &&
        original.packageName == now.packageName && original.displayWidth == now.displayWidth &&
        original.displayHeight == now.displayHeight

    fun matches(now: ScreenSnapshot): Boolean {
        if (!usable(original) || !usable(now)) return false
        return original.packageName == now.packageName &&
            original.displayWidth == now.displayWidth && original.displayHeight == now.displayHeight &&
            original.density == now.density && original.keyboardVisible == now.keyboardVisible &&
            original.appWindowRegions == now.appWindowRegions &&
            sameNodes(original.appRoots, now.appRoots)
    }

    private fun usable(tree: ScreenSnapshot): Boolean =
        !tree.truncated && !tree.ownAppInFront && !tree.activeIsSystem && !tree.packageName.isNullOrBlank() &&
            (tree.appRoots.any { it.visibleToUser && !it.covered && (it.uncovered ?: it.bounds).includes(x, y) } ||
                tree.drawnRegion?.containsPoint(x, y) == true) &&
            tree.roots.none { it.windowId in tree.systemWindowIds && it.visibleToUser && it.bounds.includes(x, y) }

    private fun sameNodes(a: List<UiNode>, b: List<UiNode>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val old = a[i]
            val fresh = b[i]
            // Compare window IDs, paths, bounds, coverage, labels and actionable state throughout
            // the app tree (also catches a moved/replaced control inside a requested rectangle).
            old.copy(focused = fresh.focused, children = fresh.children) == fresh &&
                sameNodes(old.children, fresh.children)
        }

    private fun Bounds.includes(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}
