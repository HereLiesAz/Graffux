package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether a layer's own bitmap holds paint, for the snap's candidates (spec/3d-space.md, "Snap to stroked planes").
 *
 * The scan is a full pass over the bitmap, so it never runs on the caller's thread (a gesture frame on the main
 * thread): [painted] answers from the last result and, when there is none for this bitmap and generation, starts one
 * scan on the default dispatcher and answers false meanwhile. A result is keyed by (layer, bitmap identity,
 * generation) and lands on the main dispatcher only if its job is still the layer's current one and the layer still
 * holds that bitmap; a result for a project that was closed, a layer that was removed or a bitmap that was swapped is
 * dropped without touching anything, and its job is cancelled when that is noticed ([retainOnly], [clear], a newer
 * scan).
 *
 * A bitmap that cannot be read (recycled, bad size: `IllegalStateException`/`IllegalArgumentException`) counts as
 * unpainted for that generation and is tried again when its generation changes; a cancellation is never swallowed.
 */
@Suppress("TooManyFunctions")
internal class PaintScans(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    /** The layer still holds [Bitmap]: checked on the main thread before a result is kept. */
    private val isCurrent: (layerId: String, bitmap: Bitmap) -> Boolean,
    /** A result landed and was kept: the planes built without it are stale. */
    private val onLanded: (layerId: String, painted: Boolean) -> Unit,
) {
    /** The scan itself: true when the bitmap holds paint. A seam for tests (a counting spy). */
    var scan: (Bitmap) -> Boolean = { !ArtScan.isEmpty(it) }

    private class Running(val bitmap: Bitmap, val generation: Int) {
        var job: Job? = null
    }

    private val results = HashMap<String, Triple<Bitmap, Int, Boolean>>()
    private val running = HashMap<String, Running>()

    /** What [layerId]'s [bitmap] last scanned as, false until a result for this very bitmap and generation lands. */
    fun painted(layerId: String, bitmap: Bitmap?): Boolean {
        if (bitmap == null) {
            forget(layerId)
            return false
        }
        val generation = bitmap.generationId
        val known = results[layerId]?.takeIf { it.first === bitmap && it.second == generation }
        if (known == null) scanIfNeeded(layerId, bitmap, generation)
        return known?.third ?: false
    }

    /**
     * Starts the background scan of [bitmap] so the answer is there before it is asked for (a project opened with a
     * painted layer that has no recorded strokes). One scan per (bitmap, generation): nothing when this bitmap and
     * generation already have a result or a scan, and nothing while another scan of the layer is still in flight (it is
     * never cancelled by a warm-up; a result for a generation that has moved on is not an answer, so the next call
     * after it lands scans the current one). A new generation (undo, redo, an in-place edit) is scanned again.
     */
    fun warm(layerId: String, bitmap: Bitmap) {
        val generation = bitmap.generationId
        if (results[layerId]?.let { it.first === bitmap && it.second == generation } == true) return
        if (running[layerId] != null) return
        scanIfNeeded(layerId, bitmap, generation)
    }

    /** The answer for [bitmap]'s current generation (true: painted), or null when there is none. */
    fun cached(layerId: String, bitmap: Bitmap): Boolean? =
        results[layerId]?.takeIf { it.first === bitmap && it.second == bitmap.generationId }?.third

    /** Whether [bitmap] has a landed or recorded answer for its current generation. */
    fun hasResult(layerId: String, bitmap: Bitmap): Boolean =
        results[layerId]?.let { it.first === bitmap && it.second == bitmap.generationId } == true

    /** Records an answer worked out synchronously by the caller (the stroke start), without the landing callback. */
    fun record(layerId: String, bitmap: Bitmap, painted: Boolean) {
        // The answer is known: a queued scan of this very bitmap and generation is not run (no layer scanned twice).
        running[layerId]?.takeIf { it.bitmap === bitmap && it.generation == bitmap.generationId }?.let {
            it.job?.cancel()
            running.remove(layerId)
        }
        results[layerId] = Triple(bitmap, bitmap.generationId, painted)
    }

    private fun scanIfNeeded(layerId: String, bitmap: Bitmap, generation: Int) {
        val inFlight = running[layerId]
        if (inFlight != null && inFlight.bitmap === bitmap && inFlight.generation == generation) return
        inFlight?.job?.cancel()
        start(layerId, bitmap, generation)
    }

    private fun start(layerId: String, bitmap: Bitmap, generation: Int) {
        val entry = Running(bitmap, generation)
        running[layerId] = entry
        entry.job = scope.launch(dispatchers.default) {
            val painted = try {
                scan(bitmap)
            } catch (_: IllegalStateException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
            withContext(dispatchers.main) { land(layerId, entry, painted) }
        }
    }

    private fun land(layerId: String, entry: Running, painted: Boolean) {
        if (running[layerId] !== entry) return
        running.remove(layerId)
        if (!isCurrent(layerId, entry.bitmap)) return
        results[layerId] = Triple(entry.bitmap, entry.generation, painted)
        onLanded(layerId, painted)
    }

    /** Forgets [layerId] (and cancels its scan): the layer was removed. */
    fun forget(layerId: String) {
        running.remove(layerId)?.job?.cancel()
        results.remove(layerId)
    }

    /** Forgets every layer not in [liveIds] (and cancels its scan). */
    fun retainOnly(liveIds: Set<String>) {
        (results.keys + running.keys).filter { it !in liveIds }.forEach(::forget)
    }

    /** A project opened or closed: nothing carries over. */
    fun clear() {
        running.values.forEach { it.job?.cancel() }
        running.clear()
        results.clear()
    }

    /** How many scans are in flight (tests). */
    val inFlight: Int get() = running.size
}
