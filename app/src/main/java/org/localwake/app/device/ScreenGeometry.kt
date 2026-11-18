package org.localwake.app.device

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import org.localwake.app.accessibility.UnlockAccessibilityHost

internal class ScreenGeometry(private val service: UnlockAccessibilityHost) {
    fun current(): DisplayGeometry? {
        val display = service.context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = service.context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics ?: return null
        val density = if (Build.VERSION.SDK_INT >= 34) metrics.density else service.context.resources.displayMetrics.density
        return withObservedMagnification(DisplayGeometry(metrics.bounds.toScreenBounds(), density, display.displayId, display.rotation))
    }

    private fun withObservedMagnification(geometry: DisplayGeometry): DisplayGeometry {
        // The legacy scale getter cannot observe window magnification on API 31 and 32.
        if (Build.VERSION.SDK_INT < 33 || !service.isReady()) return geometry
        return try {
            val config = service.magnification().magnificationConfig ?: return geometry
            if (!service.isReady()) return geometry
            geometry.copy(
                magnificationScale = config.scale,
                magnificationActivated = if (Build.VERSION.SDK_INT >= 34) config.isActivated else null,
            )
        } catch (_: SecurityException) {
            geometry
        }
    }
}

internal fun Rect.toScreenBounds() = ScreenBounds(left, top, right, bottom)
internal fun ScreenBounds.toRect() = Rect(left, top, right, bottom)
