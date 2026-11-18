package com.phoneuse.ui.settings

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class TaskSetupRetryState { IDLE, CHECKING, SUCCEEDED, FAILED }

/** One user-requested recovery at a time, independent of either composable's lifetime. */
internal class TaskSetupRetryController(private val scope: CoroutineScope) {
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(TaskSetupRetryState.IDLE)
    val state: StateFlow<TaskSetupRetryState> = mutableState.asStateFlow()

    fun retry(recover: suspend () -> Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false
        mutableState.value = TaskSetupRetryState.CHECKING
        // Enter the finally block even if the supplied scope has already been cancelled.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                mutableState.value = if (recover()) TaskSetupRetryState.SUCCEEDED else TaskSetupRetryState.FAILED
            } catch (cancelled: CancellationException) {
                mutableState.value = TaskSetupRetryState.IDLE
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = TaskSetupRetryState.FAILED
            } finally {
                running.set(false)
            }
        }
        return true
    }
}
