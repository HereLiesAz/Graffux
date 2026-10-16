package com.hereliesaz.graffitixr.feature.editor

/**
 * Once-per-key latch for warming the GPU document engine before the first touch.
 *
 * The first stroke of a project used to pay for wgpu device + pipeline creation (and the document
 * handle's first upload) while the finger was already down. Warming it as soon as a layer is active
 * moves that off the touch path; this latch makes sure only one warm-up per (size, tuning epoch)
 * runs, so the live setup, the commit's engine and the warm-up never cold-init two devices at once.
 */
internal class GpuWarmLatch {
    data class Key(val width: Int, val height: Int, val epoch: Int)

    private val lock = Any()
    private val warmed = HashSet<Key>()

    /** True for the single caller that should warm [key]; false once it was claimed and not [release]d. */
    fun claim(key: Key): Boolean = synchronized(lock) { warmed.add(key) }

    /** Lets a failed warm-up be tried again. */
    fun release(key: Key) {
        synchronized(lock) { warmed.remove(key) }
    }

    /** Forget everything (project closed, GPU torn down). */
    fun reset() {
        synchronized(lock) { warmed.clear() }
    }
}
