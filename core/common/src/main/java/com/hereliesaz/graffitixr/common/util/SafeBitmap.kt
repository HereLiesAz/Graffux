package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Bitmap allocation that fails softly.
 *
 * Every layer in the editor is a full-screen ARGB_8888 bitmap held twice over (the displayed copy
 * and its pristine base), and each stroke allocates another for its live preview. On a
 * high-resolution phone with several layers open, a single allocation is tens of megabytes against
 * a heap that is already nearly full — and a failed one took the whole app down mid-stroke, losing
 * the drawing.
 *
 * Catching [OutOfMemoryError] is the right call *here* specifically because the failure is
 * genuinely recoverable: nothing is half-written, the caller can abandon one operation, and the
 * document on disk is untouched. It is not a licence to swallow OOM anywhere else.
 *
 * Every function returns null on exhaustion rather than throwing, so callers must decide what
 * degrading gracefully means for them (skip the preview, keep the old pixels, show a message).
 */
object SafeBitmap {

    /**
     * [Bitmap.copy] that returns null instead of throwing when memory runs out. One retry after
     * prompting a collection: the first failure is often reclaimable garbage from superseded
     * stroke buffers, which the allocator won't wait for on its own.
     */
    fun copy(source: Bitmap, config: Bitmap.Config = Bitmap.Config.ARGB_8888, mutable: Boolean = true): Bitmap? =
        attempt { source.copy(config, mutable) }

    /** [Bitmap.createBitmap] that returns null instead of throwing when memory runs out. */
    fun create(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return attempt { Bitmap.createBitmap(width, height, config) }
    }

    private inline fun attempt(block: () -> Bitmap?): Bitmap? = try {
        block()
    } catch (first: OutOfMemoryError) {
        // Deliberately not logged as an error on the first pass — a reclaim usually rescues it, and
        // a crash report per near-miss would bury the ones that actually failed.
        System.gc()
        try {
            block()
        } catch (second: OutOfMemoryError) {
            android.util.Log.e("SafeBitmap", "Out of memory allocating a bitmap; skipping", second)
            null
        }
    }
}
