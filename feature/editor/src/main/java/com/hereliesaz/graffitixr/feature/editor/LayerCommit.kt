package com.hereliesaz.graffitixr.feature.editor

/**
 * The order a layer's bitmap and its material sidecar are committed in. They are two files, so no order can make
 * them change together; this order makes every crash recoverable instead:
 *  1. the OLD sidecar is removed first (always: even a layer ending with none must not keep the old stroke's),
 *  2. the bitmap is replaced (atomically),
 *  3. the NEW sidecar is written (atomically).
 * Dying after step 1 leaves the old bitmap with no sidecar; after step 2, the new bitmap with no sidecar; after step
 * 3, the new pair. Never a new bitmap with the old stroke's height and wetness, and never the reverse: a layer with no
 * sidecar simply reads as having no material state.
 */
internal class LayerCommit(
    private val deleteSidecar: suspend () -> Boolean,
    private val writeBitmap: suspend () -> Boolean,
    private val writeSidecar: suspend () -> Boolean,
) {
    /** Runs the steps in order and stops at the first that fails; true only if all of them succeeded. */
    suspend fun run(): Boolean {
        return deleteSidecar() && writeBitmap() && writeSidecar()
    }
}
