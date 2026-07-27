package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Pure, stateless transforms over the editor's layer list. Extracted from EditorViewModel so the
 * reorder / rename / visibility / single-layer-update logic has one unit-testable home with no
 * Android, Compose, or OpenCV dependencies. The ViewModel applies the returned list to its UiState.
 */
internal object LayerListOps {

    /**
     * Reorders [layers] to match [newOrder] (by id).
     *
     * Falls back to the original [layers] unchanged if [newOrder] doesn't account for every
     * layer — e.g. an id-scheme mismatch between the caller and this list (the rail's own item
     * ids carry a prefix this doesn't) previously matched nothing and this returned an empty
     * list, which the caller then persisted, permanently deleting every layer from a single
     * drag. A mismatched reorder request is far more likely than an artwork with zero layers, so
     * refusing it — a no-op the user can simply try again — is the safe default.
     */
    fun reorder(layers: List<Layer>, newOrder: List<String>): List<Layer> {
        val byId = layers.associateBy { it.id }
        val reordered = newOrder.mapNotNull { byId[it] }
        return if (reordered.size == layers.size) reordered else layers
    }

    /** Applies [transform] to the layer with [id], leaving every other layer untouched. */
    fun mapLayer(layers: List<Layer>, id: String, transform: (Layer) -> Layer): List<Layer> =
        layers.map { if (it.id == id) transform(it) else it }

    fun rename(layers: List<Layer>, id: String, name: String): List<Layer> =
        mapLayer(layers, id) { it.copy(name = name) }

    fun toggleVisibility(layers: List<Layer>, id: String): List<Layer> =
        mapLayer(layers, id) { it.copy(isVisible = !it.isVisible) }
}
