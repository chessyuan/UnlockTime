package org.localwake.app.device

import org.localwake.app.security.UnlockMethod

internal data class AdapterSelection(
    val surface: KeyguardSurface,
    val adapterId: String,
    val adapterVersion: Int,
)

internal interface LockScreenAdapter {
    val id: String
    val version: Int
    fun select(method: UnlockMethod, snapshot: LockScreenSnapshot): KeyguardSurface?
}

internal object LockScreenAdapterRegistry {
    fun isCurrentRevision(id: String, version: Int): Boolean =
        listOf(AospLockScreenAdapter, SamsungLockScreenAdapter).any { it.id == id && it.version == version }

    fun select(method: UnlockMethod, snapshot: LockScreenSnapshot): AdapterSelection? {
        if (!TrustedLockScreenDetector.trustsSnapshot(snapshot)) return null
        val adapter = if (snapshot.device.manufacturer.equals("samsung", ignoreCase = true))
            SamsungLockScreenAdapter else AospLockScreenAdapter
        val surface = adapter.select(method, snapshot) ?: return null
        val nodes = snapshot.nodes.associateBy { it.index }
        val permitsBounds = if (surface is KeyguardSurface.Pattern) snapshot::permitsCoordinateBounds
            else snapshot::permitsControlBounds
        if (surface.controls().any { index -> nodes[index]?.bounds?.let(permitsBounds) != true }) {
            return null
        }
        return AdapterSelection(surface, adapter.id, adapter.version)
    }
}

private object AospLockScreenAdapter : LockScreenAdapter {
    override val id = "aosp"
    override val version = 1

    override fun select(method: UnlockMethod, snapshot: LockScreenSnapshot) =
        KeyguardSurfacePolicy.select(method, snapshot.geometry, snapshot.nodes)
}

private object SamsungLockScreenAdapter : LockScreenAdapter {
    override val id = "samsung-one-ui"
    override val version = 1

    override fun select(method: UnlockMethod, snapshot: LockScreenSnapshot) =
        KeyguardSurfacePolicy.select(method, snapshot.geometry, snapshot.nodes, samsung = true)
}
