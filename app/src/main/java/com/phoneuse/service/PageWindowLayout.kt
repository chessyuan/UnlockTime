package com.phoneuse.service

internal enum class PageWindowRole { APPLICATION, OWN_OVERLAY, KEYBOARD, SYSTEM_DECORATION, OTHER }

/** Android supplies the windows front-to-back. [region] is the window's full touchable shape. */
internal data class PageWindow<G>(val id: Int, val role: PageWindowRole, val region: G)

internal class PageWindowLayout<G> {
    private var previous: List<PageWindow<G>>? = null
    var ignoredContentWindows: Set<Int> = emptySet()
        private set

    fun update(windows: List<PageWindow<G>>): Boolean {
        ignoredContentWindows = windows.filter { window ->
            window.role == PageWindowRole.OWN_OVERLAY || window.role == PageWindowRole.KEYBOARD ||
                window.role == PageWindowRole.SYSTEM_DECORATION
        }.mapTo(HashSet()) { it.id }
        val layout = windows.filterNot { it.role == PageWindowRole.OWN_OVERLAY }
        val changed = previous?.let { it != layout } ?: false
        previous = layout
        return changed
    }

    fun appExposedAt(windows: List<PageWindow<G>>, containsPoint: (G) -> Boolean): Boolean =
        windows.firstOrNull { it.role != PageWindowRole.OWN_OVERLAY && containsPoint(it.region) }?.role ==
            PageWindowRole.APPLICATION
}
