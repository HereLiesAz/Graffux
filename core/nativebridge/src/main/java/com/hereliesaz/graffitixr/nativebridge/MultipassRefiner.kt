// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/MultipassRefiner.kt
package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps multipass refinement going on wgpu engines (experimental; core/wgpu-engine multipass.rs).
 *
 * Drafts render inside every stamp/readback call already; full-quality work runs only in the time
 * left over. After a frame is presented on a handle ([frameDone]), one refinement call is queued
 * on the render thread behind it: it uses the rest of that frame (the engine measures it) and never
 * more than that, so the next frame's batch waits at most one small chunk. While work remains, a
 * tick every [tickMs] queues another, so refinement continues when the finger rests. One call per
 * handle is ever queued at a time.
 *
 * [post] queues on the render thread, [schedule] runs a task after a delay (any thread), [refine]
 * runs on the render thread and returns 1 while work remains (the engine's contract).
 */
internal class MultipassRefiner(
    private val post: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val refine: (Long) -> Int,
) {
    private val queued: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** A frame was just read back from [handle]. */
    fun frameDone(handle: Long) {
        if (queued.add(handle)) post { run(handle) }
    }

    /** Whether a refinement call for [handle] is queued or scheduled (tests). */
    fun isQueued(handle: Long): Boolean = handle in queued

    private fun run(handle: Long) {
        queued.remove(handle)
        val more = refine(handle) == 1
        if (more && queued.add(handle)) {
            schedule(tickMs) { post { run(handle) } }
        }
    }

    companion object {
        const val DEFAULT_TICK_MS = 16L
    }
}
