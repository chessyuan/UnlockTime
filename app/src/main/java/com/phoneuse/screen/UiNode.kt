package com.phoneuse.screen

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /** These bounds cut to the (0, 0, width, height) display; unchanged if nothing would remain. */
    fun clippedTo(width: Int, height: Int): Bounds {
        val c = Bounds(maxOf(left, 0), maxOf(top, 0), minOf(right, width), minOf(bottom, height))
        return if (c.isEmpty) this else c
    }

    fun intersects(o: Bounds): Boolean =
        !isEmpty && !o.isEmpty && left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun contains(o: Bounds): Boolean = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom

    fun containsPoint(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

data class UiNode(
    val windowId: Int,
    val path: List<Int>, // 윈도 루트에서의 자식 인덱스 경로 (재조회용)
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    val viewId: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    /** A scrollable element that can still scroll on (down, or right when [horizontal]): content not yet seen. */
    val moreAfter: Boolean = false,
    /** It can scroll back (up / left). */
    val moreBefore: Boolean = false,
    /** It scrolls sideways only (a carousel or tab strip). */
    val horizontal: Boolean = false,
    /** AccessibilityNodeInfo.isVisibleToUser: false for hidden, detached or clipped-away views. */
    val visibleToUser: Boolean = true,
    /** It carries a RangeInfo (a determinate progress or seek bar, a slider); a spinner has none. */
    val hasRange: Boolean = false,
    /**
     * The part of [bounds] not under a window above this one (the keyboard, a dialog, a system bar), when part of
     * it is covered; null when nothing covers it. Taps go to the middle of this part.
     */
    val uncovered: Bounds? = null,
    /** Entirely under a window above (the keyboard, a dialog): not shown to the model, never tapped. */
    val covered: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    val isActionable: Boolean get() = clickable || longClickable || editable || scrollable || checkable
}


/** Actual visible application-window geometry, independent of an app's optional accessibility nodes. */
data class AppWindowRegion(val windowId: Int, val packageName: String, val bounds: Bounds)

data class ScreenSnapshot(
    val packageName: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val roots: List<UiNode>,
    val keyboardVisible: Boolean = false,
    /** DisplayMetrics.density (px per dp); [DEFAULT_DENSITY] when unknown (tests). */
    val density: Float = DEFAULT_DENSITY,
    /** Ids of the system windows among [roots] (status bar, navigation bar, notification shade); the rest are app windows. */
    val systemWindowIds: Set<Int> = emptySet(),
    /** Our own app is in front (its windows are never read), so [packageName] is null without a transition. */
    val ownAppInFront: Boolean = false,
    /** The active window is a system one (the notification shade or quick settings pulled down), not an app. */
    val activeIsSystem: Boolean = false,
    /** The read stopped early (its time ran out or the tree was huge): later parts of the tree are missing. */
    val truncated: Boolean = false,
    val appWindowRegions: List<AppWindowRegion> = emptyList(),
) {
    /** The roots of application windows (not system UI). */
    val appRoots: List<UiNode> get() = roots.filter { it.windowId !in systemWindowIds }

    /** A single app surface with no usable node layout, e.g. WeChat's drawn web page. */
    val drawnRegion: Bounds? get() {
        if (truncated || ownAppInFront || activeIsSystem || keyboardVisible || packageName.isNullOrBlank()) return null
        val root = appRoots.singleOrNull() ?: return null
        if (!root.bounds.isEmpty || root.children.isNotEmpty() || root.password || root.editable) return null
        if (appWindowRegions.isEmpty() || appWindowRegions.any { it.windowId != root.windowId || it.packageName != packageName ||
                it.bounds.isEmpty || !Bounds(0, 0, displayWidth, displayHeight).contains(it.bounds) }) return null
        // A system edge handle splits the remaining app region into horizontal strips. Take
        // their common width only if those strips cover every row, never their enclosing box.
        val parts = appWindowRegions.map { it.bounds }.sortedBy { it.top }
        val inner = Bounds(parts.maxOf { it.left }, parts.first().top, parts.minOf { it.right }, parts.maxOf { it.bottom })
        if (inner.isEmpty) return null
        var bottom = inner.top
        for (part in parts) {
            if (part.top > bottom) return null
            bottom = maxOf(bottom, part.bottom)
        }
        return inner
    }

    companion object {
        /** A typical phone (xxhdpi-ish); used only when the real density is not known. */
        const val DEFAULT_DENSITY = 2.75f
    }
}
