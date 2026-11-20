package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live engine's GPU finish runs when the stroke ends, not when the commit's turn comes, so the
 * engine (and the document lease the next stroke's setup waits for) is back after its own readback,
 * whatever the commit queue is doing. Fakes only: events in a list.
 */
class LiveFinishTest {

    @Test
    fun `the engine is given back while an earlier commit is still unpublished`() = runTest {
        val events = ArrayList<String>()
        val earlierCommit = CompletableDeferred<Unit>() // the commit queue, blocked
        val finish = startLiveFinish(StandardTestDispatcher(testScheduler)) {
            events += "readback"
            events += "engine released"
            "live layer"
        }
        // This stroke's own commit: waits for the earlier one first, then awaits the finish.
        val commit = launch {
            earlierCommit.await()
            events += "commit awaits finish: ${finish.await()}"
        }
        advanceUntilIdle()
        assertEquals(listOf("readback", "engine released"), events)
        assertFalse("the commit has not run", commit.isCompleted)
        earlierCommit.complete(Unit)
        advanceUntilIdle()
        assertEquals("commit awaits finish: live layer", events.last())
    }

    @Test
    fun `a commit cancelled before it awaits still lets the finish give the engine back`() = runTest {
        var released = false
        val finish = startLiveFinish(StandardTestDispatcher(testScheduler)) { released = true; "x" }
        val commit = launch { finish.await() }
        commit.cancel() // an undo cancels the commit coroutine
        advanceUntilIdle()
        assertTrue("the engine must not stay held", released)
    }

    @Test
    fun `a finish that fails surfaces to the commit that awaits it, not to the scope`() = runTest {
        // The editor's viewModelScope has a SupervisorJob: an async failure waits for its awaiter.
        val supervised = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val finish = supervised.startLiveFinish(StandardTestDispatcher(testScheduler)) {
            throw StrokeRenderException("readback")
        }
        advanceUntilIdle()
        val thrown = runCatching { finish.await() }.exceptionOrNull()
        assertTrue("got $thrown", thrown is StrokeRenderException)
    }

    @Test
    fun `scope is the caller's so closing the editor cancels it`() = runTest {
        val scope = TestScope(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val finish = scope.startLiveFinish(StandardTestDispatcher(testScheduler)) { gate.await() }
        advanceUntilIdle()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        advanceUntilIdle()
        assertTrue(finish.isCancelled)
    }
}
