package org.localwake.app.device

internal object TrustedLockScreenDetector {
    fun trustsWindow(geometry: DisplayGeometry, window: LockScreenWindow): Boolean =
        geometry.displayId == 0 && geometry.rotation in 0..3 &&
            geometry.density.isFinite() && geometry.density > 0f &&
            window.id >= 0 && window.layer >= 0 && window.displayId == geometry.displayId && window.active &&
            window.packageName == KeyguardSurfacePolicy.SYSTEM_UI && window.systemInstalled &&
            (window.type == LockScreenWindowType.SYSTEM || window.type == LockScreenWindowType.APPLICATION) &&
            geometry.bounds.contains(window.bounds)

    fun trustsSnapshot(snapshot: LockScreenSnapshot): Boolean {
        val root = snapshot.nodes.firstOrNull() ?: return false
        return trustsWindow(snapshot.geometry, snapshot.window) &&
            root.index == 0 && root.parent == null && root.visible &&
            root.packageName == snapshot.window.packageName && root.windowId == snapshot.window.id &&
            snapshot.nodes.withIndex().all { (index, node) ->
                node.index == index && (index == 0 || node.parent?.let { it in 0 until index } == true)
            }
    }

    fun permitsPreparation(snapshot: LockScreenSnapshot): Boolean =
        trustsSnapshot(snapshot) && snapshot.geometry.permitsCoordinateInput && snapshot.window.touchableRectangular &&
            snapshot.nodes.all {
                !it.visible || (it.windowId == snapshot.window.id &&
                    !it.password && !it.editable && !it.setText &&
                    (it.packageName == snapshot.window.packageName ||
                        (!it.packageName.isNullOrBlank() && snapshot.window.bounds.contains(it.bounds))))
            } && !KeyguardSurfacePolicy.recognizesEntry(snapshot.nodes)
}
