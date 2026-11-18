package com.phoneuse.service

import com.phoneuse.unified.NormalSurfaceTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A short settle sample proves nothing when the surface is still rebuilding at its deadline. */
internal suspend fun readSurfaceSample(read: suspend () -> ScreenSample): ScreenSample = try {
    read()
} catch (_: NormalSurfaceTimeoutException) {
    // A genuine task cancellation may race the sample deadline; never turn that into a usable result.
    currentCoroutineContext().ensureActive()
    ScreenSample(0, busy = true, truncated = true)
}
