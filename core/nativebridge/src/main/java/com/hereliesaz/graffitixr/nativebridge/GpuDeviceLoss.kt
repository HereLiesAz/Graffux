package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPU device loss (driver reset, background reclaim). [GpuStampEngine] asks each engine at its
 * stroke's end whether its device survived; a lost one discards every engine -- pooled handles at
 * once, the document engine at its next lease -- and reports here. Nothing canonical is lost: the
 * CPU layers re-seed the fresh engines (resident layers miss and upload).
 */
object GpuDeviceLoss {
    private val epoch = AtomicInteger(0)

    /** Called on the render thread after every detected loss. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The number of losses detected so far; compare across GPU work with [since]. */
    fun epoch(): Int = epoch.get()

    /**
     * Whether a loss was detected since [epoch], after the loss checks already queued on the
     * render thread. Blocking: call off the main thread.
     */
    fun since(epoch: Int): Boolean {
        GpuRenderThread.call { }
        return this.epoch.get() != epoch
    }

    internal fun report() {
        epoch.incrementAndGet()
        listeners.forEach { runCatching { it() } }
    }
}
