package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke that ends before its live GPU engine is ready leaves the setup running; the commit then
 * paints the stroke again from its recorded dabs on an engine of its own. That replay must start
 * only once the setup has finished, or two cold GPU devices are created side by side (logcat:
 * "live engine setup superseded after 461ms ... commit replays instead", then "failed to paint").
 */
class LiveSetupWaitTest {

    @Test
    fun `the replay starts only after the superseded setup has finished`() = runTest {
        val events = ArrayList<String>()
        val setup = CompletableDeferred<Unit>()
        val replay = async(start = CoroutineStart.UNDISPATCHED) {
            replayAfterSetup(setup, log = { events += "log: $it" }) {
                events += "replay"
                "painted"
            }
        }
        events += "setup running"
        assertTrue("the replay must not start while the setup runs", "replay" !in events)
        events += "setup done"
        setup.complete(Unit)
        assertEquals("painted", replay.await())
        assertEquals(listOf("setup running", "setup done"), events.take(2))
        assertEquals("replay", events.last())
        assertTrue(events.any { it.startsWith("log: commit: waited") })
    }

    @Test
    fun `a setup that never finishes delays the replay by a bounded time only`() = runTest {
        val logs = ArrayList<String>()
        val never = CompletableDeferred<Unit>()
        val out = replayAfterSetup(never, maxWaitMs = 50L, log = { logs += it }) { "painted" }
        assertEquals("painted", out)
        assertTrue(logs.single().contains("still running"))
    }

    @Test
    fun `no setup, or one already finished, adds no wait and no log`() = runTest {
        val logs = ArrayList<String>()
        assertEquals(1, replayAfterSetup(null, log = { logs += it }) { 1 })
        val done = CompletableDeferred<Unit>().also { it.complete(Unit) }
        assertEquals(2, replayAfterSetup(done, log = { logs += it }) { 2 })
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a replay failure propagates unchanged`() = runTest {
        val failure = runCatching {
            replayAfterSetup<Unit>(null) { throw StrokeRenderException("boom") }
        }.exceptionOrNull()
        assertTrue("got $failure", failure is StrokeRenderException)
    }
}
