package com.phoneuse.unified

import android.app.Activity
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Main-thread lifecycle helper; it never cancels native owners or grants device access. */
internal class OwnActivityInputGuard {
    private class Hold(val addedFlag: Boolean) { var job: Job? = null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val holds = WeakHashMap<Activity, Hold>()

    fun beforeStart(activity: Activity) {
        if (holds.containsKey(activity) || DeviceInputFence.shared.isIdle) return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val hold = Hold(activity.window.attributes.flags and flag == 0)
        holds[activity] = hold
        if (hold.addedFlag) activity.window.addFlags(flag)
        // A lost platform callback must not retain an Activity, or enable touches on a timeout.
        val reference = WeakReference(activity)
        hold.job = scope.launch {
            while (!DeviceInputFence.shared.awaitIdle()) {
                if (reference.get() == null) return@launch
            }
            reference.get()?.let { current ->
                if (holds[current] === hold) {
                    holds.remove(current)
                    restore(current, hold)
                }
            }
        }
    }

    /** A stopped/destroyed Activity no longer receives input; a restart applies the fence again. */
    fun stopped(activity: Activity) {
        val hold = holds.remove(activity) ?: return
        hold.job?.cancel()
        restore(activity, hold)
    }

    private fun restore(activity: Activity, hold: Hold) {
        // Remove only the bit this helper added; preserve a pre-existing NOT_TOUCHABLE flag.
        if (hold.addedFlag) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}
