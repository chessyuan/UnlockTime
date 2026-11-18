package org.localwake.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Local unlock's minimum Android surface. The Context must be the live service, not the Application.
 * Returned nodes/windows retain their existing caller-owned recycle lifecycle. No credential or
 * general Agent controller is exposed here; local gestures bypass overlays that could show a trail.
 */
interface UnlockAccessibilityHost {
    val context: Context
    fun isReady(): Boolean
    fun activeRoot(): AccessibilityNodeInfo?
    fun interactiveWindows(): List<AccessibilityWindowInfo>
    fun clearNodeCache(): Boolean
    fun magnification(): AccessibilityService.MagnificationController
    fun dispatchUnlockGesture(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?,
    ): Boolean
    fun foregroundPackage(): String?
}
