package org.localwake.app.compatibility

import org.json.JSONArray
import org.json.JSONObject
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.KeyguardNode
import org.localwake.app.device.KeyguardSurfacePolicy
import org.localwake.app.device.LockScreenAdapterRegistry
import org.localwake.app.device.ScreenBounds
import org.localwake.app.security.UnlockMethod

private const val SYSTEM_UI = KeyguardSurfacePolicy.SYSTEM_UI
private const val MAX_CONTROLS = 12

internal data class CompatibilityAdapter(val id: String, val version: Int) {
    companion object {
        fun read(id: String?, version: Int?): CompatibilityAdapter? =
            if (id != null && version != null && LockScreenAdapterRegistry.isCurrentRevision(id, version)) {
                CompatibilityAdapter(id, version)
            } else {
                null
            }
    }
}

internal data class CompatibilityScreen(
    val bounds: ScreenBounds,
    val density: Float,
    val displayId: Int,
    val rotation: Int,
) {
    fun json(): JSONObject = JSONObject()
        .put("bounds", bounds.array())
        .put("density", density.toDouble())
        .put("displayId", displayId)
        .put("rotation", rotation)

    companion object {
        fun from(geometry: DisplayGeometry): CompatibilityScreen? =
            create(geometry.bounds, geometry.density, geometry.displayId, geometry.rotation)

        fun read(json: JSONObject): CompatibilityScreen? = create(
            readBounds(json.optJSONArray("bounds")),
            (json.opt("density") as? Number)?.toFloat(),
            json.opt("displayId") as? Int,
            json.opt("rotation") as? Int,
        )

        private fun create(bounds: ScreenBounds?, density: Float?, displayId: Int?, rotation: Int?): CompatibilityScreen? {
            if (bounds == null || !bounds.safe() || density == null || density !in 0.1f..16f ||
                displayId == null || displayId < 0 || rotation == null || rotation !in 0..3) return null
            return CompatibilityScreen(bounds, density, displayId, rotation)
        }
    }
}

internal data class CompatibilityControl(
    val resourceId: String?,
    val className: String?,
    val bounds: ScreenBounds,
    val flags: Int,
) {
    fun json(): JSONObject = JSONObject()
        .put("resourceId", resourceId)
        .put("className", className)
        .put("bounds", bounds.array())

    companion object {
        fun from(node: KeyguardNode): CompatibilityControl {
            val flags = listOf(node.password, node.editable, node.clickable, node.setText, node.imeEnter,
                node.numericPassword, node.textPassword)
                .foldIndexed(0) { index, value, flag -> value or (if (flag) 1 shl index else 0) }
            return CompatibilityControl(
                node.viewId.takeIf { it in resourceIds }, node.className.takeIf { it in classNames }, node.bounds, flags,
            )
        }

        fun read(json: JSONObject): CompatibilityControl? {
            val bounds = readBounds(json.optJSONArray("bounds")) ?: return null
            val flags = (json.opt("flags") as? Int)?.takeIf { it in 0..127 } ?: return null
            return CompatibilityControl(
                (json.opt("resourceId") as? String).takeIf { it in resourceIds },
                (json.opt("className") as? String).takeIf { it in classNames },
                bounds,
                flags,
            )
        }
    }
}

internal data class CompatibilityRecord(
    val method: UnlockMethod,
    val checkedAt: Long,
    val pendingToken: String? = null,
    val screen: CompatibilityScreen? = null,
    val adapter: CompatibilityAdapter? = null,
    val controls: List<CompatibilityControl> = emptyList(),
    val structureDigest: String? = null,
    val verified: Boolean = false,
    val result: String? = null,
) {
    fun json(): JSONObject = JSONObject()
        .put("method", method.name)
        .put("checkedAt", checkedAt)
        .put("pendingToken", pendingToken)
        .put("screen", screen?.json())
        .put("adapterId", adapter?.id)
        .put("adapterVersion", adapter?.version)
        .put("controls", JSONArray().also { array -> controls.forEach { array.put(it.json().put("flags", it.flags)) } })
        .put("structureDigest", structureDigest)
        .put("verified", verified)
        .put("result", result)

    companion object {
        fun read(json: JSONObject): CompatibilityRecord? {
            val method = UnlockMethod.entries.firstOrNull { it.name == json.opt("method") } ?: return null
            val checkedAt = (json.opt("checkedAt") as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
            val screenJson = json.optJSONObject("screen")
            val screen = screenJson?.let { CompatibilityScreen.read(it) ?: return null }
            val adapter = CompatibilityAdapter.read(json.opt("adapterId") as? String, json.opt("adapterVersion") as? Int)
            val array = json.optJSONArray("controls") ?: return null
            if (array.length() > MAX_CONTROLS) return null
            val controls = List(array.length()) { index ->
                CompatibilityControl.read(array.optJSONObject(index) ?: return null) ?: return null
            }
            val digest = (json.opt("structureDigest") as? String)
            if (digest != null && (screen?.displayId != 0 || adapter == null || !controlCountValid(method, controls.size) ||
                    controls.any { !screen.bounds.contains(it.bounds) } ||
                    digest != structureDigest(method, screen, adapter, controls))) return null
            if (digest == null && (controls.isNotEmpty() || adapter != null)) return null
            val verified = json.opt("verified") as? Boolean ?: return null
            val result = (json.opt("result") as? String)?.let(::knownResult)
            val pending = (json.opt("pendingToken") as? String)?.takeIf { tokenId.matches(it) }
            if (verified && (pending != null || digest == null || result !in successCodes)) return null
            return CompatibilityRecord(method, checkedAt, pending, screen, adapter, controls, digest, verified, result)
        }
    }
}

internal fun selectedControls(
    method: UnlockMethod,
    geometry: DisplayGeometry,
    nodes: List<KeyguardNode>,
    controlIndices: List<Int>,
): List<CompatibilityControl>? {
    if (!controlCountValid(method, controlIndices.size) || controlIndices.distinct().size != controlIndices.size) return null
    val root = nodes.firstOrNull() ?: return null
    if (root.index != 0 || root.parent != null || root.packageName != SYSTEM_UI || !root.visible ||
        root.windowId < 0) return null
    val selected = controlIndices.map { index ->
        val node = nodes.singleOrNull { it.index == index } ?: return null
        if (node.index == root.index || node.packageName != SYSTEM_UI || node.windowId != root.windowId ||
            !node.visible || !node.enabled || !node.bounds.safe() || !geometry.bounds.contains(node.bounds)) return null
        CompatibilityControl.from(node)
    }
    return selected.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.bounds.right }, { it.bounds.bottom },
        { it.resourceId }, { it.className }, { it.flags }))
}

internal fun structureDigest(
    method: UnlockMethod,
    screen: CompatibilityScreen?,
    adapter: CompatibilityAdapter?,
    controls: List<CompatibilityControl>,
): String = sha256(buildString {
    append(method.name).append('|').append(screen).append('|').append(adapter)
    controls.forEach { append('|').append(it) }
})

internal fun diagnosticsControls(controls: List<CompatibilityControl>): JSONArray = JSONArray().also { array ->
    controls.asSequence().filter { it.resourceId != null || it.className != null }.take(4).forEach { array.put(it.json()) }
}

private fun controlCountValid(method: UnlockMethod, count: Int): Boolean = when (method) {
    UnlockMethod.PATTERN -> count == 1
    UnlockMethod.PIN -> count in 11..MAX_CONTROLS
    UnlockMethod.PASSWORD -> count in 1..2
}

private fun ScreenBounds.safe() =
    left in -100_000..100_000 && top in -100_000..100_000 &&
        right in -100_000..100_000 && bottom in -100_000..100_000 && right > left && bottom > top

private fun ScreenBounds.array(): JSONArray = JSONArray().put(left).put(top).put(right).put(bottom)

private fun readBounds(array: JSONArray?): ScreenBounds? {
    if (array?.length() != 4) return null
    val left = array.opt(0) as? Int ?: return null
    val top = array.opt(1) as? Int ?: return null
    val right = array.opt(2) as? Int ?: return null
    val bottom = array.opt(3) as? Int ?: return null
    return ScreenBounds(left, top, right, bottom).takeIf { it.safe() }
}

private val tokenId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private val resourceIds = setOf(
    "lockPatternView", "lock_pattern_view", "pinEntry", "pin_entry", "passwordEntry", "password_entry",
    "key_enter", "pin_confirm", "pin_enter", "password_confirm", "password_enter",
).map { "$SYSTEM_UI:id/$it" }.toSet()

private val classNames = setOf(
    "com.android.internal.widget.LockPatternView",
    "com.android.keyguard.PasswordTextView",
    "com.android.keyguard.SecPasswordTextView",
    "android.widget.EditText",
)

internal val successCodes = setOf("SUCCESS", "UNLOCKED")
internal val unsupportedReasons = setOf("DISPLAY_UNSUPPORTED", "MAGNIFICATION_UNSUPPORTED")

private val resultCodes = successCodes + unsupportedReasons + setOf(
    "GRID_DYNAMIC_FOUND", "PATTERN_GRID_UNAVAILABLE", "PIN_KEYPAD_UNAVAILABLE", "PASSWORD_FIELD_UNAVAILABLE",
    "ALREADY_UNLOCKED", "AUTHENTICATION_REJECTED", "UNLOCK_METHOD_CHANGED", "UNLOCK_SURFACE_CHANGED",
    "PIN_ENTRY_NOT_EMPTY", "PIN_ENTRY_UNVERIFIED", "CREDENTIAL_INPUT_REJECTED", "CREDENTIAL_NOT_READY",
    "PATTERN_GRID_CHANGED", "KEYGUARD_STILL_LOCKED", "PATTERN_GESTURE_CANCELLED", "PATTERN_GESTURE_REJECTED",
    "PATTERN_GESTURE_TIMEOUT", "SERVICE_DISCONNECTED", "EXECUTION_INTERRUPTED", "EXECUTION_TIMEOUT",
    "EXECUTION_ERROR", "TASK_CHANGED", "MANUAL_RESET_REQUIRED", "FIRST_MANUAL_UNLOCK_REQUIRED",
    "INSPECTION_COMPLETE", "LOCK_SCREEN_UNAVAILABLE", "INTERRUPTED", "UNKNOWN_ERROR",
)

internal fun knownResult(code: String): String = if (code in resultCodes) code else "UNKNOWN_ERROR"
