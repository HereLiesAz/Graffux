package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType

/** The top-level layers host: an `azUnattachedHostItem`. */
internal const val LAYERS_HOST_ID = "grp.layers"

/** A layer's rail item id. For a group this is also the id of the rail host its children sit under. */
internal fun layerRailId(layerId: String): String = "layer.$layerId"

/** One layer row to declare in the rail: [layer] under the rail host [hostId]. */
internal data class LayerRailRow(val layer: Layer, val hostId: String) {
    val isGroup: Boolean get() = layer.type == LayerType.GROUP
}

/**
 * The order in which the layers rail declares its rows: depth-first, top-first (the frontmost
 * layer on top), each group immediately followed by its whole subtree.
 *
 * A group layer is an `azRailRelocSubHostItem` (a draggable rail host that is itself a sub-item of
 * its parent's host), and its children are rail sub-items whose `hostId` is that group's id —
 * never a nested rail.
 *
 * The ordering matters because AzNavRail (11.52+) treats a relocatable sub-host plus every item
 * *immediately after it* that descends from it as one block (`RelocItemHandler.blockEnd`): the
 * block is one slot of its parent's reloc cluster and moves as a unit when the group is dragged.
 * Declaring a group's children anywhere else would leave them out of its block, and would split
 * the parent host's run of reloc slots.
 */
internal fun layerRailRows(layers: List<Layer>): List<LayerRailRow> {
    val rows = mutableListOf<LayerRailRow>()
    fun declare(parentId: String?, hostId: String) {
        layers.filter { it.parentId == parentId }.reversed().forEach {
            rows += LayerRailRow(it, hostId)
            if (it.type == LayerType.GROUP) declare(it.id, layerRailId(it.id))
        }
    }
    declare(null, LAYERS_HOST_ID)
    return rows
}
