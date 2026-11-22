package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether a layer's own bitmap holds paint, for the snap's candidates and the nearest-edge reference
 * (spec/3d-space.md, "Snap to stroked planes" and "Draw plane depth").
 *
 * **Main thread only.** The result and in-flight maps are plain hash maps, read and written only from the main thread
 * (the view model's state writes, stroke start and the landing of a scan). A background coroutine touches nothing but
 * the
 * bitmap it was given; code that runs off the main thread (the flat-sheet planner) uses the pure [ArtScan.isEmpty]
 * instead and never this class.
 *
 * The scan is a pass over the bitmap, so it never runs on the caller's thread (a gesture frame on the main thread):
 * [painted] answers from the last result and, when there is none for this bitmap and generation, starts one scan on the
 * default dispatcher and answers false meanwhile. The scan checks for cancellation every few rows
 * ([ArtScan.isEmpty]), so
 * a cancelled scan stops at its next checkpoint rather than finishing. A result is keyed by (layer, bitmap identity,
 * generation) and is kept only if its job is still the layer's current one, the layer still holds that bitmap and the
 * bitmap is still at the scanned generation; a result for a project that was closed, a layer that was removed, a bitmap
 * that was swapped or a generation that has moved on is dropped without touching anything (and without [onLanded]), and
 * a result never replaces one for a newer generation of the same bitmap.
 *
 * A bitmap that cannot be read (recycled, bad size: `IllegalStateException`/`IllegalArgumentException`) counts as
 * unpainted for that generation and is tried again when its generation changes; a cancellation is never swallowed
 * (`CancellationException` is itself an `IllegalStateException`, so it is rethrown before that catch).
 */
internal class PaintScans(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    /** The layer still holds [Bitmap]: checked on the main thread before a result is kept. */
    private val isCurrent: (layerId: String, bitmap: Bitmap) -> Boolean,
    /** A result landed and was kept: the planes built without it are stale. */
    private val onLanded: (layerId: String, painted: Boolean) -> Unit,
) {
    /**
     * The scan itself: true when the bitmap holds paint; it calls the checkpoint between rows and lets its cancellation
     * through. A seam for tests (a counting spy).
     */
    var scan: (Bitmap, checkpoint: () -> Unit) -> Boolean =
        { bitmap, checkpoint -> !ArtScan.isEmpty(bitmap, checkpoint) }

    private class Running(val bitmap: Bitmap, val generation: Int) {
        var job: Job? = null
    }

    /** The answers, one per layer: the bitmap, the generation it was read at, and whether it held paint. */
    private val results = HashMap<String, Triple<Bitmap, Int, Boolean>>()
    private val running = HashMap<String, Running>()

    /** What [layerId]'s [bitmap] last scanned as, false until a result for this very bitmap and generation lands. */
    fun painted(layerId: String, bitmap: Bitmap?): Boolean {
        if (bitmap == null) {
            forget(layerId)
            return false
        }
        val known = cached(layerId, bitmap)
        if (known == null) scanIfNeeded(layerId, bitmap, bitmap.generationId)
        return known ?: false
    }

    /**
     * Starts the background scan of [bitmap] so the answer is there before it is asked for (a project opened with a
     * painted layer that has no recorded strokes). Nothing when this bitmap and generation already have a result or a
     * scan, and nothing while another scan of the layer is in flight (a warm-up never cancels one; its result is for a
     * generation that has moved on, so it is dropped and the next call scans the current one). A new generation (undo,
     * redo, an in-place edit) is scanned again.
     */
    fun warm(layerId: String, bitmap: Bitmap) {
        if (cached(layerId, bitmap) != null || running[layerId] != null) return
        scanIfNeeded(layerId, bitmap, bitmap.generationId)
    }

    /** The answer for [bitmap]'s current generation (true: painted), or null when there is none. */
    fun cached(layerId: String, bitmap: Bitmap): Boolean? =
        results[layerId]?.takeIf { it.first === bitmap && it.second == bitmap.generationId }?.third

    /**
     * Records an answer the caller knows for [bitmap]'s current generation (a bitmap it just made blank, or one it just
     * read), without the landing callback. A scan of this bitmap still in flight is cancelled: its answer is now known,
     * or is for an older generation.
     */
    fun record(layerId: String, bitmap: Bitmap, painted: Boolean) {
        running[layerId]?.takeIf { it.bitmap === bitmap }?.let {
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
                scan(bitmap) { ensureActive() }
            } catch (e: CancellationException) {
                throw e
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
        val newest = results[layerId]?.takeIf { it.first === entry.bitmap }?.second ?: Int.MIN_VALUE
        val stale = entry.bitmap.generationId != entry.generation || newest > entry.generation
        if (stale || !isCurrent(layerId, entry.bitmap)) return
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
