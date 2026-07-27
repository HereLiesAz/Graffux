package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType

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

    /**
     * Reorders just the layers whose ids appear in [newSubOrder] (bottom-first, matching [reorder]'s
     * own convention), leaving every other layer's list position untouched — the group-aware
     * evolution of [reorder]: a layer rail relocate is scoped per host now (top-level layers, or one
     * group's own children), so the order it reports only ever covers that host's members, never the
     * whole flat list, which [reorder]'s all-or-nothing full-coverage contract would just refuse.
     * Still refuses (returns [layers] unchanged) on a duplicate id or one that doesn't match any
     * layer — the same "a malformed order is a no-op, not data loss" stance as [reorder].
     */
    fun reorderSubset(layers: List<Layer>, newSubOrder: List<String>): List<Layer> {
        if (newSubOrder.toSet().size != newSubOrder.size) return layers
        val byId = layers.associateBy { it.id }
        if (!newSubOrder.all { it in byId }) return layers
        val slots = layers.indices.filter { layers[it].id in newSubOrder }
        if (slots.size != newSubOrder.size) return layers
        val result = layers.toMutableList()
        slots.forEachIndexed { i, slotIndex -> result[slotIndex] = byId.getValue(newSubOrder[i]) }
        return result
    }

    /**
     * Groups [aId] and [bId] under a new [LayerType.GROUP] layer with id [newGroupId] — Procreate's
     * "pinch to group" between two adjacent layers, exposed here as an explicit action since the
     * rail has no pinch gesture. The group is inserted at [aId]'s position (its list index becomes
     * the group's) and both members are reparented onto it; buildLayerTree does the rest — a
     * layer's own position in the flat list only matters for the relative order among siblings
     * sharing a parent, not for where in the list it physically sits. A no-op (returns [layers]
     * unchanged) if either id is missing, [newGroupId] collides with an existing id, or they're
     * already grouped together — the same "refuse a mismatch rather than corrupt the list" stance
     * as [reorder].
     */
    fun group(layers: List<Layer>, aId: String, bId: String, newGroupId: String, groupName: String): List<Layer> {
        val a = layers.find { it.id == aId } ?: return layers
        val b = layers.find { it.id == bId } ?: return layers
        if (aId == bId || layers.any { it.id == newGroupId }) return layers
        if (a.parentId != null && a.parentId == b.parentId) return layers
        val groupLayer = Layer(id = newGroupId, name = groupName, type = LayerType.GROUP, parentId = a.parentId)
        return layers.map {
            when (it.id) {
                aId, bId -> it.copy(parentId = newGroupId)
                else -> it
            }
        }.flatMap { if (it.id == aId) listOf(groupLayer, it) else listOf(it) }
    }

    /**
     * Dissolves the [LayerType.GROUP] layer [groupId]: its direct children are reparented onto the
     * group's own parent (so a nested group's children move up one level rather than jumping to the
     * root), and the now-empty group layer is removed. A no-op if [groupId] doesn't name an actual
     * group.
     */
    fun ungroup(layers: List<Layer>, groupId: String): List<Layer> {
        val group = layers.find { it.id == groupId && it.type == LayerType.GROUP } ?: return layers
        return layers.mapNotNull {
            when {
                it.id == groupId -> null
                it.parentId == groupId -> it.copy(parentId = group.parentId)
                else -> it
            }
        }
    }
}
