package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke that ends before its live GPU engine is ready: the setup finishes later and used to
 * destroy the engine it had built ("commit replays instead"), so the commit built a second one.
 * The engine is now parked for the commit. Fakes only: the engine is an opaque token here.
 */
class SupersededEnginesTest {
    private val released = ArrayList<String>()
    private val engines = SupersededEngines<String> { released += it }

    @Test
    fun `a setup that finishes after the end parks its engine and the commit gets that very engine`() = runTest {
        engines.expect(7L) // the stroke ended; its commit will claim
        val setup = CompletableDeferred<Unit>()
        val claimed = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSupersededEngine(engines, setup, 7L)
        }
        assertFalse("the commit waits for the setup", claimed.isCompleted)
        assertTrue("setup finishes late and parks", engines.offer(7L, "engine-A"))
        setup.complete(Unit)
        assertSame("engine-A", claimed.await())
        assertTrue("nothing was destroyed, nothing second was built", released.isEmpty())
    }

    @Test
    fun `an engine offered with no commit waiting is refused so its setup destroys it`() {
        assertFalse(engines.offer(1L, "cancelled-stroke"))
        engines.expect(2L)
        assertNull(engines.claim(2L)) // commit gave up first (timeout / undo)
        assertFalse("late setup after the claim", engines.offer(2L, "late"))
    }

    @Test
    fun `an unfinished setup delays the commit by a bounded time only and then loses the engine`() = runTest {
        val logs = ArrayList<String>()
        engines.expect(3L)
        val never = CompletableDeferred<Unit>()
        assertNull(awaitSupersededEngine(engines, never, 3L, maxWaitMs = 50L, log = { logs += it }))
        assertTrue(logs.single().contains("timeout"))
        assertFalse("the late engine must be destroyed by its setup", engines.offer(3L, "late"))
    }

    @Test
    fun `engines are keyed by stroke`() {
        engines.expect(1L)
        engines.expect(2L)
        assertTrue(engines.offer(2L, "two"))
        assertTrue(engines.offer(1L, "one"))
        assertEquals("one", engines.claim(1L))
        assertEquals("two", engines.claim(2L))
    }

    @Test
    fun `discardAll releases what is parked and forgets the expectations`() {
        engines.expect(1L)
        engines.offer(1L, "parked")
        engines.expect(2L)
        engines.discardAll()
        assertEquals(listOf("parked"), released)
        assertFalse(engines.offer(2L, "after"))
        assertNull(engines.claim(1L))
    }

    @Test
    fun `no setup means no wait and no log`() = runTest {
        val logs = ArrayList<String>()
        engines.expect(9L)
        assertNull(awaitSupersededEngine(engines, null, 9L, log = { logs += it }))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a stroke starting before the previous one's engine is ready does not cut or take it`() {
        // Stroke 1 ended before its live setup finished; stroke 2 starts (and even ends) meanwhile.
        engines.expect(1L)
        engines.expect(2L)
        assertTrue("stroke 1's late engine still parks", engines.offer(1L, "engine-1"))
        assertEquals("stroke 2's claim leaves stroke 1's engine alone", null, engines.claim(2L))
        assertTrue(released.isEmpty())
        assertEquals("stroke 1's commit still gets its own engine", "engine-1", engines.claim(1L))
    }
}
