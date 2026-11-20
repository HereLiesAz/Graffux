// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/GpuRenderThread.kt
package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The one thread every wgpu engine call runs on (see [GpuStampEngine]): an ordered FIFO queue.
 *
 * * **Order.** Work runs in submission order, so a stroke's dab batches reach the GPU in the order
 *   they were generated, a stroke's commit-time resident refresh runs after every batch of that
 *   stroke, and an invalidation posted by undo runs after whatever GPU work was already queued.
 * * **Should never be the main thread.** Stamping and readback block on the GPU; callers are meant to be the
 *   stroke workers, and teardown, undo swaps and surface changes from the main thread [post] instead of waiting.
 *   Nothing enforces it: a blocking [call] on the main thread is logged once per caller (see [call]).
 * * **One owner.** Resident layers live in pooled native engines that several strokes and the
 *   commit path touch over time. Confining every native call to this thread makes that sharing
 *   safe without a lock around each handle.
 *
 * Vulkan and GLES engines do not use it; their threading is unchanged.
 */
object GpuRenderThread {
    @Volatile private var thread: Thread? = null

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            tid = runCatching { android.os.Process.myTid() }.getOrDefault(0)
            runnable.run()
        }, "graffux-gpu").also {
            it.isDaemon = true
            thread = it
        }
    }

    /** Linux thread id of the render thread once it has started (0 before), for ADPF hint sessions. */
    @Volatile var tid: Int = 0
        private set

    /**
     * Told how much CPU time each unit of render-thread work took (nanoseconds of this thread's
     * CPU time, not wall time). Set by the ADPF `PerformanceHintManager` session (feature/editor
     * `RenderThreadHints`); null = nobody listens. A unit is mostly a wait for the GPU (an upload,
     * a readback, a multipass drain); reporting that wall time against a one-frame target would
     * push the governor to raise CPU clocks, costing battery without shortening a GPU-bound wait.
     */
    @Volatile var workObserver: ((Long) -> Unit)? = null

    /** This thread's CPU time in nanoseconds, or a negative value where it is unavailable. */
    private fun threadCpuNanos(): Long = runCatching { android.os.Debug.threadCpuTimeNanos() }.getOrDefault(-1L)

    private inline fun <T> observed(block: () -> T): T {
        val observer = workObserver ?: return block()
        val start = threadCpuNanos()
        try {
            return block()
        } finally {
            val end = threadCpuNanos()
            if (start >= 0 && end >= start) runCatching { observer(end - start) }
        }
    }

    /** True on the render thread itself. */
    val isCurrent: Boolean get() = Thread.currentThread() === thread

    /**
     * Runs [block] on the render thread after everything queued before it and returns its result,
     * blocking the caller. Inline when already on the render thread. Exceptions are rethrown.
     */
    fun <T> call(block: () -> T): T {
        if (isCurrent) return block()
        warnIfMainThread()
        return try {
            executor.submit(Callable { observed(block) }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private val warnedSites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A blocking [call] on the main thread waits behind whatever the render thread is doing, including a readback wait
     * of up to the first-use allowance on a cold device: an ANR. Logged once per call site, with the stack, so the
     * offender is named in logcat. (A guard, not an assertion: a mis-threaded call must still work.)
     */
    private fun warnIfMainThread() {
        val onMain = runCatching { android.os.Looper.getMainLooper() === android.os.Looper.myLooper() }
            .getOrDefault(false)
        // The stack is captured only for the first few main-thread calls (a Throwable per call would itself cost the
        // frame it is warning about); each distinct caller is logged once, whichever of those it is.
        if (!onMain || mainCalls.incrementAndGet() > MAIN_CALL_SAMPLES) return
        val trace = Throwable("main-thread GPU call")
        val site = trace.stackTrace.firstOrNull { !it.className.startsWith(OWN_PACKAGE) }
            ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        if (warnedSites.add(site)) {
            android.util.Log.w(
                "GpuRenderThread",
                "BLOCKING GPU call on the MAIN thread at $site: it waits behind the render thread (a readback wait " +
                    "can hold it for seconds)",
                trace,
            )
        }
    }

    private val mainCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAIN_CALL_SAMPLES = 64
    private const val OWN_PACKAGE = "com.hereliesaz.graffitixr.nativebridge."

    /** Queues [block] after everything already queued; does not wait. Failures are logged-and-dropped. */
    fun post(block: () -> Unit) {
        executor.execute {
            // A posted teardown/refresh must never kill the queue for every later stroke.
            runCatching { observed(block) }.onFailure { System.err.println("GpuRenderThread: posted work failed: $it") }
        }
    }

    /** Waits until everything queued so far has run (tests, and callers that need a barrier). */
    fun flush() {
        call { }
    }
}
