package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo's GPU swap is queued, not awaited: the caller returns while the real render thread is held, order is kept. */
class GpuUndoSwapAsyncTest {
    @Test
    fun `undo undo redo return at once while the first swap is blocked and run in order afterwards`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        GpuRenderThread.post {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val t0 = System.nanoTime()
        // No native library on the JVM: every swap "fails", which is what reports the order they ran in.
        GpuUndoSnapshots.swapAsync(GpuUndoSnapshots.Ref(1L, 1L), 1L, 2L) { order += "undo1" }
        GpuUndoSnapshots.swapAsync(GpuUndoSnapshots.Ref(1L, 2L), 2L, 3L) { order += "undo2" }
        GpuUndoSnapshots.swapAsync(GpuUndoSnapshots.Ref(1L, 2L), 3L, 4L) { order += "redo" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("three swaps queued in $ms ms", ms < 200)
        assertTrue("none ran while the render thread was held", order.isEmpty())
        release.countDown()
        GpuRenderThread.flush()
        assertEquals(listOf("undo1", "undo2", "redo"), order.toList())
    }
}
