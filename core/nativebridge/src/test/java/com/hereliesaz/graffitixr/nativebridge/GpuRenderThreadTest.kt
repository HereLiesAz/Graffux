package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The ordered queue every wgpu engine call runs on. */
class GpuRenderThreadTest {

    @Test
    fun `work runs in submission order across posting threads, and call waits for earlier posts`() {
        val order = Collections.synchronizedList(ArrayList<Int>())
        val gate = CountDownLatch(1)
        GpuRenderThread.post { gate.await(5, TimeUnit.SECONDS) } // hold the queue
        for (i in 0 until 50) GpuRenderThread.post { order += i }
        gate.countDown()
        // call() is a barrier: it returns only after everything posted before it ran.
        val seen = GpuRenderThread.call { order.size }
        assertEquals(50, seen)
        assertEquals((0 until 50).toList(), order.toList())
    }

    @Test
    fun `call returns the block's value, runs off the caller's thread, and is inline on the render thread`() {
        assertFalse(GpuRenderThread.isCurrent)
        val caller = Thread.currentThread()
        val ran = GpuRenderThread.call { Thread.currentThread() }
        assertTrue(ran !== caller)
        val nested = GpuRenderThread.call { GpuRenderThread.call { GpuRenderThread.isCurrent } }
        assertTrue("a nested call must run inline, not deadlock", nested)
    }

    @Test
    fun `a failing post does not stop later work`() {
        GpuRenderThread.post { error("boom") }
        assertEquals(7, GpuRenderThread.call { 7 })
    }

    @Test(expected = IllegalStateException::class)
    fun `call rethrows the block's exception`() {
        GpuRenderThread.call { error("boom") }
    }

    @Test
    fun `batches from a worker keep their order relative to a teardown posted by another thread`() {
        val order = Collections.synchronizedList(ArrayList<String>())
        val worker = thread {
            for (i in 0 until 20) GpuRenderThread.call { order += "batch$i" }
        }
        worker.join()
        GpuRenderThread.post { order += "destroy" }
        GpuRenderThread.flush()
        assertEquals("destroy", order.last())
        assertEquals((0 until 20).map { "batch$it" }, order.dropLast(1))
    }
}
