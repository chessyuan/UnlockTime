package com.phoneuse.service

import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.DeviceInputFence
import com.phoneuse.unified.NormalSurfaceIncompleteException
import com.phoneuse.unified.DeviceSessionLease
import com.phoneuse.unified.DeviceSessionRevokedException
import com.phoneuse.unified.DeviceRevocationReason
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.phoneuse.agent.QuickSettle
import com.phoneuse.agent.AppInfo
import com.phoneuse.agent.DeviceController
import com.phoneuse.agent.GlobalAction
import com.phoneuse.agent.ScreenMark
import com.phoneuse.agent.ServiceUnavailableException
import com.phoneuse.agent.TapSequenceResult
import com.phoneuse.assist.VoiceBridge
import com.phoneuse.screen.ScreenSnapshot
import com.phoneuse.screen.ScreenBusy
import com.phoneuse.screen.TreeCompressor
import com.phoneuse.agent.SettleKind
import android.os.SystemClock
import com.phoneuse.screen.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import com.phoneuse.screen.Bounds
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.URISyntaxException

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @param service accepted for API symmetry only; the live instance is read from
 * [PhoneUseAccessibilityService.instance] on every call so a killed service surfaces as
 * [ServiceUnavailableException].
 */
class AccessibilityDeviceController(
    @Suppress("unused") service: PhoneUseAccessibilityService? = null,
) : DeviceController {

    @Volatile
    var overlay: OverlayHooks? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun service(): PhoneUseAccessibilityService {
        AndroidDeviceAccess.requireAccess()
        return PhoneUseAccessibilityService.instance ?: throw ServiceUnavailableException()
    }

    /** Service selection alone reads no window; the read boundary below waits for a complete surface. */
    private fun readService(): PhoneUseAccessibilityService {
        AndroidDeviceAccess.requireSession()
        return PhoneUseAccessibilityService.instance ?: throw ServiceUnavailableException()
    }

    /** Registers the platform action atomically with lease validation before entering Binder. */
    private fun <T> input(block: () -> T): T {
        val lease = AndroidDeviceAccess.requireAccess()
        val ticket = AndroidDeviceAccess.coordinator.publish(lease) {
            com.phoneuse.unified.DeviceInputFence.shared.tryBegin()
        } ?: throw com.phoneuse.unified.DeviceUnavailableException("INPUT_STILL_RUNNING")
        return try { AndroidDeviceAccess.validateNormalSurface(lease); block() } finally { ticket.complete() }
    }

    fun invalidateScreens() {
        synchronized(readLock) {
            inFlight?.budget?.stop()
            inFlight?.result?.cancel()
            inFlight = null
            settledSample = null
        }
        synchronized(shown) { shown.clear() }
        lastSettled = null
        minStart = Long.MIN_VALUE
    }

    private suspend fun <T> passThrough(x: Int, y: Int, block: suspend () -> T): T =
        overlay.let { o -> if (o != null) o.passThrough(x, y, block) else block() }

    private fun displayBounds(s: PhoneUseAccessibilityService): Rect =
        (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds

    /**
     * A screen read: when it started and finished (uptime ms) and what it saw ([snap] may be truncated); its
     * hashes are computed when first needed.
     */
    private class Read(val startedAt: Long, val doneAt: Long, val snap: ScreenSnapshot, val lease: DeviceSessionLease) {
        /** The hash of the text the model is shown for it (what ToolExecutor and ScreenWatch compare). */
        val textHash: Int by lazy { TreeCompressor.compress(snap).text.hashCode() }
        val stability: Int by lazy { TreeCompressor.stabilityHash(snap) }
    }

    /** A tree read running in the background; callers wait for it only as long as they can. */
    private class InFlight(val startedAt: Long, val budget: ReadBudget, val result: Deferred<Read>, val lease: DeviceSessionLease)

    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readLock = Any()
    private var inFlight: InFlight? = null

    /**
     * The newest complete read that started after [minStart], kept for the read the model gets next (see
     * [reusable]). Set when any read completes, also one nobody waits for any more. Taken by [snapshot].
     */
    @Volatile
    private var settledSample: Read? = null

    /** Reads that started before this (the start of the current settle) show the screen before the action. */
    @Volatile
    private var minStart = Long.MIN_VALUE

    /** How the last settle ended; null once the read after it was taken. */
    @Volatile
    private var lastSettled: Settled? = null

    /** The last few reads handed out by [snapshot], newest first: the screens the model was shown. */
    private val shown = ArrayDeque<Read>()

    override suspend fun snapshot(): ScreenSnapshot = snapshot(fresh = false)

    override suspend fun freshSnapshot(): ScreenSnapshot = snapshot(fresh = true)

    private suspend fun snapshot(fresh: Boolean): ScreenSnapshot {
        AndroidDeviceAccess.ensureScreenAccess()
        return AndroidDeviceAccess.readNormalSurface { snapshotWhenReady(fresh) }
    }

    private suspend fun snapshotWhenReady(fresh: Boolean): ScreenSnapshot {
        val s = readService()
        val kept = settledSample
        settledSample = null
        val lastEvent = s.idleWaiter.lastEventAt
        // Right after a settle that gave up or saw no change, a read the settle started is as good as a new one.
        val notBefore = if (gaveUp()) minStart else lastEvent
        val read = if (!fresh && kept != null && reusable(kept, lastEvent)) kept
            else read(s, notBefore = notBefore, whole = true, fresh = fresh)
        // The settle's verdict covers only the read right after it.
        lastSettled = null
        AndroidDeviceAccess.validateNormalSurface(read.lease)
        AndroidDeviceAccess.coordinator.publish(read.lease) { synchronized(shown) {
            shown.addFirst(read)
            while (shown.size > SHOWN_KEPT) shown.removeLast()
        } }
        AndroidDeviceAccess.validateNormalSurface(read.lease)
        return read.snap
    }

    /**
     * Whether [kept] can stand in for a fresh read: complete, just finished, and nothing counted changed since it
     * started. Right after a settle that gave up (CAP, ANIMATED) or saw no change, events during the read do not
     * matter: a newer read would be no more settled, and costs as much again on a busy app.
     */
    private fun reusable(kept: Read, lastEventAt: Long): Boolean {
        if (!AndroidDeviceAccess.isCurrent(kept.lease)) return false
        if (kept.snap.truncated) return false
        if (SystemClock.uptimeMillis() - kept.doneAt > SAMPLE_REUSE_MS) return false
        if (lastEventAt < kept.startedAt) return true
        return kept.startedAt >= minStart && gaveUp()
    }

    /** The last settle ended without the screen holding still (CAP, ANIMATED) or saw nothing change. */
    private fun gaveUp(): Boolean = lastSettled.let { it == Settled.CAP || it == Settled.ANIMATED || it == Settled.NO_CHANGE }

    /** The structural hash of a screen the model was shown with text hash [textHash], if it is still known. */
    private fun shownStability(textHash: Int): Int? =
        synchronized(shown) { shown.firstOrNull { it.textHash == textHash } }?.stability

    /**
     * A tree read, waited for until [deadline] (uptime ms); a truncated empty read when it did not finish by
     * then. Reads run one at a time in the background: one already running that started at or after
     * [notBefore] is joined (its own deadline extended), an older one is stopped. A read nobody waits for any
     * more still finishes and is kept (see [settledSample]): each binder call into a busy app (a video starting)
     * can take a second, so a read is never thrown away half done to start over. With [whole] the caller waits
     * for the read to end however late (it stops descending at the deadline, so it ends after at most one more
     * binder call) and gets what it saw, truncated or not.
     */
    private suspend fun read(
        s: PhoneUseAccessibilityService,
        deadline: Long = SystemClock.uptimeMillis() + READ_BUDGET_MS,
        notBefore: Long = Long.MIN_VALUE,
        whole: Boolean = false,
        fresh: Boolean = false,
    ): Read = AndroidDeviceAccess.readNormalSurface(
        timeoutMs = (deadline - SystemClock.uptimeMillis()).coerceIn(0, SURFACE_WAIT_MS),
    ) { readWhenReady(s, deadline, notBefore, whole, fresh) }

    private suspend fun readWhenReady(
        s: PhoneUseAccessibilityService,
        deadline: Long,
        notBefore: Long,
        whole: Boolean,
        fresh: Boolean,
    ): Read {
        val lease = AndroidDeviceAccess.requireAccess()
        val f = synchronized(readLock) {
            val cur = inFlight
            if (!fresh && cur != null && cur.lease === lease && cur.result.isActive && cur.startedAt >= notBefore) {
                cur
            } else {
                if (cur != null && cur.result.isActive) cur.budget.stop()
                startRead(s).also { inFlight = it }
            }
        }
        f.budget.extendTo(deadline)
        if (whole) return f.result.await()
        val wait = deadline - SystemClock.uptimeMillis()
        val done = if (wait > 0) withTimeoutOrNull(wait) { f.result.await() } else null
        if (done != null) return done
        val now = SystemClock.uptimeMillis()
        return Read(now, now, ScreenSnapshot(null, 0, 0, emptyList(), truncated = true), lease)
    }

    private fun startRead(s: PhoneUseAccessibilityService): InFlight {
        val start = SystemClock.uptimeMillis()
        val budget = ReadBudget(start + READ_BUDGET_MS)
        val lease = AndroidDeviceAccess.requireAccess()
        val result = readScope.async(AndroidDeviceAccess.contextElement()) {
            AndroidDeviceAccess.validateNormalSurface(lease)
            val bounds = displayBounds(s)
            val snap = AccessibilityTreeReader(s).snapshot(
                bounds.width(), bounds.height(), s.resources.displayMetrics.density, budget,
            )
            AndroidDeviceAccess.validateNormalSurface(lease)
            val r = Read(start, SystemClock.uptimeMillis(), snap, lease)
            AndroidDeviceAccess.coordinator.publish(lease) {
                if (!snap.truncated && start >= minStart) {
                    val prev = settledSample
                    if (prev == null || prev.startedAt <= start) settledSample = r
                }
            }
            r
        }
        return InFlight(start, budget, result, lease)
    }

    override suspend fun screenshot(): ByteArray? = markedScreenshot(emptyList())

    override suspend fun canCaptureScreen(): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        return AndroidDeviceAccess.readNormalSurface {
            withCaptureOverlay { capture(it, copyPixels = false) === ShotResult.Allowed }
        }
    }

    override suspend fun markedScreenshot(marks: List<ScreenMark>): ByteArray? {
        AndroidDeviceAccess.ensureScreenAccess()
        return AndroidDeviceAccess.readNormalSurface(discard = { bytes: ByteArray? -> bytes?.fill(0) }) {
            withCaptureOverlay { captureJpeg(it, marks) }
        }
    }

    private suspend fun <T> withCaptureOverlay(block: suspend (PhoneUseAccessibilityService) -> T): T {
        val s = readService()
        val o = overlay
        return if (o != null) o.hiddenForCapture { block(s) } else block(s)
    }

    private suspend fun captureJpeg(s: PhoneUseAccessibilityService, marks: List<ScreenMark>): ByteArray? {
        val bitmap = (capture(s, copyPixels = true) as? ShotResult.Ok)?.bitmap ?: return null
        try { AndroidDeviceAccess.requireAccess() } catch (e: Exception) { bitmap.recycle(); throw e }
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_EDGE) {
            val f = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).toInt().coerceAtLeast(1),
                (bitmap.height * f).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        if (marks.isNotEmpty()) MarkPainter.paint(scaled, marks, scaled.width.toFloat() / bitmap.width)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        AndroidDeviceAccess.requireAccess()
        return out.toByteArray()
    }

    private sealed interface ShotResult {
        class Ok(val bitmap: Bitmap) : ShotResult
        data object Allowed : ShotResult
        class Error(val code: Int) : ShotResult
    }

    private suspend fun capture(s: PhoneUseAccessibilityService, copyPixels: Boolean): ShotResult {
        if (Build.VERSION.SDK_INT < 34) return captureWithRetry(s, copyPixels, windowId = null)
        // A whole-display buffer can succeed with a FLAG_SECURE app painted black. Check the actual app
        // windows, including an unfocused split-screen pane. No per-window pixels leave the callback.
        val windows = applicationWindowIds(s)
        if (windows.isEmpty()) return ShotResult.Error(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
        for (id in windows) {
            val allowed = captureWithRetry(s, copyPixels = false, windowId = id)
            if (allowed !== ShotResult.Allowed) return allowed
        }
        val display = captureWithRetry(s, copyPixels, windowId = null)
        try {
            if (windows == applicationWindowIds(s)) return display
        } catch (failure: Throwable) {
            (display as? ShotResult.Ok)?.bitmap?.recycle()
            throw failure
        }
        (display as? ShotResult.Ok)?.bitmap?.recycle()
        return ShotResult.Error(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
    }

    private fun applicationWindowIds(s: PhoneUseAccessibilityService): List<Int> =
        s.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.id }

    private suspend fun captureWithRetry(s: PhoneUseAccessibilityService, copyPixels: Boolean, windowId: Int?): ShotResult {
        val first = takeScreenshotOnce(s, copyPixels, windowId)
        if (first !is ShotResult.Error || first.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        delay(350)
        return takeScreenshotOnce(s, copyPixels, windowId)
    }

    /** The GPU readback and copy run off the main thread, so the overlay and event delivery do not stall. */
    private suspend fun takeScreenshotOnce(s: PhoneUseAccessibilityService, copyPixels: Boolean, windowId: Int?): ShotResult =
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { takeScreenshotUnbounded(s, copyPixels, windowId) } ?: ShotResult.Error(-2)

    private suspend fun takeScreenshotUnbounded(s: PhoneUseAccessibilityService, copyPixels: Boolean, windowId: Int?): ShotResult =
        suspendCancellableCoroutine { cont ->
            val lease = AndroidDeviceAccess.requireAccess()
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    if (!cont.isActive) {
                        buffer.close()
                        return
                    }
                    try { AndroidDeviceAccess.validateNormalSurface(lease) } catch (failure: Exception) {
                        buffer.close()
                        cont.resumeWithException(failure)
                        return
                    }
                    if (!copyPixels) {
                        buffer.close()
                        if (cont.isActive) cont.resume(ShotResult.Allowed)
                        return
                    }
                    val r: ShotResult = try {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, true)
                        hw?.recycle()
                        if (copy != null) ShotResult.Ok(copy) else ShotResult.Error(-1)
                    } catch (_: Throwable) {
                        ShotResult.Error(-1)
                    } finally {
                        buffer.close()
                    }
                    if (!cont.isActive) {
                        (r as? ShotResult.Ok)?.bitmap?.recycle()
                        return
                    }
                    try { AndroidDeviceAccess.validateNormalSurface(lease) } catch (failure: Exception) {
                        (r as? ShotResult.Ok)?.bitmap?.recycle()
                        cont.resumeWithException(failure)
                        return
                    }
                    cont.resume(r) { _, undelivered, _ -> (undelivered as? ShotResult.Ok)?.bitmap?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(ShotResult.Error(errorCode))
                }
            }
            if (windowId == null) {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, SHOT_EXECUTOR, callback)
            } else if (Build.VERSION.SDK_INT >= 34) {
                s.takeScreenshotOfWindow(windowId, SHOT_EXECUTOR, callback)
            } else cont.resume(ShotResult.Error(-1))
        }

    override suspend fun tap(x: Int, y: Int): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        overlay?.showTouch(x, y)
        return passThrough(x, y) { dispatch(point(x, y, TAP_MS), TAP_MS) }
    }

    override fun pageChangeVersion(): Long? {
        if (Build.VERSION.SDK_INT < 34) return null // No reliable per-window secure-content check for a tap group.
        val lease = AndroidDeviceAccess.requireSession()
        return PhoneUseAccessibilityService.instance?.idleWaiter?.pageChangeVersion(lease.epoch, VoiceBridge.sessions)
    }

    override suspend fun tapSequence(x: Int, y: Int, count: Int, intervalMs: Long, expectedVersion: Long?): TapSequenceResult {
        if (Build.VERSION.SDK_INT < 34) return TapSequenceResult(0, "MONITOR_UNAVAILABLE")
        if (count !in 1..MAX_TAPS_PER_CHUNK || intervalMs <= 0) return TapSequenceResult(0, "INVALID_ARGUMENT")
        val lease = AndroidDeviceAccess.ensureScreenAccess()
        val s = readService()
        val display = displayBounds(s)
        if (x !in 0 until display.width() || y !in 0 until display.height()) return TapSequenceResult(0, "OUT_OF_BOUNDS")
        val exposed = withContext(Dispatchers.Main.immediate) {
            AndroidDeviceAccess.validate(lease)
            s.canTapAppAt(x, y)
        }
        if (!exposed) return TapSequenceResult(0, "TARGET_COVERED")
        val voiceSession = VoiceBridge.sessions
        fun stopReason(): String? = when {
            VoiceBridge.listening.value || voiceSession != VoiceBridge.sessions -> "INTERRUPTED"
            expectedVersion != null && s.idleWaiter.pageChangeVersion(lease.epoch, voiceSession) != expectedVersion -> "PAGE_CHANGED"
            else -> null
        }
        val gesture = point(x, y, TAP_MS)
        var nextTapAt: Long? = null
        val result = runTapGestureChunk(count, intervalMs, SystemClock::uptimeMillis, ::stopReason,
            passThrough = { taps -> passThrough(x, y, taps) },
        ) {
            try {
                val dispatched = dispatchChecked(s, lease, gesture, TAP_MS, ::stopReason)
                if (dispatched is GestureDispatch.Completed) {
                    val startBound = dispatched.completedAt - TAP_MS
                    nextTapAt = if (intervalMs > Long.MAX_VALUE - startBound) Long.MAX_VALUE else startBound + intervalMs
                    dispatched.copy(durationMs = TAP_MS)
                } else dispatched
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: NormalSurfaceIncompleteException) {
                GestureDispatch.Stopped("SURFACE_UNAVAILABLE")
            } catch (_: RuntimeException) {
                GestureDispatch.Stopped("DISPATCH_FAILED")
            }
        }
        return result.copy(nextTapAtUptimeMs = nextTapAt)
    }

    override suspend fun longPress(x: Int, y: Int): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        overlay?.showTouch(x, y, long = true)
        return passThrough(x, y) { dispatch(point(x, y, LONG_PRESS_MS), LONG_PRESS_MS) }
    }

    override suspend fun click(node: UiNode, long: Boolean): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val live = AccessibilityTreeReader(service()).resolve(node) ?: return false
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var cur: AccessibilityNodeInfo? = live
        for (depth in 0..CLICK_PARENT_DEPTH) {
            val n = cur ?: return false
            if (n.isEnabled && (if (long) n.isLongClickable else n.isClickable)) {
                if (!n.isVisibleToUser) return false
                overlay?.showTouch(node.bounds.centerX, node.bounds.centerY, long = long)
                return input { n.performAction(action) }
            }
            cur = n.parent
        }
        return false
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val g = keepOffEdges(service(), EdgeGuard.Stroke(x1, y1, x2, y2))
        overlay?.showTouch(g.x1, g.y1, g.x2, g.y2)
        return passThrough(g.x1, g.y1) { dispatch(line(g.x1, g.y1, g.x2, g.y2, durationMs), durationMs) }
    }

    override suspend fun pinch(x: Int, y: Int, zoomIn: Boolean, durationMs: Long): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val e = edges(service())
        val (a, b) = EdgeGuard.pinch(x, y, zoomIn, e.width, e.height, e.side, e.top, e.bottom)
        overlay?.showTouch(a.x1, a.y1, a.x2, a.y2)
        overlay?.showTouch(b.x1, b.y1, b.x2, b.y2)
        val ms = durationMs.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path(a), 0, ms))
            .addStroke(GestureDescription.StrokeDescription(path(b), 0, ms))
            .build()
        return passThrough(x, y) { dispatch(gesture, ms) }
    }

    private class Edges(val width: Int, val height: Int, val side: Int, val top: Int, val bottom: Int)

    /** The screen and its system gesture strips, at least [EDGE_SIDE_DP] / [EDGE_TOP_DP] / [EDGE_BOTTOM_DP] wide. */
    private fun edges(s: PhoneUseAccessibilityService): Edges {
        val metrics = (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        val gestures = metrics.windowInsets.getInsets(WindowInsets.Type.systemGestures())
        val density = s.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return Edges(
            metrics.bounds.width(),
            metrics.bounds.height(),
            side = maxOf(gestures.left, gestures.right, dp(EDGE_SIDE_DP)),
            top = maxOf(gestures.top, dp(EDGE_TOP_DP)),
            bottom = maxOf(gestures.bottom, dp(EDGE_BOTTOM_DP)),
        )
    }

    private fun keepOffEdges(s: PhoneUseAccessibilityService, stroke: EdgeGuard.Stroke): EdgeGuard.Stroke {
        val e = edges(s)
        return EdgeGuard.keepOff(stroke, e.width, e.height, e.side, e.top, e.bottom)
    }

    private fun path(s: EdgeGuard.Stroke) = Path().apply {
        moveTo(s.x1.toFloat(), s.y1.toFloat())
        lineTo(s.x2.toFloat(), s.y2.toFloat())
    }

    private fun point(x: Int, y: Int, ms: Long): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS)))
            .build()
    }

    private fun line(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS)))
            .build()
    }

    /**
     * Dispatches [gesture] ([ms] long) and waits for its result, at most [ms] + [GESTURE_SLACK_MS]: a callback
     * that never comes (service disconnected, screen turned off) counts as failure instead of hanging the run
     * with the blocker letting touches through.
     */
    private suspend fun dispatch(gesture: GestureDescription, ms: Long): Boolean {
        val s = service()
        val lease = AndroidDeviceAccess.requireSession()
        return dispatchChecked(s, lease, gesture, ms) { null } is GestureDispatch.Completed
    }

    private suspend fun dispatchChecked(
        s: PhoneUseAccessibilityService,
        lease: DeviceSessionLease,
        gesture: GestureDescription,
        ms: Long,
        stopReason: () -> String?,
    ): GestureDispatch {
        val limit = ms.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS) + GESTURE_SLACK_MS
        return withTimeoutOrNull(limit) {
            if (!DeviceInputFence.shared.awaitIdle()) return@withTimeoutOrNull GestureDispatch.Stopped("INPUT_BUSY")
            // Accessibility events and gesture submission now share the service main looper.
            withContext(Dispatchers.Main.immediate) { submitGesture(s, lease, gesture, stopReason) }
        } ?: GestureDispatch.Stopped("DISPATCH_TIMEOUT")
    }

    private suspend fun submitGesture(
        s: PhoneUseAccessibilityService,
        lease: DeviceSessionLease,
        gesture: GestureDescription,
        stopReason: () -> String?,
    ): GestureDispatch {
        return suspendCancellableCoroutine { cont ->
            if (!cont.isActive) return@suspendCancellableCoroutine
            // Keep the normal-window check for every physical gesture. It reads window ownership only.
            AndroidDeviceAccess.validateNormalSurface(lease)
            val input = AndroidDeviceAccess.coordinator.publish(lease) { DeviceInputFence.shared.tryBegin() }
            if (input == null) {
                cont.resume(GestureDispatch.Stopped("INPUT_BUSY"))
                return@suspendCancellableCoroutine
            }
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    val completedAt = SystemClock.uptimeMillis()
                    input.complete()
                    if (!cont.isActive) return
                    if (runCatching { AndroidDeviceAccess.validateNormalSurface(lease) }.isSuccess) {
                        cont.resume(GestureDispatch.Completed(completedAt))
                    } else cont.cancel(DeviceSessionRevokedException(DeviceRevocationReason.INVALID_SESSION))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    input.complete()
                    if (cont.isActive) cont.resume(GestureDispatch.Stopped("GESTURE_CANCELLED"))
                }
            }
            try {
                AndroidDeviceAccess.coordinator.requireValid(lease)
                val stop = stopReason()
                if (!cont.isActive || stop != null) {
                    input.complete()
                    if (cont.isActive && stop != null) cont.resume(GestureDispatch.Stopped(stop))
                } else if (!s.dispatchGesture(gesture, callback, mainHandler)) {
                    input.complete()
                    if (cont.isActive) cont.resume(GestureDispatch.Stopped("DISPATCH_REJECTED"))
                }
            } catch (failure: Throwable) {
                input.complete()
                throw failure
            }
            // Deliberately no invokeOnCancellation cleanup: Android may still be moving the pointer.
        }
    }

    override suspend fun setText(node: UiNode, text: String): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val s = service()
        val reader = AccessibilityTreeReader(s)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val live = reader.resolve(node)
        AndroidDeviceAccess.requireAccess()
        if (live != null && !live.isPassword && live.packageName?.toString() != s.packageName && input { live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }) return true
        // Fallback: tap the field and type into whatever took input focus, if it is safe to.
        val d = displayBounds(s)
        val b = node.bounds.clippedTo(d.width(), d.height())
        if (!tap(b.centerX, b.centerY)) return false
        delay(200)
        AndroidDeviceAccess.requireAccess()
        val focused = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val ownWindow = focused.packageName?.toString() == AccessibilityTreeReader.OWN_PACKAGE
        if (focused.isPassword || !focused.isEditable || ownWindow) return false
        // Only the field that was tapped: a missed tap leaves focus where it was, and that field's text must not
        // be replaced.
        val fr = Rect()
        focused.getBoundsInScreen(fr)
        if (!Bounds(fr.left, fr.top, fr.right, fr.bottom).intersects(node.bounds)) return false
        return input { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
    }

    override suspend fun imeEnter(node: UiNode): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val live = AccessibilityTreeReader(service()).resolve(node) ?: return false
        return input { live.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }
    }

    override suspend fun scroll(node: UiNode?, direction: String): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val s = service()
        if (direction !in DIRECTIONS) return false
        if (node != null) {
            val live = AccessibilityTreeReader(s).resolve(node)
            if (live != null) {
                val directional = when (direction) {
                    "up" -> AccessibilityAction.ACTION_SCROLL_UP
                    "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                    "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                    else -> AccessibilityAction.ACTION_SCROLL_RIGHT
                }
                val done = if (live.actionList.contains(directional)) {
                    input { live.performAction(directional.id) }
                } else {
                    val generic = if (direction == "down" || direction == "right") {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    input { live.performAction(generic) }
                }
                if (done) return true
            }
            val b = node.bounds
            val d = displayBounds(s)
            val l = maxOf(b.left, 0)
            val t = maxOf(b.top, 0)
            val r = minOf(b.right, d.width())
            val bt = minOf(b.bottom, d.height())
            if (r <= l || bt <= t) return false
            return swipeInside(l, t, r, bt, direction)
        }
        val m = displayBounds(s)
        return swipeInside(0, 0, m.width(), m.height(), direction)
    }

    /** "down" reveals content further down, so the finger moves up. Uses the middle 60% of the box. */
    private suspend fun swipeInside(l: Int, t: Int, r: Int, b: Int, direction: String): Boolean {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val dx = ((r - l) * 0.3f).toInt()
        val dy = ((b - t) * 0.3f).toInt()
        return when (direction) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy)
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "right" -> swipe(cx + dx, cy, cx - dx, cy)
            "left" -> swipe(cx - dx, cy, cx + dx, cy)
            else -> false
        }
    }

    override suspend fun globalAction(action: GlobalAction): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val code = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        val s = service()
        // BACK and friends must reach the app, not a focused dock text field.
        overlay?.releaseInputFocus()
        return input { s.performGlobalAction(code) }
    }

    override fun playingOrInCall(): Boolean {
        val audio = PhoneUseAccessibilityService.instance?.getSystemService(AudioManager::class.java) ?: return false
        return audio.isMusicActive || audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    override suspend fun launchApp(packageName: String, fresh: Boolean): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val s = service()
        if (packageName == s.packageName) return false
        val intent = s.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Clears the app's back stack, so a finished booking or an old search from last time is not the first screen.
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return start(s, intent)
    }

    /**
     * Supports intent: URIs, bare Settings actions (android.settings.X) and anything ACTION_VIEW opens. Never our
     * own screens or files ([OpenUriPolicy]); an intent: URI loses its selector, clip data and launch flags (no
     * FLAG_GRANT_* that would hand our files to another app).
     */
    override suspend fun openUri(uri: String): Boolean {
        AndroidDeviceAccess.ensureScreenAccess()
        val s = service()
        if (OpenUriPolicy.refusal(uri, s.packageName) != null) return false
        val intent = try {
            when {
                uri.startsWith("intent:") -> Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    selector = null
                    clipData = null
                }
                SETTINGS_ACTION.matches(uri) -> Intent(uri)
                else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            }
        } catch (_: URISyntaxException) {
            return false
        }
        // Checked again on the parsed intent, whatever spelling the URI used.
        if (intent.component?.packageName == s.packageName || intent.`package` == s.packageName) return false
        if (intent.data?.authority?.startsWith(s.packageName) == true) return false
        intent.flags = OpenUriPolicy.launchFlags(intent.action)
        return start(s, intent)
    }

    private fun start(s: PhoneUseAccessibilityService, intent: Intent): Boolean =
        try {
            AndroidDeviceAccess.requireAccess()
            input { s.startActivity(intent) }
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    override suspend fun installedApps(): List<AppInfo> {
        val pm = PhoneUseAccessibilityService.instance?.packageManager ?: throw ServiceUnavailableException()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return withContext(Dispatchers.Default) {
            pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        }
    }

    /** A plain screen read: a short quiet (or a stable tree, not while loading), capped by [timeoutMs]. */
    override suspend fun waitForIdle(timeoutMs: Long) {
        AndroidDeviceAccess.ensureScreenAccess()
        val scaled = SETTLE.scaled(SettleSpeed.scale)
        val policy = scaled.copy(capMs = minOf(scaled.capMs, timeoutMs))
        settledSample = null
        minStart = SystemClock.uptimeMillis()
        lastSettled = readService().idleWaiter.settle(policy, waitForChange = false, sample = ::sample)
        AndroidDeviceAccess.readNormalSurface { Unit }
    }

    override suspend fun settleAfterAction(baseline: String?, kind: SettleKind): String {
        AndroidDeviceAccess.ensureScreenAccess()
        val (policy, _) = policies(kind, quick = false)
        return settle(policy.scaled(SettleSpeed.scale), baseline) + awaitAppWindow()
    }

    override suspend fun settleQuick(baseline: String?, kind: SettleKind): QuickSettle {
        AndroidDeviceAccess.ensureScreenAccess()
        val start = SystemClock.uptimeMillis()
        val (quick, full) = policies(kind, quick = true)
        val scale = SettleSpeed.scale
        val how = settle(quick.scaled(scale), baseline) + awaitAppWindow()
        val remaining = full.scaled(scale).capMs - (SystemClock.uptimeMillis() - start)
        return QuickSettle(how, remaining.coerceAtLeast(0))
    }

    /** The policy for [kind] (quick or full) and the full one. */
    private fun policies(kind: SettleKind, quick: Boolean): Pair<SettlePolicy, SettlePolicy> {
        val full = when (kind) {
            SettleKind.ACTION -> SETTLE
            SettleKind.APP -> APP_SETTLE
            SettleKind.LINK -> LINK_SETTLE
        }
        if (!quick) return full to full
        return when (kind) {
            SettleKind.ACTION -> QUICK_SETTLE
            SettleKind.APP -> QUICK_APP_SETTLE
            SettleKind.LINK -> QUICK_LINK_SETTLE
        } to full
    }

    /** One settle after an action; [baseline] is the model text of the screen it acted on. */
    private suspend fun settle(policy: SettlePolicy, baseline: String?): String {
        val waiter = readService().idleWaiter
        settledSample = null
        minStart = SystemClock.uptimeMillis()
        // The samples hash structure, not text: find the baseline's structural hash among the screens shown.
        val base = baseline?.let { shownStability(it.hashCode()) }
        val how = waiter.settle(policy, waitForChange = true, baseline = base, sample = ::sample)
        lastSettled = how
        return how.name.lowercase()
    }

    /**
     * Waits (up to [APP_WINDOW_WAIT_MS]) while no app window is up: one activity handing over to the next, or an
     * app still starting after the settle gave up on events. Reading then would show only the status bar. Returns
     * "" when there was an app window right away, else "+window" with the wait, for the step timings. A window
     * with nothing in it yet (a new page not laid out) counts as none. The read is the one the settle left
     * running, if any, and is kept for the model's read right after (see [snapshot]).
     */
    private suspend fun awaitAppWindow(): String {
        AndroidDeviceAccess.readNormalSurface { Unit }
        val kept = settledSample
        if (kept != null && !kept.snap.truncated && hasWindow(kept.snap)) return ""
        val s = readService()
        val start = SystemClock.uptimeMillis()
        var polls = 0
        while (true) {
            val snap = read(s, notBefore = minStart, whole = true).snap
            val waited = SystemClock.uptimeMillis() - start
            if (hasWindow(snap)) return if (polls == 0) "" else "+window${waited}ms"
            if (waited >= APP_WINDOW_WAIT_MS) return "+nowindow"
            // A window that stays empty may be a game or camera view: wait for it only briefly.
            if (snap.packageName != null && waited >= BLANK_WAIT_MS) return "+blank"
            polls++
            delay(APP_WINDOW_POLL_MS)
            // The next poll must read the screen anew.
            minStart = SystemClock.uptimeMillis()
        }
    }

    private fun hasWindow(snap: ScreenSnapshot) = snap.ownAppInFront || (snap.packageName != null && !ScreenBusy.isBlank(snap))

    /**
     * The hash of the screen text the model would see now, for the speculation watch (see ScreenWatch): while
     * the screen is structurally the one last handed out (only animations, coordinates or clock digits moved),
     * that read's text hash, so an animating map or player does not count as a change. Null when the read could
     * not finish in time (the watch then stops).
     */
    override suspend fun screenHash(): Int? {
        AndroidDeviceAccess.ensureScreenAccess()
        return AndroidDeviceAccess.readNormalSurface {
            val s = readService()
            val live = read(s, SystemClock.uptimeMillis() + SCREEN_HASH_BUDGET_MS)
            if (live.snap.truncated) null else {
                val last = synchronized(shown) { shown.firstOrNull() }
                if (last != null && last.stability == live.stability) last.textHash else live.textHash
            }
        }
    }

    /** A settle sample, read by [deadline]: the structural hash of the app windows and whether it is loading. */
    private suspend fun sample(deadline: Long): ScreenSample = readSurfaceSample {
        val s = readService()
        val r = read(s, deadline, notBefore = minStart)
        val snap = r.snap
        ScreenSample(if (snap.truncated) 0 else r.stability, ScreenBusy.isBusy(snap), snap.truncated)
    }

    private companion object {
        /** How far up from the named element a clickable parent is looked for (a label inside a list row). */
        const val CLICK_PARENT_DEPTH = 3
        const val TAP_MS = 50L
        const val APP_WINDOW_WAIT_MS = 2000L
        const val APP_WINDOW_POLL_MS = 100L
        const val SURFACE_WAIT_MS = 2_000L

        /** How long a window with nothing in it is waited for (a page not laid out yet). */
        const val BLANK_WAIT_MS = 1000L
        const val LONG_PRESS_MS = 600L
        const val MAX_EDGE = 1280
        const val MIN_GESTURE_MS = 1L
        const val MAX_GESTURE_MS = 10_000L
        /** A swipe starting this close to an edge reads as 뒤로가기 / 알림창 / 홈 (Samsung's back strip can be wide). */
        const val EDGE_SIDE_DP = 40
        const val EDGE_TOP_DP = 48
        const val EDGE_BOTTOM_DP = 56
        val DIRECTIONS = setOf("up", "down", "left", "right")
        val SETTINGS_ACTION = Regex("""^android\.settings\.[A-Z_]+$""")
        val SETTLE = SettlePolicy()

        /**
         * Opening an app: a cold start can sit on a quiet splash screen, so a stable screen counts only after
         * [SettlePolicy.firstEventMs]; before that only quiet ends the wait.
         */
        val APP_SETTLE = SettlePolicy(firstEventMs = 1500, quietMs = 300, capMs = 3000, animatedCapMs = 2000, sampleFromMs = 1500)

        /** Opening a link or intent: like an app, but a link that changes nothing gives up after 800 ms. */
        val LINK_SETTLE = SettlePolicy(firstEventMs = 800, quietMs = 300, capMs = 3000, animatedCapMs = 1500, sampleFromMs = 800)

        /**
         * Quick settles for speculative steps (see [Speculation]): they end at the first quiet point or a short
         * cap, and the agent loop watches the screen for the rest of the full settle while the model thinks.
         */
        val QUICK_SETTLE = SettlePolicy(firstEventMs = 250, quietMs = 150, capMs = 600, animatedCapMs = 400)
        val QUICK_APP_SETTLE = SettlePolicy(firstEventMs = 600, quietMs = 300, capMs = 1200, animatedCapMs = 900, sampleFromMs = 600)
        val QUICK_LINK_SETTLE = SettlePolicy(firstEventMs = 400, quietMs = 300, capMs = 1200, animatedCapMs = 800, sampleFromMs = 400)

        /** How old a settle sample may be and still stand in for the read after the settle. */
        const val SAMPLE_REUSE_MS = 400L

        /** After a settle that gave up (CAP / ANIMATED), a sample this fresh stands in even though events came. */
        const val CAP_REUSE_MS = 250L

        /** The longest a model read may take; it is cut short (marked truncated) after that. */
        const val READ_BUDGET_MS = 4000L

        /** The speculation watch's reads give up sooner: an unfinished one just stops the watch. */
        const val SCREEN_HASH_BUDGET_MS = 400L

        /** The screens handed out recently, to find a settle baseline's structural hash. */
        const val SHOWN_KEPT = 4

        const val GESTURE_SLACK_MS = 2000L
        const val SCREENSHOT_TIMEOUT_MS = 3000L
        val SHOT_EXECUTOR: java.util.concurrent.Executor = Dispatchers.Default.asExecutor()
    }
}
