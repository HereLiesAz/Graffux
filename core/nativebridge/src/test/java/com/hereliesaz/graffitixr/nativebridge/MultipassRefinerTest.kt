package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipassRefinerTest {
    private val posted = ArrayDeque<() -> Unit>()
    private val scheduled = ArrayDeque<Pair<Long, () -> Unit>>()
    private val remaining = HashMap<Long, Int>()
    private val calls = ArrayList<Long>()

    private val refiner = MultipassRefiner(
        post = { posted.addLast(it) },
        schedule = { delay, task -> scheduled.addLast(delay to task) },
        tickMs = 16L,
    ) { handle ->
        calls += handle
        val left = (remaining[handle] ?: 0) - 1
        remaining[handle] = left
        if (left > 0) 1 else 0
    }

    private fun drainPosted() {
        while (posted.isNotEmpty()) posted.removeFirst().invoke()
    }

    @Test
    fun oneQueuedCallPerHandleNoMatterHowManyFrames() {
        remaining[1L] = 1
        refiner.frameDone(1L)
        refiner.frameDone(1L)
        refiner.frameDone(1L)
        assertEquals(1, posted.size)
        drainPosted()
        assertEquals(listOf(1L), calls)
        assertFalse(refiner.isQueued(1L))
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun keepsTickingWhileWorkRemainsThenStops() {
        remaining[7L] = 4
        refiner.frameDone(7L)
        drainPosted()
        var ticks = 0
        while (scheduled.isNotEmpty()) {
            val (delay, task) = scheduled.removeFirst()
            assertEquals(16L, delay)
            task()
            drainPosted()
            ticks++
            assertTrue(ticks < 100)
        }
        assertEquals(3, ticks)
        assertEquals(4, calls.size)
        assertFalse(refiner.isQueued(7L))
    }

    @Test
    fun aFrameDuringAScheduledTickDoesNotDoubleQueue() {
        remaining[3L] = 5
        refiner.frameDone(3L)
        drainPosted()
        assertEquals(1, scheduled.size)
        refiner.frameDone(3L) // a new batch was read back before the tick fired
        assertTrue(posted.isEmpty())
        assertTrue(refiner.isQueued(3L))
    }

    @Test
    fun handlesAreIndependent() {
        remaining[1L] = 1
        remaining[2L] = 1
        refiner.frameDone(1L)
        refiner.frameDone(2L)
        drainPosted()
        assertEquals(listOf(1L, 2L), calls)
    }
}
