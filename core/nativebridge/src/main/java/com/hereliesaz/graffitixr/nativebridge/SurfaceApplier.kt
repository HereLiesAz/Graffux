package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Applies the overlay surface to the render thread without making the main thread wait for it.
 *
 * `surfaceChanged` (main thread, possibly many times while the window resizes) only records the latest request and
 * queues one apply; requests superseded before the render thread got to them are dropped (latest wins), so a busy
 * render thread (a readback wait of seconds) costs the UI nothing.
 *
 * `surfaceDestroyed` cannot be fully asynchronous: the SurfaceHolder contract says the surface must not be used after
 * the callback returns, and the wgpu swapchain holds it. So it waits for the render thread, but only for
 * [destroyWaitMs]. If
 * the render thread is busy past that, the callback returns anyway (the apply is still queued and runs as soon as the
 * thread is free): the swapchain then outlives the callback by that long, which risks a swapchain error or a dropped
 * frame on a surface that is already going away, not a freeze. [destroyed] returns false in that case.
 */
internal class SurfaceApplier<S>(
    private val post: (() -> Unit) -> Unit,
    private val apply: (S?, Int, Int) -> Unit,
    private val destroyWaitMs: Long = DESTROY_WAIT_MS,
) {
    private class Request<S>(val surface: S?, val width: Int, val height: Int)

    private val latest = AtomicReference<Request<S>?>(null)

    fun changed(surface: S, width: Int, height: Int) {
        latest.set(Request(surface, width, height))
        post { drain() }
    }

    /** True when the render thread applied it in time. */
    fun destroyed(): Boolean {
        val done = CountDownLatch(1)
        latest.set(Request(null, 0, 0))
        post {
            try {
                drain()
            } finally {
                done.countDown()
            }
        }
        return done.await(destroyWaitMs, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        latest.getAndSet(null)?.let { apply(it.surface, it.width, it.height) }
    }

    companion object {
        const val DESTROY_WAIT_MS = 150L
    }
}
