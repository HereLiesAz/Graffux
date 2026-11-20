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
 * frame on a surface that is already going away, not a freeze. [destroyed] returns false in that case. The release is
 * never lost: a `changed` arriving after the timeout is applied after it. Residual risk: if the render thread is
 * already inside an apply of the old surface when the timeout fires, the callback returns while native code still
 * uses that surface; the release follows when that apply ends.
 */
internal class SurfaceApplier<S>(
    private val post: (() -> Unit) -> Unit,
    private val apply: (S?, Int, Int) -> Unit,
    private val destroyWaitMs: Long = DESTROY_WAIT_MS,
) {
    private class Request<S>(val surface: S?, val width: Int, val height: Int)

    private val latest = AtomicReference<Request<S>?>(null)

    /** A destroy asked for the surface to be released and no apply has done it yet. Never dropped by a later change. */
    private val releaseOwed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun changed(surface: S, width: Int, height: Int) {
        latest.set(Request(surface, width, height))
        post { drain() }
    }

    /** True when the render thread applied the release in time. The release itself always happens (see class doc). */
    fun destroyed(): Boolean {
        val done = CountDownLatch(1)
        releaseOwed.set(true)
        latest.set(null)
        post {
            try {
                drain()
            } finally {
                done.countDown()
            }
        }
        return done.await(destroyWaitMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Render thread. A release owed by [destroyed] is applied first and unconditionally, even when a newer [changed]
     * arrived after the destroy timed out: the new surface is then applied after the old one was let go (the same
     * Surface object is usually reused on recreation, and the display only detaches when it sees a different one).
     */
    private fun drain() {
        val owed = releaseOwed.getAndSet(false)
        val next = latest.getAndSet(null)
        if (owed) apply(null, 0, 0)
        if (next?.surface != null) apply(next.surface, next.width, next.height)
    }

    companion object {
        const val DESTROY_WAIT_MS = 150L
    }
}
