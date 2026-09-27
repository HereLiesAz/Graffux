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
 * The order in which the layers rail declares its rows.
 *
 * A group layer is an `azRailSubHostItem` (a rail host that is itself a sub-item of its parent's
 * host), and its children are rail sub-items whose `hostId` is that group's id — never a nested
 * rail. Each host's direct children come first, top-first (the frontmost layer on top), and only
 * then the contents of any groups among them, level by level.
 *
 * The ordering matters because AzNavRail draws a host's children by filtering its item list on
 * `hostId` (so where a group's children sit relative to the group doesn't change the drawing),
 * but it decides what a drag can reorder by walking *contiguous* reloc items with the same
 * `hostId` (`RelocItemHandler.findCluster`). Declaring a group's children right after the group
 * would split its parent host's run of reloc items with foreign ones for no reason.
 */
internal fun layerRailRows(layers: List<Layer>): List<LayerRailRow> {
    val rows = mutableListOf<LayerRailRow>()
    fun declare(parentId: String?, hostId: String) {
        val siblings = layers.filter { it.parentId == parentId }.reversed()
        siblings.forEach { rows += LayerRailRow(it, hostId) }
        siblings.filter { it.type == LayerType.GROUP }.forEach { declare(it.id, layerRailId(it.id)) }
    }
    declare(null, LAYERS_HOST_ID)
    return rows
}
