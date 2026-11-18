// Adapted from OpenClaw LocalPinUnlocker. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.unlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.localwake.app.accessibility.UnlockAccessibilityHost
import org.localwake.app.device.DisplayGeometry
import org.localwake.app.device.LockScreenSnapshot
import org.localwake.app.device.ScreenBounds
import org.localwake.app.device.ScreenGeometry
import org.localwake.app.device.SystemUiTree
import org.localwake.app.device.toRect
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal class LocalPatternUnlocker(
    private val service: UnlockAccessibilityHost,
    private val geometry: ScreenGeometry,
    private val trace: (String) -> Unit,
) {
    // Preserve the bounded centre swipes for Samsung Accidental Touch Protection and the reveal swipe.
    suspend fun prepareSurface(surfaceForSwipe: suspend () -> LockScreenSnapshot?) {
        repeat(3) { index ->
            val surface = surfaceForSwipe() ?: return
            trace("surface.centerSwipe=${index + 1}")
            // A moving preview can invalidate a read-only preflight. Re-observe within the
            // original three-cycle bound; no credential or failed credential is replayed.
            swipe(surface, 0.66f, 0.34f, 650)
            delay(650)
        }
        val surface = surfaceForSwipe() ?: return
        trace("surface.revealSwipe")
        if (swipe(surface, 0.82f, 0.28f, 480)) delay(700)
    }

    private suspend fun swipe(surface: LockScreenSnapshot, from: Float, to: Float, duration: Long): Boolean {
        val expected = surface.geometry
        val screen = expected.bounds.toRect()
        val startY = screen.top + screen.height() * from
        val endY = screen.top + screen.height() * to
        val x = surface.preparationX(startY, endY) ?: return false
        val strokeBounds = ScreenBounds(
            floor(x).toInt(), floor(minOf(startY, endY)).toInt(),
            floor(x).toInt() + 1, ceil(maxOf(startY, endY)).toInt() + 1,
        )
        if (!surface.permitsCoordinateBounds(strokeBounds) || geometry.current() != expected) {
            trace("surface.check=GEOMETRY_OR_OCCLUSION")
            return false
        }
        val stillClear = SystemUiTree.capture(service, geometry)?.use { fresh ->
            val valid = fresh.snapshot.geometry == expected && fresh.snapshot.window == surface.window &&
                fresh.canPrepare() && fresh.snapshot.permitsCoordinateBounds(strokeBounds)
            if (!valid) trace("surface.check=PREVIEW_CHANGED")
            valid
        } == true
        if (!stillClear) { trace("surface.check=NOT_STABLE"); return false }
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val result = dispatchAndAwait(GestureDescription.Builder().setDisplayId(expected.displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build())
        trace("surface.gesture=$result")
        // A prepared dispatch is followed by the original quiet interval and a fresh surface check.
        return true
    }

    suspend fun draw(bounds: Rect, pattern: IntArray, expected: DisplayGeometry): String? {
        if (!expected.permitsCoordinateInput || geometry.current() != expected) return "PATTERN_GRID_CHANGED"
        val side = min(bounds.width(), bounds.height()).toFloat()
        val left = bounds.centerX() - side / 2f
        val top = bounds.centerY() - side / 2f
        val cell = side / 3f
        val path = Path()
        try {
            pattern.forEachIndexed { index, point ->
                val zeroBased = point - 1
                val x = left + (zeroBased % 3 + 0.5f) * cell
                val y = top + (zeroBased / 3 + 0.5f) * cell
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val duration = (pattern.size * 180L).coerceIn(700L, 1_800L)
            val gesture = GestureDescription.Builder().setDisplayId(expected.displayId)
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            trace("pattern.dispatch")
            val result = dispatchAndAwait(gesture)
            trace("pattern.result=${result.name}")
            return if (result == GestureOutcome.COMPLETED) null else "PATTERN_GESTURE_${result.name}"
        } finally {
            path.reset()
        }
    }

    private enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED, TIMEOUT }

    private suspend fun dispatchAndAwait(gesture: GestureDescription): GestureOutcome = withTimeoutOrNull(4_000) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(GestureOutcome.COMPLETED)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(GestureOutcome.CANCELLED)
                }
            }
            if (!service.dispatchUnlockGesture(gesture, callback, null) && continuation.isActive) {
                continuation.resume(GestureOutcome.REJECTED)
            }
        }
    } ?: GestureOutcome.TIMEOUT
}
