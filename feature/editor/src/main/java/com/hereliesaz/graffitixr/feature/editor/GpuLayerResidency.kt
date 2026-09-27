// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/GpuLayerResidency.kt
package com.hereliesaz.graffitixr.feature.editor

import java.lang.ref.WeakReference

/**
 * Content generations for layers kept resident on the GPU across strokes (wgpu only; see
 * core/wgpu-engine/src/resident.rs and docs/Native Rendering Engine Design.md §2b).
 *
 * A stroke binds the GPU's copy of a layer only when the copy was made from exactly the pixels the
 * CPU layer holds now. That is decided by a generation number per layer:
 *
 * * [generationFor] is asked at stroke start with the layer's current bitmap. It keeps the
 *   generation only if that bitmap is the very object the generation was recorded for; any other
 *   object -- undo/redo, a filter, an import, a co-op op, a merge, anything that replaced the
 *   bitmap -- gets a fresh generation, so the GPU copy misses and is uploaded again. This is the
 *   backstop that catches every path that swaps the bitmap, wired or not.
 * * [invalidate] / [invalidateAll] are called from the mutation paths themselves (undo, redo,
 *   filters, import, co-op, layer operations, document switches) and from [LayerStore]. They also
 *   cover a bitmap changed in place, which identity alone cannot see.
 * * [adoptCommit] is the only way a generation survives a content change: the stroke's own commit,
 *   when nothing else changed the layer since the stroke began. The GPU copy is then refreshed
 *   from the committed pixels and tagged with the new generation.
 *
 * Generations come from one process-wide counter shared by every layer and every instance (a new
 * editor can reuse layer ids, and pooled native engines outlive it), so a generation never names
 * two contents. When unsure, callers invalidate: a spurious invalidation costs one upload,
 * a missed one would paint on stale pixels.
 *
 * [onInvalidate] (layer key, or null for all) lets the owner drop the GPU copies eagerly to free
 * memory; correctness never depends on it, because a bumped generation can never match.
 */
class GpuLayerResidency(
    private val onInvalidate: (layerKey: Long?) -> Unit = {},
) {
    private class Entry(var generation: Long, var source: WeakReference<Any>?)

    private val entries = HashMap<String, Entry>()

    /** The generation of [layerId]'s content if [bitmap] is its current pixels (see class doc). */
    @Synchronized
    fun generationFor(layerId: String, bitmap: Any): Long {
        val entry = entries[layerId]
        if (entry != null && entry.source?.get() === bitmap) return entry.generation
        val generation = nextGeneration()
        entries[layerId] = Entry(generation, WeakReference(bitmap))
        return generation
    }

    /** Current generation of [layerId], or null if no stroke has asked about it since the last change. */
    @Synchronized
    fun currentGeneration(layerId: String): Long? = entries[layerId]?.takeIf { it.source != null }?.generation

    /** True if a GPU copy tagged [generation] still matches [layerId]'s content. */
    @Synchronized
    fun isCurrent(layerId: String, generation: Long): Boolean = currentGeneration(layerId) == generation

    /** [layerId]'s CPU content changed outside a GPU stroke commit. */
    fun invalidate(layerId: String) {
        synchronized(this) {
            entries[layerId] = Entry(nextGeneration(), null)
        }
        onInvalidate(layerKey(layerId))
    }

    /** Every layer may have changed (document load/switch, flatten, undo of a layer-list change). */
    fun invalidateAll() {
        synchronized(this) {
            for (entry in entries.values) {
                entry.generation = nextGeneration()
                entry.source = null
            }
        }
        onInvalidate(null)
    }

    /**
     * A stroke that began at [expectedGeneration] on [base] committed, and [committed] is now the
     * layer. Returns the new generation to tag the refreshed GPU copy with, or null if the layer
     * changed in between (another generation, or a different base object) -- then the copy is left
     * stale and the next stroke uploads.
     */
    @Synchronized
    fun adoptCommit(layerId: String, expectedGeneration: Long, base: Any, committed: Any): Long? {
        val entry = entries[layerId]?.takeIf { it.generation == expectedGeneration && it.source?.get() === base }
        return entry?.let {
            it.generation = nextGeneration()
            it.source = WeakReference(committed)
            it.generation
        }
    }

    /** Forgets [layerId] entirely (layer deleted). */
    fun remove(layerId: String) {
        synchronized(this) { entries.remove(layerId) }
        onInvalidate(layerKey(layerId))
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong()

        private fun nextGeneration(): Long = counter.incrementAndGet()

        /** Stable 64-bit key for a layer id (FNV-1a over its UTF-16 units). */
        fun layerKey(layerId: String): Long {
            var h = FNV_OFFSET
            for (c in layerId) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            return h
        }

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L

        /**
         * Bounding box ({x, y, w, h}; all zero when identical) of the pixels that differ between
         * two equally sized ARGB arrays -- what a CPU commit changed relative to the pre-stroke
         * layer, which the GPU copy must re-upload besides the rows the stroke painted.
         */
        fun changedRect(before: IntArray, after: IntArray, width: Int, height: Int): IntArray {
            require(before.size >= width * height && after.size >= width * height) {
                "arrays smaller than $width x $height"
            }
            var top = -1
            var bottom = -1
            var left = width
            var right = -1
            for (y in 0 until height) {
                val span = differingSpan(before, after, y * width, width) ?: continue
                if (top < 0) top = y
                bottom = y
                left = minOf(left, span.first)
                right = maxOf(right, span.last)
            }
            return if (top < 0) IntArray(RECT_INTS) else intArrayOf(left, top, right - left + 1, bottom - top + 1)
        }

        /** First..last column where one row differs, or null when it is identical. */
        private fun differingSpan(before: IntArray, after: IntArray, rowStart: Int, width: Int): IntRange? {
            var first = 0
            while (first < width && before[rowStart + first] == after[rowStart + first]) first++
            if (first == width) return null
            var last = width - 1
            while (before[rowStart + last] == after[rowStart + last]) last--
            return first..last
        }

        private const val RECT_INTS = 4
    }
}
