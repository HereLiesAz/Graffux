package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay surface reaches the render thread without the (main) caller waiting for a busy one. */
class SurfaceApplierTest {
    private val fakeRenderThread = Executors.newSingleThreadExecutor()
    private val applied = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val applier = SurfaceApplier<String>(
        post = { fakeRenderThread.execute(it) },
        apply = { s, w, h -> applied += "$s ${w}x$h" },
        destroyWaitMs = 100,
    )

    private fun blockRenderThread(): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        fakeRenderThread.execute {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return release
    }

    @Test
    fun `surfaceChanged returns at once while the render thread is blocked, and the latest size wins`() {
        val release = blockRenderThread()
        val t0 = System.nanoTime()
        applier.changed("S", 100, 100)
        applier.changed("S", 200, 300)
        applier.changed("S", 400, 800)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("returned in $ms ms", ms < 100)
        assertTrue("nothing applied while blocked", applied.isEmpty())
        release.countDown()
        fakeRenderThread.submit { }.get(5, TimeUnit.SECONDS)
        assertEquals("only the latest request is applied", listOf("S 400x800"), applied.toList())
    }

    @Test
    fun `surfaceDestroyed waits only a bounded time on a blocked render thread, then still applies`() {
        val release = blockRenderThread()
        val t0 = System.nanoTime()
        val inTime = applier.destroyed()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertFalse("the render thread was busy", inTime)
        assertTrue("bounded: returned in $ms ms", ms < 1_000)
        release.countDown()
        fakeRenderThread.submit { }.get(5, TimeUnit.SECONDS)
        assertEquals("the release still happens, late", listOf("null 0x0"), applied.toList())
    }

    @Test
    fun `surfaceDestroyed on an idle render thread is applied before it returns`() {
        applier.changed("S", 10, 10)
        assertTrue(applier.destroyed())
        assertEquals("null 0x0", applied.last())
    }
}
