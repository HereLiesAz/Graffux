package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-layer bitmap/stroke caches for the editor, extracted from EditorViewModel so the cache
 * bookkeeping has one named, thread-safe home instead of ~30 scattered raw-map accesses.
 *
 * - [baseBitmaps] holds each layer's unedited base bitmap (strokes are replayed onto a copy of it).
 * - [layerStrokes] holds the ordered brush strokes applied to each layer.
 *
 * ConcurrentHashMap because these are read/written from the project-collect coroutine, stroke
 * handlers, and the main thread (the previous plain maps threw ConcurrentModificationException).
 * The map only protects its own structure, though — each per-layer stroke list is additionally
 * guarded by synchronizing on the list itself, and [strokes] hands out snapshot copies, never
 * the live list (background compositing iterates while strokes land on other threads).
 * The compositing of base + strokes into a display bitmap stays in the ViewModel — it needs the
 * UiState, OpenCV/SLAM, and persistence — this class only owns the caches.
 */
internal class LayerStore {
    private val baseBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val layerStrokes = ConcurrentHashMap<String, MutableList<StrokeCommand>>()
    private val heightBases = ConcurrentHashMap<String, FloatArray>()

    /** Stores [bitmap] as the base for [layerId]. Callers pass a defensive copy if needed. */
    fun putBase(layerId: String, bitmap: Bitmap) {
        baseBitmaps[layerId] = bitmap
    }

    /**
     * The pristine paint-thickness base for [layerId] (roadmap item 12) — the height map baked
     * strokes have already contributed, before replaying whatever's left in [strokes]. Lazily
     * allocated at [size] (`width * height`) zeros on first request rather than at every one of
     * the many layer-creation call sites [putBase] has, since most layers never use Impasto at
     * all; self-heals by reallocating if a stale cached array's size no longer matches (e.g. a
     * layer id somehow reused at a different size).
     */
    fun heightBase(layerId: String, size: Int): FloatArray {
        val existing = heightBases[layerId]
        if (existing != null && existing.size == size) return existing
        val fresh = FloatArray(size)
        heightBases[layerId] = fresh
        return fresh
    }

    /** Replaces [layerId]'s height base — the caller bakes stale strokes' height contribution
     *  into it the same way [takeOldestStrokes] bakes their pixels into the bitmap base. */
    fun putHeightBase(layerId: String, heightMap: FloatArray) {
        heightBases[layerId] = heightMap
    }

    /** Resets [layerId]'s stroke list to empty. */
    fun initStrokes(layerId: String) {
        layerStrokes[layerId] = mutableListOf()
    }

    fun base(layerId: String): Bitmap? = baseBitmaps[layerId]

    /**
     * The strokes for [layerId] in application order (empty if the layer is unknown).
     *
     * Returns a snapshot copy: the live list keeps mutating on other threads (stroke handlers on
     * main, spectator-op replay) while DrawingEngine.composite iterates the result on
     * Dispatchers.Default — handing out the live list threw ConcurrentModificationException.
     * Stroke lists are small path metadata, so the copy is negligible next to compositing.
     */
    fun strokes(layerId: String): List<StrokeCommand> {
        val list = layerStrokes[layerId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /** Appends [command] to [layerId]'s strokes, creating the list if absent. */
    fun addStroke(layerId: String, command: StrokeCommand) {
        // computeIfAbsent, not getOrPut: getOrPut on a ConcurrentHashMap is check-then-act, so two
        // racing first-strokes could each install their own list and one stroke would vanish.
        val list = layerStrokes.computeIfAbsent(layerId) { mutableListOf() }
        synchronized(list) { list.add(command) }
    }

    /**
     * Removes the most recent stroke for [layerId]. Returns false if the layer has no stroke list
     * at all (preserving the caller's early-return on an unknown layer); true otherwise, even when
     * the list was already empty.
     */
    fun removeLastStroke(layerId: String): Boolean {
        val list = layerStrokes[layerId] ?: return false
        synchronized(list) {
            if (list.isNotEmpty()) list.removeAt(list.lastIndex)
        }
        return true
    }

    /** How many strokes are queued for replay on [layerId]. */
    fun strokeCount(layerId: String): Int {
        val list = layerStrokes[layerId] ?: return 0
        return synchronized(list) { list.size }
    }

    /**
     * Removes and returns the oldest [count] strokes for [layerId] — the caller bakes them into the
     * base bitmap so they never need replaying again.
     *
     * Every layer replays its *entire* stroke list to rebuild, so an unbounded list makes each undo
     * cost O(strokes) full-bitmap composites and holds every recorded path (and stamp bitmap)
     * forever. Strokes older than the undo depth can never be reached again, so folding them into
     * the base is pure profit: identical pixels, bounded work, bounded memory.
     *
     * Returns an empty list if there is nothing to bake, so the caller can skip the work entirely.
     */
    fun takeOldestStrokes(layerId: String, count: Int): List<StrokeCommand> {
        if (count <= 0) return emptyList()
        val list = layerStrokes[layerId] ?: return emptyList()
        return synchronized(list) {
            val take = minOf(count, list.size)
            if (take <= 0) return@synchronized emptyList()
            val taken = ArrayList<StrokeCommand>(take)
            repeat(take) { taken.add(list.removeAt(0)) }
            taken
        }
    }

    /** Drops all caches for [layerId]. */
    fun remove(layerId: String) {
        baseBitmaps.remove(layerId)
        layerStrokes.remove(layerId)
        heightBases.remove(layerId)
    }

    /** Clears all cached bitmaps, strokes, and height bases (e.g. on project unload). */
    fun clear() {
        baseBitmaps.clear()
        layerStrokes.clear()
        heightBases.clear()
    }

    /** Evicts cached entries for layer IDs that are no longer active or referenced in history. */
    fun retainOnly(liveIds: Set<String>) {
        baseBitmaps.keys.retainAll(liveIds)
        layerStrokes.keys.retainAll(liveIds)
        heightBases.keys.retainAll(liveIds)
    }
}
