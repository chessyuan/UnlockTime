package com.phoneuse.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSetupRetryControllerTest {
    @Test fun twoUiEntrypointsCannotSubmitTwoRecoveriesWhileOneIsRunning() = runTest {
        val controller = TaskSetupRetryController(this)
        val finish = CompletableDeferred<Unit>()
        var attempts = 0
        assertTrue(controller.retry { attempts++; finish.await(); true })
        assertEquals(TaskSetupRetryState.CHECKING, controller.state.value)
        assertFalse(controller.retry { attempts++; true })
        finish.complete(Unit)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(TaskSetupRetryState.SUCCEEDED, controller.state.value)
    }

    @Test fun failedRecoveryReleasesTheButtonForAnExplicitNewAttempt() = runTest {
        val controller = TaskSetupRetryController(this)
        assertTrue(controller.retry { error("synthetic local storage failure") })
        assertEquals(TaskSetupRetryState.FAILED, controller.state.value)
        assertTrue(controller.retry { true })
        assertEquals(TaskSetupRetryState.SUCCEEDED, controller.state.value)
    }

    @Test fun refusedRecoveryDoesNotReportReady() = runTest {
        val controller = TaskSetupRetryController(this)
        controller.retry { false }
        assertEquals(TaskSetupRetryState.FAILED, controller.state.value)
    }
}
