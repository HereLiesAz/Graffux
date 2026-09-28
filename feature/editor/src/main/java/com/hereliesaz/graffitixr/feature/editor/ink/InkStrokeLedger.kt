package com.hereliesaz.graffitixr.feature.editor.ink

/**
 * The committed Jetpack Ink strokes of each layer, kept in memory alongside the pixels they were
 * rendered into — the seed of a future vector layer, and what Export for Figma's Ink SVG reads.
 *
 * Separate from `LayerStore`'s stroke list on purpose: that list is a replay cache that
 * `maybeBakeOldStrokes` folds into the layer's base bitmap and discards, and the Ink geometry has
 * to outlive that. Entries are keyed by the identity of the
 * [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] that carried them (never by equality —
 * two strokes can compare equal), so Undo can take exactly the stroke it undid back out and Redo
 * can put it back, and the export always matches what the layer currently shows.
 *
 * Generic in the stroke type so it is testable without Ink's native library. Main-thread only,
 * like the undo/redo stacks it mirrors.
 */
internal class InkStrokeLedger<T : Any> {
    private class Entry<T>(val key: Any, val stroke: T)

    private val byLayer = HashMap<String, MutableList<Entry<T>>>()

    /** Records [stroke], committed to [layerId] by the command [key]. */
    fun add(layerId: String, key: Any, stroke: T) {
        byLayer.getOrPut(layerId) { mutableListOf() }.add(Entry(key, stroke))
    }

    /** Removes the stroke committed by [key] (identity). Returns whether one was found. */
    fun remove(layerId: String, key: Any): Boolean {
        val list = byLayer[layerId] ?: return false
        val index = list.indexOfLast { it.key === key }
        if (index >= 0) {
            list.removeAt(index)
            if (list.isEmpty()) byLayer.remove(layerId)
        }
        return index >= 0
    }

    /**
     * Undo of the history entry [key] on [layerId]: takes its stroke out if it carried one. Call it
     * only once the undo has actually been applied to the layer (a failed undo keeps its stroke).
     */
    fun onUndo(layerId: String, key: Any) {
        remove(layerId, key)
    }

    /** Redo of [key] on [layerId]: puts [stroke] back, or does nothing for a non-Ink entry (null). */
    fun onRedo(layerId: String, key: Any, stroke: T?) {
        if (stroke != null) add(layerId, key, stroke)
    }

    /** Layers that currently hold at least one stroke. */
    fun layerIds(): Set<String> = byLayer.keys.toSet()

    /** [layerId]'s strokes, oldest first. */
    fun strokes(layerId: String): List<T> = byLayer[layerId]?.map { it.stroke }.orEmpty()

    fun clear() = byLayer.clear()
}
