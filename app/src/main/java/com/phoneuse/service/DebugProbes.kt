package com.phoneuse.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.phoneuse.unified.AndroidDeviceAccess

/** Metadata-only unified diagnostics. Experimental secondary-display capture is not a model capability. */
class DebugProbes(private val service: AccessibilityService) {
    suspend fun run(test: String, pkg: String) {
        val lease = AndroidDeviceAccess.ensureScreenAccess()
        if (test != "windows") {
            Log.d("PhoneUseProbe", "Experimental display probes are unavailable in UnlockTime")
            return
        }
        for (window in service.windows) {
            AndroidDeviceAccess.validateNormalSurface(lease)
            // No roots, labels, screenshot, gesture path or package-controlled text is logged.
            Log.d("PhoneUseProbe", "window id=${window.id} type=${window.type} display=${window.displayId}")
        }
    }
}
